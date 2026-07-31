# ADR 007: Observations from Load Testing and Failure Drills

> English Version. Chinese version below.

## Status

Accepted (a record of observations, not defects requiring immediate remediation)

## Context

Five rounds of load testing and drills were run against the system: three throughput/latency rounds (k6, 50→200 / 600 / 1500 virtual users), one failure drill deleting the stream-processor pod, one scaling drill (re-run once after a partition skew problem was discovered), and one backpressure drill constraining consumer CPU. Five phenomena related to the system's design assumptions emerged and are worth recording. Full data is in `docs/performance.md`.

## Observation 1: `kubectl port-forward svc/X` does not follow pod recreation

**Symptom**: on the first attempt at the failure drill, deleting stream-processor with `kubectl delete pod` immediately broke the previously working `kubectl port-forward svc/stream-processor 8081:8081` (the Prometheus target went to `up=0`). For the whole drill window, Grafana and Prometheus could see no metrics at all from the new pod, including the consumer lag that mattered most. The stream-processor heap curve in `round3-failure-drill-jvm-kafka.jpg` breaks off after 15:45, which is direct evidence of this.

**Cause**: when `kubectl port-forward` is started against a Service, it resolves a single backend Pod IP at that moment and forwards directly to it, rather than continuously load-balancing or re-resolving through the Service. Once the old Pod is deleted, the forwarding pipe is dead and `port-forward` must be restarted manually to attach to the new Pod.

**Workaround used**: this drill switched to `kubectl exec` into the Kafka container and polled real lag with `kafka-consumer-groups.sh --describe`, bypassing the dependency on port-forward and yielding a complete lag recovery curve (see performance.md).

**If this were to be made permanent**: a supervisor script could detect the port-forward process exiting and restart it automatically; alternatively, Prometheus could be deployed inside the cluster and scrape through Service DNS, removing the dependency on host-side port-forwarding entirely. Neither implementation is elaborated here; the directions are recorded only.

## Observation 2: one anomalous long-tail request (8m50s) during the failure drill

**Symptom**: in the load-test window in which the stream-processor pod was killed, the k6 summary reported `http_req_duration max=8m50s` — at least one POST /events request to `ingestion-service` took nearly nine minutes to return, though it still returned 202 and was not counted in `http_req_failed`.

**Revised analysis after reviewing the raw output**: going line by line through the `running (Xm YY.Ys)` progress lines that k6 prints in `round3-k6-output.txt`, the line after `5m29.0s` (during ramp-down, with a single VU finishing up) jumps straight to `14m20.3s`. There is no progress output at all for roughly 8m51s in between, after which two lines are printed consecutively with the same timestamp. That gap matches `max=8m50s` closely.

The key point is that k6's progress output is driven by a per-second timer independent of any particular HTTP request, and is unaffected by whether a given VU or connection is blocked (output appeared every second while the VU count fell from 150 to 6). If a single TCP connection had merely been blocked by network jitter, progress output should not have stopped simultaneously for nearly nine minutes. The phenomenon looks much more like **the k6 process itself (or the WSL2 environment hosting it) being suspended for a period** than a single request genuinely taking nine minutes while the system operated normally.

Furthermore, this freeze occurred near the end of the run (5m29s, the wind-down phase of a 5m30s test), whereas the stream-processor pod was deleted 20 seconds after the test began — more than five minutes apart, which does not line up. That weakens the original hypothesis that "CNI network jitter triggered by `kubectl delete pod` blocked this connection": had that been the cause, the symptom should have appeared around t≈20s rather than near the end. As corroboration, a separate, unrelated load test (`round3-failure-drill.txt`, 300 VUs at full load, not part of this failure drill) shows a similar progress freeze of about 99s between `3m10.0s` and `4m49.1s`, and that run reported `http_req_duration max` of 1m38s, matching the freeze duration. This suggests the phenomenon originates in occasional process-level stalls of the local load-testing environment (WSL2/host) rather than in application-layer behaviour specific to the failure drill.

**A note on naming**: the filename `round3-failure-drill.txt` is misleading — it records the independent 300 VU load test described above, not the third failure drill itself. The k6 output from the failure drill is in `round3-k6-output.txt`.

**Impact**: observed once only; it affected neither the error rate (0% failed) nor the consumer lag recovery curve.

