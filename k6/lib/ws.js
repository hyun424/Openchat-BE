import ws from 'k6/ws';
import { check } from 'k6';
import { makeUUID } from './data-factory.js';
import {
  wsConnectDuration, wsMessageRoundtrip, wsConnectSuccess,
  wsConnectFailure, wsConnectFailures, wsMsgSent, wsMsgReceived,
  wsFramesReceived, chatAckRoundtrip, wsVisibleFreshness, wsAcksReceived,
  wsRealtimeIncompleteFrames, wsRealtimeOmittedMessages,
  wsMessageHandlerDuration, wsJsonParseDuration, wsBatchMessagesPerFrame,
  wsObserverVisibleSamples, wsControlMessagesSent, wsActiveHeartbeatSent,
  wsPassiveUnexpectedMessages, wsReconnectControlsReceived, wsRoutePartitionCount,
  wsRoutePartitionId, wsRouteNodeTotal, wsConnectedNodeTotal, wsRouteFallbackTotal,
  wsRouteAssignmentMismatchTotal, wsRouteFailuresTotal,
} from './metrics.js';

const WS_BASE_URL = __ENV.WS_BASE_URL || 'ws://localhost:8080';
const COUNTER_FLUSH_INTERVAL_MS = Number(__ENV.K6_COUNTER_FLUSH_INTERVAL_MS || '5000');
const VISIBLE_FRESHNESS_SAMPLE_EVERY = Math.max(1, Number(__ENV.K6_VISIBLE_FRESHNESS_SAMPLE_EVERY || '1'));
const VALID_CLIENT_MODES = {
  sender: true,
  observer: true,
  validator: true,
  passive: true,
};
const VALID_PRESENCE_MODES = {
  active: true,
  passive: true,
};

function resolveClientMode(mode) {
  const resolved = String(mode || __ENV.K6_WS_CLIENT_MODE || 'validator').toLowerCase();
  return VALID_CLIENT_MODES[resolved] ? resolved : 'validator';
}

function resolvePresenceMode(mode) {
  const resolved = String(mode || __ENV.K6_WS_PRESENCE_MODE || 'active').toLowerCase();
  return VALID_PRESENCE_MODES[resolved] ? resolved : 'active';
}

function parseJsonWithMetric(data, metricTags) {
  const parseStart = Date.now();
  const msg = JSON.parse(data);
  wsJsonParseDuration.add(Date.now() - parseStart, metricTags);
  return msg;
}

function countBatchMessagesFromText(data) {
  const matches = String(data).match(/"messageId":/g);
  return matches ? matches.length : 0;
}

function recordBatchEnvelopeFromText(data, counters, metricTags, recordBatchSize) {
  if (!String(data).includes('"type":"chat.batch"')) {
    return;
  }
  if (String(data).includes('"realtimeComplete":false')) {
    counters.incompleteFrames += 1;
    const omittedMatch = String(data).match(/"omittedCount":(\d+)/);
    counters.omittedMessages += omittedMatch ? Number(omittedMatch[1]) : 0;
  }
  if (recordBatchSize) {
    const messageCount = countBatchMessagesFromText(data);
    if (messageCount > 0) {
      wsBatchMessagesPerFrame.add(messageCount, metricTags);
    }
  }
}

function countUnexpectedFullPayloadMessages(data) {
  const text = String(data);
  if (text.includes('"type":"chat.ack"')) {
    return 0;
  }
  if (text.includes('"type":"chat.batch"')) {
    return Math.max(1, countBatchMessagesFromText(text));
  }
  if (text.includes('"messageId":') || text.includes('"clientMessageId":')) {
    return 1;
  }
  return 0;
}

