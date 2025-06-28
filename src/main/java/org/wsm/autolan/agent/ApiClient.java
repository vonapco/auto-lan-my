package org.wsm.autolan.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wsm.autolan.agent.model.*;
import org.wsm.autolan.util.SecurityUtil;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Objects;

public class ApiClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("ApiClient");
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String baseUrl;
    private final String apiKey;
    private final OkHttpClient client;
    private final Gson gson;

    /**
     * Создает новый клиент API для взаимодействия с сервером AutoLan.
     * 
     * @param baseUrl базовый URL-адрес сервера
     * @param apiKey ключ API для аутентификации
     */
    public ApiClient(String baseUrl, String apiKey) {
        // Проверяем и нормализуем URL
        this.baseUrl = normalizeUrl(baseUrl);
        this.apiKey = apiKey;
        this.client = new OkHttpClient();
        this.gson = new GsonBuilder().create();
        
        LOGGER.debug("ApiClient инициализирован с URL: {}", SecurityUtil.maskSensitiveData(this.baseUrl));
    }
    
    /**
     * Нормализует URL-адрес, проверяя, что он имеет правильный формат и содержит схему http/https.
     * 
     * @param url исходный URL
     * @return нормализованный URL
     */
    private String normalizeUrl(String url) {
        if (url == null || url.isEmpty()) {
            LOGGER.error("Передан пустой URL");
            return "http://localhost:5000"; // Запасной вариант
        }
        
        // Проверка наличия схемы
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            LOGGER.warn("URL не содержит схему http/https, добавляем http://");
            url = "http://" + url;
        }
        
        // Дополнительная проверка на валидность URL
        try {
            new URL(url);
            return url;
        } catch (MalformedURLException e) {
            LOGGER.error("Некорректный URL: {}", url, e);
            return "http://localhost:5000"; // Запасной вариант
        }
    }
    
    /**
     * Формирует полный URL для endpoint API
     */
    private String getEndpointUrl(String endpoint) {
        return baseUrl + (endpoint.startsWith("/") ? endpoint : "/" + endpoint);
    }

    @Nullable
    public RegistrationResponse register(RegistrationRequest registrationRequest) throws IOException {
        String json = gson.toJson(registrationRequest);
        RequestBody body = RequestBody.create(json, JSON);
        Request request = new Request.Builder()
                .url(getEndpointUrl("register"))
                .header("X-API-Key", apiKey)
                .post(body)
                .build();

        LOGGER.debug("Выполняется запрос регистрации к {}", SecurityUtil.maskSensitiveData(getEndpointUrl("register")));
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                LOGGER.warn("Неудачный запрос регистрации: код {}", response.code());
                throw new IOException("Unexpected code " + response);
            }
            return gson.fromJson(Objects.requireNonNull(response.body()).charStream(), RegistrationResponse.class);
        }
    }

    @Nullable
    public HeartbeatResponse sendHeartbeat(HeartbeatPayload payload) throws IOException {
        String json = gson.toJson(payload);
        RequestBody body = RequestBody.create(json, JSON);
        Request request = new Request.Builder()
                .url(getEndpointUrl("heartbeat"))
                .header("X-API-Key", apiKey)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            return gson.fromJson(Objects.requireNonNull(response.body()).charStream(), HeartbeatResponse.class);
        } catch (Exception e) {
            LOGGER.error("Ошибка при отправке heartbeat: {}", e.getMessage());
            return null;
        }
    }

    @Nullable
    public CommandsResponse getCommands(@NotNull String clientId) throws IOException {
        try {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(getEndpointUrl("commands")))
                    .newBuilder()
                    .addQueryParameter("client_id", clientId)
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .header("X-API-Key", apiKey)
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("Unexpected code " + response);
                }
                return gson.fromJson(Objects.requireNonNull(response.body()).charStream(), CommandsResponse.class);
            }
        } catch (Exception e) {
            LOGGER.error("Ошибка при получении команд: {}", e.getMessage());
            return null;
        }
    }
    
    @Nullable
    public NgrokKeyResponse requestNgrokKey(@NotNull String clientId) throws IOException {
        try {
            NgrokKeyRequest request = new NgrokKeyRequest(clientId);
            String json = gson.toJson(request);
            RequestBody body = RequestBody.create(json, JSON);
            
            Request httpRequest = new Request.Builder()
                    .url(getEndpointUrl("request_ngrok_key"))
                    .header("X-API-Key", apiKey)
                    .post(body)
                    .build();

            LOGGER.debug("Запрос временного ключа ngrok для client_id: {}", SecurityUtil.maskSensitiveData(clientId));
            try (Response response = client.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    LOGGER.warn("Неудачный запрос ключа ngrok: код {}", response.code());
                    throw new IOException("Unexpected code " + response);
                }
                
                NgrokKeyResponse keyResponse = gson.fromJson(Objects.requireNonNull(response.body()).charStream(), NgrokKeyResponse.class);
                if (keyResponse != null && keyResponse.getNgrokKey() != null) {
                    LOGGER.debug("Получен временный ключ ngrok: {}", SecurityUtil.maskSensitiveData(keyResponse.getNgrokKey()));
                }
                return keyResponse;
            }
        } catch (Exception e) {
            LOGGER.error("Ошибка при запросе ключа ngrok: {}", e.getMessage());
            return null;
        }
    }
    
    public void releaseNgrokKey(@NotNull String clientId, @NotNull String key) throws IOException {
        try {
            NgrokKeyReleaseRequest request = new NgrokKeyReleaseRequest(clientId, key);
            String json = gson.toJson(request);
            RequestBody body = RequestBody.create(json, JSON);
            
            Request httpRequest = new Request.Builder()
                    .url(getEndpointUrl("release_ngrok_key"))
                    .header("X-API-Key", apiKey)
                    .post(body)
                    .build();

            LOGGER.debug("Освобождение ключа ngrok: {}", SecurityUtil.maskSensitiveData(key));
            try (Response response = client.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    LOGGER.warn("Неудачная попытка освобождения ключа: код {}", response.code());
                    throw new IOException("Unexpected code " + response);
                }
                LOGGER.debug("Ключ ngrok успешно освобожден");
                // Ответ нам не важен, главное что запрос выполнился успешно
            }
        } catch (Exception e) {
            LOGGER.error("Ошибка при освобождении ключа ngrok: {}", e.getMessage());
        }
    }
} 