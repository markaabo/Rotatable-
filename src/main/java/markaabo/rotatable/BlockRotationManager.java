package markaabo.rotatable;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.protocol.DrawType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockPlacementSettings;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockPlacementSettings.RotationMode;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.VariantRotation;
import com.hypixel.hytale.server.core.util.Config;
import markaabo.rotatable.config.RotatableConfig;

import java.io.IOException;
import java.lang.reflect.Constructor;
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
 * Finds full blocks, gives them the "NESW" rotation mode (4 directions around the
 * vertical axis) so the R key works, and keeps that in sync with the config file.
 */
public final class BlockRotationManager {

    public static final String MOD_NAME = "Rotatable!";

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Config location, relative to the server's working folder. */
    private static final Path CONFIG_DIRECTORY = Path.of("mods", MOD_NAME);
    private static final String CONFIG_NAME = "config";
    private static final Path CONFIG_FILE = CONFIG_DIRECTORY.resolve(CONFIG_NAME + ".json");
    private static final Path DEBUG_FILE = CONFIG_DIRECTORY.resolve("debug.txt");

    /** The asset pack of the base game. Blocks from any other pack count as "modded". */
    private static final String BASE_GAME_PACK = "Hytale:Hytale";
    private static final String FALLBACK_PACK = BASE_GAME_PACK;

    private static final VariantRotation TARGET_ROTATION = VariantRotation.NESW;

    /** Numbers shown by /rotatable count. */
    public record Stats(boolean autoDetect, boolean autoDetectModded, int fullBlocks, int moddedFullBlocks,
                        int rotationAdded, int fromIncluded, int alreadyRotatable,
                        int blacklisted, int moddedSkipped) {
        static final Stats EMPTY = new Stats(true, false, 0, 0, 0, 0, 0, 0, 0);
    }

    /** Block lists written by /rotatable debug. */
    private record Report(List<String> auto, List<String> included,
                          List<String> blacklisted, List<String> moddedSkipped) {
        static final Report EMPTY = new Report(List.of(), List.of(), List.of(), List.of());
    }

    private record Change(String id, BlockType original, BlockType replacement, boolean restore) {
    }

    private final Config<RotatableConfig> config =
            new Config<>(CONFIG_DIRECTORY, CONFIG_NAME, RotatableConfig.CODEC);

    private final Field rotationField = findFieldByType(BlockType.class, VariantRotation.class);
    private final Field placementField = findFieldByType(BlockType.class, BlockPlacementSettings.class);
    private final Field modeField = findFieldByType(BlockPlacementSettings.class, RotationMode.class);

    /** id -> the untouched original block, for every block we have changed. */
    private final Map<String, BlockType> originals = new HashMap<>();
    private RotationMode appliedMode = null;
    private boolean placementWarningLogged = false;

    private Stats stats = Stats.EMPTY;
    private Report report = Report.EMPTY;

    public Path getConfigFile() {
        return CONFIG_FILE;
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
            config.load().join();
            if (Files.notExists(CONFIG_FILE)) {
                config.save().join();
                if (Files.exists(CONFIG_FILE)) {
                    LOGGER.atInfo().log("Rotatable!: created default config at %s",
                            CONFIG_FILE.toAbsolutePath());
                } else {
                    LOGGER.atWarning().log("Rotatable!: saved the config but %s does not exist",
                            CONFIG_FILE.toAbsolutePath());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not read " + CONFIG_FILE + ": " + rootMessage(e), e);
        }
    }

    // ---------------------------------------------------------------- blocks

    private void applyConfig() {
        if (rotationField == null) {
            throw new IllegalStateException(
                    "Could not find the rotation field on BlockType (the game may have changed)");
        }

        RotatableConfig settings = config.get();
        RotationMode placementMode = parsePlacementMode(settings.getPlacementRotationMode());
        List<Pattern> included = toPatterns(settings.getIncludedBlocks());
        List<Pattern> blacklisted = toPatterns(settings.getBlacklistedBlocks());
        var assetMap = BlockType.getAssetMap();

        warnAboutUnknownIds("IncludedBlocks", settings.getIncludedBlocks());
        warnAboutUnknownIds("BlacklistedBlocks", settings.getBlacklistedBlocks());

        int fullBlocks = 0;
        int moddedFullBlocks = 0;
        int alreadyRotatable = 0;
        Map<String, BlockType> wanted = new LinkedHashMap<>();
        List<String> wantedAuto = new ArrayList<>();
        List<String> wantedIncluded = new ArrayList<>();
        List<String> blacklistedIds = new ArrayList<>();
        List<String> moddedSkippedIds = new ArrayList<>();
        Map<String, String> packOf = new HashMap<>();

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
                    alreadyRotatable++;
                }
                continue;
            }

            boolean isIncluded = matchesAny(included, id);
            boolean autoPick = full && settings.isAutoDetectFullBlocks()
                    && (!modded || settings.isAutoDetectModdedBlocks());

