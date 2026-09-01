package com.tsss.targetlock;

/**
 * Safe-core stub. YOLO is intentionally disabled in v0.7 so camera/IMU startup
 * can be validated without TensorFlow/LiteRT native dependencies.
 */
public class YoloDetector {
    public volatile boolean ready=false;
    public volatile String error="SAFE CORE: YOLO OFF";
    public volatile float detectorFps=0f, latencyMs=0f;
    public volatile String backend="OFF";
    public YoloDetector(android.content.Context c, TargetTracker t) {}
    public void start() {}
    public boolean isStarted(){return false;}
    public void maybeSubmit(android.media.Image image,int frameIndex) {}

    public static final class Detection {
        public final float x,y,halfBox,confidence;
        public final int classId;
        public final String className;
        public Detection(float x,float y,float halfBox,float confidence,int classId,String className){
            this.x=x;this.y=y;this.halfBox=halfBox;this.confidence=confidence;
            this.classId=classId;this.className=className;
        }
    }
}
