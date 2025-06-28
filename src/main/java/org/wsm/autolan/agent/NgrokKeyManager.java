package org.wsm.autolan.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wsm.autolan.AutoLanConfig;
import org.wsm.autolan.agent.model.NgrokKeyResponse;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class NgrokKeyManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("NgrokKeyManager");
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "NgrokKeyManager-Worker");
        thread.setDaemon(true);
        return thread;
    });
    
    private final NgrokStateManager stateManager;
    private final ApiClient apiClient;
    private final String clientId;
    private final AutoLanConfig config;
    
    private String activeKey;
    
    public NgrokKeyManager(NgrokStateManager stateManager, ApiClient apiClient, String clientId, AutoLanConfig config) {
        this.stateManager = stateManager;
        this.apiClient = apiClient;
        this.clientId = clientId;
        this.config = config;
        this.activeKey = null;
    }
    
    /**
     * Инициализирует и возвращает активный ключ для использования с ngrok.
     * Сетевые запросы выполняются асинхронно.
     * 
     * ВАЖНО: При обнаружении пользовательского ключа и наличии предыдущего временного ключа,
     * временный ключ будет автоматически возвращен на сервер.
     * 
     * @return Действующий ngrok API ключ или null, если ключ не может быть получен.
     */
    public String initializeAndGetActiveKey() {
        String manualKey = config.getNgrok_key();
        String previousTempKey = stateManager.getLastTemporaryKey();
        
        LOGGER.info("Инициализация ключа ngrok. Пользовательский ключ: {}, предыдущий временный ключ: {}",
                manualKey != null && !manualKey.isEmpty() ? "указан" : "не указан",
                previousTempKey != null ? "имеется" : "отсутствует");
        
        // 1. Проверяем смену ключа (пользователь добавил свой ключ)
        if (manualKey != null && !manualKey.isEmpty() && previousTempKey != null) {
            // Пользователь указал свой ключ и у нас есть предыдущий временный ключ
            LOGGER.info("Обнаружен пользовательский ключ, возвращаем временный ключ на сервер");
            
            // Асинхронно освобождаем временный ключ
            CompletableFuture.runAsync(() -> {
                try {
                    apiClient.releaseNgrokKey(clientId, previousTempKey);
                    LOGGER.info("Временный ключ успешно возвращен на сервер");
                    // Очистка упоминания о ключе в локальном состоянии
                    stateManager.clearLastTemporaryKey();
                } catch (IOException e) {
                    LOGGER.error("Не удалось вернуть временный ключ на сервер", e);
                }
            }, EXECUTOR);
        }
        
        // 2. Определяем, какой ключ будет активным
        if (manualKey != null && !manualKey.isEmpty()) {
            LOGGER.info("Используется пользовательский ключ ngrok");
            this.activeKey = manualKey;
            // Дополнительная проверка на всякий случай, если ключ не был очищен в асинхронном потоке
            stateManager.clearLastTemporaryKey();
        } else {
            LOGGER.info("Запрашиваем временный ключ с сервера");
            
            // Используем пустой ключ пока запрос не выполнится асинхронно
            this.activeKey = "";
            
            // Запрос ключа выполняется асинхронно
            CompletableFuture.runAsync(() -> {
                try {
                    NgrokKeyResponse response = apiClient.requestNgrokKey(clientId);
                    if (response != null && response.getNgrokKey() != null && !response.getNgrokKey().isEmpty()) {
                        this.activeKey = response.getNgrokKey();
                        stateManager.setLastTemporaryKey(this.activeKey);
                        LOGGER.info("Получен временный ключ с сервера");
                    } else {
                        LOGGER.warn("Сервер вернул пустой или null ключ");
                        this.activeKey = "";
                    }
                } catch (IOException e) {
                    LOGGER.error("Не удалось получить временный ключ с сервера", e);
                    this.activeKey = "";
                }
            }, EXECUTOR);
        }
        
        return this.activeKey;
    }
    
    /**
     * Освобождает временный ключ (если он использовался) асинхронно.
     * ПРИМЕЧАНИЕ: Этот метод больше не вызывается автоматически при выходе из игры,
     * а используется только в специальных случаях.
     */
    public void releaseTemporaryKeyIfNeeded() {
        String previousTempKey = stateManager.getLastTemporaryKey();
        if (previousTempKey != null) {
            LOGGER.info("Освобождение временного ключа");
            
            // Выполняем асинхронно
            CompletableFuture.runAsync(() -> {
                try {
                    apiClient.releaseNgrokKey(clientId, previousTempKey);
                    LOGGER.info("Временный ключ успешно возвращен на сервер");
                    // Очистка упоминания о ключе в локальном состоянии
                    stateManager.clearLastTemporaryKey();
                } catch (IOException e) {
                    LOGGER.error("Не удалось вернуть временный ключ на сервер", e);
                }
            }, EXECUTOR);
        }
    }
    
    /**
     * @return Текущий активный ключ ngrok.
     */
    public String getActiveKey() {
        return activeKey;
    }
    
    /**
     * Закрывает executor service при выгрузке мода
     */
    public static void shutdown() {
        EXECUTOR.shutdown();
        try {
            if (!EXECUTOR.awaitTermination(2, TimeUnit.SECONDS)) {
                EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
} 