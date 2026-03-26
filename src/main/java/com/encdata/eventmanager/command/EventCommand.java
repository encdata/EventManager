package com.encdata.eventmanager.command;

import com.encdata.eventmanager.EventManagerMod;
import com.encdata.eventmanager.data.EventSavedData;
import com.encdata.eventmanager.gui.RoleConfigGui;
import com.encdata.eventmanager.identity.IdentityDefinition;
import com.encdata.eventmanager.identity.IdentityPoolService;
import com.encdata.eventmanager.identity.IdentityService;
import com.encdata.eventmanager.queue.HoldingService;
import com.encdata.eventmanager.queue.HoldingService.ContainmentReason;
import com.encdata.eventmanager.role.RoleDefinition;
import com.encdata.eventmanager.session.EventSessionService;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.DefaultPermissions;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class EventCommand {
    private static final int IDENTITY_LIST_PREVIEW_LIMIT = 25;
    private static final int MAX_CHAT_MESSAGE_LENGTH = 30000;

    public static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("event")
                .requires(source -> source.getPermissions().hasPermission(DefaultPermissions.MODERATORS) || isSingleplayerHost(source))
                .then(literal("start").executes(context -> {
                    EventManagerMod.setServerInstance(context.getSource().getServer());
                    try {
                        EventSessionService.startEvent(context.getSource().getServer().getPlayerManager().getPlayerList());
                        context.getSource().sendFeedback(() -> Text.literal("Event started! Phase: RUNNING (participants released from holding)"), true);
                        return 1;
                    } catch (Exception e) {
                        EventManagerMod.logError("Failed to start event", e);
                        context.getSource().sendError(Text.literal("Failed to start event. Check server log for the exact cause."));
                        return 0;
                    }
                }))
                .then(literal("end").executes(context -> {
                    EventManagerMod.setServerInstance(context.getSource().getServer());
                    try {
                        EventSessionService.endEvent(context.getSource().getServer().getPlayerManager().getPlayerList());
                        context.getSource().sendFeedback(() -> Text.literal("Event ended! Phase: CLOSED (containment active)"), true);
                        return 1;
                    } catch (Exception e) {
                        EventManagerMod.logError("Failed to end event", e);
                        context.getSource().sendError(Text.literal("Failed to end event. Check server log for the exact cause."));
                        return 0;
                    }
                }))
                .then(literal("status").executes(context -> {
                    EventSavedData data = EventManagerMod.getInstance().getData();
                    EventSessionService.Phase phase = EventSessionService.getPhase();
                    Map<UUID, String> participants = EventSessionService.getParticipantRolesSnapshot();
                    long participantCount = participants.keySet().stream()
                            .filter(uuid -> EventSessionService.isRuntimeParticipant(uuid, data))
                            .count();
                    long unassignedCount = participants.entrySet().stream()
                            .filter(entry -> EventSessionService.isRuntimeParticipant(entry.getKey(), data))
                            .filter(entry -> "unassigned".equals(entry.getValue()))
                            .count();
                    int containedCount = HoldingService.getContainedPlayersSnapshot().size();
                    boolean containmentActive = phase == EventSessionService.Phase.CLOSED || phase == EventSessionService.Phase.ENDING;

                    String message = "Event status: phase=" + phase
                            + ", containment=" + (containmentActive ? "active" : "released")
                            + ", participants=" + participantCount + (unassignedCount > 0 ? " (" + unassignedCount + " unassigned)" : "")
                            + ", contained=" + containedCount;

                    context.getSource().sendFeedback(() -> Text.literal(message), false);
                    if (phase == EventSessionService.Phase.RUNNING && containedCount > 0) {
                        context.getSource().sendFeedback(() -> Text.literal("Warning: contained players during RUNNING are stale/manual anomalies. Use /event debug contained."), false);
                    }
                    return 1;
                }))
                .then(literal("reload").executes(context -> {
                    EventSavedData data = EventManagerMod.getInstance().reloadConfig();
                    for (ServerPlayerEntity player : context.getSource().getServer().getPlayerManager().getPlayerList()) {
                        EventSessionService.evaluatePlayer(player, data);
                    }
                    EventSessionService.pruneContainmentForCurrentPhase();
                    context.getSource().sendFeedback(() -> Text.literal(
                            "Reloaded config. logging=" + data.enableLogging + ", roles=" + data.roles.size() + ", defaultRole=" + data.defaultRole
                    ), true);
                    return 1;
                }))
                .then(literal("debug")
                    .then(literal("participants").executes(context -> {
                        EventSavedData data = EventManagerMod.getInstance().getData();
                        Map<UUID, String> participants = EventSessionService.getParticipantRolesSnapshot();
                        StringBuilder sb = new StringBuilder("Participants:\n");
                        for (Map.Entry<UUID, String> entry : participants.entrySet()) {
                            UUID uuid = entry.getKey();
                            if (!EventSessionService.isRuntimeParticipant(uuid, data)) {
                                continue;
                            }

                            String roleName = entry.getValue();
                            RoleDefinition role = EventSessionService.getPlayerRole(uuid);
                            boolean bypassFlow = role != null && role.isBypassEventFlow();
                            boolean contained = HoldingService.isContained(uuid);
                            sb.append("- ").append(describePlayer(context.getSource(), uuid))
                              .append(" role=").append(roleName)
                              .append(" bypassFlow=").append(bypassFlow)
                              .append(" contained=").append(contained)
                              .append("\n");
                        }
                        sendSafeFeedback(context.getSource(), sb.toString(), false);
                        return 1;
                    }))
                    .then(literal("contained").executes(context -> {
                        Collection<UUID> contained = HoldingService.getContainedPlayersSnapshot();
                        EventSavedData data = EventManagerMod.getInstance().getData();
                        Map<UUID, String> participants = EventSessionService.getParticipantRolesSnapshot();
                        StringBuilder sb = new StringBuilder("Contained players (").append(contained.size()).append("):\n");
                        for (UUID uuid : contained) {
                            String roleName = participants.get(uuid);
                            boolean isParticipant = EventSessionService.isRuntimeParticipant(uuid, data);
                            boolean isBypassListed = data.bypassPlayers.contains(uuid);
                            RoleDefinition roleDef = EventSessionService.getPlayerRole(uuid);
                            boolean isBypassRole = roleDef != null && roleDef.isBypassEventFlow();

                            sb.append("- ").append(describePlayer(context.getSource(), uuid))
                              .append(" participant=").append(isParticipant)
                              .append(" bypassListed=").append(isBypassListed)
                              .append(" bypassFlow=").append(isBypassRole)
                              .append(" role=").append(roleName != null ? roleName : "none")
                              .append(" reason=").append(describeContainmentReason(uuid, roleName, isParticipant, isBypassListed, roleDef))
                              .append("\n");
                        }
                        sendSafeFeedback(context.getSource(), sb.toString(), false);
                        return 1;
                    }))
                    .then(literal("identity").then(argument("player", EntityArgumentType.player()).executes(context -> {
                        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
                        RoleDefinition role = EventSessionService.getPlayerRole(player.getUuid());
                        IdentityService.SessionIdentity assignedIdentity = IdentityService.getAssignedIdentity(player.getUuid()).orElse(null);
                        String currentCustomName = player.getCustomName() != null ? player.getCustomName().getString() : "none";

                        StringBuilder sb = new StringBuilder("Identity debug for ")
                                .append(player.getName().getString())
                                .append(":\n")
                                .append("assignedIdentity=").append(assignedIdentity != null ? assignedIdentity.name() : "none").append("\n")
                                .append("role=").append(role != null ? role.getName() : "none").append("\n")
                                .append("randomizeName=").append(role != null && role.isRandomizeName()).append("\n")
                                .append("randomizeSkin=").append(role != null && role.isRandomizeSkin()).append("\n")
                                .append("displayName=").append(IdentityService.getDisplayName(player)).append("\n")
                                .append("customName=").append(currentCustomName).append("\n")
                                .append("profileName=").append(player.getGameProfile().name()).append("\n")
                                .append("hasTextures=").append(!player.getGameProfile().properties().get("textures").isEmpty());

                        sendSafeFeedback(context.getSource(), sb.toString(), false);
                        return 1;
                    })))
                    .then(literal("release").then(argument("player", EntityArgumentType.player()).executes(context -> {
                        ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
                        HoldingService.removePlayer(target);
                        context.getSource().sendFeedback(() -> Text.literal("Released " + target.getName().getString() + " from containment."), true);
                        return 1;
                    })))
                    .then(literal("roles").executes(context -> {
                        EventSavedData data = EventManagerMod.getInstance().getData();
                        StringBuilder sb = new StringBuilder("Roles:\n");
                        for (Map.Entry<String, RoleDefinition> entry : data.roles.entrySet()) {
                            String name = entry.getKey();
                            RoleDefinition role = entry.getValue();
                            sb.append("- ").append(name)
                              .append(" bypassFlow=").append(role.isBypassEventFlow())
                              .append(" hasSpawn=").append(role.hasSpawn())
                              .append("\n");
                        }
                        sendSafeFeedback(context.getSource(), sb.toString(), false);
                        return 1;
                    }))
                )
                .then(literal("bypass")
                    .then(literal("add").then(argument("player", EntityArgumentType.player()).executes(context -> {
                        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
                        EventSavedData data = EventManagerMod.getInstance().getData();
                        boolean added = data.bypassPlayers.add(player.getUuid());
                        EventSessionService.evaluatePlayer(player, data);
                        EventManagerMod.getInstance().saveData();
                        if (!added) {
                            context.getSource().sendError(Text.literal(player.getName().getString() + " is already in the bypass list."));
                            return 0;
                        }
                        context.getSource().sendFeedback(() -> Text.literal("Added " + player.getName().getString() + " to bypass list."), true);
                        return 1;
                    })))
                    .then(literal("remove").then(argument("player", EntityArgumentType.player()).executes(context -> {
                        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
                        EventSavedData data = EventManagerMod.getInstance().getData();
                        boolean removed = data.bypassPlayers.remove(player.getUuid());
                        EventSessionService.evaluatePlayer(player, data);
                        EventSessionService.pruneContainmentForCurrentPhase();
                        EventManagerMod.getInstance().saveData();
                        RoleDefinition role = EventSessionService.getPlayerRole(player.getUuid());
                        boolean bypassFlow = role != null && role.isBypassEventFlow();
                        if (!removed) {
                            context.getSource().sendError(Text.literal(player.getName().getString() + " was not in the bypass list."));
                            return 0;
                        }
                        String message = "Removed " + player.getName().getString() + " from bypass list."
                                + (bypassFlow ? " They still have a role with bypassEventFlow=true." : "");
                        context.getSource().sendFeedback(() -> Text.literal(message), true);
                        return 1;
                    })))
                    .then(literal("list").executes(context -> {
                        sendSafeFeedback(context.getSource(), "Bypass players: " + EventManagerMod.getInstance().getData().bypassPlayers, false);
                        return 1;
                    }))
                )
                .then(literal("autojoin")
                    .then(literal("add").then(argument("player", EntityArgumentType.player()).executes(context -> {
                        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
                        EventSavedData data = EventManagerMod.getInstance().getData();
                        boolean added = data.autoJoinPlayers.add(player.getUuid());
                        EventSessionService.evaluatePlayer(player, data);
                        EventManagerMod.getInstance().saveData();
                        if (!added) {
                            context.getSource().sendError(Text.literal(player.getName().getString() + " is already in the autojoin list."));
                            return 0;
                        }
                        context.getSource().sendFeedback(() -> Text.literal("Added " + player.getName().getString() + " to autojoin list."), true);
                        return 1;
                    })))
                    .then(literal("remove").then(argument("player", EntityArgumentType.player()).executes(context -> {
                        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
                        EventSavedData data = EventManagerMod.getInstance().getData();
                        boolean removed = data.autoJoinPlayers.remove(player.getUuid());
                        EventSessionService.evaluatePlayer(player, data);
                        EventManagerMod.getInstance().saveData();
                        if (!removed) {
                            context.getSource().sendError(Text.literal(player.getName().getString() + " was not in the autojoin list."));
                            return 0;
                        }
                        context.getSource().sendFeedback(() -> Text.literal("Removed " + player.getName().getString() + " from autojoin list."), true);
                        return 1;
                    })))
                    .then(literal("list").executes(context -> {
                        sendSafeFeedback(context.getSource(), "Autojoin players: " + EventManagerMod.getInstance().getData().autoJoinPlayers, false);
                        return 1;
                    }))
                )
                .then(literal("identities")
                    .then(literal("list").executes(context -> {
                        List<IdentityDefinition> identities = IdentityService.getIdentityPoolSnapshot();
                        StringBuilder sb = new StringBuilder("Identity pool (").append(identities.size()).append(") from ")
                                .append(IdentityPoolService.getIdentityPath())
                                .append(":\n");
                        int shown = Math.min(IDENTITY_LIST_PREVIEW_LIMIT, identities.size());
                        for (int i = 0; i < shown; i++) {
                            IdentityDefinition identity = identities.get(i);
                            sb.append("- ").append(identity.getName())
                              .append(" hasSkin=").append(identity.isValid())
                              .append("\n");
                        }
                        if (identities.size() > shown) {
                            sb.append("... and ").append(identities.size() - shown)
                              .append(" more. Check the files in ").append(IdentityPoolService.getIdentityPath())
                              .append(" for the full list.");
                        }
                        sendSafeFeedback(context.getSource(), sb.toString(), false);
                        return 1;
                    }))
                    .then(literal("import").then(argument("url", StringArgumentType.greedyString()).executes(context -> {
                        String url = StringArgumentType.getString(context, "url");
                        IdentityPoolService.ImportResult result = IdentityPoolService.importFromUrl(url);
                        if (!result.success()) {
                            context.getSource().sendError(Text.literal("Failed to import identities from " + url + ": " + result.error()));
                            return 0;
                        }

                        IdentityPoolService.LoadResult loadResult = result.loadResult();
                        context.getSource().sendFeedback(() -> Text.literal(
                                "Imported identities to " + loadResult.path()
                                        + ": importedNames=" + result.importedNameCount()
                                        + ", importedSkins=" + result.importedSkinCount()
                                        + ", loadedNames=" + loadResult.loadedNameCount()
                                        + ", loadedSkins=" + loadResult.loadedSkinCount()
                                        + ", invalidNames=" + loadResult.invalidNameCount()
                                        + ", invalidSkins=" + loadResult.invalidSkinCount()
                        ), true);
                        return 1;
                    })))
                    .then(literal("reload").executes(context -> {
                        IdentityPoolService.LoadResult result = IdentityService.reloadPool();
                        context.getSource().sendFeedback(() -> Text.literal(
                                "Reloaded identities from " + result.path()
                                        + ": loadedNames=" + result.loadedNameCount()
                                        + ", loadedSkins=" + result.loadedSkinCount()
                                        + ", invalidNames=" + result.invalidNameCount()
                                        + ", invalidSkins=" + result.invalidSkinCount()
                                        + (result.createdDefaultFile() ? ", createdDefaultFile=true" : "")
                        ), true);
                        return 1;
                    }))
                )
                .then(literal("randomize")
                    .then(argument("player", EntityArgumentType.player()).executes(context -> rerollByRole(context.getSource(), EntityArgumentType.getPlayer(context, "player"))))
                    .then(argument("player", EntityArgumentType.player()).then(argument("type", StringArgumentType.word()).executes(context ->
                            rerollByType(context.getSource(), EntityArgumentType.getPlayer(context, "player"), StringArgumentType.getString(context, "type"))
                    )))
                )
                .then(literal("config")
                    .then(literal("adminAutoJoin").then(argument("value", BoolArgumentType.bool()).executes(context -> {
                        boolean value = BoolArgumentType.getBool(context, "value");
                        EventManagerMod.getInstance().getData().adminAutoJoin = value;
                        EventManagerMod.getInstance().saveData();
                        context.getSource().sendFeedback(() -> Text.literal("adminAutoJoin set to " + value), true);
                        return 1;
                    })))
                    .then(literal("defaultRole").then(argument("role", StringArgumentType.word()).executes(context -> {
                        String role = StringArgumentType.getString(context, "role");
                        if (!EventManagerMod.getInstance().getData().roles.containsKey(role)) {
                            context.getSource().sendError(Text.literal("Role not found: " + role));
                            return 0;
                        }
                        EventManagerMod.getInstance().getData().defaultRole = role;
                        EventManagerMod.getInstance().saveData();
                        context.getSource().sendFeedback(() -> Text.literal("Default role set to " + role), true);
                        return 1;
                    })))
                )
                .then(literal("roles")
                    .then(literal("create").then(argument("name", StringArgumentType.word()).executes(context -> {
                        String name = StringArgumentType.getString(context, "name");
                        EventManagerMod.getInstance().getData().roles.put(name, new RoleDefinition(name));
                        EventManagerMod.getInstance().saveData();
                        context.getSource().sendFeedback(() -> Text.literal("Created role: " + name), true);
                        return 1;
                    })))
                    .then(literal("list").executes(context -> {
                        sendSafeFeedback(context.getSource(), "Roles: " + EventManagerMod.getInstance().getData().roles.keySet(), false);
                        return 1;
                    }))
                    .then(literal("info").then(argument("role", StringArgumentType.word()).executes(context -> {
                        String roleName = StringArgumentType.getString(context, "role");
                        RoleDefinition role = EventManagerMod.getInstance().getData().roles.get(roleName);
                        if (role == null) {
                            context.getSource().sendError(Text.literal("Role not found: " + roleName));
                            return 0;
                        }
                        StringBuilder sb = new StringBuilder("Role info for ").append(roleName).append(":\n");
                        sb.append("breakBlocks=").append(role.getRules().breakBlocks()).append("\n");
                        sb.append("placeBlocks=").append(role.getRules().placeBlocks()).append("\n");
                        sb.append("useItems=").append(role.getRules().useItems()).append("\n");
                        sb.append("openBlocks=").append(role.getRules().openBlocks()).append("\n");
                        sb.append("useContainers=").append(role.getRules().useContainers()).append("\n");
                        sb.append("interactEntities=").append(role.getRules().interactEntities()).append("\n");
                        sb.append("pvpEnabled=").append(role.getRules().pvpEnabled()).append("\n");
                        sb.append("pickupItems=").append(role.getRules().pickupItems()).append("\n");
                        sb.append("dropItems=").append(role.getRules().dropItems()).append("\n");
                        sb.append("bypassEventFlow=").append(role.isBypassEventFlow()).append("\n");
                        sb.append("randomizeName=").append(role.isRandomizeName()).append("\n");
                        sb.append("randomizeSkin=").append(role.isRandomizeSkin()).append("\n");
                        sb.append("hasSpawn=").append(role.hasSpawn()).append("\n");
                        if (role.hasSpawn()) {
                            sb.append("spawnDimension=").append(role.getSpawnDimension()).append("\n");
                            sb.append("spawnPos=")
                              .append(role.getSpawnX()).append(", ")
                              .append(role.getSpawnY()).append(", ")
                              .append(role.getSpawnZ()).append("\n");
                            sb.append("spawnRotation=").append(role.getSpawnYaw()).append(", ").append(role.getSpawnPitch()).append("\n");
                        }
                        sendSafeFeedback(context.getSource(), sb.toString(), false);
                        return 1;
                    })))
                    .then(literal("assign").then(argument("player", EntityArgumentType.player()).then(argument("role", StringArgumentType.word()).executes(context -> {
                        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
                        String roleName = StringArgumentType.getString(context, "role");
                        RoleDefinition role = EventManagerMod.getInstance().getData().roles.get(roleName);
                        if (role == null) {
                            context.getSource().sendError(Text.literal("Role not found: " + roleName));
                            return 0;
                        }

                        EventSessionService.assignRole(player, roleName);
                        StringBuilder sb = new StringBuilder("Assigned role ")
                                .append(roleName)
                                .append(" to ")
                                .append(player.getName().getString())
                                .append(". bypassEventFlow=")
                                .append(role.isBypassEventFlow())
                                .append(", spawnConfigured=")
                                .append(role.hasSpawn());
                        if (EventSessionService.getPhase() == EventSessionService.Phase.RUNNING
                                && !role.isBypassEventFlow()
                                && !role.hasSpawn()) {
                            sb.append(". Warning: RUNNING released the player without teleport because the role has no spawn.");
                        }
                        context.getSource().sendFeedback(() -> Text.literal(sb.toString()), true);
                        return 1;
                    }))))
                    .then(literal("applykit").then(argument("role", StringArgumentType.word()).executes(context -> {
                        String roleName = StringArgumentType.getString(context, "role");
                        ServerPlayerEntity player = context.getSource().getPlayer();
                        if (player == null) {
                            context.getSource().sendError(Text.literal("Only players can use this command."));
                            return 0;
                        }
                        if (!EventSessionService.applyRoleKit(player, roleName)) {
                            context.getSource().sendError(Text.literal("Role not found: " + roleName));
                            return 0;
                        }
                        context.getSource().sendFeedback(() -> Text.literal("Applied kit from role " + roleName + " to " + player.getName().getString() + "."), true);
                        return 1;
                    })))
                    .then(literal("unassign").then(argument("player", EntityArgumentType.player()).executes(context -> {
                        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
                        EventSessionService.unassignRole(player);
                        String currentRole = EventSessionService.getParticipantRolesSnapshot().get(player.getUuid());
                        context.getSource().sendFeedback(() -> Text.literal(
                                "Unassigned " + player.getName().getString() + ". Current role=" + (currentRole == null ? "none" : currentRole)
                        ), true);
                        return 1;
                    })))
                    .then(literal("get").then(argument("player", EntityArgumentType.player()).executes(context -> {
                        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
                        String roleName = EventSessionService.getParticipantRolesSnapshot().get(player.getUuid());
                        if (roleName == null || "unassigned".equals(roleName)) {
                            context.getSource().sendFeedback(() -> Text.literal(
                                    "Role for " + player.getName().getString() + ": none, bypassEventFlow=n/a, spawnConfigured=n/a"
                            ), false);
                            return 1;
                        }

                        RoleDefinition role = EventSessionService.getPlayerRole(player.getUuid());
                        boolean bypassFlow = role != null && role.isBypassEventFlow();
                        boolean hasSpawn = role != null && role.hasSpawn();
                        context.getSource().sendFeedback(() -> Text.literal(
                                "Role for " + player.getName().getString()
                                        + ": " + roleName
                                        + ", bypassEventFlow=" + bypassFlow
                                        + ", spawnConfigured=" + hasSpawn
                        ), false);
                        return 1;
                    })))
                    .then(literal("configure").then(argument("name", StringArgumentType.word()).executes(context -> {
                        String name = StringArgumentType.getString(context, "name");
                        RoleDefinition role = EventManagerMod.getInstance().getData().roles.get(name);
                        if (role == null) {
                            context.getSource().sendError(Text.literal("Role not found: " + name));
                            return 0;
                        }
                        ServerPlayerEntity player = context.getSource().getPlayer();
                        if (player != null) {
                            RoleConfigGui.open(player, role);
                        }
                        return 1;
                    })))
                    .then(literal("setspawn").then(argument("role", StringArgumentType.word()).executes(context -> {
                        String roleName = StringArgumentType.getString(context, "role");
                        RoleDefinition role = EventManagerMod.getInstance().getData().roles.get(roleName);
                        if (role == null) {
                            context.getSource().sendError(Text.literal("Role not found: " + roleName));
                            return 0;
                        }
                        ServerPlayerEntity player = context.getSource().getPlayer();
                        if (player != null) {
                            role.setSpawn(player.getEntityWorld().getRegistryKey().getValue(), player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
                            EventManagerMod.getInstance().saveData();
                            context.getSource().sendFeedback(() -> Text.literal("Spawn set for role: " + roleName), true);
                        }
                        return 1;
                    })))
                    .then(literal("clearspawn").then(argument("role", StringArgumentType.word()).executes(context -> {
                        String roleName = StringArgumentType.getString(context, "role");
                        RoleDefinition role = EventManagerMod.getInstance().getData().roles.get(roleName);
                        if (role == null) {
                            context.getSource().sendError(Text.literal("Role not found: " + roleName));
                            return 0;
                        }
                        role.clearSpawn();
                        EventManagerMod.getInstance().saveData();
                        context.getSource().sendFeedback(() -> Text.literal("Spawn cleared for role: " + roleName), true);
                        return 1;
                    })))
                    .then(literal("setbypassflow").then(argument("role", StringArgumentType.word()).then(argument("bypass", BoolArgumentType.bool()).executes(context -> {
                        String roleName = StringArgumentType.getString(context, "role");
                        boolean bypass = BoolArgumentType.getBool(context, "bypass");
                        RoleDefinition role = EventManagerMod.getInstance().getData().roles.get(roleName);
                        if (role == null) {
                            context.getSource().sendError(Text.literal("Role not found: " + roleName));
                            return 0;
                        }
                        role.setBypassEventFlow(bypass);
                        reapplyPlayersWithRole(context.getSource(), roleName);
                        EventManagerMod.getInstance().saveData();
                        context.getSource().sendFeedback(() -> Text.literal("Bypass flow set to " + bypass + " for role: " + roleName), true);
                        return 1;
                    }))))
                )
            );
        });
    }

    private static boolean isSingleplayerHost(ServerCommandSource source) {
        if (source == null || source.getServer() == null) {
            return false;
        }

        if (!source.getServer().isSingleplayer()) {
            return false;
        }

        ServerPlayerEntity player = source.getPlayer();
        return player != null && source.getServer().isHost(player.getPlayerConfigEntry());
    }

    private static String describePlayer(ServerCommandSource source, UUID uuid) {
        ServerPlayerEntity player = source.getServer().getPlayerManager().getPlayer(uuid);
        if (player != null) {
            return player.getName().getString() + " (" + uuid + ")";
        }
        return "offline (" + uuid + ")";
    }

    private static int rerollByRole(ServerCommandSource source, ServerPlayerEntity player) {
        RoleDefinition role = EventSessionService.getPlayerRole(player.getUuid());
        if (role == null) {
            source.sendError(Text.literal("Player has no assigned role with randomization settings."));
            return 0;
        }

        boolean rerollName = role.isRandomizeName();
        boolean rerollSkin = role.isRandomizeSkin();
        if (!rerollName && !rerollSkin) {
            source.sendError(Text.literal("Role " + role.getName() + " does not allow name or skin randomization."));
            return 0;
        }

        if (!IdentityService.rerollIdentity(player, role, rerollName, rerollSkin)) {
            source.sendError(Text.literal("Failed to reroll identity for " + player.getName().getString() + "."));
            return 0;
        }

        source.sendFeedback(() -> Text.literal(
                "Rerolled " + player.getName().getString() + " using role " + role.getName()
                        + " (name=" + rerollName + ", skin=" + rerollSkin + ")."
        ), true);
        return 1;
    }

    private static int rerollByType(ServerCommandSource source, ServerPlayerEntity player, String type) {
        RoleDefinition role = EventSessionService.getPlayerRole(player.getUuid());
        if (role == null) {
            source.sendError(Text.literal("Player has no assigned role with randomization settings."));
            return 0;
        }

        boolean rerollName;
        boolean rerollSkin;
        if ("name".equalsIgnoreCase(type)) {
            if (!role.isRandomizeName()) {
                source.sendError(Text.literal("Role " + role.getName() + " does not allow name randomization."));
                return 0;
            }
            rerollName = true;
            rerollSkin = false;
        } else if ("skin".equalsIgnoreCase(type)) {
            if (!role.isRandomizeSkin()) {
                source.sendError(Text.literal("Role " + role.getName() + " does not allow skin randomization."));
                return 0;
            }
            rerollName = false;
            rerollSkin = true;
        } else {
            source.sendError(Text.literal("Randomize type must be 'name' or 'skin'."));
            return 0;
        }

        if (!IdentityService.rerollIdentity(player, role, rerollName, rerollSkin)) {
            source.sendError(Text.literal("Failed to reroll " + type + " for " + player.getName().getString() + "."));
            return 0;
        }

        source.sendFeedback(() -> Text.literal(
                "Rerolled " + type + " for " + player.getName().getString() + " using role " + role.getName() + "."
        ), true);
        return 1;
    }

    private static String describeContainmentReason(UUID uuid, String roleName, boolean isParticipant, boolean isBypassListed, RoleDefinition roleDef) {
        ContainmentReason storedReason = HoldingService.getContainmentReason(uuid);
        if (isBypassListed) {
            return "stale containment: bypass-listed player";
        }
        if (!isParticipant) {
            return "stale containment: non-participant";
        }
        if (roleDef != null && roleDef.isBypassEventFlow()) {
            return "stale containment: bypass-role participant";
        }
        if (EventSessionService.getPhase() == EventSessionService.Phase.RUNNING) {
            if (roleDef != null && !roleDef.hasSpawn()) {
                return "RUNNING but missing role spawn (player should be released, not contained)";
            }
            if ("unassigned".equals(roleName)) {
                return "stale containment: unassigned participant during RUNNING";
            }
            return "stale containment during RUNNING";
        }
        if (storedReason != null) {
            return storedReason.getLabel();
        }
        return "stale containment";
    }

    private static void reapplyPlayersWithRole(ServerCommandSource source, String roleName) {
        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            String currentRole = EventSessionService.getParticipantRolesSnapshot().get(player.getUuid());
            if (roleName.equals(currentRole)) {
                EventSessionService.evaluatePlayer(player, EventManagerMod.getInstance().getData());
            }
        }
    }

    private static void sendSafeFeedback(ServerCommandSource source, String message, boolean broadcastToOps) {
        source.sendFeedback(() -> Text.literal(capMessage(message)), broadcastToOps);
    }

    private static String capMessage(String message) {
        if (message == null) {
            return "";
        }
        if (message.length() <= MAX_CHAT_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_CHAT_MESSAGE_LENGTH) + "\n... output truncated ...";
    }
}
