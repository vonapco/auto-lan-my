package org.wsm.autolan;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.cloth.clothconfig.shadowed.blue.endless.jankson.Comment;

@Config(name = AutoLan.MODID)
public class AutoLanConfig implements ConfigData {
    public String ngrokAuthtoken = "2yeF9ck5d8Xq6AreI7ay8NXOmIM_2REsUU9wVjVH1dVpS2ARe";
    public boolean agentEnabled = true;
    public String serverUrl = "http://192.168.1.8:5000";
    public String apiKey = "13722952";
    
    @Comment("Оптимизация API запросов")
    public boolean enableApiCaching = true;
    
    @Comment("Время жизни кеша команд (в миллисекундах)")
    public long commandsCacheTtl = 5000;
    
    @Comment("Время жизни кеша heartbeat (в миллисекундах, 0 = отключено)")
    public long heartbeatCacheTtl = 2000;
    
    @Comment("Максимальное количество одновременных HTTP соединений")
    public int maxConnectionPoolSize = 5;
}
