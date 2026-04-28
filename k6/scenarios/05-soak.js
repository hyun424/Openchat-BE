/**
 * 05-soak.js — 내구성(Soak) 테스트
 *
 * 50 VU로 4시간 지속 실행
 * Mixed E2E와 동일한 VU 동작
 *
 * 관측 포인트:
 *   - 첫 30분 vs 마지막 30분 p95 지연시간 비교 (20% 이상 증가 시 문제)
 *   - ChatFanoutService의 ConcurrentHashMap 메모리 증가 여부
 *   - RoomSessionRegistry의 데드 세션 정리 동작 확인
 *   - Redis 메모리 증가 추이, MySQL 커넥션풀 고갈 여부
 */

import { sleep } from 'k6';
import { login } from '../lib/auth.js';
import { makeUserId, makeNickname, makeChatMessage, makeRoomName } from '../lib/data-factory.js';
import {
  listRooms, getRoom, createRoom, enterRoom,
  getMessages, getHotChats, getMyRooms,
} from '../lib/http-helpers.js';
import { connectAndChat } from '../lib/ws.js';

export const options = {
  stages: [
    { duration: '5m',     target: 50 },  // 램프업
    { duration: '3h50m',  target: 50 },  // 4시간 유지 (램프업 5분 + 유지 3h50m + 쿨다운 5분 = 4시간)
    { duration: '5m',     target: 0  },  // 쿨다운
  ],
  thresholds: {
    http_req_duration:       ['p(95)<500', 'p(99)<1000'],
    http_req_failed:         ['rate<0.01'],
    ws_connect_duration_ms:  ['p(95)<1000', 'p(99)<2000'],
    ws_message_roundtrip_ms: ['p(95)<500', 'p(99)<1000'],
    ws_connect_success_rate: ['rate>0.95'],
  },
  tags: { testType: 'soak' },
};

function humanSleep() {
  sleep(1 + Math.random() * 3);
}

export default function () {
  const vuId = __VU;
  const iter = __ITER;
  const userId = makeUserId(vuId);
  const nickname = makeNickname(vuId);

  // ── Phase 1: 로그인 및 브라우징 ──
  const token = login(userId, nickname);
  if (!token) {
    sleep(10);
    return;
  }
  humanSleep();

  const roomsRes = listRooms(token);
  let rooms = [];
  try {
    rooms = JSON.parse(roomsRes.body);
  } catch (e) { /* ignore */ }
  humanSleep();

  getHotChats(token, 5, 5);
  humanSleep();

  // ── Phase 2: 방 선택 → 입장 → 메시지 히스토리 ──
  let targetRoomId = null;

  if (Array.isArray(rooms) && rooms.length > 0) {
    const idx = Math.floor(Math.random() * rooms.length);
    targetRoomId = rooms[idx].id;
  } else {
    const res = createRoom(token, makeRoomName(`soak-${vuId}-${iter}`), 'Soak test room');
    try {
      const room = JSON.parse(res.body);
      if (room && room.id) targetRoomId = room.id;
    } catch (e) { /* ignore */ }
  }

  if (!targetRoomId) {
    sleep(5);
    return;
  }

  getRoom(token, targetRoomId);
  humanSleep();

  enterRoom(token, targetRoomId);
  humanSleep();

  getMessages(token, targetRoomId, 30);
  humanSleep();

  // ── Phase 3: WebSocket 채팅 (60~120초) ──
  const chatDuration = 60 + Math.floor(Math.random() * 61);
  const sendInterval = 2000 + Math.floor(Math.random() * 8001);

  connectAndChat({
    token,
    roomId: targetRoomId,
    duration: chatDuration,
    sendInterval: sendInterval,
    messageText: makeChatMessage(vuId),
  });

  // ── Phase 4: 브라우징 ──
  humanSleep();
  getMyRooms(token);
  humanSleep();
  listRooms(token);
  humanSleep();
  getHotChats(token, 5, 5);

  // 반복 사이 약간의 쉬는 시간
  sleep(5 + Math.random() * 10);
}
