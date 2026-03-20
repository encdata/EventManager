package com.encdata.eventmanager.session;

import com.encdata.eventmanager.EventManagerMod;
import com.encdata.eventmanager.data.EventSavedData;
import com.encdata.eventmanager.identity.IdentityService;
import com.encdata.eventmanager.queue.HoldingService;
import com.encdata.eventmanager.queue.HoldingService.ContainmentReason;
import com.encdata.eventmanager.role.RoleDefinition;
import com.encdata.eventmanager.role.RoleRules;
import net.minecraft.command.DefaultPermissions;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class EventSessionService {
    public static final String DEFAULT_ROLE_NAME = "Default";

    public enum Phase {
        CLOSED,
        RUNNING,
        ENDING
    }

    private static Phase currentPhase = Phase.CLOSED;
    private static final Map<UUID, String> participantRoles = new HashMap<>();
    private static final Map<UUID, String> appliedKitRoles = new HashMap<>();

    public static boolean isActive() { return true; }
    public static Phase getPhase() { return currentPhase; }

    public static Map<UUID, String> getParticipantRolesSnapshot() {
        return new HashMap<>(participantRoles);
    }

    public static boolean isEnrolled(UUID uuid) {
        return participantRoles.containsKey(uuid);
    }

    public static boolean isEligible(ServerPlayerEntity player, EventSavedData data) {
        UUID uuid = player.getUuid();
        if (data.bypassPlayers.contains(uuid)) {
            return false;
        }
        return participantRoles.containsKey(uuid) || isAutoEligible(player, data);
    }

    public static void startEvent(Collection<ServerPlayerEntity> players) {
        currentPhase = Phase.RUNNING;
        EventSavedData data = EventManagerMod.getInstance().getData();
        appliedKitRoles.clear();

        for (ServerPlayerEntity player : players) {
            evaluatePlayer(player, data);
        }

        pruneContainmentForCurrentPhase();
    }

    public static void endEvent(Collection<ServerPlayerEntity> players) {
        currentPhase = Phase.ENDING;

        HoldingService.clear(players);
        for (ServerPlayerEntity player : players) {
            IdentityService.clearIdentity(player);
        }
        IdentityService.clearSession();
        participantRoles.clear();
        appliedKitRoles.clear();

        currentPhase = Phase.CLOSED;

        EventSavedData data = EventManagerMod.getInstance().getData();
        for (ServerPlayerEntity player : players) {
            evaluatePlayer(player, data);
        }
        pruneContainmentForCurrentPhase();
    }

    public static void evaluatePlayer(ServerPlayerEntity player, EventSavedData data) {
        UUID uuid = player.getUuid();

        ensureDefaultConfiguration(data);

        if (data.bypassPlayers.contains(uuid)) {
            HoldingService.removePlayer(player);
            appliedKitRoles.remove(uuid);
            IdentityService.clearIdentity(player);
            return;
        }

        if (!participantRoles.containsKey(uuid)) {
            assignRole(player, DEFAULT_ROLE_NAME);
            return;
        }

        applyPlayerState(player, data, true);
    }

    public static void enrollPlayer(ServerPlayerEntity player) {
        EventSavedData data = EventManagerMod.getInstance().getData();
        ensureDefaultConfiguration(data);
        if (data.defaultRole != null && data.roles.containsKey(data.defaultRole)) {
            assignRole(player, data.defaultRole);
        } else {
            assignRole(player, DEFAULT_ROLE_NAME);
        }
    }

    public static void assignRole(ServerPlayerEntity player, String roleName) {
        EventSavedData data = EventManagerMod.getInstance().getData();
        ensureDefaultConfiguration(data);
        RoleDefinition role = data.roles.get(roleName);
        if (role != null) {
            role.ensureDefaults();
            participantRoles.put(player.getUuid(), roleName);
            appliedKitRoles.remove(player.getUuid());
            applyPlayerState(player, data, true);
        }
    }

    public static void unassignRole(ServerPlayerEntity player) {
        EventSavedData data = EventManagerMod.getInstance().getData();
        ensureDefaultConfiguration(data);
        participantRoles.remove(player.getUuid());
        appliedKitRoles.remove(player.getUuid());
        IdentityService.clearIdentity(player);
        evaluatePlayer(player, data);
    }

    public static boolean isRuntimeParticipant(UUID uuid, EventSavedData data) {
        if (data.bypassPlayers.contains(uuid)) {
            return false;
        }

        String roleName = participantRoles.get(uuid);
        if (roleName == null) {
            return false;
        }

        if ("unassigned".equals(roleName)) {
            return true;
        }

        return data.roles.containsKey(roleName);
    }

    public static boolean shouldBeContained(UUID uuid, EventSavedData data) {
        if (currentPhase == Phase.RUNNING || data.bypassPlayers.contains(uuid)) {
            return false;
        }

        String roleName = participantRoles.get(uuid);
        if (roleName == null) {
            return false;
        }

        RoleDefinition role = data.roles.get(roleName);
        return role == null || !role.isBypassEventFlow();
    }

    public static void pruneContainmentForCurrentPhase() {
        EventSavedData data = EventManagerMod.getInstance().getData();
        if (EventManagerMod.getServerInstance() == null) {
            return;
        }
        for (UUID uuid : HoldingService.getContainedPlayersSnapshot()) {
            if (!shouldBeContained(uuid, data)) {
                ServerPlayerEntity online = EventManagerMod.getServerInstance().getPlayerManager().getPlayer(uuid);
                if (online != null) {
                    HoldingService.removePlayer(online);
                } else {
                    HoldingService.removePlayer(uuid);
                }
            }
        }
    }

    private static void teleportToRoleSpawn(ServerPlayerEntity player, RoleDefinition role) {
        ServerWorld world = EventManagerMod.getServerInstance().getWorld(RegistryKey.of(RegistryKeys.WORLD, role.getSpawnDimension()));
        if (world != null) {
            player.teleport(world, role.getSpawnX(), role.getSpawnY(), role.getSpawnZ(), Set.of(), role.getSpawnYaw(), role.getSpawnPitch(), true);
        }
    }

    private static void teleportToSafeWorldSpawn(ServerPlayerEntity player) {
        ServerWorld world = EventManagerMod.getServerInstance().getOverworld();
        if (world == null) {
            return;
        }

        BlockPos spawn = world.getSpawnPoint().getPos();
        BlockPos safePos = findSafeSpawnNear(world, spawn);
        player.teleport(world, safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5, Set.of(), player.getYaw(), player.getPitch(), true);
    }

    private static BlockPos findSafeSpawnNear(ServerWorld world, BlockPos center) {
        for (int radius = 0; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos candidateColumn = center.add(dx, 0, dz);
                    int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, candidateColumn.getX(), candidateColumn.getZ());
                    BlockPos foot = new BlockPos(candidateColumn.getX(), Math.max(topY, world.getBottomY() + 1), candidateColumn.getZ());
                    if (isSafeSpawn(world, foot)) {
                        return foot;
                    }
                }
            }
        }

        return center.up(1);
    }

    private static boolean isSafeSpawn(ServerWorld world, BlockPos foot) {
        if (foot.getY() <= world.getBottomY()) {
            return false;
        }

        var floor = world.getBlockState(foot.down());
        var body = world.getBlockState(foot);
        var head = world.getBlockState(foot.up());

        if (floor.isAir() || floor.isOf(net.minecraft.block.Blocks.BEDROCK)) {
            return false;
        }
        if (!body.isAir() || !head.isAir()) {
            return false;
        }
        return floor.isSideSolidFullSquare(world, foot.down(), Direction.UP);
    }

    public static RoleDefinition getPlayerRole(UUID uuid) {
        String roleName = participantRoles.get(uuid);
        if (roleName == null) {
            return null;
        }
        return EventManagerMod.getInstance().getData().roles.get(roleName);
    }

    private static boolean isAutoEligible(ServerPlayerEntity player, EventSavedData data) {
        return (data.adminAutoJoin && player.getCommandSource().getPermissions().hasPermission(DefaultPermissions.MODERATORS))
                || data.autoJoinPlayers.contains(player.getUuid());
    }

    private static void applyPlayerState(ServerPlayerEntity player, EventSavedData data, boolean warnIfMissingSpawn) {
        UUID uuid = player.getUuid();

        if (data.bypassPlayers.contains(uuid)) {
            HoldingService.removePlayer(player);
            appliedKitRoles.remove(uuid);
            IdentityService.clearIdentity(player);
            return;
        }

        String roleName = participantRoles.get(uuid);
        if (roleName == null) {
            HoldingService.removePlayer(player);
            appliedKitRoles.remove(uuid);
            IdentityService.clearIdentity(player);
            return;
        }

        RoleDefinition role = data.roles.get(roleName);
        if (role == null && !"unassigned".equals(roleName)) {
            participantRoles.put(uuid, "unassigned");
            roleName = "unassigned";
        }

        role = data.roles.get(roleName);
        if (role != null) {
            role.ensureDefaults();
            IdentityService.applyIdentity(player, role);
        } else {
            appliedKitRoles.remove(uuid);
            IdentityService.clearIdentity(player);
        }

        if (role != null && role.isBypassEventFlow()) {
            HoldingService.removePlayer(player);
            applyRoleKitIfNeeded(player, roleName, role);
            return;
        }

        if (currentPhase == Phase.RUNNING) {
            HoldingService.removePlayer(player);
            if (role != null) {
                applyRoleKitIfNeeded(player, roleName, role);
                if (role.hasSpawn()) {
                    teleportToRoleSpawn(player, role);
                } else if (warnIfMissingSpawn) {
                    EventManagerMod.LOGGER.warn("Role {} has no spawn configured for player {}", role.getName(), player.getName().getString());
                    teleportToSafeWorldSpawn(player);
                    player.sendMessage(Text.literal("Warning: Role " + role.getName() + " has no spawn configured. Teleported to a safe world spawn instead."));
                }
            }
            return;
        }

        HoldingService.addPlayer(player, ContainmentReason.CLOSED_ELIGIBLE_PARTICIPANT);
    }

    public static boolean ensureDefaultConfiguration(EventSavedData data) {
        boolean changed = false;

        RoleDefinition role = data.roles.get(DEFAULT_ROLE_NAME);
        if (role == null) {
            role = new RoleDefinition(DEFAULT_ROLE_NAME);
            role.setRules(RoleRules.fullyEnabled());
            role.setRandomizeName(true);
            role.setRandomizeSkin(true);
            role.setBypassEventFlow(false);
            data.roles.put(DEFAULT_ROLE_NAME, role);
            changed = true;
        }
        role.ensureDefaults();
        if (!DEFAULT_ROLE_NAME.equals(data.defaultRole)) {
            data.defaultRole = DEFAULT_ROLE_NAME;
            changed = true;
        }

        for (RoleDefinition definition : data.roles.values()) {
            definition.ensureDefaults();
        }

        return changed;
    }

    public static void handlePlayerRespawn(ServerPlayerEntity player) {
        appliedKitRoles.remove(player.getUuid());
        evaluatePlayer(player, EventManagerMod.getInstance().getData());
    }

    private static void applyRoleKitIfNeeded(ServerPlayerEntity player, String roleName, RoleDefinition role) {
        if (roleName == null || role == null) {
            return;
        }
        if (roleName.equals(appliedKitRoles.get(player.getUuid()))) {
            return;
        }

        player.getInventory().clear();
        for (RoleDefinition.KitEntry entry : role.getKitEntries()) {
            if (entry.slot() < 0 || entry.slot() >= player.getInventory().size()) {
                continue;
            }

            ItemStack stack = RoleDefinition.toItemStack(entry);
            if (stack.isEmpty()) {
                continue;
            }

            player.getInventory().setStack(entry.slot(), stack);
        }

        player.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
        player.playerScreenHandler.sendContentUpdates();
        player.currentScreenHandler.sendContentUpdates();
        appliedKitRoles.put(player.getUuid(), roleName);
    }
}
