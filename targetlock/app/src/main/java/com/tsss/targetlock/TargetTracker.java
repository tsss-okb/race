package com.tsss.targetlock;

import android.media.Image;
import java.nio.ByteBuffer;

public class TargetTracker {
    public volatile boolean locked = false;
    public volatile boolean acquiring = false;
    public volatile float x = .5f, y = .5f, predX = .5f, predY = .5f;
    public volatile float vx = 0, vy = 0, box = .075f, confidence = 0;
    public volatile float trackerFps = 0, latencyMs = 0, yoloConfidence = 0;
    public volatile int lostFrames = 0, targetClass = -1, yoloCorrections = 0, imuCorrections = 0;
    public volatile String targetName = "—";
    private ImuCompensator imu;

    private static final int GRID = 19;
    private static final int HALF_SPAN_PX = 24;
    private final float[] template = new float[GRID * GRID];
    private boolean hasTemplate = false;
    private float pendingX = -1, pendingY = -1;
    private long lastNs = 0, lastFrameNs = 0;

    public synchronized void setImu(ImuCompensator compensator) { imu = compensator; }

    private synchronized void applyImuShift() {
        if (imu == null) return;
        float[] d = imu.consumeFrameShift();
        if (!locked) return;
        float dx=d[0], dy=d[1];
        if (Math.abs(dx) < 0.00001f && Math.abs(dy) < 0.00001f) return;
        x = clamp(x + dx); y = clamp(y + dy);
        predX = clamp(predX + dx); predY = clamp(predY + dy);
        imuCorrections++;
    }

    public synchronized void requestLock(float nx, float ny) {
        pendingX = clamp(nx); pendingY = clamp(ny);
        acquiring = true; locked = false; confidence = 0; lostFrames = 0;
        targetClass = -1; targetName = "manual"; yoloConfidence = 0;
        if (imu != null) imu.reset();
    }

    public synchronized void onYoloDetection(YoloDetector.Detection d) {
        if (d == null) return;
        yoloConfidence = d.confidence;
        if (!locked) {
            targetClass = d.classId;
            targetName = d.className;
            box = d.halfBox;
            pendingX = clamp(d.x); pendingY = clamp(d.y);
            acquiring = true;
            confidence = Math.max(confidence, d.confidence);
            return;
        }

        if (targetClass >= 0 && d.classId != targetClass) return;
        float dx = d.x - predX, dy = d.y - predY;
        float dist = (float)Math.sqrt(dx*dx + dy*dy);
        if (dist > .35f) return;

        targetClass = d.classId;
        targetName = d.className;
        box = .72f * box + .28f * d.halfBox;
        updateMeasurement(d.x, d.y, Math.max(.45f, d.confidence));
        confidence = Math.max(confidence, d.confidence);
        yoloCorrections++;
    }

    public synchronized void clear() {
        locked = false; acquiring = false; hasTemplate = false;
        confidence = 0; lostFrames = 0; vx = vy = 0;
        pendingX = pendingY = -1;
        targetClass = -1; targetName = "—"; yoloConfidence = 0;
        if (imu != null) imu.reset();
    }

    public void processImage(Image image) {
        long started = System.nanoTime();
        try {
            if (image == null || image.getFormat() != android.graphics.ImageFormat.YUV_420_888) return;
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int w = image.getWidth(), h = image.getHeight();

            applyImuShift();
            long now = System.nanoTime();
            if (lastFrameNs != 0) {
                float f = 1e9f / Math.max(1, now - lastFrameNs);
                trackerFps = trackerFps == 0 ? f : .88f * trackerFps + .12f * f;
            }
            lastFrameNs = now;

            float px, py;
            synchronized (this) { px = pendingX; py = pendingY; }
            if (px >= 0 && py >= 0) {
                int cx = Math.round(px * (w - 1)), cy = Math.round(py * (h - 1));
                if (extractTemplate(buf, rowStride, pixelStride, w, h, cx, cy, template)) {
                    synchronized (this) {
                        x = predX = px; y = predY = py; vx = vy = 0;
                        confidence = Math.max(.72f, confidence);
                        locked = true; acquiring = false;
                        hasTemplate = true; pendingX = pendingY = -1;
                        lastNs = System.nanoTime(); lostFrames = 0;
                    }
                }
                return;
            }
            if (!hasTemplate || !locked) return;

            int cx = Math.round(predX * (w - 1)), cy = Math.round(predY * (h - 1));
            int radius = Math.min(108, 30 + lostFrames * 12);
            Match coarse = search(buf, rowStride, pixelStride, w, h, cx, cy, radius, 4);
            Match best = coarse == null ? null : search(buf, rowStride, pixelStride, w, h, coarse.x, coarse.y, 7, 1);

            if (best != null) {
                float q = scoreToConfidence(best.score);
                if (q >= .18f) {
                    updateMeasurement(best.x / (float)(w - 1), best.y / (float)(h - 1), q);
                    lostFrames = 0;
                    if (q > .58f) adaptTemplate(buf, rowStride, pixelStride, w, h, best.x, best.y, .045f);
                } else {
                    lostFrames++;
                    coast();
                }
            } else {
                lostFrames++;
                coast();
            }
            if (lostFrames > 18 || confidence < .08f) clear();
        } finally {
            latencyMs = (System.nanoTime() - started) / 1_000_000f;
            if (image != null) image.close();
        }
    }

