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
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Runs whenever a block is placed.
 *
 * 1. Placement fix: the game turns a rotatable block to face the player. For blocks that
 *    Rotatable! changed, this makes a plain placement come out unrotated. Pressing R still
 *    rotates the block one quarter turn per press, no matter which way the player faces.
 *    The game does not tell us whether R was pressed, so we work it out: the rotation the
 *    game picks for the player's facing is "no R". Any difference from that is R presses.
 * 2. Trace (/ra trace): writes every placement to mods/Rotatable!/trace.txt for testing.
 */
public final class PlacementHandler extends EntityEventSystem<EntityStore, PlaceBlockEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Path TRACE_FILE = Path.of("mods", BlockRotationManager.MOD_NAME, "trace.txt");

    private final BlockRotationManager manager;
    private volatile boolean tracing = false;
    private int counter = 0;

    public PlacementHandler(BlockRotationManager manager) {
        super(PlaceBlockEvent.class);
        this.manager = manager;
    }

    /** Switches the trace on or off and returns the new state. */
    public boolean toggleTrace() {
        tracing = !tracing;
        if (tracing) {
            counter = 0;
            writeTrace("Rotatable! placement trace, started " + LocalDateTime.now()
                    + "  (each line = one placed block, numbered in order)", false);
        }
        return tracing;
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull PlaceBlockEvent event) {
        try {
            Vector3i block = event.getTargetBlock();
            RotationTuple original = event.getRotation();
            String itemId = itemId(event);

            // Which way is the player facing? 0 = north, 1 = west, 2 = south, 3 = east.
            int facing = -1;
            String facingText = "unknown";
            HeadRotation head = archetypeChunk.getComponent(index, HeadRotation.getComponentType());
            if (head != null) {
                Vector3i dir = head.getHorizontalAxisDirection();
                facing = facingNumber(dir.x, dir.z);
                facingText = "dir=(x " + dir.x + ", z " + dir.z + ")";
            }

            String result = "left alone";
            if (facing >= 0 && itemId != null && manager.isChanged(itemId)) {
                var turns = original.yaw().getDeclaringClass().getEnumConstants(); // None, 90, 180, 270
                int current = original.yaw().ordinal();
                int rPresses = Math.floorMod(current - facing, 4); // 0 = plain placement
                if (rPresses != current) {
                    event.setRotation(RotationTuple.of(turns[rPresses], original.pitch(), original.roll()));
                    result = "changed to yaw=" + turns[rPresses];
                } else {
                    result = "already right";
                }
            }

            if (tracing) {
                writeTrace("#" + (++counter) + "  " + LocalTime.now().withNano(0)
                        + "  item=" + itemId
                        + "  facing: " + facingText
                        + "  block=(" + block.x + ", " + block.y + ", " + block.z + ")"
                        + "  game rotation: yaw=" + original.yaw()
                        + "  -> " + result, true);
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

    private static synchronized void writeTrace(String line, boolean append) {
        try {
            Files.createDirectories(TRACE_FILE.getParent());
            if (append) {
                Files.writeString(TRACE_FILE, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(TRACE_FILE, line + System.lineSeparator(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOGGER.atWarning().withCause(e).log("Rotatable!: could not write the trace file");
        }
    }
}