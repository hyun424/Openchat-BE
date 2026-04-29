/**
 * 02-websocket-stress.js — WebSocket 스트레스 테스트
 *
 * VU 동작: 로그인 → 5개 방 중 하나에 입장 → WebSocket 연결 → 200ms 간격 메시지 전송 (5 msg/sec)
 *          → 자기 메시지 에코 수신 시 라운드트립 측정
 *
 * 방 분배: 5개 방에 VU를 round-robin 분산 배치
 *
 * 스테이지:
 *   30초 →  10 VU (베이스라인)
 *    1분 →  50 VU (보통)
 *    2분 → 100 VU (높은 동시접속)
 *    2분 → 200 VU (스트레스)
 *    2분 → 500 VU (극한 — 한계점 탐색)
 *    1분 → 500 VU (유지)
 *    1분 →   0 VU (드레인)
 */

import { sleep } from 'k6';
import { login } from '../lib/auth.js';
import { makeUserId, makeNickname, makeChatMessage } from '../lib/data-factory.js';
import { enterRoom, createRoom } from '../lib/http-helpers.js';
import { connectAndChat } from '../lib/ws.js';

const NUM_ROOMS = 5;

export const options = {
  stages: [
    { duration: '30s', target: 10  },
    { duration: '1m',  target: 50  },
    { duration: '2m',  target: 100 },
    { duration: '2m',  target: 200 },
    { duration: '2m',  target: 500 },
    { duration: '1m',  target: 500 },
    { duration: '1m',  target: 0   },
  ],
  thresholds: {
    ws_connect_duration_ms:  ['p(95)<1000', 'p(99)<2000'],
    ws_message_roundtrip_ms: ['p(50)<200', 'p(95)<500', 'p(99)<1000'],
    ws_connect_success_rate: ['rate>0.95'],
  },
  tags: { testType: 'websocket-stress' },
};

// setup: 테스트 방 5개 생성
export function setup() {
  const adminId = makeUserId(0);
  const adminNick = makeNickname(0);
  const token = login(adminId, adminNick);
  if (!token) {
    console.error('Setup: admin login failed');
    return { roomIds: [] };
  }

  const roomIds = [];
  for (let i = 1; i <= NUM_ROOMS; i++) {
    const res = createRoom(token, `ws-stress-room-${i}`, `WebSocket stress test room ${i}`);
    try {
      const room = JSON.parse(res.body);
      if (room && room.id) {
        roomIds.push(room.id);
      }
    } catch (e) {
      console.error(`Setup: failed to create room ${i}`);
    }
    sleep(0.5);
  }

  console.log(`Setup: created ${roomIds.length} rooms — ${JSON.stringify(roomIds)}`);
  return { roomIds };
}

export default function (data) {
  const { roomIds } = data;
  if (!roomIds || roomIds.length === 0) {
    console.error('No rooms available, skipping');
    sleep(5);
    return;
  }

  const vuId = __VU;
  const userId = makeUserId(vuId);
  const nickname = makeNickname(vuId);

  // 1. 로그인
  const token = login(userId, nickname);
  if (!token) {
    sleep(5);
    return;
  }

  // 2. 방 선택 (round-robin)
  const roomId = roomIds[vuId % roomIds.length];

  // 3. 방 입장 (HTTP)
  enterRoom(token, roomId);
  sleep(0.5);

  // 4. WebSocket 연결 및 채팅
  connectAndChat({
    token,
    roomId,
    duration: 60,          // 60초간 연결 유지
    sendInterval: 200,     // 200ms 간격 = 5 msg/sec
    messageText: makeChatMessage(vuId),
  });

  sleep(1);
}
