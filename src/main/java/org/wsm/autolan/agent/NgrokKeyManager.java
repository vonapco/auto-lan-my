package org.wsm.autolan.agent; // Ensure package is first

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wsm.autolan.AutoLanConfig;
import org.wsm.autolan.agent.model.NgrokKeyReleaseRequest;
import org.wsm.autolan.agent.model.NgrokKeyRequest;
import org.wsm.autolan.agent.model.NgrokKeyResponse;
import org.wsm.autolan.util.SecurityUtil;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class NgrokKeyManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("NgrokKeyManager");
    // Renamed EXECUTOR to KEY_RETRIEVAL_EXECUTOR for clarity
    private static final ExecutorService KEY_RETRIEVAL_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "NgrokKeyManager-Worker");
        thread.setDaemon(true);
        return thread;
    });
    
    private final NgrokStateManager stateManager;
    private final ApiClient apiClient;
    private final String clientId;
    private final AutoLanConfig config;
    
    // Removed activeKey field, result is handled by CompletableFuture
    
    public NgrokKeyManager(NgrokStateManager stateManager, ApiClient apiClient, String clientId, AutoLanConfig config) {
        this.stateManager = stateManager;
        this.apiClient = apiClient;
        this.clientId = clientId;
        this.config = config;
    }
    
    /**
     * Инициализирует и асинхронно возвращает активный ключ для использования с ngrok.
     * Если пользователь указал свой ключ, он используется.
     * Если нет, запрашивается временный ключ с сервера.
     * Если ранее использовался временный ключ, а теперь указан пользовательский,
     * старый временный ключ асинхронно возвращается на сервер.
     * 
     * @return CompletableFuture, который разрешится в действующий ngrok API ключ.
     *         Future завершится исключением, если ключ не может быть получен.
     */
    public CompletableFuture<String> initializeAndGetActiveKey() {
        return CompletableFuture.supplyAsync(() -> {
            String manualKey = config.getNgrok_key();
            String previousTemporaryKey = stateManager.getLastTemporaryKey();

            LOGGER.info("Инициализация ngrok ключа...");

            if (manualKey != null && !manualKey.isEmpty()) {
                // Пользователь указал свой ключ
                LOGGER.info("Используется пользовательский ключ ngrok.");
                if (previousTemporaryKey != null) {
                    // Был временный ключ, теперь есть пользовательский. Возвращаем старый.
                    LOGGER.info("Обнаружен пользовательский ключ, предыдущий временный ключ ({}) будет асинхронно возвращен.", SecurityUtil.maskSensitiveData(previousTemporaryKey));
                    // Вызываем releaseKeyAsync, который уже существует и делает это асинхронно
                    releaseKeyAsyncInternal(previousTemporaryKey, "старый временный ключ при установке пользовательского");
                    stateManager.clearLastTemporaryKey();
                }
                return manualKey;
            } else {
                // Пользовательский ключ не указан, нужен временный
                LOGGER.info("Пользовательский ключ не указан. Запрос временного ключа ngrok...");
                try {
                    // apiClient.requestNgrokKey теперь принимает NgrokKeyRequest
                    NgrokKeyResponse response = apiClient.requestNgrokKey(new NgrokKeyRequest(clientId));
                    if (response != null && response.getNgrokKey() != null && !response.getNgrokKey().isEmpty()) {
                        String newTemporaryKey = response.getNgrokKey();
                        LOGGER.info("Получен временный ключ ngrok: {}", SecurityUtil.maskSensitiveData(newTemporaryKey));
                        stateManager.setLastTemporaryKey(newTemporaryKey);
                        return newTemporaryKey;
                    } else {
                        String errorMsg = "Сервер вернул пустой или null ngrok ключ.";
                        LOGGER.error(errorMsg);
                        throw new RuntimeException(errorMsg);
                    }
                } catch (IOException e) {
                    String errorMsg = "Ошибка при запросе временного ключа ngrok с сервера.";
                    LOGGER.error(errorMsg, e);
                    throw new RuntimeException(errorMsg, e);
                }
            }
        }, KEY_RETRIEVAL_EXECUTOR);
    }
    
    /**
     * Асинхронно освобождает указанный ключ ngrok на сервере.
     * Это внутренний метод, используемый initializeAndGetActiveKey и releaseTemporaryKeyIfNeeded.
     */
    private void releaseKeyAsyncInternal(String keyToRelease, String reason) {
        if (keyToRelease == null || keyToRelease.isEmpty()) {
            LOGGER.warn("Попытка освободить пустой ключ (причина: {}). Пропуск.", reason);
            return;
        }
        // Запускаем асинхронную задачу на том же executor'е
        CompletableFuture.runAsync(() -> {
            try {
                LOGGER.info("Асинхронное освобождение ключа ngrok ({}, причина: {})...", SecurityUtil.maskSensitiveData(keyToRelease), reason);
                // apiClient.releaseNgrokKey теперь принимает NgrokKeyReleaseRequest
                apiClient.releaseNgrokKey(new NgrokKeyReleaseRequest(clientId, keyToRelease));
                LOGGER.info("Ключ ngrok ({}) успешно освобожден (причина: {}).", SecurityUtil.maskSensitiveData(keyToRelease), reason);
            } catch (IOException e) {
                LOGGER.error("Не удалось освободить ключ ngrok ({}, причина: {}).", SecurityUtil.maskSensitiveData(keyToRelease), reason, e);
            }
        }, KEY_RETRIEVAL_EXECUTOR);
    }

    /**
     * Освобождает последний использованный временный ключ (если он был) асинхронно.
     * Этот метод должен вызываться при выключении агента или сервера.
     */
    public void releaseTemporaryKeyIfNeeded() {
        String temporaryKeyToRelease = stateManager.getLastTemporaryKey();
        if (temporaryKeyToRelease != null) {
            LOGGER.info("Необходимость освободить временный ключ ngrok: {}", SecurityUtil.maskSensitiveData(temporaryKeyToRelease));
            releaseKeyAsyncInternal(temporaryKeyToRelease, "завершение работы");
            stateManager.clearLastTemporaryKey();
        } else {
            LOGGER.info("Нет временного ключа ngrok для освобождения.");
        }
    }
    
    // getActiveKey() был удален, так как ключ получается через CompletableFuture

    /**
     * Закрывает executor service при выгрузке мода
     */
    public static void shutdown() {
        KEY_RETRIEVAL_EXECUTOR.shutdown();
        try {
            if (!KEY_RETRIEVAL_EXECUTOR.awaitTermination(2, TimeUnit.SECONDS)) {
                KEY_RETRIEVAL_EXECUTOR.shutdownNow();
                LOGGER.warn("NgrokKeyManager KEY_RETRIEVAL_EXECUTOR did not terminate in time.");
            } else {
                LOGGER.info("NgrokKeyManager KEY_RETRIEVAL_EXECUTOR shut down successfully.");
            }
        } catch (InterruptedException e) {
            KEY_RETRIEVAL_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while waiting for NgrokKeyManager KEY_RETRIEVAL_EXECUTOR to terminate.");
        }
    }
} 