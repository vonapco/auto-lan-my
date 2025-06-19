package org.wsm.autolan;

import org.jetbrains.annotations.Nullable;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

import net.minecraft.world.GameMode;
import org.wsm.autolan.TunnelType;

@Config(name = AutoLan.MODID)
public class AutoLanConfig implements ConfigData {
    public String ngrokAuthtoken = "";
}
