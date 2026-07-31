# ADR 004: CQRS-Style Query Architecture for query-service

> English Version. Chinese version below.

## Status

Accepted

## Context

Day 6 required exposing a query interface so that clients could retrieve the aggregation results computed by `stream-processor` (per-track completion rate and the top-songs ranking). Kafka Streams state stores support two ways of exposing such results:

**Option A: Interactive Queries** — query the local state store inside `stream-processor` directly. One variant has `query-service` call a "read state" endpoint exposed over HTTP by `stream-processor`; another has `query-service` join the same Kafka Streams `application-id` and read the shared state store itself.

**Option B: result write-back plus a separate read model (CQRS style)** — `stream-processor` writes the final windowed aggregation results (`SongCompletionStats`, `SongHourlyCount`) back to two dedicated Kafka topics (`song-completion-stats`, `song-hourly-counts`); `query-service` subscribes to those topics as a pure consumer, writes the results into Redis, and serves its query interface from Redis alone.

## Decision

Option B was adopted.

`stream-processor` is responsible only for computation and for emitting results; it exposes no query capability of its own. `query-service` is an independent read service that consumes the aggregation results from Kafka and writes them into Redis (the ranking uses a Sorted Set under the key `hourly-top-songs`; completion rates are stored as whole JSON strings under `completion:{songId}`). The REST API reads only from Redis and never touches Kafka Streams internal state.

## Alternatives Considered

- **Option A (Interactive Queries)**: a shorter path with one fewer Kafka hop, and in theory lower latency. However, whether through a shared `application-id` or through an internal state endpoint exposed by the other service, it couples the lifecycles of `query-service` and `stream-processor` tightly: query availability is affected whenever `stream-processor` restarts or rebalances, read and write responsibilities are not separated, and independent scaling becomes difficult.
- **Option B (adopted)**: a clean separation of read and write responsibilities, consistent with CQRS (Command Query Responsibility Segregation).

## Consequences

- Benefits:
  - **Decoupled responsibilities**: `stream-processor` (write path / computation) and `query-service` (read path / queries) are deployed and scaled independently without blocking each other — query-service can run multiple instances to absorb read traffic without affecting the stream-processing workload.
  - **Clear component boundaries**: Kafka handles data movement, Redis carries the read model, and PostgreSQL (to be integrated later) handles historical archiving. Each component holds a single responsibility, so replacing any one of them does not disturb the others.
  - **Room for historical queries**: beyond Redis, an additional write path into PostgreSQL can be added later for longer-horizon historical queries, with no change to `stream-processor`.
- Costs:
  - The extra Kafka topic hop and Redis write make end-to-end latency higher than Option A (one more network round trip plus serialisation), but this is entirely acceptable for the present use case (minute-scale windowed aggregation, not real-time transactions).
  - Consistency must be maintained between the data models emitted by `stream-processor` (`SongCompletionStats`, `SongHourlyCount`) and the consumption logic in `query-service`; schema changes require coordinated updates on both sides.

---

# ADR 004: query-service 的 CQRS 风格查询架构

## Status

Accepted

## Context

Day 6 需要对外提供一个查询接口，让客户端能查到 `stream-processor` 算出来的聚合结果（每首歌的完成率、热门歌曲排行）。Kafka Streams 的状态存储（state store）本身支持两种对外查询的方式：

**方式 A：Interactive Queries** 直接查询 `stream-processor` 内部的本地状态存储。一个选择是让 `query-service` 通过 HTTP 调用 `stream-processor` 暴露的一个"读状态"端点，另一个选择是让 `query-service` 本身也加入同一个 Kafka Streams `application-id`，直接读取共享的状态存储。

**方式 B：结果回写 \+ 独立读模型（CQRS 风格）** `stream-processor` 把窗口聚合的最终结果（`SongCompletionStats`、`SongHourlyCount`）写回两个独立的 Kafka topic（`song-completion-stats`、`song-hourly-counts`），`query-service` 作为纯消费者订阅这两个 topic，把结果写入 Redis，对外的查询接口只读 Redis。

## Decision

采用方式 B。

`stream-processor` 只负责计算和写出结果，不直接对外提供查询能力；`query-service` 是独立的读服务，消费 Kafka 里的聚合结果写入 Redis（排行榜用 Sorted Set，键为 `hourly-top-songs`；完成率按 `completion:{songId}` 以 String 形式存整条 JSON），REST API 只读 Redis，不接触 Kafka Streams 的内部状态。

## Alternatives Considered

- **方式 A（Interactive Queries）**：路径更短，少了一层 Kafka 中转，理论上延迟更低。但无论是共享 `application-id` 还是依赖对方暴露的内部状态端点，都会把 `query-service` 和 `stream-processor` 的生命周期强耦合在一起：`stream-processor` 重启或 rebalance 时查询可用性会受影响，读写职责没有分离，不利于独立扩展。  
- **方式 B（采纳）**：读写职责清晰分离，符合 CQRS（命令查询职责分离）思路。

## Consequences

- 好处：  
  - **职责解耦**：`stream-processor`（写路径/计算）和 `query-service`（读路径/查询）完全独立部署、独立扩展，互不阻塞——query-service 可以起多个实例应对读流量，不影响流处理的计算负载。  
  - **组件职责边界清晰**：Kafka 负责数据流转、Redis 承载读模型、PostgreSQL（未来接入）负责历史归档，每个组件只承担一类职责，替换其中任何一环都不会波及其他部分。  
  - **为历史数据查询留了扩展空间**：Redis 之外未来可以再加一层写入 PostgreSQL，做更长周期的历史查询，不需要改动 `stream-processor`。  
- 代价：  
  - 多了一层 Kafka topic 中转和 Redis 写入，端到端延迟比方式 A 高（多了一次网络往返和序列化开销），但对当前场景（分钟级窗口聚合，非实时交易）完全可以接受。  
  - 需要维护 `stream-processor` 输出的数据模型（`SongCompletionStats`、`SongHourlyCount`）和 `query-service` 消费逻辑的一致性，schema 变更需要两边同步。

