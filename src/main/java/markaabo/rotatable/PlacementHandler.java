package markaabo.rotatable;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Runs whenever a block is placed. The game turns a rotatable block to face the player. For
 * blocks that Rotatable! changed, this makes a plain placement come out unrotated. Pressing R
 * still rotates the block one quarter turn per press, no matter which way the player faces.
 * The game does not tell us whether R was pressed, so we work it out: the rotation the game
 * picks for the player's facing is "no R". Any difference from that is R presses.
 * Does nothing when the CardinalPlacement setting is on.
 */
public final class PlacementHandler extends EntityEventSystem<EntityStore, PlaceBlockEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** How close to the line between two directions (in quarter turns; 0.15 is about 13 degrees) both count as plain. */
    private static final double EDGE_TOLERANCE = 0.15;

    private final BlockRotationManager manager;
    private int loggedGeneration = -1;

    public PlacementHandler(BlockRotationManager manager) {
        super(PlaceBlockEvent.class);
        this.manager = manager;
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull PlaceBlockEvent event) {
        try {
            String itemId = itemId(event);
            boolean cardinal = manager.isCardinalPlacement();
            boolean ours = itemId != null && manager.isChanged(itemId);

            // One log line after every reload, to show what the handler decided.
            int generation = manager.generation();
            if (generation != loggedGeneration) {
                loggedGeneration = generation;
                LOGGER.atInfo().log("Rotatable!: first placement after reload #%d: item=%s, rotation added=%s, "
                        + "CardinalPlacement=%s", generation, itemId, ours, cardinal);
            }

            if (cardinal) {
                return;
            }
            if (!ours) {
                return; // not one of our blocks
            }
            HeadRotation head = archetypeChunk.getComponent(index, HeadRotation.getComponentType());
            if (head == null) {
                return;
            }

            // Which way is the player facing? 0 = north, 1 = west, 2 = south, 3 = east.
            // Close to the line between two directions the game and the server may disagree
            // by a tiny turn, so both directions count as "no R pressed" there.
            Vector3d look = head.getDirection();
            int facing;
            int adjacent;
            if (Math.abs(look.x) + Math.abs(look.z) < 0.0001) {
                // looking straight up or down: use the plain direction
                Vector3i dir = head.getHorizontalAxisDirection();
                facing = facingNumber(dir.x, dir.z);
                adjacent = facing;
            } else {
                double steps = Math.atan2(-look.x, -look.z) / (Math.PI / 2); // north 0, west 1, south 2 or -2, east -1
                long nearest = Math.round(steps);
                facing = Math.floorMod((int) nearest, 4);
                boolean nearEdge = Math.abs(steps - nearest) > 0.5 - EDGE_TOLERANCE;
                adjacent = nearEdge ? Math.floorMod((int) nearest + (steps > nearest ? 1 : -1), 4) : facing;
            }
            if (facing < 0) {
                return;
            }

            RotationTuple original = event.getRotation();
            var turns = original.yaw().getDeclaringClass().getEnumConstants(); // None, 90, 180, 270
            int current = original.yaw().ordinal();
            int rPresses = current == adjacent ? 0 : Math.floorMod(current - facing, 4); // 0 = plain placement
            if (rPresses != current) {
                event.setRotation(RotationTuple.of(turns[rPresses], original.pitch(), original.roll()));
            }
        } catch (RuntimeException e) {
            LOGGER.atWarning().withCause(e).log("Rotatable!: could not handle a block placement");
        }
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    /** North (z -1) = 0, west (x -1) = 1, south (z +1) = 2, east (x +1) = 3. */
    private static int facingNumber(int x, int z) {
        if (z < 0) {
            return 0;
        }
        if (x < 0) {
            return 1;
        }
        if (z > 0) {
            return 2;
        }
        if (x > 0) {
            return 3;
        }
        return -1;
    }

    /** The block id of the held item, or null. */
    @Nullable
    private static String itemId(PlaceBlockEvent event) {
        Object stack = event.getItemInHand();
        if (stack == null) {
            return null;
        }
        String text = String.valueOf(stack);
        int start = text.indexOf("itemId=");
        if (start < 0) {
            return null;
        }
        start += 7;
        int end = text.indexOf(',', start);
        return text.substring(start, end > start ? end : text.length()).trim();
    }
}