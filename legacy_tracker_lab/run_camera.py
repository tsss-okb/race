import argparse
import csv
import time
from pathlib import Path

import cv2
import numpy as np
from ultralytics import YOLO

from tracking_core import (
    KalmanBBoxTracker,
    MotionPredictor2D,
    bbox_center,
    create_opencv_tracker,
    nearest_same_class,
    nearest_same_class_to_point,
)


def parse_source(value):
    try:
        return int(value)
    except ValueError:
        return value


def parse_args():
    p = argparse.ArgumentParser(description="Legacy passive YOLO tracker lab")
    p.add_argument("--source", default="0", help="USB camera index or video path")
    p.add_argument("--model", default="yolo26n.pt")
    p.add_argument("--mode", choices=["kcf-hybrid", "kalman", "csrt", "kcf", "mosse"], default="kcf-hybrid")
    p.add_argument("--class-id", type=int, default=None)
    p.add_argument("--conf-search", type=float, default=0.20)
    p.add_argument("--conf-track", type=float, default=0.15)
    p.add_argument("--iou", type=float, default=0.40)
    p.add_argument("--imgsz", type=int, default=640)
    p.add_argument("--verify-every", type=int, default=3)
    p.add_argument("--metrics", default="legacy_metrics.csv")
    p.add_argument("--record", default="")
    p.add_argument("--headless", action="store_true")
    p.add_argument("--auto-select", action="store_true",
                   help="Automatically select the highest-confidence candidate")
    return p.parse_args()


def yolo_detect(model, frame, conf, iou, imgsz, class_id=None):
    t0 = time.perf_counter()
    result = model.predict(
        frame,
        conf=conf,
        iou=iou,
        imgsz=imgsz,
        verbose=False,
        max_det=100,
    )[0]
    infer_ms = (time.perf_counter() - t0) * 1000.0

    detections = []
    if result.boxes is not None:
        for box in result.boxes:
            cid = int(box.cls[0])
            if class_id is not None and cid != class_id:
                continue
            confv = float(box.conf[0])
            x1, y1, x2, y2 = [float(v) for v in box.xyxy[0]]
            detections.append(
                {
                    "class_id": cid,
                    "class_name": str(model.names.get(cid, cid)),
                    "confidence": confv,
                    "bbox": (x1, y1, max(1.0, x2 - x1), max(1.0, y2 - y1)),
                }
            )
    return detections, infer_ms


