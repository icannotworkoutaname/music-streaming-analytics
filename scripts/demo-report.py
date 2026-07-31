#!/usr/bin/env python3
"""
demo-report.py — 读取 query-service 的查询结果，与注入时记录的预期值比对。

这一步的目的是验证整条链路
（HTTP 摄入 → Kafka → Kafka Streams 窗口聚合 → 回写 Kafka → Redis → 查询 API）
算出来的结果确实等于灌进去的数据，而不是碰巧有输出。

面向用户的输出统一使用英文，便于在英文环境下演示。
"""

import argparse
import json
import sys
import urllib.error
import urllib.request

RESET = "\033[0m"
BOLD = "\033[1m"
DIM = "\033[2m"
GREEN = "\033[32m"
YELLOW = "\033[33m"
RED = "\033[31m"


def get_json(url, timeout=10):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def fmt_title(text):
    return f"\n{BOLD}{text}{RESET}\n" + "─" * 78


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--query-url", default="http://localhost:8082")
    ap.add_argument("--expected", default="/tmp/demo-expected.json")
    ap.add_argument("--top-n", type=int, default=12,
                    help="how many ranking rows to show (injected tracks only)")
    ap.add_argument("--deep-n", type=int, default=2000,
                    help="how many entries to request, so injected tracks are found among leftovers")
    ap.add_argument("--min-plays", type=int, default=20,
                    help="minimum plays to qualify for the completion-rate ranking")
    ap.add_argument("--no-color", action="store_true")
    args = ap.parse_args()

    if args.no_color:
        globals().update({k: "" for k in
                          ("RESET", "BOLD", "DIM", "GREEN", "YELLOW", "RED")})

    with open(args.expected) as fh:
        expected = json.load(fh)
    exp_songs = expected["songs"]

    # 排行榜取足够深，以便在历史遗留条目很多时仍能找到本次注入的歌曲
    raw_top = get_json(f"{args.query_url}/api/songs/top?n={args.deep_n}") or []
    mine = [e for e in raw_top if e["songId"] in exp_songs]
    stale = [e for e in raw_top if e["songId"] not in exp_songs]

    # ---------- 热门歌曲排行 ----------
    print(fmt_title("Top tracks  GET /api/songs/top"))
    print(f"{'#':>2}  {'track':<18} {'plays':>7}  {'injected':>8}   "
          f"plays = 1-hour window total, from a Redis Sorted Set")
    accumulated = False
    top_score = max((e["playCount"] for e in mine), default=1)
    for i, entry in enumerate(mine[:args.top_n], 1):
        sid = entry["songId"]
        actual = entry["playCount"]
        exp = exp_songs.get(sid, {}).get("injectedStarts", 0)
        if actual > exp:
            accumulated = True
            note = f"{DIM}(incl. earlier runs this hour){RESET}"
        elif actual == exp:
            note = f"{GREEN}OK{RESET}"
        else:
            note = f"{YELLOW}short{RESET}"
        bar = "█" * max(1, round(actual / max(top_score, 1) * 24))
        print(f"{i:>2}  {sid:<18} {actual:>7}  {exp:>8}   {bar} {note}")

    if stale:
        print()
        print(f"  {DIM}The ranking also holds {len(stale)} leftover entries "
              f"(e.g. {', '.join(e['songId'] for e in stale[:3])} …) from earlier load tests.{RESET}")
        print(f"  {DIM}query-service only ZADDs to the Sorted Set and never trims it, so members "
              f"from expired windows persist. See 'Known limitations' in DEMO.md.{RESET}")
        print(f"  {DIM}Re-run with --reset to clear them first.{RESET}")

    # ---------- 完成率 ----------
    print(fmt_title("Completion rate  GET /api/songs/{songId}/completion"))
    print(f"{'track':<18} {'starts':>7} {'completes':>10} {'rate':>8} {'expected':>9}   "
          f"ADR 003 threshold: 90%")

    rows, mismatches, missing = [], [], []
    for sid in sorted(exp_songs, key=lambda s: -exp_songs[s]["starts"]):
        stats = get_json(f"{args.query_url}/api/songs/{sid}/completion")
        exp = exp_songs[sid]
        if stats is None:
            missing.append(sid)
            print(f"{sid:<18} {'-':>7} {'-':>10} {'-':>8} "
                  f"{exp['completionRate']:>8.1%}   {YELLOW}no data in Redis{RESET}")
            continue
        same = (stats["starts"] == exp["starts"]
                and stats["completes"] == exp["completes"])
        if not same:
            mismatches.append((sid, stats, exp))
        mark = f"{GREEN}OK{RESET}" if same else f"{YELLOW}!={RESET}"
        print(f"{sid:<18} {stats['starts']:>7} {stats['completes']:>10} "
              f"{stats['completionRate']:>7.1%} {exp['completionRate']:>8.1%}   {mark}")
        rows.append((sid, stats["completionRate"], exp["starts"]))

    carried = expected.get("carriedOverStarts", 0)
    if carried:
        print()
        print(f"  {DIM}Expected = this run's injection + the {carried} plays already in this "
              f"5-minute window.{RESET}")
        print(f"  {DIM}Aggregates keep accumulating until the window closes, so repeated runs "
              f"start from a non-zero baseline.{RESET}")

    # ---------- 结论 ----------
    print(fmt_title("What this data shows"))
    # 长尾歌曲样本量太小，完成率会剧烈波动（3 次播放里完成 3 次就是 100%），
    # 拿这种数字下结论没有意义，因此排名只在样本量足够的歌曲里选。
    ranked = [r for r in rows if r[2] >= args.min_plays]
    if mine and ranked:
        hottest = mine[0]["songId"]
        best = max(ranked, key=lambda r: r[1])
        worst = min(ranked, key=lambda r: r[1])
        excluded = len(rows) - len(ranked)
        print(f"  Most played:          {BOLD}{hottest}{RESET} ({mine[0]['playCount']} plays)")
        print(f"  Highest completion:   {BOLD}{best[0]}{RESET} "
              f"({best[1]:.1%}, {best[2]} plays)")
        print(f"  Lowest completion:    {BOLD}{worst[0]}{RESET} "
              f"({worst[1]:.1%}, {worst[2]} plays)")
        if excluded:
            print(f"  {DIM}({excluded} long-tail tracks below {args.min_plays} plays are excluded "
                  f"from the ranking; their completion rates carry little statistical weight){RESET}")
        if hottest != best[0]:
            print()
            print("  The two metrics point at different tracks. Play count measures how often a")
            print("  track was opened; completion rate measures how often it was heard through.")
            print("  This is why ADR 003 defines completion rate as a separate business metric:")
            print("  popularity does not imply that the content holds attention.")

    print(fmt_title("Verification"))
    if missing:
        print(f"  {YELLOW}!{RESET} {len(missing)} tracks have no completion data in Redis: "
              f"{', '.join(missing[:5])}")
        print(f"    Usually the aggregates have not been written yet. "
              f"Re-run with --report-only in a few seconds.")
    if mismatches:
        low = [m for m in mismatches if m[1]["starts"] < m[2]["starts"]]
        high = [m for m in mismatches if m[1]["starts"] > m[2]["starts"]]
        print(f"  {YELLOW}!{RESET} {len(mismatches)} tracks do not match the expected "
              f"starts/completes:")
        for sid, act, exp in mismatches[:5]:
            print(f"    {sid}: actual {act['starts']}/{act['completes']}, "
                  f"expected {exp['starts']}/{exp['completes']}")
        if low:
            print(f"    {len(low)} short: aggregates may still be propagating — re-run with")
            print(f"    --report-only shortly. If they stay short, the injection straddled a")
            print(f"    5-minute window boundary.")
        if high:
            print(f"    {len(high)} over: another source wrote events into the same window "
                  f"after injection began.")
    if accumulated:
        print(f"  {DIM}i{RESET} Ranking plays exceed this run's injection: the ranking uses a "
              f"1-hour window,")
        print(f"    so repeated runs within the hour accumulate, while completion rate uses a")
        print(f"    finer 5-minute window. The differing window sizes are by design.")
    if not missing and not mismatches:
        print(f"  {GREEN}OK{RESET} All {len(rows)} tracks match the expected starts / completes "
              f"exactly.")
        print(f"    The end-to-end path (HTTP -> Kafka -> Kafka Streams windowed aggregation ->")
        print(f"    Kafka -> Redis -> query API) computes correct results.")

    print()
    return 1 if (mismatches or missing) else 0


if __name__ == "__main__":
    sys.exit(main())
