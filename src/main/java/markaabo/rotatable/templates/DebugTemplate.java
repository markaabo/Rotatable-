package markaabo.rotatable.templates;

import markaabo.rotatable.BlockRotationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * THE LAYOUT OF debug.txt (written by /rotatable debug).
 *
 * This is the one place to change how the debug file looks. Everything you may want to
 * change is in the "EDIT HERE" blocks below:
 *   1. ORDER        which parts appear, and in which order (delete a part to hide it,
 *                   move it to change where it goes).
 *   2. Texts        the titles and labels of every part.
 *   3. Format       indentation, the CHANGED marker and how a block line looks.
 *
 * The data itself (which blocks, which settings) comes from BlockRotationManager.
 */
public final class DebugTemplate {

    private DebugTemplate() {
    }

    // =====================================================================================
    // EDIT HERE (1): which parts the file has, top to bottom
    // =====================================================================================

    public enum Part {
        HEADER,             // name, version, time and whether the config was changed
        SETTINGS,           // every config setting (only changed ones are marked)
        SUMMARY,            // the totals
        AUTO,               // blocks given rotation by auto-detect
        INCLUDED,           // blocks given rotation by IncludedBlocks
        EXCLUDED,           // blocks stopped by ExcludedBlocks
        MODDED_SKIPPED,     // other-mod blocks that were left alone
        DEFAULT_RULES,      // blocks left alone by the built-in rules (dev, ore, fluid ...)
        ALREADY_ROTATABLE,  // blocks that already had rotation in the base game
        ROTATION_SETS       // the rotations each rotation mode allows
    }

    public static final Part[] ORDER = {
            Part.HEADER,
            Part.SETTINGS,
            Part.SUMMARY,
            // settings you can change come first, the built-in defaults last
            Part.AUTO,
            Part.INCLUDED,
            Part.EXCLUDED,
            Part.MODDED_SKIPPED,
            Part.DEFAULT_RULES,
            Part.ALREADY_ROTATABLE,
            Part.ROTATION_SETS
    };

    // =====================================================================================
    // EDIT HERE (2): texts
    // =====================================================================================

    static final String TITLE = BlockRotationManager.MOD_NAME + " debug report";
    static final String VERSION_LABEL = "Version: ";
    static final String GENERATED_LABEL = "Generated: ";
    static final String CONFIG_DEFAULT = "Config: default settings";
    static final String CONFIG_CUSTOM = "Config: custom settings (%d changed from default)";

    static final String SETTINGS_TITLE = "Settings";

    static final String SUMMARY_TITLE = "Summary";
    static final String SUMMARY_FULL_BLOCKS = "Full blocks detected: %d (%d from other mods)";
    static final String SUMMARY_GIVEN = "Blocks given rotation by Rotatable!: %d";
    static final String SUMMARY_ALREADY = "Already rotatable in the base game: %d";
    static final String SUMMARY_DEFAULT_RULES = "Ignored by the default rules: %d";

    // Block sections. The number of blocks is added after each title, like "Title (12)".
    static final String AUTO_TITLE = "Given rotation by Rotatable!: full blocks found by auto-detect";
    static final String INCLUDED_TITLE = "Given rotation by Rotatable!: blocks from your IncludedBlocks list";
    static final String EXCLUDED_TITLE = "Excluded by your ExcludedBlocks list (NOT changed)";
    static final String MODDED_SKIPPED_TITLE =
            "Full blocks from other mods that were NOT changed (AutoDetectModdedBlocks is off)";
    static final String DEFAULT_RULES_TITLE = "Full blocks ignored by the default rules "
            + "(dev, build, test, ore, fluid, metal, connected textures; NOT changed)";
    static final String ALREADY_TITLE =
            "Full blocks that already had rotation in the base game (NOT changed by Rotatable!)";

    static final String ROTATION_SETS_TITLE =
            "Rotation sets (what each rotation mode allows, besides the unrotated state)";

    // =====================================================================================
    // EDIT HERE (3): format
    // =====================================================================================

    /** Put in front of every line that belongs to a part. */
    static final String INDENT = "  ";

    /** Shown behind a setting that differs from its default. %s = the default value. */
    static final String CHANGED_MARKER = "  (CHANGED, default: %s)";

