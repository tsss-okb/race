package com.tsss.targetlock;

import android.content.Context;
import android.hardware.*;
import android.view.Surface;
import android.view.WindowManager;

public class ImuCompensator implements SensorEventListener {
    private final SensorManager sm;
    private final Sensor gyro;
    private final Sensor rotation;
    private final WindowManager wm;

    public volatile boolean active = false;
    public volatile boolean hasRotationVector = false;
    public volatile float gyroHz = 0f;
    public volatile float angularSpeedDeg = 0f;
    public volatile float pitchDeg = 0f;
    public volatile float rollDeg = 0f;
    public volatile float lastDx = 0f, lastDy = 0f;
    public volatile float strength = 0.88f;

    private float hFov = (float)Math.toRadians(68);
    private float vFov = (float)Math.toRadians(42);
    private long lastGyroNs = 0;
    private float fx = 0, fy = 0;
    private float accDx = 0, accDy = 0;

    private static final float DEAD_BAND = 0.0105f; // rad/s
    private static final float LP = 0.28f;

    public ImuCompensator(Context c) {
        Context app = c.getApplicationContext();
        sm = (SensorManager)app.getSystemService(Context.SENSOR_SERVICE);
        wm = (WindowManager)app.getSystemService(Context.WINDOW_SERVICE);
        Sensor g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED);
        if (g == null) g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        gyro = g;
        rotation = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
    }

    public void setFovDegrees(float horizontal, float vertical) {
        if (horizontal > 20 && horizontal < 160) hFov = (float)Math.toRadians(horizontal);
        if (vertical > 15 && vertical < 130) vFov = (float)Math.toRadians(vertical);
    }

    public void start() {
        reset();
        if (gyro != null) {
            sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST);
            active = true;
        }
        if (rotation != null) sm.registerListener(this, rotation, SensorManager.SENSOR_DELAY_GAME);
    }

    public void stop() {
        sm.unregisterListener(this);
        active = false;
        reset();
    }

    public synchronized void reset() {
        lastGyroNs = 0; fx = fy = 0; accDx = accDy = 0; lastDx = lastDy = 0;
    }

    public synchronized float[] consumeFrameShift() {
        float dx = clamp(accDx, -.18f, .18f);
        float dy = clamp(accDy, -.18f, .18f);
        accDx = accDy = 0;
        lastDx = dx; lastDy = dy;
        return new float[]{dx, dy};
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            updateAttitude(e.values);
            return;
        }
        if (e.sensor.getType() != Sensor.TYPE_GYROSCOPE &&
            e.sensor.getType() != Sensor.TYPE_GYROSCOPE_UNCALIBRATED) return;

        long now = e.timestamp;
        if (lastGyroNs == 0) { lastGyroNs = now; return; }
        float dt = (now - lastGyroNs) * 1e-9f;
        lastGyroNs = now;
        if (dt <= 0 || dt > .08f) return;
        float hz = 1f / dt;
        gyroHz = gyroHz == 0 ? hz : .94f * gyroHz + .06f * hz;

        float wx=e.values[0], wy=e.values[1], wz=e.values[2];
        float sx, sy;
        int rot = wm.getDefaultDisplay().getRotation();
        if (rot == Surface.ROTATION_90) {
            sx = -wy; sy = wx;
        } else if (rot == Surface.ROTATION_180) {
            sx = -wx; sy = -wy;
        } else if (rot == Surface.ROTATION_270) {
            sx = wy; sy = -wx;
        } else {
            sx = wx; sy = wy;
        }

        fx = (1f-LP)*fx + LP*sx;
        fy = (1f-LP)*fy + LP*sy;
        float useX = Math.abs(fx) < DEAD_BAND ? 0f : fx;
        float useY = Math.abs(fy) < DEAD_BAND ? 0f : fy;
        angularSpeedDeg = (float)Math.toDegrees(Math.sqrt(wx*wx + wy*wy + wz*wz));

        // Rear camera looks through -Z. Camera rotation moves the scene in the opposite image direction.
        float dx = -useY * dt / Math.max(.35f, hFov);
        float dy =  useX * dt / Math.max(.28f, vFov);
        synchronized (this) {
            accDx += dx * strength;
            accDy += dy * strength;
        }
    }

    private void updateAttitude(float[] rv) {
        try {
            float[] r = new float[9];
            float[] o = new float[3];
            SensorManager.getRotationMatrixFromVector(r, rv);
            SensorManager.getOrientation(r, o);
            pitchDeg = (float)Math.toDegrees(o[1]);
            rollDeg = (float)Math.toDegrees(o[2]);
            hasRotationVector = true;
        } catch (Throwable ignored) {}
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private static float clamp(float v,float lo,float hi){ return Math.max(lo,Math.min(hi,v)); }
}
