package com.encdata.eventmanager.mixin;

import net.minecraft.server.PlayerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {
    @ModifyConstant(
            method = "onPlayerConnect",
            constant = @Constant(stringValue = "multiplayer.player.joined.renamed")
    )
    private String eventmanager$removeFormerlyKnownAsMessage(String original) {
        return "multiplayer.player.joined";
    }
}
