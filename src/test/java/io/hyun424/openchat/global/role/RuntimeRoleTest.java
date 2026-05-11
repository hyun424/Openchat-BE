package io.hyun424.openchat.global.role;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeRoleTest {

    @Test
    void parsesKnownRolesWithNormalizedInput() {
        assertTrue(RuntimeRole.parse(" combined ").hasCapability(RuntimeCapability.API));
        assertTrue(RuntimeRole.parse("COMBINED").hasCapability(RuntimeCapability.REALTIME));
        assertTrue(RuntimeRole.parse("api").hasCapability(RuntimeCapability.API));
        assertFalse(RuntimeRole.parse("api").hasCapability(RuntimeCapability.REALTIME));
        assertTrue(RuntimeRole.parse("realtime").hasCapability(RuntimeCapability.REALTIME));
        assertTrue(RuntimeRole.parse("ai-worker").hasCapability(RuntimeCapability.AI_WORKER));
    }

    @Test
    void matchesAnyRequestedCapability() {
        assertTrue(RuntimeRole.API.hasAnyCapability(new RuntimeCapability[]{
                RuntimeCapability.REALTIME,
                RuntimeCapability.API
        }));
        assertFalse(RuntimeRole.AI_WORKER.hasAnyCapability(new RuntimeCapability[]{
                RuntimeCapability.REALTIME,
                RuntimeCapability.API
        }));
    }

    @Test
    void rejectsUnknownRole() {
        assertThrows(IllegalArgumentException.class, () -> RuntimeRole.parse("worker"));
    }
}
