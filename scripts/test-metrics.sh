#!/bin/bash
# 验证三个服务的 Prometheus 端点正常，且业务 counter 在请求后确实递增

set -e

INGESTION=http://localhost:8080
STREAM=http://localhost:8081
QUERY=http://localhost:8082

echo "=== 1. Prometheus 端点可达性 ==="

for url in "$INGESTION/actuator/prometheus" "$STREAM/actuator/prometheus" "$QUERY/actuator/prometheus"; do
  status=$(curl -s -o /dev/null -w "%{http_code}" "$url")
  if [ "$status" = "200" ]; then
    echo "  OK  $url"
  else
    echo "  FAIL $url (HTTP $status)"
    exit 1
  fi
done

echo ""
echo "=== 2. queries.executed counter 递增验证 ==="

before=$(curl -s "$QUERY/actuator/prometheus" \
  | grep 'queries_executed_total{' \
  | awk '{sum += $2} END {print sum+0}')
echo "  查询前 queries_executed_total = $before"

# 触发两种查询各一次
curl -s "$QUERY/api/songs/song-A/completion" > /dev/null
curl -s "$QUERY/api/songs/top?n=5" > /dev/null

after=$(curl -s "$QUERY/actuator/prometheus" \
  | grep 'queries_executed_total{' \
  | awk '{sum += $2} END {print sum+0}')
echo "  查询后 queries_executed_total = $after"

diff=$(echo "$after - $before" | bc)
if [ "$diff" = "2" ]; then
  echo "  OK  counter 递增了 2（符合预期）"
else
  echo "  FAIL 期望递增 2，实际递增 $diff"
  exit 1
fi

echo ""
echo "=== 3. windows.emitted counter 当前值 ==="

curl -s "$STREAM/actuator/prometheus" \
  | grep 'windows_emitted_total{' \
  | while read line; do
      echo "  $line"
    done

echo ""
echo "=== 4. 各服务 application tag 验证 ==="

for svc in ingestion-service stream-processor query-service; do
  port=8080
  [ "$svc" = "stream-processor" ] && port=8081
  [ "$svc" = "query-service" ]    && port=8082

  hit=$(curl -s "http://localhost:$port/actuator/prometheus" \
    | grep -c "application=\"$svc\"" || true)
  if [ "$hit" -gt "0" ]; then
    echo "  OK  $svc: application tag 存在（$hit 处匹配）"
  else
    echo "  FAIL $svc: 未找到 application=\"$svc\" tag"
    exit 1
  fi
done

echo ""
echo "All checks passed."
