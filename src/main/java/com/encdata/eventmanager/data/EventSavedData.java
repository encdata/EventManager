package com.encdata.eventmanager.data;

import com.encdata.eventmanager.EventManagerMod;
import com.encdata.eventmanager.role.RoleDefinition;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class EventSavedData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("eventmanager.json");

    public boolean adminAutoJoin = false;
    public boolean enableLogging = true;
    public String defaultRole = null;
    
    // Holding configuration
    public double holdingX = 0.0;
    public double holdingY = 100.0;
    public double holdingZ = 0.0;
    public Identifier holdingDimension = EventManagerMod.VOID_PRISON_DIMENSION;

    public Set<UUID> bypassPlayers = new HashSet<>();
    public Set<UUID> autoJoinPlayers = new HashSet<>();
    public Map<String, RoleDefinition> roles = new HashMap<>();

    public static EventSavedData load() {
        if (!Files.exists(CONFIG_PATH)) {
            return new EventSavedData();
        }
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            EventSavedData data = GSON.fromJson(reader, EventSavedData.class);
            return data != null ? data : new EventSavedData();
        } catch (IOException e) {
            EventManagerMod.logError("Failed to load config from {}", CONFIG_PATH, e);
            return new EventSavedData();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            EventManagerMod.logError("Failed to save config to {}", CONFIG_PATH, e);
        }
    }
}
