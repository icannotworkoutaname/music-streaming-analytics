#!/bin/bash
# 验证三个服务的 Prometheus 端点正常，且业务 counter 在请求后确实递增

set -e

INGESTION=http://localhost:8080
STREAM=http://localhost:8081
QUERY=http://localhost:8082

echo "=== 1. Prometheus endpoint reachability ==="

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
echo "=== 2. queries.executed counter increment check ==="

before=$(curl -s "$QUERY/actuator/prometheus" \
  | grep 'queries_executed_total{' \
  | awk '{sum += $2} END {print sum+0}')
echo "  before queries: queries_executed_total = $before"

# 触发两种查询各一次
curl -s "$QUERY/api/songs/song-A/completion" > /dev/null
curl -s "$QUERY/api/songs/top?n=5" > /dev/null

after=$(curl -s "$QUERY/actuator/prometheus" \
  | grep 'queries_executed_total{' \
  | awk '{sum += $2} END {print sum+0}')
echo "  after queries:  queries_executed_total = $after"

diff=$(echo "$after - $before" | bc)
if [ "$diff" = "2" ]; then
  echo "  OK  counter incremented by 2 (as expected)"
else
  echo "  FAIL expected an increment of 2, got $diff"
  exit 1
fi

echo ""
echo "=== 3. windows.emitted counter current value ==="

curl -s "$STREAM/actuator/prometheus" \
  | grep 'windows_emitted_total{' \
  | while read line; do
      echo "  $line"
    done

echo ""
echo "=== 4. application tag check per service ==="

for svc in ingestion-service stream-processor query-service; do
  port=8080
  [ "$svc" = "stream-processor" ] && port=8081
  [ "$svc" = "query-service" ]    && port=8082

  hit=$(curl -s "http://localhost:$port/actuator/prometheus" \
    | grep -c "application=\"$svc\"" || true)
  if [ "$hit" -gt "0" ]; then
    echo "  OK  $svc: application tag present ($hit matches)"
  else
    echo "  FAIL $svc: no application=\"$svc\" tag found"
    exit 1
  fi
done

echo ""
echo "All checks passed."
