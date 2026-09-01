import math
import random

from tracking_core import KalmanBBoxTracker, nearest_same_class


def truth(frame):
    cx = 140 + frame * 2.7
    cy = 220 + 36 * math.sin(frame * .07)
    w = 64 + 7 * math.sin(frame * .05)
    h = 46 + 5 * math.cos(frame * .04)
    return cx - w / 2, cy - h / 2, w, h


def detections(frame, gt):
    if 50 <= frame <= 59 or 112 <= frame <= 119:
        return []

    x, y, w, h = gt
    return [
        {
            "class_id": 0,
            "class_name": "target",
            "confidence": .76,
            "bbox": (
                x + random.gauss(0, 2.7),
                y + random.gauss(0, 2.7),
                max(8, w + random.gauss(0, 2.0)),
                max(8, h + random.gauss(0, 1.8)),
            ),
        },
        {
            "class_id": 0,
            "class_name": "target",
            "confidence": .88,
            "bbox": (610, 90, 62, 45),
        },
    ]


def iou(a, b):
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    x1, y1 = max(ax, bx), max(ay, by)
    x2, y2 = min(ax + aw, bx + bw), min(ay + ah, by + bh)
    iw, ih = max(0, x2 - x1), max(0, y2 - y1)
    inter = iw * ih
    union = aw * ah + bw * bh - inter
    return inter / union if union > 0 else 0.0


def main():
    random.seed(7)
    tr = KalmanBBoxTracker(timeout_s=1.0)
    values = []
    wrong = 0
    reacq = 0

    for f in range(180):
        gt = truth(f)
        ds = detections(f, gt)

        if f == 0:
            tr.init(ds[0]["bbox"], now=0.0)
        else:
            pred = tr.predict()
            match = nearest_same_class(ds, pred, 0, 120)
            if match is not None:
                was_lost = tr.lost_frames > 0
                tr.correct(match["bbox"], match["confidence"], now=f / 30.0)
                if was_lost:
                    reacq += 1
            else:
                tr.missed(now=f / 30.0)

        if tr.bbox is not None:
            values.append(iou(tr.bbox, gt))
            cx = tr.bbox[0] + tr.bbox[2] / 2
            cy = tr.bbox[1] + tr.bbox[3] / 2
            gx = gt[0] + gt[2] / 2
            gy = gt[1] + gt[3] / 2
            dg = math.hypot(cx - gx, cy - gy)
            dd = math.hypot(cx - 641, cy - 112.5)
            if dd < dg:
                wrong += 1

    mean_iou = sum(values) / len(values)
    print(f"mean_iou={mean_iou:.4f}")
    print(f"wrong_jumps={wrong}")
    print(f"reacquires={reacq}")

    assert mean_iou > .45
    assert wrong == 0
    assert reacq >= 2
    print("PASS")


if __name__ == "__main__":
    main()
