# ADR 002: Kafka Advertised Listeners and the Separation of Development and Deployment Environments

> English Version. Chinese version below.

## Status

Accepted

## Context

Kafka ran on Kubernetes (minikube) while `ingestion-service` ran on the WSL host, with Kafka's port 9092 forwarded to the host via `kubectl port-forward`. After connecting, the producer timed out continuously and could not send messages.

Investigation confirmed that this is a consequence of Kafka's **two-phase connection model**:

1. **Bootstrap phase**: the producer connects to an arbitrary broker using the configured `bootstrap-servers` (here `localhost:9092`, through the port-forward) purely to obtain cluster metadata — which brokers exist and at which addresses.
2. **Data phase**: based on the `advertised.listeners` addresses returned in that metadata, the producer **opens a new connection** to the broker that actually owns the target partition.

The failure occurred in the second phase. The Kafka broker inside Kubernetes advertised an in-cluster DNS address such as `kafka-controller-0.kafka-controller-headless.kafka.svc.cluster.local:9092`, which the host (WSL) cannot resolve at all, so connections failed repeatedly.

Fundamentally this is a split network model caused by mixing the **development environment (the host)** with the **deployment environment (the cluster network)**: port-forwarding can tunnel the first-phase bootstrap connection, but not the second-phase data connection that depends on in-cluster DNS.

## Decision

Adopt a **two-track** approach to development and deployment:

- **Development**: all services (ingestion-service, stream-processor, query-service) and their supporting middleware (Kafka, PostgreSQL, Redis) run locally in the environment defined by `docker-compose.dev.yml`, all listening on `localhost`, with advertised listeners hard-coded to `localhost:9092`. Development and debugging then take the shortest path.
- **Deployment / integration verification**: Kubernetes (minikube now, GKE later) is reserved for final integration testing and demonstration. All services are deployed as containers inside the cluster and discover one another through in-cluster DNS, with no communication crossing the host/cluster boundary.

The two layers are not mixed: either everything runs on the host (docker-compose) or everything runs inside the cluster (Kubernetes). Having some services on the host communicate directly with others inside the cluster is not permitted.

**This constraint applies to the business data plane only** — that is, to Kafka producers and consumers, Redis, databases, and other communication that requires long-lived bidirectional connections and depends on server-supplied addresses to establish a second connection. Diagnostic tooling is exempt: during load testing, k6 runs on the host and sends one-way HTTP requests to the in-cluster `ingestion-service` through `kubectl port-forward`, and Prometheus runs in Docker on the host and scrapes `/actuator/prometheus` through port-forwards. Both are deliberate exceptions. These paths are one-way request/response only and involve no address redirection of the kind advertised listeners introduce, but they remain subject to the lifecycle of the port-forward (see [ADR 007](007-failure-recovery-observations.md), Observation 1).

## Alternatives Considered

- **Option A (in use)**: docker-compose for development, Kubernetes for deployment, with the two layers fully decoupled.
- **Option B: deploy ingestion-service into Kubernetes as well, so the producer runs inside the cluster.** This resolves the DNS problem but lengthens the development loop (edit code → build image → `minikube image load` → restart pod), which is inefficient for debugging and unsuitable for a phase of frequent iteration.
- **Option C: configure dual listeners in the Kafka chart (internal plus external NodePort) and advertise `minikube ip`.** This is the most complete solution from a network-model perspective and supports access from both inside and outside the cluster, but it is complex to configure and costly to maintain; at the current scale of the project the benefit does not justify the cost.

## Consequences

- Benefit: development throughput improved markedly (the edit-to-verify loop went from minutes to seconds), and network problems no longer interfere with debugging business logic.
- Cost: two sets of Kafka (and other middleware) configuration must be maintained, one for docker-compose and one for the Kubernetes YAML, which creates a risk of configuration drift. Divergent parameters can produce problems that appear only on Kubernetes and not locally, so periodic integration verification is needed as a safeguard.

