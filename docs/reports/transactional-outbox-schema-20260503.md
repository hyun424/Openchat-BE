# Transactional Outbox 운영 DDL 초안

이번 실험 브랜치는 `dev/loadtest`에서 `ddl-auto=update`로 `outbox_event` 테이블을 만들 수 있다. 기본 profile은 `ddl-auto=validate`이므로 운영/검증 환경에서는 아래와 같은 DDL을 별도로 적용해야 한다.

```sql
CREATE TABLE outbox_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id VARCHAR(36) NOT NULL,
  event_type VARCHAR(80) NOT NULL,
  aggregate_type VARCHAR(80) NOT NULL,
  aggregate_id BIGINT NOT NULL,
  room_id BIGINT NOT NULL,
  message_id VARCHAR(36) NOT NULL,
  payload_json TEXT NOT NULL,
  status VARCHAR(20) NOT NULL,
  attempt_count INT NOT NULL,
  next_retry_at BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  published_at BIGINT NULL,
  last_error TEXT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_outbox_event_id (event_id),
  KEY idx_outbox_status_retry_id (status, next_retry_at, id),
  KEY idx_outbox_room_id (room_id, id)
);
```

Ack 의미는 `chat_message`와 `outbox_event`가 같은 DB transaction에서 commit된 뒤의 저장 성공이다. Redis publish, room lastMessage, hotchat 갱신 성공은 ack 의미에 포함하지 않는다.
