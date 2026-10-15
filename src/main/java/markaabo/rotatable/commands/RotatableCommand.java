package markaabo.rotatable.commands;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import markaabo.rotatable.BlockRotationManager;
import markaabo.rotatable.RotatablePlugin;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** /rotatable (alias /ra): version, count, reload, debug. */
public class RotatableCommand extends AbstractAsyncCommand {

    private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();
    private static final String MOD = BlockRotationManager.MOD_NAME;
    private static final String SEPARATOR = "----------------------------";

    private final RotatablePlugin plugin;

    public RotatableCommand(RotatablePlugin plugin, BlockRotationManager manager) {
        super("rotatable", "Shows the " + MOD + " version (subcommands: count, reload, debug)");
        this.plugin = plugin;
        addAliases("ra");
        requireNoPermission(); // anyone may check the version
        addSubCommand(new CountCommand(manager));
        addSubCommand(new ReloadCommand(manager));
        addSubCommand(new DebugCommand(plugin, manager));
    }

    @Nonnull
    @Override
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext context) {
        reply(context, MOD + "  v" + version(plugin), List.of(
                "Commands: count, reload, debug",
                "Usage: /rotatable <command>  or  /ra <command>"));
        return CompletableFuture.completedFuture(null);
    }

    // ----------------------------------------------------------- shared bits

    /** Every command answer is framed by separator lines, with a bold title. */
    private static void reply(CommandContext context, String title, List<String> lines) {
        context.sendMessage(Message.raw(SEPARATOR));
        context.sendMessage(Message.raw(title).bold(true));
        for (String line : lines) {
            context.sendMessage(Message.raw(line));
        }
        context.sendMessage(Message.raw(SEPARATOR));
    }

    private static String version(RotatablePlugin plugin) {
        return String.valueOf(plugin.getManifest().getVersion());
    }

    private static List<String> statLines(BlockRotationManager.Stats stats) {
        List<String> lines = new ArrayList<>();
        lines.add("Full blocks detected: " + stats.fullBlocks()
                + " (" + stats.moddedFullBlocks() + " from other mods)");
        lines.add("Rotation added: " + stats.rotationAdded()
                + " (" + stats.fromIncluded() + " from IncludedBlocks)");
        lines.add("Already rotatable in the base game: " + stats.alreadyRotatable());
        lines.add("Ignored by default rules (dev, test, ore, fluid, metal blocks): " + stats.builtInIgnored());
        lines.add("Blocked by BlacklistedBlocks: " + stats.blacklisted());
        lines.add("Other-mod blocks skipped: " + stats.moddedSkipped()
                + " (AutoDetectModdedBlocks is " + (stats.autoDetectModded() ? "on" : "off") + ")");
        return lines;
    }

    // -------------------------------------------------------------- /ra count

    private static final class CountCommand extends AbstractAsyncCommand {

        private final BlockRotationManager manager;

        CountCommand(BlockRotationManager manager) {
            super("count", "Shows how many full blocks were detected and how many got rotation");
            this.manager = manager;
            requireNoPermission();
        }

        @Nonnull
        @Override
        protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext context) {
            reply(context, MOD + "  Block count", statLines(manager.getStats()));
            return CompletableFuture.completedFuture(null);
        }
    }

    // ------------------------------------------------------------ /ra reload

    /** Uses the default permission, so server operators only. */
    private static final class ReloadCommand extends AbstractAsyncCommand {

        private final BlockRotationManager manager;

        ReloadCommand(BlockRotationManager manager) {
            super("reload", "Reloads the " + MOD + " config and updates the blocks");
            this.manager = manager;
        }

        @Nonnull
        @Override
        protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext context) {
            try {
                manager.reload();
                reply(context, MOD + "  Config reloaded", List.of());
            } catch (Exception e) {
                LOG.atSevere().withCause(e).log("Rotatable!: reload failed");
                reply(context, MOD + "  Reload failed", List.of(
                        String.valueOf(e.getMessage()),
                        "See the server log for details."));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    // ------------------------------------------------------------- /ra debug

    /** Uses the default permission, so server operators only. */
    private static final class DebugCommand extends AbstractAsyncCommand {

        private final RotatablePlugin plugin;
        private final BlockRotationManager manager;

        DebugCommand(RotatablePlugin plugin, BlockRotationManager manager) {
            super("debug", "Writes a debug file listing which blocks " + MOD + " changed, blocked or skipped");
            this.plugin = plugin;
            this.manager = manager;
        }

        @Nonnull
        @Override
        protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext context) {
            try {
                manager.writeDebugFile(version(plugin));
                reply(context, MOD + "  Debug file written", List.of(
                        "Saved as debug.txt in the Rotatable! folder inside the mods folder.",
                        "It lists changed, ignored, blocked and skipped block ids."));
            } catch (Exception e) {
                LOG.atSevere().withCause(e).log("Rotatable!: could not write the debug file");
                reply(context, MOD + "  Debug file failed", List.of(
                        String.valueOf(e.getMessage()),
                        "See the server log for details."));
            }
            return CompletableFuture.completedFuture(null);
        }
    }
}