package com.tsss.targetlock;

import android.media.Image;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class VisualMotionEstimator {
    public volatile float dx = 0f, dy = 0f;
    public volatile float quality = 0f;
    public volatile float fps = 0f;
    public volatile int validPoints = 0;

    private static final int DW = 160;
    private static final int DH = 90;
    private static final int SEARCH = 7;
    private static final int PATCH = 2;
    private final byte[] prev = new byte[DW * DH];
    private final byte[] curr = new byte[DW * DH];
    private boolean hasPrev = false;
    private long lastNs = 0;

    public synchronized Motion estimate(Image image, float targetX, float targetY, float targetBox, boolean locked) {
        if (image == null || image.getFormat() != android.graphics.ImageFormat.YUV_420_888) return Motion.NONE;
        Image.Plane p = image.getPlanes()[0];
        ByteBuffer y = p.getBuffer();
        int row = p.getRowStride(), pix = p.getPixelStride();
        int w = image.getWidth(), h = image.getHeight();

        downsample(y,row,pix,w,h,curr);

        long now = System.nanoTime();
        if (lastNs != 0) {
            float f = 1e9f / Math.max(1, now-lastNs);
            fps = fps == 0 ? f : .88f*fps + .12f*f;
        }
        lastNs = now;

        if (!hasPrev) {
            System.arraycopy(curr,0,prev,0,curr.length);
            hasPrev = true;
            return Motion.NONE;
        }

        int maxPts = 28;
        float[] xs = new float[maxPts];
        float[] ys = new float[maxPts];
        float[] scores = new float[maxPts];
        int count=0;

        int[] gx={18,40,62,84,106,128,146};
        int[] gy={15,34,55,74};
        for(int yy:gy){
            for(int xx:gx){
                if(count>=maxPts) break;
                if(locked){
                    float nx=xx/(float)(DW-1), ny=yy/(float)(DH-1);
                    float ex=Math.max(.12f,targetBox*2.2f);
                    float ey=Math.max(.16f,targetBox*2.7f);
                    if(Math.abs(nx-targetX)<ex && Math.abs(ny-targetY)<ey) continue;
                }
                Match m=matchPoint(xx,yy);
                if(m!=null && m.score<23f){
                    xs[count]=m.dx;
                    ys[count]=m.dy;
                    scores[count]=m.score;
                    count++;
                }
            }
        }

        System.arraycopy(curr,0,prev,0,curr.length);
        validPoints=count;
        if(count<6){
            quality=0; dx=dy=0;
            return Motion.NONE;
        }

        float mdx=median(xs,count), mdy=median(ys,count);
        float spread=0, meanScore=0;
        int inliers=0;
        for(int i=0;i<count;i++){
            float ddx=xs[i]-mdx, ddy=ys[i]-mdy;
            float d=(float)Math.sqrt(ddx*ddx+ddy*ddy);
            meanScore+=scores[i];
            if(d<=2.5f){ spread+=d; inliers++; }
        }
        meanScore/=count;
        float inlierRatio=inliers/(float)count;
        float avgSpread=inliers>0?spread/inliers:9f;
        float q=clamp(inlierRatio*(1f-meanScore/45f)*(1f-avgSpread/8f),0f,1f);

        // Match gives where a previous patch appears in current frame: scene motion in normalized coords.
        float ndx=mdx/(DW-1f);
        float ndy=mdy/(DH-1f);
        dx=.65f*dx+.35f*ndx;
        dy=.65f*dy+.35f*ndy;
        quality=.72f*quality+.28f*q;
        return new Motion(dx,dy,quality,count);
    }

    private Match matchPoint(int x,int y){
        if(x<PATCH+SEARCH || y<PATCH+SEARCH || x>=DW-PATCH-SEARCH || y>=DH-PATCH-SEARCH) return null;
        float best=Float.MAX_VALUE, second=Float.MAX_VALUE;
        int bx=0,by=0;
        for(int oy=-SEARCH;oy<=SEARCH;oy++){
            for(int ox=-SEARCH;ox<=SEARCH;ox++){
                float s=patchSad(prev,curr,x,y,x+ox,y+oy);
                if(s<best){ second=best; best=s; bx=ox; by=oy; }
                else if(s<second) second=s;
            }
        }
        if(best>=second*.96f) return null;
        return new Match(bx,by,best);
    }

    private float patchSad(byte[] a,byte[] b,int ax,int ay,int bx,int by){
        int sum=0,n=0;
        for(int yy=-PATCH;yy<=PATCH;yy++){
            int ia=(ay+yy)*DW+(ax-PATCH);
            int ib=(by+yy)*DW+(bx-PATCH);
            for(int xx=0;xx<PATCH*2+1;xx++){
                sum+=Math.abs((a[ia+xx]&255)-(b[ib+xx]&255)); n++;
            }
        }
        return sum/(float)n;
    }

    private void downsample(ByteBuffer src,int row,int pix,int w,int h,byte[] out){
        for(int yy=0;yy<DH;yy++){
            int sy=Math.min(h-1,Math.round(yy*(h-1f)/(DH-1f)));
            for(int xx=0;xx<DW;xx++){
                int sx=Math.min(w-1,Math.round(xx*(w-1f)/(DW-1f)));
                int idx=sy*row+sx*pix;
                out[yy*DW+xx]=(idx>=0&&idx<src.limit())?src.get(idx):(byte)0;
            }
        }
    }

    private static float median(float[] a,int n){
        float[] c=Arrays.copyOf(a,n);
        Arrays.sort(c);
        return (n&1)==1?c[n/2]:(c[n/2-1]+c[n/2])*.5f;
    }

    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}

    public static final class Motion{
        public static final Motion NONE=new Motion(0,0,0,0);
        public final float dx,dy,quality; public final int points;
        Motion(float dx,float dy,float quality,int points){this.dx=dx;this.dy=dy;this.quality=quality;this.points=points;}
    }
    private static final class Match{
        final int dx,dy; final float score;
        Match(int dx,int dy,float score){this.dx=dx;this.dy=dy;this.score=score;}
    }
}
