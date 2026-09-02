import math
import time
from collections import deque

import cv2
import numpy as np


class KalmanBBoxTracker:
    def __init__(self, timeout_s=1.5):
        self.timeout_s = float(timeout_s)
        self.kf = None
        self.bbox = None
        self.history = deque(maxlen=3)
        self.last_detection = 0.0
        self.lost_frames = 0
        self.reacquires = 0
        self.corrections = 0

    @property
    def active(self):
        return self.kf is not None and self.bbox is not None

    def reset(self):
        self.kf = None
        self.bbox = None
        self.history.clear()
        self.last_detection = 0.0
        self.lost_frames = 0

    def init(self, bbox, now=None):
        now = time.monotonic() if now is None else float(now)
        x, y, w, h = [float(v) for v in bbox]
        self.kf = cv2.KalmanFilter(8, 4)
        self.kf.measurementMatrix = np.eye(4, 8, dtype=np.float32)
        self.kf.transitionMatrix = np.eye(8, dtype=np.float32)
        for i in range(4):
            self.kf.transitionMatrix[i, i + 4] = 1.0

        self.kf.processNoiseCov = np.eye(8, dtype=np.float32) * 5e-3
        self.kf.measurementNoiseCov = np.eye(4, dtype=np.float32) * 1e-1
        self.kf.errorCovPost = np.eye(8, dtype=np.float32)

        state = np.array(
            [x + w / 2, y + h / 2, w, h, 0, 0, 0, 0],
            dtype=np.float32,
        ).reshape(-1, 1)
        self.kf.statePre = state.copy()
        self.kf.statePost = state.copy()

        self.bbox = (x, y, w, h)
        self.history.clear()
        self.history.append(self.bbox)
        self.last_detection = now
        self.lost_frames = 0

    def predict(self):
        if not self.active:
            return None
        p = self.kf.predict()
        cx, cy, w, h = [float(v) for v in p[:4, 0]]
        w = max(2.0, w)
        h = max(2.0, h)
        self.bbox = (cx - w / 2, cy - h / 2, w, h)
        return self.bbox

    def correct(self, bbox, confidence=1.0, now=None):
        now = time.monotonic() if now is None else float(now)
        was_lost = self.lost_frames > 0

        raw = tuple(float(v) for v in bbox)
        self.history.append(raw)
        avg = np.mean(np.asarray(self.history, dtype=np.float32), axis=0)
        x, y, w, h = [float(v) for v in avg]

        z = np.array([x + w / 2, y + h / 2, w, h], dtype=np.float32).reshape(-1, 1)
        c = self.kf.correct(z)
        cx, cy, cw, ch = [float(v) for v in c[:4, 0]]
        cw = max(2.0, cw)
        ch = max(2.0, ch)

        self.bbox = (cx - cw / 2, cy - ch / 2, cw, ch)
        self.last_detection = now
        self.lost_frames = 0
        self.corrections += 1
        if was_lost:
            self.reacquires += 1
        return self.bbox

    def missed(self, now=None):
        now = time.monotonic() if now is None else float(now)
        self.lost_frames += 1
        if self.last_detection and now - self.last_detection > self.timeout_s:
            self.reset()
            return None
        return self.bbox


def create_opencv_tracker(name):
    name = name.lower()
    constructors = {
        "csrt": ("TrackerCSRT_create",),
        "kcf": ("TrackerKCF_create",),
        "mosse": ("TrackerMOSSE_create",),
    }

    if name not in constructors:
        raise ValueError(f"Unknown tracker: {name}")

    attr = constructors[name][0]
    if hasattr(cv2, attr):
        return getattr(cv2, attr)()

    legacy = getattr(cv2, "legacy", None)
    if legacy is not None and hasattr(legacy, attr):
        return getattr(legacy, attr)()

    raise RuntimeError(f"OpenCV tracker unavailable: {name}")


def bbox_center(b):
    x, y, w, h = b
    return x + w / 2, y + h / 2


