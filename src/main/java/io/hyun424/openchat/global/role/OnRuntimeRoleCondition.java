package io.hyun424.openchat.global.role;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

class OnRuntimeRoleCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnRuntimeRole.class.getName());
        if (attributes == null) {
            return false;
        }
        RuntimeCapability[] capabilities = (RuntimeCapability[]) attributes.get("capabilities");
        String roleValue = context.getEnvironment().getProperty("app.role", "combined");
        RuntimeRole role = RuntimeRole.parse(roleValue);
        return role.hasAnyCapability(capabilities);
    }
}
