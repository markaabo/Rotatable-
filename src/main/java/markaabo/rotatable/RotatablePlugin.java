package markaabo.rotatable;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import markaabo.rotatable.commands.RotatableCommand;

import javax.annotation.Nonnull;

public class RotatablePlugin extends JavaPlugin {

    private final BlockRotationManager rotationManager = new BlockRotationManager();
    private final PlacementHandler placementHandler = new PlacementHandler(rotationManager);

    public RotatablePlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        getCommandRegistry().registerCommand(new RotatableCommand(this, rotationManager, placementHandler));
        try {
            getEntityStoreRegistry().registerSystem(placementHandler);
        } catch (Exception e) {
            getLogger().atWarning().withCause(e).log("Rotatable!: could not start the placement handler");
        }
        getLogger().atInfo().log("Rotatable! loaded!");
    }

    @Override
    protected void start() {
        try {
            rotationManager.reload();
        } catch (Exception e) {
            getLogger().atSevere().withCause(e).log("Rotatable!: could not enable block rotation");
        }
    }
}