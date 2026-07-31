import http from 'k6/http';
import { check } from 'k6';

// 用法: k6 run scripts/load-test.js                  # 默认基线 50->200
//       k6 run -e MAX_VUS=500 scripts/load-test.js   # 找拐点时调大
const MAX_VUS = parseInt(__ENV.MAX_VUS || '200', 10);
const RAMP_VUS = Math.round(MAX_VUS / 4);

export const options = {
  stages: [
    { duration: '30s', target: RAMP_VUS }, // 30秒爬到 1/4 并发
    { duration: '2m', target: RAMP_VUS },  // 维持2分钟
    { duration: '30s', target: MAX_VUS },  // 冲到目标并发
    { duration: '2m', target: MAX_VUS },   // 维持
    { duration: '30s', target: 0 },        // 降回0,观察恢复
  ],
};

const SONG_POOL_SIZE = 300;
const types = ['PLAY_START', 'PLAY_END', 'SKIP'];

export default function () {
  const song = `song-${__VU}-${__ITER % SONG_POOL_SIZE}`;
  const type = types[Math.floor(Math.random() * types.length)];
  const payload = JSON.stringify({
    eventId: `evt-${__VU}-${__ITER}`,
    userId: `user-${__VU}`,
    songId: song,
    eventType: type,
    timestamp: Date.now(),
    positionMs: type === 'PLAY_END' ? 230000 : 0,
    durationMs: 240000,
  });
  const res = http.post('http://localhost:8080/events', payload, {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { 'status is 202': (r) => r.status === 202 });
}
