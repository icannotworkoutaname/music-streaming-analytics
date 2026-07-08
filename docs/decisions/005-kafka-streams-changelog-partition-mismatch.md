# ADR 005: Kafka Streams Consumer Group 无法注册排查

## Status

Resolved

## Context

接入 Prometheus 可观测性后发现 stream-processor 的 `windows_emitted_total` 指标持续无数据。检查 Kafka consumer group 列表：

```
kafka-consumer-groups.sh --list
# 输出只有 query-service，没有 stream-processor
```

Consumer group 从未注册成功，stream-processor 实际上没有消费任何消息。

## Investigation

**初始假设（错误）**：Kafka Broker 是 4.0.0，默认启用 KIP-848 新 consumer group 协议；Kafka Streams 客户端是 3.9.2（从 metrics 的 `kafka_version` 字段确认），仍使用 classic 协议。据此推测是协议不兼容导致无法加入 consumer group，为此加了 `group.protocol: classic` 配置。

**实际日志**：查看 stream-processor 启动日志后发现 consumer group join 本身是成功的，真正的报错是：

```
rebalance failed due to 'Existing internal topic
stream-processor-hourly-play-counts-changelog
has invalid partitions: expected: 6; actual: 1.'
```

## Root Cause

Kafka Streams 自动维护内部 changelog topic 用于窗口聚合状态的容错恢复。这些 topic 的分区数必须与输入 topic 严格一致。

早期运行时 `play-events` topic 只有 1 个分区，changelog topic 随之建立为 1 个分区。后来 `play-events` 扩展到 6 个分区，但 changelog topic 没有同步清理，Kafka Streams 在 rebalance 阶段检测到分区数不一致，直接拒绝处理并退出，导致 consumer group 始终注册不上。

## Decision

手动删除所有残留的 Kafka Streams 内部 topic，重启 stream-processor 后由 Kafka Streams 按当前分区数重新建立：

```bash
kafka-topics.sh --delete --topic stream-processor-completion-stats-changelog
kafka-topics.sh --delete --topic stream-processor-hourly-play-counts-changelog
```

同时撤销错误加入的 `group.protocol: classic` 配置，该配置与真正问题无关。

## Lessons

- 表面现象（consumer group 不存在）容易被归因于协议不兼容，但日志才是根因的直接证据。先看日志再下结论，而不是凭版本号推断。
- 修改输入 topic 的分区数后，必须同步清理该 application-id 下所有 Kafka Streams 内部 topic，否则下次启动会因分区数校验失败而无法运行。
- Kafka Streams 内部 topic 命名规则：`{application-id}-{store-name}-changelog`，可通过此规则提前预判受影响的 topic。
