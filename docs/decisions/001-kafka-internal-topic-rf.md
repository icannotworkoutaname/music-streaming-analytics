# ADR 001: Replication Factor of Kafka Internal Topics on a Single-Broker Deployment

> English Version. Chinese version below.

## Status

Accepted

## Context

After a single-node Kafka was deployed on Kubernetes (minikube) using the Bitnami Kafka Helm chart, consumer groups never worked. The problem was traced to the replication factor of `__consumer_offsets`, one of Kafka's internal system topics.

In addition to application topics, Kafka maintains several internal system topics (`__consumer_offsets`, `__transaction_state`). Their replication factors are governed by dedicated configuration properties and do not follow the `default.replication.factor` that applies to ordinary topics. The default replication factor for `__consumer_offsets` is 3, whereas a single-node deployment has only one broker and cannot satisfy that requirement. The topic could therefore not be created, and consumer groups could not function.

The Bitnami chart exposes an `offsetsTopicReplicationFactor` parameter for overriding the default, but in that particular deployment the parameter **had no effect**. The likely explanation is that, given the way the value was written or the chart version in use at the time, it was not propagated to the underlying Kafka configuration.

**Current state**: the Kafka instance now running on Kubernetes (chart `kafka-32.4.3`, app 4.0.0) depends on exactly these three chart parameters. `k8s/kafka-values.yaml` contains `offsetsTopicReplicationFactor: 1` and the two related settings, and consumer groups operate normally. The parameter itself is therefore effective, and the original failure was specific to that one deployment rather than a limitation of the chart. The conclusion of this ADR is correspondingly not about whether the parameter works, but about two points. First, the replication factor of internal topics is controlled by separate properties and does not follow `default.replication.factor`, a mechanism that is easy to overlook. Second, parameters of this kind must be declared explicitly and kept under version control, rather than being left to defaults or to a one-off manual setting.

## Decision

Confirm and set the following internal-topic replication parameters manually, all to 1 (matching the single-broker environment):

- `offsets.topic.replication.factor = 1`
- `transaction.state.log.replication.factor = 1`
- `transaction.state.log.min.isr = 1`

When Kafka was later moved to docker-compose (for the background, see [ADR 002](002-kafka-advertised-listeners-and-dev-loop.md)), these settings were specified explicitly through environment variables (`KAFKA_CFG_OFFSETS_TOPIC_REPLICATION_FACTOR` and so on) instead of relying on the chart's default value translation, which prevents the problem from recurring at the source.

## Alternatives Considered

- **Do not declare the values explicitly and rely on the chart defaults.** The defaults target multi-broker clusters (replication factor 3) and are bound to fail in a single-broker environment. Rejected.
- **Deploy a multi-broker cluster to satisfy the replication requirement.** A local development environment does not need to carry that resource overhead, and adding cluster complexity solely to work around a configuration issue does not justify the cost.
- **Modify the broker configuration file manually via `kubectl exec`.** Workable but neither reproducible nor version-controlled, which conflicts with the principle of configuration as code.

## Consequences

- Benefit: in a single-broker environment, consumer groups, transactions, and other features that depend on internal topics all work, and the configuration is stated explicitly in docker-compose rather than left as an implicit default.
- Cost: it must be remembered that these parameters are a temporary concession specific to a single-broker development environment. A genuine multi-broker deployment would require changing them back to the default of 3, otherwise replication-based fault tolerance would be lost.
- The Kubernetes-side Helm values (`kafka`, `redis`) have been exported with `helm get values -o yaml` and committed as `k8s/kafka-values.yaml` and `k8s/redis-values.yaml`. Both environments are now reproducible, consistent with the configuration-as-code principle stated under Alternatives. The files contain `offsetsTopicReplicationFactor: 1`, `transactionStateLogReplicationFactor: 1`, and `transactionStateLogMinIsr: 1`, matching the Decision above.

---

# ADR 001: Single-broker Kafka 的 Internal Topic 副本因子

## Status

Accepted

## Context

在 K8s（minikube）里用 Bitnami Kafka Helm chart 部署单节点 Kafka 后，consumer group 始终无法工作。排查发现问题出在 `__consumer_offsets` 这个 Kafka 内部系统 topic 的副本因子上。

Kafka 除业务 topic 外还有几个内部系统 topic（如 `__consumer_offsets`、`__transaction_state`），它们的副本因子由独立的配置项控制，不跟随普通 topic 的 `default.replication.factor`。`__consumer_offsets` 的默认副本因子是 3，而单节点部署只有 1 个 broker，无法满足 3 副本要求，导致该 topic 无法正常创建，consumer group 随之无法工作。

Bitnami chart 提供了 `offsetsTopicReplicationFactor` 参数用于覆盖默认值，但最初那次部署里该参数**没有生效**——推测是当时的写法或 chart 版本下 values 的传递路径不对，设置未能透传到底层 Kafka 配置。

**当前状态说明**：目前 K8s 中运行的 Kafka（chart `kafka-32.4.3`，app 4.0.0）正是依赖这三个 chart 参数工作，`k8s/kafka-values.yaml` 中可见 `offsetsTopicReplicationFactor: 1` 等三项，consumer group 运行正常。因此该参数本身是有效的，最初的失败源于那一次具体部署，而非 chart 参数不可用。本 ADR 的结论相应地不在于该参数是否可用，而在于两点：其一，内部 topic 的副本因子由独立配置项控制、不跟随 `default.replication.factor`，这一机制容易被忽略；其二，此类参数必须显式声明并纳入版本控制，不能依赖默认值或一次性的手工设置。

## Decision

手动确认并设置以下几个内部 topic 相关的副本因子参数，全部设为 1（匹配单 broker 环境）：

- `offsets.topic.replication.factor = 1`  
- `transaction.state.log.replication.factor = 1`  
- `transaction.state.log.min.isr = 1`

后续改用 docker-compose 起 Kafka 时（背景见 [ADR 002](002-kafka-advertised-listeners-and-dev-loop.md)），直接在环境变量里显式指定这几项（`KAFKA_CFG_OFFSETS_TOPIC_REPLICATION_FACTOR` 等），不再依赖 chart 的默认值转换逻辑，从源头避免该问题复现。

## Alternatives Considered

- **不显式声明、依赖 chart 默认值**：默认值面向多 broker 集群（副本因子 3），在单 broker 环境下必然失败，放弃。  
- **部署多 broker 集群以满足副本数要求**：本地开发环境无需承担这部分资源开销，仅为绕开一个配置问题而增加集群复杂度，收益不足以覆盖成本。  
- **手动 `kubectl exec` 进容器修改 broker 配置文件**：可行但不可复现、不可版本控制，不符合"配置即代码"的原则。

## Consequences

- 好处：单 broker 环境下 consumer group、事务等依赖内部 topic 的功能全部可用，且该配置在 docker-compose 中显式声明，不再是隐式默认值。  
- 代价：需要记住这几个参数是单 broker 开发环境专属的临时妥协——若未来真的做多 broker 部署，需要把这几个值改回默认的 3，否则会失去副本容错能力。  
- K8s 侧的 Helm values（`kafka`、`redis`）已通过 `helm get values -o yaml` 导出，提交在 `k8s/kafka-values.yaml`、`k8s/redis-values.yaml`，两套环境的配置现在都可复现，与 Alternatives 中"配置即代码"的原则一致。可以在文件里看到 `offsetsTopicReplicationFactor: 1`、`transactionStateLogReplicationFactor: 1`、`transactionStateLogMinIsr: 1` 这几项，与本 ADR 的 Decision 一致。

