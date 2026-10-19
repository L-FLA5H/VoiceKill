"""Validate frozen same-audio evidence and update the dev.5 report. No inference."""
import json
import statistics
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / ".local/diagnostics/dev5"


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def rows(name, count, realtime=True):
    report = read(DATA / (name + ".json"))
    assert len(report["rows"]) == count, (name, "incomplete")
    assert report["realtime"] is realtime, (name, "timing mode")
    for row in report["rows"]:
        assert not row.get("error"), (name, row.get("error"))
    return report


def false_pairs(report):
    return {(r["clip"]["id"], word) for r in report["rows"] for word in r["hits"]
            if word != r["clip"].get("target")}


def compare(base, after):
    before = {r["clip"]["id"]: r for r in base["rows"] if r["round"] == 0}
    new = {r["clip"]["id"]: r for r in after["rows"]}
    assert before.keys() == new.keys(), "different corpus"
    for key in new:
        assert before[key]["clip"]["sha256"] == new[key]["clip"]["sha256"], key
    added = false_pairs(after) - false_pairs(base)
    lost = [key for key in new if before[key]["targetHit"] and not new[key]["targetHit"]]
    assert not added, ("new false triggers", added)
    assert not lost, ("lost old hits", lost)
    return {"addedFalseTriggers": sorted(added), "lostHits": lost,
            "recovered": [key for key in new if new[key]["targetHit"] and not before[key]["targetHit"]]}


def stats(report):
    result = {"clips": len(report["rows"]), "positive": sum(bool(r["clip"].get("target")) for r in report["rows"]),
              "hits": sum(r["targetHit"] for r in report["rows"]),
              "falseTriggers": sum(r["falseKillCount"] for r in report["rows"])}
    result["negativeChecks"] = len(report["rows"]) * len(report["evaluatedWords"]) - result["positive"]
    times = [hit["afterSpeechEndMs"] for r in report["rows"] for word, hit in r["hits"].items()
             if word == r["clip"].get("target")]
    if report["realtime"] and times:
        result["hitMedianMs"] = round(statistics.median(times), 1)
    events = [e["postprocess"]["latest"] for r in report["rows"] for e in r["events"] if "postprocess" in e]
    if events:
        assert not [e for e in events if e["error"]], "postprocess errors"
        result["reviewMedianMs"] = round(statistics.median(e["reviewMs"] for e in events), 1)
        result["punctuationMedianMs"] = round(statistics.median(e["punctuationMs"] for e in events), 1)
    return result


def main():
    original = rows("original", 22)
    negative = rows("negative", 14)
    general_base = rows("general-base", 49, False)
    general = rows("general-realtime", 49)
    continuous = rows("continuous", 5)
    old = read(ROOT / ".local/diagnostics/nickname-replay/optimized-dev4/results.json")
    old_negative = read(ROOT / ".local/diagnostics/nickname-replay/optimized-dev4/negative-results.json")
    old_continuous = read(ROOT / ".local/diagnostics/nickname-replay/optimized-dev4/continuous-zipformer.json")
    assert general_base["evaluatedWords"] == general["evaluatedWords"], "different target pool"
    comparisons = {"original": compare(old, original), "negative": compare(old_negative, negative),
                   "general": compare(general_base, general), "continuous": compare(old_continuous, continuous)}
    summary = {name: stats(report) for name, report in [("original", original), ("negative", negative),
                ("general-base", general_base), ("general", general), ("continuous", continuous)]}
    summary["comparisons"] = comparisons
    text = "| 语料 / 一轮 | 命中 / 应触发 | 其他误触发 / 负例检查 |\n| --- | --- | --- |\n"
    for name, label in [("original", "原组 22 段"), ("negative", "额外近邻 14 段"),
                        ("general-base", "逐字母 49 条，基础识别对照"), ("general", "逐字母 49 条，句末复核"),
                        ("continuous", "连续讲话与空闲控制 5 段")]:
        s = summary[name]
        text += f"| {label} | {s['hits']}/{s['positive']} | {s['falseTriggers']}/{s['negativeChecks']} |\n"
    text += "\n逐字母组按危险词拆分（命中遵循精确包含，不代表整句逐字转写正确）：\n\n| 目标 | 基础识别 | 句末复核 |\n| --- | --- | --- |\n"
    for target in general["evaluatedWords"]:
        baseline_rows = [r for r in general_base["rows"] if r["clip"].get("target") == target]
        new_rows = [r for r in general["rows"] if r["clip"].get("target") == target]
        if new_rows:
            text += f"| {target} | {sum(r['targetHit'] for r in baseline_rows)}/{len(baseline_rows)} | {sum(r['targetHit'] for r in new_rows)}/{len(new_rows)} |\n"
    text += "\n逐项按 `(音频 ID, 危险词)` 比较：新增误触发 0，原有命中丢失 0。"
    text += "逐字母专项新增命中：" + "、".join(comparisons["general"]["recovered"]) + "。\n\n"
    for key, label in [("original", "原组"), ("general", "字母专项")]:
        s = summary[key]
        text += f"{label}命中延迟中位数 {s['hitMedianMs']/1000:.2f} 秒，句末复核中位数 {s['reviewMedianMs']:.1f}ms，标点 {s['punctuationMedianMs']:.1f}ms。\n\n"
    text += "| 连续样本 | 开口到首次最终文本 | 最长相邻结算间隔 |\n| --- | --- | --- |\n"
    intervals = []
    for r in continuous["rows"]:
        if not r["events"]:
            continue
        times = [e["wallMs"] for e in r["events"]]
        first = (times[0] - r["clip"]["speechStartSample"] / 16) / 1000
        gap = max([b-a for a,b in zip(times,times[1:])] or [0]) / 1000
        text += f"| {r['clip']['id']} | {first:.2f} 秒 | {gap:.2f} 秒 |\n"
        intervals.append({"id":r["clip"]["id"],"firstSeconds":first,"maxGapSeconds":gap})
    summary["continuousIntervals"] = intervals
    (DATA / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report = ROOT / "docs/DEV5_VALIDATION.md"
    document = report.read_text(encoding="utf-8")
    start, end = "<!-- RESULTS -->", "<!-- /RESULTS -->"
    if end in document:
        document = document[:document.index(start)] + start + "\n" + text + end + document[document.index(end)+len(end):]
    else:
        document = document.replace(start, start + "\n" + text + end)
    report.write_text(document, encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()
