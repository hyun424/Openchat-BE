import { Trend, Rate, Counter } from 'k6/metrics';

// ── WebSocket Metrics ──
export const wsConnectDuration = new Trend('ws_connect_duration_ms', true);
export const wsMessageRoundtrip = new Trend('ws_message_roundtrip_ms', true);
export const wsConnectSuccess = new Rate('ws_connect_success_rate');
export const wsConnectFailure = new Rate('ws_connect_failure_rate');
export const wsConnectFailures = new Counter('ws_connect_failures_total');
export const wsMsgSent = new Counter('ws_messages_sent_total');
export const wsMsgReceived = new Counter('ws_messages_received_total');

// ── REST API Metrics (per endpoint) ──
export const restLogin = new Trend('rest_login_duration_ms', true);
export const restListRooms = new Trend('rest_list_rooms_duration_ms', true);
export const restGetRoom = new Trend('rest_get_room_duration_ms', true);
export const restCreateRoom = new Trend('rest_create_room_duration_ms', true);
export const restEnterRoom = new Trend('rest_enter_room_duration_ms', true);
export const restJoinRoom = new Trend('rest_join_room_duration_ms', true);
export const restGetMessages = new Trend('rest_get_messages_duration_ms', true);
export const restGetHotChat = new Trend('rest_get_hotchat_duration_ms', true);
export const restGetMyRooms = new Trend('rest_get_my_rooms_duration_ms', true);

// ── Error Metrics ──
export const httpErrorRate = new Rate('http_error_rate');
export const rateLimitHits = new Counter('rate_limit_hits_total');
