/**
 * 03-mixed-e2e.js — 실제 사용자 여정 (Mixed E2E)
 *
 * VU 동작 (전체 흐름):
 *   1. 로그인 → 방 목록 브라우징 → HotChat 확인
 *   2. 방 선택 → 상세 조회 → 입장 → 메시지 히스토리 로드
 *   3. WebSocket 연결 → 60~120초간 채팅 (2~10초 간격 메시지)
 *   4. WebSocket 종료 → 다른 방 브라우징
 *   각 단계 사이 1~4초 랜덤 슬립 (인간 행동 시뮬레이션)
 *
 * 스테이지:
 *   2분 →  20 VU
 *   5분 →  50 VU
 *   3분 → 100 VU
 *   5분 →  50 VU
 *   2분 →   0 VU
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
    { duration: '2m',  target: 20  },
    { duration: '5m',  target: 50  },
    { duration: '3m',  target: 100 },
    { duration: '5m',  target: 50  },
    { duration: '2m',  target: 0   },
  ],
  thresholds: {
    http_req_duration:       ['p(95)<500', 'p(99)<1000'],
    http_req_failed:         ['rate<0.01'],
    ws_connect_duration_ms:  ['p(95)<1000', 'p(99)<2000'],
    ws_message_roundtrip_ms: ['p(95)<500', 'p(99)<1000'],
    ws_connect_success_rate: ['rate>0.95'],
  },
  tags: { testType: 'mixed-e2e' },
};

function humanSleep() {
  sleep(1 + Math.random() * 3); // 1~4초
}

export default function () {
  const vuId = __VU;
  const iter = __ITER;
  const userId = makeUserId(vuId);
  const nickname = makeNickname(vuId);

  // ── Phase 1: 로그인 및 브라우징 ──
  const token = login(userId, nickname);
  if (!token) {
    sleep(5);
    return;
  }
  humanSleep();

  // 방 목록 브라우징
  const roomsRes = listRooms(token);
  let rooms = [];
  try {
    rooms = JSON.parse(roomsRes.body);
  } catch (e) { /* ignore */ }
  humanSleep();

  // HotChat 확인
  getHotChats(token, 5, 5);
  humanSleep();

  // ── Phase 2: 방 선택 → 상세 → 입장 → 메시지 히스토리 ──
  let targetRoomId = null;

  if (Array.isArray(rooms) && rooms.length > 0) {
    // 기존 방 중 랜덤 선택
    const idx = Math.floor(Math.random() * rooms.length);
    targetRoomId = rooms[idx].id;
  } else {
    // 방이 없으면 생성
    const res = createRoom(token, makeRoomName(`${vuId}-${iter}`), 'E2E test room');
    try {
      const room = JSON.parse(res.body);
      if (room && room.id) targetRoomId = room.id;
    } catch (e) { /* ignore */ }
  }

  if (!targetRoomId) {
    sleep(3);
    return;
  }

  getRoom(token, targetRoomId);
  humanSleep();

  enterRoom(token, targetRoomId);
  humanSleep();

  getMessages(token, targetRoomId, 30);
  humanSleep();

  // ── Phase 3: WebSocket 채팅 (60~120초) ──
  const chatDuration = 60 + Math.floor(Math.random() * 61); // 60~120초
  const sendInterval = 2000 + Math.floor(Math.random() * 8001); // 2~10초

  connectAndChat({
    token,
    roomId: targetRoomId,
    duration: chatDuration,
    sendInterval: sendInterval,
    messageText: makeChatMessage(vuId),
  });

  // ── Phase 4: 다른 방 브라우징 ──
  humanSleep();
  getMyRooms(token);
  humanSleep();
  listRooms(token);
  humanSleep();
  getHotChats(token, 5, 5);
}
