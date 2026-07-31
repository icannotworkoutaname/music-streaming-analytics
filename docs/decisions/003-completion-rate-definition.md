# ADR 003: Business Definition of Playback Completion Rate

> English Version. Chinese version below.

## Status

Accepted

## Context

Implementing the windowed aggregation "completion rate per track over the past 5 minutes" requires first defining what counts as a complete playback. That definition directly determines whether every subsequent analysis and presentation built on the metric is meaningful.

`EventType` has four values: `PLAY_START`, `PLAY_PROGRESS`, `PLAY_END`, and `SKIP`. This metric uses only two of them: `PLAY_START` (playback begins) and `PLAY_END` (playback ends, carrying `positionMs`, the position reached, and `durationMs`, the total track length).

Requiring `positionMs == durationMs` (playback to 100%) would clearly underestimate the true completion rate. In real user behaviour, reaching a point close to the end — a track fading out, or the user switching tracks a few seconds before the end — is common and normal, and should not be counted as incomplete.

## Decision

Within each time window, for each track:

- **Criterion for a completed playback**: a `PLAY_END` event with `positionMs >= durationMs * 0.9`, i.e. reaching at least 90% of the track counts as one complete playback.
- **Completion rate**: `completionRate = completes / starts`
  - Denominator (`starts`): the number of `PLAY_START` events for that track within the window
  - Numerator (`completes`): the number of `PLAY_END` events for that track within the window that meet the 90% threshold above

`PLAY_PROGRESS` and `SKIP` events do not participate in this metric and count toward neither the denominator nor the numerator. In other words, a user skipping a track does not directly depress the completion rate; it simply contributes nothing to the numerator.

The window is configured as a 5-minute tumbling window with a 1-minute grace period (see `StreamConfig.kt`): late events arriving within 1 minute after the window closes are still folded into the original window, and anything later is dropped. The corresponding verification script is `scripts/test-late.sh`.

## Alternatives Considered

- **Exact 100% match.** Too strict; it would misclassify the normal behaviour of switching tracks at the very end as incomplete, systematically underestimating the completion rate and diverging from the ordinary understanding of "listened to a track all the way through" in a real business context.
- **A fixed threshold in seconds (e.g. "ending within the last 10 seconds counts as complete").** This does not generalise across track lengths; the last 10 seconds represent very different proportions of a 3-minute and a 6-minute track. A percentage threshold handles both uniformly.
- **No threshold at all, counting only whether a `PLAY_END` event was emitted.** This cannot distinguish a full listen from an immediate exit after starting, leaving the metric without business meaning.

## Consequences

- Benefit: the 90% threshold is closer to real user behaviour, so the metric better reflects the underlying business question of whether users are actually interested in a track. Both numerator and denominator are clear, reproducible event counts, which keeps implementation and verification straightforward.
- Trade-off: 0.9 is an empirical threshold and has not been calibrated against real user data. If real data is ever ingested, the value should be re-evaluated — bucketing by genre or track length may call for different thresholds.
- Edge cases: multiple `PLAY_END` events for a single playback, and a `PLAY_START` with no corresponding `PLAY_END` (for example after a client crash), are not currently handled. Such data-quality problems are implicitly counted as incomplete, which is a known simplification.
- Window boundary splitting: because numerator and denominator are counted separately in whichever window each event falls into, a playback that crosses a window boundary (`PLAY_START` in one window, `PLAY_END` in the next) depresses the completion rate of the earlier window and inflates that of the later one. The shorter the window, the more pronounced the effect. At the current 5-minute window the impact is acceptable and no compensation is applied.

---

# ADR 003: 播放完成率（Completion Rate）业务定义

## Status

Accepted

## Context

实现"每首歌过去 5 分钟播放完成率"这一窗口聚合指标时，需要先定义一次完整播放的标准，该定义直接影响后续所有基于这个指标的分析和展示是否合理。

`EventType` 共有四种取值：`PLAY_START`、`PLAY_PROGRESS`、`PLAY_END`、`SKIP`。本指标只使用其中两种：`PLAY_START`（开始播放）与 `PLAY_END`（结束播放，携带 `positionMs` 播放到的位置和 `durationMs` 歌曲总时长）。

如果严格要求 `positionMs == durationMs`（播放到 100%）才算完成，会明显低估真实的完成率——真实用户行为中，播放到接近结尾（例如歌曲淡出、用户在结尾前几秒切歌）是常见的正常行为，不应被计为"未完成"。

## Decision

在每个时间窗口内，对每首歌：

- **完成播放的判定标准**：`PLAY_END` 事件里 `positionMs >= durationMs * 0.9`，即播放到 90% 以上视为一次完整播放。  
- **完成率计算**：`completionRate = completes / starts`  
  - 分母（`starts`）：窗口内该歌曲的 `PLAY_START` 事件数  
  - 分子（`completes`）：窗口内该歌曲满足上述 90% 阈值的 `PLAY_END` 事件数

`PLAY_PROGRESS` 和 `SKIP` 事件不参与本指标的计算，既不计入分母也不计入分子。也就是说，用户跳过一首歌不会直接压低完成率，只是不会为分子贡献计数。

窗口配置为 5 分钟滚动窗口 + 1 分钟 grace period（见 `StreamConfig.kt`），即窗口关闭后 1 分钟内到达的迟到事件仍会补进原窗口，超出则丢弃；对应的验证脚本是 `scripts/test-late.sh`。

## Alternatives Considered

- **100% 精确匹配**：过于严格，会把"听完后在结尾换歌"的正常行为误判为未完成，系统性低估完成率，不符合真实业务场景下对"听完一首歌"的一般理解。  
- **使用固定秒数阈值（如"最后 10 秒内结束算完成"）**：对不同时长的歌曲不适用，3 分钟的歌和 6 分钟的歌，最后 10 秒所代表的比例差异很大，不如用百分比阈值统一处理。  
- **不设阈值，只统计是否触发过 `PLAY_END` 事件**：无法区分完整听完与开始后立即退出，指标失去业务意义。

## Consequences

- 好处：90% 阈值更贴近真实用户行为，指标更能反映"用户是否对这首歌感兴趣"这一业务问题；分子分母都是清晰可复现的事件计数，实现和验证都比较简单。  
- 权衡：0.9 是一个经验性阈值，未基于真实用户数据做过校准。若未来接入真实数据，应重新评估该取值是否合适（例如按歌曲类型或时长分桶后可能需要不同阈值）。  
- 边界情况：目前没有处理"同一次播放触发多个 `PLAY_END`"或"只有 `PLAY_START` 没有对应 `PLAY_END`"（例如客户端崩溃）的情况，这类数据质量问题当前被隐式计入"未完成"，是一个已知的简化。  
- 跨窗口切分：由于分子分母都按事件落入的窗口分别计数，一次跨越窗口边界的播放（`PLAY_START` 在前一个窗口、`PLAY_END` 在后一个窗口）会使前一个窗口的完成率偏低、后一个窗口偏高。窗口越短这一效应越明显，当前 5 分钟窗口下影响可接受，此处不做补偿处理。

