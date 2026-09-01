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
