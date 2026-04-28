/**
 * 01-rest-api-load.js — REST API 부하 테스트
 *
 * 전제: dev 프로필로 앱 실행 (rate limit 완화)
 *
 * VU 동작: 로그인 → 방 목록 조회 → 방 상세 → 방 생성 → 방 입장 → 메시지 조회 → HotChat 조회 (반복)
 *
 * 스테이지:
 *   1분  →  10 VU (워밍업)
 *   3분  →  50 VU (보통 부하)
 *   3분  → 100 VU (높은 부하)
 *   2분  → 200 VU (피크)
 *   3분  → 200 VU (피크 유지)
 *   2분  →   0 VU (쿨다운)
 */

import { sleep } from 'k6';
import { login } from '../lib/auth.js';
import { makeUserId, makeNickname, makeRoomName } from '../lib/data-factory.js';
import {
  listRooms, getRoom, createRoom, enterRoom,
  getMessages, getHotChats, getMyRooms,
} from '../lib/http-helpers.js';

export const options = {
  stages: [
    { duration: '1m',  target: 10  },
    { duration: '3m',  target: 50  },
    { duration: '3m',  target: 100 },
    { duration: '2m',  target: 200 },
    { duration: '3m',  target: 200 },
    { duration: '2m',  target: 0   },
  ],
  thresholds: {
    http_req_duration:            ['p(95)<500', 'p(99)<1000'],
    http_req_failed:              ['rate<0.01'],
    rest_get_hotchat_duration_ms: ['p(95)<100', 'p(99)<300'],
    http_error_rate:              ['rate<0.01'],
  },
  tags: { testType: 'rest-api-load' },
};

export default function () {
  const vuId = __VU;
  const userId = makeUserId(vuId);
  const nickname = makeNickname(vuId);

  // 1. 로그인
  const token = login(userId, nickname);
  if (!token) {
    sleep(5);
    return;
  }
  sleep(0.5);

  // 2. 방 목록 조회
  const roomsRes = listRooms(token);
  let rooms = [];
  try {
    rooms = JSON.parse(roomsRes.body);
  } catch (e) { /* ignore */ }
  sleep(0.5);

  // 3. 방 상세 조회 (목록에 방이 있으면 랜덤 선택)
  let targetRoomId = null;
  if (Array.isArray(rooms) && rooms.length > 0) {
    const idx = Math.floor(Math.random() * rooms.length);
    targetRoomId = rooms[idx].id;
    getRoom(token, targetRoomId);
    sleep(0.3);
  }

  // 4. 방 생성
  const iterCount = __ITER;
  const newRoomRes = createRoom(token, makeRoomName(`${vuId}-${iterCount}`), 'Load test room');
  try {
    const newRoom = JSON.parse(newRoomRes.body);
    if (newRoom && newRoom.id) {
      targetRoomId = newRoom.id;
    }
  } catch (e) { /* ignore */ }
  sleep(0.3);

  // 5. 방 입장
  if (targetRoomId) {
    enterRoom(token, targetRoomId);
    sleep(0.3);

    // 6. 메시지 조회
    getMessages(token, targetRoomId, 30);
    sleep(0.3);
  }

  // 7. 내 방 목록 조회
  getMyRooms(token);
  sleep(0.3);

  // 8. HotChat 조회
  getHotChats(token, 5, 5);
  sleep(0.5);
}
