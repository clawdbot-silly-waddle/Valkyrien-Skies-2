package org.valkyrienskies.mod.common.util

import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.api.toJOML
import org.valkyrienskies.mod.api.toMinecraft
import org.valkyrienskies.mod.common.entity.handling.VSEntityManager

import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.EntityLerper.yawToWorld
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object EntityDragger {
    // How much we decay the addedMovement each tick after player hasn't collided with a ship for at least 10 ticks.
    private const val ADDED_MOVEMENT_DECAY = 0.9


    /**
     * Drag these entities with the ship they're standing on.
     */
    fun dragEntitiesWithShips(entities: Iterable<Entity>, preTick: Boolean = false) {
        for (entity in entities) {
            val entityDraggingInformation = (entity as? IEntityDraggingInformationProvider)?.draggingInformation ?: continue

            var dragTheEntity = false
            var addedMovement: Vector3dc? = null
            var addedYRot = 0.0

            val shipDraggingEntity = entityDraggingInformation.lastShipStoodOn


            // Only drag entities that aren't mounted to vehicles
            if (shipDraggingEntity != null && entity.vehicle == null && isDraggable(entity)) {
                if (entityDraggingInformation.isEntityBeingDraggedByAShip()) {
                    // Compute how much we should drag the entity
                    val shipData = entity.level().shipObjectWorld.allShips.getById(shipDraggingEntity)
                    if (shipData != null) {
                        dragTheEntity = true
                        val entityReferencePos: Vector3dc = if (preTick) {
                            Vector3d(entity.x, entity.y, entity.z)
                        } else {
                            Vector3d(entity.xo, entity.yo, entity.zo)
                        }

                        val referenceTransform = if (shipData is ClientShip) shipData.transform else shipData.transform

                        // region Compute position dragging
                        val newPosIdeal: Vector3dc = referenceTransform.shipToWorld.transformPosition(
                            shipData.prevTickTransform.worldToShip.transformPosition(
                                Vector3d(entityReferencePos)
                            )
                        )
                        addedMovement = newPosIdeal.sub(entityReferencePos, Vector3d())
                        // endregion

                        // region Compute look dragging
                        val yViewRot = entity.yRot.toDouble()

                        // Get the y-look vector of the entity only using y-rotation, ignore x-rotation
                        val entityLookYawOnly =
                            Vector3d(sin(-Math.toRadians(yViewRot)), 0.0, cos(-Math.toRadians(yViewRot)))

                        val newLookIdeal = referenceTransform.shipToWorld.transformDirection(
                            shipData.prevTickTransform.worldToShip.transformDirection(
                                entityLookYawOnly
                            )
                        )

                        // Get the X and Y rotation from [newLookIdeal]
                        val newXRot = asin(-newLookIdeal.y())
                        val xRotCos = cos(newXRot)
                        val newYRot = -atan2(newLookIdeal.x() / xRotCos, newLookIdeal.z() / xRotCos)

                        // The Y rotation of the entity before dragging
                        var entityYRotCorrected = entity.yRot % 360.0
                        // Limit [entityYRotCorrected] to be between -180 to 180 degrees
                        if (entityYRotCorrected <= -180.0) entityYRotCorrected += 360.0
                        if (entityYRotCorrected >= 180.0) entityYRotCorrected -= 360.0

                        // The Y rotation of the entity after dragging
                        val newYRotAsDegrees = Math.toDegrees(newYRot)
                        // Limit [addedYRotFromDragging] to be between -180 to 180 degrees
                        var addedYRotFromDragging = newYRotAsDegrees - entityYRotCorrected
                        if (addedYRotFromDragging <= -180.0) addedYRotFromDragging += 360.0
                        if (addedYRotFromDragging >= 180.0) addedYRotFromDragging -= 360.0

                        addedYRot = addedYRotFromDragging
                        // endregion
                    }
                } else {
                    addedMovement = Vector3d(entityDraggingInformation.addedMovementLastTick)
                    addedYRot = 0.0
                }
            }

            if (dragTheEntity && addedMovement != null && addedMovement.isFinite && addedYRot.isFinite()) {
                // TODO: Do collision on [addedMovement], as currently this can push players into
                //       blocks
                // Apply [addedMovement]
                val newBB = entity.boundingBox.move(addedMovement.toMinecraft())
                entity.boundingBox = newBB
                entity.setPos(
                    entity.x + addedMovement.x(),
                    entity.y + addedMovement.y(),
                    entity.z + addedMovement.z()
                )

                if(entityDraggingInformation.shouldImpulseMovement && (!entity.level().isClientSide || entity is LocalPlayer)) { //This is the first Tick on the ship. Also, should push the entity in server side only and propagate the result.
                    val acceleration = Vector3d(entityDraggingInformation.addedMovementLastTick) // if it was on a different ship last tick, consider that too.
                        .sub(addedMovement) // relative velocity to current ship.
                    entity.push(acceleration.x, acceleration.y, acceleration.z)
                }

                entityDraggingInformation.addedMovementLastTick = addedMovement

                // Apply [addedYRot]
                if (addedYRot.isFinite()) {
                    if (!entity.level().isClientSide()) {
                        if (entity !is ServerPlayer) {
                            entity.yRot = ((entity.yRot + addedYRot.toFloat()) + 360f) % 360f
                            entity.yHeadRot = ((entity.yHeadRot + addedYRot.toFloat()) + 360f) % 360f
                            if(entity is LivingEntity) {
                                entity.yBodyRot = ((entity.yBodyRot + addedYRot.toFloat()) + 360f) % 360f
                            }
                        } else {
                            entity.yRot = Mth.wrapDegrees(entity.yRot + addedYRot.toFloat())
                            entity.yHeadRot = Mth.wrapDegrees(entity.yHeadRot + addedYRot.toFloat())
                            entity.yBodyRot = Mth.wrapDegrees(entity.yBodyRot + addedYRot.toFloat())
                        }
                    } else {
                        if (!entity.isControlledByLocalInstance && entity !is Player) {
                            entity.yRot = Mth.wrapDegrees(entity.yRot + addedYRot.toFloat())
                            entity.yHeadRot = Mth.wrapDegrees(entity.yHeadRot + addedYRot.toFloat())
                            if(entity is LivingEntity) {
                                entity.yBodyRot = Mth.wrapDegrees(entity.yBodyRot + addedYRot.toFloat())
                            }
                        } else {
                            entity.yRot = (entity.yRot + addedYRot.toFloat())
                            entity.yHeadRot = (entity.yHeadRot + addedYRot.toFloat())
                            if(entity is LivingEntity) {
                                entity.yBodyRot = (entity.yBodyRot + addedYRot.toFloat())
                            }
                        }
                    }

                    entityDraggingInformation.addedYawRotLastTick = addedYRot
                }
            } else if ((!entity.level().isClientSide || entity is LocalPlayer) && entityDraggingInformation.addedMovementLastTick.length() > 1e-3) {
                entity.push(entityDraggingInformation.addedMovementLastTick.x(),
                    entityDraggingInformation.addedMovementLastTick.y(),
                    entityDraggingInformation.addedMovementLastTick.z())
                entityDraggingInformation.addedMovementLastTick = Vector3d()
                entityDraggingInformation.addedYawRotLastTick = 0.0
            }
            entityDraggingInformation.ticksSinceStoodOnShip++
            entityDraggingInformation.mountedToEntity = entity.vehicle != null
        }
    }

    /**
     * Checks if the entity is a ServerPlayer and has a [serverRelativePlayerPosition] set. If it does, returns that, which is in ship space; otherwise, returns worldspace entity position.
     */
    fun Entity.serversidePosition(): Vec3 {
        if (this is IEntityDraggingInformationProvider && this.draggingInformation.isEntityBeingDraggedByAShip()) {
            if (this.draggingInformation.bestRelativeEntityPosition() != null) {
                return this.draggingInformation.bestRelativeEntityPosition()!!.toMinecraft()
            }
        }
        return this.position()
    }

    /**
     * Checks if the entity is a ServerPlayer and has a [serverRelativePlayerPosition] set. If it does, returns that, which is in ship space; otherwise, returns worldspace eye position.
     */
    fun Entity.serversideEyePosition(): Vec3 {
        if (this is IEntityDraggingInformationProvider && this.draggingInformation.isEntityBeingDraggedByAShip()) {
            if (this.draggingInformation.bestRelativeEntityPosition() != null) {
                return this.draggingInformation.bestRelativeEntityPosition()!!.add(0.0, this.getEyeHeight(pose).toDouble(), 0.0,
                    Vector3d())!!.toMinecraft()
            }
        }
        return this.eyePosition
    }

    /**
     * Checks if the entity is a ServerPlayer and has a [serverRelativePlayerYaw] set. If it does, returns that, which is in ship space; otherwise, returns worldspace eye rotation.
     */
    fun Entity.serversideEyeRotation(): Double {
        if (this is ServerPlayer && this is IEntityDraggingInformationProvider && this.draggingInformation.isEntityBeingDraggedByAShip()) {
            if (this.draggingInformation.serverRelativePlayerYaw != null) {
                return this.draggingInformation.serverRelativePlayerYaw!! * 180.0 / Math.PI
            }
        }
        return this.yRot.toDouble()
    }

    /**
     * Checks if the entity is a ServerPlayer and has a [serverRelativePlayerPosition] set. If it does, returns that, which is in ship space; otherwise, returns a default value.
     */
    fun Entity.serversideEyePositionOrDefault(default: Vec3): Vec3 {
        if (this is ServerPlayer && this is IEntityDraggingInformationProvider && this.draggingInformation.isEntityBeingDraggedByAShip()) {
            if (this.draggingInformation.serverRelativePlayerPosition != null) {
                return this.draggingInformation.serverRelativePlayerPosition!!.toMinecraft()
            }
        }
        return default
    }

    /**
     * Checks if the entity is a ServerPlayer and has a [serverRelativePlayerYaw] set. If it does, returns that, which is in ship space; otherwise, returns a default value.
     */
    fun Entity.serversideEyeRotationOrDefault(default: Double): Double {
        if (this is ServerPlayer && this is IEntityDraggingInformationProvider && this.draggingInformation.isEntityBeingDraggedByAShip()) {
            if (this.draggingInformation.serverRelativePlayerYaw != null) {
                return Math.toDegrees(this.draggingInformation.serverRelativePlayerYaw!!)
            }
        }
        return default
    }


    fun Entity.serversideWorldEyeRotationOrDefault(ship: Ship, default: Double): Double {
        if (this is ServerPlayer && this is IEntityDraggingInformationProvider && this.draggingInformation.isEntityBeingDraggedByAShip()) {
            if (this.draggingInformation.serverRelativePlayerYaw != null) {
                return yawToWorld(ship, this.draggingInformation.serverRelativePlayerYaw!!)
            }
        }
        return default
    }

    @JvmStatic
    fun backOff(vec3: Vec3, ship: Ship, player: Player, cLevel: Level): Vec3 {
        // Like vanilla maybeBackOffFromEdge: only reduce horizontal movement (world X, Z).
        // Gravity (world Y) always passes through unchanged — vanilla returns Vec3(d, vec.y, e).
        // This prevents mid-air freeze and avoids shifting players on sloped ships.
        if (vec3.x == 0.0 && vec3.z == 0.0) {
            return vec3
        }

        // Entity dragging (ship movement/rotation) can push the player past the ship edge.
        // The while loops below can only REDUCE movement — they can't push the player back.
        // If no part of the hitbox has ground below (full AABB check, not point sampling),
        // skip backOff to let the player recover.
        val belowBBox = player.getBoundingBox().move(0.0, -player.maxUpStep().toDouble(), 0.0)
        if (cLevel.noCollision(player, belowBBox)) {
            val shipBBox = belowBBox.toJOML().transform(ship.worldToShip).toMinecraft()
            if (cLevel.noCollision(shipBBox)) {
                return vec3
            }
        }

        // Transform only the horizontal world movement into ship space for edge checks.
        // For tilted ships, this correctly distributes horizontal movement across all
        // ship-space axes, so all three while loops provide edge protection.
        val shipSpace = ship.worldToShip.transformDirection(Vector3d(vec3.x, 0.0, vec3.z), Vector3d())
        var d = shipSpace.x
        var e = shipSpace.y
        var f = shipSpace.z

        while (d != 0.0 && !isValidWalkablePosition(cLevel, ship, player, d, Direction.EAST)) {
            if (d < 0.025 && d >= -0.025) {
                d = 0.0
            } else if (d > 0.0) {
                d -= 0.025
            } else {
                d += 0.025
            }
        }

        while (f != 0.0 && !isValidWalkablePosition(cLevel, ship, player, f, Direction.SOUTH)) {
            if (f < 0.025 && f >= -0.025) {
                f = 0.0
            } else if (f > 0.0) {
                f -= 0.025
            } else {
                f += 0.025
            }
        }

        while (e != 0.0 && !isValidWalkablePosition(cLevel, ship, player, e, Direction.UP)) {
            if (e < 0.025 && e >= -0.025) {
                e = 0.0
            } else if (e > 0.0) {
                e -= 0.025
            } else {
                e += 0.025
            }
        }

        while (d != 0.0 && f != 0.0 && e != 0.0 &&
            !isValidWalkablePosition(cLevel, ship, player, d, Direction.EAST) &&
            !isValidWalkablePosition(cLevel, ship, player, f, Direction.SOUTH) &&
            !isValidWalkablePosition(cLevel, ship, player, e, Direction.UP)) {
            if (d < 0.025 && d >= -0.025) {
                d = 0.0
            } else if (d > 0.0) {
                d -= 0.025
            } else {
                d += 0.025
            }

            if (f < 0.025 && f >= -0.025) {
                f = 0.0
            } else if (f > 0.0) {
                f -= 0.025
            } else {
                f += 0.025
            }

            if (e < 0.025 && e >= -0.025) {
                e = 0.0
            } else if (e > 0.0) {
                e -= 0.025
            } else {
                e += 0.025
            }
        }

        // Horizontal fully blocked — return only gravity, like vanilla returning Vec3(0, vec.y, 0)
        if (d == 0.0 && e == 0.0 && f == 0.0) {
            return Vec3(0.0, vec3.y, 0.0)
        }

        // Transform reduced horizontal back to world space and combine with original gravity.
        // Like vanilla's `return new Vec3(d, vec.y, e)` — only horizontal is adjusted.
        // normalize+mul is needed because shipToWorld may include scaling (ShipTransform has
        // shipToWorldScaling), so transformDirection doesn't preserve length.
        val motionLength = sqrt(d * d + e * e + f * f)
        val adjusted = ship.shipToWorld.transformDirection(Vector3d(d, e, f)).normalize().mul(motionLength)
        return Vec3(adjusted.x, vec3.y, adjusted.z)
    }

    private fun isValidWalkablePosition(
        level: Level, ship: Ship, player: Player, step: Double, dir: Direction
    ): Boolean {
        // Matching vanilla's noCollision(entity, bbox.move(d, -maxUpStep, 0)) approach:
        // move the full bounding box to the potential position and check for ground support.
        // Unlike the old raycast approach, this accounts for the full hitbox width,
        // letting the player walk until their hitbox edge reaches the ship edge.
        //
        // Note: transforming an AABB to ship space produces a larger axis-aligned box that
        // encloses the rotated box. This makes edge protection slightly weaker on highly
        // rotated ships (detecting ground that isn't directly under the player). Acceptable
        // trade-off vs the old single-point raycast which was 0.3 blocks too restrictive.
        val offset = ship.shipToWorld.transformDirection(Vector3d(dir.step())).normalize().mul(step)
        val movedBBox = player.getBoundingBox().move(
            offset.x, offset.y - player.maxUpStep().toDouble(), offset.z
        )

        // Check world blocks for ground support
        if (!level.noCollision(player, movedBBox)) {
            return true
        }

        // Check ship blocks by transforming the AABB to ship space (= shipyard coordinates).
        // Ship blocks are stored in the shipyard, so noCollision at those coords checks them.
        val shipBBox = movedBBox.toJOML().transform(ship.worldToShip).toMinecraft()
        return !level.noCollision(shipBBox)
    }

    /**
     * Check if the given entity should be dragged. Shipyard entities and ones marked as non-draggable return false.
     */
    @JvmStatic
    fun isDraggable(entity: Entity): Boolean {
        return !VSEntityManager.isShipyardEntity(entity) && entity is IEntityDraggingInformationProvider && (entity as IEntityDraggingInformationProvider).`vs$shouldDrag`()
    }
}
