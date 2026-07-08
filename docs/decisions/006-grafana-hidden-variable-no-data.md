# ADR 006: Grafana 隐藏变量 "All" 值在精确匹配下导致 No data

## Status

Resolved (partial — datasource binding fixed; hidden variable issue documented)

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

**数据源问题**：通过 Grafana API 批量替换 `${DS_PROMETHEUS}` 为实际 UID，永久修复。对应的 provisioning 配置中也为数据源显式指定了固定 UID（`uid: prometheus`），防止重建容器后 UID 变化。

**隐藏变量问题**：受影响的 panel 属于 I/O 详情和进程内存，对当前项目的可观测性目标不是必需项。暂时保持现状，主要关注 JVM 内存、GC、线程、CPU 这几个核心指标（均正常显示）。后续如需完整修复，方案为将相关变量的默认值从 `All` 改为具体值，或修改 panel 查询为正则匹配。

## Lessons

- 社区 dashboard 常带有不在页面上直接展示的隐藏变量，排查 No data 问题时不能只检查可见筛选框，需要进 Settings → Variables 查看完整列表。
- Grafana 变量的 `All` 值与等号精确匹配（`=`）不兼容，必须配合正则匹配（`=~`）使用，这是社区 dashboard 质量参差不齐时常见的坑。
- 导入社区 dashboard 时，若数据源变量绑定失败，优先通过 API 批量替换占位符，比逐个 panel 手动修改效率高得多。
