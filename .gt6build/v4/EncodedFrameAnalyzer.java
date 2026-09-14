package de.droiddrone.flight;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static de.droiddrone.common.Logcat.log;

/**
 * Decodes a copy of the already-encoded DroidDrone video stream for visual tracking.
 * It does NOT add another Camera2 Surface, so the original camera session remains untouched.
 * Decoder failures are isolated from streaming and flight telemetry.
 */
public final class EncodedFrameAnalyzer implements AutoCloseable {
    private static final int QUEUE_CAPACITY = 120;

    private static final class Packet {
        final byte[] data;
        final long ptsUs;
        final int flags;

        Packet(byte[] data, long ptsUs, int flags) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.flags = flags;
        }
    }

    private final ArrayBlockingQueue<Packet> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final TargetTracker tracker = new TargetTracker(6);
    private final MediaFormat decoderFormat;
    private final String mime;
    private final int sourceWidth;
    private final int sourceHeight;
    private final long minAnalysisIntervalNs;

    private volatile boolean running = true;
    private volatile boolean waitingForKeyFrame = false;
    private volatile boolean flushRequested = false;
    private volatile String status = "STARTING";
    private volatile int currentFps = 0;
    private volatile long decodedFrames = 0;
    private volatile long analyzedFrames = 0;

    private MediaCodec decoder;
    private MediaFormat outputFormat;
    private Thread worker;
    private byte[] packedY;
    private long lastAnalysisNs;
    private long fpsWindowNs;
    private int fpsCounter;

    public EncodedFrameAnalyzer(MediaFormat encoderFormat, int maxAnalysisFps) throws Exception {
        if (encoderFormat == null) throw new IllegalArgumentException("encoder format is null");
        mime = encoderFormat.getString(MediaFormat.KEY_MIME);
        if (mime == null) throw new IllegalArgumentException("encoder MIME is null");
        sourceWidth = getInt(encoderFormat, MediaFormat.KEY_WIDTH, 0);
        sourceHeight = getInt(encoderFormat, MediaFormat.KEY_HEIGHT, 0);
        if (sourceWidth < 16 || sourceHeight < 16) throw new IllegalArgumentException("bad video size");
        int fps = Math.max(5, Math.min(20, maxAnalysisFps));
        minAnalysisIntervalNs = 1_000_000_000L / fps;
        decoderFormat = buildDecoderFormat(encoderFormat, mime, sourceWidth, sourceHeight);
        fpsWindowNs = System.nanoTime();
        worker = new Thread(this::runLoop, "GT6EncodedTracker");
        worker.setDaemon(true);
        worker.start();
    }

    public TargetTracker getTracker() { return tracker; }
    public int getCurrentFps() { return currentFps; }
    public String getStatus() { return status; }
    public long getDecodedFrames() { return decodedFrames; }
    public long getAnalyzedFrames() { return analyzedFrames; }

    public void offer(byte[] data, MediaCodec.BufferInfo info) {
        if (!running || data == null || data.length == 0 || info == null) return;
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return;
        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        if (waitingForKeyFrame && !key) return;
        if (waitingForKeyFrame && key) waitingForKeyFrame = false;

        Packet packet = new Packet(data.clone(), info.presentationTimeUs,
                info.flags & ~MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
        if (!queue.offer(packet)) {
            queue.clear();
            flushRequested = true;
            waitingForKeyFrame = !key;
            status = "RESYNC";
            if (key) queue.offer(packet);
        }
    }

    private void runLoop() {
        try {
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(decoderFormat, null, null, 0);
            decoder.start();
            status = "RUNNING";
            while (running) {
                if (flushRequested) {
                    try { decoder.flush(); } catch (Exception ignore) {}
                    flushRequested = false;
                }
                Packet p = queue.poll(30, TimeUnit.MILLISECONDS);
                if (p != null) feedPacket(p);
                drainOutput();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            status = "ERROR: " + shortError(e);
            log("EncodedFrameAnalyzer error: " + e);
        } finally {
            releaseDecoder();
            if (!status.startsWith("ERROR")) status = "CLOSED";
        }
    }

    private void feedPacket(Packet p) {
        if (decoder == null) return;
        try {
            int index = decoder.dequeueInputBuffer(20_000);
            if (index < 0) {
                drainOutput();
                index = decoder.dequeueInputBuffer(20_000);
            }
            if (index < 0) {
                status = "DECODER BUSY";
                flushRequested = true;
                waitingForKeyFrame = true;
                return;
            }
            ByteBuffer input = decoder.getInputBuffer(index);
            if (input == null || input.capacity() < p.data.length) {
                decoder.queueInputBuffer(index, 0, 0, p.ptsUs, 0);
                status = "INPUT BUFFER ERROR";
                return;
            }
            input.clear();
            input.put(p.data);
            decoder.queueInputBuffer(index, 0, p.data.length, p.ptsUs, p.flags);
        } catch (Throwable e) {
            status = "FEED ERROR: " + shortError(e);
            log("Target decoder feed error: " + e);
            flushRequested = true;
            waitingForKeyFrame = true;
        }
    }

    private void drainOutput() {
        if (decoder == null) return;
        MediaCodec.BufferInfo outInfo = new MediaCodec.BufferInfo();
        for (int n = 0; n < 12; n++) {
            final int index;
            try {
                index = decoder.dequeueOutputBuffer(outInfo, 0);
            } catch (Throwable e) {
                status = "DRAIN ERROR: " + shortError(e);
                return;
            }
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try { outputFormat = decoder.getOutputFormat(); } catch (Exception ignore) {}
                status = "RUNNING";
                continue;
            }
            if (index < 0) continue;
            decodedFrames++;
            try {
                long now = System.nanoTime();
                if (lastAnalysisNs == 0 || now - lastAnalysisNs >= minAnalysisIntervalNs) {
                    if (analyzeOutput(index)) {
                        lastAnalysisNs = now;
                        analyzedFrames++;
                        updateFps(now);
                        status = "RUNNING";
                    }
                }
            } catch (Throwable e) {
                status = "FRAME ERROR: " + shortError(e);
            } finally {
                try { decoder.releaseOutputBuffer(index, false); } catch (Exception ignore) {}
            }
        }
    }

    private boolean analyzeOutput(int index) {
        Image image = null;
        try {
            image = decoder.getOutputImage(index);
            if (image != null && image.getFormat() == ImageFormat.YUV_420_888 && image.getPlanes().length > 0) {
                Image.Plane y = image.getPlanes()[0];
                return copyAndTrack(y.getBuffer(), image.getWidth(), image.getHeight(), y.getRowStride(), y.getPixelStride());
            }
        } catch (Throwable ignore) {
            // Fall through to raw ByteBuffer mode.
        } finally {
            if (image != null) try { image.close(); } catch (Exception ignore) {}
        }

        try {
            ByteBuffer raw = decoder.getOutputBuffer(index);
            if (raw == null) return false;
            MediaFormat fmt = outputFormat != null ? outputFormat : decoder.getOutputFormat();
            int width = getInt(fmt, MediaFormat.KEY_WIDTH, sourceWidth);
            int height = getInt(fmt, MediaFormat.KEY_HEIGHT, sourceHeight);
            int stride = getInt(fmt, MediaFormat.KEY_STRIDE, width);
            int cropLeft = getInt(fmt, MediaFormat.KEY_CROP_LEFT, 0);
            int cropTop = getInt(fmt, MediaFormat.KEY_CROP_TOP, 0);
            int cropRight = getInt(fmt, MediaFormat.KEY_CROP_RIGHT, width - 1);
            int cropBottom = getInt(fmt, MediaFormat.KEY_CROP_BOTTOM, height - 1);
            int cropW = Math.max(1, cropRight - cropLeft + 1);
            int cropH = Math.max(1, cropBottom - cropTop + 1);
            return copyRawAndTrack(raw, cropW, cropH, stride, cropLeft, cropTop);
        } catch (Throwable e) {
            return false;
        }
    }

    private boolean copyAndTrack(ByteBuffer source, int width, int height, int rowStride, int pixelStride) {
        if (source == null || width < 16 || height < 16 || rowStride <= 0 || pixelStride <= 0) return false;
        int needed = width * height;
        if (packedY == null || packedY.length != needed) packedY = new byte[needed];
        ByteBuffer b = source.duplicate();
        int base = b.position();
        int limit = b.limit();
        int dst = 0;
        if (pixelStride == 1 && rowStride == width && base + needed <= limit) {
            b.get(packedY, 0, needed);
        } else {
            for (int y = 0; y < height; y++) {
                int row = base + y * rowStride;
                for (int x = 0; x < width; x++) {
                    int pos = row + x * pixelStride;
                    packedY[dst++] = pos >= base && pos < limit ? b.get(pos) : 0;
                }
            }
        }
        tracker.process(packedY, width, height, width);
        return true;
    }

    private boolean copyRawAndTrack(ByteBuffer source, int width, int height, int stride, int cropLeft, int cropTop) {
        if (source == null || width < 16 || height < 16 || stride < width) return false;
        int needed = width * height;
        if (packedY == null || packedY.length != needed) packedY = new byte[needed];
        ByteBuffer b = source.duplicate();
        int base = b.position();
        int limit = b.limit();
        int dst = 0;
        for (int y = 0; y < height; y++) {
            int row = base + (cropTop + y) * stride + cropLeft;
            if (row < base || row + width > limit) return false;
            for (int x = 0; x < width; x++) packedY[dst++] = b.get(row + x);
        }
        tracker.process(packedY, width, height, width);
        return true;
    }

    private void updateFps(long now) {
        fpsCounter++;
        long dt = now - fpsWindowNs;
        if (dt >= 1_000_000_000L) {
            currentFps = Math.round(fpsCounter * 1_000_000_000f / dt);
            fpsCounter = 0;
            fpsWindowNs = now;
        }
    }

    private static MediaFormat buildDecoderFormat(MediaFormat src, String mime, int width, int height) {
        MediaFormat dst = MediaFormat.createVideoFormat(mime, width, height);
        dst.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        copyCsd(src, dst, "csd-0");
        copyCsd(src, dst, "csd-1");
        copyCsd(src, dst, "csd-2");
        return dst;
    }

    private static void copyCsd(MediaFormat src, MediaFormat dst, String key) {
        try {
            if (!src.containsKey(key)) return;
            ByteBuffer in = src.getByteBuffer(key);
            if (in == null) return;
            ByteBuffer d = in.duplicate();
            byte[] bytes = new byte[d.remaining()];
            d.get(bytes);
            dst.setByteBuffer(key, ByteBuffer.wrap(bytes));
        } catch (Exception ignore) {}
    }

    private static int getInt(MediaFormat format, String key, int fallback) {
        try { return format != null && format.containsKey(key) ? format.getInteger(key) : fallback; }
        catch (Exception e) { return fallback; }
    }

    private static String shortError(Throwable e) {
        String n = e.getClass().getSimpleName();
        String m = e.getMessage();
        if (m == null || m.length() == 0) return n;
        if (m.length() > 80) m = m.substring(0, 80);
        return n + ": " + m;
    }

    private void releaseDecoder() {
        MediaCodec d = decoder;
        decoder = null;
        if (d != null) {
            try { d.stop(); } catch (Exception ignore) {}
            try { d.release(); } catch (Exception ignore) {}
        }
    }

    @Override
    public void close() {
        running = false;
        queue.clear();
        Thread t = worker;
        if (t != null) t.interrupt();
        worker = null;
        tracker.clear();
    }
}
