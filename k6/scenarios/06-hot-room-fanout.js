/**
 * 06-hot-room-fanout.js — HotChat 유입 단일 방 fan-out 테스트
 *
 * 목적:
 *   HotChat으로 특정 방이 상단 노출되어 한 방에 사용자가 몰리는 상황을 가정한다.
 *   모든 VU가 같은 방에 접속해 메시지를 보내며, 방당 인원 증가에 따른
 *   WebSocket RTT, broadcast 수신량, 중복 수신, 연결 성공률을 측정한다.
 *
 * 기본 부하:
 *   - 모든 VU가 동일한 방에 입장
 *   - 1초당 1 메시지 전송 (SEND_INTERVAL_MS=1000)
 *   - stage: 10 -> 50 -> 100 -> 200 -> 300 -> 500 -> 0
 *
 * 참고:
 *   일반 방 생성 API는 maxMembers <= 100 검증이 있다.
 *   이 시나리오는 단일 대형 방 fan-out 한계를 보기 위해 maxMembers를 보내지 않아
 *   정원 제한이 없는 테스트 방을 생성한다.
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { login, authHeaders, BASE_URL } from '../lib/auth.js';
import { enterRoom } from '../lib/http-helpers.js';
import { makeUserId, makeNickname, makeChatMessage } from '../lib/data-factory.js';
import { connectAndChat } from '../lib/ws.js';
import { restCreateRoom, httpErrorRate } from '../lib/metrics.js';

const SEND_INTERVAL_MS = Number(__ENV.SEND_INTERVAL_MS || '1000');
const CHAT_DURATION_SECONDS = Number(__ENV.CHAT_DURATION_SECONDS || '120');
const MESSAGE_TEXT = __ENV.MESSAGE_TEXT || makeChatMessage(8);

export const hotRoomBroadcastReceived = new Counter('hot_room_broadcast_received_total');
export const hotRoomUniqueReceived = new Counter('hot_room_unique_received_total');
export const hotRoomDuplicateReceived = new Counter('hot_room_duplicate_received_total');
export const hotRoomOwnEchoReceived = new Counter('hot_room_own_echo_received_total');

export const options = {
  stages: [
    { duration: '30s', target: 10 },
    { duration: '2m', target: 50 },
    { duration: '2m', target: 100 },
    { duration: '2m', target: 200 },
    { duration: '2m', target: 300 },
    { duration: '2m', target: 500 },
    { duration: '1m', target: 0 },
  ],
  thresholds: {
    ws_connect_duration_ms: ['p(95)<1000', 'p(99)<2000'],
    ws_message_roundtrip_ms: ['p(50)<200', 'p(95)<500', 'p(99)<1000'],
    ws_connect_success_rate: ['rate>0.99'],
    http_error_rate: ['rate<0.01'],
  },
  tags: { testType: 'hot-room-fanout' },
};

function createUnlimitedHotRoom(token) {
  const body = JSON.stringify({
    name: `hot-room-fanout-${Date.now()}`,
    description: 'HotChat single-room fan-out load test room',
    category: 'loadtest',
    requiresApproval: false,
  });

  const res = http.post(`${BASE_URL}/api/rooms`, body, {
    headers: authHeaders(token),
    tags: { name: 'create_hot_room' },
  });

  restCreateRoom.add(res.timings.duration);
  httpErrorRate.add(res.status >= 400);

  check(res, {
    'createHotRoom status 2xx': (r) => r.status >= 200 && r.status < 300,
    'createHotRoom has id': (r) => {
      try {
        return !!JSON.parse(r.body).id;
      } catch (e) {
        return false;
      }
    },
  });

  if (res.status < 200 || res.status >= 300) {
    console.error(`Setup: failed to create hot room status=${res.status} body=${res.body}`);
    return null;
  }

  try {
    return JSON.parse(res.body).id;
  } catch (e) {
    console.error('Setup: failed to parse hot room response');
    return null;
  }
}

export function setup() {
  const adminId = 'hot-room-admin';
  const adminNick = 'HotRoomAdmin';
  const token = login(adminId, adminNick);
  if (!token) {
    console.error('Setup: admin login failed');
    return { roomId: null };
  }

  const roomId = createUnlimitedHotRoom(token);
  console.log(`Setup: created hot room roomId=${roomId}`);
  return { roomId };
}

export default function (data) {
  const roomId = data.roomId;
  if (!roomId) {
    console.error('No hot room available, skipping');
    sleep(5);
    return;
  }

  const vuId = __VU;
  const userId = makeUserId(`hot-room-${vuId}`);
  const nickname = makeNickname(`Hot${vuId}`);

  const token = login(userId, nickname);
  if (!token) {
    sleep(5);
    return;
  }

  enterRoom(token, roomId);
  sleep(0.2);

  const seenMessageIds = {};

  connectAndChat({
    token,
    roomId,
    duration: CHAT_DURATION_SECONDS,
    sendInterval: SEND_INTERVAL_MS,
    messageText: MESSAGE_TEXT,
    onMessage: (msg) => {
      hotRoomBroadcastReceived.add(1);

      if (msg.senderId === userId) {
        hotRoomOwnEchoReceived.add(1);
      }

      if (!msg.messageId) {
        return;
      }

      if (seenMessageIds[msg.messageId]) {
        hotRoomDuplicateReceived.add(1);
        return;
      }

      seenMessageIds[msg.messageId] = true;
      hotRoomUniqueReceived.add(1);
    },
  });

  sleep(1);
}