**Decision**: the goal of this round of testing was to establish a testing methodology and locate the throughput inflection point, so the root cause of this anomaly is not pursued further here. It is recorded as a lead to reproduce and investigate when production-grade SLA analysis is undertaken. The suspected cause has been revised from "CNI network jitter" to "more likely a process-level stall in the local environment", so that environmental noise is not misread as an application-layer or Kafka-layer defect.

## Observation 3: at very low CPU limits, JVM startup time is amplified disproportionately, with no `readinessProbe` as a safeguard

**Symptom**: to construct a realistic backpressure scenario, the stream-processor CPU limit was lowered from 500m to 100m (to one fifth). The startup time recorded at the time was 181 seconds, but that figure came from an ad hoc observation and was not archived with the raw data.

**Re-run for verification**: a controlled experiment was repeated, triggering recreation with `kubectl delete pod` and reading the real startup time from the Spring Boot `Started ... in X seconds` line in `kubectl logs`:

- **Normal CPU (500m limit, the current deployment configuration)**: startup took **31.3 seconds** (`Started StreamProcessorApplicationKt in 31.343 seconds`). This figure alone corrects the original claim in this Observation that startup "completes within a few seconds under normal conditions" — even without throttling, a Spring Boot plus Kafka Streams application cold-starts in this environment on the order of 30 seconds, not a few seconds.
- **Throttled CPU (100m limit)**: during polling, an anomalous gap of roughly 10 minutes appeared (with `kubectl get` polling every 10 seconds, the interval between the 8th poll at 16:45:15Z and the 9th at 16:55:14Z was about 10 minutes), the same class of WSL2/host process-level stall recorded in Observation 2. Affected by this stall, the two internal timings read back contradict each other: Spring Boot reported `Started in 764.151 seconds`, while the JVM process uptime on the same line was only `196.048s`. Such a relationship is semantically impossible — Spring's startup time cannot exceed the JVM's own lifetime — indicating that at least one of the clocks was contaminated by the stall, with no way to determine which. The archived file `round5-startup-timing.txt` records the judgement that "both numbers can only be treated as contaminated upper bounds".

  **No amplification factor is therefore given for this run.** What can be confirmed is the qualitative conclusion: throttling to 100m stretched startup from the normal 30-second scale to the order of minutes. The 181 seconds recorded ad hoc earlier is of the same order as the smaller of the two readings, which corroborates that the amplification effect is real, but producing a credible precise factor requires re-measurement without environmental stalls.

Raw data is in `docs/observability/perf-results/round5-startup-timing.txt` (poll-by-poll records, the full log lines from both the normal and throttled startups, and notes on the data-quality problem) and `round5-readiness-verify.txt` (the recreation drill after the probe took effect, including probe parameters and a cross-check of the timeline).

**Cause**: this is the classic amplification effect of a JVM under Kubernetes CFS quota throttling. Startup is inherently multi-threaded and bursty (class loading, Spring context initialisation, and Kafka Streams topology construction proceed in parallel), so the CPU quota within a 100ms scheduling period is exhausted almost immediately, the process is throttled, and it can only continue in the next period. Repeated over and over, actual wall-clock time is amplified far beyond the proportional reduction in CPU quota. The mechanism itself is well established; this particular measurement simply failed to produce a clean quantitative result.

**Related finding, now fixed**: `k8s/stream-processor.yaml` (along with `ingestion-service.yaml` and `query-service.yaml`) previously had no `readinessProbe`. As soon as the container process started, Kubernetes marked the pod `Ready`, while the Spring Boot application still needed considerably longer (on the order of 30 seconds under normal conditions, several minutes when throttled) before it could actually serve requests. A `readinessProbe` against `/actuator/health` has now been added to all three Deployments, and the same drill was measured before and after:

**Before the fix** (`round5-startup-timing.txt`, 2026-07-30 16:41:58Z; that record was originally intended to measure startup time, and since the probe had not yet been added, it also captured the pre-fix behaviour):

```
deleting pod: stream-processor-67bb89fb6b-cxcr9 at 2026-07-30T16:41:58+00:00
kubectl wait --for=condition=Ready pod -l app=stream-processor --timeout=180s
pod/stream-processor-67bb89fb6b-ljzn4 condition met
pod Ready at 2026-07-30T16:42:01+00:00, elapsed since delete: 3s
...
2026-07-30T16:42:35.086Z  Started StreamProcessorApplicationKt in 31.343 seconds
```

