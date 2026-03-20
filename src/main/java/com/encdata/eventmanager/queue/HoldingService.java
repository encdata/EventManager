package com.encdata.eventmanager.queue;

import com.encdata.eventmanager.EventManagerMod;
import com.encdata.eventmanager.data.EventSavedData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class HoldingService {
    public enum ContainmentReason {
        CLOSED_ELIGIBLE_PARTICIPANT("CLOSED eligible participant"),
        MANUAL_HOLD("manual hold");

        private final String label;

        ContainmentReason(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private static final Set<UUID> containedPlayers = new HashSet<>();
    private static final java.util.Map<UUID, ContainmentReason> containmentReasons = new java.util.HashMap<>();

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            EventSavedData data = EventManagerMod.getInstance().getData();
            Vec3d holdingPos = new Vec3d(data.holdingX, data.holdingY, data.holdingZ);
            
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (containedPlayers.contains(player.getUuid())) {
                    // Check dimension
                    if (!player.getEntityWorld().getRegistryKey().getValue().equals(data.holdingDimension)) {
                        teleportToHolding(player, data);
                        continue;
                    }

                    // Check distance
                    if (player.squaredDistanceTo(holdingPos) > 1.0) { // 1 block tolerance
                        player.requestTeleport(holdingPos.x, holdingPos.y, holdingPos.z);
                        player.setVelocity(0, 0, 0);
                    }
                }
            }
        });
    }

    public static void addPlayer(ServerPlayerEntity player) {
        addPlayer(player, ContainmentReason.CLOSED_ELIGIBLE_PARTICIPANT);
    }

    public static void addPlayer(ServerPlayerEntity player, ContainmentReason reason) {
        containedPlayers.add(player.getUuid());
        containmentReasons.put(player.getUuid(), reason);
        EventSavedData data = EventManagerMod.getInstance().getData();
        teleportToHolding(player, data);
        
        player.getAbilities().flying = true;
        player.getAbilities().allowFlying = true;
        player.sendAbilitiesUpdate();
    }

    public static void removePlayer(ServerPlayerEntity player) {
        if (containedPlayers.remove(player.getUuid())) {
            containmentReasons.remove(player.getUuid());
            restorePlayer(player);
        }
    }

    public static void removePlayer(UUID uuid) {
        containedPlayers.remove(uuid);
        containmentReasons.remove(uuid);
    }

    private static void teleportToHolding(ServerPlayerEntity player, EventSavedData data) {
        ServerWorld world = EventManagerMod.getServerInstance().getWorld(RegistryKey.of(RegistryKeys.WORLD, data.holdingDimension));
        if (world == null) {
            EventManagerMod.LOGGER.warn("Holding dimension {} is unavailable; falling back to overworld", data.holdingDimension);
            world = EventManagerMod.getServerInstance().getOverworld();
        }
        player.teleport(world, data.holdingX, data.holdingY, data.holdingZ, Set.of(), player.getYaw(), player.getPitch(), true);
    }

    private static void restorePlayer(ServerPlayerEntity player) {
        if (!player.isCreative() && !player.isSpectator()) {
            player.getAbilities().flying = false;
            player.getAbilities().allowFlying = false;
        }
        player.sendAbilitiesUpdate();
    }

    public static void clear(Iterable<ServerPlayerEntity> players) {
        for (ServerPlayerEntity player : players) {
            if (containedPlayers.contains(player.getUuid())) {
                restorePlayer(player);
            }
        }
        containedPlayers.clear();
        containmentReasons.clear();
    }

    public static boolean isContained(UUID uuid) {
        return containedPlayers.contains(uuid);
    }

    public static Set<UUID> getContainedPlayersSnapshot() {
        return new HashSet<>(containedPlayers);
    }

    public static ContainmentReason getContainmentReason(UUID uuid) {
        return containmentReasons.get(uuid);
    }
}
