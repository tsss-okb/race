package com.tsss.targetlock;

import android.content.Context;
import android.media.Image;
import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class YoloDetector {
    public static final int INPUT = 640;
    private static final int FEATURES = 84;
    private static final int ANCHORS = 8400;
    private static final float CONF = 0.38f;
    private static final int PAD_Y = 140;

    public volatile boolean ready = false;
    public volatile String error = "";
    public volatile float detectorFps = 0f;
    public volatile float latencyMs = 0f;
    public volatile String backend = "CPU";

    private final Context context;
    private final TargetTracker tracker;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private Interpreter interpreter;
    private long lastDoneNs = 0;

    private static final String[] NAMES = {
        "person","bicycle","car","motorcycle","airplane","bus","train","truck","boat","traffic light",
        "fire hydrant","stop sign","parking meter","bench","bird","cat","dog","horse","sheep","cow",
        "elephant","bear","zebra","giraffe","backpack","umbrella","handbag","tie","suitcase","frisbee",
        "skis","snowboard","sports ball","kite","baseball bat","baseball glove","skateboard","surfboard",
        "tennis racket","bottle","wine glass","cup","fork","knife","spoon","bowl","banana","apple",
        "sandwich","orange","broccoli","carrot","hot dog","pizza","donut","cake","chair","couch",
        "potted plant","bed","dining table","toilet","tv","laptop","mouse","remote","keyboard","cell phone",
        "microwave","oven","toaster","sink","refrigerator","book","clock","vase","scissors","teddy bear",
        "hair drier","toothbrush"
    };

    public YoloDetector(Context c, TargetTracker t) {
        context = c.getApplicationContext();
        tracker = t;
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            executor.execute(this::load);
        }
    }

    public boolean isStarted() { return started.get(); }

    private void load() {
        try {
            InputStream in = context.getAssets().open("yolo26n_w8a32.tflite");
            ByteArrayOutputStream out = new ByteArrayOutputStream(8 * 1024 * 1024);
            byte[] tmp = new byte[64 * 1024];
            int n;
            while ((n = in.read(tmp)) > 0) out.write(tmp, 0, n);
            in.close();
            byte[] bytes = out.toByteArray();
            ByteBuffer model = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
            model.put(bytes).rewind();

            Interpreter.Options opts = new Interpreter.Options();
            int cpus = Runtime.getRuntime().availableProcessors();
            opts.setNumThreads(Math.max(2, Math.min(6, cpus - 1)));
            interpreter = new Interpreter(model, opts);

            int[] inShape = interpreter.getInputTensor(0).shape();
            int[] outShape = interpreter.getOutputTensor(0).shape();
            if (inShape.length != 4 || inShape[1] != 640 || inShape[2] != 640 ||
                outShape.length != 3 || outShape[1] != FEATURES || outShape[2] != ANCHORS) {
                throw new IllegalStateException("Unexpected tensors: in=" + java.util.Arrays.toString(inShape) +
                        " out=" + java.util.Arrays.toString(outShape));
            }
            ready = true;
        } catch (Throwable e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            ready = false;
        }
    }

    public void maybeSubmit(Image image, int frameIndex) {
        if (!started.get()) start();
        if (!ready || interpreter == null || busy.get()) return;
        int stride = tracker.locked ? 7 : 2;
        if ((frameIndex % stride) != 0) return;
        if (!busy.compareAndSet(false, true)) return;

        Frame frame;
        try {
            frame = Frame.copy(image);
        } catch (Throwable e) {
            busy.set(false);
            return;
        }

        final float hintX = tracker.predX;
        final float hintY = tracker.predY;
        final int wantedClass = tracker.targetClass;
        final boolean locked = tracker.locked;

        executor.execute(() -> {
            long start = System.nanoTime();
            try {
                Detection d = detect(frame, hintX, hintY, wantedClass, locked);
                if (d != null) tracker.onYoloDetection(d);
            } catch (Throwable e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            } finally {
                long now = System.nanoTime();
                latencyMs = (now - start) / 1_000_000f;
                if (lastDoneNs != 0) {
                    float f = 1e9f / Math.max(1, now - lastDoneNs);
                    detectorFps = detectorFps == 0 ? f : detectorFps * .82f + f * .18f;
                }
                lastDoneNs = now;
                busy.set(false);
            }
        });
    }

    private Detection detect(Frame f, float hintX, float hintY, int wantedClass, boolean tracking) {
        ByteBuffer input = ByteBuffer.allocateDirect(INPUT * INPUT * 3 * 4).order(ByteOrder.nativeOrder());
        final float pad = 114f / 255f;

        for (int oy = 0; oy < INPUT; oy++) {
            if (oy < PAD_Y || oy >= PAD_Y + f.height) {
                for (int ox = 0; ox < INPUT; ox++) {
                    input.putFloat(pad); input.putFloat(pad); input.putFloat(pad);
                }
                continue;
            }
            int sy = oy - PAD_Y;
            for (int ox = 0; ox < INPUT; ox++) {
                int sx = Math.min(f.width - 1, ox);
                int yy = f.y[sy * f.yRow + sx * f.yPix] & 0xff;
                int uvx = sx / 2, uvy = sy / 2;
                int ui = Math.min(f.u.length - 1, uvy * f.uRow + uvx * f.uPix);
                int vi = Math.min(f.v.length - 1, uvy * f.vRow + uvx * f.vPix);
                int uu = f.u[ui] & 0xff;
                int vv = f.v[vi] & 0xff;

                float yf = Math.max(0, yy - 16) * 1.164f;
                float uf = uu - 128f, vf = vv - 128f;
                float r = clip(yf + 1.596f * vf) / 255f;
                float g = clip(yf - .392f * uf - .813f * vf) / 255f;
                float b = clip(yf + 2.017f * uf) / 255f;
                input.putFloat(r); input.putFloat(g); input.putFloat(b);
            }
        }
        input.rewind();

        float[][][] output = new float[1][FEATURES][ANCHORS];
        interpreter.run(input, output);
        float[][] o = output[0];

        Detection best = null;
        float bestRank = -1f;
        for (int i = 0; i < ANCHORS; i++) {
            int cls = 0;
            float score = o[4][i];
            for (int c = 1; c < 80; c++) {
                float s = o[c + 4][i];
                if (s > score) { score = s; cls = c; }
            }
            if (score < CONF) continue;
            if (tracking && wantedClass >= 0 && cls != wantedClass) continue;

            float cx = o[0][i], cy = o[1][i], bw = o[2][i], bh = o[3][i];
            float nx = cx / INPUT;
            float ny = (cy - PAD_Y) / Math.max(1f, f.height);
            float nw = bw / INPUT;
            float nh = bh / Math.max(1f, f.height);
            if (nx < 0 || nx > 1 || ny < 0 || ny > 1) continue;

            float rank = score;
            if (tracking) {
                float dx = nx - hintX, dy = ny - hintY;
                float dist = (float)Math.sqrt(dx * dx + dy * dy);
                if (dist > .35f) continue;
                rank = score * (1f - Math.min(.75f, dist * 1.8f));
            } else {
                float dx = nx - .5f, dy = ny - .5f;
                float centerBias = 1f - Math.min(.28f, (float)Math.sqrt(dx*dx + dy*dy) * .35f);
                rank *= centerBias;
            }
            if (rank > bestRank) {
                bestRank = rank;
                float half = Math.max(.035f, Math.min(.24f, Math.max(nw, nh) * .55f));
                best = new Detection(nx, ny, half, score, cls, name(cls));
            }
        }
        return best;
    }

    private static float clip(float v) { return Math.max(0f, Math.min(255f, v)); }
    public static String name(int cls) { return cls >= 0 && cls < NAMES.length ? NAMES[cls] : ("class " + cls); }

    public static final class Detection {
        public final float x, y, halfBox, confidence;
        public final int classId;
        public final String className;
        Detection(float x, float y, float halfBox, float confidence, int classId, String className) {
            this.x=x; this.y=y; this.halfBox=halfBox; this.confidence=confidence;
            this.classId=classId; this.className=className;
        }
    }

    private static final class Frame {
        final int width, height;
        final byte[] y,u,v;
        final int yRow,yPix,uRow,uPix,vRow,vPix;
        Frame(int width,int height,byte[] y,byte[] u,byte[] v,
              int yRow,int yPix,int uRow,int uPix,int vRow,int vPix) {
            this.width=width;this.height=height;this.y=y;this.u=u;this.v=v;
            this.yRow=yRow;this.yPix=yPix;this.uRow=uRow;this.uPix=uPix;this.vRow=vRow;this.vPix=vPix;
        }
        static Frame copy(Image image) {
            Image.Plane[] p=image.getPlanes();
            return new Frame(image.getWidth(), image.getHeight(),
                    bytes(p[0].getBuffer()), bytes(p[1].getBuffer()), bytes(p[2].getBuffer()),
                    p[0].getRowStride(),p[0].getPixelStride(),
                    p[1].getRowStride(),p[1].getPixelStride(),
                    p[2].getRowStride(),p[2].getPixelStride());
        }
        static byte[] bytes(ByteBuffer src) {
            ByteBuffer b=src.duplicate(); b.rewind();
            byte[] a=new byte[b.remaining()]; b.get(a); return a;
        }
    }
}