    private Match search(ByteBuffer b, int row, int pix, int w, int h, int cx, int cy, int radius, int step) {
        Match best = null;
        int minX = Math.max(HALF_SPAN_PX + 2, cx - radius), maxX = Math.min(w - HALF_SPAN_PX - 3, cx + radius);
        int minY = Math.max(HALF_SPAN_PX + 2, cy - radius), maxY = Math.min(h - HALF_SPAN_PX - 3, cy + radius);
        for (int yy = minY; yy <= maxY; yy += step) {
            for (int xx = minX; xx <= maxX; xx += step) {
                float s = sadScore(b, row, pix, w, h, xx, yy);
                if (best == null || s < best.score) best = new Match(xx, yy, s);
            }
        }
        return best;
    }

    private float sadScore(ByteBuffer b, int row, int pix, int w, int h, int cx, int cy) {
        float sum = 0;
        int k = 0;
        for (int gy = 0; gy < GRID; gy++) {
            int yy = cy + ((gy - GRID/2) * HALF_SPAN_PX * 2) / (GRID - 1);
            for (int gx = 0; gx < GRID; gx++, k++) {
                int xx = cx + ((gx - GRID/2) * HALF_SPAN_PX * 2) / (GRID - 1);
                int v = getY(b, row, pix, xx, yy);
                sum += Math.abs(v - template[k]);
            }
        }
        return sum / (GRID * GRID);
    }

    private boolean extractTemplate(ByteBuffer b, int row, int pix, int w, int h, int cx, int cy, float[] out) {
        if (cx < HALF_SPAN_PX || cy < HALF_SPAN_PX || cx >= w - HALF_SPAN_PX || cy >= h - HALF_SPAN_PX) return false;
        int k = 0;
        for (int gy = 0; gy < GRID; gy++) {
            int yy = cy + ((gy - GRID/2) * HALF_SPAN_PX * 2) / (GRID - 1);
            for (int gx = 0; gx < GRID; gx++, k++) {
                int xx = cx + ((gx - GRID/2) * HALF_SPAN_PX * 2) / (GRID - 1);
                out[k] = getY(b, row, pix, xx, yy);
            }
        }
        return true;
    }

    private void adaptTemplate(ByteBuffer b, int row, int pix, int w, int h, int cx, int cy, float alpha) {
        if (cx < HALF_SPAN_PX || cy < HALF_SPAN_PX || cx >= w - HALF_SPAN_PX || cy >= h - HALF_SPAN_PX) return;
        int k = 0;
        for (int gy = 0; gy < GRID; gy++) {
            int yy = cy + ((gy - GRID/2) * HALF_SPAN_PX * 2) / (GRID - 1);
            for (int gx = 0; gx < GRID; gx++, k++) {
                int xx = cx + ((gx - GRID/2) * HALF_SPAN_PX * 2) / (GRID - 1);
                float v = getY(b, row, pix, xx, yy);
                template[k] = (1f - alpha) * template[k] + alpha * v;
            }
        }
    }

    private static int getY(ByteBuffer b, int row, int pix, int x, int y) {
        int idx = y * row + x * pix;
        if (idx < 0 || idx >= b.limit()) return 0;
        return b.get(idx) & 0xff;
    }

    private float scoreToConfidence(float sad) { return clamp(1f - sad / 52f); }

    private synchronized void updateMeasurement(float nx, float ny, float q) {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? .016f : Math.max(.004f, Math.min(.12f, (now - lastNs) / 1e9f));
        lastNs = now;
        float mx = (nx - x) / dt, my = (ny - y) / dt;
        vx = .72f * vx + .28f * mx; vy = .72f * vy + .28f * my;
        x = .58f * x + .42f * nx; y = .58f * y + .42f * ny;
        float lead = .050f;
        predX = clamp(x + vx * lead); predY = clamp(y + vy * lead);
        confidence = .72f * confidence + .28f * q; locked = true;
    }

    private synchronized void coast() {
        if (!locked) return;
        long now = System.nanoTime();
        float dt = lastNs == 0 ? .016f : Math.min(.07f, (now - lastNs) / 1e9f);
        predX = clamp(x + vx * (dt + .050f)); predY = clamp(y + vy * (dt + .050f));
        confidence *= .91f;
    }

    public synchronized void predictOnly() {
        applyImuShift();
        if (!locked) return;
        long now = System.nanoTime();
        float dt = lastNs == 0 ? .016f : Math.min(.06f, (now - lastNs) / 1e9f);
        predX = clamp(x + vx * (dt + .050f)); predY = clamp(y + vy * (dt + .050f));
    }

    private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }
    private static final class Match { final int x,y; final float score; Match(int x,int y,float s){this.x=x;this.y=y;this.score=s;} }
}
