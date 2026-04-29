/**
 * 04-spike.js — 스파이크 테스트
 *
 * ramping-arrival-rate executor 사용
 * 베이스라인 10 req/sec → 10초만에 500 req/sec 급등 → 2분 유지 → 급락 → 회복 → 두 번째 스파이크
 * WebSocket도 병렬로: 10VU → 10초만에 200VU 급등
 *
 * 관측 포인트:
 *   - 회복 시간
 *   - 스파이크 중 에러율
 *   - 그레이스풀 디그레이드 여부
 */

import { sleep } from 'k6';
import { login } from '../lib/auth.js';
import { makeUserId, makeNickname, makeChatMessage, makeRoomName } from '../lib/data-factory.js';
import {
  listRooms, getRoom, createRoom, enterRoom,
  getMessages, getHotChats,
} from '../lib/http-helpers.js';
import { connectAndChat } from '../lib/ws.js';

export const options = {
  scenarios: {
    // HTTP 스파이크: ramping-arrival-rate
    http_spike: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 600,
      maxVUs: 1000,
      stages: [
        { duration: '30s', target: 10  },  // 베이스라인
        { duration: '10s', target: 500 },  // 급등!
        { duration: '2m',  target: 500 },  // 피크 유지
        { duration: '10s', target: 10  },  // 급락
        { duration: '1m',  target: 10  },  // 회복
        { duration: '10s', target: 500 },  // 두 번째 스파이크
        { duration: '2m',  target: 500 },  // 유지
        { duration: '30s', target: 0   },  // 종료
      ],
      exec: 'httpSpike',
    },
    // WebSocket 스파이크: ramping-vus
    ws_spike: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 10  },  // 베이스라인
        { duration: '10s', target: 200 },  // 급등!
        { duration: '2m',  target: 200 },  // 유지
        { duration: '10s', target: 10  },  // 급락
        { duration: '1m',  target: 10  },  // 회복
        { duration: '10s', target: 200 },  // 두 번째 스파이크
        { duration: '2m',  target: 200 },  // 유지
        { duration: '30s', target: 0   },  // 종료
      ],
      exec: 'wsSpike',
    },
  },
  thresholds: {
    http_req_duration:       ['p(95)<2000', 'p(99)<5000'], // 스파이크 시 여유 임계값
    http_req_failed:         ['rate<0.10'],                 // 10% 이하
    ws_connect_duration_ms:  ['p(95)<2000', 'p(99)<5000'],
    ws_connect_success_rate: ['rate>0.80'],                 // 극한 상황 80% 이상
  },
  tags: { testType: 'spike' },
};

// setup: 스파이크 테스트용 방 생성
export function setup() {
  const token = login(makeUserId(0), makeNickname(0));
  if (!token) return { roomIds: [] };

  const roomIds = [];
  for (let i = 1; i <= 5; i++) {
    const res = createRoom(token, `spike-room-${i}`, `Spike test room ${i}`);
    try {
      const room = JSON.parse(res.body);
      if (room && room.id) roomIds.push(room.id);
    } catch (e) { /* ignore */ }
    sleep(0.5);
  }
  return { roomIds };
}

// ── HTTP 스파이크 시나리오 ──
export function httpSpike(data) {
  const vuId = __VU;
  const userId = makeUserId(vuId);
  const nickname = makeNickname(vuId);

  const token = login(userId, nickname);
  if (!token) return;

  // 주요 API 호출 순환
  listRooms(token);
  getHotChats(token, 5, 5);

  if (data.roomIds && data.roomIds.length > 0) {
    const roomId = data.roomIds[vuId % data.roomIds.length];
    getRoom(token, roomId);
    getMessages(token, roomId, 30);
  }
}

// ── WebSocket 스파이크 시나리오 ──
export function wsSpike(data) {
  const vuId = __VU;
  const userId = makeUserId(vuId + 10000); // HTTP와 충돌 방지
  const nickname = makeNickname(vuId + 10000);

  const token = login(userId, nickname);
  if (!token) {
    sleep(5);
    return;
  }

  if (!data.roomIds || data.roomIds.length === 0) {
    sleep(5);
    return;
  }

  const roomId = data.roomIds[vuId % data.roomIds.length];
  enterRoom(token, roomId);

  connectAndChat({
    token,
    roomId,
    duration: 30,
    sendInterval: 500,
    messageText: makeChatMessage(vuId),
  });

  sleep(1);
}
