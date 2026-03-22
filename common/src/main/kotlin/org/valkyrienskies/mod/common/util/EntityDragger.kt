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
        // The while loops below can only REDUCE movement toward zero — they can't push
        // the player back onto the ship. If no ground exists below the player at all,
        // skip backOff entirely to let the player recover via normal movement.
        if (!hasGroundBelow(cLevel, player)) {
            return vec3
        }

        // Transform only the horizontal world movement into ship space for edge checks.
        val shipSpace = ship.worldToShip.transformDirection(Vector3d(vec3.x, 0.0, vec3.z), Vector3d())
        var d = shipSpace.x
        var f = shipSpace.z

        // Reduce ship-EAST component while destination has no ground below
        while (d != 0.0 && !isValidWalkablePosition(cLevel, ship, player, d, Direction.EAST)) {
            if (d < 0.025 && d >= -0.025) {
                d = 0.0
            } else if (d > 0.0) {
                d -= 0.025
            } else {
                d += 0.025
            }
        }

        // Reduce ship-SOUTH component while destination has no ground below
        while (f != 0.0 && !isValidWalkablePosition(cLevel, ship, player, f, Direction.SOUTH)) {
            if (f < 0.025 && f >= -0.025) {
                f = 0.0
            } else if (f > 0.0) {
                f -= 0.025
            } else {
                f += 0.025
            }
        }

        // Reduce both components together for diagonal edge case
        while (d != 0.0 && f != 0.0 &&
            !isValidWalkablePosition(cLevel, ship, player, d, Direction.EAST) &&
            !isValidWalkablePosition(cLevel, ship, player, f, Direction.SOUTH)) {
            if (d < 0.025 && d >= -0.025) d = 0.0 else if (d > 0.0) d -= 0.025 else d += 0.025
            if (f < 0.025 && f >= -0.025) f = 0.0 else if (f > 0.0) f -= 0.025 else f += 0.025
        }

        // Horizontal fully blocked — return only gravity
        if (d == 0.0 && f == 0.0) {
            return Vec3(0.0, vec3.y, 0.0)
        }

        // Transform reduced ship-space movement back to world space.
        // normalize().mul(motionLength) compensates for any ship scaling.
        val motionLength = sqrt(d * d + f * f)
        val adjusted = ship.shipToWorld.transformDirection(Vector3d(d, 0.0, f)).normalize().mul(motionLength)
        return Vec3(adjusted.x, vec3.y, adjusted.z)
    }

    /**
     * Check if there's walkable ground at the destination when moving [step] blocks
     * in the ship-space [dir] direction. Uses VS2's polygon collision system which
     * projects ship block collision shapes into world space, avoiding the AABB rotation
     * expansion that made the previous approach always find blocks.
     *
     * For world blocks: standard noCollision() check at destination.
     * For ship blocks: getShipPolygonsCollidingWithEntity() checks actual ship block
     * collision shapes in world space (no coordinate transform artifacts).
     */
    private fun isValidWalkablePosition(
        level: Level, ship: Ship, player: Player, step: Double, dir: Direction
    ): Boolean {
        val stepDir = ship.shipToWorld.transformDirection(Vector3d(dir.step())).normalize().mul(step)
        // Movement = step in ship direction + maxUpStep downward (world Y), matching vanilla
        val potentialMovement = Vec3(stepDir.x, -player.maxUpStep().toDouble(), stepDir.z)
        val movedBBox = player.getBoundingBox().move(potentialMovement)

        // Check world blocks at destination
        if (!level.noCollision(player, movedBBox)) return true

        // Check ship blocks using VS2's polygon collision (handles rotation correctly).
        // inflate(-0.1) shrinks the search bbox slightly to avoid false edge detection.
        val shipPolygons = EntityShipCollisionUtils.getShipPolygonsCollidingWithEntity(
            player, Vec3.ZERO, movedBBox.inflate(-0.1), level
        )
        return shipPolygons.isNotEmpty()
    }

    /**
     * Check if the player currently has any ground below them (within maxUpStep).
     * Used as an escape hatch: if entity dragging pushed the player fully past the
     * ship edge, skip backOff to let them recover via normal movement.
     */
    private fun hasGroundBelow(level: Level, player: Player): Boolean {
        val downMovement = Vec3(0.0, -player.maxUpStep().toDouble(), 0.0)
        val belowBBox = player.getBoundingBox().move(downMovement)

        // Check world blocks
        if (!level.noCollision(player, belowBBox)) return true

        // Check ship blocks via polygon collision
        val shipPolygons = EntityShipCollisionUtils.getShipPolygonsCollidingWithEntity(
            player, Vec3.ZERO, belowBBox.inflate(-0.1), level
        )
        return shipPolygons.isNotEmpty()
    }

    /**
     * Check if the given entity should be dragged. Shipyard entities and ones marked as non-draggable return false.
     */
    @JvmStatic
    fun isDraggable(entity: Entity): Boolean {
        return !VSEntityManager.isShipyardEntity(entity) && entity is IEntityDraggingInformationProvider && (entity as IEntityDraggingInformationProvider).`vs$shouldDrag`()
    }
}
