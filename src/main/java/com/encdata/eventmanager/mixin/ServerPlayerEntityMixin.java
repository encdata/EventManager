package com.encdata.eventmanager.mixin;

import com.encdata.eventmanager.rules.RuleService;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {
    @Inject(method = "dropSelectedItem", at = @At("HEAD"), cancellable = true)
    private void eventmanager$dropSelectedItem(boolean entireStack, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        if (RuleService.shouldCancel(player, "dropItems", player.getBlockPos())) {
            ci.cancel();
        }
    }
}
