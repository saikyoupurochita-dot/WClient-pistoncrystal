package com.retrivedmods.wclient.game.world

import com.retrivedmods.wclient.game.GameSession
import com.retrivedmods.wclient.game.entity.Entity
import com.retrivedmods.wclient.game.entity.EntityUnknown
import com.retrivedmods.wclient.game.entity.Item
import com.retrivedmods.wclient.game.entity.Player
import com.retrivedmods.wclient.game.registry.BlockDefinition
import com.retrivedmods.wclient.game.registry.UnknownBlockDefinition
import com.retrivedmods.wclient.game.world.chunk.Chunk
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket
import org.cloudburstmc.protocol.bedrock.packet.AddItemEntityPacket
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket
import org.cloudburstmc.protocol.bedrock.packet.ChunkRadiusUpdatedPacket
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheBlobStatusPacket
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheMissResponsePacket
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket
import org.cloudburstmc.protocol.bedrock.packet.TakeItemEntityPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateSubChunkBlocksPacket
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

@Suppress("MemberVisibilityCanBePrivate")
class Level(val session: GameSession) {

    val entityMap = ConcurrentHashMap<Long, Entity>()

    val playerMap = ConcurrentHashMap<UUID, PlayerListPacket.Entry>()

    // --- world/chunk block tracking -----------------------------------------------------------
    // Ported (with real changes, see Chunk/ChunkSection/BlockStorage) from ProtoHax. Handles the
    // "normal" full LevelChunkPacket path, the newer per-subchunk request system (SubChunkPacket),
    // and blob-cache chunk loading (via ClientCacheMissResponsePacket, see pendingCacheBlobs
    // below) - so real block data is tracked regardless of which of the three loading methods a
    // given server uses. UpdateBlockPacket (single block changes) and UpdateSubChunkBlocksPacket
    // (batch changes - explosions, redstone, etc.) are both handled, so blocks placed/broken
    // after a chunk loads stay accurate.

    val chunks = ConcurrentHashMap<Long, Chunk>()

    /**
     * Blob-cache loading (LevelChunkPacket.isCachingEnabled) doesn't put block data directly in
     * the LevelChunkPacket - instead it lists content-hash IDs (one per subchunk, plus one for
     * biome data, in bottom-to-top order) and the real client separately negotiates with the
     * server over which hashes it already has cached (ClientCacheBlobStatusPacket) vs needs sent
     * (ClientCacheMissResponsePacket, hash -> raw payload). As a relay we don't need to run that
     * negotiation ourselves - the real client does it - we just need to passively watch
     * ClientCacheMissResponsePacket go by and match each hash back to the (chunk, section) it
     * belongs to, which we recorded here when the LevelChunkPacket first listed it. A hash that
     * was already in the real client's own cache never triggers a miss response and so never
     * arrives at all - that subchunk just silently stays untracked (same as any other subchunk we
     * haven't seen data for yet), which is a value-if-known / not-an-error position, matching
     * every other gap in this tracking (unloaded chunks, servers that don't use this feature).
     */
    private val pendingCacheBlobs = ConcurrentHashMap<Long, Pair<Long, Int>>() // blobId -> (chunkHash, sectionIndex); sectionIndex == -1 means "biome blob, ignore"

    var is384WorldSupported = false
        private set

    var viewDistance = -1
        private set

    fun onDisconnect() {
        entityMap.clear()
        playerMap.clear()
        chunks.clear()
        pendingCacheBlobs.clear()
    }

