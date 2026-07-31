# ADR 006: Grafana Hidden Variables Whose "All" Value Yields No Data Under Exact Matching

> English Version. Chinese version below.

## Status

Resolved (partial — the datasource binding is fixed and persisted through provisioning; the hidden-variable issue is documented and intentionally left unfixed)

## Context

After importing a community JVM (Micrometer) dashboard, the two visible variables `Application` and `Instance` were both set correctly (ingestion-service / host.docker.internal:8080), yet every panel showed No data and the error `Data Source ($DS_PROMETHEUS) was not found` appeared on the panels. Querying Prometheus directly confirmed that the underlying metrics such as `jvm_memory_used_bytes` were entirely normal.

## Investigation

**Problem 1: the datasource variable was not bound**

Each panel in the dashboard references its datasource via `"uid": "${DS_PROMETHEUS}"`, the standard variable placeholder used by Grafana community dashboards. In a normal import, Grafana prompts the user to map that variable to an actual datasource, but in this import the variable value was not saved correctly, so the panels could not find a datasource.

Replacing every `${DS_PROMETHEUS}` in the dashboard JSON with the real datasource UID (`prometheus`) directly through the Grafana API removed the datasource error and restored data on some panels.

**Problem 2: exact matching against hidden variables (some panels still showed No data)**

After the datasource was fixed, the panels in the JVM Memory and JVM Misc sections had data, but Errors, Duration, and Utilisation under I/O Overview, along with JVM Process Memory, continued to show No data.

Inspecting dashboard Settings → Variables revealed that, besides the visible `application` and `instance`, there were three further variables — `jvm_memory_pool_heap`, `jvm_memory_pool_nonheap`, and one more — marked `Display: Hidden`, with the Grafana special value `All` as their default.

These hidden variables are used with exact equality matching in the panel queries:

```promql
jvm_memory_used_bytes{id="$jvm_memory_pool_heap", ...}
```

Grafana's special `All` value expands to "match all values" only under regex matching (`=~`). Under exact equality it is passed into PromQL as the literal string `All`, and since no time series with `id="All"` exists in Prometheus, the query returns nothing.

## Decision

**Datasource problem**: replace `${DS_PROMETHEUS}` with the real UID in bulk through the Grafana API. The corresponding provisioning configuration also assigns the datasource a fixed UID explicitly (`uid: prometheus`) so the UID does not change when the container is recreated.

**Hidden-variable problem**: the affected panels cover I/O detail and process memory, neither of which is required by the current observability goals of the project, so the situation is left as it stands. The focus remains on JVM memory, GC, threads, and CPU, all of which display correctly. A complete fix, if needed later, would involve changing the defaults of the relevant variables from `All` to concrete values, or rewriting the panel queries to use regex matching.

## Persistence problem: fixed

The datasource fix above was originally applied through the API to the database of a running Grafana instance, and the grafana service in `docker-compose.dev.yml` had no volume mounted at `/var/lib/grafana`. The community JVM dashboard had also been imported through the UI, so its JSON was not present in the repository under `observability/grafana/dashboards/`. The state at that point was therefore that recreating the container would discard both the imported dashboard and the fix.

This has now been addressed:

1. The fixed dashboard JSON was exported via `GET /api/dashboards/uid/<uid>` (confirming zero remaining `${DS_PROMETHEUS}` placeholders, with all datasources pointing at the fixed UID `prometheus`), stripped of its `meta` wrapper and instance-specific numeric `id`, and committed as `observability/grafana/dashboards/jvm-micrometer.json`, to be loaded automatically by the existing file provisioning (`observability/grafana/provisioning/dashboards/dashboards.yml`).
2. A persistent volume for `/var/lib/grafana` was added to the grafana service in `docker-compose.dev.yml`. An initial attempt using a bind mount (`./observability/grafana/data:/var/lib/grafana`) reproduced a well-known pitfall: a newly created mount directory on the host is owned by root by default, whereas the grafana container runs as the non-root user 472, producing `GF_PATHS_DATA is not writable` and an immediate container exit. Switching to a named volume (`grafana-data:`) let Docker manage ownership and the problem disappeared.

**Verification**: `observability/grafana/data` was deleted (clearing the directory left behind by the bind-mount attempt) and `docker compose up -d --force-recreate grafana` created a fresh named volume and a fresh SQLite database, equivalent to a first start after a clean clone. The results confirmed: both dashboards (`music-streaming`, `jvm-micrometer`) appeared automatically in `/api/search` through provisioning; the `jvm-micrometer` JSON contained zero remaining `DS_PROMETHEUS` placeholders; and querying `jvm_memory_used_bytes{job="stream-processor"}` through the Grafana datasource proxy returned real data (provided the relevant `kubectl port-forward` was running). In other words, after the container is recreated, the dashboards and the original fix are restored automatically with no manual steps.

## Lessons

- Community dashboards frequently carry hidden variables that are not shown on the page. When investigating No data, it is not enough to check the visible selectors; the full list must be inspected under Settings → Variables.
- Grafana's `All` variable value is incompatible with exact equality matching (`=`) and must be paired with regex matching (`=~`). This is a common issue when reusing community dashboards.
- When the datasource variable binding fails during a community dashboard import, replacing the placeholders in bulk through the API is far more efficient than editing each panel by hand.
- Grafana changes made through the UI or the API exist only in the instance database. A fix is reproducible only once the dashboard JSON is placed in the provisioning directory and committed to the repository.

---

# ADR 006: Grafana 隐藏变量 "All" 值在精确匹配下导致 No data

## Status

Resolved (partial — datasource binding fixed and persisted via provisioning; hidden variable issue documented, intentionally not fixed)

