package org.wsm.autolan.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wsm.autolan.agent.model.*;

import java.io.IOException;
import java.util.Objects;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ApiClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoLanApiClient");
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String baseUrl;
    private final String apiKey;
    private final OkHttpClient client;
    private final Gson gson;
    
    // Структура для хранения кешированных запросов
    private static class CacheEntry<T> {
        final T data;
        final long expirationTime;
        final String etag;
        
        CacheEntry(T data, long expirationTime, String etag) {
            this.data = data;
            this.expirationTime = expirationTime;
            this.etag = etag;
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }
    }
    
    // Кеш запросов к API
    private final Map<String, CacheEntry<?>> requestCache = new ConcurrentHashMap<>();
    
    // Настройки кеширования (в миллисекундах)
    private long commandsCacheTtl = 5000;   // 5 секунд по умолчанию для команд
    private long heartbeatCacheTtl = 0;     // 0 = без кеширования для heartbeat
    private boolean cachingEnabled = true;  // Включено ли кеширование

    public ApiClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        
        // Используем стандартные значения
        int maxConnections = 5;
        
        // Конфигурируем OkHttp клиент с оптимизированными настройками
        this.client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(maxConnections, 5, TimeUnit.MINUTES)) // Сохраняем соединения до 5 минут
            .retryOnConnectionFailure(true)
            .build();
            
        this.gson = new GsonBuilder().create();
        
        LOGGER.info("ApiClient initialized with caching support.");
    }
    
    /**
     * Создает клиент API с возможностью настройки пула соединений
     * 
     * @param baseUrl URL сервера API
     * @param apiKey Ключ API
     * @param maxConnectionPoolSize Максимальное количество одновременных соединений
     */
    public ApiClient(String baseUrl, String apiKey, int maxConnectionPoolSize) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        
        // Конфигурируем OkHttp клиент с оптимизированными настройками
        this.client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(maxConnectionPoolSize, 5, TimeUnit.MINUTES)) 
            .retryOnConnectionFailure(true)
            .build();
            
        this.gson = new GsonBuilder().create();
        
        LOGGER.info("ApiClient initialized with caching support and connection pool size: {}", maxConnectionPoolSize);
    }
    
    /**
     * Устанавливает, включено ли кеширование
     */
    public void setCachingEnabled(boolean enabled) {
        this.cachingEnabled = enabled;
        LOGGER.info("API request caching {}", enabled ? "enabled" : "disabled");
    }
    
    /**
     * Устанавливает TTL для кеша команд
     * @param ttlMillis Время жизни кеша в миллисекундах
     */
    public void setCommandsCacheTtl(long ttlMillis) {
        this.commandsCacheTtl = ttlMillis;
        LOGGER.info("Commands cache TTL set to {} ms", ttlMillis);
    }
    
    /**
     * Устанавливает TTL для кеша heartbeat
     * @param ttlMillis Время жизни кеша в миллисекундах
     */
    public void setHeartbeatCacheTtl(long ttlMillis) {
        this.heartbeatCacheTtl = ttlMillis;
        LOGGER.info("Heartbeat cache TTL set to {} ms", ttlMillis);
    }
    
    /**
     * Очищает весь кеш запросов
     */
    public void clearCache() {
        requestCache.clear();
        LOGGER.info("Request cache cleared");
    }

    @Nullable
    public RegistrationResponse register(RegistrationRequest registrationRequest) throws IOException {
        // Регистрацию не кешируем, она выполняется редко
        String json = gson.toJson(registrationRequest);
        RequestBody body = RequestBody.create(json, JSON);
        Request request = new Request.Builder()
                .url(baseUrl + "/register")
                .header("X-API-Key", apiKey)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            return gson.fromJson(Objects.requireNonNull(response.body()).charStream(), RegistrationResponse.class);
        }
    }

    @Nullable
    public HeartbeatResponse sendHeartbeat(HeartbeatPayload payload) throws IOException {
        // Для heartbeat можем использовать короткое кеширование, если оно включено
        String cacheKey = "heartbeat:" + payload.getClientId();
        
        // Проверяем кеш если кеширование включено и TTL > 0
        if (cachingEnabled && heartbeatCacheTtl > 0) {
            CacheEntry<HeartbeatResponse> cachedResponse = 
                    (CacheEntry<HeartbeatResponse>) requestCache.get(cacheKey);
                    
            if (cachedResponse != null && !cachedResponse.isExpired()) {
                LOGGER.debug("Using cached heartbeat response");
                return cachedResponse.data;
            }
        }
        
        String json = gson.toJson(payload);
        RequestBody body = RequestBody.create(json, JSON);
        
        Request.Builder requestBuilder = new Request.Builder()
                .url(baseUrl + "/heartbeat")
                .header("X-API-Key", apiKey)
                .post(body);
        
        // Добавляем ETag, если он есть в кеше
        CacheEntry<?> existingEntry = requestCache.get(cacheKey);
        if (existingEntry != null && existingEntry.etag != null) {
            requestBuilder.header("If-None-Match", existingEntry.etag);
        }
        
        Request request = requestBuilder.build();

        try (Response response = client.newCall(request).execute()) {
            // Если получили 304 Not Modified и у нас есть кешированный ответ
            if (response.code() == 304 && existingEntry != null) {
                // Обновляем только время жизни кеша
                long newExpirationTime = System.currentTimeMillis() + heartbeatCacheTtl;
                requestCache.put(cacheKey, new CacheEntry<>(
                        ((CacheEntry<HeartbeatResponse>)existingEntry).data, 
                        newExpirationTime, 
                        existingEntry.etag));
                        
                return ((CacheEntry<HeartbeatResponse>)existingEntry).data;
            }
            
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            
            HeartbeatResponse heartbeatResponse = gson.fromJson(
                    Objects.requireNonNull(response.body()).charStream(), 
                    HeartbeatResponse.class);
            
            // Кешируем ответ, если кеширование включено и TTL > 0
            if (cachingEnabled && heartbeatCacheTtl > 0) {
                String etag = response.header("ETag");
                long expirationTime = System.currentTimeMillis() + heartbeatCacheTtl;
                requestCache.put(cacheKey, new CacheEntry<>(heartbeatResponse, expirationTime, etag));
            }
            
            return heartbeatResponse;
        }
    }

    @Nullable
    public CommandsResponse getCommands(@NotNull String clientId) throws IOException {
        String cacheKey = "commands:" + clientId;
        
        // Проверяем кеш, если кеширование включено
        if (cachingEnabled) {
            CacheEntry<CommandsResponse> cachedResponse = 
                    (CacheEntry<CommandsResponse>) requestCache.get(cacheKey);
                    
            if (cachedResponse != null && !cachedResponse.isExpired()) {
                LOGGER.debug("Using cached commands response for clientId: {}", clientId);
                return cachedResponse.data;
            }
        }
        
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(baseUrl + "/commands"))
                .newBuilder()
                .addQueryParameter("client_id", clientId)
                .build();
        
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .header("X-API-Key", apiKey);
                
        // Добавляем ETag, если он есть в кеше       
        CacheEntry<?> existingEntry = requestCache.get(cacheKey);
        if (existingEntry != null && existingEntry.etag != null) {
            requestBuilder.header("If-None-Match", existingEntry.etag);
        }
        
        Request request = requestBuilder.build();

        try (Response response = client.newCall(request).execute()) {
            // Если получили 304 Not Modified и у нас есть кешированный ответ
            if (response.code() == 304 && existingEntry != null) {
                // Обновляем время жизни кеша
                long newExpirationTime = System.currentTimeMillis() + commandsCacheTtl;
                requestCache.put(cacheKey, new CacheEntry<>(
                        ((CacheEntry<CommandsResponse>)existingEntry).data, 
                        newExpirationTime, 
                        existingEntry.etag));
                        
                LOGGER.debug("Using validated cached commands response for clientId: {}", clientId);
                return ((CacheEntry<CommandsResponse>)existingEntry).data;
            }
            
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            
            CommandsResponse commandsResponse = gson.fromJson(
                    Objects.requireNonNull(response.body()).charStream(), 
                    CommandsResponse.class);
            
            // Кешируем ответ, если кеширование включено
            if (cachingEnabled) {
                String etag = response.header("ETag");
                long expirationTime = System.currentTimeMillis() + commandsCacheTtl;
                requestCache.put(cacheKey, new CacheEntry<>(commandsResponse, expirationTime, etag));
                LOGGER.debug("Cached commands response for clientId: {} with TTL: {} ms",
                           clientId, commandsCacheTtl);
            }
            
            return commandsResponse;
        }
    }
} 