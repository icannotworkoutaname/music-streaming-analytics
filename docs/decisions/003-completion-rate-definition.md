# ADR 003: Completion Rate Definition

## Context
需要定义"播放完成"的判定标准。

## Decision
positionMs >= durationMs * 0.9 视为完成播放（而非要求 100%）。

## Consequences
- 优点：容忍用户常见的拖尾/未精确播放到结尾的行为，避免低估完成率
- 权衡：0.9 是经验阈值，后续如有真实数据可调整
