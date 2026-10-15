package markaabo.rotatable;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import markaabo.rotatable.commands.RotatableCommand;

import javax.annotation.Nonnull;

public class RotatablePlugin extends JavaPlugin {

    private final BlockRotationManager rotationManager = new BlockRotationManager();

    public RotatablePlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        getCommandRegistry().registerCommand(new RotatableCommand(this, rotationManager));
        getLogger().atInfo().log("Rotatable! loaded!");
    }

    // Runs after the game's assets (including all block types) have loaded.
    @Override
    protected void start() {
        try {
            rotationManager.reload();
        } catch (Exception e) {
            getLogger().atSevere().withCause(e).log("Rotatable!: could not enable block rotation");
        }
    }
}