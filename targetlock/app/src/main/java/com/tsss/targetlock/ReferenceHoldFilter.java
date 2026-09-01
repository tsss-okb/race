package com.tsss.targetlock;

import java.util.Arrays;

/**
 * Reference 8-state constant-velocity Kalman:
 * [cx, cy, w, h, vx, vy, vw, vh]
 *
 * This mirrors the simple, stable tracking structure from the older
 * YOLO tracking project. It is passive visual tracking only.
 */
public final class ReferenceHoldFilter {
    private final double[] x=new double[8];
    private final double[][] p=new double[8][8];
    private boolean initialized=false;

    private double sigmaX=.02;
    private double sigmaY=.02;
    private double innovation=0;

    public synchronized boolean isInitialized(){return initialized;}

    public synchronized void reset(double cx,double cy,double w,double h){
        Arrays.fill(x,0);
        x[0]=clamp01(cx);
        x[1]=clamp01(cy);
        x[2]=clamp(w,.01,.90);
        x[3]=clamp(h,.01,.90);

        for(int r=0;r<8;r++){
            Arrays.fill(p[r],0);
            p[r][r]=r<4?2.5e-4:2.5e-2;
        }
        sigmaX=sigmaY=Math.sqrt(2.5e-4);
        innovation=0;
        initialized=true;
    }

    public synchronized void clear(){
        initialized=false;
        Arrays.fill(x,0);
        for(double[] row:p)Arrays.fill(row,0);
        sigmaX=sigmaY=.02;
        innovation=0;
    }

    public synchronized void predict(double dt,boolean coast){
        if(!initialized)return;
        dt=clamp(dt,.004,.10);

        // State prediction.
        for(int i=0;i<4;i++)x[i]+=x[i+4]*dt;
        x[0]=clamp01(x[0]);
        x[1]=clamp01(x[1]);
        x[2]=clamp(x[2],.01,.90);
        x[3]=clamp(x[3],.01,.90);

        // F = I, F[i][i+4] = dt
        double[][] f=identity(8);
        for(int i=0;i<4;i++)f[i][i+4]=dt;

        double[][] fp=mul(f,p);
        double[][] next=mul(fp,transpose(f));

        // Process noise. Broaden covariance while coasting.
        double qPos=coast?3.0e-4:6.0e-5;
        double qVel=coast?2.0e-2:5.0e-3;
        for(int i=0;i<4;i++)next[i][i]+=qPos;
        for(int i=4;i<8;i++)next[i][i]+=qVel;

        copy(next,p);
        updateSigma();
    }

    public synchronized void correct(
            double cx,double cy,double w,double h,double confidence){
        if(!initialized){
            reset(cx,cy,w,h);
            return;
        }

        double[] z={
                clamp01(cx),clamp01(cy),
                clamp(w,.01,.90),clamp(h,.01,.90)
        };
        double[] y=new double[4];
        for(int i=0;i<4;i++)y[i]=z[i]-x[i];

        // Higher detector confidence -> lower measurement noise.
        double c=clamp(confidence,.05,1.0);
        double r=lerp(8.0e-4,8.0e-5,c);

        // S = top-left 4x4 block of P + R.
        double[][] s=new double[4][4];
        for(int i=0;i<4;i++){
            for(int j=0;j<4;j++)s[i][j]=p[i][j];
            s[i][i]+=r;
        }
        double[][] sInv=invert4(s);
        if(sInv==null)return;

        // K = P[:,0:4] * inv(S)
        double[][] k=new double[8][4];
        for(int i=0;i<8;i++){
            for(int j=0;j<4;j++){
                double sum=0;
                for(int m=0;m<4;m++)sum+=p[i][m]*sInv[m][j];
                k[i][j]=sum;
            }
        }

        for(int i=0;i<8;i++){
            double d=0;
            for(int j=0;j<4;j++)d+=k[i][j]*y[j];
            x[i]+=d;
        }

        x[0]=clamp01(x[0]);
        x[1]=clamp01(x[1]);
        x[2]=clamp(x[2],.01,.90);
        x[3]=clamp(x[3],.01,.90);
        for(int i=4;i<8;i++)x[i]=clamp(x[i],-3.0,3.0);

        // P = (I - K H) P; H selects first 4 states.
        double[][] ikh=identity(8);
        for(int i=0;i<8;i++){
            for(int j=0;j<4;j++)ikh[i][j]-=k[i][j];
        }
        double[][] next=mul(ikh,p);
        copy(next,p);

        double ix=y[0]/Math.sqrt(Math.max(1e-9,s[0][0]));
        double iy=y[1]/Math.sqrt(Math.max(1e-9,s[1][1]));
        innovation=Math.sqrt(ix*ix+iy*iy);
        updateSigma();
    }

