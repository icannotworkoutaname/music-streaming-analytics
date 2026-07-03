# ADR 002: Kafka Advertised Listeners and Dev Loop

## Context
Day 3 在 K8s (minikube) 里跑 Kafka 时，producer 连接超时。

## Decision
放弃 K8s 内跑 Kafka，改用 docker-compose 本地起 Kafka + PostgreSQL + Redis。

## Consequences
开发体验更轻量，但生产部署时需要重新考虑 K8s 化方案。
