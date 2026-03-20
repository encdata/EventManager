package com.encdata.eventmanager.mixin;

import com.encdata.eventmanager.rules.RuleService;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
    private void onPickup(PlayerEntity player, CallbackInfo ci) {
        if (RuleService.shouldCancel(player, "pickupItems", player.getBlockPos())) {
            ci.cancel();
        }
    }
}
