package io.hyun424.openchat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Arrays;

@Slf4j
@SpringBootApplication
@EnableScheduling
public class OpenchatApplication {

	public static void main(String[] args) {
		SpringApplication.run(OpenchatApplication.class, args);
	}

    @Bean
    public ApplicationRunner runner(Environment env) {
        return args -> {
            log.info("===== APPLICATION PROPERTIES =====");
            log.info("app.instance-id = {}", env.getProperty("app.instance-id"));
            log.info("server.port     = {}", env.getProperty("server.port"));
            log.info("active profiles = {}", Arrays.toString(env.getActiveProfiles()));
            log.info("==================================");
        };
    }
}
