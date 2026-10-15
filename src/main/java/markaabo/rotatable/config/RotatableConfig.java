package markaabo.rotatable.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/** The settings stored in mods/rotatable/config.json. */
public class RotatableConfig {

    public static final BuilderCodec<RotatableConfig> CODEC = BuilderCodec
            .builder(RotatableConfig.class, RotatableConfig::new)
            .append(new KeyedCodec<Boolean>("AutoDetectFullBlocks", Codec.BOOLEAN),
                    (config, value) -> config.autoDetectFullBlocks = value,
                    config -> config.autoDetectFullBlocks)
            .add()
            .append(new KeyedCodec<String[]>("IncludedBlocks", Codec.STRING_ARRAY),
                    (config, value) -> config.includedBlocks = value,
                    config -> config.includedBlocks)
            .add()
            .append(new KeyedCodec<String[]>("BlacklistedBlocks", Codec.STRING_ARRAY),
                    (config, value) -> config.blacklistedBlocks = value,
                    config -> config.blacklistedBlocks)
            .add()
            .build();

    private boolean autoDetectFullBlocks = true;
    private String[] includedBlocks = new String[0];
    private String[] blacklistedBlocks = new String[0];

    public boolean isAutoDetectFullBlocks() {
        return autoDetectFullBlocks;
    }

    public String[] getIncludedBlocks() {
        return includedBlocks == null ? new String[0] : includedBlocks;
    }

    public String[] getBlacklistedBlocks() {
        return blacklistedBlocks == null ? new String[0] : blacklistedBlocks;
    }
}