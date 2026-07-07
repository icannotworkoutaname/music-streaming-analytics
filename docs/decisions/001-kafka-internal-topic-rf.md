# ADR 001: Single-broker Kafka 的 Internal Topic 副本因子

## Status

Accepted

## Context

Day 2 在 K8s（minikube）里用 Bitnami Kafka Helm chart 部署单节点 Kafka，起好后消费者组一直用不了。排查发现是 `__consumer_offsets` 这个 Kafka 内部系统 topic 的副本因子问题。

Kafka 除了业务 topic 外，还有几个特殊的内部系统 topic（如 `__consumer_offsets`、`__transaction_state`），它们的副本因子由独立的配置项控制，不跟随普通 topic 的 `default.replication.factor`。`__consumer_offsets` 默认副本因子是 3，但单节点部署只有 1 个 broker，无法凑齐3个副本，导致这个 topic 无法正常创建，consumer group 因此无法工作。

Bitnami chart 提供了 `offsetsTopicReplicationFactor` 这个参数用来覆盖默认值，但实际部署时这个参数**没有生效**——推测是 chart 版本迭代过程中 values 的传递路径发生了变化，导致设置没有正确透传到底层 Kafka 配置。

## Decision

手动确认并设置以下几个内部 topic 相关的副本因子参数，全部设为 1（匹配单 broker 环境）：

- `offsets.topic.replication.factor = 1`  
- `transaction.state.log.replication.factor = 1`  
- `transaction.state.log.min.isr = 1`

在后续 Day 3 改用 docker-compose 起 Kafka 时，直接在环境变量里显式指定这几项（`KAFKA_CFG_OFFSETS_TOPIC_REPLICATION_FACTOR` 等），不再依赖 chart 的默认值转换逻辑，从源头避免这个坑复现。

## Alternatives Considered

- **依赖 chart 默认参数直通**：已验证不可靠，放弃。  
- **部署多 broker 集群凑够副本数**：本地开发环境资源开销不必要，纯粹为了绕开一个配置问题而增加集群复杂度，不划算。  
- **手动 kubectl exec 进容器改 broker 配置文件**：可行但不可复现、不可版本控制，不符合"配置即代码"的原则。

## Consequences

- 好处：单 broker 环境下 consumer group、事务等依赖内部 topic 的功能全部可用，且这个配置在 docker-compose 和未来 K8s 部署配置里都显式声明，不再是隐式默认值。  
- 代价：需要记住这几个参数是"单 broker 开发环境专属"的临时妥协——如果未来真的多 broker 部署，需要把这几个值改回默认的 3，否则会失去副本容错能力。