function websocketUrl(routeWsUrl, roomId, partitionId, routeVersion, token, nodeId, assignmentVersion) {
  if (routeWsUrl) {
    const base = String(routeWsUrl).startsWith('/')
      ? `${WS_BASE_URL.replace(/\/$/, '')}${routeWsUrl}`
      : String(routeWsUrl);
    return `${base}${base.includes('?') ? '&' : '?'}token=${token}`;
  }
  const partitionQuery = partitionId === null || partitionId === undefined || String(partitionId) === ''
    ? ''
    : `&partitionId=${partitionId}`;
  const routeVersionQuery = routeVersion === null || routeVersion === undefined
    ? ''
    : `&routeVersion=${routeVersion}`;
  const nodeQuery = nodeId ? `&nodeId=${nodeId}` : '';
  const assignmentQuery = assignmentVersion ? `&assignmentVersion=${assignmentVersion}` : '';
  return `${WS_BASE_URL}/ws/chat?roomId=${roomId}${partitionQuery}${routeVersionQuery}${nodeQuery}${assignmentQuery}&token=${token}`;
}

/**
 * WebSocket 연결 후 메시지 송수신 수행
 *
 * @param {Object} opts
 * @param {string} opts.token       - JWT 토큰
 * @param {number} opts.roomId      - 채팅방 ID
 * @param {number|string} opts.partitionId - WebSocket fan-out partition id (선택)
 * @param {number} opts.duration    - 연결 유지 시간(초)
 * @param {number} opts.sendInterval - 메시지 전송 간격(ms), 기본 200
 * @param {string} opts.messageText - 전송할 메시지 텍스트
 * @param {string} opts.clientMessageIdPrefix - DB row count 검증용 clientMessageId prefix (선택)
 * @param {number} opts.drainDurationMs - 전송 종료 후 ack/수신 대기 시간(ms), 기본 max(2000, sendInterval*2)
 * @param {string} opts.clientMode - sender, observer, validator 중 하나
 * @param {string} opts.presenceMode - active, passive 중 하나
 * @param {number} opts.activeHeartbeatIntervalMs - active heartbeat 간격(ms), 기본 20000
 * @param {number} opts.passiveSettleMs - passive 선언 직후 race를 제외할 시간(ms), 기본 2000
 * @param {function} opts.onMessage - 수신 메시지 콜백 (선택)
 * @param {function} opts.routeResolver - reconnect control 수신 후 새 /ws-route를 조회하는 콜백 (선택)
 * @param {Object} opts.tags        - k6 metric tags (선택)
 */