Kubernetes judged the new pod `Ready` **3 seconds** after the deletion, while the application in that same pod (`ljzn4`) did not finish starting until **34 seconds later**. Those 34 seconds constitute a false-Ready window: the pod is already in the Service endpoints, and requests arriving during it cannot be served.

**After the fix** (`round5-readiness-verify.txt`, 2026-07-31 18:09:05Z, running the same `kubectl delete pod` drill): the new pod remained `0/1 Not Ready` from `t+0` through `t+30` and turned `1/1` at `t+32`; the application reported `Started ... in 22.5 seconds`, which converts to `t+27.8s` relative to the deletion. Deriving from the probe parameters (the container enters Running at about `t+3`, `initialDelaySeconds: 10` places the first probe at `t+13`, and `periodSeconds: 5` governs subsequent ones), the first probe that could succeed falls at `t+28`, which differs from the observed `t+30`–`t+32` by 2 to 4 seconds — within the normal range of kubelet status propagation delay, so the two are consistent.

The startup times of the two runs, 31.3 seconds and 22.5 seconds, are both on a scale of tens of seconds, further confirming that this Observation's original phrasing of "starts within a few seconds" does not hold. The difference between the runs is ordinary variance for a cold JVM on single-node minikube; neither is a tuned benchmark result. As additional corroboration, the ReplicaSet hash of the post-fix pod changed from `67bb89fb6b` to `75cb6f4db`, showing that the probe was written into the pod template and actually applied to the cluster, not merely edited in a local file.

**Decision**: the CPU constraint used for this backpressure test was temporary and the quota was restored to the value declared in `k8s/stream-processor.yaml` afterwards. The missing `readinessProbe` has been implemented and verified and is no longer an open item.

## Observation 4: insufficient key cardinality in the test data limited consumer parallelism to 2

**Symptom**: `play-events` has 6 partitions, but during the scaling drill `kafka-consumer-groups.sh --describe` showed data only in partitions 0 and 5 (`LOG-END-OFFSET` of 679,022 and 452,071 respectively), while the `LOG-END-OFFSET` of the other four partitions remained 0 throughout.

**Cause**: `ingestion-service` uses `songId` as the Kafka message key, while the load-test script `scripts/load-test.js` used only 5 fixed songIds (song-A through song-E). Hashed by the default partitioner, those 5 keys landed on only 2 partitions.

**Impact**: effective consumer-side parallelism was 2, not 6. When scaled to 2 replicas, the apparently balanced "6 partitions split 3/3" was misleading: each instance happened to receive one partition carrying data, so the even split of load was a coincidence rather than a result of the assignment strategy. By the same token, the consumer throughput ceiling in the backpressure drill was also bounded by this parallelism.

**Decision**: the fix has been verified by re-running the load test. The songId generation in `scripts/load-test.js` was changed from 5 fixed values to `song-${__VU}-${__ITER % 300}` (300 distinct keys); after re-running, `kafka-consumer-groups.sh --describe` confirmed that all 6 partitions carried data and kept growing, so consumer-side parallelism can indeed use all 6 paths. Raw data is in `docs/observability/perf-results/round4-scaling-v2.txt`, and the relevant conclusions have been propagated to the fourth-round section of `docs/performance.md` and to its "two preconditions for interpreting the results". Note that the raw data for rounds 1–3 and 5 was still produced with the pre-fix script, so the "effective parallelism of 2" conclusion for those rounds is unaffected; only the fourth round was re-verified with the new script. This re-run incidentally surfaced Observation 5 (below), the most valuable finding of this testing effort.

## Observation 5: with real state present, scaling out does not immediately share load — by default a warm-up replica cycle of up to 10 minutes must elapse first

**Symptom**: after fixing the partition skew from Observation 4 so that all 6 partitions carried real data, the scaling drill was repeated (running `kubectl scale deployment stream-processor --replicas=2` while load-test traffic was in progress, scaling out at 15:55:45). Two `--describe` snapshots at 48 and 85 seconds after scaling showed all 6 partitions still handled by the original pod, with the new pod holding consumption rights on no partition at all; the new pod's log showed it had received `New standby tasks: [0_2, 0_0]`, not active tasks. The new pod completed its first join at 15:56:26, and a full 10 minutes later at 16:06:26 (`probing.rebalance.interval.ms` defaults to 600000ms) a `triggered followup rebalance` occurred, after which the new pod received `New active tasks: [0_2, 0_0]` and `--describe` confirmed that consumption of partitions 0 and 2 had formally transferred, with the old pod retaining 1, 3, 4, and 5 — a 4/2 assignment, not the 3/3 split seen in the first attempt (with empty state).

