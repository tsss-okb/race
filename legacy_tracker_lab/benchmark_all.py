import argparse
import csv
import math
import statistics
import subprocess
import sys
from pathlib import Path


MODES = ("kcf-hybrid", "kalman", "csrt", "kcf", "mosse")


def parse_args():
    p = argparse.ArgumentParser(
        description="Run Kalman/CSRT/KCF/MOSSE on the same video and summarize metrics"
    )
    p.add_argument("--video", required=True, help="Input MP4/video")
    p.add_argument("--model", required=True, help="YOLO model path")
    p.add_argument("--class-id", type=int, default=None)
    p.add_argument("--imgsz", type=int, default=640)
    p.add_argument("--conf-search", type=float, default=0.20)
    p.add_argument("--conf-track", type=float, default=0.15)
    p.add_argument("--iou", type=float, default=0.40)
    p.add_argument("--verify-every", type=int, default=30)
    p.add_argument("--out-dir", default="benchmark_out")
    p.add_argument("--python", default=sys.executable)
    return p.parse_args()


def safe_float(v):
    try:
        return float(v)
    except Exception:
        return math.nan


def percentile(values, q):
    vals = sorted(v for v in values if math.isfinite(v))
    if not vals:
        return math.nan
    if len(vals) == 1:
        return vals[0]
    pos = (len(vals) - 1) * q
    lo = int(math.floor(pos))
    hi = int(math.ceil(pos))
    if lo == hi:
        return vals[lo]
    a = pos - lo
    return vals[lo] * (1 - a) + vals[hi] * a


def summarize_csv(path):
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            rows.append(row)

    if not rows:
        return {
            "frames": 0,
            "avg_infer_ms": math.nan,
            "avg_detector_fps": math.nan,
            "avg_tracker_fps": math.nan,
            "mean_jitter_px": math.nan,
            "p95_jitter_px": math.nan,
            "lost_peak": 0,
            "reacquires": 0,
            "hold_ratio": 0.0,
        }

    infer = [safe_float(r["infer_ms"]) for r in rows if r["infer_ms"] not in ("", None)]
    detfps = [safe_float(r["detector_fps"]) for r in rows if r["detector_fps"] not in ("", None)]
    trkfps = [safe_float(r["tracker_fps"]) for r in rows if r["tracker_fps"] not in ("", None)]
    jit = [safe_float(r["jitter_px"]) for r in rows if r["jitter_px"] not in ("", None)]

    lost = [int(float(r["lost_frames"] or 0)) for r in rows]
    reacq = [int(float(r["reacquires"] or 0)) for r in rows]

    bbox_present = 0
    for r in rows:
        if r["cx"] not in ("", None):
            bbox_present += 1

    finite_infer = [v for v in infer if math.isfinite(v) and v > 0]
    finite_detfps = [v for v in detfps if math.isfinite(v) and v > 0]
    finite_trkfps = [v for v in trkfps if math.isfinite(v) and v > 0]
    finite_jit = [v for v in jit if math.isfinite(v) and v >= 0]

    return {
        "frames": len(rows),
        "avg_infer_ms": statistics.fmean(finite_infer) if finite_infer else math.nan,
        "avg_detector_fps": statistics.fmean(finite_detfps) if finite_detfps else math.nan,
        "avg_tracker_fps": statistics.fmean(finite_trkfps) if finite_trkfps else math.nan,
        "mean_jitter_px": statistics.fmean(finite_jit) if finite_jit else math.nan,
        "p95_jitter_px": percentile(finite_jit, 0.95),
        "lost_peak": max(lost) if lost else 0,
        "reacquires": max(reacq) if reacq else 0,
        "hold_ratio": bbox_present / max(1, len(rows)),
    }


def score(summary):
    # Higher is better. Hold ratio dominates; jitter and long lost runs are penalties.
    hold = summary["hold_ratio"]
    jitter = summary["p95_jitter_px"]
    lost = summary["lost_peak"]
    fps = summary["avg_tracker_fps"]

    if not math.isfinite(jitter):
        jitter = 999.0
    if not math.isfinite(fps):
        fps = 0.0

    return hold * 100.0 + min(fps, 60.0) * 0.12 - jitter * 0.08 - lost * 0.35


def main():
    args = parse_args()
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    base = Path(__file__).resolve().parent / "run_camera.py"
    summaries = []

    for mode in MODES:
        metrics = out_dir / f"{mode}_metrics.csv"
        record = out_dir / f"{mode}_overlay.mp4"

        cmd = [
            args.python,
            str(base),
            "--source", args.video,
            "--model", args.model,
            "--mode", mode,
            "--imgsz", str(args.imgsz),
            "--conf-search", str(args.conf_search),
            "--conf-track", str(args.conf_track),
            "--iou", str(args.iou),
            "--verify-every", str(args.verify_every),
            "--metrics", str(metrics),
            "--record", str(record),
            "--headless",
            "--auto-select",
        ]
        if args.class_id is not None:
            cmd += ["--class-id", str(args.class_id)]

        print("=" * 72)
        print("RUN", mode.upper())
        print(" ".join(cmd))
        print("=" * 72)

        result = subprocess.run(cmd)
        if result.returncode != 0:
            print(f"{mode}: FAILED with exit code {result.returncode}")
            summaries.append({
                "mode": mode,
                "status": "FAIL",
                "score": -9999.0,
                "frames": 0,
                "avg_infer_ms": math.nan,
                "avg_detector_fps": math.nan,
                "avg_tracker_fps": math.nan,
                "mean_jitter_px": math.nan,
                "p95_jitter_px": math.nan,
                "lost_peak": 999,
                "reacquires": 0,
                "hold_ratio": 0.0,
            })
            continue

        s = summarize_csv(metrics)
        s["mode"] = mode
        s["status"] = "PASS"
        s["score"] = score(s)
        summaries.append(s)

    summaries.sort(key=lambda s: s["score"], reverse=True)

    summary_path = out_dir / "tracker_summary.csv"
    fields = [
        "rank", "mode", "status", "score", "frames",
        "hold_ratio", "avg_infer_ms", "avg_detector_fps",
        "avg_tracker_fps", "mean_jitter_px", "p95_jitter_px",
        "lost_peak", "reacquires"
    ]

    with open(summary_path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for rank, s in enumerate(summaries, 1):
            row = {k: s.get(k, "") for k in fields}
            row["rank"] = rank
            w.writerow(row)

    print()
    print("=" * 72)
    print("RESULT")
    print("=" * 72)
    for rank, s in enumerate(summaries, 1):
        print(
            f"{rank}. {s['mode'].upper():7s} "
            f"score={s['score']:.2f} "
            f"hold={s['hold_ratio']*100:.1f}% "
            f"trk={s['avg_tracker_fps']:.1f}fps "
            f"jitter95={s['p95_jitter_px']:.2f}px "
            f"lost_peak={s['lost_peak']} "
            f"reacq={s['reacquires']}"
        )

    if summaries:
        print()
        print(f"WINNER: {summaries[0]['mode'].upper()}")
    print(f"summary: {summary_path.resolve()}")


if __name__ == "__main__":
    main()
