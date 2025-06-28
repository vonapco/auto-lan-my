package org.wsm.autolan.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wsm.autolan.AutoLan;
import org.wsm.autolan.AutoLanConfig;
import org.wsm.autolan.agent.model.*;
import com.github.alexdlaird.ngrok.protocol.Tunnel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Map;
// import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents; // Will be removed

public class AutoLanAgent {
    public static final Logger LOGGER = LoggerFactory.getLogger("AutoLanAgent");

    // Dedicated executor for agent's own async tasks (not key retrieval)
    private static final ExecutorService AGENT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "AutoLanAgent-Worker");
        thread.setDaemon(true);
        return thread;
    });
    
    private final ApiClient apiClient;
    private ScheduledExecutorService heartbeatScheduler; // Renamed for clarity
    private String clientId;
    private final AutoLanConfig config;
    private volatile boolean worldActive = false; // Now controlled by setWorldActive
    private String activeNgrokKey = null; // To store the resolved ngrok key
    
    private NgrokStateManager ngrokStateManager;
    private NgrokKeyManager ngrokKeyManager;
    private PersistentClientIdManager clientIdManager;

    public AutoLanAgent() {
        this.config = AutoLan.CONFIG.getConfig();
        this.apiClient = new ApiClient(AutoLan.SERVER_URL, AutoLan.API_KEY);
        this.ngrokStateManager = new NgrokStateManager();
        this.clientIdManager = new PersistentClientIdManager();
        
        // Event handlers for worldActive are removed from here.
        // worldActive will be managed by AutoLan class via setWorldActive().
    }

    public CompletableFuture<Void> init() {
        if (!AutoLan.AGENT_ENABLED) {
            LOGGER.info("Auto-LAN Agent is disabled by AGENT_ENABLED=false flag.");
            return CompletableFuture.completedFuture(null); // Agent not enabled
        }
        LOGGER.info("Auto-LAN Agent initialization started...");

        // Chain of CompletableFuture
        return CompletableFuture.runAsync(() -> {
            // Step 1: Load or register client ID (blocking within this async step)
            loadOrRegisterClientId();
            if (this.clientId == null) {
                LOGGER.error("Failed to obtain client ID. Agent initialization aborted.");
                throw new IllegalStateException("Client ID could not be obtained.");
            }
            LOGGER.info("Client ID obtained: {}", hideKey(this.clientId));
            this.ngrokKeyManager = new NgrokKeyManager(ngrokStateManager, apiClient, this.clientId, config);
        }, AGENT_EXECUTOR)
        .thenComposeAsync(v -> {
            // Step 2: Initialize and get ngrok key
            LOGGER.info("Requesting ngrok key...");
            return ngrokKeyManager.initializeAndGetActiveKey();
        }, AGENT_EXECUTOR)
        .thenAcceptAsync(ngrokKey -> {
            // Step 3: Store key and start background tasks
            if (ngrokKey != null && !ngrokKey.isEmpty()) {
                this.activeNgrokKey = ngrokKey;
                LOGGER.info("Successfully obtained ngrok key: {}", hideKey(this.activeNgrokKey));
                startBackgroundTasks(); // Start heartbeats etc.
                LOGGER.info("Auto-LAN Agent initialized successfully and background tasks started.");
            } else {
                LOGGER.error("Failed to obtain a valid ngrok key. Agent initialization failed at key step.");
                throw new RuntimeException("Failed to obtain a valid ngrok key.");
            }
        }, AGENT_EXECUTOR)
        .exceptionally(ex -> {
            LOGGER.error("Auto-LAN Agent initialization failed.", ex);
            // Ensure cleanup if partial initialization occurred
            shutdownInternal(false); // Don't release key if init failed before getting it
            return null;
        });
    }

    private void shutdownInternal(boolean releaseKey) {
        LOGGER.info("Auto-LAN Agent internal shutdown sequence initiated (release key: {})...", releaseKey);
        if (heartbeatScheduler != null && !heartbeatScheduler.isShutdown()) {
            heartbeatScheduler.shutdownNow();
            try {
                if (!heartbeatScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warn("Heartbeat scheduler did not terminate in time.");
                }
            } catch (InterruptedException e) {
                LOGGER.warn("Interrupted while waiting for heartbeat scheduler to terminate.");
                Thread.currentThread().interrupt();
            }
            heartbeatScheduler = null;
            LOGGER.info("Heartbeat scheduler shut down.");
        }
        
        if (releaseKey && ngrokKeyManager != null) {
            LOGGER.info("Requesting ngrok key release...");
            ngrokKeyManager.releaseTemporaryKeyIfNeeded(); // This is async now
        }

        // NgrokKeyManager.shutdown() should be called once when the mod unloads,
        // not every time an agent instance is shut down, to manage its executor.
        // This will be handled by AutoLan's main shutdown hook or server stopping event.

        this.activeNgrokKey = null;
        this.worldActive = false;
        LOGGER.info("Auto-LAN Agent internal shutdown sequence completed.");
    }

    public void shutdown() {
        LOGGER.info("Auto-LAN Agent public shutdown called.");
        shutdownInternal(true); // Default to releasing key on public shutdown

        // Shutdown the agent's own executor
        AGENT_EXECUTOR.shutdown();
        try {
            if (!AGENT_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                AGENT_EXECUTOR.shutdownNow();
                LOGGER.warn("Agent executor did not terminate in time.");
            } else {
                LOGGER.info("Agent executor shut down successfully.");
            }
        } catch (InterruptedException e) {
            AGENT_EXECUTOR.shutdownNow();
            LOGGER.warn("Interrupted while waiting for agent executor to terminate.");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns the active ngrok key. This should only be called after the init() CompletableFuture has completed successfully.
     * @return The active ngrok key, or null if not initialized or key retrieval failed.
     */
    public String getNgrokKey() {
        if (activeNgrokKey == null) {
            LOGGER.warn("getNgrokKey() called but activeNgrokKey is null. Agent might not be initialized or key retrieval failed.");
        }
        return activeNgrokKey;
    }

    public void setWorldActive(boolean active) {
        this.worldActive = active;
        LOGGER.info("AutoLanAgent world status set to: {}", active);
        if (active && (heartbeatScheduler == null || heartbeatScheduler.isShutdown())) {
             if (clientId != null && activeNgrokKey != null) {
                LOGGER.info("World is active and prerequisites met, ensuring background tasks are running.");
                startBackgroundTasks();
             } else {
                LOGGER.warn("World set to active, but agent may not be fully initialized (clientId or ngrokKey missing). Background tasks not started.");
             }
        } else if (!active && heartbeatScheduler != null && !heartbeatScheduler.isShutdown()) {
            LOGGER.info("World is inactive, stopping background tasks (heartbeats).");
            heartbeatScheduler.shutdownNow();
            // It's better to re-create the scheduler in startBackgroundTasks if needed again
        }
    }
    
    /**
     * Скрывает большую часть ключа для безопасного логирования
     */
    private String hideKey(String key) {
        return org.wsm.autolan.util.SecurityUtil.maskSensitiveData(key);
    }

    private void loadOrRegisterClientId() {
        try {
            // Используем менеджер для получения постоянного client_id
            this.clientId = clientIdManager.getOrCreateClientId();
            LOGGER.info("Используется постоянный client ID: {}", org.wsm.autolan.util.SecurityUtil.maskSensitiveData(this.clientId));
        } catch (Exception e) {
            LOGGER.error("Ошибка при получении постоянного client ID", e);
            // Пытаемся получить ID с сервера, если не смогли загрузить локально
            performRegistration();
        }
    }

    private void performRegistration() {
        try {
            String computerName = getComputerName();
            // Читаем client.id из папки Industrial/Industrial
            String industrialClientId = readIndustrialClientId();
            if (industrialClientId == null) {
                industrialClientId = ""; // избегаем null в JSON
            }
            
            RegistrationRequest request = new RegistrationRequest(computerName, industrialClientId);
            
            try {
                RegistrationResponse response = apiClient.register(request);
                if (response != null && response.getClientId() != null) {
                    this.clientId = response.getClientId();
                    LOGGER.info("Successfully registered and received new client ID: {}", org.wsm.autolan.util.SecurityUtil.maskSensitiveData(clientId));
                } else {
                    LOGGER.error("Registration response was null or did not contain a client ID.");
                }
            } catch (IOException e) {
                LOGGER.error("Network error during registration: {}", e.getMessage());
                // Планируем повторную попытку через некоторое время
                scheduleRetryRegistration();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to register client", e);
        }
    }

    private void scheduleRetryRegistration() {
        if (heartbeatScheduler != null && !heartbeatScheduler.isShutdown()) {
            LOGGER.info("Scheduling registration retry in 60 seconds");
            heartbeatScheduler.schedule(this::performRegistration, 60, TimeUnit.SECONDS);
        }
    }

    private void startBackgroundTasks() {
        if (heartbeatScheduler != null && !heartbeatScheduler.isShutdown()) {
            // Ensure previous scheduler is properly shut down before creating a new one
            heartbeatScheduler.shutdownNow();
            try {
                if (!heartbeatScheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                     LOGGER.warn("Previous heartbeat scheduler did not terminate quickly during restart.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        heartbeatScheduler = Executors.newScheduledThreadPool(2); // One for heartbeat, one for commands
        heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeat, 0, 10, TimeUnit.SECONDS);
        heartbeatScheduler.scheduleAtFixedRate(this::fetchAndExecuteCommands, 0, 5, TimeUnit.SECONDS);
        
        LOGGER.info("Auto-LAN agent started with client ID: {}", org.wsm.autolan.util.SecurityUtil.maskSensitiveData(clientId));
    }

    private void sendHeartbeat() {
        try {
            HeartbeatPayload payload = new HeartbeatPayload();
            payload.setClientId(this.clientId);
            payload.setStatus(gatherStatus());
            payload.setNgrokUrls(getNgrokUrls());

            try {
                apiClient.sendHeartbeat(payload);
                LOGGER.debug("Heartbeat sent successfully");
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("404")) {
                    LOGGER.warn("Client ID not recognized by server. Re-registering...");
                    performRegistration();
                } else {
                    // Для других ошибок просто логируем, но не прерываем работу агента
                    LOGGER.error("Failed to send heartbeat: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            // Перехватываем все исключения, чтобы не нарушить работу планировщика
            LOGGER.error("Error preparing heartbeat data", e);
        }
    }

    private Map<String, String> getNgrokUrls() {
        Map<String, String> urls = new HashMap<>();
        
        // Только если мир активен, получаем туннели из Auto-LAN
        if (worldActive) {
            // Получаем URLs из активных туннелей
            urls.putAll(AutoLan.activeTunnels);
            
            // Также проверяем Ngrok клиент, как дополнительный источник
            if (AutoLan.NGROK_CLIENT != null) {
                try {
                    for (Tunnel tunnel : AutoLan.NGROK_CLIENT.getTunnels()) {
                        urls.put("minecraft", tunnel.getPublicUrl());
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to get ngrok tunnels", e);
                }
            }
        }
        
        return urls;
    }

    private void fetchAndExecuteCommands() {
        if (clientId == null || clientId.isEmpty()) {
            // Если ID клиента отсутствует, пытаемся его получить
            loadOrRegisterClientId();
            return;
        }
        
        try {
            CommandsResponse response = apiClient.getCommands(this.clientId);
            if (response != null && response.getCommands() != null) {
                for (Command command : response.getCommands()) {
                    executeCommand(command);
                }
            }
        } catch (IOException e) {
            // При ошибке запроса просто логируем, но не останавливаем агента
            LOGGER.error("Failed to fetch commands: {}", e.getMessage());
        } catch (Exception e) {
            // Перехватываем все исключения, чтобы не нарушить работу планировщика
            LOGGER.error("Unexpected error when processing commands", e);
        }
    }

    private Status gatherStatus() {
        // Получение информации о состоянии системы и сервера
        Status status = new Status();
        
        boolean isServerRunning = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        
        // Проверяем, работает ли интегрированный сервер
        if (mc != null && mc.isIntegratedServerRunning() && mc.getServer() != null) {
            isServerRunning = true;
        }
        
        status.setServerRunning(isServerRunning);
        status.setMinecraftClientActive(worldActive);
        
        // Заглушка для статистики процессов
        status.setSystemStats(new ProcessStats(0.0, 0.0));
        status.setServerProcessStats(new ProcessStats(0.0, 0.0));
        
        return status;
    }

    private void executeCommand(Command command) {
        LOGGER.info("Received command: {}", command.getCommand());
        // Реализация выполнения команд
        switch (command.getCommand()) {
            case "start_server":
                // Логика запуска сервера
                break;
            case "stop_server":
                // Логика остановки сервера
                break;
            default:
                LOGGER.warn("Unknown command received: {}", command.getCommand());
        }
    }

    private String getComputerName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            LOGGER.warn("Could not determine hostname, using 'unknown-pc'.", e);
            return "unknown-pc";
        }
    }

    /**
     * Считывает содержимое файла Industrial/Industrial/client.id в директории игры.
     * @return содержимое файла client.id или пустую строку, если файл не найден/ошибка чтения
     */
    private String readIndustrialClientId() {
        try {
            // Корневая директория игры
            java.nio.file.Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
            java.nio.file.Path idFile = gameDir.resolve("Industrial").resolve("Industrial").resolve("client.id");

            if (java.nio.file.Files.exists(idFile)) {
                String id = java.nio.file.Files.readString(idFile).trim();
                LOGGER.info("Found Industrial client.id: {}", id);
                return id;
            } else {
                LOGGER.warn("Industrial client.id not found at {}", idFile.toString());
            }
        } catch (Exception e) {
            LOGGER.error("Failed to read Industrial client.id", e);
        }
        return ""; // возвращаем пустую строку, чтобы не посылать null
    }

    /**
     * Обновляет URL-адреса туннелей без ожидания следующего heartbeat
     * @param tunnelName Имя туннеля
     * @param tunnelUrl URL туннеля
     */
    public void updateTunnelUrl(String tunnelName, String tunnelUrl) {
        // This method is called when a new tunnel is established
        LOGGER.info("New tunnel established: {} -> {}", tunnelName, tunnelUrl);
    }

    /**
     * Shuts down the agent's dedicated executor service.
     * This should be called globally when the mod is shutting down.
     */
    public static void shutdownAgentExecutor() {
        if (AGENT_EXECUTOR != null && !AGENT_EXECUTOR.isShutdown()) {
            LOGGER.info("Shutting down AutoLanAgent AGENT_EXECUTOR...");
            AGENT_EXECUTOR.shutdown();
            try {
                if (!AGENT_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                    AGENT_EXECUTOR.shutdownNow();
                    LOGGER.warn("AutoLanAgent AGENT_EXECUTOR did not terminate in time.");
                } else {
                    LOGGER.info("AutoLanAgent AGENT_EXECUTOR shut down successfully.");
                }
            } catch (InterruptedException e) {
                AGENT_EXECUTOR.shutdownNow();
                LOGGER.warn("Interrupted while waiting for AutoLanAgent AGENT_EXECUTOR to terminate.");
                Thread.currentThread().interrupt();
            }
        } else {
            LOGGER.info("AutoLanAgent AGENT_EXECUTOR is null or already shut down.");
        }
    }
} 