package com.encdata.eventmanager.data;

import com.encdata.eventmanager.EventManagerMod;
import com.encdata.eventmanager.role.RoleDefinition;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.*;

public class EventSavedData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configPath() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        return (configDir != null ? configDir : Path.of("config")).resolve("eventmanager.json");
    }

    public boolean adminAutoJoin = false;
    public boolean enableLogging = true;
    public boolean backgroundIdentityRefresh = true;
    public boolean showQueuePosition = true;
    public String defaultRole = null;
    
    // Holding configuration
    public double holdingX = 0.0;
    public double holdingY = 1.0;
    public double holdingZ = 0.0;
    public Identifier holdingDimension = EventManagerMod.VOID_PRISON_DIMENSION;

    public Set<UUID> bypassPlayers = new HashSet<>();
    public Set<UUID> autoJoinPlayers = new HashSet<>();
    public Map<String, RoleDefinition> roles = new HashMap<>();
    public Map<String, Integer> roleQueueLimits = new LinkedHashMap<>();
    /** Runtime participant assignments survive a server restart. Queue positions are rebuilt on join. */
    public Map<UUID, String> participantRoles = new HashMap<>();
    public String phase = "CLOSED";

    public static EventSavedData load() {
        Path configPath = configPath();
        if (!Files.exists(configPath)) {
            return new EventSavedData();
        }
        try (Reader reader = Files.newBufferedReader(configPath)) {
            EventSavedData data = GSON.fromJson(reader, EventSavedData.class);
            if (data == null) {
                return new EventSavedData();
            }
            if (data.bypassPlayers == null) data.bypassPlayers = new HashSet<>();
            if (data.autoJoinPlayers == null) data.autoJoinPlayers = new HashSet<>();
            if (data.roles == null) data.roles = new HashMap<>();
            if (data.roleQueueLimits == null) data.roleQueueLimits = new LinkedHashMap<>();
            if (data.participantRoles == null) data.participantRoles = new HashMap<>();
            if (data.phase == null || data.phase.isBlank()) data.phase = "CLOSED";
            return data;
        } catch (IOException | JsonParseException | IllegalStateException e) {
            EventManagerMod.logError("Failed to load config from {}", configPath, e);
            return new EventSavedData();
        }
    }

    public void save() {
        Path configPath = configPath();
        Path temporary = null;
        try {
            Files.createDirectories(configPath.getParent());
            temporary = Files.createTempFile(configPath.getParent(), "eventmanager-", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                GSON.toJson(this, writer);
            }
            try {
                Files.move(temporary, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            EventManagerMod.logError("Failed to save config to {}", configPath, e);
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupError) {
                    EventManagerMod.logWarn("Failed to clean temporary config file {}", temporary, cleanupError);
                }
            }
        }
    }
}
