#!/bin/bash
# 模拟 song-A 在一个 5 分钟窗口内：10 次 start，8 次 complete（完成率应为 0.80）
BASE=1717600000000

for i in {1..10}; do
  TS=$((BASE + i * 1000))
  curl -s -X POST http://localhost:8080/events \
    -H "Content-Type: application/json" \
    -d "{\"eventId\":\"start-$i\",\"userId\":\"u$i\",\"songId\":\"song-A\",\"eventType\":\"PLAY_START\",\"timestamp\":$TS,\"positionMs\":0,\"durationMs\":240000}"
done

for i in {1..8}; do
  TS=$((BASE + i * 1000 + 500))
  curl -s -X POST http://localhost:8080/events \
    -H "Content-Type: application/json" \
    -d "{\"eventId\":\"end-$i\",\"userId\":\"u$i\",\"songId\":\"song-A\",\"eventType\":\"PLAY_END\",\"timestamp\":$TS,\"positionMs\":230000,\"durationMs\":240000}"
done

echo "Sent 10 starts + 8 completes for song-A"