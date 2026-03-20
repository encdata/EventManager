package com.encdata.eventmanager.identity;

import com.encdata.eventmanager.EventManagerMod;
import com.encdata.eventmanager.mixin.PlayerEntityAccessor;
import com.encdata.eventmanager.role.RoleDefinition;
import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

public final class IdentityService {
    private static final Random RANDOM = new Random();

    private static final Map<UUID, SessionIdentity> assignedIdentities = new HashMap<>();
    private static final Map<UUID, String> randomizedNames = new HashMap<>();
    private static final Map<UUID, OriginalIdentity> originalIdentities = new HashMap<>();
    private static final Map<UUID, GameProfile> originalProfiles = new HashMap<>();

    private IdentityService() {
    }

    private record OriginalIdentity(Text customName, boolean customNameVisible) {
    }

    public record SessionIdentity(String name, String skinTextureValue, String skinSignature) {
        public boolean hasSkin() {
            return skinTextureValue != null && !skinTextureValue.isBlank()
                    && skinSignature != null && !skinSignature.isBlank();
        }
    }

    public static void init() {
        IdentityPoolService.load();
    }

    public static IdentityPoolService.LoadResult reloadPool() {
        return IdentityPoolService.load();
    }

    public static List<IdentityDefinition> getIdentityPoolSnapshot() {
        return IdentityPoolService.getIdentities();
    }

    public static Optional<SessionIdentity> getAssignedIdentity(UUID uuid) {
        return Optional.ofNullable(assignedIdentities.get(uuid));
    }

    public static void applyIdentity(ServerPlayerEntity player, RoleDefinition role) {
        UUID uuid = player.getUuid();
        boolean wantsRandomName = role.isRandomizeName();
        boolean wantsRandomSkin = role.isRandomizeSkin();

        originalIdentities.computeIfAbsent(uuid, ignored ->
                new OriginalIdentity(player.getCustomName(), player.isCustomNameVisible())
        );
        originalProfiles.computeIfAbsent(uuid, ignored -> cloneProfile(player.getGameProfile()));

        if (!wantsRandomName && !wantsRandomSkin) {
            clearIdentity(player);
            return;
        }

        SessionIdentity identity = assignedIdentities.computeIfAbsent(uuid, ignored -> selectIdentity(uuid));
        if (identity == null) {
            clearIdentity(player);
            player.sendMessage(Text.literal("Warning: No valid names/skins are available in config/eventmanager/identities/."));
            return;
        }

        if (wantsRandomName) {
            randomizedNames.put(uuid, identity.name());
            player.setCustomName(Text.literal(identity.name()));
            player.setCustomNameVisible(true);
        } else {
            restoreNameOnly(player);
        }

        GameProfile originalProfile = originalProfiles.get(uuid);
        if (wantsRandomSkin) {
            GameProfile desiredProfile = buildAppliedProfile(originalProfile, identity, wantsRandomName);
            if (!profilesEquivalent(player.getGameProfile(), desiredProfile)) {
                setPlayerProfile(player, desiredProfile);
                refreshProfile(player);
            }
        } else if (originalProfile != null && !profilesEquivalent(player.getGameProfile(), originalProfile)) {
            setPlayerProfile(player, cloneProfile(originalProfile));
            refreshProfile(player);
        }
    }

    public static void clearIdentity(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        randomizedNames.remove(uuid);
        restoreNameOnly(player);

        GameProfile originalProfile = originalProfiles.get(uuid);
        if (originalProfile != null && !profilesEquivalent(player.getGameProfile(), originalProfile)) {
            setPlayerProfile(player, cloneProfile(originalProfile));
            refreshProfile(player);
        }
    }

    public static void clearSession() {
        assignedIdentities.clear();
        randomizedNames.clear();
        originalIdentities.clear();
        originalProfiles.clear();
    }

    public static String getDisplayName(ServerPlayerEntity player) {
        String randomized = randomizedNames.get(player.getUuid());
        if (randomized != null) {
            return randomized;
        }
        return player.getName().getString();
    }

    private static SessionIdentity selectIdentity(UUID playerUuid) {
        List<String> names = IdentityPoolService.getNames();
        List<IdentityPoolService.SkinDefinition> skins = IdentityPoolService.getSkins();
        if (names.isEmpty() || skins.isEmpty()) {
            EventManagerMod.LOGGER.warn(
                    "Identity randomization requested for {} but the identity pool is incomplete: names={}, skins={}",
                    playerUuid,
                    names.size(),
                    skins.size()
            );
            return null;
        }

        List<String> preferredNames = names.stream()
                .filter(name -> assignedIdentities.values().stream().noneMatch(assigned -> assigned.name().equals(name)))
                .toList();
        List<IdentityPoolService.SkinDefinition> preferredSkins = skins.stream()
                .filter(skin -> assignedIdentities.values().stream().noneMatch(assigned ->
                        Objects.equals(assigned.skinTextureValue(), skin.skinTextureValue())
                                && Objects.equals(assigned.skinSignature(), skin.skinSignature())))
                .toList();

        List<String> candidateNames = preferredNames.isEmpty() ? names : preferredNames;
        List<IdentityPoolService.SkinDefinition> candidateSkins = preferredSkins.isEmpty() ? skins : preferredSkins;
        String chosenName = candidateNames.get(RANDOM.nextInt(candidateNames.size()));
        IdentityPoolService.SkinDefinition chosenSkin = candidateSkins.get(RANDOM.nextInt(candidateSkins.size()));
        return new SessionIdentity(chosenName, chosenSkin.skinTextureValue(), chosenSkin.skinSignature());
    }

