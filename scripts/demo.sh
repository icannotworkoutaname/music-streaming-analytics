#!/usr/bin/env bash
#
# demo.sh — one-command demo: start the stack, inject skewed play traffic,
#           query the aggregates and verify them against what was injected.
#
# Usage:
#   ./scripts/demo.sh                 detect the environment and run the full demo
#   ./scripts/demo.sh --sessions 2000 inject more data
#   ./scripts/demo.sh --skip-infra    services already running; only inject and report
#   ./scripts/demo.sh --reset         clear the Redis read model before injecting
#   ./scripts/demo.sh --report-only   do not inject; just re-run the queries
#   ./scripts/demo.sh --stop          stop the services and containers this script started
#
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
ROOT=$(pwd)

INGESTION=http://localhost:8080
STREAM=http://localhost:8081
QUERY=http://localhost:8082
RUN_DIR=/tmp/music-streaming-demo
COMPOSE_FILE=docker-compose.dev.yml

SESSIONS=600
SKIP_INFRA=0
REPORT_ONLY=0
DO_STOP=0
DO_RESET=0
NO_COLOR=""

while [ $# -gt 0 ]; do
  case "$1" in
    --sessions)    SESSIONS="$2"; shift 2 ;;
    --skip-infra)  SKIP_INFRA=1; shift ;;
    --reset)       DO_RESET=1; shift ;;
    --report-only) REPORT_ONLY=1; SKIP_INFRA=1; shift ;;
    --stop)        DO_STOP=1; shift ;;
    --no-color)    NO_COLOR="--no-color"; shift ;;
    -h|--help)     sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)             echo "Unknown argument: $1 (use --help)" >&2; exit 1 ;;
  esac
done

if [ -n "$NO_COLOR" ]; then
  B=""; RESET=""; GREEN=""; YELLOW=""; DIM=""
