#!/bin/bash
# 用一首新歌，避免历史数据干扰
BASE=1717600000000  # 与之前保持一致的起始时间戳
SONG=song-E

echo "=== 第一步：发一条正常事件，落进 [00:00-00:05] 窗口 ==="
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d "{\"eventId\":\"b-start-1\",\"userId\":\"u1\",\"songId\":\"$SONG\",\"eventType\":\"PLAY_START\",\"timestamp\":$BASE,\"positionMs\":0,\"durationMs\":240000}"
echo ""

sleep 1

echo "=== 第二步：发一条时间戳 +5min30S 的事件==="
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d "{\"eventId\":\"b-trigger-1\",\"userId\":\"u2\",\"songId\":\"$SONG\",\"eventType\":\"PLAY_START\",\"timestamp\":$((BASE + 5*60*1000 + 30*1000)),\"positionMs\":0,\"durationMs\":240000}"
echo ""

sleep 1

echo "=== 第三步：发一条迟到事件，时间戳是 +30s（应该还能补进 [00:00-00:05] 窗口，因为 grace=1min） ==="
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d "{\"eventId\":\"b-late-1\",\"userId\":\"u3\",\"songId\":\"$SONG\",\"eventType\":\"PLAY_START\",\"timestamp\":$((BASE + 30*1000)),\"positionMs\":0,\"durationMs\":240000}"
echo ""

sleep 1

echo "=== 第四步：发一条时间戳 +9min 的事件，把 streamTime 推得更远 ==="
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d "{\"eventId\":\"b-trigger-2\",\"userId\":\"u4\",\"songId\":\"$SONG\",\"eventType\":\"PLAY_START\",\"timestamp\":$((BASE + 10*60*1000)),\"positionMs\":0,\"durationMs\":240000}"
echo ""

sleep 1

echo "=== 第五步：再发一条 +40s 的迟到事件，这次应该被丢弃（超出 grace period） ==="
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d "{\"eventId\":\"b-late-2\",\"userId\":\"u5\",\"songId\":\"$SONG\",\"eventType\":\"PLAY_START\",\"timestamp\":$((BASE + 40*1000)),\"positionMs\":0,\"durationMs\":240000}"
echo ""

echo "全部发送完毕，去看 stream-processor 日志"