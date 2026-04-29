/**
 * 테스트 데이터 생성 유틸리티
 * - userId: ^[a-zA-Z0-9_-]+$ 패턴 준수
 * - nickname: ^[a-zA-Z0-9가-힣]+$ 패턴 준수
 */

export function makeUserId(vuId) {
  return `loadtest-user-${vuId}`;
}

export function makeNickname(vuId) {
  return `Tester${vuId}`;
}

export function makeRoomName(index) {
  return `loadtest-room-${index}`;
}

export function makeUUID() {
  // k6에는 crypto.randomUUID가 없으므로 간단한 v4 UUID 생성
  const hex = '0123456789abcdef';
  let uuid = '';
  for (let i = 0; i < 36; i++) {
    if (i === 8 || i === 13 || i === 18 || i === 23) {
      uuid += '-';
    } else if (i === 14) {
      uuid += '4';
    } else if (i === 19) {
      uuid += hex[(Math.random() * 4) | 8];
    } else {
      uuid += hex[(Math.random() * 16) | 0];
    }
  }
  return uuid;
}

export function makeChatMessage(index) {
  const messages = [
    'Hello everyone!',
    'How are you doing?',
    'This is a load test message',
    'Testing real-time chat performance',
    'k6 stress test in progress',
    'Checking message delivery latency',
    'WebSocket connection is stable',
    'Monitoring round-trip times',
    'Fan-out broadcast test',
    'Multi-room concurrent chat test',
  ];
  return messages[index % messages.length];
}

/**
 * 주어진 범위의 VU 데이터 배열 생성 (setup용)
 */
export function generateVUData(count) {
  const data = [];
  for (let i = 1; i <= count; i++) {
    data.push({
      userId: makeUserId(i),
      nickname: makeNickname(i),
    });
  }
  return data;
}
