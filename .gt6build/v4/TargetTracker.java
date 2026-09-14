package de.droiddrone.flight;

/**
 * Lightweight grayscale tracker for visual stabilization/target-lock testing.
 * It never sends flight-control commands.
 */
public final class TargetTracker {
    public enum State { IDLE, SEARCHING, LOCKED, LOST }

    public static final class Result {
        public final State state;
        public final float centerX, centerY, width, height;
        public final float errorX, errorY;
        public final float confidence;
        public final long frameId;

        private Result(State state, float centerX, float centerY, float width, float height,
                       float errorX, float errorY, float confidence, long frameId) {
            this.state = state;
            this.centerX = centerX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
            this.errorX = errorX;
            this.errorY = errorY;
            this.confidence = confidence;
            this.frameId = frameId;
        }

        public static Result idle() {
            return new Result(State.IDLE, 0.5f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0L);
        }
    }

    private final int downsample;
    private byte[] reducedFrame;
    private byte[] template;
    private int reducedW, reducedH, templateW, templateH;
    private float cx, cy, vx, vy;
    private int lostFrames;
    private long frameId;
    private volatile boolean lockPending;
    private volatile float requestedCx = 0.5f, requestedCy = 0.5f;
    private volatile float requestedW = 0.18f, requestedH = 0.18f;
    private volatile Result result = Result.idle();

    public TargetTracker() { this(6); }

    public TargetTracker(int downsample) {
        this.downsample = Math.max(2, downsample);
    }

    public synchronized void requestCenterLock() {
        requestLock(0.5f, 0.5f, 0.18f, 0.18f);
    }

    public synchronized void requestLock(float centerX, float centerY, float width, float height) {
        requestedCx = clamp(centerX, 0.02f, 0.98f);
        requestedCy = clamp(centerY, 0.02f, 0.98f);
        requestedW = clamp(width, 0.04f, 0.70f);
        requestedH = clamp(height, 0.04f, 0.70f);
        lockPending = true;
        result = new Result(State.SEARCHING, requestedCx, requestedCy, requestedW, requestedH,
                requestedCx - 0.5f, requestedCy - 0.5f, 0f, frameId);
    }

    public synchronized void clear() {
        template = null;
        lockPending = false;
        lostFrames = 0;
        vx = vy = 0f;
        result = Result.idle();
    }

    public Result getResult() { return result; }

    public synchronized Result process(byte[] yPlane, int width, int height, int rowStride) {
        frameId++;
        if (yPlane == null || width < 32 || height < 32 || rowStride < width) return result;
        downsample(yPlane, width, height, rowStride);
        if (lockPending) initializeTemplate();
        if (template == null) return result;

        float predictedCx = clamp(cx + vx, templateW * 0.5f, reducedW - templateW * 0.5f - 1);
        float predictedCy = clamp(cy + vy, templateH * 0.5f, reducedH - templateH * 0.5f - 1);
        int baseRadiusX = Math.max(6, Math.round(templateW * 0.75f));
        int baseRadiusY = Math.max(6, Math.round(templateH * 0.75f));
        float expansion = 1f + Math.min(2.5f, lostFrames * 0.25f);
        int radiusX = Math.round(baseRadiusX * expansion);
        int radiusY = Math.round(baseRadiusY * expansion);

        int minX = clamp(Math.round(predictedCx - templateW * 0.5f) - radiusX, 0, reducedW - templateW);
        int maxX = clamp(Math.round(predictedCx - templateW * 0.5f) + radiusX, 0, reducedW - templateW);
        int minY = clamp(Math.round(predictedCy - templateH * 0.5f) - radiusY, 0, reducedH - templateH);
        int maxY = clamp(Math.round(predictedCy - templateH * 0.5f) + radiusY, 0, reducedH - templateH);

        double bestCorr = -1.0;
        int bestX = clamp(Math.round(cx - templateW * 0.5f), 0, reducedW - templateW);
        int bestY = clamp(Math.round(cy - templateH * 0.5f), 0, reducedH - templateH);
        int searchStep = lostFrames > 2 ? 2 : 1;
        for (int y = minY; y <= maxY; y += searchStep) {
            for (int x = minX; x <= maxX; x += searchStep) {
                double corr = correlationAt(x, y);
                if (corr > bestCorr) {
                    bestCorr = corr;
                    bestX = x;
                    bestY = y;
                }
            }
        }

        float confidence = (float) clamp(bestCorr, 0.0, 1.0);
        float matchCx = bestX + templateW * 0.5f;
        float matchCy = bestY + templateH * 0.5f;
        if (confidence >= 0.46f) {
            float dx = matchCx - cx;
            float dy = matchCy - cy;
            vx = vx * 0.64f + dx * 0.36f;
            vy = vy * 0.64f + dy * 0.36f;
            cx = matchCx;
            cy = matchCy;
            lostFrames = 0;
            if (confidence >= 0.82f) updateTemplate(bestX, bestY, 0.025f);
            publish(State.LOCKED, confidence);
        } else {
            lostFrames++;
            vx *= 0.72f;
            vy *= 0.72f;
            if (confidence >= 0.30f) {
                cx = matchCx;
                cy = matchCy;
            }
            publish(State.LOST, confidence);
        }
        return result;
    }