    fun onPacketBound(packet: BedrockPacket) {
        when (packet) {
            is StartGamePacket -> {
                entityMap.clear()
                playerMap.clear()
                chunks.clear()

                is384WorldSupported = try {
                    // 384 height world was introduced in Minecraft 1.18
                    val parts = packet.vanillaVersion.split(".")
                    parts.size >= 2 && parts[0] == "1" && (parts[1].toIntOrNull() ?: 0) >= 18
                } catch (e: Exception) {
                    true
                }
            }

            is LevelChunkPacket -> {
                if (!session.isBlockMappingInitialized) {
                    // shouldn't normally happen (StartGamePacket sets blockMapping before Level
                    // sees it), but guard anyway since a missing mapping would crash chunk parsing
                    return
                }

                val chunk = Chunk(packet.chunkX, packet.chunkZ, is384WorldSupported, session.blockMapping)
                try {
                    if (packet.isCachingEnabled) {
                        // No raw data here - record which (chunk, section) each listed hash is
                        // for, then wait for ClientCacheMissResponsePacket (below) to supply the
                        // actual bytes for whichever ones the real client didn't already have
                        // cached. Per protocol, blobIds is subChunksLength subchunk hashes
                        // (bottom-to-top) followed by exactly one biome-data hash - we only care
                        // about the former.
                        packet.blobIds.forEachIndexed { index, blobId ->
                            val sectionIndex = if (index < packet.subChunksLength) index else -1
                            pendingCacheBlobs[blobId] = chunk.hash to sectionIndex
                        }
                    } else if (!packet.isRequestSubChunks) {
                        // duplicate() gives us an independent reader index over the same underlying
                        // memory (refcount shared with the original packet), so parsing here can
                        // never disturb packet.data's own reader index / the relay's forwarding of
                        // the real packet to the client.
                        val buf = packet.data.duplicate()
                        chunk.read(buf, packet.subChunksLength)
                    }
                    // Either way, register the (possibly still-empty) chunk now: both
                    // isCachingEnabled and isRequestSubChunks mean the actual block data streams
                    // in afterwards (via ClientCacheMissResponsePacket or SubChunkPacket
                    // respectively), which needs a Chunk already sitting in the map to attach its
                    // sections to.
                    chunks[chunk.hash] = chunk
                } catch (e: Exception) {
                    // malformed/unexpected chunk data for this protocol version - skip it rather
                    // than crash the relay
                }
            }

            is ClientCacheBlobStatusPacket -> {
                // Rewrite what the real client reports having cached (acks) into "don't have it"
                // (naks) before this goes on to the server. Without this, any blob the real
                // client's on-device cache already had from a previous session never gets resent
                // at all - the bytes never cross the network, so there's nothing for us to
                // passively observe no matter what ClientCacheMissResponsePacket handling we add.
                // Forcing every ack into a nak makes the server treat everything as a cache miss
                // and resend full data every time, which we can then always track via
                // ClientCacheMissResponsePacket above. The real client doesn't notice or care -
                // it just receives fresh data instead of using its local cache; functionally
                // identical, just slightly less bandwidth-efficient.
                if (packet.acks.isNotEmpty()) {
                    packet.naks.addAll(packet.acks)
                    packet.acks.clear()
                }
            }

            is ClientCacheMissResponsePacket -> {
                if (!session.isBlockMappingInitialized) return

                packet.blobs.forEach { (blobId, data) ->
                    val target = pendingCacheBlobs.remove(blobId) ?: return@forEach
                    val (chunkHash, sectionIndex) = target
                    if (sectionIndex < 0) return@forEach // biome blob - nothing for us to parse

                    try {
                        val chunk = chunks[chunkHash] ?: return@forEach
                        chunk.readSubChunk(sectionIndex, data.duplicate())
                    } catch (e: Exception) {
                        // same reasoning as the LevelChunkPacket catch above - skip, don't crash
                    }
                }
            }

            is SubChunkPacket -> {
                if (!session.isBlockMappingInitialized) return

                val center = packet.centerPosition
                packet.subChunks.forEach { subChunkData ->
                    try {
                        // Only bother parsing entries that actually carry block data. We don't
                        // depend on the exact SubChunkRequestResult enum name/value here (its
                        // constants weren't confirmed) - an empty/absent buffer is a reliable
                        // enough signal that there's nothing to parse for this one.
                        val data = subChunkData.data ?: return@forEach
                        if (data.readableBytes() <= 0) return@forEach

                        val offset = subChunkData.position
                        val chunkX = center.x + offset.x
                        val chunkZ = center.z + offset.z
                        // centerPosition.y is the signed index of the reference (usually bottom)
                        // section; offset.y shifts from there. Our own Chunk.sectionStorage is a
                        // plain 0-based array, so for a 384-world (24 sections, floor at y=-64) we
                        // shift by +4 to land the lowest legal signed index (-4) on array index 0.
                        // For the classic 256-world (16 sections, y starts at 0) signed indices are
                        // already 0-based, so no shift is needed.
                        val sectionIndex = (center.y + offset.y) + (if (is384WorldSupported) 4 else 0)

                        val chunk = chunks.getOrPut(Chunk.hash(chunkX, chunkZ)) {
                            Chunk(chunkX, chunkZ, is384WorldSupported, session.blockMapping)
                        }

                        val buf = data.duplicate()
                        chunk.readSubChunk(sectionIndex, buf)
                    } catch (e: Exception) {
                        // same reasoning as the LevelChunkPacket catch above - skip, don't crash
                    }
                }
            }

            is UpdateBlockPacket -> {
                if (packet.dataLayer == 0) {
                    setBlockIdAt(
                        packet.blockPosition.x,
                        packet.blockPosition.y,
                        packet.blockPosition.z,
                        packet.definition.runtimeId
                    )
                }
            }

            is UpdateSubChunkBlocksPacket -> {
                // Batch block-change variant of UpdateBlockPacket - the server uses this for
                // several simultaneous changes in one subchunk (explosions, redstone, pistons,
                // etc.) instead of one UpdateBlockPacket per block. We had no handler for this at
                // all before, so any such batch change left our tracked world state stale until
                // the whole chunk happened to reload. Each entry already carries an absolute world
                // position, same as UpdateBlockPacket.blockPosition. Only standardBlocks (the
                // primary layer) is applied, matching how UpdateBlockPacket above only acts on
                // dataLayer == 0 - extraBlocks is the secondary/waterlogged layer we don't track.
                packet.standardBlocks.forEach { entry ->
                    val pos = entry.position
                    setBlockIdAt(pos.x, pos.y, pos.z, entry.definition.runtimeId)
                }
            }

            is ChunkRadiusUpdatedPacket -> {
                viewDistance = packet.radius
            }

            is ChangeDimensionPacket -> {
                chunks.clear()
            }

            is AddEntityPacket -> {
                val entity = EntityUnknown(
                    packet.runtimeEntityId,
                    packet.uniqueEntityId,
                    packet.identifier
                ).apply {
                    move(packet.position)
                    rotate(packet.rotation)
                    handleSetData(packet.metadata)
                    handleSetAttribute(packet.attributes)
                }
                entityMap[packet.runtimeEntityId] = entity
            }

            is AddItemEntityPacket -> {
                val entity = Item(packet.runtimeEntityId, packet.uniqueEntityId).apply {
                    move(packet.position)
                    handleSetData(packet.metadata)
                }
                entityMap[packet.runtimeEntityId] = entity
            }

            is AddPlayerPacket -> {
                val entity = Player(
                    packet.runtimeEntityId,
                    packet.uniqueEntityId,
                    packet.uuid,
                    packet.username
                ).apply {
                    move(packet.position)
                    rotate(packet.rotation)
                    handleSetData(packet.metadata)
                }
                entityMap[packet.runtimeEntityId] = entity
            }

            is RemoveEntityPacket -> {
                val entityToRemove =
                    entityMap.values.find { it.uniqueEntityId == packet.uniqueEntityId } ?: return
                entityMap.remove(entityToRemove.runtimeEntityId)
            }

            is TakeItemEntityPacket -> {
                entityMap.remove(packet.itemRuntimeEntityId)
            }

            is PlayerListPacket -> {
                val add = packet.action == PlayerListPacket.Action.ADD
                packet.entries.forEach {
                    if (add) {
                        playerMap[it.uuid] = it
                    } else {
                        playerMap.remove(it.uuid)
                    }
                }
            }

            else -> {
                entityMap.values.forEach { entity ->
                    entity.onPacketBound(packet)
                }
            }
        }
    }

