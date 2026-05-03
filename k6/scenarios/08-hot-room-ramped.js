/**
 * 08-hot-room-ramped.js - ramped hot-room fan-out test
 *
 * Each VU connects once to the same room, but login/enter/ws connect is spread
 * across CONNECT_RAMP_SECONDS to separate connection admission from fan-out.
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { login, authHeaders, BASE_URL } from '../lib/auth.js';
import { enterRoom } from '../lib/http-helpers.js';
import { makeUserId, makeNickname, makeChatMessage } from '../lib/data-factory.js';
import { connectAndChat } from '../lib/ws.js';
import { restCreateRoom, httpErrorRate } from '../lib/metrics.js';

const TARGET_VUS = Number(__ENV.TARGET_VUS || '500');
const CONNECT_RAMP_SECONDS = Number(__ENV.CONNECT_RAMP_SECONDS || '60');
const CHAT_DURATION_SECONDS = Number(__ENV.CHAT_DURATION_SECONDS || '120');
const SEND_INTERVAL_MS = Number(__ENV.SEND_INTERVAL_MS || '1000');
const MESSAGE_TEXT = __ENV.MESSAGE_TEXT || makeChatMessage(8);
const TEST_LABEL = __ENV.TEST_LABEL || `ramped-${TARGET_VUS}`;

export const hotRoomBroadcastReceived = new Counter('hot_room_broadcast_received_total');
export const hotRoomUniqueReceived = new Counter('hot_room_unique_received_total');
export const hotRoomDuplicateReceived = new Counter('hot_room_duplicate_received_total');
export const hotRoomOwnEchoReceived = new Counter('hot_room_own_echo_received_total');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    hot_room_ramped: {
      executor: 'per-vu-iterations',
      vus: TARGET_VUS,
      iterations: 1,
      maxDuration: `${CONNECT_RAMP_SECONDS + CHAT_DURATION_SECONDS + 90}s`,
    },
  },
  thresholds: {
    http_error_rate: ['rate<0.01'],
    ws_connect_success_rate: ['rate>0.99'],
    ws_connect_failure_rate: ['rate<0.01'],
    ws_connect_duration_ms: ['p(95)<5000', 'p(99)<10000'],
    chat_ack_roundtrip_ms: ['p(95)<300'],
    ws_visible_freshness_ms: ['p(95)<300'],
  },
  tags: {
    testType: 'hot-room-ramped',
    targetVus: String(TARGET_VUS),
    testLabel: TEST_LABEL,
  },
};

function createUnlimitedHotRoom(token) {
  const body = JSON.stringify({
    name: `hr-r-${TARGET_VUS}-${Date.now().toString(36)}`,
    description: 'Ramped hot room fan-out load test room',
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
  const adminId = `hr-r-admin-${TARGET_VUS}`;
  const adminNick = makeNickname(`Admin${TARGET_VUS}`);
  const token = login(adminId, adminNick);
  if (!token) {
    console.error('Setup: admin login failed');
    return { roomId: null };
  }

  const roomId = createUnlimitedHotRoom(token);
  console.log(`Setup: created ramped hot room roomId=${roomId} targetVus=${TARGET_VUS}`);
  return { roomId };
}

export default function (data) {
  const roomId = data.roomId;
  if (!roomId) {
    console.error('No hot room available, skipping');
    sleep(5);
    return;
  }

  const rampOffset = CONNECT_RAMP_SECONDS * ((__VU - 1) / Math.max(TARGET_VUS, 1));
  if (rampOffset > 0) {
    sleep(rampOffset);
  }

  const vuId = __VU;
  const userId = makeUserId(`hr-r-${TARGET_VUS}-${vuId}`);
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
      if (msg.type === 'chat.ack') {
        return;
      }
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
