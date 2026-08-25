package com.retrivedmods.wclient.game.registry

import android.content.Context
import org.cloudburstmc.nbt.NBTInputStream
import org.cloudburstmc.nbt.NbtList
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtUtils
import org.cloudburstmc.protocol.common.DefinitionRegistry
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.TreeMap
import java.util.zip.GZIPInputStream

class BlockMapping(
    private val runtimeToGameMap: Map<Int, BlockDefinition>
) : DefinitionRegistry<org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition> {

    private val gameToRuntimeMap = mutableMapOf<BlockDefinition, Int>()

    /** Number of distinct block states this mapping knows about. */
    val size: Int get() = runtimeToGameMap.size

    init {
        runtimeToGameMap.forEach { (k, v) -> gameToRuntimeMap[v] = k }
    }

    override fun getDefinition(runtimeId: Int): BlockDefinition {
        return runtimeToGameMap[runtimeId] ?: UnknownBlockDefinition(runtimeId)
    }

    override fun isRegistered(definition: org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition): Boolean {
        return definition is UnknownBlockDefinition || getDefinition(definition.runtimeId) == definition
    }

    /**
     * Returns the first runtime id whose identifier matches [identifier] (e.g. "minecraft:obsidian").
     * Block states with extra properties share the same base identifier, so this returns
     * the default/first state found, which is fine for simple full blocks like obsidian.
     */
    fun getRuntimeIdByIdentifier(identifier: String): Int? {
        return runtimeToGameMap.entries.firstOrNull { it.value.identifier == identifier }?.key
    }

    /**
     * Runtime id of "minecraft:air", used by chunk/block storage as the default/empty block.
     * Falls back to 0 if not found (shouldn't happen with a valid mapping).
     */
    val airId: Int by lazy { getRuntimeIdByIdentifier("minecraft:air") ?: 0 }

    companion object {
        /**
         * Builds a BlockMapping straight from the server's own StartGamePacket block palette,
         * instead of a bundled per-version asset file.
         *
         * The runtime id is NOT simply an entry's position in [palette] as sent by the server,
         * nor does BlockPropertyData carry an explicit runtime id field at all - it's just
         * `name: String` + `properties: NbtMap` (confirmed directly against the vendored
         * bedrock-codec source in relay/Protocol/bedrock-codec/.../data/BlockPropertyData.java).
         * Since Minecraft 1.18.30, the real runtime id assignment is: compute each block STATE's
         * (not just its name!) network hash, sort every entry by that hash ascending (as
         * unsigned), then assign sequential ids 0, 1, 2... in that sorted order.
         *
         * The previous version of this function got the hash itself wrong on three separate
         * counts - confirmed against a real reference implementation
         * (https://gist.github.com/Alemiz112/504d0f79feac7ef57eda174b668dd345, an example anyone
         * can check against a captured StartGamePacket):
         *  1. It hashed only [BlockPropertyData.name] - but many, many blocks (stairs, doors,
         *     anything with variants) share the same name across multiple palette entries that
         *     differ only in `properties`/states. All of those tied under the old name-only key,
         *     so which one landed at which index was arbitrary (whatever order the server's
         *     packet happened to list them in) instead of matching the server's real order -
         *     and since blocks are added one after another in the palette, this shifts basically
         *     every runtime id after the first tied group, not just the tied entries themselves.
         *  2. It used a 64-bit FNV-1a hash. The real algorithm is 32-bit.
         *  3. Its FNV-1a step order was backwards: `hash *= PRIME` THEN `hash = hash xor byte`.
         *     Real FNV-1a is XOR-first: `hash = hash xor byte` THEN `hash *= PRIME`. As written
         *     this was closer to plain FNV-1 with the steps still in the wrong order - not FNV-1a
         *     at all.
         *
         * Any one of these alone is enough to desync every runtime id from the server's real
         * assignment; together they explain both symptoms seen in testing: an item's own embedded
         * block form decoding as a completely unrelated block (e.g. holding obsidian but its
         * blockDefinition reading back as minecraft:birch_hanging_sign), and session.level
         * reporting "minecraft:unknown" for chunk data that really is loaded (SubChunkPacket block
         * indices get looked up against a mapping whose keys don't line up with what the server
         * actually sent).
         */
        fun fromPalette(palette: List<org.cloudburstmc.protocol.bedrock.data.BlockPropertyData>): BlockMapping {
            val runtimeToBlock = mutableMapOf<Int, BlockDefinition>()
            palette
                .sortedWith(compareBy(NetworkHashComparator) { it })
                .forEachIndexed { index, entry ->
                    runtimeToBlock[index] = BlockDefinition(index, entry.name)
                }
            return BlockMapping(runtimeToBlock)
        }

        /**
         * Real Bedrock network block-state hash (32-bit FNV-1a over the little-endian NBT bytes of
         * `{name, states}`, with `states` keys sorted alphabetically first via TreeMap so key order
         * always matches what the server hashed on its end regardless of the order properties
         * happened to arrive in). Ported directly from
         * https://gist.github.com/Alemiz112/504d0f79feac7ef57eda174b668dd345. Compared as unsigned
         * 32-bit values, matching the sort the server itself performs when assigning runtime ids.
         */
        private object NetworkHashComparator : Comparator<org.cloudburstmc.protocol.bedrock.data.BlockPropertyData> {
            private const val FNV1_32_INIT = 0x811c9dc5.toInt()
            private const val FNV1_PRIME_32 = 0x01000193

            override fun compare(
                a: org.cloudburstmc.protocol.bedrock.data.BlockPropertyData,
                b: org.cloudburstmc.protocol.bedrock.data.BlockPropertyData
            ): Int {
                return Integer.compareUnsigned(hash(a), hash(b))
            }

            private fun hash(entry: org.cloudburstmc.protocol.bedrock.data.BlockPropertyData): Int {
                // Special-cased by the reference implementation - never actually hit for a real
                // palette entry (the server doesn't list "minecraft:unknown" itself), kept only to
                // match the reference exactly.
                if (entry.name == "minecraft:unknown") return -2

                val sortedStates = TreeMap<String, Any>(entry.properties ?: NbtMap.builder().build())
                val tag = NbtMap.builder()
                    .putString("name", entry.name)
                    .putCompound("states", NbtMap.fromMap(sortedStates))
                    .build()

                val bytes = ByteArrayOutputStream().use { stream ->
                    NbtUtils.createWriterLE(stream).use { it.writeTag(tag) }
                    stream.toByteArray()
                }
                return fnv1a32(bytes)
            }

            private fun fnv1a32(data: ByteArray): Int {
                var hash = FNV1_32_INIT
                for (byte in data) {
                    hash = hash xor (byte.toInt() and 0xff)
                    hash *= FNV1_PRIME_32
                }
                return hash
            }
        }

        fun read(context: Context, version: Short): BlockMapping {
            val path = "mcpedata/blocks/runtime_block_states_$version.dat"
            context.assets.open(path).use { stream ->
                val gzipStream = GZIPInputStream(stream)
                val nbtInput = NBTInputStream(DataInputStream(gzipStream))

                @Suppress("unchecked_cast")
                val tag = nbtInput.readTag() as NbtList<NbtMap>
                val runtimeToBlock = mutableMapOf<Int, BlockDefinition>()

                tag.forEach { subtag ->
                    val runtime = subtag.getInt("runtimeId")
                    val name = subtag.getString("name")
                    runtimeToBlock[runtime] = BlockDefinition(runtime, name)
                }

                return BlockMapping(runtimeToBlock)
            }
        }
    }

}