import argparse
import csv
import time
from pathlib import Path

import cv2
from ultralytics import YOLO

from reference_tracker import Detection, ReferenceKalmanTracker


def parse_args():
    p = argparse.ArgumentParser(
        description="Passive YOLO + Kalman reference benchmark"
    )
    p.add_argument("--video", required=True, help="Input MP4/video path")
    p.add_argument("--model", required=True, help="Ultralytics model path")
    p.add_argument("--class-id", type=int, default=None, help="Target class ID")
    p.add_argument("--conf-search", type=float, default=0.25)
    p.add_argument("--conf-track", type=float, default=0.15)
    p.add_argument("--imgsz", type=int, default=640)
    p.add_argument("--device", default=None)
    p.add_argument("--output", default="reference_overlay.mp4")
    p.add_argument("--csv", default="reference_metrics.csv")
    p.add_argument("--no-display", action="store_true")
    return p.parse_args()


def to_detections(result, model):
    out = []
    boxes = result.boxes
    if boxes is None:
        return out

    for box in boxes:
        conf = float(box.conf[0])
        cls_id = int(box.cls[0])
        x1, y1, x2, y2 = [float(v) for v in box.xyxy[0]]
        out.append(
            Detection(
                x=x1,
                y=y1,
                w=max(1.0, x2 - x1),
                h=max(1.0, y2 - y1),
                conf=conf,
                cls_id=cls_id,
                cls_name=str(model.names.get(cls_id, cls_id)),
            )
        )
    return out


def draw_bbox(frame, bbox, color, text):
    if bbox is None:
        return
    x, y, w, h = bbox
    p1 = (int(x), int(y))
    p2 = (int(x + w), int(y + h))
    cv2.rectangle(frame, p1, p2, color, 2)
    cv2.putText(
        frame,
        text,
        (p1[0], max(20, p1[1] - 8)),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.55,
        color,
        2,
    )


def main():
    args = parse_args()
    model = YOLO(args.model)

    cap = cv2.VideoCapture(args.video)
    if not cap.isOpened():
        raise SystemExit(f"Cannot open video: {args.video}")

    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = cap.get(cv2.CAP_PROP_FPS)
    if fps <= 1:
        fps = 30.0

    writer = cv2.VideoWriter(
        args.output,
        cv2.VideoWriter_fourcc(*"mp4v"),
        fps,
        (width, height),
    )

    tracker = ReferenceKalmanTracker(
        width,
        height,
        target_class=args.class_id,
        search_margin=max(70, int(min(width, height) * 0.10)),
        timeout_s=2.0,
        history_len=3,
    )

    csv_file = open(args.csv, "w", newline="", encoding="utf-8")
    log = csv.writer(csv_file)
    log.writerow([
        "frame", "mode", "detected", "det_count", "infer_ms",
        "track_x", "track_y", "track_w", "track_h",
        "lost_frames", "corrections", "reacquires", "assoc_distance"
    ])

    frame_idx = 0
    started = time.perf_counter()
    infer_total = 0.0

    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                break

            frame_idx += 1
            conf = args.conf_track if tracker.tracking else args.conf_search

            t0 = time.perf_counter()
            results = model.predict(
                frame,
                conf=conf,
                imgsz=args.imgsz,
                verbose=False,
                device=args.device,
                max_det=100,
            )
            infer_ms = (time.perf_counter() - t0) * 1000.0
            infer_total += infer_ms

            detections = to_detections(results[0], model)
            bbox, corrected = tracker.update(detections)

            # Draw raw detector candidates.
            for d in detections:
                if args.class_id is None or d.cls_id == args.class_id:
                    draw_bbox(
                        frame,
                        (d.x, d.y, d.w, d.h),
                        (255, 200, 0),
                        f"{d.cls_name} {d.conf:.2f}",
                    )

            mode = "TRACK" if tracker.tracking else "SEARCH"
            state = "YOLO" if corrected else ("PREDICT" if tracker.tracking else "NONE")
            draw_bbox(frame, bbox, (0, 255, 0), f"{mode}/{state}")

            cv2.putText(
                frame,
                f"{mode} infer={infer_ms:.1f}ms lost={tracker.lost_frames} "
                f"reacq={tracker.reacquires}",
                (12, 28),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.65,
                (0, 255, 0),
                2,
            )

            if bbox is None:
                row_bbox = ["", "", "", ""]
            else:
                row_bbox = [f"{v:.3f}" for v in bbox]

            log.writerow([
                frame_idx, mode, int(corrected), len(detections),
                f"{infer_ms:.3f}", *row_bbox,
                tracker.lost_frames, tracker.corrections,
                tracker.reacquires, f"{tracker.last_assoc_distance:.3f}",
            ])

            writer.write(frame)

            if not args.no_display:
                cv2.imshow("Reference YOLO + Kalman", frame)
                if cv2.waitKey(1) & 0xFF in (ord("q"), 27):
                    break
    finally:
        elapsed = time.perf_counter() - started
        cap.release()
        writer.release()
        csv_file.close()
        cv2.destroyAllWindows()

    proc_fps = frame_idx / elapsed if elapsed > 0 else 0.0
    avg_infer = infer_total / max(1, frame_idx)
    print()
    print("REFERENCE BENCHMARK DONE")
    print(f"frames={frame_idx}")
    print(f"processing_fps={proc_fps:.2f}")
    print(f"avg_infer_ms={avg_infer:.2f}")
    print(f"corrections={tracker.corrections}")
    print(f"reacquires={tracker.reacquires}")
    print(f"overlay={Path(args.output).resolve()}")
    print(f"metrics={Path(args.csv).resolve()}")


if __name__ == "__main__":
    main()
