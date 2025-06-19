package org.wsm.autolan.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import net.minecraft.client.gui.screen.GameMenuScreen;
import org.wsm.autolan.gui.AutoLanOpenToLanScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.GridWidget;
import net.minecraft.client.gui.widget.Widget;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;

@Mixin(GameMenuScreen.class)
public class GameMenuScreenMixin extends Screen {
    private static final Text PLAYER_REPORTING_TEXT = Text.translatable("menu.playerReporting");

    private static final Text OPEN_LAN_TEXT = Text.translatable("menu.shareToLan");

    protected GameMenuScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "initWidgets", at = @At("RETURN"), locals = LocalCapture.CAPTURE_FAILEXCEPTION)
    private void updateOpenToLanButton(CallbackInfo ci, GridWidget gridWidget) {
        IntegratedServer server = this.client.getServer();

        boolean isHost = this.client.isIntegratedServerRunning();
        if (isHost) {
            for (Widget widget : ((GridWidgetAccessor) gridWidget).getChildren()) {
                if (!(widget instanceof ButtonWidget)) {
                    continue;
                }
                ButtonWidget button = (ButtonWidget) widget;

                // Случай, когда мир уже открыт: вместо Player Reporting показываем «Открыть для сети»
                if (server.isRemote() && PLAYER_REPORTING_TEXT.equals(button.getMessage())) {
                    button.setMessage(OPEN_LAN_TEXT);
                    ((ButtonWidgetAccessor) button)
                            .setOnPress(btn -> this.client.setScreen(new AutoLanOpenToLanScreen(this)));
                }

                // Случай, когда мир ещё НЕ открыт: у кнопки уже правильный текст, меняем действие
                if (OPEN_LAN_TEXT.equals(button.getMessage())) {
                    ((ButtonWidgetAccessor) button)
                            .setOnPress(btn -> this.client.setScreen(new AutoLanOpenToLanScreen(this)));
                }
            }
        }
    }
}