class App:
    def __init__(self, args):
        self.args = args
        self.model = YOLO(args.model)
        self.cap = cv2.VideoCapture(parse_source(args.source))
        if not self.cap.isOpened():
            raise SystemExit(f"Cannot open source: {args.source}")

        self.w = int(self.cap.get(cv2.CAP_PROP_FRAME_WIDTH)) or 1280
        self.h = int(self.cap.get(cv2.CAP_PROP_FRAME_HEIGHT)) or 720
        self.fps = self.cap.get(cv2.CAP_PROP_FPS)
        if self.fps <= 1:
            self.fps = 30.0

        self.mode = args.mode
        self.selected_class = args.class_id
        self.selected = False
        self.pending_click = None

        self.kalman = KalmanBBoxTracker(timeout_s=1.5)
        self.cv_tracker = None
        self.cv_bbox = None
        self.track_frame = 0
        self.reacquires = 0
        self.lost_frames = 0

        self.motion = MotionPredictor2D(velocity_alpha=0.35, decay=0.985)
        self.hybrid_state = "SEARCH"
        self.hybrid_drift_rejects = 0

        self.last_detections = []
        self.last_infer_ms = 0.0
        self.detector_fps = 0.0
        self.last_det_done = 0.0

        self.writer = None
        if args.record:
            self.writer = cv2.VideoWriter(
                args.record,
                cv2.VideoWriter_fourcc(*"mp4v"),
                self.fps,
                (self.w, self.h),
            )

        self.metrics = open(args.metrics, "w", newline="", encoding="utf-8")
        self.csv = csv.writer(self.metrics)
        self.csv.writerow([
            "frame", "mode", "selected_class", "det_count", "infer_ms",
            "detector_fps", "tracker_fps", "lost_frames", "reacquires",
            "cx", "cy", "w", "h", "jitter_px"
        ])

        self.prev_center = None
        self.last_frame_ts = time.perf_counter()
        self.frame_idx = 0

        if not args.headless:
            cv2.namedWindow("Legacy Tracker Lab", cv2.WINDOW_NORMAL)
            cv2.setMouseCallback("Legacy Tracker Lab", self.on_mouse)

    def on_mouse(self, event, x, y, flags, param):
        if event == cv2.EVENT_LBUTTONUP:
            self.pending_click = (x, y)

    def reset(self):
        self.selected = False
        self.selected_class = self.args.class_id
        self.kalman.reset()
        self.cv_tracker = None
        self.cv_bbox = None
        self.track_frame = 0
        self.reacquires = 0
        self.lost_frames = 0
        self.motion.reset()
        self.hybrid_state = "SEARCH"
        self.hybrid_drift_rejects = 0
        self.prev_center = None

    def switch_mode(self, mode):
        if self.mode == mode:
            return
        self.mode = mode
        self.reset()
        print(f"MODE -> {mode.upper()}")

    def pick_clicked_detection(self):
        if self.pending_click is None:
            return None
        x, y = self.pending_click
        self.pending_click = None

        best = None
        best_score = float("inf")
        for d in self.last_detections:
            bx, by, bw, bh = d["bbox"]
            inside = bx <= x <= bx + bw and by <= y <= by + bh
            cx, cy = bbox_center(d["bbox"])
            dist = (cx - x) ** 2 + (cy - y) ** 2
            if inside:
                dist *= 0.05
            if dist < best_score:
                best_score = dist
                best = d
        return best

    def start_from_detection(self, frame, det):
        self.selected = True
        self.selected_class = det["class_id"]
        bbox = det["bbox"]
        if self.mode == "kalman":
            self.kalman.init(bbox)
        else:
            tracker_name = "kcf" if self.mode == "kcf-hybrid" else self.mode
            self.cv_tracker = create_opencv_tracker(tracker_name)
            init_bbox = tuple(int(round(v)) for v in bbox)
            ok = self.cv_tracker.init(frame, init_bbox)
            if ok is False:
                self.cv_tracker = None
                self.selected = False
                return
            self.cv_bbox = tuple(float(v) for v in init_bbox)
            self.track_frame = 0
            if self.mode == "kcf-hybrid":
                self.motion.initialize(self.cv_bbox, self.frame_idx)
                self.hybrid_state = "LOCK"
                self.lost_frames = 0

    def kalman_step(self, frame):
        detections, infer_ms = yolo_detect(
            self.model,
            frame,
            self.args.conf_track if self.selected else self.args.conf_search,
            self.args.iou,
            self.args.imgsz,
            self.selected_class if self.selected else self.args.class_id,
        )
        self.update_detector_stats(infer_ms)
        self.last_detections = detections

        if not self.selected:
            clicked = self.pick_clicked_detection()
            if clicked is None and self.args.auto_select and detections:
                clicked = max(detections, key=lambda d: d["confidence"])
            if clicked is not None:
                self.start_from_detection(frame, clicked)
            return

        predicted = self.kalman.predict()
        gate = max(80, int(min(self.w, self.h) * 0.15))
        match = nearest_same_class(detections, predicted, self.selected_class, gate)

        if match is not None:
            was_lost = self.kalman.lost_frames > 0
            self.kalman.correct(match["bbox"], match["confidence"])
            if was_lost:
                self.reacquires += 1
            self.lost_frames = 0
        else:
            self.kalman.missed()
            self.lost_frames = self.kalman.lost_frames
            if not self.kalman.active:
                self.reset()

    def opencv_step(self, frame):
        need_detection = (
            not self.selected
            or self.cv_tracker is None
            or self.track_frame % max(1, self.args.verify_every) == 0
        )

        if need_detection:
            detections, infer_ms = yolo_detect(
                self.model,
                frame,
                self.args.conf_track if self.selected else self.args.conf_search,
                self.args.iou,
                self.args.imgsz,
                self.selected_class if self.selected else self.args.class_id,
            )
            self.update_detector_stats(infer_ms)
            self.last_detections = detections

            if not self.selected:
                clicked = self.pick_clicked_detection()
                if clicked is None and self.args.auto_select and detections:
                    clicked = max(detections, key=lambda d: d["confidence"])
                if clicked is not None:
                    self.start_from_detection(frame, clicked)
                return

            if self.cv_bbox is not None:
                gate = max(80, int(min(self.w, self.h) * 0.15))
                match = nearest_same_class(
                    detections, self.cv_bbox, self.selected_class, gate
                )
                if match is not None:
                    self.cv_tracker = create_opencv_tracker(self.mode)
                    init_bbox = tuple(int(round(v)) for v in match["bbox"])
                    self.cv_tracker.init(frame, init_bbox)
                    self.cv_bbox = tuple(float(v) for v in init_bbox)
                    self.reacquires += 1

        if self.cv_tracker is not None:
            ok, bbox = self.cv_tracker.update(frame)
            self.track_frame += 1
            if ok:
                self.cv_bbox = tuple(float(v) for v in bbox)
                self.lost_frames = 0
            else:
                self.lost_frames += 1
                self.cv_tracker = None
                self.cv_bbox = None

    def _hybrid_detect(self, frame, tracking=True):
        detections, infer_ms = yolo_detect(
            self.model,
            frame,
            self.args.conf_track if tracking else self.args.conf_search,
            self.args.iou,
            self.args.imgsz,
            self.selected_class if tracking else self.args.class_id,
        )
        self.update_detector_stats(infer_ms)
        self.last_detections = detections
        return detections

    def _hybrid_match(self, detections, gate_px):
        predicted = self.motion.predict_center(self.frame_idx)
        return nearest_same_class_to_point(
            detections,
            predicted,
            self.selected_class,
            gate_px,
            reference_bbox=self.motion.bbox,
            max_scale_ratio=3.0,
        )

    def _hybrid_reinitialize(self, frame, det, reacquire=False):
        bbox = tuple(int(round(v)) for v in det["bbox"])
        self.cv_tracker = create_opencv_tracker("kcf")
        ok = self.cv_tracker.init(frame, bbox)
        if ok is False:
            return False

        self.cv_bbox = tuple(float(v) for v in bbox)
        self.motion.update(det["bbox"], self.frame_idx)
        self.hybrid_state = "LOCK"
        self.lost_frames = 0
        if reacquire:
            self.reacquires += 1
        return True

    def kcf_hybrid_step(self, frame):
        # SEARCH: run YOLO until a box is selected.
        if not self.selected:
            detections = self._hybrid_detect(frame, tracking=False)
            clicked = self.pick_clicked_detection()
            if clicked is None and self.args.auto_select and detections:
                clicked = max(detections, key=lambda d: d["confidence"])
            if clicked is not None:
                self.start_from_detection(frame, clicked)
            return

        predicted_center = self.motion.predict_center(self.frame_idx)
        predicted_bbox = self.motion.predict_bbox(self.frame_idx)

        if self.hybrid_state == "LOCK":
            ok = False
            bbox = None
            if self.cv_tracker is not None:
                ok, bbox = self.cv_tracker.update(frame)

            self.track_frame += 1

            if ok:
                self.cv_bbox = tuple(float(v) for v in bbox)

            verify = (
                not ok
                or self.track_frame % max(1, self.args.verify_every) == 0
            )

            if verify:
                detections = self._hybrid_detect(frame, tracking=True)
                base_gate = max(20.0, min(self.w, self.h) * 0.12)
                match = self._hybrid_match(detections, base_gate)

                if match is not None:
                    old_center = (
                        np.asarray(bbox_center(self.cv_bbox), dtype=np.float32)
                        if self.cv_bbox is not None else predicted_center
                    )
                    new_center = np.asarray(bbox_center(match["bbox"]), dtype=np.float32)
                    correction = (
                        float(np.linalg.norm(old_center - new_center))
                        if old_center is not None else 0.0
                    )
                    self._hybrid_reinitialize(
                        frame,
                        match,
                        reacquire=correction > 3.0,
                    )
                    return

                drift_gate = max(22.0, min(self.w, self.h) * 0.14)
                drift = float("inf")
                if ok and predicted_center is not None and self.cv_bbox is not None:
                    drift = float(
                        np.linalg.norm(
                            np.asarray(bbox_center(self.cv_bbox), dtype=np.float32)
                            - predicted_center
                        )
                    )

                # Reject a tracker that has failed OR has wandered away from
                # the detector-confirmed motion prior.
                if (not ok) or drift > drift_gate:
                    self.hybrid_state = "COAST"
                    self.hybrid_drift_rejects += 1
                    self.lost_frames = 1
                    self.cv_tracker = None
                    self.cv_bbox = predicted_bbox
                    return

            if ok:
                self.hybrid_state = "LOCK"
                self.lost_frames = 0
                return

            self.hybrid_state = "COAST"
            self.lost_frames = 1
            self.cv_tracker = None
            self.cv_bbox = predicted_bbox
            return

        # COAST / REACQUIRE: YOLO every frame. Search window expands with time.
        self.hybrid_state = "COAST"
        self.lost_frames += 1
        detections = self._hybrid_detect(frame, tracking=True)

        base_gate = max(18.0, min(self.w, self.h) * 0.10)
        gate = min(
            max(self.w, self.h) * 0.45,
            base_gate + 1.5 * self.lost_frames,
        )
        match = self._hybrid_match(detections, gate)

        # After a longer loss, allow a broad same-class/size search. The
        # motion/scale cost still prevents a blind max-confidence jump.
        if match is None and self.lost_frames >= 6:
            gate = max(self.w, self.h) * 0.45
            match = self._hybrid_match(detections, gate)

        if match is not None:
            if self._hybrid_reinitialize(frame, match, reacquire=True):
                self.hybrid_state = "REACQUIRED"
                return

        self.cv_bbox = self.motion.predict_bbox(self.frame_idx)

    def update_detector_stats(self, infer_ms):
        now = time.perf_counter()
        if self.last_det_done:
            f = 1.0 / max(1e-6, now - self.last_det_done)
            self.detector_fps = f if self.detector_fps == 0 else 0.8 * self.detector_fps + 0.2 * f
        self.last_det_done = now
        self.last_infer_ms = infer_ms

    def current_bbox(self):
        if self.mode == "kalman":
            return self.kalman.bbox if self.kalman.active else None
        return self.cv_bbox

    def draw(self, frame, tracker_fps):
        for d in self.last_detections:
            x, y, w, h = [int(v) for v in d["bbox"]]
            color = (255, 220, 0)
            if self.selected_class is not None and d["class_id"] == self.selected_class:
                color = (0, 255, 0)
            cv2.rectangle(frame, (x, y), (x + w, y + h), color, 2)
            cv2.putText(
                frame,
                f'{d["class_name"]} {d["confidence"]:.2f}',
                (x, max(18, y - 5)),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.50,
                color,
                2,
            )

        bbox = self.current_bbox()
        if bbox is not None:
            x, y, w, h = [int(v) for v in bbox]
            cv2.rectangle(frame, (x, y), (x + w, y + h), (0, 255, 0), 3)

        cv2.rectangle(frame, (8, 8), (760, 126), (0, 20, 0), -1)
        cv2.putText(frame, f"MODE {self.mode.upper()}  YOLO {self.detector_fps:.1f} FPS / {self.last_infer_ms:.0f} ms",
                    (18, 34), cv2.FONT_HERSHEY_SIMPLEX, 0.62, (0,255,0), 2)
        state = self.hybrid_state if self.mode == "kcf-hybrid" else "TRACK"
        cv2.putText(frame, f"TRACK {tracker_fps:.1f} FPS  {state}  class={self.selected_class}  lost={self.lost_frames}  reacq={self.reacquires}",
                    (18, 62), cv2.FONT_HERSHEY_SIMPLEX, 0.58, (0,255,0), 2)
        cv2.putText(frame, "Click bbox | 0 KCF-HYB  1 Kalman  2 CSRT  3 KCF  4 MOSSE | R reset | Q exit",
                    (18, 92), cv2.FONT_HERSHEY_SIMPLEX, 0.48, (0,255,0), 1)

    def log(self, tracker_fps):
        bbox = self.current_bbox()
        if bbox is None:
            cx = cy = bw = bh = jitter = ""
        else:
            x, y, bw, bh = bbox
            cx, cy = bbox_center(bbox)
            if self.prev_center is None:
                jitter = 0.0
            else:
                jitter = float(np.hypot(cx - self.prev_center[0], cy - self.prev_center[1]))
            self.prev_center = (cx, cy)

        self.csv.writerow([
            self.frame_idx, self.mode, self.selected_class,
            len(self.last_detections), f"{self.last_infer_ms:.3f}",
            f"{self.detector_fps:.3f}", f"{tracker_fps:.3f}",
            self.lost_frames, self.reacquires,
            cx, cy, bw, bh, jitter
        ])

    def run(self):
        try:
            while True:
                ok, frame = self.cap.read()
                if not ok:
                    break

                self.frame_idx += 1
                now = time.perf_counter()
                tracker_fps = 1.0 / max(1e-6, now - self.last_frame_ts)
                self.last_frame_ts = now

                if self.mode == "kcf-hybrid":
                    self.kcf_hybrid_step(frame)
                elif self.mode == "kalman":
                    self.kalman_step(frame)
                else:
                    self.opencv_step(frame)

                self.draw(frame, tracker_fps)
                self.log(tracker_fps)

                if self.writer is not None:
                    self.writer.write(frame)

                if not self.args.headless:
                    cv2.imshow("Legacy Tracker Lab", frame)
                    key = cv2.waitKey(1) & 0xFF
                    if key in (ord("q"), 27):
                        break
                    elif key == ord("r"):
                        self.reset()
                    elif key == ord("0"):
                        self.switch_mode("kcf-hybrid")
                    elif key == ord("1"):
                        self.switch_mode("kalman")
                    elif key == ord("2"):
                        self.switch_mode("csrt")
                    elif key == ord("3"):
                        self.switch_mode("kcf")
                    elif key == ord("4"):
                        self.switch_mode("mosse")
        finally:
            self.cap.release()
            if self.writer is not None:
                self.writer.release()
            self.metrics.close()
            cv2.destroyAllWindows()


if __name__ == "__main__":
    App(parse_args()).run()
