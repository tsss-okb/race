import math
import random

from reference_tracker import Detection, ReferenceKalmanTracker, bbox_iou


def ground_truth(frame):
    cx = 120 + frame * 3.0
    cy = 180 + 38 * math.sin(frame * 0.08)
    w = 70 + 8 * math.sin(frame * 0.05)
    h = 48 + 5 * math.cos(frame * 0.04)
    return (cx - w / 2, cy - h / 2, w, h)


def noisy_detection(frame, gt):
    # Simulate temporary detector loss.
    if 55 <= frame <= 63 or 118 <= frame <= 124:
        return []

    x, y, w, h = gt
    return [
        Detection(
            x=x + random.gauss(0, 3.0),
            y=y + random.gauss(0, 3.0),
            w=max(10, w + random.gauss(0, 2.5)),
            h=max(10, h + random.gauss(0, 2.0)),
            conf=0.75,
            cls_id=0,
            cls_name="target",
        ),
        # Distractor of the same class, deliberately far away.
        Detection(
            x=500 + 15 * math.sin(frame * .1),
            y=80,
            w=60,
            h=45,
            conf=0.85,
            cls_id=0,
            cls_name="target",
        ),
    ]


def main():
    random.seed(7)
    tracker = ReferenceKalmanTracker(
        frame_width=960,
        frame_height=540,
        target_class=0,
        search_margin=110,
        timeout_s=1.0,
        history_len=3,
    )

    ious = []
    wrong_jumps = 0
    dt = 1 / 30.0

    for frame in range(180):
        gt = ground_truth(frame)
        detections = noisy_detection(frame, gt)

        # The real workflow is DETECT -> user selects the intended box -> HOLD.
        # Seed the intended target once; from then on the tracker must preserve identity.
        if frame == 0:
            tracker.initialize(detections[0], now=0.0)
            bbox = tracker.bbox
        else:
            bbox, _ = tracker.update(detections, now=frame * dt)

        if bbox is not None:
            iou = bbox_iou(bbox, gt)
            ious.append(iou)
            if bbox[0] > 430:
                wrong_jumps += 1

    mean_iou = sum(ious) / max(1, len(ious))
    print(f"mean_iou={mean_iou:.4f}")
    print(f"wrong_jumps={wrong_jumps}")
    print(f"reacquires={tracker.reacquires}")
    print(f"corrections={tracker.corrections}")

    assert wrong_jumps == 0, "Tracker jumped to the distractor"
    assert mean_iou > 0.45, f"Mean IoU too low: {mean_iou:.3f}"
    assert tracker.reacquires >= 2, "Expected reacquisition after synthetic losses"
    print("PASS")


if __name__ == "__main__":
    main()
