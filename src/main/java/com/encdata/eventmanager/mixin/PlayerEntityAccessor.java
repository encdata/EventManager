package com.encdata.eventmanager.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerEntity.class)
public interface PlayerEntityAccessor {
    @Accessor("gameProfile")
    GameProfile getEventManagerGameProfile();

    @Mutable
    @Accessor("gameProfile")
    void setEventManagerGameProfile(GameProfile profile);
}