    /** A block line is: id, then the pack in brackets (if SHOW_PACK), then the note (if any). */
    static final boolean SHOW_PACK = true;
    static final String ENTRY_SEPARATOR = "  ";

    /** Empty line between two parts. */
    static final boolean BLANK_LINE_BETWEEN_PARTS = true;

    // =====================================================================================
    // Data handed over by BlockRotationManager (no need to edit)
    // =====================================================================================

    /** One config setting. */
    public record Setting(String name, String value, String defaultValue) {
        public boolean changed() {
            return !value.equalsIgnoreCase(defaultValue);
        }
    }

    /** One block in a list: its id, its asset pack and an optional note. */
    public record Entry(String id, String pack, String note) {
    }

    public record Data(String version, BlockRotationManager.Stats stats, BlockRotationManager.Report report,
                       List<Setting> settings, List<String> rotationSets) {
    }

    // =====================================================================================
    // Building the file (edit only if you want to change how a part is built)
    // =====================================================================================

    public static List<String> build(Data data) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < ORDER.length; i++) {
            if (i > 0 && BLANK_LINE_BETWEEN_PARTS) {
                lines.add("");
            }
            addPart(lines, ORDER[i], data);
        }
        return lines;
    }

    private static void addPart(List<String> lines, Part part, Data data) {
        var stats = data.stats();
        var report = data.report();
        switch (part) {
            case HEADER -> {
                lines.add(TITLE);
                lines.add(VERSION_LABEL + data.version());
                lines.add(GENERATED_LABEL + LocalDateTime.now());
                int changed = (int) data.settings().stream().filter(Setting::changed).count();
                lines.add(changed == 0 ? CONFIG_DEFAULT : String.format(CONFIG_CUSTOM, changed));
            }
            case SETTINGS -> {
                lines.add(SETTINGS_TITLE);
                for (Setting setting : data.settings()) {
                    lines.add(INDENT + setting.name() + ": " + setting.value()
                            + (setting.changed() ? String.format(CHANGED_MARKER, setting.defaultValue()) : ""));
                }
            }
            case SUMMARY -> {
                lines.add(SUMMARY_TITLE);
                lines.add(INDENT + String.format(SUMMARY_FULL_BLOCKS, stats.fullBlocks(), stats.moddedFullBlocks()));
                lines.add(INDENT + String.format(SUMMARY_GIVEN, stats.rotationAdded()));
                lines.add(INDENT + String.format(SUMMARY_ALREADY, stats.alreadyRotatable()));
                lines.add(INDENT + String.format(SUMMARY_DEFAULT_RULES, stats.builtInIgnored()));
            }
            case AUTO -> addBlockList(lines, AUTO_TITLE, report.auto());
            case INCLUDED -> addBlockList(lines, INCLUDED_TITLE, report.included());
            case EXCLUDED -> addBlockList(lines, EXCLUDED_TITLE, report.excluded());
            case MODDED_SKIPPED -> addBlockList(lines, MODDED_SKIPPED_TITLE, report.moddedSkipped());
            case DEFAULT_RULES -> addBlockList(lines, DEFAULT_RULES_TITLE, report.builtIn());
            case ALREADY_ROTATABLE -> addBlockList(lines, ALREADY_TITLE, report.alreadyRotatable());
            case ROTATION_SETS -> addTextBlock(lines, ROTATION_SETS_TITLE, data.rotationSets());
        }
    }

    private static void addBlockList(List<String> lines, String title, List<Entry> entries) {
        lines.add(title + " (" + entries.size() + ")");
        for (Entry entry : entries) {
            StringBuilder line = new StringBuilder(INDENT).append(entry.id());
            if (SHOW_PACK) {
                line.append(ENTRY_SEPARATOR).append('[').append(entry.pack()).append(']');
            }
            if (entry.note() != null) {
                line.append(ENTRY_SEPARATOR).append(entry.note());
            }
            lines.add(line.toString());
        }
    }

    /** A title followed by lines that BlockRotationManager already formatted. */
    private static void addTextBlock(List<String> lines, String title, List<String> body) {
        lines.add(title);
        lines.addAll(body);
    }
}