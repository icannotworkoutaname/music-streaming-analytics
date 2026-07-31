> English Version. Chinese version below.
# Demo Guide

One command exercises the whole system: it injects a batch of popularity-skewed play
events, waits for the pipeline to finish computing, and compares the results returned by
the query API against the expected values recorded at injection time, row by row.

```bash
./scripts/demo.sh
```

On a first run with nothing else running locally, the script starts the dependencies
defined in `docker-compose.dev.yml` (Kafka, Redis, PostgreSQL, Prometheus, Grafana) along
with the three Spring Boot services. If `localhost:8080` and `localhost:8082` are already
reachable, it reuses the existing deployment instead — which covers both a local
docker-compose setup and a minikube `kubectl port-forward`.

## What the demo does

```
demo-traffic.py ──HTTP──▶ ingestion-service ──▶ Kafka: play-events
                                                     │
                                            stream-processor
                              (5-min completion window / 1-hour ranking window)
                                                     │
                             Kafka: song-completion-stats / song-hourly-counts
                                                     │
                                              query-service ──▶ Redis
                                                     │
                 demo-report.py ──HTTP──▶ query API ──▶ compare against expectations
```

The demo data is not uniformly random; it is deliberately skewed in two ways:

- **Popularity skew**: the play weights of the 12 tracks approximate a Zipf distribution,
  with the top track taking about a third of all plays and the tail tracks reaching only
  single digits. This is the basic shape of real music traffic.
- **Completion propensity is independent of popularity**: each track is assigned its own
  probability of being played past 90%, unrelated to how popular it is.

The second point is what the demo is built around. A completed run shows:

```
Most played:          neon-skyline (179 plays)
Highest completion:   paper-boats (94.8%, 58 plays)
Lowest completion:    static-bloom (50.0%, 58 plays)
(6 long-tail tracks below 20 plays are excluded from the ranking; their completion
 rates carry little statistical weight)
```

The ranking skips long-tail tracks with fewer than 20 plays (adjustable via `--min-plays`).
Three completions out of three plays is a completion rate of 100%, and putting a number
like that into a conclusion is only misleading. Long-tail tracks still appear in the table;
they are simply excluded from the "highest / lowest" selection.

All script output is in English so the demo can be run in an English-speaking setting;
the Chinese original of this document and the comments inside the scripts remain in Chinese.

The most-played track is not the track with the highest completion rate. That is exactly
why [ADR 003](docs/decisions/003-completion-rate-definition.md) defines completion rate as
a separate business metric: play count says how many people a track was put in front of,
while completion rate says how many it actually held. The two measure different things.
In the demo, `amber-room` is the longest track in the set (401 seconds) and has the lowest
completion rate, reproducing the familiar effect that long tracks naturally complete less
often.

## Reading the output

**Top tracks** (`GET /api/songs/top`). `plays` comes from the Redis Sorted Set and is
measured over a **1-hour window**; the `injected` column is the volume pushed in by this
run. The two need not be equal — see the section on windows below.

**Completion rate** (`GET /api/songs/{songId}/completion`). Measured over a **5-minute
window**. The `expected` column comes from the data recorded at injection time, and the
`OK` at the end of each row means the `starts` and `completes` returned by the query API
match the expectation exactly.

**Verification**. When everything matches, the output is:

```
OK All 12 tracks match the expected starts / completes exactly.
   The end-to-end path (HTTP -> Kafka -> Kafka Streams windowed aggregation ->
   Kafka -> Redis -> query API) computes correct results.
```

This step is where the practical value of the demo lies: what it verifies is not that
"numbers came out", but that the results computed across four components equal the data
that was injected.

## About the two windows

The system has two windows of different sizes, and understanding them explains nearly
every apparent mismatch in the demo output:

| Metric | Window | Grace period |
|---|---|---|
| Completion rate | 5-minute tumbling window | 1 minute |
| Top-songs ranking | 1-hour tumbling window | 5 minutes |

Two visible consequences follow:

**Repeated runs within the same 5-minute window accumulate completion counts.** Until the
window closes, the aggregate keeps accumulating. `demo-traffic.py` therefore queries the
current window's existing `starts`/`completes` as a baseline before injecting, and computes
the expectation as "baseline + this injection". The comparison is then exact no matter how
many times the demo has been run.

