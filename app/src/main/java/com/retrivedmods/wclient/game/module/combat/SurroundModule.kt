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
 * Ported from the reference Surround.cpp, now using real block data (session.level.getBlockAt)
 * instead of blindly firing placements. Follows the same ring-without-corners shape and
 * dynamic-expansion idea as the original, adapted to what WClient can actually see: entities
 * don't carry a hitbox width/height here, so "dynamic" expansion uses a flat per-entity margin
 * check against the ring cells instead of true AABB intersection.
 */
class SurroundModule : Module("surround", ModuleCategory.Combat) {

    private var placeDelayTicks by intValue("place_delay", 1, 0..20)
    private var blocksPerTick by intValue("blocks_per_tick", 1, 1..10)
    private var airPlace by boolValue("air_place", false)
    private var center by boolValue("center", true)
    private var dynamic by boolValue("dynamic", true)
    private var dynamicMargin by floatValue("dynamic_margin", 0.6f, 0f..2f)
    private var placeButton by boolValue("button", true)
    private var fakeRotation by boolValue("rotate", false)

    private companion object {
        const val OBSIDIAN = "minecraft:obsidian"
        const val BUTTON = "minecraft:stone_button"
    }

    private var placeList: MutableList<Vector3i> = mutableListOf()
    private var tickCounter = 0
    private var oldSlot = -1
    private var hasCentered = false
    private var surroundDiagTickCounter = 0

    override fun onEnabled() {
        super.onEnabled()
        placeList = mutableListOf()
        tickCounter = 0
        oldSlot = -1
        hasCentered = false
        lastWarnedMessage = null
    }

