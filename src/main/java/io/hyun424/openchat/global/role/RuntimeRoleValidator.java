package io.hyun424.openchat.global.role;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RuntimeRoleValidator {

    public RuntimeRoleValidator(@Value("${app.role:combined}") String role) {
        RuntimeRole.parse(role);
    }
}