else
  B=$'\033[1m'; RESET=$'\033[0m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; DIM=$'\033[2m'
fi

step()  { echo; echo "${B}▸ $*${RESET}"; }
ok()    { echo "  ${GREEN}✓${RESET} $*"; }
warn()  { echo "  ${YELLOW}!${RESET} $*"; }
info()  { echo "  ${DIM}·${RESET} $*"; }
die()   { echo; echo "  ${YELLOW}✗${RESET} $*" >&2; exit 1; }

up() { curl -fsS --max-time 3 "$1/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; }

# ---------------------------------------------------------------- stop
if [ "$DO_STOP" = "1" ]; then
  step "Stopping processes and containers started by this script"
  if [ -d "$RUN_DIR" ]; then
    for f in "$RUN_DIR"/*.pid; do
      [ -e "$f" ] || continue
      pid=$(cat "$f")
      if kill -0 "$pid" 2>/dev/null; then
        # bootRun 的实际 JVM 是 gradle 的子进程，一并结束
        pkill -P "$pid" 2>/dev/null
        kill "$pid" 2>/dev/null
        ok "Stopped $(basename "$f" .pid) (pid $pid)"
      fi
      rm -f "$f"
    done
  fi
  if [ -f "$RUN_DIR/compose.marker" ]; then
    docker compose -f "$COMPOSE_FILE" down >/dev/null 2>&1 && ok "Stopped docker-compose dependencies"
    rm -f "$RUN_DIR/compose.marker"
  fi
  echo
  exit 0
fi

# ---------------------------------------------------------------- 前置检查
step "Checking prerequisites"
for c in curl python3; do
  command -v "$c" >/dev/null || die "$c not found"
done
ok "curl and python3 available"
mkdir -p "$RUN_DIR"

# ---------------------------------------------------------------- 启动
if [ "$SKIP_INFRA" = "0" ]; then
  if up "$INGESTION" && up "$QUERY"; then
    ok "ingestion-service and query-service already running; reusing them"
    info "(works for both local docker-compose and a minikube kubectl port-forward)"
  else
    command -v docker >/dev/null || die "docker not found and services are not running. Start the services yourself, then re-run with --skip-infra"

    step "Starting dependencies (Kafka / Redis / PostgreSQL / Prometheus / Grafana)"
    docker compose -f "$COMPOSE_FILE" up -d >/dev/null 2>&1 \
      || die "docker compose failed. Run 'docker compose -f $COMPOSE_FILE up -d' manually to see the error"
    touch "$RUN_DIR/compose.marker"
    ok "Dependency containers started"

    info "Waiting for Kafka to accept connections ..."
    for _ in $(seq 1 60); do
      docker compose -f "$COMPOSE_FILE" exec -T kafka \
        kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1 && break
      sleep 2
    done

    step "Starting the three services (first run needs a Gradle build; this can take minutes)"
    for svc in ingestion-service stream-processor query-service; do
      nohup ./gradlew ":$svc:bootRun" --console=plain \
        > "$RUN_DIR/$svc.log" 2>&1 &
      echo $! > "$RUN_DIR/$svc.pid"
      info "$svc starting, log: $RUN_DIR/$svc.log"
    done

    info "Waiting for health checks to pass (up to 5 minutes) ..."
    for _ in $(seq 1 150); do
      if up "$INGESTION" && up "$STREAM" && up "$QUERY"; then break; fi
      sleep 2
    done
  fi
fi

for pair in "ingestion-service $INGESTION" "stream-processor $STREAM" "query-service $QUERY"; do
  set -- $pair
  if up "$2"; then
    ok "$1 healthy"
  elif [ "$1" = "stream-processor" ]; then
    # k8s 场景下通常只 port-forward 了 8080/8082，8081 不通不影响演示
    warn "$1 not reachable at $2/actuator/health (expected on K8s if port 8081 is not forwarded)"
  else
    echo
    echo "  Recent log output:"
    tail -n 15 "$RUN_DIR/$1.log" 2>/dev/null | sed 's/^/    /'
    die "$1 is not ready; cannot continue"
  fi
done

# ---------------------------------------------------------------- 清读模型
# query-service 对排行榜只做 ZADD、不裁剪，窗口过期的成员会一直留在
# hourly-top-songs 里（见 DEMO.md「已知限制」）。演示前可选择清空。
#
# 整段清理必须在容器内一次跑完：读模型里可能有数万个键，
# 每个键起一次 kubectl exec / docker exec 会慢到不可用。
redis_sh() {
  if kubectl get pod redis-master-0 -n data >/dev/null 2>&1; then
    kubectl exec -n data redis-master-0 -- sh -c "$1" 2>/dev/null
  elif [ -n "$(docker compose -f "$COMPOSE_FILE" ps -q redis 2>/dev/null)" ]; then
    docker compose -f "$COMPOSE_FILE" exec -T redis sh -c "$1" 2>/dev/null
  else
    return 1
  fi
}

if [ "$DO_RESET" = "1" ]; then
  step "Clearing the Redis read model"
  before=$(redis_sh "redis-cli ZCARD hourly-top-songs" | tr -d '\r')
  if [ -z "$before" ]; then
    warn "Could not reach Redis (neither data/redis-master-0 on K8s nor the compose redis service); skipping"
  else
    n=$(redis_sh "redis-cli --scan --pattern 'completion:*' | wc -l" | tr -d '\r ')
    info "To clear: $before ranking members, ${n:-0} completion:* keys"
    redis_sh "redis-cli DEL hourly-top-songs >/dev/null;
              redis-cli --scan --pattern 'completion:*' | xargs -r -n 500 redis-cli DEL >/dev/null" \
      && ok "Read model cleared" \
      || warn "Cleanup returned non-zero; continuing"
    info "The read model is derived data. Clearing it does not affect the facts in Kafka"
    info "nor the Kafka Streams window state, so ranking counts are rewritten from the"
    info "existing 1-hour window totals rather than reset to zero."
  fi
fi

# ---------------------------------------------------------------- 灌数据
if [ "$REPORT_ONLY" = "0" ]; then
  step "Injecting demo traffic: a long-tail 'hot songs' distribution"
  info "12 tracks, popularity approximately Zipf; each track's completion propensity is independent of its popularity"
  python3 scripts/demo-traffic.py \
    --url "$INGESTION/events" \
    --query-url "$QUERY" \
    --sessions "$SESSIONS" \
    --expected-out "$RUN_DIR/expected.json" || warn "Some events failed to send; results may be short"

  step "Waiting for the pipeline to catch up"
  info "Kafka Streams updates the aggregates per record; query-service then writes them to Redis"
  sleep 6
fi

[ -f "$RUN_DIR/expected.json" ] || die "$RUN_DIR/expected.json not found. Run once without --report-only first"

# ---------------------------------------------------------------- 出结果
python3 scripts/demo-report.py \
  --query-url "$QUERY" \
  --expected "$RUN_DIR/expected.json" \
  $NO_COLOR
RC=$?

# ---------------------------------------------------------------- 后续入口
cat <<EOF
${B}Where to look next${RESET}
────────────────────────────────────────────────────────────────────────
  Grafana business panels   http://localhost:3000  (admin / admin)
                            Dashboards → Music Streaming Analytics
  Prometheus targets        http://localhost:9090/targets
  Query one track           curl $QUERY/api/songs/neon-skyline/completion
  Ranking                   curl "$QUERY/api/songs/top?n=5"
  Refresh results only      ./scripts/demo.sh --report-only
  Inject more data          ./scripts/demo.sh --sessions 3000
  Clear read model, rerun   ./scripts/demo.sh --reset
  Tear down                 ./scripts/demo.sh --stop

  Guide: DEMO.md   Load test and failure drill report: docs/performance.md
EOF
echo
exit $RC
