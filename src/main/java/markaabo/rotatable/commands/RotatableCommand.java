package markaabo.rotatable.commands;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import markaabo.rotatable.BlockRotationManager;
import markaabo.rotatable.RotatablePlugin;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

/** /rotatable (alias /ra): version, count, reload. */
public class RotatableCommand extends AbstractAsyncCommand {

    private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();

    private final RotatablePlugin plugin;

    public RotatableCommand(RotatablePlugin plugin, BlockRotationManager manager) {
        super("rotatable", "Shows the Rotatable version (subcommands: count, reload)");
        this.plugin = plugin;
        addAliases("ra");
        requireNoPermission(); // anyone may check the version
        addSubCommand(new CountCommand(manager));
        addSubCommand(new ReloadCommand(manager));
    }

    @Nonnull
    @Override
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext context) {
        String version = String.valueOf(plugin.getManifest().getVersion());
        context.sendMessage(Message.raw("Rotatable v" + version));
        context.sendMessage(Message.raw("Commands: /rotatable count, /rotatable reload  (shorthand: /ra ...)"));
        return CompletableFuture.completedFuture(null);
    }

    private static void sendStats(CommandContext context, BlockRotationManager.Stats stats) {
        context.sendMessage(Message.raw("Full blocks detected: " + stats.fullBlocks()));
        context.sendMessage(Message.raw("Rotation added by Rotatable: " + stats.rotationAdded()
                + " (" + stats.extraIncluded() + " of them from IncludedBlocks)"));
        context.sendMessage(Message.raw("Already rotatable in the base game: " + stats.alreadyRotatable()));
        context.sendMessage(Message.raw("Skipped by BlacklistedBlocks: " + stats.blacklisted()));
        context.sendMessage(Message.raw("AutoDetectFullBlocks: " + stats.autoDetect()));
    }

    /** /rotatable count */
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
            sendStats(context, manager.getStats());
            return CompletableFuture.completedFuture(null);
        }
    }

    /** /rotatable reload (uses the default permission, so server operators only) */
    private static final class ReloadCommand extends AbstractAsyncCommand {

        private final BlockRotationManager manager;

        ReloadCommand(BlockRotationManager manager) {
            super("reload", "Reloads config.json and updates the blocks");
            this.manager = manager;
        }

        @Nonnull
        @Override
        protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext context) {
            try {
                BlockRotationManager.Stats stats = manager.reload();
                context.sendMessage(Message.raw("Reloaded " + manager.getConfigFile().toAbsolutePath()));
                sendStats(context, stats);
            } catch (Exception e) {
                LOG.atSevere().withCause(e).log("Rotatable: reload failed");
                context.sendMessage(Message.raw("Reload failed: " + e.getMessage()));
            }
            return CompletableFuture.completedFuture(null);
        }
    }
}