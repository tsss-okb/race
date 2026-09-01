import math
import time
from dataclasses import dataclass
from collections import deque

import cv2
import numpy as np


@dataclass
class Detection:
    x: float
    y: float
    w: float
    h: float
    conf: float
    cls_id: int
    cls_name: str

    @property
    def cx(self):
        return self.x + self.w * 0.5

    @property
    def cy(self):
        return self.y + self.h * 0.5


class ReferenceKalmanTracker:
    """
    Passive reference tracker derived from the stable logic used in the older
    YOLO tracking project:
      detection -> nearest same-class association -> Kalman(cx, cy, w, h)
      -> prediction during short detection loss.

    No MAVLink / flight-control output is present.
    """

    def __init__(
        self,
        frame_width,
        frame_height,
        target_class=None,
        search_margin=100,
        timeout_s=2.0,
        history_len=3,
    ):
        self.frame_width = int(frame_width)
        self.frame_height = int(frame_height)
        self.target_class = target_class
        self.search_margin = int(search_margin)
        self.timeout_s = float(timeout_s)

        self.tracking = False
        self.bbox = None
        self.last_detection_time = 0.0
        self.history = deque(maxlen=history_len)
        self.kf = None

        self.lost_frames = 0
        self.corrections = 0
        self.reacquires = 0
        self.last_assoc_distance = 0.0

    def _new_kalman(self, bbox):
        kf = cv2.KalmanFilter(8, 4)
        kf.measurementMatrix = np.eye(4, 8, dtype=np.float32)
        kf.transitionMatrix = np.eye(8, dtype=np.float32)
        for i in range(4):
            kf.transitionMatrix[i, i + 4] = 1.0

        kf.processNoiseCov = np.eye(8, dtype=np.float32) * 5e-3
        kf.measurementNoiseCov = np.eye(4, dtype=np.float32) * 1e-1
        kf.errorCovPost = np.eye(8, dtype=np.float32)

        x, y, w, h = bbox
        state = np.array(
            [x + w / 2.0, y + h / 2.0, w, h, 0, 0, 0, 0],
            dtype=np.float32,
        ).reshape(-1, 1)
        kf.statePre = state.copy()
        kf.statePost = state.copy()
        return kf

    def initialize(self, det: Detection, now=None):
        now = time.monotonic() if now is None else float(now)
        self.target_class = det.cls_id if self.target_class is None else self.target_class
        self.bbox = (det.x, det.y, det.w, det.h)
        self.kf = self._new_kalman(self.bbox)
        self.history.clear()
        self.history.append(self.bbox)
        self.tracking = True
        self.last_detection_time = now
        self.lost_frames = 0

    def reset(self):
        self.tracking = False
        self.bbox = None
        self.kf = None
        self.history.clear()
        self.lost_frames = 0

    def predicted_bbox(self):
        if self.bbox is None:
            return None
        return self.bbox

    def search_roi(self):
        if self.bbox is None:
            return (0, 0, self.frame_width, self.frame_height)

        x, y, w, h = self.bbox
        sx = max(0, int(x - self.search_margin))
        sy = max(0, int(y - self.search_margin))
        ex = min(self.frame_width, int(x + w + self.search_margin))
        ey = min(self.frame_height, int(y + h + self.search_margin))
        return sx, sy, max(1, ex - sx), max(1, ey - sy)

    def _pick_nearest_same_class(self, detections):
        if not detections:
            return None
        if self.bbox is None:
            valid = [
                d for d in detections
                if self.target_class is None or d.cls_id == self.target_class
            ]
            return max(valid, key=lambda d: d.conf) if valid else None

        px, py, pw, ph = self.bbox
        pcx = px + pw / 2.0
        pcy = py + ph / 2.0

        best = None
        best_dist = float("inf")
        for det in detections:
            if self.target_class is not None and det.cls_id != self.target_class:
                continue

            dist = math.hypot(det.cx - pcx, det.cy - pcy)
            # Simple spatial gate from the old working design.
            gate = max(self.search_margin * 1.75, max(pw, ph) * 2.5)
            if dist > gate:
                continue

            if dist < best_dist:
                best_dist = dist
                best = det

        self.last_assoc_distance = 0.0 if best is None else best_dist
        return best

    def update(self, detections, now=None):
        now = time.monotonic() if now is None else float(now)

        if not self.tracking:
            det = self._pick_nearest_same_class(detections)
            if det is not None:
                self.initialize(det, now)
            return self.bbox, det is not None

        # Predict every frame first.
        pred = self.kf.predict()
        pred_cx, pred_cy, pred_w, pred_h = [float(v) for v in pred[:4, 0]]
        pred_w = max(2.0, pred_w)
        pred_h = max(2.0, pred_h)
        self.bbox = (
            pred_cx - pred_w / 2.0,
            pred_cy - pred_h / 2.0,
            pred_w,
            pred_h,
        )

        det = self._pick_nearest_same_class(detections)
        if det is not None:
            was_lost = self.lost_frames > 0
            raw_bbox = (det.x, det.y, det.w, det.h)
            self.history.append(raw_bbox)

            avg = np.mean(np.asarray(self.history, dtype=np.float32), axis=0)
            mx, my, mw, mh = [float(v) for v in avg]
            measurement = np.array(
                [mx + mw / 2.0, my + mh / 2.0, mw, mh],
                dtype=np.float32,
            ).reshape(-1, 1)

            corrected = self.kf.correct(measurement)
            cx, cy, w, h = [float(v) for v in corrected[:4, 0]]
            w = max(2.0, w)
            h = max(2.0, h)
            self.bbox = (cx - w / 2.0, cy - h / 2.0, w, h)

            self.last_detection_time = now
            self.lost_frames = 0
            self.corrections += 1
            if was_lost:
                self.reacquires += 1
            return self.bbox, True

        self.lost_frames += 1
        if now - self.last_detection_time > self.timeout_s:
            self.reset()
            return None, False

        return self.bbox, False


def bbox_iou(a, b):
    if a is None or b is None:
        return 0.0
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    a2x, a2y = ax + aw, ay + ah
    b2x, b2y = bx + bw, by + bh

    ix1, iy1 = max(ax, bx), max(ay, by)
    ix2, iy2 = min(a2x, b2x), min(a2y, b2y)
    iw, ih = max(0.0, ix2 - ix1), max(0.0, iy2 - iy1)
    inter = iw * ih
    union = aw * ah + bw * bh - inter
    return inter / union if union > 1e-9 else 0.0
