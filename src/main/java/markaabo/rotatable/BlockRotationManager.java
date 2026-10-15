package markaabo.rotatable;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.protocol.DrawType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.VariantRotation;
import com.hypixel.hytale.server.core.util.Config;
import markaabo.rotatable.config.RotatableConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Finds full blocks, gives them the "NESW" rotation mode (4 directions around the
 * vertical axis) so the R key works, and keeps that in sync with the config file.
 */
public final class BlockRotationManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Config location, relative to the server's working folder. */
    private static final Path CONFIG_DIRECTORY = Path.of("mods", "rotatable");
    private static final String CONFIG_NAME = "config";
    private static final Path CONFIG_FILE = CONFIG_DIRECTORY.resolve(CONFIG_NAME + ".json");

    /** Used only if the game can't tell us which pack a block came from. */
    private static final String FALLBACK_PACK = "Hytale:Hytale";

    private static final VariantRotation TARGET_ROTATION = VariantRotation.NESW;

    /** Numbers shown by /rotatable count. */
    public record Stats(boolean autoDetect, int fullBlocks, int rotationAdded,
                        int alreadyRotatable, int blacklisted, int extraIncluded) {
        static final Stats EMPTY = new Stats(true, 0, 0, 0, 0, 0);
    }

    private record Change(String id, BlockType original, BlockType replacement, boolean restore) {
    }

    private final Config<RotatableConfig> config =
            new Config<>(CONFIG_DIRECTORY, CONFIG_NAME, RotatableConfig.CODEC);
    private final Field rotationField = findVariantRotationField();

    /** id -> the untouched original block, for every block we have changed. */
    private final Map<String, BlockType> originals = new HashMap<>();
    private Stats stats = Stats.EMPTY;

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
                    LOGGER.atInfo().log("Rotatable: created default config at %s",
                            CONFIG_FILE.toAbsolutePath());
                } else {
                    LOGGER.atWarning().log("Rotatable: saved the config but %s does not exist",
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
        List<Pattern> included = toPatterns(settings.getIncludedBlocks());
        List<Pattern> blacklisted = toPatterns(settings.getBlacklistedBlocks());
        var assetMap = BlockType.getAssetMap();

        warnAboutUnknownIds("IncludedBlocks", settings.getIncludedBlocks());
        warnAboutUnknownIds("BlacklistedBlocks", settings.getBlacklistedBlocks());

        int fullBlocks = 0;
        int alreadyRotatable = 0;
        int blacklistedSkipped = 0;
        int extraIncluded = 0;
        Map<String, BlockType> wanted = new LinkedHashMap<>();

        for (Map.Entry<String, BlockType> entry : assetMap.getAssetMap().entrySet()) {
            String id = entry.getKey();
            // Judge every block by its original, untouched version.
            BlockType original = originals.getOrDefault(id, entry.getValue());

            boolean full = isFullBlock(id, original);
            if (full) {
                fullBlocks++;
            }

            if (original.getVariantRotation() != VariantRotation.None) {
                if (full) {
                    alreadyRotatable++;
                }
                continue;
            }

            boolean shouldRotate = (full && settings.isAutoDetectFullBlocks()) || matchesAny(included, id);
            if (!shouldRotate) {
                continue;
            }
            if (matchesAny(blacklisted, id)) {
                blacklistedSkipped++;
                continue;
            }

            wanted.put(id, original);
            if (!full) {
                extraIncluded++;
            }
        }

        // Work out what has to change compared to the last run.
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, BlockType> entry : originals.entrySet()) {
            if (!wanted.containsKey(entry.getKey())) {
                changes.add(new Change(entry.getKey(), entry.getValue(), entry.getValue(), true));
            }
        }
        for (Map.Entry<String, BlockType> entry : wanted.entrySet()) {
            if (!originals.containsKey(entry.getKey())) {
                BlockType copy = createRotatedCopy(entry.getKey(), entry.getValue());
                if (copy != null) {
                    changes.add(new Change(entry.getKey(), entry.getValue(), copy, false));
                }
            }
        }

        // Group by asset pack, then send each group through the normal asset system.
        Map<String, List<Change>> byPack = new LinkedHashMap<>();
        for (Change change : changes) {
            String pack = assetMap.getAssetPack(change.id());
            byPack.computeIfAbsent(pack != null ? pack : FALLBACK_PACK, key -> new ArrayList<>())
                    .add(change);
        }

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
                LOGGER.atSevere().withCause(e).log("Rotatable: could not update %d blocks in pack %s",
                        blocks.size(), group.getKey());
            }
        }

        stats = new Stats(settings.isAutoDetectFullBlocks(), fullBlocks, originals.size(),
                alreadyRotatable, blacklistedSkipped, extraIncluded);

        LOGGER.atInfo().log("Rotatable: %d full blocks detected, rotation added to %d "
                        + "(%d already rotatable, %d blacklisted). Config: %s",
                stats.fullBlocks(), stats.rotationAdded(), stats.alreadyRotatable(),
                stats.blacklisted(), CONFIG_FILE.toAbsolutePath());
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

    private BlockType createRotatedCopy(String id, BlockType original) {
        try {
            BlockType copy = original.clone(id);
            rotationField.set(copy, TARGET_ROTATION);
            return copy;
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Rotatable: could not prepare block %s", id);
            return null;
        }
    }

    // --------------------------------------------------------------- helpers

    private static void warnAboutUnknownIds(String listName, String[] ids) {
        for (String raw : ids) {
            String id = raw == null ? "" : raw.trim();
            if (id.isEmpty() || id.contains("*")) {
                continue;
            }
            if (BlockType.getAssetMap().getAsset(id) == null) {
                LOGGER.atWarning().log("Rotatable: %s contains an unknown block id: %s", listName, id);
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

    /** Finds the private field that stores a block's rotation mode (by type, not by name). */
    private static Field findVariantRotationField() {
        try {
            for (Field field : BlockType.class.getDeclaredFields()) {
                if (field.getType() == VariantRotation.class && !Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    return field;
                }
            }
        } catch (RuntimeException e) {
            LOGGER.atSevere().withCause(e).log("Rotatable: reflection on BlockType failed");
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