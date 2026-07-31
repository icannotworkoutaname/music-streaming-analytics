#!/usr/bin/env python3
"""
Demo-traffc.py - Generate a stream of playback events with a heat tilt and feed it into the entertainment service.

Each song in the directory has two independent parameters:

The weight of popularity determines the number of times it is played, and the overall distribution is approximately Zipf
(A few top songs occupy the majority of the views)
The completion tendency determines the probability of hearing more than 90% during a playback

The two are deliberately designed to be unrelated. This demonstration data can reflect a business fact: the song with the highest playback volume
Not necessarily the song with the highest completion rate, the two indicators measure different things (see ADR 003).

The script is only responsible for generating events and POST them to the estimation service, while also providing the 'expected results'
Write a JSON document. Subsequently, demo.sh will use this JSON and the actual return of query service to perform the task
Comparison is used to confirm that the calculations of the entire assembly line are correct, rather than just 'having numbers coming out'.

User oriented output is uniformly in English, making it easy to demonstrate in an English environment.
"""
"""
demo-traffic.py — 生成带热度倾斜的播放事件流，灌入 ingestion-service。

目录里每首歌有两个互相独立的参数：

  weight      热度权重，决定它被播放的次数，整体近似 Zipf 分布
              （少数头部歌曲占据大部分播放量）
  completion  完成倾向，决定一次播放中听到 90% 以上的概率

两者刻意设计成不相关。这样演示数据能体现一个业务事实：播放量最高的歌
不一定是完成率最高的歌，两个指标衡量的是不同的东西（见 ADR 003）。

脚本只负责产生事件并 POST 到 ingestion-service，同时把"应该得到的结果"
写成一份 JSON。后续由 demo.sh 拿这份 JSON 与 query-service 的实际返回做
比对，用来确认整条流水线的计算是正确的，而不只是"有数字出来"。

面向用户的输出统一使用英文，便于在英文环境下演示。
"""

import argparse
import json
import random
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

# 完成率窗口为 5 分钟（见 StreamProcessor 的 TimeWindows 配置）
COMPLETION_WINDOW_MS = 5 * 60 * 1000

# (歌名, 热度权重, 完成倾向, 时长秒)
CATALOG = [
    ("Neon Skyline",    100, 0.88, 168),  # 头部热单：播放量第一，完成率也不错
    ("Midnight Drive",   62, 0.71, 205),
    ("Paper Boats",      41, 0.93, 189),  # 播放量中游，完成率最高
    ("Static Bloom",     28, 0.44, 312),  # 偏长，完成率明显偏低
    ("Cassette Sunset",  19, 0.66, 224),
    ("Glass Harbour",    13, 0.81, 176),
    ("Low Tide",          9, 0.58, 258),
    ("Ferris Wheel",      6, 0.74, 198),
    ("Amber Room",        4, 0.35, 401),  # 全场最长，完成率最低
    ("Northbound",        3, 0.69, 213),
    ("Salt Flats",        2, 0.77, 187),
    ("Winter Static",     1, 0.62, 241),
]


def slug(name):
    return name.lower().replace(" ", "-")


def build_events(sessions, rng, base_ts, spread_ms):
    """生成事件列表，并统计每首歌的 starts / completes 作为预期值。"""
    songs = [slug(n) for n, _, _, _ in CATALOG]
    weights = [w for _, w, _, _ in CATALOG]
    by_slug = {slug(n): (c, d * 1000) for n, _, c, d in CATALOG}

    events = []
    expected = {s: {"starts": 0, "completes": 0} for s in songs}

    for i in range(sessions):
        song = rng.choices(songs, weights=weights, k=1)[0]
        completion_p, duration_ms = by_slug[song]
        user = f"user-{rng.randint(1, 400):03d}"
        # 时间戳的抖动范围由 spread_ms 限定，确保所有事件落在同一个 5 分钟窗口内
        ts = base_ts + rng.randint(0, spread_ms)

        events.append({
            "eventId": f"demo-{i}-start",
            "userId": user,
            "songId": song,
            "eventType": "PLAY_START",
            "timestamp": ts,
            "positionMs": 0,
            "durationMs": duration_ms,
        })
        expected[song]["starts"] += 1

        if rng.random() < completion_p:
            # 听到 92%~100%，越过 ADR 003 定义的 90% 阈值，计入完成
            pos = int(duration_ms * rng.uniform(0.92, 1.0))
            events.append({
                "eventId": f"demo-{i}-end",
                "userId": user,
                "songId": song,
                "eventType": "PLAY_END",
                "timestamp": ts + 1,
                "positionMs": pos,
                "durationMs": duration_ms,
            })
            expected[song]["completes"] += 1
        elif rng.random() < 0.6:
            # 中途退出但仍上报 PLAY_END，位置不足 90%，不计入完成
            pos = int(duration_ms * rng.uniform(0.10, 0.85))
            events.append({
                "eventId": f"demo-{i}-end",
                "userId": user,
                "songId": song,
                "eventType": "PLAY_END",
                "timestamp": ts + 1,
                "positionMs": pos,
                "durationMs": duration_ms,
            })
        else:
            # 直接切歌，SKIP 事件不参与完成率的分子分母（见 ADR 003）
            events.append({
                "eventId": f"demo-{i}-skip",
                "userId": user,
                "songId": song,
                "eventType": "SKIP",
                "timestamp": ts + 1,
                "positionMs": int(duration_ms * rng.uniform(0.02, 0.30)),
                "durationMs": duration_ms,
            })

    return events, expected


