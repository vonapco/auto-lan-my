package org.wsm.autolan.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.OpenToLanScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.text.Texts;
import net.minecraft.text.TranslatableTextContent;
import org.wsm.autolan.AutoLanServerValues;
import org.wsm.autolan.mixin.ButtonWidgetAccessor;
import org.wsm.autolan.SetCommandsAllowed;

public class AutoLanOpenToLanScreen extends OpenToLanScreen {
    private static final Text START_TEXT = Text.translatable("lanServer.start");
    private static final Text SAVE_TEXT = Text.translatable("lanServer.save");
    private static final Text COMMANDS_TEXT = Text.translatable("lanServer.allowCommands");

    private final Screen parent;

    private CyclingButtonWidget<Boolean> commandsButton;

    public AutoLanOpenToLanScreen(Screen parent) {
        super(parent);
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        // Найти существующий переключатель 'Использование команд' (Allow Cheats)
        for (var child : this.children()) {
            if (child instanceof CyclingButtonWidget<?>) {
                @SuppressWarnings("unchecked")
                CyclingButtonWidget<?> cbGeneric = (CyclingButtonWidget<?>) child;
                Text m = cbGeneric.getMessage();
                boolean matches = false;
                if (m.getContent() instanceof TranslatableTextContent ttc) {
                    matches = "lanServer.allowCommands".equals(ttc.getKey());
                }
                if (matches || m.equals(COMMANDS_TEXT)) {
                    //noinspection unchecked
                    this.commandsButton = (CyclingButtonWidget<Boolean>) cbGeneric;
                    break;
                }
            }
        }

        // Найти кнопку Start/Save и заменить её действие
        for (var child : this.children()) {
            if (child instanceof ButtonWidget button) {
                Text msg = button.getMessage();
                boolean isTarget = false;
                if (msg.getContent() instanceof TranslatableTextContent tr) {
                    String key = tr.getKey();
                    if ("lanServer.start".equals(key) || "lanServer.save".equals(key)) {
                        isTarget = true;
                    }
                }
                if (!isTarget && (msg.equals(START_TEXT) || msg.equals(SAVE_TEXT))) {
                    isTarget = true;
                }
                if (isTarget) {
                    ((ButtonWidgetAccessor) button).setOnPress(btn -> this.startOrSaveCustom());
                }
            }
        }
    }

    private void startOrSaveCustom() {
        MinecraftClient mc = MinecraftClient.getInstance();
        IntegratedServer server = mc.getServer();
        if (server == null) {
            mc.setScreen(this.parent);
            return;
        }

        // Закрываем экран
        mc.setScreen(null);

        AutoLanServerValues serverValues = (AutoLanServerValues) server;
        Text tunnelText = serverValues.getTunnelText();
        Text portText = Texts.bracketedCopyable(String.valueOf(server.getServerPort()));
        String motd = server.getServerMotd();
        Text newMsg;
        if (tunnelText != null) {
            newMsg = Text.translatable("commands.publish.saved.tunnel", portText, tunnelText, motd);
        } else {
            newMsg = Text.translatable("commands.publish.saved", portText, motd);
        }
        mc.inGameHud.getChatHud().addMessage(newMsg);

        // --- Выдаём права, если включено использование команд ---
        if (this.commandsButton != null && this.commandsButton.getValue()) {
            ((SetCommandsAllowed) server.getSaveProperties()).setCommandsAllowed(true);
            PlayerManager pm = server.getPlayerManager();
            for (ServerPlayerEntity player : pm.getPlayerList()) {
                if (!pm.isOperator(player.getGameProfile())) {
                    pm.addToOperators(player.getGameProfile());
                    pm.sendCommandTree(player);
                }
            }
        }
    }
} 