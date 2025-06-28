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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class AutoLanAgent {
    public static final Logger LOGGER = LoggerFactory.getLogger("AutoLanAgent");
    
    private final ApiClient apiClient;
    private ScheduledExecutorService scheduler;
    private String clientId;
    private final AutoLanConfig config;
    private volatile boolean worldActive = false;
    
    private NgrokStateManager ngrokStateManager;
    private NgrokKeyManager ngrokKeyManager;
    private PersistentClientIdManager clientIdManager;

    public AutoLanAgent() {
        this.config = AutoLan.CONFIG.getConfig();
        this.apiClient = new ApiClient(AutoLan.SERVER_URL, AutoLan.API_KEY);
        this.ngrokStateManager = new NgrokStateManager();
        this.clientIdManager = new PersistentClientIdManager();
        
        // Регистрируем обработчики событий подключения/отключения
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            worldActive = true;
            LOGGER.info("World joined, setting status to active");
        });
        
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            worldActive = false;
            LOGGER.info("World disconnected, setting status to inactive");
        });
    }

    public void init() {
        if (!AutoLan.AGENT_ENABLED) {
            LOGGER.info("Auto-LAN Agent is disabled in the config.");
            return;
        }
        LOGGER.info("Auto-LAN Agent is initializing...");

        loadOrRegisterClientId();

        if (clientId == null) {
            LOGGER.error("Failed to get client ID. Agent will not start.");
            return;
        }
        
        // Инициализация менеджера ключей ngrok
        this.ngrokKeyManager = new NgrokKeyManager(ngrokStateManager, apiClient, clientId, config);
        String ngrokKey = ngrokKeyManager.initializeAndGetActiveKey();
        
        if (ngrokKey != null && !ngrokKey.isEmpty()) {
            LOGGER.info("Установлен активный ключ ngrok: {}", hideKey(ngrokKey));
        } else {
            LOGGER.warn("Не удалось получить действительный ключ ngrok");
        }

        startBackgroundTasks();
    }

    public void shutdown() {
        LOGGER.info("Auto-LAN Agent is shutting down...");
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        
        // Освобождаем временный ключ перед выходом, если он используется
        if (ngrokKeyManager != null) {
            ngrokKeyManager.releaseTemporaryKeyIfNeeded();
        }
        
        // Корректно завершаем работу менеджера ключей
        NgrokKeyManager.shutdown();
    }
    
    /**
     * Возвращает активный ключ ngrok для использования 
     * при настройке туннеля.
     * 
     * @return активный ключ ngrok или пустая строка, если ключ не доступен
     */
    public String getNgrokKey() {
        if (ngrokKeyManager != null) {
            String key = ngrokKeyManager.getActiveKey();
            return key != null ? key : "";
        }
        return "";
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
        if (scheduler != null && !scheduler.isShutdown()) {
            LOGGER.info("Scheduling registration retry in 60 seconds");
            scheduler.schedule(this::performRegistration, 60, TimeUnit.SECONDS);
        }
    }

    private void startBackgroundTasks() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        
        scheduler = Executors.newScheduledThreadPool(2); // One for heartbeat, one for commands
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 0, 10, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::fetchAndExecuteCommands, 0, 5, TimeUnit.SECONDS);
        
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
} 