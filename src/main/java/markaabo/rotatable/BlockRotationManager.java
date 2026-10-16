package markaabo.rotatable;

import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.protocol.DrawType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockPlacementSettings;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockPlacementSettings.RotationMode;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.VariantRotation;
import markaabo.rotatable.config.RotatableConfig;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Finds full blocks, gives them a rotation mode (default "NESW": 4 directions around the
 * vertical axis) so the R key works, and keeps that in sync with the config file.
 */
public final class BlockRotationManager {

    public static final String MOD_NAME = "Rotatable!";

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Config location, relative to the server's working folder. */
    private static final Path CONFIG_DIRECTORY = Path.of("mods", MOD_NAME);
    private static final Path CONFIG_FILE = CONFIG_DIRECTORY.resolve("config.json");
    private static final Path DEBUG_FILE = CONFIG_DIRECTORY.resolve("debug.txt");
    /** Ids of the blocks Rotatable! has changed. The game can keep our changes across restarts. */
    private static final Path CHANGED_FILE = CONFIG_DIRECTORY.resolve("changed-blocks.txt");

    /** The asset pack of the base game. Blocks from any other pack count as "modded". */
    private static final String BASE_GAME_PACK = "Hytale:Hytale";
    private static final String FALLBACK_PACK = BASE_GAME_PACK;

    private static final VariantRotation DEFAULT_VARIANT = VariantRotation.NESW;

    /**
     * Blocks that auto-detect never touches (* is a wildcard). Add more patterns here if you
     * find other blocks that should be ignored. IncludedBlocks in the config can still add them.
     */
    private static final String[] BUILT_IN_EXCLUSIONS = {
            // developer, build and test blocks
            "Prototype_*", "Template_*", "Test_*", "*_Test", "*_Test_*", "Debug_*", "Dev_*", "Build_*",
            // special blocks
            "Greenscreen*",
            // fluids
            "Fluid_*", "*_Fluid", "*_Fluid_*",
            // ores
            "Ore_*", "*_Ore", "*_Ore_*",
            // ingredients that happen to be placeable cubes (hay and similar)
            "Ingredient_*",
            // metal blocks (their texture depends on the blocks around them)
            "Metal_*"
    };
    private static final List<Pattern> BUILT_IN_PATTERNS = toPatterns(BUILT_IN_EXCLUSIONS);

    /** Any block whose texture changes depending on its neighbours is skipped too. */
    private static final String CONNECTED_RULE = "connected textures";

    /** Vanilla blocks shown in the debug file's placement check. */
    private static final List<String> PLACEMENT_SAMPLES = List.of(
            "Rock_Shale_Brick", "Rock_Shale_Cobble", "Rock_Shale_Cobble_Corner", "Rock_Shale_Stairs",
            "Rock_Shale_Beam", "Rock_Shale_Brick_Wall", "Rock_Shale_Brick_Pillar_Middle");

    /** The file created on first start. Keep the values in sync with RotatableConfig. */
    private static final String DEFAULT_CONFIG_TEXT = """
            {
              // Rotatable! configuration
              // Lines that start with // are comments and are ignored.
              // After changing something, run /rotatable reload (or /ra reload) in the game.
              // Do not put a comma after the last entry of the file or of a list.

              // Give every full block (solid 1x1x1 cube) the R-key rotation.
              // Stairs, roofs, slabs, walls and dev, test, ore, fluid and metal blocks are skipped.
              //   true  = on (default)
              //   false = only the blocks listed in IncludedBlocks get rotation
              "AutoDetectFullBlocks": true,

              // Also auto-detect full blocks that come from other mods.
              //   false = base game blocks only (default)
              //   true  = other mods' full blocks too
              "AutoDetectModdedBlocks": false,

              // Which rotations changed blocks get:
              //   "NESW" = 4 directions around the vertical axis (default)
              //   "Wall" = 2 directions (normal or turned 90 degrees)
              // Other names (UpDown, Pipe, DoublePipe, UpDownNESW, Debug, All) also tip blocks
              // over onto other axes, so they are not recommended for full blocks.
              "RotationVariant": "NESW",

              // Advanced and experimental: how the game picks the starting direction when a
              // changed block is placed. This may have no visible effect in the current game version.
              //   "Keep" = leave it alone (default)
              //   "Default", "BlockNormal", "FacingPlayer", "StairFacingPlayer"
              "PlacementRotationMode": "Keep",

              // Blocks that always get rotation, even if they are not full blocks or are skipped
              // by default (ores, test blocks and so on). Use * as a wildcard.
              // Example: ["Ore_Iron_Shale", "Rock_Aqua_*"]
              "IncludedBlocks": [],

              // Blocks that never get rotation. This overrides everything above.
              // Example: ["Rock_Stone_Brick", "Soil_*"]
              "BlacklistedBlocks": []
            }
            """;