export function connectAndChat(opts) {
  const {
    token,
    roomId,
    partitionId = null,
    duration = 60,
    sendInterval = 200,
    sendStopAfterSeconds = Number(__ENV.K6_WS_SEND_STOP_AFTER_SECONDS || String(duration)),
    messageText = 'k6 load test message',
    clientMessageIdPrefix = '',
    drainDurationMs = Number(__ENV.K6_WS_DRAIN_MS || String(Math.max(2000, sendInterval * 2))),
    clientMode = __ENV.K6_WS_CLIENT_MODE || 'validator',
    presenceMode = __ENV.K6_WS_PRESENCE_MODE || 'active',
    activeHeartbeatIntervalMs = Number(__ENV.ACTIVE_HEARTBEAT_INTERVAL_MS || '20000'),
    passiveSettleMs = Number(__ENV.PASSIVE_SETTLE_MS || '2000'),
    onMessage,
    routeResolver = null,
    routeVersion = null,
    wsUrl = null,
    nodeId = null,
    assignmentVersion = null,
    fallbackReason = null,
    tags = {},
  } = opts;

  let currentPartitionId = partitionId;
  let currentRouteVersion = routeVersion;
  let currentWsUrl = wsUrl;
  let currentRouteNodeId = nodeId;
  let currentAssignmentVersion = assignmentVersion;
  let currentFallbackReason = fallbackReason;
  let remainingDuration = duration;
  let lastResponse = null;
  const reconnectDeadline = Date.now() + duration * 1000;
  const sendStopDeadline = Date.now() + Math.max(0, sendStopAfterSeconds) * 1000;

  while (remainingDuration > 0) {
  let reconnectControl = null;
  const url = websocketUrl(
    currentWsUrl,
    roomId,
    currentPartitionId,
    currentRouteVersion,
    token,
    currentRouteNodeId,
    currentAssignmentVersion
  );
  const connectStart = Date.now();
  const resolvedPresenceMode = resolvePresenceMode(presenceMode);
  const resolvedClientMode = resolveClientMode(clientMode);
  const metricTags = { ...(tags || {}), presenceMode: resolvedPresenceMode, clientMode: resolvedClientMode };
  const shouldSend = resolvedPresenceMode === 'active' && Number(sendInterval) > 0;
  const shouldParseBroadcast = resolvedPresenceMode === 'active'
    && (resolvedClientMode === 'observer' || resolvedClientMode === 'validator');
  const shouldRecordVisible = shouldParseBroadcast;
  const shouldRunDetailCallback = resolvedPresenceMode === 'active' && resolvedClientMode === 'validator';
  if (currentRouteNodeId) {
    wsRouteNodeTotal.add(1, {
      ...metricTags,
      nodeId: String(currentRouteNodeId),
      partitionId: String(currentPartitionId ?? 'none'),
    });
  }
  if (currentFallbackReason) {
    wsRouteFallbackTotal.add(1, { ...metricTags, reason: String(currentFallbackReason) });
  }

  // 전송 메시지의 clientMessageId → 전송 시각 맵 (라운드트립 측정용)
  const pendingMessages = {};
  const counters = {
    framesReceived: 0,
    messagesReceived: 0,
    acksReceived: 0,
    sent: 0,
    incompleteFrames: 0,
    omittedMessages: 0,
  };
  let receivedSinceLastFreshnessSample = 0;
  let sendingEnabled = true;
  let passiveUnexpectedAfter = 0;

  function flushCounters() {
    if (counters.framesReceived > 0) {
      wsFramesReceived.add(counters.framesReceived, metricTags);
      counters.framesReceived = 0;
    }
    if (counters.messagesReceived > 0) {
      wsMsgReceived.add(counters.messagesReceived, metricTags);
      counters.messagesReceived = 0;
    }
    if (counters.acksReceived > 0) {
      wsAcksReceived.add(counters.acksReceived, metricTags);
      counters.acksReceived = 0;
    }
    if (counters.sent > 0) {
      wsMsgSent.add(counters.sent, metricTags);
      counters.sent = 0;
    }
    if (counters.incompleteFrames > 0) {
      wsRealtimeIncompleteFrames.add(counters.incompleteFrames, metricTags);
      counters.incompleteFrames = 0;
    }
    if (counters.omittedMessages > 0) {
      wsRealtimeOmittedMessages.add(counters.omittedMessages, metricTags);
      counters.omittedMessages = 0;
    }
  }

  const res = ws.connect(url, {}, function (socket) {
    const connectEnd = Date.now();
    wsConnectDuration.add(connectEnd - connectStart, metricTags);
    wsConnectSuccess.add(true, metricTags);
    wsConnectFailure.add(false, metricTags);

    function sendControl(type) {
      const payload = JSON.stringify({
        type,
        roomId,
        lastSeenSequence: 0,
      });
      socket.send(payload);
      wsControlMessagesSent.add(1, metricTags);
      if (type === 'room.active.heartbeat') {
        wsActiveHeartbeatSent.add(1, metricTags);
      }
    }

    if (resolvedPresenceMode === 'passive') {
      sendControl('room.passive');
      passiveUnexpectedAfter = Date.now() + passiveSettleMs;
    } else {
      sendControl('room.active');
      socket.setInterval(function () {
        sendControl('room.active.heartbeat');
      }, activeHeartbeatIntervalMs);
    }

    socket.on('message', function (data) {
      const handlerStart = Date.now();
      counters.framesReceived += 1;
      try {
        if (resolvedPresenceMode === 'passive' && Date.now() >= passiveUnexpectedAfter) {
          const unexpectedMessages = countUnexpectedFullPayloadMessages(data);
          if (unexpectedMessages > 0) {
            wsPassiveUnexpectedMessages.add(unexpectedMessages, metricTags);
          }
        }

        const text = String(data);
        if (!shouldParseBroadcast && !text.includes('"type":"chat.ack"') && !text.includes('"type":"room.reconnect"') && !text.includes('"type":"node.connected"')) {
          recordBatchEnvelopeFromText(data, counters, metricTags, false);
          return;
        }

        const msg = parseJsonWithMetric(data, metricTags);
        if (msg && msg.type === 'node.connected') {
          const connectedNodeId = String(msg.nodeId || 'unknown');
          wsConnectedNodeTotal.add(1, {
            ...metricTags,
            nodeId: connectedNodeId,
            routeNodeId: String(currentRouteNodeId || msg.routeNodeId || 'unknown'),
            partitionId: String(msg.partitionId ?? currentPartitionId ?? 'none'),
          });
          if (currentRouteNodeId && connectedNodeId !== String(currentRouteNodeId)) {
            wsRouteAssignmentMismatchTotal.add(1, {
              ...metricTags,
              routeNodeId: String(currentRouteNodeId),
              connectedNodeId,
            });
            console.error(`WS route/connected node mismatch roomId=${roomId} routeNodeId=${currentRouteNodeId} connectedNodeId=${connectedNodeId}`);
          }
          if (onMessage) {
            onMessage(msg);
          }
          return;
        }

        if (msg && msg.type === 'room.reconnect') {
          reconnectControl = msg;
          sendingEnabled = false;
          wsReconnectControlsReceived.add(1, {
            ...metricTags,
            reason: String(msg.reason || 'unknown'),
          });
          if (onMessage) {
            onMessage(msg);
          }
          const reconnectDrainMs = Number(__ENV.K6_WS_RECONNECT_DRAIN_MS || String(Math.max(1000, Number(msg.retryAfterMs || 0))));
          socket.setTimeout(function () {
            flushCounters();
            socket.close();
          }, reconnectDrainMs);
          return;
        }

        if (msg && msg.type === 'chat.ack') {
          counters.acksReceived += 1;
          if (msg.clientMessageId && pendingMessages[msg.clientMessageId]) {
            const ackRtt = Date.now() - pendingMessages[msg.clientMessageId];
            chatAckRoundtrip.add(ackRtt, metricTags);
            delete pendingMessages[msg.clientMessageId];
          }
          if (onMessage) {
            onMessage(msg);
          }
          return;
        }

        if (msg && msg.type === 'chat.batch' && msg.realtimeComplete === false) {
          counters.incompleteFrames += 1;
          counters.omittedMessages += Number(msg.omittedCount || 0);
        }

        const messages = msg && msg.type === 'chat.batch' && Array.isArray(msg.messages)
          ? msg.messages
          : [msg];
        if (msg && msg.type === 'chat.batch') {
          wsBatchMessagesPerFrame.add(messages.length, metricTags);
        }

        for (const logicalMsg of messages) {
          counters.messagesReceived += 1;
          if (shouldRecordVisible && logicalMsg.createdAt) {
            receivedSinceLastFreshnessSample += 1;
            if (receivedSinceLastFreshnessSample >= VISIBLE_FRESHNESS_SAMPLE_EVERY) {
              receivedSinceLastFreshnessSample = 0;
              wsVisibleFreshness.add(Date.now() - Number(logicalMsg.createdAt), metricTags);
              if (resolvedClientMode === 'observer') {
                wsObserverVisibleSamples.add(1, metricTags);
              }
            }
          }

          // 에코된 자기 메시지의 라운드트립 측정
          if (logicalMsg.clientMessageId && pendingMessages[logicalMsg.clientMessageId]) {
            const rtt = Date.now() - pendingMessages[logicalMsg.clientMessageId];
            wsMessageRoundtrip.add(rtt, metricTags);
            delete pendingMessages[logicalMsg.clientMessageId];
          }

          if (shouldRunDetailCallback && onMessage) {
            onMessage(logicalMsg);
          }
        }
      } catch (e) {
        // non-JSON 메시지 무시
      } finally {
        wsMessageHandlerDuration.add(Date.now() - handlerStart, metricTags);
      }
    });

    socket.on('error', function (e) {
      wsConnectSuccess.add(false, metricTags);
      wsConnectFailure.add(true, { ...metricTags, status: 'socket_error' });
      wsConnectFailures.add(1, { ...metricTags, status: 'socket_error' });
      console.error(`WS socket error roomId=${roomId} error=${String(e).slice(0, 240)}`);
    });

    // 주기적 메시지 전송
    if (shouldSend) {
      socket.setInterval(function () {
        if (!sendingEnabled) {
          return;
        }
        const clientMessageId = clientMessageIdPrefix
          ? `${clientMessageIdPrefix}-${makeUUID()}`
          : makeUUID();
        const payload = JSON.stringify({
          content: messageText,
          clientMessageId: clientMessageId,
          clientSentAt: Date.now(),
        });
        pendingMessages[clientMessageId] = Date.now();
        socket.send(payload);
        counters.sent += 1;
      }, sendInterval);
    }

    socket.setInterval(function () {
      flushCounters();
    }, COUNTER_FLUSH_INTERVAL_MS);

    socket.on('close', function () {
      flushCounters();
    });

    // 전송을 먼저 멈춘 뒤 ack/수신을 짧게 drain해서 종료 경계의 유실성 send를 줄인다.
    const sendStopDelayMillis = Math.max(0, Math.min(remainingDuration * 1000, sendStopDeadline - Date.now()));
    if (sendStopDelayMillis === 0) {
      sendingEnabled = false;
    } else {
      socket.setTimeout(function () {
        sendingEnabled = false;
        flushCounters();
      }, sendStopDelayMillis);
    }

    socket.setTimeout(function () {
      socket.close();
    }, remainingDuration * 1000 + drainDurationMs);
  });

  flushCounters();
  lastResponse = res;

  // 연결 실패 시
  const connected = check(res, {
    'ws connected': (r) => r && r.status === 101,
  });
  if (!connected) {
    const status = res && res.status ? String(res.status) : 'unknown';
    const error = res && res.error ? String(res.error).slice(0, 240) : '';
    wsConnectSuccess.add(false, metricTags);
    wsConnectFailure.add(true, { ...metricTags, status });
    wsConnectFailures.add(1, { ...metricTags, status });
    console.error(`WS connect failed roomId=${roomId} status=${status} error=${error}`);
  }

  if (!reconnectControl || !routeResolver) {
    return res;
  }

  const nextRoute = routeResolver({
    roomId,
    reason: reconnectControl.reason,
    routeVersion: reconnectControl.routeVersion,
    previousPartitionId: currentPartitionId,
    previousRouteVersion: currentRouteVersion,
  });
  if (!nextRoute) {
    wsRouteFailuresTotal.add(1, { ...metricTags, phase: 'reconnect', reason: 'route_resolver_null' });
    return res;
  }
  currentPartitionId = nextRoute.partitionId;
  currentRouteVersion = nextRoute.routeVersion;
  currentWsUrl = nextRoute.wsUrl || null;
  currentRouteNodeId = nextRoute.nodeId || null;
  currentAssignmentVersion = nextRoute.assignmentVersion || null;
  currentFallbackReason = nextRoute.fallbackReason || null;
  if (nextRoute.partitionCount !== undefined && nextRoute.partitionCount !== null) {
    wsRoutePartitionCount.add(Number(nextRoute.partitionCount), metricTags);
  }
  if (nextRoute.partitionId !== undefined && nextRoute.partitionId !== null) {
    wsRoutePartitionId.add(Number(nextRoute.partitionId), metricTags);
  }
  console.log(`WS reconnect route roomId=${roomId} partitionCount=${nextRoute.partitionCount} partitionId=${nextRoute.partitionId} routeVersion=${nextRoute.routeVersion} nodeId=${nextRoute.nodeId || ''} fallbackReason=${nextRoute.fallbackReason || ''}`);
  remainingDuration = Math.floor((reconnectDeadline - Date.now()) / 1000);
  }

  return lastResponse;
}

export { WS_BASE_URL };
