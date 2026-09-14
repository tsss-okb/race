package de.droiddrone.flight;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * Low-latency Y-plane tap for the internal Camera2 stream.
 * The tracker is intentionally decoupled from flight-control output.
 */
public final class FrameAnalyzer implements AutoCloseable {
    private final ImageReader reader;
    private final HandlerThread thread;
    private final TargetTracker tracker = new TargetTracker(4);
    private final long minFrameIntervalNs;
    private byte[] packedY;
    private long lastFrameNs;
    private int frameCounter;
    private int currentFps;
    private long fpsWindowNs;

    public FrameAnalyzer(int width, int height, int maxAnalysisFps) {
        int fps = Math.max(5, Math.min(60, maxAnalysisFps));
        minFrameIntervalNs = 1_000_000_000L / fps;
        reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
        thread = new HandlerThread("TargetFrameAnalyzer");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        reader.setOnImageAvailableListener(this::onImageAvailable, handler);
        fpsWindowNs = System.nanoTime();
    }

    public Surface getSurface() {
        return reader.getSurface();
    }

    public TargetTracker getTracker() {
        return tracker;
    }

    public int getCurrentFps() {
        return currentFps;
    }

    private void onImageAvailable(ImageReader imageReader) {
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) return;
            long now = System.nanoTime();
            if (lastFrameNs != 0 && now - lastFrameNs < minFrameIntervalNs) return;
            lastFrameNs = now;

            Image.Plane y = image.getPlanes()[0];
            int width = image.getWidth();
            int height = image.getHeight();
            int rowStride = y.getRowStride();
            int pixelStride = y.getPixelStride();
            int needed = width * height;
            if (packedY == null || packedY.length != needed) packedY = new byte[needed];

            ByteBuffer buffer = y.getBuffer().duplicate();
            int base = buffer.position();
            int limit = buffer.limit();
            int dst = 0;
            for (int row = 0; row < height; row++) {
                int rowBase = base + row * rowStride;
                for (int col = 0; col < width; col++) {
                    int index = rowBase + col * pixelStride;
                    packedY[dst++] = index < limit ? buffer.get(index) : 0;
                }
            }
            tracker.process(packedY, width, height, width);
            updateFps(now);
        } catch (Exception ignored) {
        } finally {
            if (image != null) image.close();
        }
    }

    private void updateFps(long now) {
        frameCounter++;
        long dt = now - fpsWindowNs;
        if (dt >= 1_000_000_000L) {
            currentFps = Math.round(frameCounter * 1_000_000_000f / dt);
            frameCounter = 0;
            fpsWindowNs = now;
        }
    }

    public static Size chooseAnalysisSize(StreamConfigurationMap map) {
        final int targetW = 640;
        final int targetH = 360;
        Size best = null;
        long bestScore = Long.MAX_VALUE;
        if (map != null) {
            Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            if (sizes != null) {
                for (Size s : sizes) {
                    if (s.getWidth() < 320 || s.getHeight() < 180) continue;
                    long areaPenalty = Math.abs((long) s.getWidth() * s.getHeight() - (long) targetW * targetH);
                    long ratioPenalty = Math.abs((long) s.getWidth() * targetH - (long) s.getHeight() * targetW) * 20L;
                    long score = areaPenalty + ratioPenalty;
                    if (score < bestScore) {
                        bestScore = score;
                        best = s;
                    }
                }
            }
        }
        return best != null ? best : new Size(targetW, targetH);
    }

    @Override
    public void close() {
        reader.setOnImageAvailableListener(null, null);
        reader.close();
        thread.quitSafely();
    }
}
