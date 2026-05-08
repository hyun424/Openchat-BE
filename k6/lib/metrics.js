import { Trend, Rate, Counter } from 'k6/metrics';

// ── WebSocket Metrics ──
export const wsConnectDuration = new Trend('ws_connect_duration_ms', true);
export const wsMessageRoundtrip = new Trend('ws_message_roundtrip_ms', true);
export const chatAckRoundtrip = new Trend('chat_ack_roundtrip_ms', true);
export const wsVisibleFreshness = new Trend('ws_visible_freshness_ms', true);
export const wsConnectSuccess = new Rate('ws_connect_success_rate');
export const wsConnectFailure = new Rate('ws_connect_failure_rate');
export const wsConnectFailures = new Counter('ws_connect_failures_total');
export const wsMsgSent = new Counter('ws_messages_sent_total');
export const wsMsgReceived = new Counter('ws_messages_received_total');
export const wsFramesReceived = new Counter('ws_frames_received_total');
export const wsAcksReceived = new Counter('ws_acks_received_total');
export const wsRealtimeIncompleteFrames = new Counter('ws_realtime_incomplete_frames_total');
export const wsRealtimeOmittedMessages = new Counter('ws_realtime_omitted_messages_total');
export const wsMessageHandlerDuration = new Trend('ws_message_handler_duration_ms', true);
export const wsJsonParseDuration = new Trend('ws_json_parse_duration_ms', true);
export const wsBatchMessagesPerFrame = new Trend('ws_batch_messages_per_frame', false);
export const wsObserverVisibleSamples = new Counter('ws_observer_visible_samples_total');
export const wsPresenceAssigned = new Counter('ws_presence_assigned_total');
export const wsControlMessagesSent = new Counter('ws_control_messages_sent_total');
export const wsActiveHeartbeatSent = new Counter('ws_active_heartbeat_sent_total');
export const wsPassiveUnexpectedMessages = new Counter('ws_passive_unexpected_messages_total');
export const wsReconnectControlsReceived = new Counter('ws_reconnect_controls_received_total');
export const wsRoutePartitionCount = new Trend('ws_route_partition_count', false);
export const wsRoutePartitionId = new Trend('ws_route_partition_id', false);
export const wsRouteNodeTotal = new Counter('ws_route_node_total');
export const wsConnectedNodeTotal = new Counter('ws_connected_node_total');
export const wsRouteFallbackTotal = new Counter('ws_route_fallback_total');
export const wsRouteAssignmentMismatchTotal = new Counter('ws_route_assignment_mismatch_total');
export const wsRouteFailuresTotal = new Counter('ws_route_failures_total');

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
export const restWsRoute = new Trend('rest_ws_route_duration_ms', true);

// ── Error Metrics ──
export const httpErrorRate = new Rate('http_error_rate');
export const rateLimitHits = new Counter('rate_limit_hits_total');
