/**
 * 10-active-passive-hot-room-ramped.js
 *
 * Single shared hot-room test that keeps total room membership at TARGET_VUS
 * while only active users receive full WebSocket fan-out. Passive users keep
 * the socket open, declare room.passive, and must not receive full payloads.
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { login, authHeaders, BASE_URL } from '../lib/auth.js';
import { enterRoom } from '../lib/http-helpers.js';
import { makeUserId, makeNickname, makeChatMessage } from '../lib/data-factory.js';
import { connectAndChat } from '../lib/ws.js';
import { restCreateRoom, restWsRoute, httpErrorRate, wsPresenceAssigned, wsRouteFailuresTotal } from '../lib/metrics.js';

const TARGET_VUS = Number(__ENV.TARGET_VUS || '500');
const TOTAL_TARGET_VUS = Number(__ENV.TOTAL_TARGET_VUS || String(TARGET_VUS));
const CONNECT_RAMP_SECONDS = Number(__ENV.CONNECT_RAMP_SECONDS || '120');
const CHAT_DURATION_SECONDS = Number(__ENV.CHAT_DURATION_SECONDS || '120');
const SEND_INTERVAL_MS = Number(__ENV.SEND_INTERVAL_MS || '1000');
const MESSAGE_TEXT = __ENV.MESSAGE_TEXT || makeChatMessage(8);
const TEST_LABEL = __ENV.TEST_LABEL || `active-passive-${TOTAL_TARGET_VUS}`;
const SHARED_ROOM_ID = __ENV.SHARED_ROOM_ID || '';
const VU_INDEX_OFFSET = Number(__ENV.VU_INDEX_OFFSET || '0');
const K6_WORKER_INDEX = Number(__ENV.K6_WORKER_INDEX || '1');
const K6_WORKER_COUNT = Number(__ENV.K6_WORKER_COUNT || '1');
const ACTIVE_USER_RATIO = Number(__ENV.ACTIVE_USER_RATIO || '0.30');
const K6_SENDER_RATIO = Number(__ENV.K6_SENDER_RATIO || '0.94');
const K6_OBSERVER_RATIO = Number(__ENV.K6_OBSERVER_RATIO || '0.05');
const K6_VALIDATOR_RATIO = Number(__ENV.K6_VALIDATOR_RATIO || '0.01');
const OBSERVER_SEND_INTERVAL_MS = Number(__ENV.OBSERVER_SEND_INTERVAL_MS || '0');
const ACTIVE_HEARTBEAT_INTERVAL_MS = Number(__ENV.ACTIVE_HEARTBEAT_INTERVAL_MS || '20000');
const PASSIVE_SETTLE_MS = Number(__ENV.PASSIVE_SETTLE_MS || '2000');
const WS_ROUTE_MAX_RETRIES = Number(__ENV.WS_ROUTE_MAX_RETRIES || '20');
const WS_ROUTE_RETRY_AFTER_MS = Number(__ENV.WS_ROUTE_RETRY_AFTER_MS || '500');

function normalizedRatio(value, fallback) {
  if (!Number.isFinite(value)) {
    return fallback;
  }
  return Math.min(1, Math.max(0, value));
}

function periodicBucket(vuId, denominator) {
  return ((vuId - 1) % denominator) / denominator;
}

function stableBucket(value) {
  let x = value >>> 0;
  x = Math.imul(x ^ (x >>> 16), 0x7feb352d) >>> 0;
  x = Math.imul(x ^ (x >>> 15), 0x846ca68b) >>> 0;
  x = (x ^ (x >>> 16)) >>> 0;
  return x / 4294967296;
}

function presenceModeForVu(vuId) {
  const activeRatio = normalizedRatio(ACTIVE_USER_RATIO, 0.3);
  return periodicBucket(vuId, 10) < activeRatio ? 'active' : 'passive';
}

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

function clientModeForActiveVu(vuId) {
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

function sendIntervalForMode(presenceMode, clientMode) {
  if (presenceMode !== 'active') {
    return 0;
  }
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
    active_passive_hot_room_ramped: {
      executor: 'per-vu-iterations',
      vus: TARGET_VUS,
      iterations: 1,
      maxDuration: `${CONNECT_RAMP_SECONDS + CHAT_DURATION_SECONDS + 120}s`,
    },
  },
  thresholds: {
    http_error_rate: ['rate<0.01'],
    ws_connect_success_rate: ['rate>0.99'],
    ws_connect_failure_rate: ['rate<0.01'],
    ws_connect_duration_ms: ['p(95)<5000', 'p(99)<10000'],
    'chat_ack_roundtrip_ms{presenceMode:active,clientMode:sender}': ['p(95)<300'],
    'ws_visible_freshness_ms{presenceMode:active,clientMode:observer}': ['p(95)<500'],
    'ws_passive_unexpected_messages_total{presenceMode:passive}': ['count<10'],
    'ws_presence_assigned_total{presenceMode:active}': ['count>0'],
    'ws_presence_assigned_total{presenceMode:passive}': ['count>0'],
    'ws_control_messages_sent_total{presenceMode:active}': ['count>0'],
    'ws_control_messages_sent_total{presenceMode:passive}': ['count>0'],
    ws_route_assignment_mismatch_total: ['count==0'],
    ws_route_fallback_total: ['count==0'],
    ws_route_failures_total: ['count==0'],
  },
  tags: {
    workerIndex: String(K6_WORKER_INDEX),
    workerCount: String(K6_WORKER_COUNT),
  },
};

function createUnlimitedHotRoom(token) {
  const body = JSON.stringify({
    name: `aphr-${TOTAL_TARGET_VUS}-${Date.now().toString(36)}`,
    description: 'Active/passive hot room load test room',
    category: 'loadtest',
    requiresApproval: false,
  });

  const res = http.post(`${BASE_URL}/api/rooms`, body, {
    headers: authHeaders(token),
    tags: { name: 'create_active_passive_hot_room' },
  });

  restCreateRoom.add(res.timings.duration);
  httpErrorRate.add(res.status >= 400);

  check(res, {
    'createActivePassiveHotRoom status 2xx': (r) => r.status >= 200 && r.status < 300,
    'createActivePassiveHotRoom has id': (r) => {
      try {
        const body = JSON.parse(r.body);
        return !!(body.id || (body.data && body.data.id));
      } catch (e) {
        return false;
      }
    },
  });

  if (res.status < 200 || res.status >= 300) {
    console.error(`Setup: failed to create active/passive hot room status=${res.status} body=${res.body}`);
    return null;
  }

  try {
    const body = JSON.parse(res.body);
    return body.id || (body.data && body.data.id) || null;
  } catch (e) {
    console.error('Setup: failed to parse active/passive hot room response');
    return null;
  }
}

function getWebSocketRoute(token, roomId, phase = 'initial') {
  let res = null;
  for (let attempt = 1; attempt <= WS_ROUTE_MAX_RETRIES; attempt += 1) {
    res = http.get(`${BASE_URL}/api/rooms/${roomId}/ws-route`, {
      headers: authHeaders(token),
      tags: { name: 'get_ws_route' },
    });
    restWsRoute.add(res.timings.duration);
    if (res.status !== 503) {
      break;
    }
    let retryAfterMs = WS_ROUTE_RETRY_AFTER_MS;
    try {
      const body = JSON.parse(res.body || '{}');
      retryAfterMs = Number(body.retryAfterMs || retryAfterMs);
      console.log(`getWsRoute retry roomId=${roomId} reason=${body.reason || 'unavailable'} attempt=${attempt}`);
    } catch (e) {
      console.log(`getWsRoute retry roomId=${roomId} status=503 attempt=${attempt}`);
    }
    sleep(Math.max(0.05, retryAfterMs / 1000));
  }

  httpErrorRate.add(res.status >= 400);

  check(res, {
    'getWsRoute status 2xx': (r) => r.status >= 200 && r.status < 300,
    'getWsRoute has partition id': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.partitionId !== undefined;
      } catch (e) {
        return false;
      }
    },
  });

  if (res.status < 200 || res.status >= 300) {
    console.error(`getWsRoute failed roomId=${roomId} status=${res.status} body=${res.body}`);
    wsRouteFailuresTotal.add(1, { phase, reason: `status_${res.status}` });
    return null;
  }

  try {
    const body = JSON.parse(res.body);
    return {
      partitionId: body.partitionId,
      partitioned: Boolean(body.partitioned),
      partitionCount: Number(body.partitionCount || 1),
      routeVersion: Number(body.version || body.routeVersion || 0),
      wsUrl: body.wsUrl || null,
      nodeId: body.nodeId || null,
      assignmentVersion: body.assignmentVersion || null,
      fallbackReason: body.fallbackReason || null,
    };
  } catch (e) {
    console.error(`getWsRoute parse failed roomId=${roomId}`);
    wsRouteFailuresTotal.add(1, { phase, reason: 'parse_failed' });
    return null;
  }
}

export function setup() {
  if (SHARED_ROOM_ID) {
    console.log(`Setup: using shared active/passive hot room roomId=${SHARED_ROOM_ID} targetVus=${TARGET_VUS} totalTargetVus=${TOTAL_TARGET_VUS} worker=${K6_WORKER_INDEX}/${K6_WORKER_COUNT}`);
    return { roomId: SHARED_ROOM_ID };
  }

  const adminId = `aphr-admin-${TOTAL_TARGET_VUS}`;
  const adminNick = makeNickname(`APAdmin${TOTAL_TARGET_VUS}`);
  const token = login(adminId, adminNick);
  if (!token) {
    console.error('Setup: admin login failed');
    return { roomId: null };
  }

  const roomId = createUnlimitedHotRoom(token);
  console.log(`Setup: created active/passive hot room roomId=${roomId} targetVus=${TARGET_VUS} totalTargetVus=${TOTAL_TARGET_VUS}`);
  return { roomId };
}

export default function (data) {
  const roomId = data.roomId;
  if (!roomId) {
    console.error('No active/passive hot room available, skipping');
    sleep(5);
    return;
  }

  const rampOffset = CONNECT_RAMP_SECONDS * ((__VU - 1) / Math.max(TARGET_VUS, 1));
  if (rampOffset > 0) {
    sleep(rampOffset);
  }

  const vuId = VU_INDEX_OFFSET + __VU;
  const presenceMode = presenceModeForVu(__VU);
  const clientMode = presenceMode === 'active' ? clientModeForActiveVu(vuId) : 'passive';
  const metricTags = {
    presenceMode,
    clientMode,
    workerIndex: String(K6_WORKER_INDEX),
    workerCount: String(K6_WORKER_COUNT),
  };

  wsPresenceAssigned.add(1, metricTags);

  const userId = makeUserId(`aphr-${TOTAL_TARGET_VUS}-${vuId}`);
  const nickname = makeNickname(`AP${vuId}`);
  const token = login(userId, nickname);
  if (!token) {
    sleep(5);
    return;
  }

  enterRoom(token, roomId);
  sleep(0.2);
  const wsRoute = getWebSocketRoute(token, roomId);
  if (!wsRoute) {
    return;
  }

  connectAndChat({
    token,
    roomId,
    partitionId: wsRoute.partitionId,
    routeVersion: wsRoute.routeVersion,
    wsUrl: wsRoute.wsUrl,
    nodeId: wsRoute.nodeId,
    assignmentVersion: wsRoute.assignmentVersion,
    fallbackReason: wsRoute.fallbackReason,
    routeResolver: () => getWebSocketRoute(token, roomId, 'reconnect'),
    duration: CHAT_DURATION_SECONDS,
    sendInterval: sendIntervalForMode(presenceMode, clientMode),
    messageText: MESSAGE_TEXT,
    clientMessageIdPrefix: TEST_LABEL,
    clientMode,
    presenceMode,
    activeHeartbeatIntervalMs: ACTIVE_HEARTBEAT_INTERVAL_MS,
    passiveSettleMs: PASSIVE_SETTLE_MS,
    tags: {
      workerIndex: String(K6_WORKER_INDEX),
      workerCount: String(K6_WORKER_COUNT),
    },
  });
}
