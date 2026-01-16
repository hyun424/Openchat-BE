package io.hyun424.openchat.infra.kafka.config;


import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@Profile("kafka")
public class KafkaConsumerConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {

        FixedBackOff backOff = new FixedBackOff(1000L, 3L);

        return new DefaultErrorHandler(
                (record, ex) -> {
                    // 재시도 후 최종 실패
                    // 여기서는 죽이지 않고 로그만 남김
                    System.err.println(
                            "[KAFKA CONSUME FAIL] topic=" + record.topic()
                                    + " partition=" + record.partition()
                                    + " offset=" + record.offset()
                                    + " error=" + ex.getMessage()
                    );
                    // TODO: DLQ 연동은 추후 KafkaProducer 분리 후 추가
                },
                backOff
        );
    }
}
