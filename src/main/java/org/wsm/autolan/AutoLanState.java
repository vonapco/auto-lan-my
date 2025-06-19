package org.wsm.autolan;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

public class AutoLanState extends PersistentState {
    private static final String WHITELIST_ENABLED_KEY = "whitelistEnabled";

    public static final String CUSTOM_LAN_KEY = AutoLan.MODID;
    public static final Codec<AutoLanState> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(Codec.BOOL.optionalFieldOf(WHITELIST_ENABLED_KEY, false)
                            .forGetter(state -> state.whitelistEnabled))
            .apply(instance, AutoLanState::new));
    public static final PersistentStateType<AutoLanState> STATE_TYPE = new PersistentStateType<>(CUSTOM_LAN_KEY,
            AutoLanState::new, CODEC, null);

    private boolean whitelistEnabled;

    private AutoLanState(boolean whitelistEnabled) {
        this.whitelistEnabled = whitelistEnabled;
    }

    public AutoLanState() {
        this.whitelistEnabled = false;
    }

    public boolean getWhitelistEnabled() {
        return this.whitelistEnabled;
    }

    public void setWhitelistEnabled(boolean whitelistEnabled) {
        this.whitelistEnabled = whitelistEnabled;
        this.markDirty();
    }
}
