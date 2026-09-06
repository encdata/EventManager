package com.encdata.eventmanager;

import com.encdata.eventmanager.data.EventSavedData;
import com.encdata.eventmanager.session.EventSessionService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EventManagerCoreTest {
    @Test
    void runtimeStateSurvivesRestartAndInvalidPhaseFallsBackClosed() {
        UUID player = UUID.randomUUID();
        EventSavedData saved = new EventSavedData();
        saved.phase = "RUNNING";
        saved.participantRoles = new HashMap<>();
        saved.participantRoles.put(player, "Default");

        EventSessionService.restoreRuntimeState(saved);

        assertEquals(EventSessionService.Phase.RUNNING, EventSessionService.getPhase());
        assertEquals("Default", EventSessionService.getParticipantRolesSnapshot().get(player));

        saved.phase = "not-a-phase";
        EventSessionService.restoreRuntimeState(saved);
        assertEquals(EventSessionService.Phase.CLOSED, EventSessionService.getPhase());
    }

}