**Key timeline**: a single k6 run lasts 5 minutes 30 seconds; extrapolating from the growth rate of each partition between the two snapshots, the load ended between 15:59 and 16:00, whereas the active handover occurred at 16:06:26, roughly 6 to 7 minutes after the load ended. The `--describe` at 16:11:17 showed lag of 0 on all 6 partitions with offsets no longer advancing. Therefore, **by the time the new instance took ownership there was no live traffic on the topic, and it never processed a single load-test message**. What this round demonstrates is that ownership does eventually transfer correctly, not that the two instances shared the load.

**Cause**: the project does not configure `num.standby.replicas`, so what was triggered is the Kafka Streams default warm-up replica mechanism (KIP-441). When the partitions to be migrated have already accumulated real state (historical data in the RocksDB store), a new instance does not take active ownership immediately. It first warms the state up as a standby until it is within `acceptable.recovery.lag`, and the actual active handover waits for the next probing rebalance. This contrasts directly with the first attempt (`round4-scaling.txt`, where state was close to empty) in which the new pod received active tasks directly within about 30 seconds of scaling out — the differing variable being whether the migrating partitions carried real state.

**Impact**: the intuition that "a new instance shares load immediately after scaling out" holds only when the state of the target partitions is close to empty. Once real historical state has accumulated (almost always the case in production), the default configuration leaves a window of up to 10 minutes between `kubectl scale` and the new instance actually processing traffic; during that window the new instance is merely warming state in the background and shares no load at all. In this drill the window was longer than the entire 5-minute-30-second load test, meaning **the scale-out performed to relieve this load had no effect on it whatsoever**. If a scale-out is intended to absorb a traffic spike and that spike is shorter than the warm-up cycle, the scale-out will not take effect before the spike is over.

**Decision**: neither `probing.rebalance.interval.ms` nor `num.standby.replicas` is being changed; this default behaviour is simply recorded as observed. Genuine elastic scaling (for example HPA-driven scale-out to absorb traffic spikes) would require assessing in advance whether this 10-minute window is acceptable. The available levers include reducing `probing.rebalance.interval.ms` (faster handover, but more frequent rebalances and more jitter) or relaxing `acceptable.recovery.lag` (the new instance qualifies to take over sooner, but its state may be less current at handover). Raw data (k6 output, multiple `--describe` snapshots, and the full lifecycle log of the new pod) is in `docs/observability/perf-results/round4-scaling-v2.txt`.

## Lessons

- A local monitoring path built on `kubectl port-forward` breaks along with the target during a pod-deletion drill, taking the monitoring itself down. The dependency chain of the monitoring setup must be understood before such a drill, or an observation method that does not depend on port-forward should be used instead (such as querying directly via `kubectl exec`).
- An isolated long-tail anomaly during load testing or a failure drill does not necessarily indicate a defect in the system; it may originate in the limitations of the local development environment (single-node minikube, port-forwarded traffic). When reporting figures, application-layer behaviour must be distinguished from local environmental noise, so that noise is not interpreted as a product defect.
- Verifying the classic backpressure scenario of "consumer slower than producer" may not be achievable by increasing load-test concurrency alone. If the bottleneck already sits on the producer side (here the HTTP layer of ingestion-service saturates first), pressure never propagates to the consumer. Actively constraining consumer resources (here reducing the CPU limit to one fifth) is required to reproduce backpressure genuinely, and doing so also demonstrated that the system still recovers on its own with reduced compute capacity.
- The key distribution of load-test data determines the parallelism that can actually be verified. The partition count is only an upper bound on parallelism; key cardinality is what takes effect. This should be confirmed when designing load-test data, otherwise the system's verified scaling capability is easily overstated.
- "New instances share load immediately after scaling out" is an intuition that holds only with empty state. The Kafka Streams warm-up replica mechanism inserts a warm-up period of up to 10 minutes by default when real state exists, and this window must be taken into account when designing elastic scaling policies or estimating how quickly scaling out can relieve pressure; changing a replica count does not take effect immediately as a matter of course.

---

# ADR 007: 压测与故障演练观察记录

## Status

Accepted（观察记录，非需要立即修复的缺陷）

## Context