    override fun onDisabled() {
        super.onDisabled()
        placeList.clear()
        if (oldSlot != -1 && isSessionCreated) {
            switchToSlot(oldSlot)
        }
        oldSlot = -1
        hasCentered = false
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return
        val packet = interceptablePacket.packet
        if (packet !is PlayerAuthInputPacket) return

        val localPlayer = session.localPlayer

        if (center && !hasCentered) {
            val pos = localPlayer.vec3Position
            packet.position = Vector3f.from(floor(pos.x) + 0.5f, pos.y, floor(pos.z) + 0.5f)
            hasCentered = true
        }

        val obsidianSlot = localPlayer.inventory.searchForItemInHotbar {
            it.definition?.identifier == OBSIDIAN
        }

        if (obsidianSlot == null) {
            warnMissingItem("§c黒曜石を持っていません！")
        }

        placeList = computePlaceList()

        run {
            surroundDiagTickCounter++
            if (surroundDiagTickCounter % 40 == 0) {
                val pos = localPlayer.vec3Position
                // floor(pos.y), not floor(pos.y + 0.5f) - that extra 0.5 effectively rounded to
                // the nearest block level instead of taking the block the feet actually occupy,
                // so it silently referenced a level 1 too high whenever the fractional part of
                // pos.y was >= 0.5 (which is most of the time - players are rarely exactly on a
                // block boundary). Must match computePlaceList's currentPos below exactly, or
                // this diagnostic prints a different ring than the one actually being computed.
                val currentPos = Vector3i.from(floor(pos.x).toInt(), floor(pos.y).toInt(), floor(pos.z).toInt())
                val cells = listOf(
                    "N(0,-1)" to Vector3i.from(0, 0, -1),
                    "S(0,1)" to Vector3i.from(0, 0, 1),
                    "W(-1,0)" to Vector3i.from(-1, 0, 0),
                    "E(1,0)" to Vector3i.from(1, 0, 0),
                    "center-below(0,0)" to Vector3i.from(0, -1, 0)
                )
                val cellDump = cells.joinToString(" | ") { (label, offset) ->
                    val cellPos = currentPos.add(offset.x, offset.y, offset.z)
                    val below = currentPos.add(offset.x, offset.y - 1, offset.z)
                    val cellId = session.level.getBlockAt(cellPos).identifier
                    val belowId = session.level.getBlockAt(below).identifier
                    "$label:cell=$cellId,below=$belowId,canPlace=${canPlaceAt(cellPos)}"
                }
                session.displayClientMessage(
                    "§b[SurroundDiag] placeList size=${placeList.size}, airPlace=$airPlace, obsidianSlot=$obsidianSlot\n$cellDump"
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

                var placed = 0
                val iterator = placeList.iterator()
                while (iterator.hasNext() && placed < blocksPerTick) {
                    val pos = iterator.next()
                    place(OBSIDIAN, pos, obsidianSlot)
                    placed++
                }
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

    /** [Surround.cpp]'s canPlaceBlock(): the target itself must be air (or, with airPlace, anything). */
    private fun canPlaceAt(pos: Vector3i): Boolean {
        if (airPlace) return true
        val identifier = session.level.getBlockAt(pos).identifier
        // Many servers use chunk-loading methods this Level doesn't track (blob cache / per-
        // subchunk requests), so block state often comes back "minecraft:unknown" rather than a
        // confirmed real block - confirmed via [SurroundDiag]: every position read back unknown on
        // such a server, which made this always return false and silently disabled placement
        // entirely (placeList size was always 0). Treat unknown the same as air - only refuse when
        // we positively know a real block is already there.
        return identifier == "minecraft:air" || identifier == "minecraft:unknown"
    }

    private fun computePlaceList(): MutableList<Vector3i> {
        val localPlayer = session.localPlayer
        val pos = localPlayer.vec3Position
        // floor(pos.y), not floor(pos.y + 0.5f) - see the diagnostic block above for why the +0.5
        // was wrong (silently referenced a level 1 too high most of the time).
        val currentPos = Vector3i.from(floor(pos.x).toInt(), floor(pos.y).toInt(), floor(pos.z).toInt())

        var xStart = -1
        var zStart = -1
        var xEnd = 1
        var zEnd = 1

        if (dynamic) {
            session.level.entityMap.values.forEach { entity ->
                val d = entity.distance(pos)
                if (d > 4f) return@forEach
                val dx = entity.posX - pos.x
                val dz = entity.posZ - pos.z
                if (dx <= xStart + 1 + dynamicMargin && dx >= xStart - dynamicMargin) xStart -= 1
                if (dz <= zStart + 1 + dynamicMargin && dz >= zStart - dynamicMargin) zStart -= 1
                if (dx >= xEnd - 1 - dynamicMargin && dx <= xEnd + dynamicMargin) xEnd += 1
                if (dz >= zEnd - 1 - dynamicMargin && dz <= zEnd + dynamicMargin) zEnd += 1
            }
        }

        val result = mutableListOf<Vector3i>()
        for (x in xStart..xEnd) {
            for (z in zStart..zEnd) {
                // skip the 4 corners, matching Surround.cpp's ring-without-corners shape
                if ((x == xStart || x == xEnd) && (z == zStart || z == zEnd)) continue

                if (x > xStart && x < xEnd && z > zStart && z < zEnd) {
                    // strictly interior cell (only reachable once dynamic expansion has grown the
                    // box past the base 3x3): floor it in, one level down
                    val placePos = currentPos.add(x, -1, z)
                    if (canPlaceAt(placePos)) result.add(placePos)
                    continue
                }

                val placePos = currentPos.add(x, 0, z)
                val below = currentPos.add(x, -1, z)
                // only bother with the "wall" cell if there's solid ground for it to stand on
                if (session.level.getBlockAt(below).identifier == "minecraft:air" && !airPlace) continue

                if (canPlaceAt(placePos)) {
                    result.add(placePos)
                } else if (canPlaceAt(below)) {
                    result.add(below)
                }
            }
        }

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
        session.serverBound(packet)
        // Deliberately NOT calling BlockPlacementUtils.predictLocalBlockChange() here (unlike
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
    }
}
