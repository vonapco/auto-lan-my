package org.wsm.autolan;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;

@Config(name = AutoLan.MODID)
public class AutoLanConfig implements ConfigData {
    public String ngrokAuthtoken = "";
}