## Context

导入社区 JVM (Micrometer) dashboard 后，`Application` 和 `Instance` 两个可见变量均选择正确（ingestion-service / host.docker.internal:8080），但所有 panel 持续显示 No data，且面板上出现 `Data Source ($DS_PROMETHEUS) was not found` 错误。Prometheus 端直接查询确认底层 `jvm_memory_used_bytes` 等指标数据完全正常。

## Investigation

**问题一：数据源变量未绑定**

Dashboard 的每个 panel 通过 `"uid": "${DS_PROMETHEUS}"` 引用数据源，这是 Grafana 社区 dashboard 的标准变量占位符。正常导入流程中 Grafana 会弹窗要求用户将该变量映射到实际数据源，但此次导入后变量值未正确保存，面板找不到数据源。

直接通过 Grafana API 将 dashboard JSON 中所有 `${DS_PROMETHEUS}` 替换为实际数据源 UID（`prometheus`）后，datasource 报错消失，部分 panel 恢复数据。

**问题二：隐藏变量精确匹配失效（部分 panel 仍 No data）**

修复数据源后，JVM Memory 和 JVM Misc 区域的 panel 均有数据，但 I/O Overview 的 Errors、Duration、Utilisation 和 JVM Process Memory 持续 No data。

检查 dashboard Settings → Variables，发现除页面可见的 `application` 和 `instance` 外，还有 `jvm_memory_pool_heap`、`jvm_memory_pool_nonheap` 等 3 个 `Display: Hidden` 的隐藏变量，默认值为 Grafana 的特殊值 `All`。

这些隐藏变量在 panel 查询中使用等号精确匹配：
```promql
jvm_memory_used_bytes{id="$jvm_memory_pool_heap", ...}
```

Grafana 的 `All` 特殊值只在正则匹配（`=~`）下才展开为"匹配所有值"；在等号精确匹配下，`All` 被当作字面字符串传入 PromQL，Prometheus 中不存在 `id="All"` 的时间序列，查询返回空。

## Decision

**数据源问题**：通过 Grafana API 批量替换 `${DS_PROMETHEUS}` 为实际 UID。对应的 provisioning 配置中也为数据源显式指定了固定 UID（`uid: prometheus`），防止重建容器后 UID 变化。

**隐藏变量问题**：受影响的 panel 属于 I/O 详情和进程内存，不属于当前项目可观测性目标的必需项，因此保持现状，重点关注 JVM 内存、GC、线程、CPU 这几个核心指标（均正常显示）。后续如需完整修复，可将相关变量的默认值从 `All` 改为具体值，或把 panel 查询改为正则匹配。

## 持久化问题：已修复

上述数据源修复原本是通过 API 写入运行中 Grafana 实例的数据库完成的，`docker-compose.dev.yml` 里的 grafana 服务也没有为 `/var/lib/grafana` 挂载卷；这份社区 JVM dashboard 又是通过 UI 导入的，JSON 没有进入仓库的 `observability/grafana/dashboards/`。因此当时的状态是：容器一旦重建，导入的 dashboard 和上述修复都会一并丢失。

现已处理：

1. 通过 `GET /api/dashboards/uid/<uid>` 导出修复后的 dashboard JSON（确认其中 `${DS_PROMETHEUS}` 占位符为 0 处残留，datasource 已全部指向固定 UID `prometheus`），去掉 `meta` 包装和实例相关的数字 `id` 后提交为 `observability/grafana/dashboards/jvm-micrometer.json`，交由已有的 file provisioning（`observability/grafana/provisioning/dashboards/dashboards.yml`）自动加载。
2. `docker-compose.dev.yml` 给 grafana 服务加了 `/var/lib/grafana` 的持久化卷。最初尝试用 bind mount（`./observability/grafana/data:/var/lib/grafana`）复现了一个经典坑——宿主机上新建的挂载目录默认属主是 root，而 grafana 容器以非 root 的 472 用户运行，导致 `GF_PATHS_DATA is not writable`、容器直接退出。改用具名卷（`grafana-data:`）后 Docker 自己管理属主，问题消失。

**验证**：删除 `observability/grafana/data`（清掉上一次 bind mount 试验留下的目录）、`docker compose up -d --force-recreate grafana` 创建了一个全新的具名卷和全新的 SQLite 库（相当于全新 clone 后第一次启动）。确认结果：两个 dashboard（`music-streaming`、`jvm-micrometer`）都通过 provisioning 自动出现在 `/api/search` 里；`jvm-micrometer` 的 JSON 里 `DS_PROMETHEUS` 占位符残留数为 0；通过 Grafana 的 datasource 代理直接查询 `jvm_memory_used_bytes{job="stream-processor"}` 拿到了真实数据（前提是对应服务的 `kubectl port-forward` 是开着的）。也就是说，容器重建后不需要任何手动操作，dashboard 和当初的修复都会自动恢复。

## Lessons

- 社区 dashboard 常带有不在页面上直接展示的隐藏变量，排查 No data 问题时不能只检查可见筛选框，需要进 Settings → Variables 查看完整列表。
- Grafana 变量的 `All` 值与等号精确匹配（`=`）不兼容，必须配合正则匹配（`=~`）使用；这是引用社区 dashboard 时较常遇到的问题。
- 导入社区 dashboard 时，若数据源变量绑定失败，通过 API 批量替换占位符比逐个 panel 手动修改效率高得多。
- 通过 UI 或 API 做的 Grafana 变更只存在于实例数据库中。只有把 dashboard JSON 纳入 provisioning 目录并提交到仓库，修复才是可复现的。