    /** Numbers shown by /rotatable count. */
    public record Stats(boolean autoDetect, boolean autoDetectModded, int fullBlocks, int moddedFullBlocks,
                        int rotationAdded, int fromIncluded, int alreadyRotatable, int builtInIgnored,
                        int blacklisted, int moddedSkipped) {
        static final Stats EMPTY = new Stats(true, false, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** Block lists written by /rotatable debug. */
    private record Report(List<String> auto, List<String> included, List<String> blacklisted,
                          List<String> moddedSkipped, List<String> builtIn, List<String> alreadyRotatable) {
        static final Report EMPTY =
                new Report(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private record Change(String id, BlockType original, BlockType replacement, boolean restore) {
    }

    private record SettingsView(List<String> lines, int changed) {
    }

    private final Field rotationField = findFieldByType(BlockType.class, VariantRotation.class);
    private final Field placementField = findFieldByType(BlockType.class, BlockPlacementSettings.class);
    private final Field modeField = findFieldByType(BlockPlacementSettings.class, RotationMode.class);

    private RotatableConfig current = new RotatableConfig();

    /** id -> the untouched original block, for every block we have changed. */
    private final Map<String, BlockType> originals = new HashMap<>();
    private VariantRotation appliedVariant = null;
    private RotationMode appliedMode = null;
    private boolean placementWarningLogged = false;
    private boolean recordLoaded = false;

    private Stats stats = Stats.EMPTY;
    private Report report = Report.EMPTY;

    /** True if Rotatable! has given this block its rotation. */
    public synchronized boolean isChanged(String id) {
        return originals.containsKey(id);
    }

    public synchronized Stats getStats() {
        return stats;
    }

    /** Reads the config file, then updates the blocks to match it. */
    public synchronized Stats reload() {
        loadConfig();
        applyConfig();
        return stats;
    }

    // ---------------------------------------------------------------- config

    private void loadConfig() {
        try {
            Files.createDirectories(CONFIG_DIRECTORY);
            if (Files.notExists(CONFIG_FILE)) {
                Files.writeString(CONFIG_FILE, DEFAULT_CONFIG_TEXT, StandardCharsets.UTF_8);
                LOGGER.atInfo().log("Rotatable!: created default config (%s)", CONFIG_FILE);
            }

            // The game's JSON reader is not documented to accept comments, so we remove them
            // ourselves and give the game a clean temporary copy to read.
            String clean = stripComments(Files.readString(CONFIG_FILE, StandardCharsets.UTF_8));
            Path temp = Files.createTempFile(CONFIG_DIRECTORY, "config-read-", ".tmp");
            try {
                Files.writeString(temp, clean, StandardCharsets.UTF_8);
                RotatableConfig loaded = RawJsonReader.readSync(temp, RotatableConfig.CODEC, LOGGER);
                if (loaded == null) {
                    throw new IllegalStateException("the file is empty or invalid");
                }
                current = loaded;
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not read the Rotatable! config file: " + rootMessage(e), e);
        }
    }

    /** Removes // and /* *\/ comments (outside of quoted text) and a leading byte-order mark. */
    private static String stripComments(String text) {
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < text.length()) {
                    out.append(text.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    i++;
                }
                out.append('\n');
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 1;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- blocks

    private void applyConfig() {
        if (rotationField == null) {
            throw new IllegalStateException(
                    "Could not find the rotation field on BlockType (the game may have changed)");
        }

        RotatableConfig settings = current;
        VariantRotation variant = parseVariant(settings.getRotationVariant());
        RotationMode placementMode = parsePlacementMode(settings.getPlacementRotationMode());
        List<Pattern> included = toPatterns(settings.getIncludedBlocks());
        List<Pattern> blacklisted = toPatterns(settings.getBlacklistedBlocks());
        var assetMap = BlockType.getAssetMap();

        adoptBlocksFromLastRun();

        warnAboutUnknownIds("IncludedBlocks", settings.getIncludedBlocks());
        warnAboutUnknownIds("BlacklistedBlocks", settings.getBlacklistedBlocks());

        int fullBlocks = 0;
        int moddedFullBlocks = 0;
        Map<String, BlockType> wanted = new LinkedHashMap<>();
        List<String> wantedAuto = new ArrayList<>();
        List<String> wantedIncluded = new ArrayList<>();
        List<String> builtInIds = new ArrayList<>();
        List<String> blacklistedIds = new ArrayList<>();
        List<String> moddedSkippedIds = new ArrayList<>();
        List<String> alreadyIds = new ArrayList<>();
        Map<String, String> packOf = new HashMap<>();
        Map<String, String> noteOf = new HashMap<>();

        for (Map.Entry<String, BlockType> entry : assetMap.getAssetMap().entrySet()) {
            String id = entry.getKey();
            // Judge every block by its original, untouched version.
            BlockType original = originals.getOrDefault(id, entry.getValue());

            boolean full = isFullBlock(id, original);
            String pack = assetMap.getAssetPack(id);
            boolean modded = pack != null && !BASE_GAME_PACK.equals(pack);

            if (full) {
                fullBlocks++;
                if (modded) {
                    moddedFullBlocks++;
                }
            }

            if (original.getVariantRotation() != VariantRotation.None) {
                if (full) {
                    alreadyIds.add(id);
                    packOf.put(id, pack);
                    noteOf.put(id, "(rotation=" + original.getVariantRotation()
                            + ", placement mode=" + serverPlacementMode(original) + ")");
                }
                continue;
            }

            boolean isIncluded = matchesAny(included, id);
            boolean autoCandidate = full && settings.isAutoDetectFullBlocks();
            String rule = builtInRule(id, original);

            // Dev, ore, fluid, metal and similar blocks are skipped unless the config includes them.
            if (!isIncluded && autoCandidate && rule != null) {
                builtInIds.add(id);
                packOf.put(id, pack);
                noteOf.put(id, "(rule: " + rule + ")");
                continue;
            }

            boolean autoPick = autoCandidate && rule == null
                    && (!modded || settings.isAutoDetectModdedBlocks());

            if (!isIncluded && !autoPick) {
                if (autoCandidate && rule == null && modded) {
                    moddedSkippedIds.add(id);
                    packOf.put(id, pack);
                }
                continue;
            }

            packOf.put(id, pack);
            if (matchesAny(blacklisted, id)) {
                blacklistedIds.add(id);
                continue;
            }

            wanted.put(id, original);
            if (isIncluded) {
                wantedIncluded.add(id);
            } else {
                wantedAuto.add(id);
            }
        }

        // Work out what has to change compared to the last run.
        boolean settingsChanged = !originals.isEmpty()
                && (appliedMode != placementMode || appliedVariant != variant);
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, BlockType> entry : originals.entrySet()) {
            if (!wanted.containsKey(entry.getKey())) {
                changes.add(new Change(entry.getKey(), entry.getValue(), entry.getValue(), true));
            }
        }
        for (Map.Entry<String, BlockType> entry : wanted.entrySet()) {
            boolean alreadyApplied = originals.containsKey(entry.getKey());
            if (alreadyApplied && !settingsChanged) {
                continue;
            }
            BlockType copy = createRotatedCopy(entry.getKey(), entry.getValue(), variant, placementMode);
            if (copy != null) {
                changes.add(new Change(entry.getKey(), entry.getValue(), copy, false));
            }
        }

        // Group by asset pack, then send each group through the normal asset system.
        Map<String, List<Change>> byPack = new LinkedHashMap<>();
        for (Change change : changes) {
            String pack = assetMap.getAssetPack(change.id());
            byPack.computeIfAbsent(pack != null ? pack : FALLBACK_PACK, key -> new ArrayList<>())
                    .add(change);
        }

        boolean allOk = true;
        for (Map.Entry<String, List<Change>> group : byPack.entrySet()) {
            List<BlockType> blocks = new ArrayList<>();
            for (Change change : group.getValue()) {
                blocks.add(change.replacement());
            }
            try {
                BlockType.getAssetStore().loadAssets(group.getKey(), blocks);
                for (Change change : group.getValue()) {
                    if (change.restore()) {
                        originals.remove(change.id());
                    } else {
                        originals.put(change.id(), change.original());
                    }
                }
            } catch (Exception e) {
                allOk = false;
                LOGGER.atSevere().withCause(e).log("Rotatable!: could not update %d blocks in pack %s",
                        blocks.size(), group.getKey());
            }
        }
        if (allOk) {
            appliedVariant = variant;
            appliedMode = placementMode;
            saveChangedRecord();
        }

        report = new Report(
                describe(wantedAuto, packOf, noteOf, true),
                describe(wantedIncluded, packOf, noteOf, true),
                describe(blacklistedIds, packOf, noteOf, false),
                describe(moddedSkippedIds, packOf, noteOf, false),
                describe(builtInIds, packOf, noteOf, false),
                describe(alreadyIds, packOf, noteOf, false));

        stats = new Stats(settings.isAutoDetectFullBlocks(), settings.isAutoDetectModdedBlocks(),
                fullBlocks, moddedFullBlocks, originals.size(), report.included().size(),
                alreadyIds.size(), builtInIds.size(), blacklistedIds.size(), moddedSkippedIds.size());

        LOGGER.atInfo().log("Rotatable!: %d full blocks detected (%d from other mods), rotation added to %d "
                        + "(%d already rotatable, %d ignored by default rules, %d blacklisted, "
                        + "%d other-mod blocks skipped)",
                stats.fullBlocks(), stats.moddedFullBlocks(), stats.rotationAdded(),
                stats.alreadyRotatable(), stats.builtInIgnored(), stats.blacklisted(), stats.moddedSkipped());
    }

    /**
     * The game can keep our changes between restarts, so on a new start some blocks already
     * have rotation that came from Rotatable!. The record file tells us which ones. We take
     * them over (original = the same block without rotation) so they are handled like any
     * other changed block: the placement mode gets applied and they are restored if needed.
     */
    private void adoptBlocksFromLastRun() {
        var assetMap = BlockType.getAssetMap();
        if (recordLoaded) {
            return;
        }
        recordLoaded = true;
        if (Files.notExists(CHANGED_FILE)) {
            return;
        }
        try {
            int adopted = 0;
            for (String line : Files.readAllLines(CHANGED_FILE, StandardCharsets.UTF_8)) {
                String id = line.trim();
                BlockType now = id.isEmpty() ? null : assetMap.getAsset(id);
                if (now == null || now.getVariantRotation() == VariantRotation.None
                        || originals.containsKey(id)) {
                    continue;
                }
                BlockType original = now.clone(id);
                rotationField.set(original, VariantRotation.None);
                originals.put(id, original);
                adopted++;
            }
            if (adopted > 0) {
                LOGGER.atInfo().log("Rotatable!: %d blocks still had rotation from the last run, taking them over",
                        adopted);
            }
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Rotatable!: could not read the list of changed blocks");
        }
    }

    private void saveChangedRecord() {
        try {
            Files.createDirectories(CONFIG_DIRECTORY);
            List<String> ids = new ArrayList<>(originals.keySet());
            Collections.sort(ids);
            Files.write(CHANGED_FILE, ids, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.atWarning().withCause(e).log("Rotatable!: could not save the list of changed blocks");
        }
    }

    /** A full block: a solid, full-size cube. Stairs, roofs, slabs, walls etc. are models. */
    public static boolean isFullBlock(String id, BlockType block) {
        if (id == null || id.startsWith("*")) {
            return false; // generated state variants
        }
        if (block.isUnknown()) {
            return false;
        }
        if (BlockType.TECHNICAL_BLOCK_GROUP.equals(block.getGroup())) {
            return false;
        }
        return block.getDrawType() == DrawType.Cube
                && block.getMaterial() == BlockMaterial.Solid
                && "Full".equals(block.getHitboxType());
    }

    private BlockType createRotatedCopy(String id, BlockType original,
                                        VariantRotation variant, RotationMode placementMode) {
        try {
            BlockType copy = original.clone(id);
            rotationField.set(copy, variant);
            if (placementMode != null) {
                applyPlacementMode(copy, placementMode);
            }
            return copy;
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Rotatable!: could not prepare block %s", id);
            return null;
        }
    }

    /**
     * Gives the block its own copy of the placement settings with the chosen rotation mode.
     * Blocks without placement settings start from the game's own default settings.
     */
    private void applyPlacementMode(BlockType block, RotationMode mode) {
        try {
            if (placementField == null || modeField == null) {
                throw new IllegalStateException("placement fields not found");
            }
            BlockPlacementSettings source = (BlockPlacementSettings) placementField.get(block);
            BlockPlacementSettings template =
                    source != null ? source : BlockPlacementSettings.CODEC.getDefaultValue();

            BlockPlacementSettings fresh = BlockPlacementSettings.CODEC.getSupplier().get();
            if (template != null) {
                for (Field field : BlockPlacementSettings.class.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    field.set(fresh, field.get(template));
                }
            }
            modeField.set(fresh, mode);
            placementField.set(block, fresh);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!placementWarningLogged) {
                placementWarningLogged = true;
                LOGGER.atWarning().withCause(e).log(
                        "Rotatable!: could not change the placement rotation mode (rotation itself still works)");
            }
        }
    }

    // ----------------------------------------------------------- debug file

    /** Writes mods/Rotatable!/debug.txt. */
    public synchronized void writeDebugFile(String version) throws IOException {
        Files.createDirectories(CONFIG_DIRECTORY);
        SettingsView view = viewSettings();

        List<String> lines = new ArrayList<>();
        lines.add(MOD_NAME + " debug report");
        lines.add("Version: " + version);
        lines.add("Generated: " + LocalDateTime.now());
        lines.add(view.changed() == 0
                ? "Config: default settings"
                : "Config: custom settings (" + view.changed() + " changed from default)");
        lines.add("");
        lines.add("Settings");
        lines.addAll(view.lines());
        lines.add("");
        lines.add("Summary");
        lines.add("  Full blocks detected: " + stats.fullBlocks() + " (" + stats.moddedFullBlocks() + " from other mods)");
        lines.add("  Blocks given rotation by Rotatable!: " + stats.rotationAdded());
        lines.add("  Already rotatable in the base game: " + stats.alreadyRotatable());
        lines.add("  Ignored by the default rules: " + stats.builtInIgnored());
        lines.add("");

        // Settings you can change come first, the built-in defaults last.
        addSection(lines, "Given rotation by Rotatable!: full blocks found by auto-detect", report.auto());
        addSection(lines, "Given rotation by Rotatable!: blocks from your IncludedBlocks list", report.included());
        addSection(lines, "Blocked by BlacklistedBlocks", report.blacklisted());
        addSection(lines, "Full blocks from other mods that were NOT changed (AutoDetectModdedBlocks is off)",
                report.moddedSkipped());
        addSection(lines, "Full blocks ignored by the default rules "
                        + "(dev, build, test, ore, fluid, metal, connected textures; NOT changed)",
                report.builtIn());
        addSection(lines, "Full blocks that already had rotation in the base game (NOT changed by Rotatable!)",
                report.alreadyRotatable());

        addPlacementCheck(lines);
        addComparison(lines, "Rock_Shale_Cobble_Corner", "Rock_Shale_Cobble");
        addVariantReference(lines);

        Files.write(DEBUG_FILE, lines, StandardCharsets.UTF_8);
    }

    /** Settings; only the ones that differ from the default get a (CHANGED) marker. */
    private SettingsView viewSettings() {
        RotatableConfig now = current;
        RotatableConfig defaults = new RotatableConfig();
        List<String> lines = new ArrayList<>();
        int changed = 0;

        changed += addSetting(lines, "AutoDetectFullBlocks",
                String.valueOf(now.isAutoDetectFullBlocks()), String.valueOf(defaults.isAutoDetectFullBlocks()));
        changed += addSetting(lines, "AutoDetectModdedBlocks",
                String.valueOf(now.isAutoDetectModdedBlocks()), String.valueOf(defaults.isAutoDetectModdedBlocks()));
        changed += addSetting(lines, "RotationVariant",
                now.getRotationVariant().trim(), defaults.getRotationVariant());
        changed += addSetting(lines, "PlacementRotationMode",
                now.getPlacementRotationMode().trim(), defaults.getPlacementRotationMode());
        changed += addSetting(lines, "IncludedBlocks",
                listText(now.getIncludedBlocks()), listText(defaults.getIncludedBlocks()));
        changed += addSetting(lines, "BlacklistedBlocks",
                listText(now.getBlacklistedBlocks()), listText(defaults.getBlacklistedBlocks()));
        return new SettingsView(lines, changed);
    }

    private static int addSetting(List<String> lines, String name, String value, String defaultValue) {
        boolean same = value.equalsIgnoreCase(defaultValue);
        lines.add("  " + name + ": " + value
                + (same ? "" : "  (CHANGED, default: " + defaultValue + ")"));
        return same ? 0 : 1;
    }

    private static String listText(String[] entries) {
        return entries.length == 0 ? "none" : String.join(", ", entries);
    }

    private static void addSection(List<String> lines, String title, List<String> entries) {
        lines.add(title + " (" + entries.size() + ")");
        for (String entry : entries) {
            lines.add("  " + entry);
        }
        lines.add("");
    }

    /** Shows how some vanilla blocks are defined and what players are told about them. */
    private void addPlacementCheck(List<String> lines) {
        lines.add("Placement check (how selected blocks are defined, and what is sent to players)");
        var assetMap = BlockType.getAssetMap();
        for (String id : PLACEMENT_SAMPLES) {
            BlockType block = assetMap.getAsset(id);
            if (block == null) {
                lines.add("  " + id + ": not found");
                continue;
            }
            lines.add("  " + id + (originals.containsKey(id) ? "  (Changed by Rotatable!)" : ""));
            lines.add("    server: rotation=" + block.getVariantRotation()
                    + ", placement mode=" + serverPlacementMode(block));
            try {
                com.hypixel.hytale.protocol.BlockType packet = block.toPacket();
                String placement = packet.placementSettings == null
                        ? "none"
                        : packet.placementSettings.rotationMode
                        + ", R key allowed: " + packet.placementSettings.allowRotationKey;
                lines.add("    sent to players: rotation=" + packet.variantRotation
                        + ", placement mode=" + placement
                        + ", yaw offset=" + packet.rotationYawPlacementOffset);
            } catch (Exception e) {
                lines.add("    sent to players: unavailable (" + rootMessage(e) + ")");
            }
        }
        lines.add("");
    }

    /**
     * Lists every value that differs between two blocks, as sent to players. Used to find out
     * what a vanilla rotatable block has that our changed blocks do not.
     */
    private void addComparison(List<String> lines, String idA, String idB) {
        lines.add("Comparison: " + idA + " (vanilla rotation) vs " + idB + " (what is sent to players)");
        try {
            var assetMap = BlockType.getAssetMap();
            BlockType a = assetMap.getAsset(idA);
            BlockType b = assetMap.getAsset(idB);
            if (a == null || b == null) {
                lines.add("  one of the blocks was not found");
                lines.add("");
                return;
            }
            Object packetA = a.toPacket();
            Object packetB = b.toPacket();
            int shown = 0;
            for (Field field : packetA.getClass().getFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String valueA = valueText(field.get(packetA));
                String valueB = valueText(field.get(packetB));
                if (!valueA.equals(valueB)) {
                    lines.add("  " + field.getName() + ": " + valueA + "  |  " + valueB);
                    shown++;
                }
            }
            if (shown == 0) {
                lines.add("  no differences");
            }
        } catch (Exception e) {
            lines.add("  comparison failed: " + rootMessage(e));
        }
        lines.add("");
    }

    private static String valueText(Object value) {
        String text;
        if (value == null) {
            text = "null";
        } else if (value instanceof Object[] array) {
            text = java.util.Arrays.deepToString(array);
        } else if (value.getClass().isArray()) {
            text = value.getClass().getComponentType() == byte.class
                    ? "bytes[" + ((byte[]) value).length + "]" : "array";
        } else {
            text = String.valueOf(value);
        }
        return text.length() > 160 ? text.substring(0, 160) + "..." : text;
    }

    private String serverPlacementMode(BlockType block) {
        try {
            if (placementField == null || modeField == null) {
                return "unknown";
            }
            Object settings = placementField.get(block);
            return settings == null ? "no settings" : String.valueOf(modeField.get(settings));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "unknown";
        }
    }

    /** Lists the rotations each rotation mode allows (the unrotated state is not listed). */
    private static void addVariantReference(List<String> lines) {
        lines.add("Rotation sets (what each rotation mode allows, besides the unrotated state)");
        for (VariantRotation variant : VariantRotation.values()) {
            RotationTuple[] rotations = variant.getRotations();
            boolean yawOnly = true;
            StringBuilder list = new StringBuilder();
            for (RotationTuple tuple : rotations) {
                if (!"None".equals(String.valueOf(tuple.pitch())) || !"None".equals(String.valueOf(tuple.roll()))) {
                    yawOnly = false;
                }
                if (list.length() > 0) {
                    list.append(", ");
                }
                list.append(tuple.yaw()).append('/').append(tuple.pitch()).append('/').append(tuple.roll());
            }
            lines.add("  " + variant + ": " + rotations.length + " rotations, yaw only: " + yawOnly);
            lines.add("    (yaw/pitch/roll) " + list);
        }
        lines.add("");
    }

    private List<String> describe(List<String> ids, Map<String, String> packOf,
                                  Map<String, String> noteOf, boolean onlyApplied) {
        List<String> lines = new ArrayList<>();
        for (String id : ids) {
            if (onlyApplied && !originals.containsKey(id)) {
                continue;
            }
            String note = noteOf.get(id);
            lines.add(id + "  [" + packOf.getOrDefault(id, "unknown pack") + "]"
                    + (note != null ? "  " + note : ""));
        }
        Collections.sort(lines);
        return lines;
    }

    // --------------------------------------------------------------- helpers

    /** Turns the config text into a rotation mode set. Anything unknown falls back to NESW. */
    private static VariantRotation parseVariant(String text) {
        String wanted = text == null ? "" : text.trim();
        for (VariantRotation candidate : VariantRotation.values()) {
            if (candidate != VariantRotation.None && candidate.name().equalsIgnoreCase(wanted)) {
                return candidate;
            }
        }
        LOGGER.atWarning().log("Rotatable!: unknown RotationVariant '%s', using %s", text, DEFAULT_VARIANT);
        return DEFAULT_VARIANT;
    }

    /** Turns the config text into a placement mode. Returns null for "Keep" (don't change it). */
    private static RotationMode parsePlacementMode(String text) {
        String key = text == null ? "" : text.trim().replace("_", "").toLowerCase(Locale.ROOT);
        return switch (key) {
            case "default" -> RotationMode.DEFAULT;
            case "blocknormal" -> RotationMode.BLOCK_NORMAL;
            case "facingplayer" -> RotationMode.FACING_PLAYER;
            case "stairfacingplayer" -> RotationMode.STAIR_FACING_PLAYER;
            case "keep", "" -> null;
            default -> {
                LOGGER.atWarning().log("Rotatable!: unknown PlacementRotationMode '%s' "
                        + "(use Default, BlockNormal, FacingPlayer, StairFacingPlayer or Keep)", text);
                yield null;
            }
        };
    }

    /** Returns the default rule (pattern text or rule name) that skips this block, or null. */
    private static String builtInRule(String id, BlockType block) {
        for (int i = 0; i < BUILT_IN_PATTERNS.size(); i++) {
            if (BUILT_IN_PATTERNS.get(i).matcher(id).matches()) {
                return BUILT_IN_EXCLUSIONS[i];
            }
        }
        if (block.getConnectedBlockRuleSet() != null) {
            return CONNECTED_RULE;
        }
        return null;
    }

    private static void warnAboutUnknownIds(String listName, String[] ids) {
        for (String raw : ids) {
            String id = raw == null ? "" : raw.trim();
            if (id.isEmpty() || id.contains("*")) {
                continue;
            }
            if (BlockType.getAssetMap().getAsset(id) == null) {
                LOGGER.atWarning().log("Rotatable!: %s contains an unknown block id: %s", listName, id);
            }
        }
    }

    /** Turns "Rock_*" style entries into regular expressions. */
    private static List<Pattern> toPatterns(String[] globs) {
        List<Pattern> patterns = new ArrayList<>();
        for (String glob : globs) {
            if (glob == null || glob.isBlank()) {
                continue;
            }
            String[] parts = glob.trim().split("\\*", -1);
            StringBuilder regex = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    regex.append(".*");
                }
                regex.append(Pattern.quote(parts[i]));
            }
            patterns.add(Pattern.compile(regex.toString()));
        }
        return patterns;
    }

    private static boolean matchesAny(List<Pattern> patterns, String id) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(id).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Finds a private field by its type (not its name), so game updates that rename it don't break us. */
    private static Field findFieldByType(Class<?> owner, Class<?> type) {
        try {
            for (Field field : owner.getDeclaredFields()) {
                if (field.getType() == type && !Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    return field;
                }
            }
        } catch (RuntimeException e) {
            LOGGER.atSevere().withCause(e).log("Rotatable!: reflection on %s failed", owner.getSimpleName());
        }
        return null;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }
}