    private static void restoreNameOnly(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        OriginalIdentity original = originalIdentities.get(uuid);
        if (original != null) {
            player.setCustomName(original.customName());
            player.setCustomNameVisible(original.customNameVisible());
        } else {
            player.setCustomName(null);
            player.setCustomNameVisible(false);
        }
        randomizedNames.remove(uuid);
    }

    private static GameProfile buildAppliedProfile(GameProfile originalProfile, SessionIdentity identity, boolean useIdentityName) {
        String profileName = useIdentityName ? identity.name() : originalProfile.name();
        PropertyMap properties = new PropertyMap(ImmutableMultimap.of(
                "textures",
                new Property("textures", identity.skinTextureValue(), identity.skinSignature())
        ));
        return new GameProfile(originalProfile.id(), profileName, properties);
    }

    private static GameProfile cloneProfile(GameProfile source) {
        return new GameProfile(source.id(), source.name(), source.properties());
    }

    private static boolean profilesEquivalent(GameProfile left, GameProfile right) {
        if (!Objects.equals(left.id(), right.id()) || !Objects.equals(left.name(), right.name())) {
            return false;
        }

        List<Property> leftTextures = List.copyOf(left.properties().get("textures"));
        List<Property> rightTextures = List.copyOf(right.properties().get("textures"));
        if (leftTextures.size() != rightTextures.size()) {
            return false;
        }

        for (int i = 0; i < leftTextures.size(); i++) {
            Property leftProperty = leftTextures.get(i);
            Property rightProperty = rightTextures.get(i);
            if (!Objects.equals(leftProperty.name(), rightProperty.name())
                    || !Objects.equals(leftProperty.value(), rightProperty.value())
                    || !Objects.equals(leftProperty.signature(), rightProperty.signature())) {
                return false;
            }
        }

        return true;
    }

    private static void setPlayerProfile(ServerPlayerEntity player, GameProfile profile) {
        ((PlayerEntityAccessor) player).setEventManagerGameProfile(profile);
    }

    private static void refreshProfile(ServerPlayerEntity player) {
        if (EventManagerMod.getServerInstance() == null) {
            return;
        }

        ServerWorld world = player.getEntityWorld();
        EventManagerMod.getServerInstance().getPlayerManager().sendToAll(new PlayerRemoveS2CPacket(List.of(player.getUuid())));
        EventManagerMod.getServerInstance().getPlayerManager().sendToAll(PlayerListS2CPacket.entryFromPlayer(List.of(player)));

        EntityTrackerEntry trackerEntry = new EntityTrackerEntry(world, player, 1, true, new EntityTrackerEntry.TrackerPacketSender() {
            @Override
            public void sendToListeners(net.minecraft.network.packet.Packet<? super net.minecraft.network.listener.ClientPlayPacketListener> packet) {
            }

            @Override
            public void sendToSelfAndListeners(net.minecraft.network.packet.Packet<? super net.minecraft.network.listener.ClientPlayPacketListener> packet) {
            }

            @Override
            public void sendToListenersIf(net.minecraft.network.packet.Packet<? super net.minecraft.network.listener.ClientPlayPacketListener> packet,
                                          java.util.function.Predicate<ServerPlayerEntity> predicate) {
            }
        });
        for (ServerPlayerEntity viewer : EventManagerMod.getServerInstance().getPlayerManager().getPlayerList()) {
            if (viewer.equals(player) || viewer.getEntityWorld() != world) {
                continue;
            }
            viewer.networkHandler.sendPacket(new EntitiesDestroyS2CPacket(player.getId()));
            trackerEntry.sendPackets(viewer, viewer.networkHandler::sendPacket);
        }

        player.networkHandler.sendPacket(new PlayerRespawnS2CPacket(player.createCommonPlayerSpawnInfo(world), PlayerRespawnS2CPacket.KEEP_ALL));
        EventManagerMod.getServerInstance().getPlayerManager().sendWorldInfo(player, world);
        EventManagerMod.getServerInstance().getPlayerManager().sendPlayerStatus(player);
        player.networkHandler.requestTeleport(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
        player.sendAbilitiesUpdate();
    }
}