def fetch_baseline(query_url, songs, window_start):
    """
    读取注入前各首歌在目标窗口内已有的 starts/completes。

    同一个 5 分钟窗口内重复运行 demo，聚合值会在既有基础上继续累加；
    而 --reset 只清空 Redis 读模型，清不掉 Kafka Streams 里的窗口状态。
    因此预期值必须建立在"注入前已有多少"之上，否则每次重跑都会误报不一致。
    用 windowStart 判断读到的记录是否属于本次要注入的那个窗口。
    """
    baseline = {s: {"starts": 0, "completes": 0} for s in songs}
    for s in songs:
        try:
            req = urllib.request.Request(f"{query_url}/api/songs/{s}/completion")
            with urllib.request.urlopen(req, timeout=5) as resp:
                data = json.load(resp)
            if data.get("windowStart") == window_start:
                baseline[s]["starts"] = data.get("starts", 0)
                baseline[s]["completes"] = data.get("completes", 0)
        except urllib.error.HTTPError as e:
            if e.code != 404:
                raise
        except (urllib.error.URLError, OSError):
            # 查询服务暂时不可达时按无基线处理，后续比对会提示不一致
            pass
    return baseline


def post(url, payload, timeout=10):
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", default="http://localhost:8080/events")
    ap.add_argument("--query-url", default="http://localhost:8082",
                    help="query-service base URL, used to read the pre-injection baseline")
    ap.add_argument("--sessions", type=int, default=600,
                    help="number of play sessions; each yields 2 events (default 600)")
    ap.add_argument("--concurrency", type=int, default=16)
    ap.add_argument("--seed", type=int, default=20260731,
                    help="random seed; a fixed seed reproduces the exact same data")
    ap.add_argument("--expected-out", default="/tmp/demo-expected.json")
    ap.add_argument("--no-window-align", action="store_true",
                    help="skip the 5-minute window alignment wait (may straddle windows; debug only)")
    args = ap.parse_args()

    rng = random.Random(args.seed)

    # 完成率是 5 分钟滚动窗口的聚合值。只要有任何事件的时间戳越过窗口边界，
    # 该窗口的结果就会被拆成两半，Redis 里最终留下的是后一个窗口的部分数据，
    # 与预期对不上。因此这里做两件事：
    #   1. 当前窗口剩余不足 MIN_REMAINING_MS 时，等到下一个窗口开始再灌；
    #   2. 时间戳抖动范围收敛到窗口剩余时间之内，并留出安全余量。
    MIN_REMAINING_MS = 60_000
    SAFETY_MARGIN_MS = 20_000
    MAX_SPREAD_MS = 30_000

    now_ms = int(time.time() * 1000)
    remaining = COMPLETION_WINDOW_MS - (now_ms % COMPLETION_WINDOW_MS)
    if remaining < MIN_REMAINING_MS and not args.no_window_align:
        wait_s = remaining / 1000 + 1
        print(f"  Only {remaining/1000:.0f}s left in the current 5-minute window; "
              f"waiting {wait_s:.0f}s so all events land in a single window", flush=True)
        time.sleep(wait_s)
        now_ms = int(time.time() * 1000)
        remaining = COMPLETION_WINDOW_MS - (now_ms % COMPLETION_WINDOW_MS)

    spread_ms = max(1_000, min(MAX_SPREAD_MS, remaining - SAFETY_MARGIN_MS))

    base_ts = now_ms
    window_start = (base_ts // COMPLETION_WINDOW_MS) * COMPLETION_WINDOW_MS
    events, expected = build_events(args.sessions, rng, base_ts, spread_ms)
    print(f"  Event timestamps spread over {spread_ms/1000:.0f}s; "
          f"{remaining/1000:.0f}s remaining in the current 5-minute window", flush=True)

    baseline = fetch_baseline(args.query_url, list(expected.keys()), window_start)
    carried = sum(v["starts"] for v in baseline.values())
    if carried:
        print(f"  Window already holds {carried} plays from an earlier run; "
              f"expectations are computed on top of that baseline", flush=True)

    print(f"  {len(CATALOG)} tracks, {args.sessions} play sessions, "
          f"{len(events)} events total", flush=True)

    t0 = time.time()
    failures = []
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(post, args.url, e) for e in events]
        for f, e in zip(futures, events):
            try:
                if f.result() != 202:
                    failures.append(e["eventId"])
            except (urllib.error.URLError, OSError) as exc:
                failures.append(f'{e["eventId"]}: {exc}')
    elapsed = time.time() - t0

    ok = len(events) - len(failures)
    rate = ok / elapsed if elapsed > 0 else 0
    print(f"  Sent {ok}/{len(events)} in {elapsed:.1f}s (~{rate:.0f} events/s)", flush=True)

    if failures:
        print(f"  {len(failures)} events failed to send; first 3: {failures[:3]}",
              file=sys.stderr)

    songs_summary = {}
    for s, v in expected.items():
        total_starts = baseline[s]["starts"] + v["starts"]
        total_completes = baseline[s]["completes"] + v["completes"]
        songs_summary[s] = {
            # 本次注入的量
            "injectedStarts": v["starts"],
            "injectedCompletes": v["completes"],
            # 注入前该窗口内已有的量
            "baselineStarts": baseline[s]["starts"],
            "baselineCompletes": baseline[s]["completes"],
            # 二者之和，即查询接口应当返回的值
            "starts": total_starts,
            "completes": total_completes,
            "completionRate": (total_completes / total_starts) if total_starts else 0.0,
        }

    summary = {
        "generatedAt": base_ts,
        "windowStart": window_start,
        "sessions": args.sessions,
        "eventsSent": ok,
        "carriedOverStarts": carried,
        "songs": songs_summary,
    }
    with open(args.expected_out, "w") as fh:
        json.dump(summary, fh, indent=2)

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