    public synchronized double associationInnovation(
            double cx,double cy,double confidence){
        if(!initialized)return 0;
        double c=clamp(confidence,.05,1.0);
        double r=lerp(8.0e-4,8.0e-5,c);
        double dx=cx-x[0];
        double dy=cy-x[1];
        return Math.sqrt(
                dx*dx/Math.max(1e-9,p[0][0]+r)+
                dy*dy/Math.max(1e-9,p[1][1]+r));
    }

    public synchronized float cx(){return (float)x[0];}
    public synchronized float cy(){return (float)x[1];}
    public synchronized float w(){return (float)x[2];}
    public synchronized float h(){return (float)x[3];}
    public synchronized float vx(){return (float)x[4];}
    public synchronized float vy(){return (float)x[5];}
    public synchronized float sigmaX(){return (float)sigmaX;}
    public synchronized float sigmaY(){return (float)sigmaY;}
    public synchronized float innovation(){return (float)innovation;}

    private void updateSigma(){
        sigmaX=Math.sqrt(Math.max(1e-9,p[0][0]));
        sigmaY=Math.sqrt(Math.max(1e-9,p[1][1]));
    }

    private static double[][] identity(int n){
        double[][] m=new double[n][n];
        for(int i=0;i<n;i++)m[i][i]=1;
        return m;
    }

    private static double[][] transpose(double[][] a){
        int r=a.length,c=a[0].length;
        double[][] t=new double[c][r];
        for(int i=0;i<r;i++)for(int j=0;j<c;j++)t[j][i]=a[i][j];
        return t;
    }

    private static double[][] mul(double[][] a,double[][] b){
        int r=a.length,n=b.length,c=b[0].length;
        double[][] out=new double[r][c];
        for(int i=0;i<r;i++){
            for(int k=0;k<n;k++){
                double v=a[i][k];
                if(v==0)continue;
                for(int j=0;j<c;j++)out[i][j]+=v*b[k][j];
            }
        }
        return out;
    }

    private static void copy(double[][] src,double[][] dst){
        for(int i=0;i<src.length;i++)
            System.arraycopy(src[i],0,dst[i],0,src[i].length);
    }

    private static double[][] invert4(double[][] a){
        double[][] m=new double[4][8];
        for(int i=0;i<4;i++){
            for(int j=0;j<4;j++)m[i][j]=a[i][j];
            m[i][4+i]=1;
        }

        for(int col=0;col<4;col++){
            int pivot=col;
            for(int r=col+1;r<4;r++)
                if(Math.abs(m[r][col])>Math.abs(m[pivot][col]))pivot=r;

            if(Math.abs(m[pivot][col])<1e-12)return null;
            if(pivot!=col){
                double[] tmp=m[pivot];m[pivot]=m[col];m[col]=tmp;
            }

            double div=m[col][col];
            for(int j=0;j<8;j++)m[col][j]/=div;

            for(int r=0;r<4;r++){
                if(r==col)continue;
                double f=m[r][col];
                for(int j=0;j<8;j++)m[r][j]-=f*m[col][j];
            }
        }

        double[][] inv=new double[4][4];
        for(int i=0;i<4;i++)for(int j=0;j<4;j++)inv[i][j]=m[i][4+j];
        return inv;
    }

    private static double lerp(double a,double b,double t){return a+(b-a)*t;}
    private static double clamp01(double v){return clamp(v,0,1);}
    private static double clamp(double v,double lo,double hi){
        return Math.max(lo,Math.min(hi,v));
    }
}