对系统做了五轮压测与演练：三轮吞吐/延迟压测（k6，50→200 / 600 / 1500 并发）、一次删除 stream-processor pod 的故障演练、一次扩缩容演练（后因发现分区倾斜问题重跑过一次），以及一次限制消费者 CPU 的背压演练。过程中发现五个与系统设计假设相关、值得记录的现象。完整数据见 `docs/performance.md`。

## Observation 1: `kubectl port-forward svc/X` 不会跟随 pod 重建

**现象**：故障演练第一次尝试时，`kubectl delete pod` 删除 stream-processor 后，原本正常抓取的 `kubectl port-forward svc/stream-processor 8081:8081` 立刻失效（Prometheus target 变为 `up=0`），导致整个演练窗口内 Grafana/Prometheus 完全看不到新 pod 的任何指标，包括最关心的 consumer lag。`round3-failure-drill-jvm-kafka.jpg` 里 stream-processor 的堆内存曲线在 15:45 之后中断，即这一现象的直接证据。

**原因**：`kubectl port-forward` 面向 Service 启动时，会在启动那一刻解析出一个具体的后端 Pod IP 并直接转发到它，而不是持续通过 Service 做负载均衡/重新解析。旧 Pod 一旦被删除，这条转发管道就是死的，必须手动重启 `port-forward` 才能接上新 Pod。

**应对**：本次演练改用 `kubectl exec` 进 Kafka 容器直接跑 `kafka-consumer-groups.sh --describe` 轮询真实 lag，绕开了对 port-forward 的依赖，拿到了完整的 lag 恢复曲线（见 performance.md）。

**后续如果要长期化**：可以写一个 supervisor 脚本，检测 port-forward 进程退出后自动重启；或者把 Prometheus 部署进集群内、直接用 Service DNS 抓取，从根本上不依赖宿主机侧的 port-forward。两种方案的具体实现在此不做展开，仅记录方向。

## Observation 2: 故障演练期间出现一次异常长尾请求（8m50s）

**现象**：在杀 stream-processor pod 的压测窗口里，k6 汇总报告显示 `http_req_duration max=8m50s`，即有至少一个对 `ingestion-service` 的 POST /events 请求耗时近 9 分钟才返回，但最终仍是 202（未计入 `http_req_failed`）。

**复查原始输出后的修正分析**：逐行核对 `round3-k6-output.txt` 里 k6 自己打印的 `running (Xm YY.Ys)` 进度行，发现在 `5m29.0s`（ramp-down 阶段，只剩 1 个 VU 收尾）之后，下一行直接跳到 `14m20.3s`，中间约 8m51s 没有任何进度输出，随后两行以同一时间戳连续打印。该空档与 `max=8m50s` 基本吻合。

关键点：k6 的进度打印是独立于任何具体 HTTP 请求的秒级定时器，与某一个 VU 或连接是否阻塞无关（VU 数从 150 递减到 6 的过程中，每秒都有正常输出）。如果只是某一条 TCP 连接被网络抖动阻塞，进度输出不应该同时停止近 9 分钟。这个现象更接近于 **k6 进程本身（乃至所在的 WSL2 环境）被挂起了一段时间**，而不是单一请求在系统正常运行的情况下真的耗时 9 分钟。

另外，这次冻结发生在压测尾声（5m29s，即 5m30s 压测的收尾阶段），而删除 stream-processor pod 是在压测开始后第 20 秒——两者相差 5 分多钟，时间上对不上。这削弱了"由 `kubectl delete pod` 引发的 CNI 网络抖动阻塞了这条连接"这一原始猜测：若真是该原因，现象应出现在 t≈20s 附近，而非临近结束时。作为旁证，另一次独立压测（`round3-failure-drill.txt`，300 VU 满载，与本轮故障演练无关）在 `3m10.0s → 4m49.1s` 之间也出现过一次约 99s 的类似进度冻结，且该次运行的 `http_req_duration max` 为 1m38s，与冻结时长吻合。这说明现象更可能来自本地压测环境（WSL2/宿主机）偶发的进程级卡顿，而非故障演练特有的应用层行为。

**命名提示**：`round3-failure-drill.txt` 这个文件名容易误导——它记录的是上述 300 VU 的独立压测，并非第三轮故障演练本身；故障演练的 k6 输出在 `round3-k6-output.txt`。

**影响**：仅观察到 1 次，不影响错误率（0% failed），也不影响 consumer lag 的恢复曲线。

