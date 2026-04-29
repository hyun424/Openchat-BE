import http from 'k6/http';
import { check, sleep } from 'k6';
import { restLogin, httpErrorRate, rateLimitHits } from './metrics.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

/**
 * dev 프로필 로그인 — POST /api/auth/login
 * @returns {string} JWT 토큰
 */
export function login(userId, nickname) {
  const res = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ userId, nickname }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } }
  );

  restLogin.add(res.timings.duration);
  httpErrorRate.add(res.status !== 200);

  if (res.status === 429) {
    rateLimitHits.add(1);
    return null;
  }

  const ok = check(res, {
    'login status 200': (r) => r.status === 200,
    'login has token': (r) => {
      try { return !!JSON.parse(r.body).token; } catch { return false; }
    },
  });

  if (!ok) return null;
  return JSON.parse(res.body).token;
}

/**
 * Authorization 헤더 생성
 */
export function authHeaders(token) {
  return {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
  };
}

/**
 * setup()에서 배치 로그인 수행
 * auth rate limit (10 req/min)을 우회하기 위해 배치 간 sleep 삽입
 */
export function batchLogin(users) {
  const tokens = {};
  const BATCH_SIZE = 8; // auth rate limit: 10/min — 안전 마진 확보

  for (let i = 0; i < users.length; i++) {
    const { userId, nickname } = users[i];
    const token = login(userId, nickname);
    if (token) {
      tokens[userId] = token;
    }

    // rate limit 회피: 매 BATCH_SIZE 요청마다 잠시 대기
    if ((i + 1) % BATCH_SIZE === 0 && i + 1 < users.length) {
      sleep(61); // 60초 윈도우 + 1초 여유
    }
  }

  return tokens;
}

export { BASE_URL };
