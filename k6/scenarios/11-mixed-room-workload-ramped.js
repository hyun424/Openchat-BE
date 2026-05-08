/**
 * 11-mixed-room-workload-ramped.js
 *
 * Mixed-room workload smoke/main scenario. It creates one hot room, several
 * medium rooms, and several small rooms, then assigns VUs by configured room
 * sizes. The goal is not a single-room maximum test; it verifies whether room
 * workload observer metrics can distinguish actual delivery work from
 * conceptual work across mixed room shapes.
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { login, authHeaders, BASE_URL } from '../lib/auth.js';
import { enterRoom } from '../lib/http-helpers.js';
import { makeNickname, makeChatMessage } from '../lib/data-factory.js';
import { connectAndChat } from '../lib/ws.js';
import { restCreateRoom, restWsRoute, httpErrorRate, wsPresenceAssigned, wsRouteFailuresTotal } from '../lib/metrics.js';

const TARGET_VUS = Number(__ENV.TARGET_VUS || '100');
const CONNECT_RAMP_SECONDS = Number(__ENV.CONNECT_RAMP_SECONDS || '30');
const CHAT_DURATION_SECONDS = Number(__ENV.CHAT_DURATION_SECONDS || '30');
const TEST_LABEL = __ENV.TEST_LABEL || `mixed-room-${TARGET_VUS}`;
const MESSAGE_TEXT = __ENV.MESSAGE_TEXT || makeChatMessage(8);
const VU_INDEX_OFFSET = Number(__ENV.VU_INDEX_OFFSET || '0');
const K6_WORKER_INDEX = Number(__ENV.K6_WORKER_INDEX || '1');
const K6_WORKER_COUNT = Number(__ENV.K6_WORKER_COUNT || '1');
const WS_ROUTE_MAX_RETRIES = Number(__ENV.WS_ROUTE_MAX_RETRIES || '20');
const WS_ROUTE_RETRY_AFTER_MS = Number(__ENV.WS_ROUTE_RETRY_AFTER_MS || '500');
const ASSIGNMENT_PREFLIGHT_ENABLED = String(__ENV.K6_ASSIGNMENT_PREFLIGHT_ENABLED || 'false').toLowerCase() === 'true';
const ASSIGNMENT_PREFLIGHT_PARTITION_COUNT = Number(__ENV.K6_ASSIGNMENT_PREFLIGHT_PARTITION_COUNT || '1');
const ASSIGNMENT_PREFLIGHT_EXPECTED_NODES = Number(__ENV.K6_ASSIGNMENT_PREFLIGHT_EXPECTED_NODES || '0');
const ASSIGNMENT_PREFLIGHT_TIMEOUT_SECONDS = Number(__ENV.K6_ASSIGNMENT_PREFLIGHT_TIMEOUT_SECONDS || '60');
const ASSIGNMENT_PREFLIGHT_POLL_MS = Number(__ENV.K6_ASSIGNMENT_PREFLIGHT_POLL_MS || '1000');

const HOT_ROOM_COUNT = Number(__ENV.MIXED_HOT_ROOM_COUNT || '1');
const HOT_ROOM_VUS = Number(__ENV.MIXED_HOT_ROOM_VUS || '40');
const HOT_ACTIVE_RATIO = Number(__ENV.MIXED_HOT_ACTIVE_RATIO || '0.30');
const HOT_SEND_INTERVAL_MS = Number(__ENV.MIXED_HOT_SEND_INTERVAL_MS || __ENV.SEND_INTERVAL_MS || '1000');

const MEDIUM_ROOM_COUNT = Number(__ENV.MIXED_MEDIUM_ROOM_COUNT || '3');
const MEDIUM_ROOM_VUS = Number(__ENV.MIXED_MEDIUM_ROOM_VUS || '15');
const MEDIUM_ACTIVE_RATIO = Number(__ENV.MIXED_MEDIUM_ACTIVE_RATIO || '0.30');
const MEDIUM_SEND_INTERVAL_MS = Number(__ENV.MIXED_MEDIUM_SEND_INTERVAL_MS || '3000');

const LARGE_ROOM_COUNT = Number(__ENV.MIXED_LARGE_ROOM_COUNT || '0');
const LARGE_ROOM_VUS = Number(__ENV.MIXED_LARGE_ROOM_VUS || '120');
const LARGE_ACTIVE_RATIO = Number(__ENV.MIXED_LARGE_ACTIVE_RATIO || '0.35');
const LARGE_SEND_INTERVAL_MS = Number(__ENV.MIXED_LARGE_SEND_INTERVAL_MS || '2000');

const SMALL_ROOM_COUNT = Number(__ENV.MIXED_SMALL_ROOM_COUNT || '5');
const SMALL_ROOM_VUS = Number(__ENV.MIXED_SMALL_ROOM_VUS || '3');
const SMALL_ACTIVE_RATIO = Number(__ENV.MIXED_SMALL_ACTIVE_RATIO || '0.30');
const SMALL_SEND_INTERVAL_MS = Number(__ENV.MIXED_SMALL_SEND_INTERVAL_MS || '5000');

const K6_SENDER_RATIO = Number(__ENV.K6_SENDER_RATIO || '0.94');
const K6_OBSERVER_RATIO = Number(__ENV.K6_OBSERVER_RATIO || '0.05');
const K6_VALIDATOR_RATIO = Number(__ENV.K6_VALIDATOR_RATIO || '0.01');
const OBSERVER_SEND_INTERVAL_MS = Number(__ENV.OBSERVER_SEND_INTERVAL_MS || '0');
const ACTIVE_HEARTBEAT_INTERVAL_MS = Number(__ENV.ACTIVE_HEARTBEAT_INTERVAL_MS || '20000');
const PASSIVE_SETTLE_MS = Number(__ENV.PASSIVE_SETTLE_MS || '2000');

export const mixedRoomAssignedUsers = new Counter('mixed_room_assigned_users_total');
export const mixedRoomConfigMismatch = new Counter('mixed_room_config_mismatch_total');
export const assignmentPreflightFailuresTotal = new Counter('assignment_preflight_failures_total');

function normalizedRatio(value, fallback) {
  if (!Number.isFinite(value)) {
    return fallback;
  }
  return Math.min(1, Math.max(0, value));
}

function stableBucket(value) {
  let x = value >>> 0;
  x = Math.imul(x ^ (x >>> 16), 0x7feb352d) >>> 0;
  x = Math.imul(x ^ (x >>> 15), 0x846ca68b) >>> 0;
  x = (x ^ (x >>> 16)) >>> 0;
  return x / 4294967296;
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

function clientModeForActiveVu(globalVuId) {
  const ratios = roleRatios();
  const bucket = stableBucket(globalVuId);
  if (bucket < ratios.sender) {
    return 'sender';
  }
  if (bucket < ratios.sender + ratios.observer) {
    return 'observer';
  }
  return 'validator';
}

function presenceModeForVu(localVuInRoom, activeRatio) {
  const ratio = normalizedRatio(activeRatio, 0.3);
  const bucket = ((localVuInRoom - 1) % 100) / 100;
  return bucket < ratio ? 'active' : 'passive';
}

function sendIntervalForMode(roomSpec, presenceMode, clientMode) {
  if (presenceMode !== 'active') {
    return 0;
  }
  if (clientMode === 'sender') {
    return roomSpec.sendIntervalMs;
  }
  if (clientMode === 'observer') {
    return OBSERVER_SEND_INTERVAL_MS;
  }
  return 0;
}

function pushRoomSpecs(specs, type, count, vusPerRoom, activeRatio, sendIntervalMs) {
  for (let i = 0; i < count; i++) {
    const startVu = specs.length === 0 ? 1 : specs[specs.length - 1].endVu + 1;
    const endVu = startVu + vusPerRoom - 1;
    specs.push({
      type,
      roomOrdinal: i + 1,
      roomKey: `${type}-${i + 1}`,
      startVu,
      endVu,
      vusPerRoom,
      activeRatio: normalizedRatio(activeRatio, 0.3),
      sendIntervalMs,
    });
  }
}

function buildRoomSpecs() {
  const specs = [];
  pushRoomSpecs(specs, 'hot', HOT_ROOM_COUNT, HOT_ROOM_VUS, HOT_ACTIVE_RATIO, HOT_SEND_INTERVAL_MS);
  pushRoomSpecs(specs, 'large', LARGE_ROOM_COUNT, LARGE_ROOM_VUS, LARGE_ACTIVE_RATIO, LARGE_SEND_INTERVAL_MS);
  pushRoomSpecs(specs, 'medium', MEDIUM_ROOM_COUNT, MEDIUM_ROOM_VUS, MEDIUM_ACTIVE_RATIO, MEDIUM_SEND_INTERVAL_MS);
  pushRoomSpecs(specs, 'small', SMALL_ROOM_COUNT, SMALL_ROOM_VUS, SMALL_ACTIVE_RATIO, SMALL_SEND_INTERVAL_MS);
  return specs;
}

const ROOM_SPECS = buildRoomSpecs();
const CONFIGURED_VUS = ROOM_SPECS.reduce((sum, room) => sum + room.vusPerRoom, 0);
const CHAT_ACK_P95_THRESHOLD_MS = Number(__ENV.K6_CHAT_ACK_P95_THRESHOLD_MS || '300');
const VISIBLE_FRESHNESS_P95_THRESHOLD_MS = Number(__ENV.K6_VISIBLE_FRESHNESS_P95_THRESHOLD_MS || '500');

function buildThresholds() {
  const thresholds = {
    http_error_rate: ['rate<0.01'],
    ws_connect_success_rate: ['rate>0.99'],
    ws_connect_failure_rate: ['rate<0.01'],
    ws_connect_duration_ms: ['p(95)<5000', 'p(99)<10000'],
    'chat_ack_roundtrip_ms{presenceMode:active,clientMode:sender}': [`p(95)<${CHAT_ACK_P95_THRESHOLD_MS}`],
    'ws_passive_unexpected_messages_total{presenceMode:passive}': ['count<10'],
    mixed_room_config_mismatch_total: ['count==0'],
    ws_route_assignment_mismatch_total: ['count==0'],
    ws_route_fallback_total: ['count==0'],
    ws_route_failures_total: ['count==0'],
    assignment_preflight_failures_total: ['count==0'],
  };
  if (VISIBLE_FRESHNESS_P95_THRESHOLD_MS > 0) {
    thresholds['ws_visible_freshness_ms{presenceMode:active,clientMode:observer}'] = [`p(95)<${VISIBLE_FRESHNESS_P95_THRESHOLD_MS}`];
  }
  return thresholds;
}

function assignmentList(assignments) {
  if (!assignments || typeof assignments !== 'object') {
    return [];
  }
  return Object.keys(assignments).map((key) => assignments[key]);
}

function assignmentReadiness(nodesBody, assignmentsBody) {
  const activeNodes = Array.isArray(nodesBody.activeNodes) ? nodesBody.activeNodes : [];
  const assignments = assignmentList(assignmentsBody.assignments);
  const readyAssignments = assignments.filter((assignment) => assignment && assignment.ready === true);
  const distinctOwners = new Set(readyAssignments.map((assignment) => assignment.nodeId).filter(Boolean));
  const expectedNodes = Math.max(0, ASSIGNMENT_PREFLIGHT_EXPECTED_NODES);
  const expectedPartitions = Math.max(1, ASSIGNMENT_PREFLIGHT_PARTITION_COUNT);
  const expectedDistinctOwners = expectedNodes > 0 ? Math.min(expectedNodes, expectedPartitions) : 1;

  return {
    ready:
      activeNodes.length >= expectedNodes &&
      assignments.length >= expectedPartitions &&
      readyAssignments.length >= expectedPartitions &&
      distinctOwners.size >= expectedDistinctOwners,
    activeNodeCount: activeNodes.length,
    assignmentCount: assignments.length,
    readyAssignmentCount: readyAssignments.length,
    distinctOwnerCount: distinctOwners.size,
    expectedNodes,
    expectedPartitions,
    expectedDistinctOwners,
  };
}

function getJsonOrNull(url, token, name) {
  const res = http.get(url, {
    headers: authHeaders(token),
    tags: { name },
  });
  if (res.status < 200 || res.status >= 300) {
    return { status: res.status, body: null };
  }
  try {
    return { status: res.status, body: JSON.parse(res.body || '{}') };
  } catch (e) {
    return { status: res.status, body: null };
  }
}

function waitForAssignmentPreflight(token) {
  if (!ASSIGNMENT_PREFLIGHT_ENABLED) {
    return true;
  }

  const timeoutAt = Date.now() + Math.max(1, ASSIGNMENT_PREFLIGHT_TIMEOUT_SECONDS) * 1000;
  const pollSeconds = Math.max(0.1, ASSIGNMENT_PREFLIGHT_POLL_MS / 1000);
  let attempt = 0;
  let last = null;

  while (Date.now() < timeoutAt) {
    attempt += 1;
    const nodes = getJsonOrNull(`${BASE_URL}/api/internal/room-partition/nodes`, token, 'assignment_preflight_nodes');
    const assignments = getJsonOrNull(
      `${BASE_URL}/api/internal/room-partition/assignments?partitionCount=${Math.max(1, ASSIGNMENT_PREFLIGHT_PARTITION_COUNT)}`,
      token,
      'assignment_preflight_assignments'
    );

    if (nodes.body && assignments.body) {
      last = assignmentReadiness(nodes.body, assignments.body);
      if (last.ready) {
        console.log(
          `Assignment preflight ready activeNodes=${last.activeNodeCount}/${last.expectedNodes} ` +
          `readyAssignments=${last.readyAssignmentCount}/${last.expectedPartitions} ` +
          `distinctOwners=${last.distinctOwnerCount}/${last.expectedDistinctOwners} attempt=${attempt}`
        );
        return true;
      }
    } else {
      last = {
        ready: false,
        nodesStatus: nodes.status,
        assignmentsStatus: assignments.status,
      };
    }

    if (attempt === 1 || attempt % 5 === 0) {
      console.log(`Assignment preflight waiting attempt=${attempt} state=${JSON.stringify(last)}`);
    }
    sleep(pollSeconds);
  }

  assignmentPreflightFailuresTotal.add(1);
  console.error(`Assignment preflight timeout state=${JSON.stringify(last)}`);
  return false;
}

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    mixed_room_workload_ramped: {
      executor: 'per-vu-iterations',
      vus: TARGET_VUS,
      iterations: 1,
      maxDuration: `${CONNECT_RAMP_SECONDS + CHAT_DURATION_SECONDS + 120}s`,
    },
  },
  thresholds: buildThresholds(),
  tags: {
    testType: 'mixed-room-workload-ramped',
    workerIndex: String(K6_WORKER_INDEX),
    workerCount: String(K6_WORKER_COUNT),
  },
};

function createLoadtestRoom(token, roomSpec) {
  const body = JSON.stringify({
    name: `mixed-${roomSpec.roomKey}-${Date.now().toString(36)}`,
    description: `Mixed room workload ${roomSpec.type} room`,
    category: 'loadtest',
    requiresApproval: false,
  });

  const res = http.post(`${BASE_URL}/api/rooms`, body, {
    headers: authHeaders(token),
    tags: { name: 'create_mixed_room', roomType: roomSpec.type },
  });

  restCreateRoom.add(res.timings.duration, { roomType: roomSpec.type });
  httpErrorRate.add(res.status >= 400, { roomType: roomSpec.type });

  check(res, {
    'createMixedRoom status 2xx': (r) => r.status >= 200 && r.status < 300,
    'createMixedRoom has id': (r) => {
      try {
        const body = JSON.parse(r.body);
        return !!(body.id || (body.data && body.data.id));
      } catch (e) {
        return false;
      }
    },
  });

  if (res.status < 200 || res.status >= 300) {
    console.error(`Setup: failed to create mixed room type=${roomSpec.type} status=${res.status} body=${res.body}`);
    return null;
  }

  try {
    const responseBody = JSON.parse(res.body);
    return responseBody.id || (responseBody.data && responseBody.data.id) || null;
  } catch (e) {
    console.error(`Setup: failed to parse mixed room response type=${roomSpec.type}`);
    return null;
  }
}

function getWebSocketRoute(token, roomId, roomType, phase = 'initial') {
  let res = null;
  for (let attempt = 1; attempt <= WS_ROUTE_MAX_RETRIES; attempt += 1) {
    res = http.get(`${BASE_URL}/api/rooms/${roomId}/ws-route`, {
      headers: authHeaders(token),
      tags: { name: 'get_ws_route', roomType },
    });
    restWsRoute.add(res.timings.duration, { roomType });
    if (res.status !== 503) {
      break;
    }
    let retryAfterMs = WS_ROUTE_RETRY_AFTER_MS;
    try {
      const body = JSON.parse(res.body || '{}');
      retryAfterMs = Number(body.retryAfterMs || retryAfterMs);
      console.log(`getWsRoute retry roomId=${roomId} roomType=${roomType} reason=${body.reason || 'unavailable'} attempt=${attempt}`);
    } catch (e) {
      console.log(`getWsRoute retry roomId=${roomId} roomType=${roomType} status=503 attempt=${attempt}`);
    }
    sleep(Math.max(0.05, retryAfterMs / 1000));
  }

  httpErrorRate.add(res.status >= 400, { roomType });

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
    wsRouteFailuresTotal.add(1, { roomType, phase, reason: `status_${res.status}` });
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
    wsRouteFailuresTotal.add(1, { roomType, phase, reason: 'parse_failed' });
    return null;
  }
}

function findAssignedRoom(rooms, vuId) {
  for (const room of rooms) {
    if (vuId >= room.startVu && vuId <= room.endVu) {
      return room;
    }
  }
  return null;
}

function enterAndResolveRoute(token, roomId, roomType) {
  for (let attempt = 1; attempt <= 8; attempt++) {
    enterRoom(token, roomId);
    sleep(0.25 * attempt);

    const route = getWebSocketRoute(token, roomId, roomType, 'initial');
    if (route && route.partitionId !== null && route.partitionId !== undefined) {
      return route;
    }
  }

  console.error(`enterAndResolveRoute failed roomId=${roomId} roomType=${roomType}`);
  return null;
}

export function setup() {
  if (CONFIGURED_VUS !== TARGET_VUS) {
    console.error(`Configured mixed room VUs=${CONFIGURED_VUS} does not match TARGET_VUS=${TARGET_VUS}`);
  }

  const adminId = `mixed-admin-${TARGET_VUS}`;
  const adminNick = makeNickname(`MixedAdmin${TARGET_VUS}`);
  const token = login(adminId, adminNick);
  if (!token) {
    console.error('Setup: admin login failed');
    return { rooms: [] };
  }
  if (!waitForAssignmentPreflight(token)) {
    throw new Error('assignment preflight failed');
  }

  const rooms = [];
  for (const spec of ROOM_SPECS) {
    const roomId = createLoadtestRoom(token, spec);
    if (roomId) {
      rooms.push({ ...spec, roomId });
    }
  }

  console.log(`Setup: created mixed rooms=${rooms.length}/${ROOM_SPECS.length} targetVus=${TARGET_VUS} configuredVus=${CONFIGURED_VUS}`);
  return { rooms };
}

export default function (data) {
  const rooms = data.rooms || [];
  if (rooms.length !== ROOM_SPECS.length || CONFIGURED_VUS !== TARGET_VUS) {
    mixedRoomConfigMismatch.add(1);
    console.error(`Mixed room config mismatch rooms=${rooms.length}/${ROOM_SPECS.length} configuredVus=${CONFIGURED_VUS} targetVus=${TARGET_VUS}`);
    sleep(5);
    return;
  }

  const rampOffset = CONNECT_RAMP_SECONDS * ((__VU - 1) / Math.max(TARGET_VUS, 1));
  if (rampOffset > 0) {
    sleep(rampOffset);
  }

  const globalVuId = VU_INDEX_OFFSET + __VU;
  const room = findAssignedRoom(rooms, __VU);
  if (!room) {
    mixedRoomConfigMismatch.add(1);
    console.error(`No mixed room assignment for vu=${__VU}`);
    sleep(5);
    return;
  }

  const localVuInRoom = __VU - room.startVu + 1;
  const presenceMode = presenceModeForVu(localVuInRoom, room.activeRatio);
  const clientMode = presenceMode === 'active' ? clientModeForActiveVu(globalVuId) : 'passive';
  const metricTags = {
    roomType: room.type,
    presenceMode,
    clientMode,
    workerIndex: String(K6_WORKER_INDEX),
    workerCount: String(K6_WORKER_COUNT),
  };

  mixedRoomAssignedUsers.add(1, metricTags);
  wsPresenceAssigned.add(1, metricTags);

  // Keep loadtest ids short enough for the production user_id column.
  const userId = `mix-w${K6_WORKER_INDEX}-u${globalVuId}`;
  const nickname = makeNickname(`M${room.type[0].toUpperCase()}${room.roomOrdinal}U${localVuInRoom}`);
  const token = login(userId, nickname);
  if (!token) {
    sleep(5);
    return;
  }

  const wsRoute = enterAndResolveRoute(token, room.roomId, room.type);
  if (!wsRoute) {
    return;
  }

  connectAndChat({
    token,
    roomId: room.roomId,
    partitionId: wsRoute.partitionId,
    routeVersion: wsRoute.routeVersion,
    wsUrl: wsRoute.wsUrl,
    nodeId: wsRoute.nodeId,
    assignmentVersion: wsRoute.assignmentVersion,
    fallbackReason: wsRoute.fallbackReason,
    routeResolver: () => getWebSocketRoute(token, room.roomId, room.type, 'reconnect'),
    duration: CHAT_DURATION_SECONDS,
    sendInterval: sendIntervalForMode(room, presenceMode, clientMode),
    messageText: MESSAGE_TEXT,
    clientMessageIdPrefix: TEST_LABEL,
    clientMode,
    presenceMode,
    activeHeartbeatIntervalMs: ACTIVE_HEARTBEAT_INTERVAL_MS,
    passiveSettleMs: PASSIVE_SETTLE_MS,
    tags: {
      roomType: room.type,
      workerIndex: String(K6_WORKER_INDEX),
      workerCount: String(K6_WORKER_COUNT),
    },
  });
}
