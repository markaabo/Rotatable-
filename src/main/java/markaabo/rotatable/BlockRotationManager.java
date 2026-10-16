package markaabo.rotatable;

import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.protocol.DrawType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.VariantRotation;
import markaabo.rotatable.config.RotatableConfig;
import markaabo.rotatable.templates.ConfigTemplate;
import markaabo.rotatable.templates.DebugTemplate;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
    /** A config file with another ConfigVersion is moved here and replaced by a fresh default one. */
    private static final Path OLD_CONFIG_FILE = CONFIG_DIRECTORY.resolve("config-old.json");
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
            // metal blocks (their texture depends on the surrounding blocks)
            "Metal_*"
    };
    private static final List<Pattern> BUILT_IN_PATTERNS = toPatterns(BUILT_IN_EXCLUSIONS);

    /** Any block whose texture changes depending on its neighboring blocks is skipped too. */
    private static final String CONNECTED_RULE = "connected textures";

    /** Numbers shown by /rotatable count. */
    public record Stats(boolean autoDetect, boolean autoDetectModded, int fullBlocks, int moddedFullBlocks,
                        int rotationAdded, int fromIncluded, int alreadyRotatable, int builtInIgnored,
                        int excluded, int moddedSkipped) {
        static final Stats EMPTY = new Stats(true, false, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** Block lists written by /rotatable debug. */
    public record Report(List<DebugTemplate.Entry> auto, List<DebugTemplate.Entry> included,
                         List<DebugTemplate.Entry> excluded, List<DebugTemplate.Entry> moddedSkipped,
                         List<DebugTemplate.Entry> builtIn, List<DebugTemplate.Entry> alreadyRotatable) {
        static final Report EMPTY =
                new Report(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private record Change(String id, BlockType original, BlockType replacement, boolean restore) {
    }

    private final Field rotationField = findFieldByType(BlockType.class, VariantRotation.class);

    private volatile RotatableConfig current = new RotatableConfig();

    /** id -> the untouched original block, for every block we have changed. */
    private final Map<String, BlockType> originals = new HashMap<>();
    /** The ids that should have rotation under the current config. Read by the placement handler. */
    private volatile Set<String> rotatableIds = Set.of();
    /** Counts finished reloads. */
    private final AtomicInteger generation = new AtomicInteger();
    private boolean recordLoaded = false;

    private Stats stats = Stats.EMPTY;
    private Report report = Report.EMPTY;

    /** True if the CardinalPlacement setting is on (placed blocks then face the player like before). */
    public boolean isCardinalPlacement() {
        return current.isCardinalPlacement();
    }

    /** True if this block should have rotation under the current config (does not wait for a reload). */
    public boolean isChanged(String id) {
        return rotatableIds.contains(id);
    }

    /** Number of finished reloads (0 = none yet). */
    public int generation() {
        return generation.get();
    }

    public synchronized Stats getStats() {
        return stats;
    }

    /** Reads the config file, then updates the blocks to match it. */
    public synchronized void reload() {
        loadConfig();
        applyConfig();
    }

    // ---------------------------------------------------------------- config

    private void loadConfig() {
        try {
            Files.createDirectories(CONFIG_DIRECTORY);
            if (Files.notExists(CONFIG_FILE)) {
                writeDefaultConfig();
                LOGGER.atInfo().log("Rotatable!: created default config");
            }

            RotatableConfig loaded = readConfigFile();
            if (loaded.getConfigVersion() != RotatableConfig.CURRENT_VERSION) {
                // An old or unknown layout: keep it as config-old.json and start fresh.
                Files.move(CONFIG_FILE, OLD_CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING);
                writeDefaultConfig();
                LOGGER.atWarning().log("Rotatable!: the config file had version %d but version %d is expected. "
                                + "It was saved as config-old.json and a new default config was created.",
                        loaded.getConfigVersion(), RotatableConfig.CURRENT_VERSION);
                loaded = readConfigFile();
            }
            current = loaded;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not read the Rotatable! config file: " + rootMessage(e), e);
        }
    }

    private void writeDefaultConfig() throws IOException {
        Files.writeString(CONFIG_FILE, ConfigTemplate.text(RotatableConfig.CURRENT_VERSION),
                StandardCharsets.UTF_8);
    }

    private RotatableConfig readConfigFile() throws IOException {
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
            return loaded;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Removes // and /* *\/ comments (outside quoted text) and a leading byte-order mark. */
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
        List<Pattern> included = toPatterns(settings.getIncludedBlocks());
        List<Pattern> excluded = toPatterns(settings.getExcludedBlocks());
        var assetMap = BlockType.getAssetMap();

        adoptBlocksFromLastRun();

        warnAboutUnknownIds("IncludedBlocks", settings.getIncludedBlocks());
        warnAboutUnknownIds("ExcludedBlocks", settings.getExcludedBlocks());

        int fullBlocks = 0;
        int moddedFullBlocks = 0;
        Map<String, BlockType> wanted = new LinkedHashMap<>();
        List<String> wantedAuto = new ArrayList<>();
        List<String> wantedIncluded = new ArrayList<>();
        List<String> builtInIds = new ArrayList<>();
        List<String> excludedIds = new ArrayList<>();
        List<String> moddedSkippedIds = new ArrayList<>();
        List<String> alreadyIds = new ArrayList<>();
        Map<String, String> packOf = new HashMap<>();
        Map<String, String> noteOf = new HashMap<>();

        for (Map.Entry<String, BlockType> entry : assetMap.getAssetMap().entrySet()) {
            String id = entry.getKey();
            // Judge every block by its original, untouched version. A block we changed before
            // counts as "had no rotation" (that is the only kind we change), whatever the stored
            // object looks like now.
            boolean ours = originals.containsKey(id);
            BlockType original = originals.getOrDefault(id, entry.getValue());
            VariantRotation originalVariant = ours ? VariantRotation.None : original.getVariantRotation();

            boolean full = isFullBlock(id, original);
            String pack = assetMap.getAssetPack(id);
            boolean modded = pack != null && !BASE_GAME_PACK.equals(pack);

            if (full) {
                fullBlocks++;
                if (modded) {
                    moddedFullBlocks++;
                }
            }

            if (originalVariant != VariantRotation.None) {
                if (full) {
                    alreadyIds.add(id);
                    packOf.put(id, pack);
                    noteOf.put(id, "(rotation=" + originalVariant + ")");
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
            if (matchesAny(excluded, id)) {
                excludedIds.add(id);
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
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, BlockType> entry : originals.entrySet()) {
            if (!wanted.containsKey(entry.getKey())) {
                BlockType restored = createRotatedCopy(entry.getKey(), entry.getValue(), VariantRotation.None);
                if (restored != null) {
                    changes.add(new Change(entry.getKey(), entry.getValue(), restored, true));
                }
            }
        }
        for (Map.Entry<String, BlockType> entry : wanted.entrySet()) {
            // Every reload sends all wanted blocks again (a hard reload), even if nothing changed.
            BlockType copy = createRotatedCopy(entry.getKey(), entry.getValue(), variant);
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
            saveChangedRecord();
        }
        rotatableIds = Set.copyOf(wanted.keySet());
        generation.incrementAndGet();

        report = new Report(
                describe(wantedAuto, packOf, noteOf, true),
                describe(wantedIncluded, packOf, noteOf, true),
                describe(excludedIds, packOf, noteOf, false),
                describe(moddedSkippedIds, packOf, noteOf, false),
                describe(builtInIds, packOf, noteOf, false),
                describe(alreadyIds, packOf, noteOf, false));

        stats = new Stats(settings.isAutoDetectFullBlocks(), settings.isAutoDetectModdedBlocks(),
                fullBlocks, moddedFullBlocks, originals.size(), report.included().size(),
                alreadyIds.size(), builtInIds.size(), excludedIds.size(), moddedSkippedIds.size());

        LOGGER.atInfo().log("Rotatable!: %d block updates sent to the game (%d restored)",
                changes.size(), changes.stream().filter(Change::restore).count());
        LOGGER.atInfo().log("Rotatable!: %d full blocks detected (%d from other mods), rotation added to %d "
                        + "(%d already rotatable, %d ignored by default rules, %d excluded, "
                        + "%d other-mod blocks skipped)",
                stats.fullBlocks(), stats.moddedFullBlocks(), stats.rotationAdded(),
                stats.alreadyRotatable(), stats.builtInIgnored(), stats.excluded(), stats.moddedSkipped());
    }

    /**
     * The game can keep our changes between restarts, so on a new start some blocks already
     * have rotation that came from Rotatable!. The record file tells us which ones. We take
     * them over (original = the same block without rotation) so they are handled like any
     * other changed block: they follow the config and are restored if needed.
     */
    private void adoptBlocksFromLastRun() {
        var assetMap = BlockType.getAssetMap();
        if (recordLoaded || rotationField == null) {
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
            LOGGER.atInfo().log("Rotatable!: saved %d changed block ids to changed-blocks.txt", ids.size());
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

    private BlockType createRotatedCopy(String id, BlockType original, VariantRotation variant) {
        try {
            if (rotationField == null) {
                return null;
            }
            BlockType copy = original.clone(id);
            rotationField.set(copy, variant);
            return copy;
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Rotatable!: could not prepare block %s", id);
            return null;
        }
    }

    // ----------------------------------------------------------- debug file

    /** Collects the data and writes mods/Rotatable!/debug.txt. The layout is in DebugTemplate.java. */
    public synchronized void writeDebugFile(String version) throws IOException {
        Files.createDirectories(CONFIG_DIRECTORY);
        List<String> lines = DebugTemplate.build(new DebugTemplate.Data(
                version, stats, report, collectSettings(), rotationSetLines()));
        Files.write(DEBUG_FILE, lines, StandardCharsets.UTF_8);
    }

    /** Every config setting with its current and default value. */
    private List<DebugTemplate.Setting> collectSettings() {
        RotatableConfig now = current;
        RotatableConfig defaults = new RotatableConfig();
        List<DebugTemplate.Setting> settings = new ArrayList<>();
        settings.add(new DebugTemplate.Setting("ConfigVersion",
                String.valueOf(now.getConfigVersion()), String.valueOf(RotatableConfig.CURRENT_VERSION)));
        settings.add(new DebugTemplate.Setting("AutoDetectFullBlocks",
                String.valueOf(now.isAutoDetectFullBlocks()), String.valueOf(defaults.isAutoDetectFullBlocks())));
        settings.add(new DebugTemplate.Setting("AutoDetectModdedBlocks",
                String.valueOf(now.isAutoDetectModdedBlocks()), String.valueOf(defaults.isAutoDetectModdedBlocks())));
        settings.add(new DebugTemplate.Setting("RotationVariant",
                now.getRotationVariant().trim(), defaults.getRotationVariant()));
        settings.add(new DebugTemplate.Setting("CardinalPlacement",
                String.valueOf(now.isCardinalPlacement()), String.valueOf(defaults.isCardinalPlacement())));
        settings.add(new DebugTemplate.Setting("IncludedBlocks",
                listText(now.getIncludedBlocks()), listText(defaults.getIncludedBlocks())));
        settings.add(new DebugTemplate.Setting("ExcludedBlocks",
                listText(now.getExcludedBlocks()), listText(defaults.getExcludedBlocks())));
        return settings;
    }

    private static String listText(String[] entries) {
        return entries.length == 0 ? "none" : String.join(", ", entries);
    }

    /** Lists the rotations each rotation mode allows (the unrotated state is not listed). */
    private static List<String> rotationSetLines() {
        List<String> lines = new ArrayList<>();
        for (VariantRotation variant : VariantRotation.values()) {
            RotationTuple[] rotations = variant.getRotations();
            boolean yawOnly = true;
            StringBuilder list = new StringBuilder();
            for (RotationTuple tuple : rotations) {
                if (!"None".equals(String.valueOf(tuple.pitch())) || !"None".equals(String.valueOf(tuple.roll()))) {
                    yawOnly = false;
                }
                if (!list.isEmpty()) {
                    list.append(", ");
                }
                list.append(tuple.yaw()).append('/').append(tuple.pitch()).append('/').append(tuple.roll());
            }
            lines.add("  " + variant + ": " + rotations.length + " rotations, yaw only: " + yawOnly);
            lines.add("    (yaw/pitch/roll) " + list);
        }
        return lines;
    }

    private List<DebugTemplate.Entry> describe(List<String> ids, Map<String, String> packOf,
                                               Map<String, String> noteOf, boolean onlyApplied) {
        List<DebugTemplate.Entry> entries = new ArrayList<>();
        for (String id : ids) {
            if (onlyApplied && !originals.containsKey(id)) {
                continue;
            }
            entries.add(new DebugTemplate.Entry(id, packOf.getOrDefault(id, "unknown pack"), noteOf.get(id)));
        }
        entries.sort(java.util.Comparator.comparing(DebugTemplate.Entry::id));
        return entries;
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