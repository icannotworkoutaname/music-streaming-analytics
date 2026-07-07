# ADR 004: query-service 的 CQRS 风格查询架构

## Status

Accepted

## Context

Day 6 需要对外提供一个查询接口，让客户端能查到 `stream-processor` 算出来的聚合结果（每首歌的完成率、热门歌曲排行）。Kafka Streams 的状态存储（state store）本身支持两种对外查询的方式：

**方式 A：Interactive Queries** 直接查询 `stream-processor` 内部的本地状态存储。一个选择是让 `query-service` 通过 HTTP 调用 `stream-processor` 暴露的一个"读状态"端点，另一个选择是让 `query-service` 本身也加入同一个 Kafka Streams `application-id`，直接读取共享的状态存储。

**方式 B：结果回写 \+ 独立读模型（CQRS 风格）** `stream-processor` 把窗口聚合的最终结果（`SongCompletionStats`、`SongHourlyCount`）写回两个独立的 Kafka topic（`song-completion-stats`、`song-hourly-counts`），`query-service` 作为纯消费者订阅这两个 topic，把结果写入 Redis，对外的查询接口只读 Redis。

## Decision

采用方式 B。

`stream-processor` 只负责计算和写出结果，不直接对外提供查询能力；`query-service` 是独立的读服务，消费 Kafka 里的聚合结果写入 Redis（`hourly-top-songs` 用 Sorted Set 存排行、完成率数据按 `songId` 存 String/Hash），REST API 只读 Redis，不接触 Kafka Streams 的内部状态。

## Alternatives Considered

- **方式 A（Interactive Queries）**：技术上更"直接"，少了一层 Kafka 中转，理论上延迟更低。但会把 `query-service` 和 `stream-processor` 的生命周期强耦合在一起。也就是说共享 `application-id`或依赖对方暴露的内部状态端点。`stream-processor` 重启或 rebalance 时查询可用性会受影响，架构上读写没有分离，不利于独立扩展。  
- **方式 B（采纳）**：读写职责清晰分离，符合 CQRS（命令查询职责分离）思路。

## Consequences

- 好处：  
  - **职责解耦**：`stream-processor`（写路径/计算）和 `query-service`（读路径/查询）完全独立部署、独立扩展，互不阻塞——query-service 可以起多个实例应对读流量，不影响流处理的计算负载。  
  - **技术栈对齐目标岗位**：Kafka \+ Redis \+ PostgreSQL（未来接入）的组合正好命中北欧后端岗位 JD 里的高频关键词，架构决策本身也是很好的面试素材。  
  - **为历史数据查询留了扩展空间**：Redis 之外未来可以再加一层写入 PostgreSQL，做更长周期的历史查询，不需要改动 `stream-processor`。  
- 代价：  
  - 多了一层 Kafka topic 中转和 Redis 写入，端到端延迟比方式 A 高（多了一次网络往返和序列化开销），但对当前场景（分钟级窗口聚合，非实时交易）完全可以接受。  
  - 需要维护 `stream-processor` 输出的数据模型（`SongCompletionStats`、`SongHourlyCount`）和 `query-service` 消费逻辑的一致性，schema 变更需要两边同步。

