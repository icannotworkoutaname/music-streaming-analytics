# ADR 002: Kafka Advertised Listeners 与开发/部署环境解耦

## Status

Accepted

## Context

Day 3 把 Kafka 部署在 K8s（minikube）里，`ingestion-service` 跑在 WSL 宿主机上，通过 `kubectl port-forward` 把 Kafka 的 9092 端口转发到本地。Producer 连接后一直超时，无法发送消息。

排查后确认这是 Kafka 的**两阶段连接模型**导致的：

1. **Bootstrap 阶段**：producer 用配置的 `bootstrap-servers`（这里是 `localhost:9092`，经过 port-forward）连上任意一个 broker，目的只是获取集群元数据（有哪些 broker、各自地址）。  
2. **实际通信阶段**：producer 根据返回的元数据里的 `advertised.listeners` 地址，**重新发起连接**去和真正负责对应 partition 的 broker 通信。

问题出在第二阶段：K8s 里的 Kafka broker 返回的 `advertised.listeners` 是 `kafka-controller-0.kafka-controller-headless.kafka.svc.cluster.local:9092` 这样的 K8s 集群内部 DNS 地址，宿主机（WSL）根本无法解析，导致连接反复失败。

这本质是\*\*开发环境（宿主机）**和**部署环境（K8s 集群网络）\*\*混用导致的网络模型割裂：port-forward 能穿透第一阶段的 bootstrap 连接，但穿不透第二阶段基于集群内部 DNS 的真实数据连接。

## Decision

采用开发和部署**双轨制**：

- **开发期**：所有服务（ingestion-service、stream-processor、query-service）和依赖的中间件（Kafka、PostgreSQL、Redis）统一跑在本地 `docker-compose.dev.yml` 定义的环境里，全部监听 `localhost`，advertised listeners 直接写死 `localhost:9092`，开发调试走最短路径。  
- **部署/集成验证**：K8s（minikube，未来 GKE）留给最终集成测试和演示使用，所有服务都以容器方式部署进集群内部，用集群内部 DNS 互相发现，不再跨宿主机/集群边界通信。

两层环境不混用——要么全部在宿主机（docker-compose），要么全部在集群内（K8s），不允许一部分服务在宿主机、一部分在集群内直接通信。

## Alternatives Considered

- **方案 A（使用中）**：开发用 docker-compose，部署用 K8s，两层完全解耦。  
- **方案 B：把 ingestion-service 也部署进 K8s，让 producer 在集群内运行**：技术上能解决 DNS 问题，但开发循环变长（改代码 → 打镜像 → `minikube image load` → 重启 pod），调试效率低，不适合高频迭代的开发阶段。  
- **方案 C：改 Kafka chart 配置双 listener（内部 \+ 外部 NodePort），advertised 地址用 `minikube ip`**：技术上最"正确"，能同时支持集群内外访问，但配置复杂、维护成本高，对个人项目的投入产出比不划算。

## Consequences

- 好处：开发效率大幅提升（改代码到验证的循环从分钟级降到秒级），网络问题不再干扰业务逻辑调试。  
- 代价：需要维护两套 Kafka（及其他中间件）配置（docker-compose 一套、K8s YAML 一套），存在"配置漂移"的风险——两边参数不一致可能导致"本地能跑、K8s 跑不通"的问题，需要定期做集成验证来兜底。

