/**
 * 09-multi-hot-room-ramped.js - ramped multi hot-room fan-out test
 *
 * Creates HOT_ROOMS rooms and distributes VUs evenly by roomIndex. The scenario
 * is meant to reveal shared-resource contention across simultaneous hot rooms.
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { login, authHeaders, BASE_URL } from '../lib/auth.js';
import { enterRoom } from '../lib/http-helpers.js';
import { makeUserId, makeNickname, makeChatMessage } from '../lib/data-factory.js';
import { connectAndChat } from '../lib/ws.js';
import { restCreateRoom, httpErrorRate } from '../lib/metrics.js';

const HOT_ROOMS = Number(__ENV.HOT_ROOMS || '3');
const ROOM_INDEX_OFFSET = Number(__ENV.ROOM_INDEX_OFFSET || '0');
const RAW_VUS_PER_ROOM = Number(__ENV.VUS_PER_ROOM || '0');
const DEFAULT_VUS_PER_ROOM = RAW_VUS_PER_ROOM > 0 ? RAW_VUS_PER_ROOM : 500;
const DEFAULT_TARGET_VUS = HOT_ROOMS * DEFAULT_VUS_PER_ROOM;
const TARGET_VUS = Number(__ENV.TARGET_VUS || String(DEFAULT_TARGET_VUS));
const VUS_PER_ROOM = RAW_VUS_PER_ROOM > 0 ? RAW_VUS_PER_ROOM : Math.floor(TARGET_VUS / HOT_ROOMS);
const CONNECT_RAMP_SECONDS = Number(__ENV.CONNECT_RAMP_SECONDS || '120');
const CHAT_DURATION_SECONDS = Number(__ENV.CHAT_DURATION_SECONDS || '120');
const SEND_INTERVAL_MS = Number(__ENV.SEND_INTERVAL_MS || '1000');
const MESSAGE_TEXT = __ENV.MESSAGE_TEXT || makeChatMessage(8);
const TEST_LABEL = __ENV.TEST_LABEL || `multi-hot-${HOT_ROOMS}x${VUS_PER_ROOM}`;
const HOT_ROOM_DETAIL_METRICS = (__ENV.HOT_ROOM_DETAIL_METRICS || 'false') === 'true';

export const hotRoomBroadcastReceived = new Counter('hot_room_broadcast_received_total');
export const hotRoomUniqueReceived = new Counter('hot_room_unique_received_total');
export const hotRoomDuplicateReceived = new Counter('hot_room_duplicate_received_total');
export const hotRoomOwnEchoReceived = new Counter('hot_room_own_echo_received_total');
export const hotRoomAssignedUsers = new Counter('hot_room_assigned_users_total');

function buildThresholds() {
  const thresholds = {
    http_error_rate: ['rate<0.01'],
    ws_connect_success_rate: ['rate>0.99'],
    ws_connect_failure_rate: ['rate<0.01'],
    ws_connect_duration_ms: ['p(95)<5000', 'p(99)<10000'],
    chat_ack_roundtrip_ms: ['p(95)<300'],
    ws_visible_freshness_ms: ['p(95)<500'],
  };

  for (let i = 0; i < HOT_ROOMS; i++) {
    const tag = `room-${ROOM_INDEX_OFFSET + i}`;
    thresholds[`ws_connect_success_rate{roomIndex:${tag}}`] = ['rate>0.99'];
    thresholds[`chat_ack_roundtrip_ms{roomIndex:${tag}}`] = ['p(95)<300', 'p(99)<1000'];
    thresholds[`ws_visible_freshness_ms{roomIndex:${tag}}`] = ['p(95)<500', 'p(99)<1000'];
    thresholds[`ws_messages_sent_total{roomIndex:${tag}}`] = ['count>0'];
    thresholds[`ws_messages_received_total{roomIndex:${tag}}`] = ['count>0'];
    thresholds[`ws_realtime_omitted_messages_total{roomIndex:${tag}}`] = ['count>=0'];
  }

  return thresholds;
}

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    multi_hot_room_ramped: {
      executor: 'per-vu-iterations',
      vus: TARGET_VUS,
      iterations: 1,
      maxDuration: `${CONNECT_RAMP_SECONDS + CHAT_DURATION_SECONDS + 120}s`,
    },
  },
  thresholds: buildThresholds(),
  tags: {
    testType: 'multi-hot-room-ramped',
    targetVus: String(TARGET_VUS),
    hotRooms: String(HOT_ROOMS),
    vusPerRoom: String(VUS_PER_ROOM),
    testLabel: TEST_LABEL,
  },
};

function createUnlimitedHotRoom(token, roomIndex) {
  const body = JSON.stringify({
    name: `mhr-${ROOM_INDEX_OFFSET + roomIndex}-${Date.now().toString(36)}`,
    description: `Multi hot room load test room ${ROOM_INDEX_OFFSET + roomIndex}`,
    category: 'loadtest',
    requiresApproval: false,
  });

  const res = http.post(`${BASE_URL}/api/rooms`, body, {
    headers: authHeaders(token),
    tags: { name: 'create_multi_hot_room', roomIndex: `room-${ROOM_INDEX_OFFSET + roomIndex}` },
  });

  restCreateRoom.add(res.timings.duration, { roomIndex: `room-${ROOM_INDEX_OFFSET + roomIndex}` });
  httpErrorRate.add(res.status >= 400, { roomIndex: `room-${ROOM_INDEX_OFFSET + roomIndex}` });

  check(res, {
    'createMultiHotRoom status 2xx': (r) => r.status >= 200 && r.status < 300,
    'createMultiHotRoom has id': (r) => {
      try {
        const body = JSON.parse(r.body);
        return !!(body.id || (body.data && body.data.id));
      } catch (e) {
        return false;
      }
    },
  });

  if (res.status < 200 || res.status >= 300) {
    console.error(`Setup: failed to create roomIndex=${roomIndex} status=${res.status} body=${res.body}`);
    return null;
  }

  try {
    const body = JSON.parse(res.body);
    return body.id || (body.data && body.data.id) || null;
  } catch (e) {
    console.error(`Setup: failed to parse room response roomIndex=${roomIndex}`);
    return null;
  }
}

export function setup() {
  const adminId = `mhr-admin-${HOT_ROOMS}x${VUS_PER_ROOM}`;
  const adminNick = makeNickname(`MHRAdmin${HOT_ROOMS}`);
  const token = login(adminId, adminNick);
  if (!token) {
    console.error('Setup: admin login failed');
    return { rooms: [] };
  }

  const rooms = [];
  for (let i = 0; i < HOT_ROOMS; i++) {
    const roomId = createUnlimitedHotRoom(token, i);
    if (roomId) {
      rooms.push({ roomId, roomIndex: ROOM_INDEX_OFFSET + i });
    }
  }

  console.log(`Setup: created ${rooms.length}/${HOT_ROOMS} rooms targetVus=${TARGET_VUS} vusPerRoom=${VUS_PER_ROOM} roomIndexOffset=${ROOM_INDEX_OFFSET}`);
  return { rooms };
}

export default function (data) {
  const rooms = data.rooms || [];
  if (rooms.length !== HOT_ROOMS) {
    console.error(`Expected ${HOT_ROOMS} rooms, got ${rooms.length}`);
    sleep(5);
    return;
  }

  const rampOffset = CONNECT_RAMP_SECONDS * ((__VU - 1) / Math.max(TARGET_VUS, 1));
  if (rampOffset > 0) {
    sleep(rampOffset);
  }

  const vuId = __VU;
  const localRoomIndex = (vuId - 1) % HOT_ROOMS;
  const roomIndex = ROOM_INDEX_OFFSET + localRoomIndex;
  const userOrdinalInRoom = Math.floor((vuId - 1) / HOT_ROOMS) + 1;
  const room = rooms[localRoomIndex];
  const roomTag = `room-${roomIndex}`;
  const tags = { roomIndex: roomTag };
  hotRoomAssignedUsers.add(1, tags);

  const userId = makeUserId(`mhr-${HOT_ROOMS}x${VUS_PER_ROOM}-r${roomIndex}-${userOrdinalInRoom}`);
  const nickname = makeNickname(`MHR${roomIndex}U${userOrdinalInRoom}`);

  const token = login(userId, nickname);
  if (!token) {
    sleep(5);
    return;
  }

  enterRoom(token, room.roomId);
  sleep(0.2);

  const seenMessageIds = {};

  connectAndChat({
    token,
    roomId: room.roomId,
    duration: CHAT_DURATION_SECONDS,
    sendInterval: SEND_INTERVAL_MS,
    messageText: MESSAGE_TEXT,
    tags,
    onMessage: (msg) => {
      if (msg.type === 'chat.ack') {
        return;
      }
      if (!HOT_ROOM_DETAIL_METRICS) {
        return;
      }
      hotRoomBroadcastReceived.add(1, tags);

      if (msg.senderId === userId) {
        hotRoomOwnEchoReceived.add(1, tags);
      }

      if (!msg.messageId) {
        return;
      }

      if (seenMessageIds[msg.messageId]) {
        hotRoomDuplicateReceived.add(1, tags);
        return;
      }

      seenMessageIds[msg.messageId] = true;
      hotRoomUniqueReceived.add(1, tags);
    },
  });
}
