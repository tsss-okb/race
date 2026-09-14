package de.droiddrone.flight;

public final class TrackerSelfTest {
    private static byte[] frame(int w, int h, int cx, int cy) {
        byte[] y = new byte[w*h];
        for (int i=0;i<y.length;i++) y[i]=(byte)35;
        for (int yy=Math.max(0,cy-22); yy<Math.min(h,cy+22); yy++) {
            for (int xx=Math.max(0,cx-28); xx<Math.min(w,cx+28); xx++) {
                int checker = ((xx/5)+(yy/5)) & 1;
                y[yy*w+xx]=(byte)(checker==0 ? 210 : 90);
            }
        }
        return y;
    }

    public static void main(String[] args) {
        int w=640,h=360;
        TargetTracker t=new TargetTracker(4);
        int cx=320,cy=180;
        t.process(frame(w,h,cx,cy),w,h,w);
        t.requestCenterLock();
        int locked=0;
        float maxErr=0f;
        for (int i=0;i<35;i++) {
            cx=320+i*3;
            cy=180+(int)Math.round(Math.sin(i*0.35)*18.0);
            TargetTracker.Result r=t.process(frame(w,h,cx,cy),w,h,w);
            if (r.state==TargetTracker.State.LOCKED) locked++;
            float ex=Math.abs(r.centerX*w-cx);
            float ey=Math.abs(r.centerY*h-cy);
            maxErr=Math.max(maxErr,Math.max(ex,ey));
        }
        if (locked < 30 || maxErr > 16f) {
            throw new RuntimeException("tracker test failed locked="+locked+" maxErr="+maxErr);
        }
        System.out.println("TRACKER_SELF_TEST_OK locked="+locked+" maxErr="+maxErr);
    }
}