            if (!isIncluded && !autoPick) {
                if (full && settings.isAutoDetectFullBlocks() && modded) {
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
        boolean modeChanged = !originals.isEmpty() && appliedMode != placementMode;
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, BlockType> entry : originals.entrySet()) {
            if (!wanted.containsKey(entry.getKey())) {
                changes.add(new Change(entry.getKey(), entry.getValue(), entry.getValue(), true));
            }
        }
        for (Map.Entry<String, BlockType> entry : wanted.entrySet()) {
            boolean alreadyApplied = originals.containsKey(entry.getKey());
            if (alreadyApplied && !modeChanged) {
                continue;
            }
            BlockType copy = createRotatedCopy(entry.getKey(), entry.getValue(), placementMode);
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
            appliedMode = placementMode;
        }

        report = new Report(
                describe(wantedAuto, packOf, true),
                describe(wantedIncluded, packOf, true),
                describe(blacklistedIds, packOf, false),
                describe(moddedSkippedIds, packOf, false));

        stats = new Stats(settings.isAutoDetectFullBlocks(), settings.isAutoDetectModdedBlocks(),
                fullBlocks, moddedFullBlocks, originals.size(), report.included().size(),
                alreadyRotatable, blacklistedIds.size(), moddedSkippedIds.size());

        LOGGER.atInfo().log("Rotatable!: %d full blocks detected (%d from other mods), rotation added to %d "
                        + "(%d already rotatable, %d blacklisted, %d other-mod blocks skipped). Config: %s",
                stats.fullBlocks(), stats.moddedFullBlocks(), stats.rotationAdded(),
                stats.alreadyRotatable(), stats.blacklisted(), stats.moddedSkipped(),
                CONFIG_FILE.toAbsolutePath());
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

    private BlockType createRotatedCopy(String id, BlockType original, RotationMode placementMode) {
        try {
            BlockType copy = original.clone(id);
            rotationField.set(copy, TARGET_ROTATION);
            if (placementMode != null) {
                applyPlacementMode(copy, placementMode);
            }
            return copy;
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Rotatable!: could not prepare block %s", id);
            return null;
        }
    }

    /** Gives the block its own copy of the placement settings with the chosen rotation mode. */
    private void applyPlacementMode(BlockType block, RotationMode mode) {
        try {
            if (placementField == null || modeField == null) {
                throw new IllegalStateException("placement fields not found");
            }
            BlockPlacementSettings source = (BlockPlacementSettings) placementField.get(block);

            Constructor<BlockPlacementSettings> constructor =
                    BlockPlacementSettings.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            BlockPlacementSettings fresh = constructor.newInstance();

            if (source != null) {
                for (Field field : BlockPlacementSettings.class.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    field.set(fresh, field.get(source));
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

    /** Writes mods/Rotatable!/debug.txt and returns its path. */
    public synchronized Path writeDebugFile(String version) throws IOException {
        Files.createDirectories(CONFIG_DIRECTORY);
        RotatableConfig settings = config.get();

        List<String> lines = new ArrayList<>();
        lines.add(MOD_NAME + " debug report");
        lines.add("Version: " + version);
        lines.add("Generated: " + LocalDateTime.now());
        lines.add("Config: " + CONFIG_FILE.toAbsolutePath());
        lines.add("");
        lines.add("Settings");
        lines.add("  AutoDetectFullBlocks: " + settings.isAutoDetectFullBlocks());
        lines.add("  AutoDetectModdedBlocks: " + settings.isAutoDetectModdedBlocks());
        lines.add("  PlacementRotationMode: " + settings.getPlacementRotationMode());
        lines.add("  IncludedBlocks: " + String.join(", ", settings.getIncludedBlocks()));
        lines.add("  BlacklistedBlocks: " + String.join(", ", settings.getBlacklistedBlocks()));
        lines.add("");
        lines.add("Summary");
        lines.add("  Full blocks detected: " + stats.fullBlocks() + " (" + stats.moddedFullBlocks() + " from other mods)");
        lines.add("  Rotation added: " + stats.rotationAdded());
        lines.add("  Already rotatable in the base game: " + stats.alreadyRotatable());
        lines.add("");

        addSection(lines, "Rotation added (auto-detected)", report.auto());
        addSection(lines, "Rotation added (from IncludedBlocks)", report.included());
        addSection(lines, "Blocked by BlacklistedBlocks", report.blacklisted());
        addSection(lines, "Full blocks from other mods that were NOT changed (AutoDetectModdedBlocks is off)",
                report.moddedSkipped());

        Files.write(DEBUG_FILE, lines, StandardCharsets.UTF_8);
        return DEBUG_FILE;
    }

    private static void addSection(List<String> lines, String title, List<String> entries) {
        lines.add(title + " (" + entries.size() + ")");
        for (String entry : entries) {
            lines.add("  " + entry);
        }
        lines.add("");
    }

    private List<String> describe(List<String> ids, Map<String, String> packOf, boolean onlyApplied) {
        List<String> lines = new ArrayList<>();
        for (String id : ids) {
            if (onlyApplied && !originals.containsKey(id)) {
                continue;
            }
            lines.add(id + "  [" + packOf.getOrDefault(id, "unknown pack") + "]");
        }
        Collections.sort(lines);
        return lines;
    }

    // --------------------------------------------------------------- helpers

    /** Turns the config text into a rotation mode. Returns null for "Keep" (don't change it). */
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