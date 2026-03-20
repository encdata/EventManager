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
    private static EventManagerMod instance;
    private EventSavedData data;
    private static MinecraftServer serverInstance;

    @Override
    public void onInitialize() {
        instance = this;
        this.data = EventSavedData.load();
        boolean changed = EventSessionService.ensureDefaultConfiguration(this.data);
        if (this.data.holdingDimension == null
                || Identifier.of("minecraft", "overworld").equals(this.data.holdingDimension)
                || Identifier.of("minecraft", "the_end").equals(this.data.holdingDimension)
                || Identifier.of(MOD_ID, "void_prison_empty").equals(this.data.holdingDimension)) {
            this.data.holdingDimension = VOID_PRISON_DIMENSION;
            this.data.holdingX = 0.0;
            this.data.holdingY = 100.0;
            this.data.holdingZ = 0.0;
            changed = true;
        }
        if (changed) {
            this.saveData();
        }
        
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
            // Always evaluate on join; CLOSED still means containment is active.
            EventSessionService.evaluatePlayer(handler.getPlayer(), data);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> EventSessionService.handlePlayerRespawn(newPlayer));

        LOGGER.info("Event Manager initialized.");
    }

    public static EventManagerMod getInstance() { return instance; }
    public static MinecraftServer getServerInstance() { return serverInstance; }
    public EventSavedData getData() { return data; }
    public void saveData() { data.save(); }
}
