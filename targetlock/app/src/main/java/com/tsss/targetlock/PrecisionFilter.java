package com.tsss.targetlock;

/**
 * 2D constant-velocity Kalman filter in normalized image coordinates.
 * Camera/attitude motion is injected as a known position control input,
 * while tracker and detector coordinates are measurements.
 */
public final class PrecisionFilter {
    private boolean initialized=false;

    private float x=.5f,y=.5f,vx=0f,vy=0f;

    // Independent [position, velocity] covariance for X/Y.
    private float px00=.0004f,px01=0f,px10=0f,px11=.25f;
    private float py00=.0004f,py01=0f,py10=0f,py11=.25f;

    private float lastInnovation=0f;
    private float sigmaX=.02f,sigmaY=.02f;

    public synchronized boolean isInitialized(){return initialized;}

    public synchronized void reset(float nx,float ny){
        x=clamp(nx);y=clamp(ny);vx=0f;vy=0f;
        px00=py00=.0004f;
        px01=px10=py01=py10=0f;
        px11=py11=.25f;
        sigmaX=sigmaY=.02f;
        lastInnovation=0f;
        initialized=true;
    }

    public synchronized void clear(){
        initialized=false;
        x=y=.5f;vx=vy=0f;
        lastInnovation=0f;
    }

    public synchronized void predict(float dt,float cameraDx,float cameraDy,boolean coast){
        if(!initialized)return;
        dt=clamp(dt,.004f,.10f);

        x=clamp(x+vx*dt+cameraDx);
        y=clamp(y+vy*dt+cameraDy);

        // Constant-velocity covariance prediction with acceleration noise.
        float accelSigma=coast?5.0f:2.2f;
        float q=accelSigma*accelSigma;
        float dt2=dt*dt,dt3=dt2*dt,dt4=dt2*dt2;
        float q00=q*dt4*.25f;
        float q01=q*dt3*.5f;
        float q11=q*dt2;

        float npx00=px00+dt*(px10+px01)+dt2*px11+q00;
        float npx01=px01+dt*px11+q01;
        float npx10=px10+dt*px11+q01;
        float npx11=px11+q11;

        float npy00=py00+dt*(py10+py01)+dt2*py11+q00;
        float npy01=py01+dt*py11+q01;
        float npy10=py10+dt*py11+q01;
        float npy11=py11+q11;

        px00=npx00;px01=npx01;px10=npx10;px11=npx11;
        py00=npy00;py01=npy01;py10=npy10;py11=npy11;
        updateSigma();
    }

    public synchronized void updateTracker(float mx,float my,float quality){
        float q=clamp(quality,.05f,1f);
        float sigma=lerp(.026f,.0045f,q);
        update(mx,my,sigma);
    }

    public synchronized void updateYolo(float mx,float my,float confidence){
        float q=clamp(confidence,.05f,1f);
        float sigma=lerp(.040f,.0080f,q);
        update(mx,my,sigma);
    }

    private void update(float mx,float my,float sigma){
        if(!initialized){
            reset(mx,my);
            return;
        }

        float r=sigma*sigma;

        float sx=px00+r;
        float sy=py00+r;
        float ix=mx-x;
        float iy=my-y;

        lastInnovation=(float)Math.sqrt(
                ix*ix/Math.max(1e-7f,sx)+
                iy*iy/Math.max(1e-7f,sy));

        float kx0=px00/sx;
        float kx1=px10/sx;
        x=clamp(x+kx0*ix);
        vx=vx+kx1*ix;

        float opx00=px00,opx01=px01;
        px00=(1f-kx0)*opx00;
        px01=(1f-kx0)*opx01;
        px10=px10-kx1*opx00;
        px11=px11-kx1*opx01;

        float ky0=py00/sy;
        float ky1=py10/sy;
        y=clamp(y+ky0*iy);
        vy=vy+ky1*iy;

        float opy00=py00,opy01=py01;
        py00=(1f-ky0)*opy00;
        py01=(1f-ky0)*opy01;
        py10=py10-ky1*opy00;
        py11=py11-ky1*opy01;

        // Keep pathological one-frame velocity estimates bounded.
        vx=clamp(vx,-4f,4f);
        vy=clamp(vy,-4f,4f);
        updateSigma();
    }

    public synchronized float innovationScore(float mx,float my,float measurementSigma){
        if(!initialized)return 0f;
        float r=measurementSigma*measurementSigma;
        float dx=mx-x,dy=my-y;
        return (float)Math.sqrt(
                dx*dx/Math.max(1e-7f,px00+r)+
                dy*dy/Math.max(1e-7f,py00+r));
    }

    public synchronized float predictedX(float leadSeconds){
        return clamp(x+vx*clamp(leadSeconds,0f,.15f));
    }

    public synchronized float predictedY(float leadSeconds){
        return clamp(y+vy*clamp(leadSeconds,0f,.15f));
    }

    public synchronized float x(){return x;}
    public synchronized float y(){return y;}
    public synchronized float vx(){return vx;}
    public synchronized float vy(){return vy;}
    public synchronized float sigmaX(){return sigmaX;}
    public synchronized float sigmaY(){return sigmaY;}
    public synchronized float lastInnovation(){return lastInnovation;}

    private void updateSigma(){
        sigmaX=(float)Math.sqrt(Math.max(1e-7f,px00));
        sigmaY=(float)Math.sqrt(Math.max(1e-7f,py00));
    }

    private static float lerp(float a,float b,float t){return a+(b-a)*t;}
    private static float clamp(float v){return Math.max(0f,Math.min(1f,v));}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
}