**The ranking's play counts are usually larger than the volume injected by one run.** It
uses the 1-hour window, so multiple runs within the same hour accumulate. This is not an
error; it is a direct consequence of the two metrics using different window sizes.

Before injecting, the script also checks the time remaining in the current 5-minute window:
if less than 60 seconds remain, it waits for the next window to begin, and it constrains
the jitter range of event timestamps to stay within the window boundary. Otherwise a batch
of events would be split across two windows and what remained in Redis would be the partial
result of the later one.

## Common options

```bash
./scripts/demo.sh                  # default: 600 play sessions, roughly 1200 events
./scripts/demo.sh --sessions 3000  # larger data volume
./scripts/demo.sh --reset          # clear the Redis read model before injecting
./scripts/demo.sh --report-only    # inject nothing, just re-run the queries
./scripts/demo.sh --skip-infra     # services already running, skip the startup steps
./scripts/demo.sh --stop           # stop the services and containers this script started
./scripts/demo.sh --no-color       # disable ANSI colour, for redirecting to a file
```

`demo-traffic.py` uses a fixed random seed (`--seed`, default 20260731), so the same
parameters generate identical data every time, which keeps runs reproducible.

## What to look at afterwards

| Entry point | Address |
|---|---|
| Grafana business dashboard | http://localhost:3000 → Dashboards → Music Streaming Analytics (admin / admin) |
| Grafana JVM dashboard | as above → JVM (Micrometer) |
| Prometheus targets | http://localhost:9090/targets |
| Completion rate for one track | `curl http://localhost:8082/api/songs/neon-skyline/completion` |
| Ranking | `curl "http://localhost:8082/api/songs/top?n=5"` |

Grafana's Events Ingested Rate panel shows the rate curve during injection, separated into
the three event types `PLAY_START`, `PLAY_END`, and `SKIP`.

## Known limitations

The following are genuine boundaries of the current implementation. The demo exposes them
directly, so they are stated here as they are.

**The ranking Sorted Set is never trimmed.** After consuming a window result,
`query-service` only performs `ZADD` and never removes members. Tracks from expired windows
stay in the `hourly-top-songs` key permanently — in an environment where load tests have
been run, this key accumulates tens of thousands of members from load-test data, pushing the
demo data down the ranking. `demo-report.py` filters out the tracks injected by the current
run and reports the number of leftover entries; `--reset` clears the key. The proper fix is
window-based expiry logic or a TTL on the ZSET, which is not currently implemented.

**Clearing the read model does not reset the aggregation state.** `--reset` deletes keys in
Redis, whereas the Kafka Streams window state lives in RocksDB and in the changelog topics.
After clearing, the next window update writes the existing 1-hour cumulative values back
into Redis, so the ranking does not return to zero. This is a direct demonstration that a
CQRS read model can be rebuilt from the source of truth, not a defect.

**The demo's throughput is not a measure of system performance.** `demo-traffic.py` uses the
synchronous HTTP client from the Python standard library and opens a separate connection per
request, measuring around 100 events/s. That figure is bounded by the client and by
`kubectl port-forward` and says nothing about the capability of the system itself. For the
system's actual throughput, latency distribution, failure recovery, and backpressure
behaviour, see [docs/performance.md](docs/performance.md).

**`SKIP` events do not participate in the completion rate.** Per the definition in ADR 003,
the denominator is the number of `PLAY_START` events and the numerator is the number of
`PLAY_END` events past the 90% position; both `SKIP` and `PLAY_PROGRESS` are excluded. Some
incomplete plays in the demo data are reported as `SKIP`, so a track's `PLAY_END` count can
be lower than its `starts`.

## Troubleshooting

**Services fail to start.** Logs for the three services started by the script are in
`/tmp/music-streaming-demo/<service-name>.log`. The first run needs Gradle to download
dependencies and build, which can take several minutes.

**Completion rate shows "no data in Redis".** The aggregation results have not been written
yet; wait a few seconds and run `--report-only` again.

**Running the demo on Kubernetes.** Start the two port-forwards first, then use
`--skip-infra`:

```bash
kubectl port-forward svc/ingestion-service 8080:8080 &
kubectl port-forward svc/query-service 8082:8082 &
./scripts/demo.sh --skip-infra
```

Not forwarding stream-processor's port 8081 does not affect the demo; the script only
prints a notice.

---

# 演示指南

一条命令跑通整个系统：注入一批带热度倾斜的播放事件，等流水线算完，再把查询接口
返回的结果和注入时记录的预期值逐条比对。

