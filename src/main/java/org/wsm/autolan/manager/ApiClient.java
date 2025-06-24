package org.wsm.autolan.manager;

import org.wsm.autolan.AutoLan;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

// Для работы с JSON можно использовать простую библиотеку или формировать JSON вручную для простых случаев.
// В данном случае, JSON простой, поэтому можно обойтись без дополнительных зависимостей на JSON библиотеки,
// если они не добавлены в проект ранее. Если Gson или Jackson уже есть, лучше использовать их.
// Предположим пока ручное формирование/парсинг для минимизации зависимостей.

public class ApiClient {

    // TODO: Заменить на реальный URL сервера
    private static final String BASE_URL = "https://your-main-server.com/api";
    // TODO: Заменить на реальный секретный ключ API
    private static final String X_API_KEY_SECRET = "your-secret-api-key";

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Запрашивает временный ngrok API ключ с сервера.
     * @param clientId Уникальный идентификатор клиента.
     * @return CompletableFuture, содержащий ngrok ключ или исключение при ошибке.
     */
    public static CompletableFuture<String> requestNgrokKey(String clientId) {
        String requestBody = String.format("{\"clientId\": \"%s\", \"hasCustomKey\": false}", clientId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/request_ngrok_key"))
                .header("Content-Type", "application/json")
                .header("X-API-Key", X_API_KEY_SECRET)
                .POST(BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(20)) // Таймаут для всего запроса
                .build();

        return httpClient.sendAsync(request, BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        // Простой парсинг JSON ответа. Для более сложных случаев нужна библиотека.
                        // Пример ответа: { "ngrokKey": "abc123xyz..." }
                        String responseBody = response.body();
                        // Очень упрощенный парсинг, подверженный ошибкам при изменении формата JSON
                        try {
                            // Ищем начало ключа после "ngrokKey": "
                            int keyStart = responseBody.indexOf("\"ngrokKey\": \"") + "\"ngrokKey\": \"".length();
                            // Ищем конец ключа до закрывающей кавычки "
                            int keyEnd = responseBody.indexOf("\"", keyStart);
                            if (keyStart > -1 && keyEnd > -1 && keyEnd > keyStart) {
                                return responseBody.substring(keyStart, keyEnd);
                            } else {
                                AutoLan.LOGGER.error("[AutoLan] ApiClient: Could not parse ngrokKey from response: {}", responseBody);
                                throw new RuntimeException("Failed to parse ngrokKey from server response. Body: " + responseBody);
                            }
                        } catch (Exception e) {
                             AutoLan.LOGGER.error("[AutoLan] ApiClient: Exception while parsing ngrokKey from response: {}", responseBody, e);
                            throw new RuntimeException("Exception while parsing ngrokKey from server response.", e);
                        }
                    } else {
                        AutoLan.LOGGER.error("[AutoLan] ApiClient: Failed to request ngrok key. Status: {}, Body: {}", response.statusCode(), response.body());
                        throw new RuntimeException("Failed to request ngrok key. Status: " + response.statusCode() + ", Body: " + response.body());
                    }
                })
                .exceptionally(ex -> {
                    AutoLan.LOGGER.error("[AutoLan] ApiClient: Exception during requestNgrokKey", ex);
                    throw new RuntimeException("Exception during ngrok key request: " + ex.getMessage(), ex);
                });
    }

    /**
     * Сообщает серверу об освобождении временного ngrok API ключа.
     * @param clientId Уникальный идентификатор клиента.
     * @param ngrokKey Освобождаемый ключ.
     * @return CompletableFuture, завершающийся успешно или с исключением при ошибке.
     */
    public static CompletableFuture<Void> releaseNgrokKey(String clientId, String ngrokKey) {
        String requestBody = String.format("{\"clientId\": \"%s\", \"key\": \"%s\"}", clientId, ngrokKey);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/release_ngrok_key"))
                .header("Content-Type", "application/json")
                // X-API-Key здесь не указан в документации, но возможно, он тоже нужен.
                // Если нужен, его следует добавить: .header("X-API-Key", X_API_KEY_SECRET)
                .POST(BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(20))
                .build();

        return httpClient.sendAsync(request, BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200 && response.statusCode() != 204) { // 204 No Content тоже может быть успехом
                        AutoLan.LOGGER.error("[AutoLan] ApiClient: Failed to release ngrok key. Status: {}, Body: {}", response.statusCode(), response.body());
                        // Не бросаем исключение здесь, чтобы не прерывать цепочку, если освобождение не критично,
                        // но логируем ошибку. Если критично - нужно бросать.
                        // throw new RuntimeException("Failed to release ngrok key. Status: " + response.statusCode() + ", Body: " + response.body());
                    } else {
                        AutoLan.LOGGER.info("[AutoLan] ApiClient: Successfully released ngrok key or server acknowledged. Status: {}", response.statusCode());
                    }
                })
                .exceptionally(ex -> {
                    AutoLan.LOGGER.error("[AutoLan] ApiClient: Exception during releaseNgrokKey", ex);
                    // Аналогично, можно не бросать исключение, если это некритичная операция.
                    // throw new RuntimeException("Exception during ngrok key release: " + ex.getMessage(), ex);
                    return null; // Обязательно вернуть значение для exceptionally, если не перебрасываем
                });
    }
}
