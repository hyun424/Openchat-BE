package io.hyun424.openchat.global.role;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(OnRuntimeRoleCondition.class)
public @interface ConditionalOnRuntimeRole {
    RuntimeCapability[] capabilities();
}