```bash
./scripts/demo.sh
```

首次运行如果本地没有服务在跑，脚本会自行拉起 `docker-compose.dev.yml` 里的依赖
（Kafka、Redis、PostgreSQL、Prometheus、Grafana）和三个 Spring Boot 服务；如果检测到
`localhost:8080` 与 `localhost:8082` 已经可用，则直接复用现有部署——本地 docker-compose
和 minikube 的 `kubectl port-forward` 两种情况都适用。

## 演示做了什么

```
demo-traffic.py ──HTTP──▶ ingestion-service ──▶ Kafka: play-events
                                                     │
                                            stream-processor
                                       （5 分钟完成率窗口 / 1 小时排行窗口）
                                                     │
                             Kafka: song-completion-stats / song-hourly-counts
                                                     │
                                              query-service ──▶ Redis
                                                     │
                          demo-report.py ──HTTP──▶ 查询 API ──▶ 与预期值比对
```

演示数据不是均匀随机的，而是刻意造成两种倾斜：

- **热度倾斜**：12 首歌的播放权重近似 Zipf 分布，头部一首歌占掉约三分之一的播放量，
  尾部几首只有个位数。这是真实音乐流量的基本形态。
- **完成倾向与热度无关**：每首歌单独设定"听到 90% 以上的概率"，与它的热度无关。

第二点是这个演示的重点。跑完之后会看到：

```
Most played:          neon-skyline (179 plays)
Highest completion:   paper-boats (94.8%, 58 plays)
Lowest completion:    static-bloom (50.0%, 58 plays)
(6 long-tail tracks below 20 plays are excluded from the ranking; their completion
 rates carry little statistical weight)
```

排名会跳过播放量低于 20 次的长尾歌曲（`--min-plays` 可调）。3 次播放里完成 3 次就是
100% 完成率，这种数字放进结论里只会误导；长尾歌曲的完成率仍会在表格中列出，只是不参与
"第一/最低"的评选。

脚本的所有输出为英文，便于在英文环境下演示；本文档为中英双语（英文版在文件开头），
脚本内的注释保持中文。

播放量最高的歌不是完成率最高的歌。这正是 [ADR 003](docs/decisions/003-completion-rate-definition.md)
把完成率单独定义成一个业务指标的理由：点击量说明歌被推荐到了多少人面前，完成率说明
内容真正留住了多少人，两者衡量的不是同一件事。演示里 `amber-room` 是全场最长的曲目
（401 秒）且完成率最低，也复现了"长曲目完成率天然偏低"这一常见现象。

## 输出怎么读

**Top tracks**（`GET /api/songs/top`）。`plays` 取自 Redis Sorted Set，统计口径是
**1 小时窗口**；`injected` 列是这一次灌进去的量。两者不一定相等，见下面的窗口说明。

**Completion rate**（`GET /api/songs/{songId}/completion`）。统计口径是 **5 分钟窗口**。
`expected` 列来自注入时记录的数据，每行末尾的 `OK` 表示查询接口返回的 `starts` 和
`completes` 与预期完全一致。

**校验结果**（Verification）。全部一致时会输出：

```
OK All 12 tracks match the expected starts / completes exactly.
   The end-to-end path (HTTP -> Kafka -> Kafka Streams windowed aggregation ->
   Kafka -> Redis -> query API) computes correct results.
```

这一步是演示的实际价值所在——它验证的不是"有数字出来了"，而是这条跨越四个组件的
链路算出来的结果等于灌进去的数据。

## 关于两个窗口

系统里有两个尺寸不同的窗口，理解它们能解释演示输出中几乎所有"看起来对不上"的地方：

| 指标 | 窗口 | grace period |
|---|---|---|
| 完成率 | 5 分钟滚动窗口 | 1 分钟 |
| 热门排行 | 1 小时滚动窗口 | 5 分钟 |

由此带来两个可见现象：

**同一个 5 分钟窗口内重复运行，完成率会累加。** 窗口没关闭之前，聚合值持续累积。
所以 `demo-traffic.py` 在注入前会先查询当前窗口已有的 `starts`/`completes` 作为基线，
把预期值算成"基线 + 本次注入"。这样无论第几次运行，比对都是精确的。

**排行榜的播放量通常大于本次注入量。** 它用的是 1 小时窗口，同一小时内的多次运行都会
累进去。这不是错误，是两个指标窗口尺寸不同的直接结果。

