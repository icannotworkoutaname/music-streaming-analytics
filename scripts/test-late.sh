#!/bin/bash
# 用一首新歌，避免历史数据干扰
BASE=1717600000000  # 与之前保持一致的起始时间戳
SONG=song-E

echo "=== Step 1: send a normal event, landing in the [00:00-00:05] window ==="
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d "{\"eventId\":\"b-start-1\",\"userId\":\"u1\",\"songId\":\"$SONG\",\"eventType\":\"PLAY_START\",\"timestamp\":$BASE,\"positionMs\":0,\"durationMs\":240000}"
echo ""

sleep 1

echo "=== Step 2: send an event with timestamp +5min30s ==="
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d "{\"eventId\":\"b-trigger-1\",\"userId\":\"u2\",\"songId\":\"$SONG\",\"eventType\":\"PLAY_START\",\"timestamp\":$((BASE + 5*60*1000 + 30*1000)),\"positionMs\":0,\"durationMs\":240000}"
echo ""

sleep 1

echo "=== Step 3: send a late event with timestamp +30s (should still fall into the [00:00-00:05] window, since grace=1min) ==="
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d "{\"eventId\":\"b-late-1\",\"userId\":\"u3\",\"songId\":\"$SONG\",\"eventType\":\"PLAY_START\",\"timestamp\":$((BASE + 30*1000)),\"positionMs\":0,\"durationMs\":240000}"
echo ""

sleep 1

echo "=== Step 4: send an event with timestamp +10min to advance streamTime further ==="
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d "{\"eventId\":\"b-trigger-2\",\"userId\":\"u4\",\"songId\":\"$SONG\",\"eventType\":\"PLAY_START\",\"timestamp\":$((BASE + 10*60*1000)),\"positionMs\":0,\"durationMs\":240000}"
echo ""

sleep 1

echo "=== Step 5: send another late event at +40s; this one should be dropped (beyond the grace period) ==="
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d "{\"eventId\":\"b-late-2\",\"userId\":\"u5\",\"songId\":\"$SONG\",\"eventType\":\"PLAY_START\",\"timestamp\":$((BASE + 40*1000)),\"positionMs\":0,\"durationMs\":240000}"
echo ""

echo "All events sent; check the stream-processor logs"