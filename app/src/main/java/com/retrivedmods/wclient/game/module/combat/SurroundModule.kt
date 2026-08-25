package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.BlockPlacementUtils
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket
import kotlin.math.floor

/**
 * Faithful port of ProtoHax's ModuleSurround.kt (dev.sora.relay.cheat.module.impl.combat) - much
 * simpler than what was here before. Notably:
 *  - only 5 directions (E/N/S/W/DOWN), not a full 3x3 ring - there's no "corners" concept at all
 *  - a candidate only needs to itself be air; there's NO requirement that anything be solid
 *    below it (the previous ring-based version required solid ground under every cell, which is
 *    a real, meaningfully stricter condition ProtoHax's own algorithm never had)
 *  - one placement per tick (found via findReferenceBlock, mirroring ProtoHax's own "find any
 *    adjacent non-air face closest to the player" search), not multiple
 */
class SurroundModule : Module("surround", ModuleCategory.Combat) {

    private var placeDelayTicks by intValue("place_delay", 2, 0..20)
    private var airPlace by boolValue("air_place", false)
    private var placeButton by boolValue("button", true)
    private var fakeRotation by boolValue("rotate", false)

    private companion object {
        const val OBSIDIAN = "minecraft:obsidian"
        const val BUTTON = "minecraft:stone_button"

        // EnumFacing.EAST, NORTH, SOUTH, WEST, DOWN from ProtoHax's placeableDirections - note
        // there's deliberately no UP here, matching the original (you're not trying to place a
        // ceiling over your own head).
        val DIRECTIONS = listOf(
            Vector3i.from(1, 0, 0),
            Vector3i.from(0, 0, -1),
            Vector3i.from(0, 0, 1),
            Vector3i.from(-1, 0, 0),
            Vector3i.from(0, -1, 0)
        )
    }

    private var tickCounter = 0
    private var oldSlot = -1
    private var surroundDiagTickCounter = 0

    override fun onEnabled() {
        super.onEnabled()
        tickCounter = 0
        oldSlot = -1
        lastWarnedMessage = null
    }

    override fun onDisabled() {
        super.onDisabled()
        if (oldSlot != -1 && isSessionCreated) {
            switchToSlot(oldSlot)
        }
        oldSlot = -1
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return
        val packet = interceptablePacket.packet
        if (packet !is PlayerAuthInputPacket) return

        val localPlayer = session.localPlayer

        val obsidianSlot = localPlayer.inventory.searchForItemInHotbar {
            it.definition?.identifier == OBSIDIAN
        }
        if (obsidianSlot == null) {
            warnMissingItem("§c黒曜石を持っていません！")
        }

        val placeList = computePlaceList()

        run {
            surroundDiagTickCounter++
            if (surroundDiagTickCounter % 40 == 0) {
                val pos = localPlayer.vec3Position
                val currentPos = Vector3i.from(floor(pos.x).toInt(), floor(pos.y).toInt(), floor(pos.z).toInt())
                val cellDump = DIRECTIONS.joinToString(" | ") { dir ->
                    val cellPos = currentPos.add(dir.x, dir.y, dir.z)
                    val cellId = session.level.getBlockAt(cellPos).identifier
                    "($dir)abs=($cellPos):cell=$cellId,canPlace=${canPlaceAt(cellPos)}"
                }
                session.displayClientMessage(
                    "§b[SurroundDiag] playerPos=$currentPos, placeList size=${placeList.size}, airPlace=$airPlace, obsidianSlot=$obsidianSlot\n$cellDump"
                )
            }
        }

        if (fakeRotation && placeList.isNotEmpty()) {
            val eye = localPlayer.vec3Position
            val target = placeList.first()
            val dx = (target.x + 0.5f) - eye.x
            val dz = (target.z + 0.5f) - eye.z
            val yaw = Math.toDegrees(kotlin.math.atan2(-dx.toDouble(), dz.toDouble())).toFloat()
            packet.rotation = Vector3f.from(packet.rotation.x, yaw, yaw)
        }

        if (obsidianSlot != null && placeList.isNotEmpty()) {
            if (oldSlot == -1) {
                oldSlot = localPlayer.inventory.heldItemSlot
            }

            if (tickCounter >= placeDelayTicks) {
                tickCounter = 0
                if (localPlayer.inventory.heldItemSlot != obsidianSlot) {
                    switchToSlot(obsidianSlot)
                }
                // Only one placement per tick, matching ProtoHax's `return@handle` after the
                // first successful placement in its forEach loop.
                place(OBSIDIAN, placeList.first(), obsidianSlot)
            } else {
                tickCounter++
            }
        }

        if (placeButton) {
            val buttonSlot = localPlayer.inventory.searchForItemInHotbar {
                it.definition?.identifier == BUTTON
            }
            if (buttonSlot == null) {
                warnMissingItem("§cボタン(stone_button)を持っていません！")
            } else {
                val buttonPos = Vector3i.from(
                    floor(localPlayer.vec3Position.x).toInt(),
                    floor(localPlayer.vec3Position.y).toInt() - 1,
                    floor(localPlayer.vec3Position.z).toInt()
                )
                place(BUTTON, buttonPos, buttonSlot)
            }
        }
    }

    private var lastWarnedMessage: String? = null