脚本还会在注入前检查当前 5 分钟窗口的剩余时间：不足 60 秒时会等到下一个窗口开始再灌，
并把事件时间戳的抖动范围收敛在窗口边界之内。否则一批事件被拆到两个窗口里，Redis 中
留下的会是后一个窗口的部分结果。

## 常用参数

```bash
./scripts/demo.sh                  # 默认 600 个播放会话，约 1200 条事件
./scripts/demo.sh --sessions 3000  # 加大数据量
./scripts/demo.sh --reset          # 注入前清空 Redis 读模型
./scripts/demo.sh --report-only    # 不灌数据，只重新查询一次结果
./scripts/demo.sh --skip-infra     # 已有服务在跑，跳过启动步骤
./scripts/demo.sh --stop           # 停掉本脚本拉起的服务与容器
./scripts/demo.sh --no-color       # 关闭 ANSI 颜色，便于重定向到文件
```

`demo-traffic.py` 使用固定随机种子（`--seed`，默认 20260731），同样的参数每次生成的
数据完全一致，便于复现。

## 跑完之后可以看什么

| 入口 | 地址 |
|---|---|
| Grafana 业务面板 | http://localhost:3000 → Dashboards → Music Streaming Analytics（admin / admin） |
| Grafana JVM 面板 | 同上 → JVM (Micrometer) |
| Prometheus targets | http://localhost:9090/targets |
| 单曲完成率 | `curl http://localhost:8082/api/songs/neon-skyline/completion` |
| 排行榜 | `curl "http://localhost:8082/api/songs/top?n=5"` |

Grafana 的 Events Ingested Rate 面板会显示注入期间的速率曲线，按 `PLAY_START` /
`PLAY_END` / `SKIP` 三类事件分开。

## 已知限制

以下几点是当前实现的真实边界，演示时会直接暴露出来，此处如实说明。

**排行榜的 Sorted Set 不会裁剪。** `query-service` 消费到窗口结果后只做 `ZADD`，从不
删除成员。窗口过期的歌曲会永久留在 `hourly-top-songs` 键里——在做过压测的环境上，这个
键里会积累数万个来自压测数据的成员，把演示数据挤到排行榜后面。`demo-report.py` 会把
本次注入的歌曲单独筛出来展示，并提示遗留条目的数量；`--reset` 可以清空该键。
彻底的修法是给 ZSET 加上按窗口过期的清理逻辑或 TTL，目前没有实现。

**清空读模型不会重置聚合状态。** `--reset` 删除的是 Redis 里的键，而 Kafka Streams 的
窗口状态存在 RocksDB 和 changelog topic 里。清空之后下一次窗口更新会把 1 小时窗口的
既有累计值重新写回 Redis，排行榜不会归零。这是 CQRS 读模型可从事实数据重建的直接体现，
不是缺陷。

**演示的吞吐不代表系统性能。** `demo-traffic.py` 用的是 Python 标准库的同步 HTTP 客户端，
每条请求单独建连，实测约 100 events/s；这个数字受限于客户端和 `kubectl port-forward`，
与系统本身的能力无关。系统的实际吞吐、延迟分布、故障恢复与背压表现见
[docs/performance.md](docs/performance.md)。

**`SKIP` 事件不参与完成率计算。** 按 ADR 003 的定义，完成率的分母是 `PLAY_START` 数、
分子是位置超过 90% 的 `PLAY_END` 数，`SKIP` 和 `PLAY_PROGRESS` 都不计入。演示数据里
一部分未完成的播放上报的是 `SKIP`，因此某首歌的 `PLAY_END` 数会少于 `starts`。

## 排查

**服务起不来。** 脚本拉起的三个服务日志在 `/tmp/music-streaming-demo/<服务名>.log`。
首次运行需要 Gradle 下载依赖并构建，可能要几分钟。

**完成率显示"Redis 中无数据"。** 聚合结果还没写完，隔几秒执行 `--report-only` 再看一次。

**在 K8s 上演示。** 先把两个端口转发起来，再用 `--skip-infra`：

```bash
kubectl port-forward svc/ingestion-service 8080:8080 &
kubectl port-forward svc/query-service 8082:8082 &
./scripts/demo.sh --skip-infra
```

`stream-processor` 的 8081 不转发也不影响演示，脚本只会给一条提示。
