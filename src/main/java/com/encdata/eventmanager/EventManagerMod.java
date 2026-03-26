package com.encdata.eventmanager;

import com.encdata.eventmanager.command.EventCommand;
import com.encdata.eventmanager.data.EventSavedData;
import com.encdata.eventmanager.identity.IdentityPoolService;
import com.encdata.eventmanager.identity.IdentityService;
import com.encdata.eventmanager.queue.HoldingService;
import com.encdata.eventmanager.rules.RuleService;
import com.encdata.eventmanager.session.EventSessionService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventManagerMod implements ModInitializer {
    public static final String MOD_ID = "eventmanager";
    public static final Identifier VOID_PRISON_DIMENSION = Identifier.of(MOD_ID, "void_prison");
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static volatile boolean loggingEnabled = true;
    private static EventManagerMod instance;
    private EventSavedData data;
    private static MinecraftServer serverInstance;

    @Override
    public void onInitialize() {
        instance = this;
        reloadConfig();
        
        IdentityService.init();
        HoldingService.init();
        RuleService.init();
        EventCommand.init();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> serverInstance = server);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> IdentityPoolService.startBackgroundRefresh());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            IdentityPoolService.stopBackgroundRefresh();
            if (serverInstance == server) {
                serverInstance = null;
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            UserCacheCleaner.removePlayerEntry(handler.getPlayer());
            // Always evaluate on join; CLOSED still means containment is active.
            EventSessionService.evaluatePlayer(handler.getPlayer(), data);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                UserCacheCleaner.removePlayerEntry(handler.getPlayer()));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> EventSessionService.handlePlayerRespawn(newPlayer));

        logInfo("Event Manager initialized.");
    }

    public static EventManagerMod getInstance() { return instance; }
    public static MinecraftServer getServerInstance() { return serverInstance; }
    public static void setServerInstance(MinecraftServer server) { serverInstance = server; }
    public EventSavedData getData() { return data; }
    public void saveData() { data.save(); }

    public EventSavedData reloadConfig() {
        EventSavedData reloaded = EventSavedData.load();
        boolean changed = EventSessionService.ensureDefaultConfiguration(reloaded);
        if (reloaded.holdingDimension == null
                || Identifier.of("minecraft", "overworld").equals(reloaded.holdingDimension)
                || Identifier.of("minecraft", "the_end").equals(reloaded.holdingDimension)
                || Identifier.of(MOD_ID, "void_prison_empty").equals(reloaded.holdingDimension)) {
            reloaded.holdingDimension = VOID_PRISON_DIMENSION;
            reloaded.holdingX = 0.0;
            reloaded.holdingY = 100.0;
            reloaded.holdingZ = 0.0;
            changed = true;
        }

        this.data = reloaded;
        loggingEnabled = this.data.enableLogging;
        IdentityService.reloadPool();
        if (changed) {
            this.saveData();
        }
        return this.data;
    }

    public static boolean isLoggingEnabled() {
        return loggingEnabled;
    }

    public static void logInfo(String message, Object... args) {
        if (loggingEnabled) {
            LOGGER.info(message, args);
        }
    }

    public static void logWarn(String message, Object... args) {
        if (loggingEnabled) {
            LOGGER.warn(message, args);
        }
    }

    public static void logError(String message, Object... args) {
        if (loggingEnabled) {
            LOGGER.error(message, args);
        }
    }
}
