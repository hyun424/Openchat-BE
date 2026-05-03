import ws from 'k6/ws';
import { check } from 'k6';
import { makeUUID } from './data-factory.js';
import {
  wsConnectDuration, wsMessageRoundtrip, wsConnectSuccess,
  wsConnectFailure, wsConnectFailures, wsMsgSent, wsMsgReceived,
  wsFramesReceived, chatAckRoundtrip, wsVisibleFreshness, wsAcksReceived,
  wsRealtimeIncompleteFrames, wsRealtimeOmittedMessages,
} from './metrics.js';

const WS_BASE_URL = __ENV.WS_BASE_URL || 'ws://localhost:8080';

/**
 * WebSocket 연결 후 메시지 송수신 수행
 *
 * @param {Object} opts
 * @param {string} opts.token       - JWT 토큰
 * @param {number} opts.roomId      - 채팅방 ID
 * @param {number} opts.duration    - 연결 유지 시간(초)
 * @param {number} opts.sendInterval - 메시지 전송 간격(ms), 기본 200
 * @param {string} opts.messageText - 전송할 메시지 텍스트
 * @param {function} opts.onMessage - 수신 메시지 콜백 (선택)
 */
export function connectAndChat(opts) {
  const {
    token,
    roomId,
    duration = 60,
    sendInterval = 200,
    messageText = 'k6 load test message',
    onMessage,
  } = opts;

  const url = `${WS_BASE_URL}/ws/chat?roomId=${roomId}&token=${token}`;
  const connectStart = Date.now();

  // 전송 메시지의 clientMessageId → 전송 시각 맵 (라운드트립 측정용)
  const pendingMessages = {};

  const res = ws.connect(url, {}, function (socket) {
    const connectEnd = Date.now();
    wsConnectDuration.add(connectEnd - connectStart);
    wsConnectSuccess.add(true);
    wsConnectFailure.add(false);

    // 메시지 수신 핸들러
    socket.on('message', function (data) {
      wsFramesReceived.add(1);
      try {
        const msg = JSON.parse(data);
        if (msg && msg.type === 'chat.ack') {
          wsAcksReceived.add(1);
          if (msg.clientMessageId && pendingMessages[msg.clientMessageId]) {
            const ackRtt = Date.now() - pendingMessages[msg.clientMessageId];
            chatAckRoundtrip.add(ackRtt);
            delete pendingMessages[msg.clientMessageId];
          }
          if (onMessage) {
            onMessage(msg);
          }
          return;
        }

        if (msg && msg.type === 'chat.batch' && msg.realtimeComplete === false) {
          wsRealtimeIncompleteFrames.add(1);
          wsRealtimeOmittedMessages.add(Number(msg.omittedCount || 0));
        }

        const messages = msg && msg.type === 'chat.batch' && Array.isArray(msg.messages)
          ? msg.messages
          : [msg];

        for (const logicalMsg of messages) {
          wsMsgReceived.add(1);
          if (logicalMsg.createdAt) {
            wsVisibleFreshness.add(Date.now() - Number(logicalMsg.createdAt));
          }

          // 에코된 자기 메시지의 라운드트립 측정
          if (logicalMsg.clientMessageId && pendingMessages[logicalMsg.clientMessageId]) {
            const rtt = Date.now() - pendingMessages[logicalMsg.clientMessageId];
            wsMessageRoundtrip.add(rtt);
            delete pendingMessages[logicalMsg.clientMessageId];
          }

          if (onMessage) {
            onMessage(logicalMsg);
          }
        }
      } catch (e) {
        // non-JSON 메시지 무시
      }
    });

    socket.on('error', function (e) {
      wsConnectSuccess.add(false);
      wsConnectFailure.add(true, { status: 'socket_error' });
      wsConnectFailures.add(1, { status: 'socket_error' });
      console.error(`WS socket error roomId=${roomId} error=${String(e).slice(0, 240)}`);
    });

    // 주기적 메시지 전송
    socket.setInterval(function () {
      const clientMessageId = makeUUID();
      const payload = JSON.stringify({
        content: messageText,
        clientMessageId: clientMessageId,
      });
      pendingMessages[clientMessageId] = Date.now();
      socket.send(payload);
      wsMsgSent.add(1);
    }, sendInterval);

    // duration 후 연결 종료
    socket.setTimeout(function () {
      socket.close();
    }, duration * 1000);
  });

  // 연결 실패 시
  const connected = check(res, {
    'ws connected': (r) => r && r.status === 101,
  });
  if (!connected) {
    const status = res && res.status ? String(res.status) : 'unknown';
    const error = res && res.error ? String(res.error).slice(0, 240) : '';
    wsConnectSuccess.add(false);
    wsConnectFailure.add(true, { status });
    wsConnectFailures.add(1, { status });
    console.error(`WS connect failed roomId=${roomId} status=${status} error=${error}`);
  }

  return res;
}

export { WS_BASE_URL };
