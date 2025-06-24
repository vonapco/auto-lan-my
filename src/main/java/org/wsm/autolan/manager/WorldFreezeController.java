package org.wsm.autolan.manager;

import org.wsm.autolan.AutoLan;

public class WorldFreezeController {

    // Состояние заморозки мира, чтобы избежать лишних вызовов freeze/unfreeze
    private static boolean isFrozen = false;

    public static void freeze() {
        if (isFrozen) {
            AutoLan.LOGGER.info("[AutoLan] WorldFreezeController: World is already frozen. Skipping freeze command.");
            return;
        }
        // Здесь должна быть реальная логика заморозки загрузки мира Minecraft.
        // Например, остановка тиков сервера или блокировка определенных событий.
        // Пока что просто логируем.
        AutoLan.LOGGER.info("[AutoLan] WorldFreezeController: Freezing world loading...");
        isFrozen = true;
        // TODO: Реализовать фактическую заморозку мира
    }

    public static void unfreeze() {
        if (!isFrozen) {
            AutoLan.LOGGER.info("[AutoLan] WorldFreezeController: World is not frozen. Skipping unfreeze command.");
            return;
        }
        // Здесь должна быть реальная логика возобновления загрузки мира Minecraft.
        AutoLan.LOGGER.info("[AutoLan] WorldFreezeController: Unfreezing world loading...");
        isFrozen = false;
        // TODO: Реализовать фактическое возобновление мира
    }

    public static boolean isFrozen() {
        return isFrozen;
    }
}
