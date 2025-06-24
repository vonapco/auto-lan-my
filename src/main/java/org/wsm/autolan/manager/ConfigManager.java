package org.wsm.autolan.manager;

import org.wsm.autolan.AutoLan;
import org.wsm.autolan.AutoLanConfig;
// import me.shedaniel.autoconfig.AutoConfig; // Не используется напрямую для сохранения здесь, но может понадобиться

import java.util.UUID;

public class ConfigManager {

    private static AutoLanConfig getConfig() {
        // Убедимся, что конфиг загружен
        if (AutoLan.CONFIG == null) {
            // Это аварийный случай, конфиг должен быть инициализирован в AutoLan.onInitialize()
            AutoLan.LOGGER.error("[AutoLan] ConfigManager: AutoLan.CONFIG is null! This should not happen.");
            // Возвращаем временный объект, чтобы избежать NPE, но это проблема, которую нужно решить.
            // В идеале, инициализация мода должна гарантировать, что CONFIG не null к моменту вызова.
            return new AutoLanConfig();
        }
        return AutoLan.CONFIG.getConfig();
    }

    /**
     * Получает ngrok API ключ из конфигурации.
     * @return ngrok ключ или null, если он не задан.
     */
    public static String getNgrokKey() {
        String key = getConfig().ngrokKey;
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        return key;
    }

    /**
     * Получает clientId из конфигурации.
     * Если clientId отсутствует, генерирует новый, сохраняет его в конфигурацию
     * и возвращает.
     * @return clientId.
     */
    public static String getClientId() {
        AutoLanConfig currentConfig = getConfig();
        String clientId = currentConfig.clientId;

        if (clientId == null || clientId.trim().isEmpty()) {
            clientId = UUID.randomUUID().toString();
            currentConfig.clientId = clientId;
            AutoLan.LOGGER.info("[AutoLan] ConfigManager: Generated new clientId: {}", clientId);

            // Сохранение конфигурации через AutoConfig
            if (AutoLan.CONFIG != null) {
                try {
                    // AutoConfig сам управляет сохранением.
                    // Обычно save() вызывается не здесь, а глобально, или AutoConfig делает это автоматически.
                    // Но для немедленного сохранения clientId это может быть необходимо.
                    // Если есть проблемы с конкурентным доступом или моментом сохранения,
                    // это место может потребовать пересмотра.
                    AutoLan.CONFIG.save();
                    AutoLan.LOGGER.info("[AutoLan] ConfigManager: Successfully saved new clientId to config using AutoLan.CONFIG.save().");
                } catch (Exception e) {
                    AutoLan.LOGGER.error("[AutoLan] ConfigManager: Failed to save new clientId to config using AutoLan.CONFIG.save().", e);
                }
            } else {
                 AutoLan.LOGGER.warn("[AutoLan] ConfigManager: AutoLan.CONFIG was null, could not save new clientId immediately.");
            }
        }
        return clientId;
    }
}