    /** Warns once per distinct message while the module stays enabled, instead of spamming chat every tick. */
    private fun warnMissingItem(message: String) {
        if (lastWarnedMessage == message) return
        lastWarnedMessage = message
        session.displayClientMessage(message)
    }

    /** ProtoHax's filter: `getBlockAt(it).identifier == "minecraft:air"` (plus airPlace override). */
    private fun canPlaceAt(pos: Vector3i): Boolean {
        if (airPlace) return true
        val identifier = session.level.getBlockAt(pos).identifier
        // Many servers use chunk-loading methods this Level historically didn't track, so block
        // state could come back "minecraft:unknown" rather than a confirmed real block. Treat
        // unknown the same as air - only refuse when we positively know a real block is there.
        // (ProtoHax's own Level always has real world data since it patches the full game client,
        // so its reference implementation never needed this "unknown" case at all.)
        return identifier == "minecraft:air" || identifier == "minecraft:unknown"
    }

    /**
     * ProtoHax's onTick: check the 5 directions around the player's current block, keep the ones
     * that are air, skip anything that would intersect the player's own position, sort by
     * distance. No ring, no dynamic expansion, no "solid ground below" requirement.
     */
    private fun computePlaceList(): MutableList<Vector3i> {
        val localPlayer = session.localPlayer
        val pos = localPlayer.vec3Position
        val currentPos = Vector3i.from(floor(pos.x).toInt(), floor(pos.y).toInt(), floor(pos.z).toInt())

        val result = DIRECTIONS
            .map { currentPos.add(it.x, it.y, it.z) }
            .filter { canPlaceAt(it) }
            // ProtoHax checks a real AABB around the player's own hitbox here; WClient's Entity
            // doesn't carry hitbox dimensions, so approximate with "don't target the player's own
            // block" - the 5 directions are all adjacent-but-not-overlapping by construction
            // anyway, so this mainly guards against currentPos itself if it somehow ended up in
            // the candidate list.
            .filter { it != currentPos }
            .toMutableList()

        result.sortBy { it.distanceSq(currentPos) }
        return result
    }

    private fun Vector3i.distanceSq(other: Vector3i): Int {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return dx * dx + dy * dy + dz * dz
    }

    private fun place(identifier: String, pos: Vector3i, slot: Int) {
        val localPlayer = session.localPlayer
        val (refPos, refFace) = BlockPlacementUtils.findReferenceBlock(session, pos)
            ?: return // no solid neighbor to click yet - skip this cell, the ring pass will retry it
        val heldItem = localPlayer.inventory.hand

        val packet = InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE
            actionType = 0
            blockPosition = refPos
            blockFace = refFace
            hotbarSlot = slot
            itemInHand = heldItem
            playerPosition = localPlayer.vec3Position
            clickPosition = Vector3f.from(0.5f, 0.5f, 0.5f)
            // blockDefinition must describe the EXISTING block being clicked (refPos), not the
            // item being placed - see BlockPlacementUtils' class doc for how a real captured
            // packet confirmed this.
            blockDefinition = BlockPlacementUtils.referenceBlockDefinition(session, refPos)
            actions.add(BlockPlacementUtils.consumeItemAction(slot, heldItem))
        }
        BlockPlacementUtils.sendAndLog(session, packet)
        // NOT calling BlockPlacementUtils.predictLocalBlockChange() here (unlike
        // PistonCrystalModule, which needs it for its piston->crystal->redstone sequencing).
        // Surround recomputes its whole ring from scratch every tick anyway, so there's no
        // sequencing need for an immediate local update - and doing it unconditionally caused a
        // real bug: since Bedrock servers silently ignore/reject invalid transactions (no NACK
        // packet), if a placement got rejected, the optimistic prediction would still mark that
        // cell as "already obsidian" in our own world model forever, with nothing to ever correct
        // it - so canPlaceAt() kept refusing to retry a spot that, on the real server, still had
        // nothing there. Letting the real UpdateBlockPacket (handled in Level.kt) be the only
        // source of truth means a rejected placement is naturally retried next tick instead of
        // being permanently (and incorrectly) considered done.
    }

    private fun switchToSlot(slot: Int) {
        val packet = PlayerHotbarPacket().apply {
            selectedHotbarSlot = slot
            containerId = 0
            isSelectHotbarSlot = true
        }
        // Must go to the real server (this is what tells it which item we're now holding), not
        // just update our own local display - sending it clientBound only meant the server never
        // learned about the switch, so every placement afterwards referenced a hotbar slot/item
        // the server didn't think was selected and rejected it.
        session.serverBound(packet)

        // session.serverBound() bypasses the normal interception pipeline entirely (see
        // GameSession/WRelaySession), which is the ONLY place PlayerInventory.heldItemSlot gets
        // updated (it only listens for packets that pass through there, i.e. the real client's
        // own traffic). Without this, heldItemSlot silently never changes, so
        // localPlayer.inventory.hand (= content[heldItemSlot]) kept pointing at whatever was
        // selected before Surround/PistonCrystal ever ran - meaning every placement packet's
        // itemInHand didn't actually match its own hotbarSlot, which is exactly the kind of
        // mismatch a server's inventory validation rejects outright. Predict it locally, the same
        // way a real client's own selection updates immediately without waiting on a round trip.
        session.localPlayer.inventory.predictHeldItemSlot(slot)
    }
}
