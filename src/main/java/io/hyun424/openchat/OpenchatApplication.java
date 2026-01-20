package io.hyun424.openchat;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.Arrays;

@SpringBootApplication
public class OpenchatApplication {

	public static void main(String[] args) {
		SpringApplication.run(OpenchatApplication.class, args);
	}

    @Bean
    public ApplicationRunner runner(Environment env) {
        return args -> {
            System.out.println("===== DEBUG PROPERTIES =====");
            System.out.println("app.instance-id = " + env.getProperty("app.instance-id"));
            System.out.println("server.port     = " + env.getProperty("server.port"));
            System.out.println("active profiles = " + Arrays.toString(env.getActiveProfiles()));
            System.out.println("============================");
        };
    }
}
