package io.hyun424.openchat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenchatInvalidRoleApplicationTests {

    @Test
    void invalidRoleFailsFast() {
        assertThrows(RuntimeException.class, () -> new SpringApplicationBuilder(OpenchatApplication.class)
                .properties(
                        "app.role=not-a-role",
                        "app.kafka.enabled=false",
                        "spring.datasource.url=jdbc:h2:mem:openchat-invalid-role-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                        "spring.jpa.hibernate.ddl-auto=create-drop",
                        "spring.kafka.bootstrap-servers=false"
                )
                .run()
                .close());
    }

    @Test
    void blankRoleFailsFast() {
        assertThrows(RuntimeException.class, () -> new SpringApplicationBuilder(OpenchatApplication.class)
                .properties(
                        "app.role= ",
                        "app.kafka.enabled=false",
                        "spring.datasource.url=jdbc:h2:mem:openchat-blank-role-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                        "spring.jpa.hibernate.ddl-auto=create-drop",
                        "spring.kafka.bootstrap-servers=false"
                )
                .run()
                .close());
    }
}
