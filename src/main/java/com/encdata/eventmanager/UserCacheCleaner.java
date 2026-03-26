package com.encdata.eventmanager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

final class UserCacheCleaner {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private UserCacheCleaner() {
    }

    static void removePlayerEntry(ServerPlayerEntity player) {
        Path userCachePath = player.getCommandSource().getServer().getRunDirectory().resolve("usercache.json");
        if (!Files.exists(userCachePath)) {
            return;
        }

        UUID uuid = player.getUuid();
        String currentName = player.getGameProfile().name();

        try (Reader reader = Files.newBufferedReader(userCachePath)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonArray()) {
                return;
            }

            JsonArray filtered = new JsonArray();
            boolean changed = false;
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    filtered.add(element);
                    continue;
                }

                String cachedUuid = element.getAsJsonObject().has("uuid")
                        ? element.getAsJsonObject().get("uuid").getAsString()
                        : null;
                String cachedName = element.getAsJsonObject().has("name")
                        ? element.getAsJsonObject().get("name").getAsString()
                        : null;

                boolean matches = uuid.toString().equalsIgnoreCase(cachedUuid)
                        || (currentName != null && currentName.equalsIgnoreCase(cachedName));
                if (matches) {
                    changed = true;
                    continue;
                }

                filtered.add(element);
            }

            if (!changed) {
                return;
            }

            try (Writer writer = Files.newBufferedWriter(userCachePath)) {
                GSON.toJson(filtered, writer);
            }
        } catch (IOException e) {
            EventManagerMod.logWarn("Failed to clean usercache entry for {}", player.getName().getString(), e);
        }
    }
}
