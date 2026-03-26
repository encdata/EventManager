package com.encdata.eventmanager.identity;

import com.encdata.eventmanager.EventManagerMod;
import com.encdata.eventmanager.mixin.PlayerEntityAccessor;
import com.encdata.eventmanager.role.RoleDefinition;
import com.google.common.collect.ArrayListMultimap;
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
        if (shouldSkipProfileRandomization(player)) {
            clearIdentity(player);
            return;
        }

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
        if (originalProfile == null) {
            return;
        }

        GameProfile desiredProfile = buildAppliedProfile(originalProfile, identity, wantsRandomName, wantsRandomSkin);
        if (!profilesEquivalent(player.getGameProfile(), desiredProfile)) {
            setPlayerProfile(player, desiredProfile);
            refreshProfile(player);
        }
    }

    public static boolean rerollIdentity(ServerPlayerEntity player, RoleDefinition role, boolean rerollName, boolean rerollSkin) {
        if (shouldSkipProfileRandomization(player)) {
            clearIdentity(player);
            return false;
        }

        if (role == null || (!rerollName && !rerollSkin)) {
            return false;
        }

        UUID uuid = player.getUuid();
        SessionIdentity currentIdentity = assignedIdentities.get(uuid);
        if (currentIdentity == null) {
            applyIdentity(player, role);
            return assignedIdentities.containsKey(uuid);
        }

        String updatedName = currentIdentity.name();
        String updatedSkinValue = currentIdentity.skinTextureValue();
        String updatedSkinSignature = currentIdentity.skinSignature();

        if (rerollName) {
            String nextName = selectName(uuid, currentIdentity.name());
            if (nextName == null) {
                return false;
            }
            updatedName = nextName;
        }

        if (rerollSkin) {
            IdentityPoolService.SkinDefinition nextSkin = selectSkin(uuid, currentIdentity.skinTextureValue(), currentIdentity.skinSignature());
            if (nextSkin == null) {
                return false;
            }
            updatedSkinValue = nextSkin.skinTextureValue();
            updatedSkinSignature = nextSkin.skinSignature();
        }

        assignedIdentities.put(uuid, new SessionIdentity(updatedName, updatedSkinValue, updatedSkinSignature));
        applyIdentity(player, role);
        return true;
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

    public static void resetIdentity(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        clearIdentity(player);
        assignedIdentities.remove(uuid);
        originalIdentities.remove(uuid);
        originalProfiles.remove(uuid);
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
        String chosenName = selectName(playerUuid, null);
        IdentityPoolService.SkinDefinition chosenSkin = selectSkin(playerUuid, null, null);
        if (chosenName == null || chosenSkin == null) {
            EventManagerMod.logWarn(
                    "Identity randomization requested for {} but the identity pool is incomplete: names={}, skins={}",
                    playerUuid,
                    IdentityPoolService.getNames().size(),
                    IdentityPoolService.getSkins().size()
            );
            return null;
        }
        return new SessionIdentity(chosenName, chosenSkin.skinTextureValue(), chosenSkin.skinSignature());
    }

    private static String selectName(UUID playerUuid, String currentName) {
        List<String> names = IdentityPoolService.getNames();
        if (names.isEmpty()) {
            return null;
        }

        List<String> preferredNames = names.stream()
                .filter(name -> !Objects.equals(name, currentName))
                .filter(name -> assignedIdentities.entrySet().stream()
                        .filter(entry -> !entry.getKey().equals(playerUuid))
                        .noneMatch(entry -> entry.getValue().name().equals(name)))
                .toList();

        List<String> fallbackNames = names.stream()
                .filter(name -> !Objects.equals(name, currentName))
                .toList();
        List<String> candidateNames = preferredNames.isEmpty() ? fallbackNames : preferredNames;
        if (candidateNames.isEmpty()) {
            return currentName != null ? currentName : names.get(RANDOM.nextInt(names.size()));
        }
        return candidateNames.get(RANDOM.nextInt(candidateNames.size()));
    }

    private static IdentityPoolService.SkinDefinition selectSkin(UUID playerUuid, String currentSkinValue, String currentSkinSignature) {
        List<IdentityPoolService.SkinDefinition> skins = IdentityPoolService.getSkins();
        if (skins.isEmpty()) {
            return null;
        }

        List<IdentityPoolService.SkinDefinition> preferredSkins = skins.stream()
                .filter(skin -> !Objects.equals(skin.skinTextureValue(), currentSkinValue)
                        || !Objects.equals(skin.skinSignature(), currentSkinSignature))
                .filter(skin -> assignedIdentities.entrySet().stream()
                        .filter(entry -> !entry.getKey().equals(playerUuid))
                        .noneMatch(entry -> Objects.equals(entry.getValue().skinTextureValue(), skin.skinTextureValue())
                                && Objects.equals(entry.getValue().skinSignature(), skin.skinSignature())))
                .toList();

        List<IdentityPoolService.SkinDefinition> fallbackSkins = skins.stream()
                .filter(skin -> !Objects.equals(skin.skinTextureValue(), currentSkinValue)
                        || !Objects.equals(skin.skinSignature(), currentSkinSignature))
                .toList();

        List<IdentityPoolService.SkinDefinition> candidateSkins = preferredSkins.isEmpty() ? fallbackSkins : preferredSkins;
        if (candidateSkins.isEmpty()) {
            return currentSkinValue != null && currentSkinSignature != null
                    ? new IdentityPoolService.SkinDefinition(currentSkinValue, currentSkinSignature)
                    : skins.get(RANDOM.nextInt(skins.size()));
        }
        return candidateSkins.get(RANDOM.nextInt(candidateSkins.size()));
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

    private static GameProfile buildAppliedProfile(GameProfile originalProfile, SessionIdentity identity, boolean useIdentityName, boolean useIdentitySkin) {
        String profileName = useIdentityName ? identity.name() : originalProfile.name();
        PropertyMap properties;
        if (useIdentitySkin) {
            properties = new PropertyMap(ImmutableMultimap.of(
                    "textures",
                    new Property("textures", identity.skinTextureValue(), identity.skinSignature())
            ));
        } else {
            properties = copyProperties(originalProfile.properties());
        }
        return new GameProfile(originalProfile.id(), profileName, properties);
    }

    private static GameProfile cloneProfile(GameProfile source) {
        return new GameProfile(source.id(), source.name(), copyProperties(source.properties()));
    }

    private static PropertyMap copyProperties(PropertyMap source) {
        ArrayListMultimap<String, Property> copy = ArrayListMultimap.create();
        for (String key : source.keySet()) {
            for (Property property : source.get(key)) {
                copy.put(key, new Property(property.name(), property.value(), property.signature()));
            }
        }
        return new PropertyMap(copy);
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

    private static boolean shouldSkipProfileRandomization(ServerPlayerEntity player) {
        if (player.getCommandSource().getPermissions().hasPermission(net.minecraft.command.DefaultPermissions.MODERATORS)) {
            return true;
        }

        var server = player.getCommandSource().getServer();
        return server.isSingleplayer() && server.isHost(player.getPlayerConfigEntry());
    }
}
