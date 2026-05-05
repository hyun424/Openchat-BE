# Discord MaxJourney 사례에서 OpenChat에 가져갈 아이디어

> 참고 글: [How Discord Serves 15-Million Users on One Server](https://blog.bytebytego.com/p/how-discord-serves-15-million-users)
> 목적: Discord의 초대형 guild 최적화 사례를 OpenChat의 hot-room fan-out 구조와 부하테스트 개선 방향에 맞게 재해석한다.

## 한 줄 요약

초대형 실시간 채팅방의 확장은 단순히 서버를 더 붙이는 문제가 아니라, 필요 없는 fan-out을 줄이고, hot path에서 큰 순회를 제거하고, 핵심 처리 루프를 느린 I/O와 대량 작업에서 분리하는 문제다.

## Discord 사례 핵심

Discord는 MidJourney guild가 기존 한계였던 100만 명 규모를 빠르게 넘어서자 `MaxJourney`라는 소규모 팀을 만들어 guild 프로세스 병목을 분석했다. Discord의 실시간 백엔드는 Elixir/BEAM 기반이고, 각 guild 프로세스가 연결된 session process로 이벤트를 fan-out하는 구조였다.

규모가 커지면서 문제가 된 부분은 특정 기술 하나가 아니라 다음과 같은 구조적 비용이었다.

- 사용자가 실제로 보고 있지 않은 guild에도 업데이트를 계속 처리하는 비용
- 대형 guild의 전체 멤버 목록을 relay마다 복제하는 메모리 비용
- guild 프로세스가 큰 멤버 목록을 직접 순회하면서 몇 초씩 막히는 문제
- 네트워크 전송과 garbage collection이 핵심 처리 루프에 주는 압력

Discord는 이를 passive session, relay 상태 축소, worker process와 ETS, sender offload, BEAM GC 튜닝으로 해결했다. 중요한 점은 "더 빠른 서버"보다 "필요 없는 작업 제거"와 "blocking 작업 분리"가 먼저였다는 것이다.

## OpenChat에 바로 연결되는 관점

OpenChat도 hot-room 부하테스트에서 같은 종류의 문제가 나타날 수 있다. 한 방에 사용자가 몰리면 메시지 1개가 방의 모든 WebSocket 세션으로 fan-out된다. 모든 사용자가 초당 1개씩 메시지를 보내면 fan-out 총량은 대략 `N * N`으로 증가한다.

현재 OpenChat은 이미 다음 기반을 갖고 있다.

- `ChatFanoutService`: 방별 batch buffer와 hot-room 상태별 batch window
- `RoomSessionRegistry`: 방별 WebSocket session registry와 broadcast lane executor
- `RoomTrafficMonitor`: 방 트래픽과 hot state 판단
- `ChatPipelineMetrics`: fanout, broadcast, send, queue wait, failure reason 계측
- controlled realtime policy: HOT/SUPER_HOT 방에서 live visible message 수 제한

따라서 Discord 사례는 OpenChat에 "새 아키텍처를 통째로 바꾸자"는 의미보다, 현재 구조의 다음 개선 우선순위를 정하는 기준으로 쓰는 것이 맞다.

## 가져갈 아이디어

### 1. Active/Passive 수신 모델

Discord의 passive session은 사용자가 실제로 보고 있지 않은 guild에 대해서는 업데이트 처리와 전송을 줄이는 방식이다.

OpenChat에 적용하면 다음과 같다.

- 사용자가 현재 보고 있는 방만 `active room`으로 본다.
- 백그라운드 방, 탭 비활성 상태, 채팅 목록에서만 보이는 방은 `passive`로 둔다.
- passive session에는 모든 메시지 본문을 실시간 전송하지 않고 unread count, last sequence, last message summary 정도만 보낸다.
- 사용자가 방을 열 때 `lastSeenSequence` 이후 메시지를 HTTP pagination 또는 sync API로 가져오게 한다.

이 방식은 hot-room에서 특히 효과가 크다. 모든 연결을 동일한 수신자로 보지 않고, 실제로 화면에 메시지를 렌더링해야 하는 세션만 full fan-out 대상으로 남길 수 있다.

### 2. Hot path에서 전체 순회 줄이기

대형방에서 가장 위험한 코드는 이벤트 처리 중 전체 세션이나 전체 멤버를 매번 순회하는 코드다. OpenChat의 broadcast는 결국 방 세션 snapshot을 만들고 lane별로 나눠 전송한다. 이 순회 자체는 fan-out 서비스에서는 피할 수 없지만, fan-out 외 경로에서는 최대한 제거해야 한다.

점검 기준은 다음과 같다.

- 입장, 퇴장, 메시지 저장, 방 목록 조회, hot state 계산에서 전체 멤버나 전체 세션을 순회하지 않는가
- 방 인원 수, unread count, last message 같은 값이 요청마다 재계산되지 않는가
- 대형방 migration, 종료, 정리 작업이 WebSocket message 처리 흐름을 막지 않는가

이미 `RoomMetadataUpdateBuffer`처럼 write 부담을 모으는 구조가 있으므로, 같은 원칙을 session/member 관련 통계에도 적용할 수 있다.

### 3. 상태 복제를 줄이기

Discord relay의 문제는 relay마다 전체 member list를 들고 있었다는 점이다. OpenChat에서도 비슷한 위험은 생길 수 있다.

주의할 부분은 다음과 같다.

- 모든 앱 인스턴스가 모든 Redis Pub/Sub 메시지를 받고 있으면, scale-out할수록 중복 수신과 Redis egress가 증가한다.
- 모든 인스턴스가 모든 hot-room 상태를 동일하게 크게 들고 있으면, 방 수가 늘 때 메모리와 동기화 비용이 커진다.
- 부하테스트용 observer/validator처럼 full parse 또는 full validation 역할은 소수로 제한해야 한다.

장기적으로는 모든 인스턴스가 모든 방 메시지를 처리하는 구조보다, roomId 기반 fan-out owner 또는 room shard 구조를 검토할 필요가 있다.

### 4. 핵심 처리 루프는 coordinator로 남기기

Discord는 guild 프로세스가 모든 일을 직접 하지 않도록 worker, relay, sender로 책임을 나눴다. OpenChat도 같은 기준으로 보면 `ChatFanoutService`와 `RoomSessionRegistry`가 너무 많은 일을 직접 하게 만들면 위험하다.

가져갈 방향은 다음과 같다.

- message ingest는 저장과 발행 결정에 집중한다.
- fanout coordinator는 방별 buffering, ordering, backpressure 정책 결정에 집중한다.
- WebSocket send는 lane worker 또는 session queue가 담당한다.
- 방 종료, 대형방 정리, 통계 집계 같은 느린 작업은 별도 worker로 분리한다.

현재 `RoomSessionRegistry`는 broadcast lane executor를 통해 전송 작업을 분리하고 있다. 다음 단계는 session 단위 bounded queue와 slow session drop 정책을 명확히 두는 것이다.

### 5. 백프레셔 격리

실시간 시스템에서 느린 클라이언트 하나가 방 전체 전송을 늦추면 안 된다. Discord의 sender offload도 guild 프로세스가 네트워크 backpressure에 끌려가지 않게 하려는 방향이었다.

OpenChat 기준으로는 다음 정책이 필요하다.

- session별 send buffer 크기 제한
- send time limit 초과 시 session close 또는 degraded mode
- lane queue가 차는 경우 fallback 실행보다 drop/close/omit 정책을 우선 검토
- `closed_before_send`, `send_time_limit`, `buffer_limit`, `io_exception` 같은 reason별 failure metric 유지

현재 `RoomSessionRegistry`는 `ConcurrentWebSocketSessionDecorator`, lane queue, send failure reason metric을 이미 갖고 있다. 남은 과제는 failure reason별로 운영 판단을 정하는 것이다.

### 6. 계측을 먼저 하고 최적화하기

Discord 팀은 stack sampling, event type별 처리 시간, memory sampling으로 병목을 찾았다. OpenChat도 최근 role-aware k6 재측정에서 서버 병목과 k6 관측 병목을 분리했다.

계속 유지할 기준은 다음과 같다.

- `fanout.total`, `fanout.batch.flush_total`
- `ws.broadcast.enqueue.total`, `ws.broadcast.lane.queue_wait`
- `ws.broadcast.lane.worker.total`, `ws.broadcast.lane_done.since_created`
- `ws.send.duration`, `ws.send.failed` reason
- batch size, lane task count, lane queue size
- k6 sender ack, observer visible freshness, validator 정합성 지표 분리

성능 개선은 "무엇을 바꿨다"보다 "어떤 지표가 어떤 이유로 움직였다"까지 설명할 수 있어야 한다.

## OpenChat 개선 우선순위

### 단기

- 현재 hot-room 테스트 결과를 기준으로 active/passive 수신 모델을 문서화한다.
- 클라이언트가 현재 보고 있는 roomId를 서버에 알려주는 control message를 설계한다.
- passive room에는 full message가 아니라 summary/count/sequence만 보내는 프로토콜을 설계한다.
- `ws.send.failed` reason별로 정상 종료 경계와 실제 backpressure 실패를 분리해서 해석한다.

### 중기

- session 단위 bounded outbound queue를 둔다.
- slow session은 방 전체 broadcast를 지연시키지 않고 close 또는 passive degrade한다.
- room별 broadcast lane queue depth와 active session 수를 함께 관측한다.
- hot-room 상태에 따라 full fan-out, batched fan-out, controlled realtime, summary-only fan-out을 전환한다.

### 장기

- roomId 기반 fan-out owner 또는 shard 구조를 검토한다.
- 모든 앱 인스턴스가 모든 Redis Pub/Sub 메시지를 처리하는 구조의 한계를 측정한다.
- hot-room migration, room close, member sync 같은 대형 작업을 별도 worker로 분리한다.
- active/passive 세션 비율을 부하테스트 시나리오에 포함한다.

## 면접/포트폴리오용 정리 문장

Discord의 MidJourney guild 사례를 보면서 대형 실시간 채팅방의 병목은 단순 서버 증설보다 불필요한 fan-out과 상태 복제를 줄이는 문제에 가깝다고 판단했습니다. OpenChat의 hot-room 테스트에서도 단일 방 메시지가 모든 WebSocket 세션으로 전파되기 때문에 부하가 `N * N`에 가깝게 증가합니다. 그래서 batch fan-out, controlled realtime, broadcast lane metric을 먼저 도입했고, 다음 단계로는 사용자가 실제로 보고 있는 방만 full fan-out하는 active/passive 수신 모델과 slow session 격리 정책을 설계하려고 합니다.

이 사례에서 배운 점은 성능 개선을 코드 최적화로만 접근하면 안 된다는 것입니다. 먼저 이벤트 타입별 처리 시간, queue wait, send duration, 실패 reason, 부하 생성기 관측 비용을 분리해 측정해야 합니다. 그 다음 hot path에서 전체 순회와 큰 상태 복제를 제거하고, 느린 I/O나 대형 작업은 핵심 fan-out 루프 밖으로 밀어내야 대형방에서도 tail latency를 설명 가능한 방식으로 낮출 수 있습니다.

## 최종 takeaway

OpenChat이 가져갈 핵심은 다음 한 문장으로 정리할 수 있다.

> 대형 실시간 채팅방은 "더 많이 보내는 구조"가 아니라 "실제로 필요한 대상에게만, 핵심 루프를 막지 않는 방식으로 보내는 구조"로 확장해야 한다.