**Decision**：本轮压测的目标是建立测试方法论并定位吞吐拐点，该异常的根因在此不做深究，仅记录为后续做生产级 SLA 分析时需要复现和排查的线索。根因方向已从"CNI 网络抖动"修正为"更可能是本地环境的进程级挂起"，以避免把环境噪音误判成应用层或 Kafka 层面的缺陷。

## Observation 3: CPU limit 极低时，JVM 启动时间会被不成比例地放大，且没有 `readinessProbe` 兜底

**现象**：为了构造真实背压场景，把 stream-processor 的 CPU limit 从 500m 降到 100m（降到 1/5）。当时记录的启动耗时是 181 秒，但这个数字取自临时观察，没有随原始数据归档。

**重跑验证**：补做了一次对照实验，`kubectl delete pod` 触发重建，从 `kubectl logs` 里 Spring Boot 的 `Started ... in X seconds` 行读取真实启动耗时：

- **正常 CPU（500m limit，当前部署配置）**：启动耗时 **31.3 秒**（`Started StreamProcessorApplicationKt in 31.343 seconds`）。这个数字本身就纠正了本 Observation 最初"正常情况下几秒内即可启动完成"的说法——即使不限流，Spring Boot + Kafka Streams 应用在这个环境下的冷启动也要 30 秒量级，不是几秒。
- **限流 CPU（100m limit）**：轮询过程中出现了一次约 10 分钟的异常空档（`kubectl get` 每 10 秒一次的轮询，第 8 次 16:45:15Z 到第 9 次 16:55:14Z 之间实际隔了约 10 分钟），与 Observation 2 记录的 WSL2/宿主机进程级卡顿是同一类现象。受这次卡顿影响，读到的两个内部计时相互矛盾：Spring Boot 自报的 `Started in 764.151 seconds`，而同一行里的 JVM 进程 uptime 只有 `196.048s`。这两个数字在语义上不可能出现这种大小关系——Spring 的启动耗时不可能超过 JVM 自身的存活时长——说明至少有一个时钟已被这次卡顿污染，且无法判断被污染的是哪一个。归档文件 `round5-startup-timing.txt` 中对此的判断是"两个数字都只能当作被污染的上界"。

  **因此本次不给出放大倍数。** 能确认的是定性结论：限流到 100m 后，启动耗时从正常的 30 秒量级拉长到分钟量级。原先临时记录的 181 秒与两个读数中较小的那个量级相当，可以佐证放大效应真实存在，但要给出可信的精确倍数，需要在没有环境卡顿的条件下重新测量。

原始数据见 `docs/observability/perf-results/round5-startup-timing.txt`（轮询逐条记录、正常/限流两次启动的完整日志行、数据质量问题说明）与 `round5-readiness-verify.txt`（探针生效后的重建演练，含探针参数与时间线的推算核对）。

**原因**：这是 JVM 在 Kubernetes CFS quota 节流下的典型放大效应——启动阶段本身是多线程、突发性很强的（类加载、Spring 容器初始化、Kafka Streams 拓扑构建等并行发生），瞬间就会把 100ms 调度周期内的 CPU 配额用完，随即被限流，等到下一个周期才能继续；如此反复，实际墙钟时间被放大远超 CPU 配额的下降比例。这个机制本身是确定的，本次测量只是没能给出干净的量化结果。

**连带发现，已修复**：`k8s/stream-processor.yaml`（以及 `ingestion-service.yaml`、`query-service.yaml`）原先都没有配置 `readinessProbe`，容器进程一启动，K8s 立刻把 pod 标记为 `Ready`，但 Spring Boot 应用实际上还要再等（正常情况下也要 30 秒量级，限流时数分钟）才能真正处理请求。现已给三个 Deployment 都补上探测 `/actuator/health` 的 `readinessProbe`，并做了同一个演练的前后对照实测：

**修复前**（`round5-startup-timing.txt`，2026-07-30 16:41:58Z。该次记录的本意是测量启动耗时，此时探针尚未加入，因而一并保留了修复前的行为）：

```
deleting pod: stream-processor-67bb89fb6b-cxcr9 at 2026-07-30T16:41:58+00:00
kubectl wait --for=condition=Ready pod -l app=stream-processor --timeout=180s
pod/stream-processor-67bb89fb6b-ljzn4 condition met
pod Ready at 2026-07-30T16:42:01+00:00, elapsed since delete: 3s
...
2026-07-30T16:42:35.086Z  Started StreamProcessorApplicationKt in 31.343 seconds
```

