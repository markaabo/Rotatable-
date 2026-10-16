package markaabo.rotatable.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/** The settings stored in mods/Rotatable!/config.json. The text of the file is in templates/ConfigTemplate. */
public class RotatableConfig {

    /** Raise this when the config layout changes in a way old files cannot be read. */
    public static final int CURRENT_VERSION = 1;

    public static final BuilderCodec<RotatableConfig> CODEC = BuilderCodec
            .builder(RotatableConfig.class, RotatableConfig::new)
            .append(new KeyedCodec<Integer>("ConfigVersion", Codec.INTEGER),
                    (config, value) -> config.configVersion = value,
                    config -> config.configVersion)
            .add()
            .append(new KeyedCodec<Boolean>("AutoDetectFullBlocks", Codec.BOOLEAN),
                    (config, value) -> config.autoDetectFullBlocks = value,
                    config -> config.autoDetectFullBlocks)
            .add()
            .append(new KeyedCodec<Boolean>("AutoDetectModdedBlocks", Codec.BOOLEAN),
                    (config, value) -> config.autoDetectModdedBlocks = value,
                    config -> config.autoDetectModdedBlocks)
            .add()
            .append(new KeyedCodec<String>("RotationVariant", Codec.STRING),
                    (config, value) -> config.rotationVariant = value,
                    config -> config.rotationVariant)
            .add()
            .append(new KeyedCodec<Boolean>("CardinalPlacement", Codec.BOOLEAN),
                    (config, value) -> config.cardinalPlacement = value,
                    config -> config.cardinalPlacement)
            .add()
            .append(new KeyedCodec<String[]>("IncludedBlocks", Codec.STRING_ARRAY),
                    (config, value) -> config.includedBlocks = value,
                    config -> config.includedBlocks)
            .add()
            .append(new KeyedCodec<String[]>("ExcludedBlocks", Codec.STRING_ARRAY),
                    (config, value) -> config.excludedBlocks = value,
                    config -> config.excludedBlocks)
            .add()
            .build();

    // A file without ConfigVersion counts as version 0 (an old file).
    private int configVersion = 0;
    private boolean autoDetectFullBlocks = true;
    private boolean autoDetectModdedBlocks = false;
    private String rotationVariant = "NESW";
    private boolean cardinalPlacement = false;
    private String[] includedBlocks = new String[0];
    private String[] excludedBlocks = new String[0];

    public int getConfigVersion() {
        return configVersion;
    }

    public boolean isAutoDetectFullBlocks() {
        return autoDetectFullBlocks;
    }

    public boolean isAutoDetectModdedBlocks() {
        return autoDetectModdedBlocks;
    }

    public String getRotationVariant() {
        return rotationVariant == null ? "NESW" : rotationVariant;
    }

    public boolean isCardinalPlacement() {
        return cardinalPlacement;
    }

    public String[] getIncludedBlocks() {
        return includedBlocks == null ? new String[0] : includedBlocks;
    }

    public String[] getExcludedBlocks() {
        return excludedBlocks == null ? new String[0] : excludedBlocks;
    }
}