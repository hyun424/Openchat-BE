package io.hyun424.openchat.infra.kafka.config;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Slf4j
@Configuration
@Profile("kafka")
public class KafkaConsumerConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, ChatMessageDto> kafkaTemplate) {
        // 실패 메시지를 chat-message.DLT 토픽에 발행
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);

        // Exponential backoff: 1s → 2s → 4s, 최대 3회 재시도
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxAttempts(3);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("[KAFKA RETRY] topic={} partition={} offset={} attempt={} error={}",
                        record.topic(), record.partition(), record.offset(),
                        deliveryAttempt, ex.getMessage())
        );

        return errorHandler;
    }
}