K8s 在删除后 **3 秒**即将新 pod 判定为 `Ready`，而同一个 pod（`ljzn4`）的应用直到 **34 秒后**才启动完成。这 34 秒构成一个"假 Ready"窗口：pod 已被纳入 Service 的 endpoints，此时到达的请求无法得到处理。

**修复后**（`round5-readiness-verify.txt`，2026-07-31 18:09:05Z，执行同样的 `kubectl delete pod` 演练）：新 pod 自 `t+0` 至 `t+30` 始终为 `0/1 Not Ready`，至 `t+32` 转为 `1/1`；应用自报 `Started ... in 22.5 seconds`，换算到删除时刻为 `t+27.8s`。按探针参数推算（容器约在 `t+3` 进入 Running，`initialDelaySeconds: 10` 使首次探测落在 `t+13`，此后每 `periodSeconds: 5` 一次），首个可能成功的探测在 `t+28`，与观测到的 `t+30`~`t+32` 相差 2 至 4 秒，属于 kubelet 状态传播延迟的正常范围，两者一致。

两次运行的启动耗时分别为 31.3 秒和 22.5 秒，同处几十秒量级，进一步说明本 Observation 最初"几秒内即可启动完成"的表述不成立；两次之间的差异属于冷 JVM 在单节点 minikube 上的正常波动，均非调优后的基准测试结果。另有一项旁证：修复后 pod 的 ReplicaSet 哈希由 `67bb89fb6b` 变为 `75cb6f4db`，说明探针已写入 pod template 并实际应用到集群，而非仅修改了本地文件。

**Decision**：本次背压测试是临时的资源调整，测完已把 CPU 配额恢复到 `k8s/stream-processor.yaml` 声明的值。`readinessProbe` 缺口已实施并验证，不再是待办项。

## Observation 4: 压测数据的 key 基数不足，消费并行度被限制在 2

**现象**：`play-events` 有 6 个 partition，但扩缩容演练中 `kafka-consumer-groups.sh --describe` 显示，只有 partition 0 和 5 有数据（`LOG-END-OFFSET` 分别为 679,022 和 452,071），其余四个 partition 的 `LOG-END-OFFSET` 始终为 0。

**原因**：`ingestion-service` 以 `songId` 作为 Kafka 消息 key，而压测脚本 `scripts/load-test.js` 只使用 5 个固定 songId（song-A 至 song-E），这 5 个 key 经默认分区器散列后只落到 2 个 partition 上。

**影响**：消费侧的有效并行度是 2 而非 6。扩容到 2 副本时"6 个 partition 按 3/3 均分"看似均衡，实际每个实例各拿到一个有数据的 partition，负载对半分是巧合而非分配策略的结果；同理，背压演练中消费者的吞吐上限也受这一并行度约束。

**Decision**：已重跑压测验证修复。把 `scripts/load-test.js` 的 songId 生成方式从 5 个固定值改为 `song-${__VU}-${__ITER % 300}`（300 个不同 key），重新压测后 `kafka-consumer-groups.sh --describe` 确认 6 个 partition 全部有数据且持续增长，消费侧并行度确实能用满 6 路。原始数据见 `docs/observability/perf-results/round4-scaling-v2.txt`；相关结论已同步更新到 `docs/performance.md` 第四轮小节和"结果解读的两个前提"。注意：第一至三、五轮的原始数据仍是用修复前的脚本跑的，这几轮"有效并行度为 2"的结论不受影响，只有第四轮的重新验证用上了新脚本。这次重跑过程中意外牵出了 Observation 5（见下文），是本次压测收获最大的一个发现。

## Observation 5: 有真实状态时，扩容不会立刻分担负载——默认要等最多 10 分钟的 warm-up replica 周期

**现象**：修复 Observation 4 的分区倾斜、6 个 partition 都有真实数据后，重新做了一次扩容演练（压测流量持续期间执行 `kubectl scale deployment stream-processor --replicas=2`，扩容时刻 15:55:45）。扩容后 48 秒和 85 秒两次 `--describe`，6 个 partition 始终全部由原 pod 处理，新 pod 完全没有拿到任何 partition 的消费权；新 pod 日志显示它拿到的是 `New standby tasks: [0_2, 0_0]`，不是 active task。新 pod 于 15:56:26 完成首次 join，整整 10 分钟后的 16:06:26（`probing.rebalance.interval.ms` 默认 600000ms）才发生一次 `triggered followup rebalance`，之后新 pod 才拿到 `New active tasks: [0_2, 0_0]`，`--describe` 确认 partition 0、2 的消费权正式转移，旧 pod 保留 1、3、4、5——是 4/2 分配，不是第一次尝试（空状态）那样的 3/3 均分。

