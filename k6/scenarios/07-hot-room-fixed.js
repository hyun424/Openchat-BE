/**
 * 07-hot-room-fixed.js - fixed VU hot room fan-out test
 *
 * Use this for scale-up vs scale-out comparison. Each VU connects once to the
 * same room and stays connected for CHAT_DURATION_SECONDS.
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { login, authHeaders, BASE_URL } from '../lib/auth.js';
import { enterRoom } from '../lib/http-helpers.js';
import { makeUserId, makeNickname, makeChatMessage } from '../lib/data-factory.js';
import { connectAndChat } from '../lib/ws.js';
import { restCreateRoom, httpErrorRate } from '../lib/metrics.js';

const TARGET_VUS = Number(__ENV.TARGET_VUS || '100');
const CHAT_DURATION_SECONDS = Number(__ENV.CHAT_DURATION_SECONDS || '120');
const SEND_INTERVAL_MS = Number(__ENV.SEND_INTERVAL_MS || '1000');
const MESSAGE_TEXT = __ENV.MESSAGE_TEXT || makeChatMessage(8);
const TEST_LABEL = __ENV.TEST_LABEL || `fixed-${TARGET_VUS}`;

export const hotRoomBroadcastReceived = new Counter('hot_room_broadcast_received_total');
export const hotRoomUniqueReceived = new Counter('hot_room_unique_received_total');
export const hotRoomDuplicateReceived = new Counter('hot_room_duplicate_received_total');
export const hotRoomOwnEchoReceived = new Counter('hot_room_own_echo_received_total');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    hot_room_fixed: {
      executor: 'per-vu-iterations',
      vus: TARGET_VUS,
      iterations: 1,
      maxDuration: `${CHAT_DURATION_SECONDS + 90}s`,
    },
  },
  thresholds: {
    ws_connect_duration_ms: ['p(95)<5000', 'p(99)<10000'],
    ws_message_roundtrip_ms: ['p(50)<5000', 'p(95)<60000', 'p(99)<120000'],
    ws_connect_success_rate: ['rate>0.99'],
    http_error_rate: ['rate<0.01'],
  },
  tags: {
    testType: 'hot-room-fixed',
    targetVus: String(TARGET_VUS),
    testLabel: TEST_LABEL,
  },
};

function createUnlimitedHotRoom(token) {
  const body = JSON.stringify({
    name: `hot-room-${TEST_LABEL}-${Date.now()}`,
    description: 'Fixed-VU hot room fan-out load test room',
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
  const adminId = `hot-room-admin-${TEST_LABEL}`;
  const adminNick = makeNickname(`Admin${TARGET_VUS}`);
  const token = login(adminId, adminNick);
  if (!token) {
    console.error('Setup: admin login failed');
    return { roomId: null };
  }

  const roomId = createUnlimitedHotRoom(token);
  console.log(`Setup: created hot room roomId=${roomId} targetVus=${TARGET_VUS}`);
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
  const userId = makeUserId(`${TEST_LABEL}-hot-room-${vuId}`);
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
}
