package com.retrivedmods.wclient.game.world.chunk

import com.retrivedmods.wclient.game.world.chunk.palette.BitArray
import com.retrivedmods.wclient.game.world.chunk.palette.BitArrayVersion
import io.netty.buffer.ByteBuf
import org.cloudburstmc.protocol.common.util.VarInts

/**
 * Ported from ProtoHax (dev.sora.relay.game.world.chunk.BlockStorage), adapted for WClient:
 * - uses a plain MutableList<Int> instead of fastutil's IntArrayList (WClient doesn't pull in the
 *   int-list fastutil artifact, only long/int-object map variants), to avoid an extra dependency.
 * - only supports the runtime-id palette format (the "isRuntime" branch). Real Bedrock servers
 *   always send LevelChunkPacket/SubChunkPacket over the network using runtime ids, never the
 *   persistent NBT-tag palette (that format is only used in world save files), so this covers
 *   every case WClient - a live network relay - actually needs to handle.
 */
class BlockStorage {

    var bitArray: BitArray
    var palette: MutableList<Int>

    constructor(airId: Int, version: BitArrayVersion = BitArrayVersion.V2) {
        bitArray = version.createPalette(MAX_BLOCK_IN_SECTION)
        palette = mutableListOf(airId)
    }

    constructor(buf: ByteBuf, network: Boolean) {
        val paletteHeader = buf.readByte().toInt()
        val isRuntime = (paletteHeader and 1) == 1
        if (!isRuntime) {
            throw UnsupportedOperationException(
                "persistent (NBT tag) block palettes are not supported, only runtime-id palettes"
            )
        }

        val bitsPerBlock = paletteHeader shr 1

        fun readInt(): Int = if (network) VarInts.readInt(buf) else buf.readIntLE()

        if (bitsPerBlock == 0) {
            // "Uniform" layer: the entire 4096-block section is a single block, encoded as just
            // one VarInt runtime id right after the header byte - no bit-packed word array, no
            // palette array follows at all. This is extremely common (any fully-air or otherwise
            // uniform section - most of the sky and plenty of underground stone), confirmed
            // against WClient's own currently-shipping v36 chunk parser (u5/h.java, the
            // `unsignedByte3 == 0` branch there). BitArrayVersion has no entry for bits=0, so
            // before this fix BitArrayVersion.get(0, true) below unconditionally threw
            // "Invalid palette version: 0" on every uniform layer - which, left uncaught,
            // desynced (or aborted) parsing of every subsequent subchunk in the same
            // LevelChunkPacket, which is almost certainly why block reads kept coming back
            // minecraft:unknown (or a single stale constant runtime id) almost everywhere.
            val uniformRuntimeId = readInt()
            bitArray = BitArrayVersion.V1.createPalette(MAX_BLOCK_IN_SECTION)
            // A freshly created bitArray's backing IntArray defaults every entry to 0, so a
            // single-entry palette already means "every one of the 4096 positions resolves to
            // this one block" with no further writes needed.
            palette = mutableListOf(uniformRuntimeId)
            return
        }

        val bitArrayVersion = BitArrayVersion.get(bitsPerBlock, true)

        bitArray = bitArrayVersion.createPalette(MAX_BLOCK_IN_SECTION)

        for (i in bitArray.words.indices) {
            bitArray.words[i] = buf.readIntLE()
        }

        val paletteSize = readInt()
        palette = ArrayList(paletteSize)
        for (i in 0 until paletteSize) {
            palette.add(readInt())
        }
    }

    private fun getIndex(x: Int, y: Int, z: Int): Int {
        return x shl 8 or (z shl 4) or y
    }

    fun setBlock(x: Int, y: Int, z: Int, runtimeId: Int) {
        this.setBlock(getIndex(x, y, z), runtimeId)
    }

    fun getByIndex(index: Int): Int {
        return palette[bitArray[index]]
    }

    fun getBlock(x: Int, y: Int, z: Int): Int {
        return getByIndex(getIndex(x, y, z))
    }

    fun setBlock(index: Int, runtimeId: Int) {
        try {
            val id = idFor(runtimeId)
            bitArray[index] = id
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Unable to set block runtime ID: $runtimeId, palette: $palette", e)
        }
    }

    private fun onResize(version: BitArrayVersion) {
        val newBitArray = version.createPalette(MAX_BLOCK_IN_SECTION)
        for (i in 0 until MAX_BLOCK_IN_SECTION) {
            newBitArray[i] = bitArray[i]
        }
        bitArray = newBitArray
    }

    private fun idFor(runtimeId: Int): Int {
        var index = palette.indexOf(runtimeId)
        if (index != -1) {
            return index
        }
        index = palette.size
        val version = bitArray.version
        if (index > version.maxEntryValue) {
            val next = version.next()
            if (next != null) {
                onResize(next)
            }
        }
        palette.add(runtimeId)
        return index
    }

    companion object {
        const val MAX_BLOCK_IN_SECTION = 4096
    }
}
