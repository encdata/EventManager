package com.encdata.eventmanager.mixin;

import com.encdata.eventmanager.rules.RuleService;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
    @Inject(method = "dropItem(Lnet/minecraft/item/ItemStack;Z)Lnet/minecraft/entity/ItemEntity;", at = @At("HEAD"), cancellable = true)
    private void onDropItem(ItemStack stack, boolean retainOwnership, CallbackInfoReturnable<ItemEntity> cir) {
        if (RuleService.shouldCancel((PlayerEntity) (Object) this, "dropItems", ((PlayerEntity) (Object) this).getBlockPos())) {
            cir.setReturnValue(null);
            cir.cancel();
        }
    }

    @Inject(method = "canDropItems", at = @At("HEAD"), cancellable = true)
    private void onCanDropItems(CallbackInfoReturnable<Boolean> cir) {
        if (RuleService.shouldCancel((PlayerEntity) (Object) this, "dropItems", ((PlayerEntity) (Object) this).getBlockPos())) {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }

    @Inject(method = "applyDamage", at = @At("TAIL"))
    private void eventmanager$clampImmuneHealth(ServerWorld world, DamageSource source, float amount, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayerEntity player)) {
            return;
        }

        if (!RuleService.hasDeathImmunity(player) || RuleService.hasTotemInHand(player)) {
            return;
        }

        if (player.getHealth() < 1.0F) {
            player.setHealth(1.0F);
        }
    }
}
