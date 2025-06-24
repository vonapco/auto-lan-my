package org.wsm.autolan.manager;

import org.wsm.autolan.AutoLan;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public class NgrokKeyManager {

    private static String currentNgrokKey = null;
    private static boolean isFromServer = false;
    private static String lastProvidedKeyToNgrok = null; // Для отслеживания ключа, который был передан в NgrokBootstrapper

    /**
     * Определяет и возвращает ключ ngrok для использования.
     * Если пользовательский ключ не найден, запрашивает временный ключ с сервера,
     * при этом мир может быть "заморожен".
     * Этот метод является блокирующим до тех пор, пока ключ не будет получен или не произойдет ошибка.
     * @param clientId Уникальный идентификатор клиента.
     * @return Ngrok ключ или null, если ключ не удалось получить.
     */
    public static synchronized String resolveNgrokKey(String clientId) {
        AutoLan.LOGGER.info("[AutoLan] NgrokKeyManager: Resolving ngrok key for clientId: {}", clientId);
        String userKey = ConfigManager.getNgrokKey();

        if (userKey != null) {
            AutoLan.LOGGER.info("[AutoLan] NgrokKeyManager: Custom ngrok key found in config.");
            // Если ранее использовался ключ с сервера, и новый ключ отличается от старого серверного,
            // или если просто был серверный ключ, а теперь пользовательский.
            if (isFromServer && currentNgrokKey != null && !currentNgrokKey.equals(userKey)) {
                AutoLan.LOGGER.info("[AutoLan] NgrokKeyManager: Releasing previously used server key as custom key is now provided.");
                releaseKeyIfNeeded(clientId, currentNgrokKey); // Передаем старый ключ для освобождения
            }
            currentNgrokKey = userKey;
            isFromServer = false;
            lastProvidedKeyToNgrok = currentNgrokKey;
            return currentNgrokKey;
        } else {
            AutoLan.LOGGER.info("[AutoLan] NgrokKeyManager: No custom ngrok key found. Requesting temporary key from server.");
            // Если до этого был какой-то ключ (даже пользовательский, который потом удалили),
            // и он был от сервера, его стоит освободить перед запросом нового.
            // Однако, если currentNgrokKey уже null, а isFromServer true, это странная ситуация.
            // Безопаснее всего освобождать только если isFromServer true и currentNgrokKey не null.
            if (isFromServer && currentNgrokKey != null) {
                 AutoLan.LOGGER.info("[AutoLan] NgrokKeyManager: Releasing previously used server key before requesting a new one.");
                 releaseKeyIfNeeded(clientId, currentNgrokKey); // Освобождаем старый серверный ключ
                 currentNgrokKey = null; // Сбрасываем, так как он освобожден
                 isFromServer = false;
            }

            WorldFreezeController.freeze();
            try {
                CompletableFuture<String> futureKey = ApiClient.requestNgrokKey(clientId);
                // Блокируемся и ждем результат. Устанавливаем таймаут, чтобы не зависнуть навсегда.
                // Таймаут должен быть достаточно большим, чтобы сервер успел ответить.
                currentNgrokKey = futureKey.orTimeout(30, TimeUnit.SECONDS).join();
                isFromServer = true;
                lastProvidedKeyToNgrok = currentNgrokKey;
                AutoLan.LOGGER.info("[AutoLan] NgrokKeyManager: Successfully received temporary ngrok key from server.");
                return currentNgrokKey;
            } catch (CompletionException ce) {
                // CompletionException оборачивает оригинальное исключение
                Throwable cause = ce.getCause();
                AutoLan.LOGGER.error("[AutoLan] NgrokKeyManager: Failed to get temporary ngrok key from server (CompletionException). Cause: {}", cause != null ? cause.getMessage() : "Unknown", cause);
                // Дополнительно логируем само 'ce', если cause == null
                if (cause == null) AutoLan.LOGGER.error("[AutoLan] NgrokKeyManager: CompletionException details", ce);

            } catch (Exception e) {
                AutoLan.LOGGER.error("[AutoLan] NgrokKeyManager: Failed to get temporary ngrok key from server (Exception).", e);
            } finally {
                WorldFreezeController.unfreeze();
            }
            // Если ключ не получен, currentNgrokKey останется null или будет содержать старое значение (если не был сброшен)
            // В случае ошибки, возвращаем null, чтобы показать, что ключ не доступен.
            currentNgrokKey = null;
            isFromServer = false;
            lastProvidedKeyToNgrok = null;
            return null;
        }
    }

    /**
     * Вызывается, когда NgrokBootstrapper не смог запуститься с предоставленным ключом.
     * Если ключ был от сервера, он освобождается, и делается попытка получить новый.
     * @param clientId Уникальный идентификатор клиента.
     * @param failedKey Ключ, с которым не удалось запустить ngrok.
     * @return Новый ключ ngrok, если удалось его получить, иначе null.
     */
    public static synchronized String handleNgrokStartupFailure(String clientId, String failedKey) {
        AutoLan.LOGGER.warn("[AutoLan] NgrokKeyManager: Ngrok startup failed with key: {}", failedKey);
        // Проверяем, действительно ли это тот ключ, который мы выдали и он был с сервера
        if (isFromServer && failedKey != null && failedKey.equals(lastProvidedKeyToNgrok)) {
            AutoLan.LOGGER.info("[AutoLan] NgrokKeyManager: Releasing failed server key and attempting to get a new one.");
            releaseKeyIfNeeded(clientId, failedKey); // Освобождаем нерабочий ключ

            // Сбрасываем текущий ключ, так как он невалиден
            currentNgrokKey = null;
            isFromServer = false;
            lastProvidedKeyToNgrok = null;

            // Пытаемся получить новый ключ
            // Эта логика дублирует часть resolveNgrokKey, но без проверки пользовательского ключа,
            // так как мы уже знаем, что пользовательского ключа нет (иначе isFromServer было бы false).
            WorldFreezeController.freeze();
            try {
                CompletableFuture<String> futureKey = ApiClient.requestNgrokKey(clientId);
                currentNgrokKey = futureKey.orTimeout(30, TimeUnit.SECONDS).join();
                isFromServer = true;
                lastProvidedKeyToNgrok = currentNgrokKey;
                AutoLan.LOGGER.info("[AutoLan] NgrokKeyManager: Successfully received a new temporary ngrok key after failure.");
                return currentNgrokKey;
            } catch (Exception e) {
                AutoLan.LOGGER.error("[AutoLan] NgrokKeyManager: Failed to get a new temporary ngrok key after failure.", e);
            } finally {
                WorldFreezeController.unfreeze();
            }
            // Если не удалось получить новый ключ
            currentNgrokKey = null;
            isFromServer = false;
            lastProvidedKeyToNgrok = null;
            return null;
        } else if (!isFromServer && failedKey != null && failedKey.equals(lastProvidedKeyToNgrok)) {
            // Пользовательский ключ не сработал. Сообщаем об этом.
            // Ничего не освобождаем, так как это ключ пользователя.
            AutoLan.LOGGER.error("[AutoLan] NgrokKeyManager: Custom ngrok key failed to start ngrok. Key: {}", failedKey);
            // Можно добавить сообщение для пользователя здесь
            currentNgrokKey = null; // Считаем, что текущий ключ невалиден
            lastProvidedKeyToNgrok = null;
            return null; // Возвращаем null, т.к. пользовательский ключ не сработал
        } else {
            AutoLan.LOGGER.warn("[AutoLan] NgrokKeyManager: Ngrok startup failure for a key that was not managed or not from server. Key: {}. isFromServer: {}, lastProvidedKey: {}", failedKey, isFromServer, lastProvidedKeyToNgrok);
            return null; // Не обрабатываем, если ключ не тот, что мы ожидали
        }
    }

    /**
     * Освобождает текущий ngrok ключ, если он был получен с сервера.
     * Этот метод следует вызывать при выключении мода или когда ключ больше не нужен.
     * @param clientId Уникальный идентификатор клиента.
     * @param keyToRelease Конкретный ключ, который нужно освободить.
     */
    public static synchronized void releaseKeyIfNeeded(String clientId, String keyToRelease) {
        if (isFromServer && keyToRelease != null && keyToRelease.equals(currentNgrokKey)) {
            AutoLan.LOGGER.info("[AutoLan] NgrokKeyManager: Releasing server-provided ngrok key: {}", keyToRelease);
            ApiClient.releaseNgrokKey(clientId, keyToRelease)
                .thenRun(() -> AutoLan.LOGGER.info("[AutoLan] NgrokKeyManager: Successfully signalled release of key {}", keyToRelease))
                .exceptionally(ex -> {
                    AutoLan.LOGGER.error("[AutoLan] NgrokKeyManager: Failed to signal release of key {}", keyToRelease, ex);
                    return null;
                });
            currentNgrokKey = null;
            isFromServer = false;
            // lastProvidedKeyToNgrok не сбрасываем здесь, т.к. он отражает последний ключ, отданный бутстрапперу
        } else {
            AutoLan.LOGGER.info("[AutoLan] NgrokKeyManager: No server-provided key to release or key mismatch. Current key: {}, Provided key: {}, isFromServer: {}", currentNgrokKey, keyToRelease, isFromServer);
        }
    }

    /**
     * Специальный метод для вызова при завершении работы или выходе из мира.
     * Гарантированно освобождает серверный ключ, если он используется.
     * @param clientId Client ID.
     */
    public static synchronized void shutdownCleanup(String clientId) {
        AutoLan.LOGGER.info("[AutoLan] NgrokKeyManager: Performing shutdown cleanup for clientId: {}", clientId);
        if (isFromServer && currentNgrokKey != null) {
            AutoLan.LOGGER.info("[AutoLan] NgrokKeyManager: Releasing server-provided ngrok key during shutdown: {}", currentNgrokKey);
            // Используем копию ключа, так как releaseKeyIfNeeded может его изменить
            String keyToReleaseCopy = currentNgrokKey;
            releaseKeyIfNeeded(clientId, keyToReleaseCopy);
        } else {
             AutoLan.LOGGER.info("[AutoLan] NgrokKeyManager: No server-provided key to release during shutdown.");
        }
        // Сбрасываем состояние на всякий случай
        currentNgrokKey = null;
        isFromServer = false;
        lastProvidedKeyToNgrok = null;
    }


    // Метод для проверки, был ли ключ получен с сервера.
    // Может быть полезен для NgrokBootstrapper, чтобы знать, нужно ли повторять попытку через NgrokKeyManager.
    public static boolean isCurrentKeyFromServer() {
        return isFromServer;
    }

    // Метод для получения текущего активного ключа (если он есть).
    // Полезно, если другой части системы нужно знать текущий ключ без его изменения.
    public static String getCurrentNgrokKey() {
        return currentNgrokKey;
    }
}
