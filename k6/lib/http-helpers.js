import http from 'k6/http';
import { check } from 'k6';
import { authHeaders, BASE_URL } from './auth.js';
import {
  restListRooms, restGetRoom, restCreateRoom, restEnterRoom,
  restJoinRoom, restGetMessages, restGetHotChat, restGetMyRooms,
  httpErrorRate, rateLimitHits,
} from './metrics.js';

function handleResponse(res, metricTrend, checkName) {
  metricTrend.add(res.timings.duration);
  const isError = res.status >= 400;
  httpErrorRate.add(isError);
  if (res.status === 429) rateLimitHits.add(1);
  check(res, { [`${checkName} ok`]: (r) => r.status >= 200 && r.status < 300 });
  return res;
}

// ── Room APIs ──

export function listRooms(token) {
  const res = http.get(`${BASE_URL}/api/rooms`, {
    headers: authHeaders(token),
    tags: { name: 'list_rooms' },
  });
  return handleResponse(res, restListRooms, 'listRooms');
}

export function getRoom(token, roomId) {
  const res = http.get(`${BASE_URL}/api/rooms/${roomId}`, {
    headers: authHeaders(token),
    tags: { name: 'get_room' },
  });
  return handleResponse(res, restGetRoom, 'getRoom');
}

export function createRoom(token, name, description) {
  const body = JSON.stringify({
    name: name,
    description: description || 'Load test room',
    maxMembers: 100,
    requiresApproval: false,
  });
  const res = http.post(`${BASE_URL}/api/rooms`, body, {
    headers: authHeaders(token),
    tags: { name: 'create_room' },
  });
  return handleResponse(res, restCreateRoom, 'createRoom');
}

export function enterRoom(token, roomId) {
  const res = http.post(`${BASE_URL}/api/rooms/${roomId}/enter`, null, {
    headers: authHeaders(token),
    tags: { name: 'enter_room' },
  });
  return handleResponse(res, restEnterRoom, 'enterRoom');
}

export function joinRoom(token, roomId) {
  const res = http.post(`${BASE_URL}/api/rooms/${roomId}/join`, null, {
    headers: authHeaders(token),
    tags: { name: 'join_room' },
  });
  return handleResponse(res, restJoinRoom, 'joinRoom');
}

export function getMyRooms(token) {
  const res = http.get(`${BASE_URL}/api/rooms/my`, {
    headers: authHeaders(token),
    tags: { name: 'my_rooms' },
  });
  return handleResponse(res, restGetMyRooms, 'getMyRooms');
}

// ── Message APIs ──

export function getMessages(token, roomId, limit) {
  const l = limit || 30;
  const res = http.get(`${BASE_URL}/api/rooms/${roomId}/messages?limit=${l}`, {
    headers: authHeaders(token),
    tags: { name: 'get_messages' },
  });
  return handleResponse(res, restGetMessages, 'getMessages');
}

// ── HotChat API ──

export function getHotChats(token, window, limit) {
  const w = window || 5;
  const l = limit || 5;
  const res = http.get(`${BASE_URL}/api/hotchat?window=${w}&limit=${l}`, {
    headers: authHeaders(token),
    tags: { name: 'get_hotchat' },
  });
  return handleResponse(res, restGetHotChat, 'getHotChats');
}