def nearest_same_class(detections, predicted_bbox, class_id, gate_px):
    candidates = [d for d in detections if class_id is None or d["class_id"] == class_id]
    if not candidates:
        return None

    if predicted_bbox is None:
        return max(candidates, key=lambda d: d["confidence"])

    pcx, pcy = bbox_center(predicted_bbox)
    best = None
    best_d = float("inf")

    for d in candidates:
        cx, cy = bbox_center(d["bbox"])
        dist = math.hypot(cx - pcx, cy - pcy)
        if dist <= gate_px and dist < best_d:
            best_d = dist
            best = d

    return best


class MotionPredictor2D:
    """Small motion prior for passive visual reacquisition.

    Keeps only the last detector-confirmed center, bbox size and an EMA velocity.
    It is intentionally simpler than a full second tracker.
    """

    def __init__(self, velocity_alpha=0.35, decay=0.985):
        self.velocity_alpha = float(velocity_alpha)
        self.decay = float(decay)
        self.center = None
        self.velocity = np.zeros(2, dtype=np.float32)
        self.bbox = None
        self.frame_index = 0

    @property
    def active(self):
        return self.center is not None and self.bbox is not None

    def reset(self, bbox=None, frame_index=0):
        self.center = None
        self.velocity[:] = 0
        self.bbox = None
        self.frame_index = int(frame_index)
        if bbox is not None:
            self.initialize(bbox, frame_index)

    def initialize(self, bbox, frame_index=0):
        self.bbox = tuple(float(v) for v in bbox)
        self.center = np.asarray(bbox_center(self.bbox), dtype=np.float32)
        self.velocity[:] = 0
        self.frame_index = int(frame_index)

    def update(self, bbox, frame_index):
        bbox = tuple(float(v) for v in bbox)
        c = np.asarray(bbox_center(bbox), dtype=np.float32)
        fi = int(frame_index)

        if self.center is None:
            self.initialize(bbox, fi)
            return

        dt = max(1, fi - self.frame_index)
        measured_v = (c - self.center) / float(dt)
        a = self.velocity_alpha
        self.velocity = (1.0 - a) * self.velocity + a * measured_v
        self.center = c
        self.bbox = bbox
        self.frame_index = fi

    def predict_center(self, frame_index):
        if self.center is None:
            return None
        dt = max(0, int(frame_index) - self.frame_index)
        decay = self.decay ** max(0, dt - 1)
        return self.center + self.velocity * float(dt) * decay

    def predict_bbox(self, frame_index):
        pc = self.predict_center(frame_index)
        if pc is None or self.bbox is None:
            return None
        _, _, w, h = self.bbox
        return (float(pc[0] - w / 2), float(pc[1] - h / 2), float(w), float(h))


def nearest_same_class_to_point(
    detections,
    predicted_center,
    class_id,
    gate_px,
    reference_bbox=None,
    max_scale_ratio=3.0,
):
    """Associate a detector result with a predicted center and previous target size."""
    if predicted_center is None:
        return None

    px, py = float(predicted_center[0]), float(predicted_center[1])
    best = None
    best_score = float("inf")

    ref_w = ref_h = None
    if reference_bbox is not None:
        _, _, ref_w, ref_h = reference_bbox
        ref_w = max(1.0, float(ref_w))
        ref_h = max(1.0, float(ref_h))

    for d in detections:
        if class_id is not None and d["class_id"] != class_id:
            continue

        x, y, w, h = d["bbox"]
        w, h = max(1.0, float(w)), max(1.0, float(h))

        if ref_w is not None:
            ratio = max(w / ref_w, ref_w / w, h / ref_h, ref_h / h)
            if ratio > max_scale_ratio:
                continue
        else:
            ratio = 1.0

        cx, cy = bbox_center(d["bbox"])
        dist = math.hypot(cx - px, cy - py)
        if dist > gate_px:
            continue

        # Position dominates. Confidence and scale consistency break ties.
        score = dist + 5.0 * abs(math.log(max(1e-6, ratio))) - 4.0 * d["confidence"]
        if score < best_score:
            best_score = score
            best = d

    return best
