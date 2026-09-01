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

    public volatile boolean active=false;
    public volatile boolean hasRotationVector=false;
    public volatile boolean calibrating=true;
    public volatile float calibrationProgress=0f;
    public volatile int calibrationSamples=0;

    public volatile float gyroHz=0f;
    public volatile float angularSpeedDeg=0f;
    public volatile float pitchDeg=0f;
    public volatile float rollDeg=0f;

    public volatile float lastRawDx=0f,lastRawDy=0f;
    public volatile float lastAppliedDx=0f,lastAppliedDy=0f;
    public volatile float strengthX=.88f,strengthY=.88f;
    public volatile float adaptiveStrength=.88f;

    private float hFov=(float)Math.toRadians(68);
    private float vFov=(float)Math.toRadians(42);
    private long lastGyroNs=0;
    private float fx=0,fy=0;

    // Analysis accumulator spans one analysis-frame interval.
    private float analysisDx=0,analysisDy=0;
    // Display accumulator is only peeked between analysis frames for low-latency HUD prediction.
    private float displayDx=0,displayDy=0;

    private static final float DEAD_BAND=.0105f;
    private static final float LP=.28f;
    private static final int CAL_TARGET_SAMPLES=36;

    public ImuCompensator(Context c){
        Context app=c.getApplicationContext();
        sm=(SensorManager)app.getSystemService(Context.SENSOR_SERVICE);
        wm=(WindowManager)app.getSystemService(Context.WINDOW_SERVICE);
        Sensor g=sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED);
        if(g==null)g=sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        gyro=g;
        rotation=sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
    }

    public void setFovDegrees(float horizontal,float vertical){
        if(horizontal>20&&horizontal<160)hFov=(float)Math.toRadians(horizontal);
        if(vertical>15&&vertical<130)vFov=(float)Math.toRadians(vertical);
    }

    public void start(){
        resetMotion();
        beginCalibration();
        if(gyro!=null){
            sm.registerListener(this,gyro,SensorManager.SENSOR_DELAY_FASTEST);
            active=true;
        }
        if(rotation!=null)sm.registerListener(this,rotation,SensorManager.SENSOR_DELAY_GAME);
    }

    public void stop(){
        sm.unregisterListener(this);
        active=false;
        resetMotion();
    }

    public synchronized void resetMotion(){
        lastGyroNs=0;fx=fy=0;
        analysisDx=analysisDy=displayDx=displayDy=0;
        lastRawDx=lastRawDy=lastAppliedDx=lastAppliedDy=0;
    }

    public synchronized void beginCalibration(){
        calibrating=true;
        calibrationSamples=0;
        calibrationProgress=0;
        // Start near a useful prior but allow optical motion to refine it.
        strengthX=clamp(strengthX,.62f,1.18f);
        strengthY=clamp(strengthY,.62f,1.18f);
        adaptiveStrength=(strengthX+strengthY)*.5f;
    }

    public synchronized float[] consumeAnalysisRawShift(){
        float dx=clamp(analysisDx,-.22f,.22f);
        float dy=clamp(analysisDy,-.22f,.22f);
        analysisDx=analysisDy=0;
        // Current analysis frame is now the new reference for display prediction.
        displayDx=displayDy=0;
        lastRawDx=dx;lastRawDy=dy;
        return new float[]{dx,dy};
    }

    public synchronized float[] peekDisplayAppliedShift(){
        float dx=clamp(displayDx*strengthX,-.18f,.18f);
        float dy=clamp(displayDy*strengthY,-.18f,.18f);
        return new float[]{dx,dy};
    }

    public synchronized float[] scaleRaw(float rawDx,float rawDy){
        float dx=clamp(rawDx*strengthX,-.18f,.18f);
        float dy=clamp(rawDy*strengthY,-.18f,.18f);
        lastAppliedDx=dx;lastAppliedDy=dy;
        return new float[]{dx,dy};
    }

    public synchronized void updateCalibration(float rawDx,float rawDy,float flowDx,float flowDy,float flowQuality){
        if(flowQuality<.34f||angularSpeedDeg<3.5f)return;

        boolean used=false;
        float alpha=calibrating?.075f:.012f;

        if(Math.abs(rawDx)>.0007f){
            float ratio=flowDx/rawDx;
            if(ratio>.38f&&ratio<1.65f&&sameDirection(flowDx,rawDx)){
                strengthX=clamp((1f-alpha)*strengthX+alpha*ratio,.48f,1.40f);
                used=true;
            }
        }
        if(Math.abs(rawDy)>.0007f){
            float ratio=flowDy/rawDy;
            if(ratio>.38f&&ratio<1.65f&&sameDirection(flowDy,rawDy)){
                strengthY=clamp((1f-alpha)*strengthY+alpha*ratio,.48f,1.40f);
                used=true;
            }
        }

        if(used&&calibrating){
            calibrationSamples++;
            calibrationProgress=clamp(calibrationSamples/(float)CAL_TARGET_SAMPLES,0f,1f);
            if(calibrationSamples>=CAL_TARGET_SAMPLES)calibrating=false;
        }
        adaptiveStrength=(strengthX+strengthY)*.5f;
    }

    @Override public void onSensorChanged(SensorEvent e){
        if(e.sensor.getType()==Sensor.TYPE_GAME_ROTATION_VECTOR){
            updateAttitude(e.values);
            return;
        }
        if(e.sensor.getType()!=Sensor.TYPE_GYROSCOPE&&e.sensor.getType()!=Sensor.TYPE_GYROSCOPE_UNCALIBRATED)return;

        long now=e.timestamp;
        if(lastGyroNs==0){lastGyroNs=now;return;}
        float dt=(now-lastGyroNs)*1e-9f;
        lastGyroNs=now;
        if(dt<=0||dt>.08f)return;

        float hz=1f/dt;
        gyroHz=gyroHz==0?hz:.94f*gyroHz+.06f*hz;

        float wx=e.values[0],wy=e.values[1],wz=e.values[2];
        float sx,sy;
        int rot=wm.getDefaultDisplay().getRotation();
        if(rot==Surface.ROTATION_90){sx=-wy;sy=wx;}
        else if(rot==Surface.ROTATION_180){sx=-wx;sy=-wy;}
        else if(rot==Surface.ROTATION_270){sx=wy;sy=-wx;}
        else{sx=wx;sy=wy;}

        fx=(1f-LP)*fx+LP*sx;
        fy=(1f-LP)*fy+LP*sy;
        float useX=Math.abs(fx)<DEAD_BAND?0f:fx;
        float useY=Math.abs(fy)<DEAD_BAND?0f:fy;
        angularSpeedDeg=(float)Math.toDegrees(Math.sqrt(wx*wx+wy*wy+wz*wz));

        // Rear camera: angular camera motion creates opposite apparent scene motion.
        float dx=-useY*dt/Math.max(.35f,hFov);
        float dy= useX*dt/Math.max(.28f,vFov);
        synchronized(this){
            analysisDx+=dx;analysisDy+=dy;
            displayDx+=dx;displayDy+=dy;
        }
    }

    private void updateAttitude(float[] rv){
        try{
            float[] r=new float[9];
            float[] o=new float[3];
            SensorManager.getRotationMatrixFromVector(r,rv);
            SensorManager.getOrientation(r,o);
            pitchDeg=(float)Math.toDegrees(o[1]);
            rollDeg=(float)Math.toDegrees(o[2]);
            hasRotationVector=true;
        }catch(Throwable ignored){}
    }

    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}

    private static boolean sameDirection(float a,float b){return a==0||b==0||Math.signum(a)==Math.signum(b);}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
}