    /** Fast center-sample downscale: avoids touching every source pixel. */
    private void downsample(byte[] src, int width, int height, int rowStride) {
        int w = Math.max(1, width / downsample);
        int h = Math.max(1, height / downsample);
        int needed = w * h;
        if (reducedFrame == null || reducedFrame.length != needed) reducedFrame = new byte[needed];
        reducedW = w;
        reducedH = h;
        int dst = 0;
        int half = downsample / 2;
        for (int y = 0; y < h; y++) {
            int sy = Math.min(height - 1, y * downsample + half);
            int row = sy * rowStride;
            for (int x = 0; x < w; x++) {
                int sx = Math.min(width - 1, x * downsample + half);
                reducedFrame[dst++] = src[row + sx];
            }
        }
    }

    private void initializeTemplate() {
        lockPending = false;
        templateW = evenAtLeast(Math.round(reducedW * requestedW), 12);
        templateH = evenAtLeast(Math.round(reducedH * requestedH), 12);
        templateW = Math.min(templateW, Math.max(12, reducedW - 2));
        templateH = Math.min(templateH, Math.max(12, reducedH - 2));
        cx = clamp(requestedCx * reducedW, templateW * 0.5f, reducedW - templateW * 0.5f - 1);
        cy = clamp(requestedCy * reducedH, templateH * 0.5f, reducedH - templateH * 0.5f - 1);
        int x0 = clamp(Math.round(cx - templateW * 0.5f), 0, reducedW - templateW);
        int y0 = clamp(Math.round(cy - templateH * 0.5f), 0, reducedH - templateH);
        template = new byte[templateW * templateH];
        for (int y = 0; y < templateH; y++) {
            System.arraycopy(reducedFrame, (y0 + y) * reducedW + x0, template, y * templateW, templateW);
        }
        vx = vy = 0f;
        lostFrames = 0;
        publish(State.LOCKED, 1f);
    }

    private double correlationAt(int x0, int y0) {
        final int sampleStep = 2;
        long sumC = 0, sumCSq = 0, sumTC = 0, sumT = 0, sumTSq = 0;
        int n = 0;
        for (int y = 0; y < templateH; y += sampleStep) {
            int ti = y * templateW;
            int ci = (y0 + y) * reducedW + x0;
            for (int x = 0; x < templateW; x += sampleStep) {
                int t = template[ti + x] & 0xff;
                int c = reducedFrame[ci + x] & 0xff;
                sumT += t;
                sumTSq += (long) t * t;
                sumC += c;
                sumCSq += (long) c * c;
                sumTC += (long) t * c;
                n++;
            }
        }
        if (n < 8) return -1.0;
        double num = n * (double) sumTC - (double) sumT * sumC;
        double denT = n * (double) sumTSq - (double) sumT * sumT;
        double denC = n * (double) sumCSq - (double) sumC * sumC;
        double den = Math.sqrt(Math.max(1.0, denT * denC));
        return num / den;
    }

    private void updateTemplate(int x0, int y0, float alpha) {
        int a = Math.max(1, Math.min(255, Math.round(alpha * 255f)));
        for (int y = 0; y < templateH; y++) {
            int ti = y * templateW;
            int ci = (y0 + y) * reducedW + x0;
            for (int x = 0; x < templateW; x++) {
                int oldV = template[ti + x] & 0xff;
                int newV = reducedFrame[ci + x] & 0xff;
                template[ti + x] = (byte) ((oldV * (255 - a) + newV * a) / 255);
            }
        }
    }

    private void publish(State state, float confidence) {
        float nx = cx / Math.max(1f, reducedW);
        float ny = cy / Math.max(1f, reducedH);
        float nw = templateW / Math.max(1f, reducedW);
        float nh = templateH / Math.max(1f, reducedH);
        result = new Result(state, nx, ny, nw, nh, nx - 0.5f, ny - 0.5f, confidence, frameId);
    }

    private static int evenAtLeast(int value, int min) {
        int v = Math.max(min, value);
        return (v & 1) == 0 ? v : v + 1;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
