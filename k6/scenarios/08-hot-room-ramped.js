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
const TOTAL_TARGET_VUS = Number(__ENV.TOTAL_TARGET_VUS || String(TARGET_VUS));
const CONNECT_RAMP_SECONDS = Number(__ENV.CONNECT_RAMP_SECONDS || '60');
const CHAT_DURATION_SECONDS = Number(__ENV.CHAT_DURATION_SECONDS || '120');
const SEND_INTERVAL_MS = Number(__ENV.SEND_INTERVAL_MS || '1000');
const MESSAGE_TEXT = __ENV.MESSAGE_TEXT || makeChatMessage(8);
const TEST_LABEL = __ENV.TEST_LABEL || `ramped-${TOTAL_TARGET_VUS}`;
const HOT_ROOM_DETAIL_METRICS = (__ENV.HOT_ROOM_DETAIL_METRICS || 'false') === 'true';
const SHARED_ROOM_ID = __ENV.SHARED_ROOM_ID || '';
const SHARED_ROOM_MODE = (__ENV.SHARED_ROOM_MODE || 'false') === 'true';
const VU_INDEX_OFFSET = Number(__ENV.VU_INDEX_OFFSET || '0');
const K6_WORKER_INDEX = Number(__ENV.K6_WORKER_INDEX || '1');
const K6_WORKER_COUNT = Number(__ENV.K6_WORKER_COUNT || '1');
const K6_SENDER_RATIO = Number(__ENV.K6_SENDER_RATIO || '0.94');
const K6_OBSERVER_RATIO = Number(__ENV.K6_OBSERVER_RATIO || '0.05');
const K6_VALIDATOR_RATIO = Number(__ENV.K6_VALIDATOR_RATIO || '0.01');
const OBSERVER_SEND_INTERVAL_MS = Number(__ENV.OBSERVER_SEND_INTERVAL_MS || '0');

export const hotRoomBroadcastReceived = new Counter('hot_room_broadcast_received_total');
export const hotRoomUniqueReceived = new Counter('hot_room_unique_received_total');
export const hotRoomDuplicateReceived = new Counter('hot_room_duplicate_received_total');
export const hotRoomOwnEchoReceived = new Counter('hot_room_own_echo_received_total');
export const hotRoomClientRoleAssigned = new Counter('hot_room_client_role_assigned_total');

function roleRatios() {
  const sender = Math.max(0, K6_SENDER_RATIO);
  const observer = Math.max(0, K6_OBSERVER_RATIO);
  const validator = Math.max(0, K6_VALIDATOR_RATIO);
  const sum = sender + observer + validator;
  if (sum <= 0) {
    return { sender: 1, observer: 0, validator: 0 };
  }
  return {
    sender: sender / sum,
    observer: observer / sum,
    validator: validator / sum,
  };
}

function stableBucket(value) {
  let x = value >>> 0;
  x = Math.imul(x ^ (x >>> 16), 0x7feb352d) >>> 0;
  x = Math.imul(x ^ (x >>> 15), 0x846ca68b) >>> 0;
  x = (x ^ (x >>> 16)) >>> 0;
  return x / 4294967296;
}

function clientModeForVu(vuId) {
  const ratios = roleRatios();
  const bucket = stableBucket(vuId);
  if (bucket < ratios.sender) {
    return 'sender';
  }
  if (bucket < ratios.sender + ratios.observer) {
    return 'observer';
  }
  return 'validator';
}

function sendIntervalForMode(clientMode) {
  if (clientMode === 'sender') {
    return SEND_INTERVAL_MS;
  }
  if (clientMode === 'observer') {
    return OBSERVER_SEND_INTERVAL_MS;
  }
  return 0;
}

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
    'chat_ack_roundtrip_ms{clientMode:sender}': ['p(95)<300'],
    'ws_visible_freshness_ms{clientMode:observer}': ['p(95)<500'],
  },
  tags: {
    workerIndex: String(K6_WORKER_INDEX),
    workerCount: String(K6_WORKER_COUNT),
  },
};

function createUnlimitedHotRoom(token) {
  const body = JSON.stringify({
    name: `hr-r-${TOTAL_TARGET_VUS}-${Date.now().toString(36)}`,
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
        const body = JSON.parse(r.body);
        return !!(body.id || (body.data && body.data.id));
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
    const body = JSON.parse(res.body);
    return body.id || (body.data && body.data.id) || null;
  } catch (e) {
    console.error('Setup: failed to parse hot room response');
    return null;
  }
}

export function setup() {
  if (SHARED_ROOM_ID) {
    console.log(`Setup: using shared ramped hot room roomId=${SHARED_ROOM_ID} targetVus=${TARGET_VUS} totalTargetVus=${TOTAL_TARGET_VUS} worker=${K6_WORKER_INDEX}/${K6_WORKER_COUNT}`);
    return { roomId: SHARED_ROOM_ID };
  }

  const adminId = `hr-r-admin-${TOTAL_TARGET_VUS}`;
  const adminNick = makeNickname(`Admin${TOTAL_TARGET_VUS}`);
  const token = login(adminId, adminNick);
  if (!token) {
    console.error('Setup: admin login failed');
    return { roomId: null };
  }

  const roomId = createUnlimitedHotRoom(token);
  console.log(`Setup: created ramped hot room roomId=${roomId} targetVus=${TARGET_VUS} totalTargetVus=${TOTAL_TARGET_VUS}`);
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

  const vuId = VU_INDEX_OFFSET + __VU;
  const clientMode = clientModeForVu(vuId);
  const userId = makeUserId(`hr-r-${TOTAL_TARGET_VUS}-${vuId}`);
  const nickname = makeNickname(`Hot${vuId}`);

  const token = login(userId, nickname);
  if (!token) {
    sleep(5);
    return;
  }

  enterRoom(token, roomId);
  sleep(0.2);

  const seenMessageIds = {};
  hotRoomClientRoleAssigned.add(1, { clientMode });

  connectAndChat({
    token,
    roomId,
    duration: CHAT_DURATION_SECONDS,
    sendInterval: sendIntervalForMode(clientMode),
    messageText: MESSAGE_TEXT,
    clientMessageIdPrefix: TEST_LABEL,
    clientMode,
    tags: {
      workerIndex: String(K6_WORKER_INDEX),
      workerCount: String(K6_WORKER_COUNT),
    },
    onMessage: (msg) => {
      if (msg.type === 'chat.ack') {
        return;
      }
      if (clientMode !== 'validator' && !HOT_ROOM_DETAIL_METRICS) {
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
