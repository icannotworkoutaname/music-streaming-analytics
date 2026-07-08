#!/bin/bash
# 持续发送测试事件，让 Grafana rate() 曲线产生可见波动
# 用法: ./scripts/load-test.sh [轮数，默认10] [每轮间隔秒数，默认15]

ROUNDS=${1:-10}
INTERVAL=${2:-15}
BASE_URL=http://localhost:8080/events
SONGS=("song-A" "song-B" "song-C" "song-D" "song-E")
DURATION=240000

send_event() {
  local event_id=$1 user_id=$2 song_id=$3 event_type=$4 position=$5
  local ts=$(date +%s%3N)
  curl -s -X POST "$BASE_URL" \
    -H "Content-Type: application/json" \
    -d "{\"eventId\":\"$event_id\",\"userId\":\"$user_id\",\"songId\":\"$song_id\",\"eventType\":\"$event_type\",\"timestamp\":$ts,\"positionMs\":$position,\"durationMs\":$DURATION}" \
    > /dev/null
}

echo "Starting load test: $ROUNDS rounds, ${INTERVAL}s interval"
echo "Press Ctrl+C to stop"
echo ""

for round in $(seq 1 $ROUNDS); do
  echo "[Round $round/$ROUNDS] $(date '+%H:%M:%S')"

  for i in {1..5}; do
    song=${SONGS[$((i - 1))]}
    send_event "r${round}-start-$i" "user-$i" "$song" "PLAY_START" 0
  done

  # song-A/B/C 完整播放（完成率高），song-D/E 中途跳过（完成率低）
  for i in {1..3}; do
    song=${SONGS[$((i - 1))]}
    send_event "r${round}-end-$i" "user-$i" "$song" "PLAY_END" 220000
  done
  for i in {4..5}; do
    song=${SONGS[$((i - 1))]}
    send_event "r${round}-end-$i" "user-$i" "$song" "PLAY_END" 60000
  done

  # 顺带触发几次 query-service 查询
  curl -s "http://localhost:8082/api/songs/song-A/completion" > /dev/null
  curl -s "http://localhost:8082/api/songs/top?n=5" > /dev/null

  echo "  Sent 5 starts + 5 ends, 2 queries"

  if [ $round -lt $ROUNDS ]; then
    sleep $INTERVAL
  fi
done

echo ""
echo "Done. Check Grafana at http://localhost:3000"
