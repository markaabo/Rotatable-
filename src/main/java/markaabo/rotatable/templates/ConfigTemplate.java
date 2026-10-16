package markaabo.rotatable.templates;

/**
 * THE TEXT OF THE CONFIG FILE THAT ROTATABLE! CREATES ON FIRST START.
 *
 * This is the one place to change how the generated config looks.
 *
 * How to edit:
 *  - Everything between the two triple quotes below is written to mods/Rotatable!/config.json
 *    exactly as it is written here (the leading indentation is removed).
 *  - Lines starting with // are comments. Change, add or remove them freely.
 *  - Do NOT rename the keys in quotes (like "AutoDetectFullBlocks") and keep the default values
 *    in sync with RotatableConfig.java. Do not put a comma after the last entry.
 *  - Only newly created config files use this text. To see a change, delete your config.json
 *    and restart the server (this resets your settings).
 *  - "ConfigVersion": %VERSION% is filled in automatically. Keep it. A config file with another
 *    version is saved as config-old.json and replaced by a fresh one.
 */
public final class ConfigTemplate {

    private ConfigTemplate() {
    }

    private static final String TEXT = """
            {
              // Rotatable! configuration
              // Lines that start with // are comments and are ignored.
              // After changing something, run /rotatable reload (or /ra reload) in the game.

              // Config file version. Do not change this.
              "ConfigVersion": %VERSION%,

              // Makes every full block Rotatable with R key.
              //   true  = on (default)
              //   false = only the blocks listed in IncludedBlocks get rotation
              "AutoDetectFullBlocks": true,

              // Also auto-detect full blocks that come from other mods.
              "AutoDetectModdedBlocks": false,

              // EXPERIMENTAL!, Changes to this will make it so you wont get help with any bug report.
              //   Which rotations changed blocks get:
              //   "NESW" = 4 directions around the vertical axis (default)
              //   "Wall" = 2 directions (normal or turned 90 degrees)
              // Other options (UpDown, Pipe, DoublePipe, UpDownNESW, Debug, All)
              "RotationVariant": "NESW",

              // Enables placement based on player facing.
              //   false = (default)
              //   true.
              "CardinalPlacement": false,

              // Override the AutoDetection and add blocks that will them be Rotatable!
              // even if they are not full blocks or normally isn't included by default
              // Use * as a wildcard.
              // Example (one block ID per line):
              //   "IncludedBlocks": [
              //     "Ore_Iron_Shale",
              //     "Rock_Aqua_*"
              //   ],
              "IncludedBlocks": [],

              // Override the AutoDetection and exclude blocks from being Rotatable!.
              // Use * as a wildcard.
              // Example (one block ID per line):
              //   "ExcludedBlocks": [
              //     "Rock_Stone_Brick",
              //     "Soil_*"
              //   ]
              "ExcludedBlocks": []
            }
            """;

    /** The finished file text, with the config version filled in. */
    public static String text(int version) {
        return TEXT.replace("%VERSION%", String.valueOf(version));
    }
}