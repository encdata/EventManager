package com.encdata.eventmanager.mixin;

import com.encdata.eventmanager.rules.RuleService;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
}
