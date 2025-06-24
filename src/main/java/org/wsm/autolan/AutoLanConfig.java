package org.wsm.autolan;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry; // Добавлено

@Config(name = AutoLan.MODID)
public class AutoLanConfig implements ConfigData {

    // Поле для ngrok API ключа. Если null или пустое, ключ не задан.
    // Ранее могло называться ngrokAuthtoken.
    public String ngrokKey = null;

    // Уникальный идентификатор клиента. Генерируется автоматически, если отсутствует.
    // Обычно не требует ручного редактирования, поэтому скрыт из GUI.
    @ConfigEntry.Gui.Excluded
    public String clientId = "";
}
