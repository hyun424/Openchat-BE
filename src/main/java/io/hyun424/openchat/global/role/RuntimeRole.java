package io.hyun424.openchat.global.role;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

public enum RuntimeRole {
    COMBINED("combined", Set.of(RuntimeCapability.API, RuntimeCapability.REALTIME)),
    API("api", Set.of(RuntimeCapability.API)),
    REALTIME("realtime", Set.of(RuntimeCapability.REALTIME)),
    AI_WORKER("ai-worker", Set.of(RuntimeCapability.AI_WORKER));

    private final String id;
    private final Set<RuntimeCapability> capabilities;

    RuntimeRole(String id, Set<RuntimeCapability> capabilities) {
        this.id = id;
        this.capabilities = capabilities;
    }

    public String id() {
        return id;
    }

    public boolean hasCapability(RuntimeCapability capability) {
        return capabilities.contains(capability);
    }

    public boolean hasAnyCapability(RuntimeCapability[] requested) {
        if (requested == null || requested.length == 0) {
            return false;
        }
        return Arrays.stream(requested).anyMatch(this::hasCapability);
    }

    public static RuntimeRole parse(String value) {
        String normalized = normalize(value);
        for (RuntimeRole role : values()) {
            if (role.id.equals(normalized)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unsupported app.role: " + value);
    }

    public static String canonical(String value) {
        return parse(value).id();
    }

    private static String normalize(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("app.role must not be blank");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