    /**
     * Approximates vanilla explosion damage falloff for every tracked entity (and any [extraEntities]
     * not currently in [entityMap], e.g. the local player) around [center].
     *
     * This does NOT account for block occlusion/exposure (WClient has no local world/chunk state to
     * raycast against), so it always assumes full exposure (1.0). Real in-game damage will be lower
     * whenever blocks are between the explosion and the target. Treat the result as an upper-bound
     * estimate for target/placement selection, not an exact value.
     */
    fun simulateExplosionDamage(
        center: Vector3f,
        size: Float,
        extraEntities: List<Entity> = emptyList(),
        damageCallback: (Entity, Float) -> Unit
    ) {
        val searchRadiusSq = (size * 2).pow(2)

        fun evaluate(entity: Entity) {
            val distSq = entity.distanceSq(center)
            if (distSq >= searchRadiusSq) return

            val distance = entity.distance(center) / size
            if (distance <= 1f) {
                val impact = 1f - distance
                val damage = ((impact * impact + impact) / 2f) * 8f * size + 1f
                damageCallback(entity, damage)
            }
        }

        entityMap.values.forEach(::evaluate)
        extraEntities.forEach(::evaluate)
    }

    // --- block query/update helpers -----------------------------------------------------------

    fun getChunkAt(chunkX: Int, chunkZ: Int): Chunk? = chunks[Chunk.hash(chunkX, chunkZ)]