---

# ADR 002: Kafka Advertised Listeners 与开发/部署环境解耦

## Status

Accepted

## Context

Kafka 部署在 K8s（minikube）里，`ingestion-service` 跑在 WSL 宿主机上，通过 `kubectl port-forward` 把 Kafka 的 9092 端口转发到本地。Producer 连接后持续超时，无法发送消息。

排查后确认这是 Kafka 的**两阶段连接模型**导致的：

1. **Bootstrap 阶段**：producer 用配置的 `bootstrap-servers`（这里是 `localhost:9092`，经过 port-forward）连上任意一个 broker，目的只是获取集群元数据（有哪些 broker、各自地址）。  
2. **实际通信阶段**：producer 根据返回的元数据里的 `advertised.listeners` 地址，**重新发起连接**去和真正负责对应 partition 的 broker 通信。

问题出在第二阶段：K8s 里的 Kafka broker 返回的 `advertised.listeners` 是 `kafka-controller-0.kafka-controller-headless.kafka.svc.cluster.local:9092` 这样的 K8s 集群内部 DNS 地址，宿主机（WSL）根本无法解析，导致连接反复失败。

这本质上是**开发环境（宿主机）**和**部署环境（K8s 集群网络）**混用导致的网络模型割裂：port-forward 能穿透第一阶段的 bootstrap 连接，但穿不透第二阶段基于集群内部 DNS 的真实数据连接。

## Decision

采用开发和部署**双轨制**：

- **开发期**：所有服务（ingestion-service、stream-processor、query-service）和依赖的中间件（Kafka、PostgreSQL、Redis）统一跑在本地 `docker-compose.dev.yml` 定义的环境里，全部监听 `localhost`，advertised listeners 直接写死 `localhost:9092`，开发调试走最短路径。  
- **部署/集成验证**：K8s（minikube，未来 GKE）留给最终集成测试和演示使用，所有服务都以容器方式部署进集群内部，用集群内部 DNS 互相发现，不再跨宿主机/集群边界通信。

两层环境不混用——要么全部在宿主机（docker-compose），要么全部在集群内（K8s），不允许一部分服务在宿主机、另一部分在集群内直接通信。

**这条约束的适用范围限于业务数据面**，即 Kafka producer / consumer、Redis、数据库这类需要双向长连接、且依赖服务端返回地址完成二次连接的通信。诊断类工具不受此约束：压测时 k6 在宿主机、经 `kubectl port-forward` 向集群内的 `ingestion-service` 发起单向 HTTP 请求，Prometheus 在宿主机 Docker、经 port-forward 抓取 `/actuator/prometheus`，都属于有意为之的例外。这类链路只走单向请求-响应，不存在 advertised listener 那样的地址重定向问题，但仍会受 port-forward 生命周期的影响（见 [ADR 007](007-failure-recovery-observations.md) Observation 1）。

## Alternatives Considered

- **方案 A（使用中）**：开发用 docker-compose，部署用 K8s，两层完全解耦。  
- **方案 B：把 ingestion-service 也部署进 K8s，让 producer 在集群内运行**：能解决 DNS 问题，但开发循环变长（改代码 → 构建镜像 → `minikube image load` → 重启 pod），调试效率低，不适合高频迭代的开发阶段。  
- **方案 C：修改 Kafka chart 配置双 listener（内部 + 外部 NodePort），advertised 地址用 `minikube ip`**：从网络模型上看最完整，能同时支持集群内外访问，但配置复杂、维护成本高，对当前规模的项目而言收益不足以覆盖成本。

## Consequences

- 好处：开发效率明显提升（改代码到验证的循环从分钟级降到秒级），网络问题不再干扰业务逻辑调试。  
- 代价：需要维护两套 Kafka（及其他中间件）配置（docker-compose 一套、K8s YAML 一套），存在配置漂移的风险——两边参数不一致可能导致本地正常、K8s 异常的问题，需要定期做集成验证来兜底。