**关键时序**：k6 单次运行 5 分 30 秒，按各 partition 在两次快照之间的增长速率反推，负载在 15:59 至 16:00 之间结束；而 active 切换发生在 16:06:26，晚于负载结束约 6 至 7 分钟。16:11:17 的 `--describe` 显示 6 个 partition 的 lag 均为 0、offset 不再推进。因此，**新实例取得所有权时 topic 上已无实时流量，其全程未处理过任何一条压测消息**。本轮可以证明的是所有权最终会正确转移，而非两个实例分担了负载。

**原因**：项目没有配置 `num.standby.replicas`，触发的是 Kafka Streams 默认的 warm-up replica 机制（KIP-441）：当待迁移的 partition 已经积累了实际状态（RocksDB store 里有历史数据），新实例不会立刻拿到 active 所有权，而是先以 standby 身份把状态预热到 `acceptable.recovery.lag` 以内，真正的 active 切换要等下一次 probing rebalance。这和第一次尝试（`round4-scaling.txt`，当时状态接近空）扩容后约 30 秒内新 pod 就直接拿到 active task 的行为形成了直接对比——差异变量就是待迁移 partition 有没有实际状态。

**影响**：这意味着"扩容后新实例立刻分担负载"这个直觉只在目标 partition 状态接近空的时候成立。一旦有真实的历史状态积累（生产环境几乎总是如此），默认配置下从 `kubectl scale` 到新实例真正开始处理流量之间，有一个长达 10 分钟的窗口——这段时间里新实例只是在后台预热状态，完全不分担负载。本次演练里这个窗口比整场 5 分 30 秒的压测还长，也就是说**为缓解本次负载而做的扩容，对本次负载没有产生任何效果**。如果扩容动作是为了应对一段突发流量，而这段流量的持续时间短于 warm-up 周期，那么扩容在它结束之前都不会生效。

**Decision**：本次不修改 `probing.rebalance.interval.ms` 或 `num.standby.replicas`，只如实记录这个默认行为。如果后续要做真正的弹性伸缩（比如基于 HPA 自动扩容来应对突发流量），需要提前评估这 10 分钟窗口是否可接受；能调的方向包括调小 `probing.rebalance.interval.ms`（更快切换，但更频繁的 rebalance 会增加抖动）或放宽 `acceptable.recovery.lag`（新实例更快被判定为"够格接管"，但切换时状态可能不够新）。原始数据（k6 输出、多次 `--describe` 快照、新 pod 完整生命周期日志）见 `docs/observability/perf-results/round4-scaling-v2.txt`。

## Lessons

- 用 `kubectl port-forward` 搭建的本地监控链路，在做"删除 pod"这类故障演练时会连带中断监控自身——演练前需要理清监控的依赖链，或改用不依赖 port-forward 的观测手段（如直接 `kubectl exec` 查询）。
- 压测和故障演练中出现的孤立长尾异常，不必然意味着系统存在缺陷，也可能来自本地开发环境（minikube 单节点、port-forward 转发）本身的局限；报告数字时要区分应用层行为与本地环境噪音，避免把环境噪音当成产品缺陷解读。
- 要验证"消费者慢于生产者"的经典背压场景，仅靠加大压测并发未必足够——如果瓶颈本就在生产者一侧（本项目是 ingestion-service 的 HTTP 层先饱和），压力永远传导不到消费端。需要主动限制消费者的资源（本次是把 CPU limit 降到 1/5）才能真正复现背压，同时也验证了系统在算力被削弱的条件下仍能自愈。
- 压测数据的 key 分布决定了实际能验证到的并行度。partition 数只是并行度的上限，真正起作用的是 key 基数；设计压测数据时应先确认这一点，否则容易高估系统已验证的扩展能力。
- "扩容后立刻分担负载"是个只在空状态下成立的直觉。Kafka Streams 的 warm-up replica 机制会在有真实状态时插入一个默认最长 10 分钟的预热期，这个窗口在设计弹性伸缩策略、或是估算扩容能多快缓解压力时必须纳入考虑，不能想当然认为改个 replica 数就能马上见效。