    fun isChunkLoaded(x: Int, z: Int): Boolean = chunks.containsKey(Chunk.hash(x shr 4, z shr 4))

    /**
     * Runtime id of the block at the given world coordinates, or the air runtime id if the
     * containing chunk hasn't been loaded/tracked (see the notes on the LevelChunkPacket handling
     * above for when that can happen).
     */
    fun getBlockIdAt(x: Int, y: Int, z: Int): Int {
        val chunk = getChunkAt(x shr 4, z shr 4)
            ?: return if (session.isBlockMappingInitialized) session.blockMapping.airId else 0
        return chunk.getBlockAt(x and 0x0f, y, z and 0x0f)
    }

    fun getBlockIdAt(pos: Vector3i): Int = getBlockIdAt(pos.x, pos.y, pos.z)

    fun getBlockAt(x: Int, y: Int, z: Int): BlockDefinition {
        if (!session.isBlockMappingInitialized) return UnknownBlockDefinition(0)
        return session.blockMapping.getDefinition(getBlockIdAt(x, y, z))
    }

    fun getBlockAt(pos: Vector3i): BlockDefinition = getBlockAt(pos.x, pos.y, pos.z)

    fun setBlockIdAt(x: Int, y: Int, z: Int, runtimeId: Int) {
        val chunk = getChunkAt(x shr 4, z shr 4) ?: return
        chunk.setBlockAt(x and 0x0f, y, z and 0x0f, runtimeId)
    }

    fun isAir(x: Int, y: Int, z: Int): Boolean = getBlockAt(x, y, z).identifier == "minecraft:air"

    fun isAir(pos: Vector3i): Boolean = isAir(pos.x, pos.y, pos.z)

    /**
     * Bedrock places a new block adjacent to whatever *existing* block you "click" - not directly
     * at the position you name - so block-placing modules (Surround, PistonCrystal, ...) need to
     * find a real, currently non-air neighbor of [pos] to click, and which face of that neighbor
     * points back at [pos]. Checks straight down first (the common "place on the ground" case),
     * then up, then the four horizontal neighbors.
     *
     * Returns null if [pos] itself isn't currently air (already occupied, or the chunk/subchunk
     * simply isn't tracked yet - see the LevelChunkPacket/ClientCacheMissResponsePacket handling
     * notes above for the ways that can happen) or if none of its neighbors are known to be
     * solid, e.g. floating in open air.
     *
     * @return (position of the block to click, Bedrock face index of that block to click:
     *   0=down,1=up,2=north,3=south,4=west,5=east) or null
     */
    fun findPlacementReference(pos: Vector3i): Pair<Vector3i, Int>? {
        if (!isAir(pos)) return null

        // (offset to the neighboring block, face of THAT block which points back at pos)
        val candidates = listOf(
            Vector3i.from(0, -1, 0) to 1, // below   -> click its UP face
            Vector3i.from(0, 1, 0) to 0,  // above   -> click its DOWN face
            Vector3i.from(1, 0, 0) to 4,  // east    -> click its WEST face
            Vector3i.from(-1, 0, 0) to 5, // west    -> click its EAST face
            Vector3i.from(0, 0, 1) to 2,  // south   -> click its NORTH face
            Vector3i.from(0, 0, -1) to 3  // north   -> click its SOUTH face
        )

        for ((offset, face) in candidates) {
            val neighborPos = Vector3i.from(pos.x + offset.x, pos.y + offset.y, pos.z + offset.z)
            if (!isAir(neighborPos)) {
                return neighborPos to face
            }
        }
        return null
    }

}