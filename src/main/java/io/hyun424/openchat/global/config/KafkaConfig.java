package io.hyun424.openchat.global.config;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;

@Configuration
@Profile("kafka")
public class KafkaConfig {

    @Bean
    public ProducerFactory<String, ChatMessageDto> producerFactory(
            KafkaProperties properties
    ) {
        Map<String, Object> config = properties.buildProducerProperties();
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, ChatMessageDto> kafkaTemplate(
            ProducerFactory<String, ChatMessageDto> producerFactory
    ) {
        return new KafkaTemplate<>(producerFactory);
    }
}
