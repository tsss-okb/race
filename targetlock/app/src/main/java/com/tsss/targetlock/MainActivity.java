package com.tsss.targetlock;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.view.*;
import android.widget.FrameLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private CameraPreview preview;
    private FrameLayout root;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        configureDisplay();
        root=new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        try{
            preview=new CameraPreview(this);
            root.addView(preview,new FrameLayout.LayoutParams(-1,-1));
            root.addView(new HudView(this,preview,preview.getTracker(),preview.getImu()),
                    new FrameLayout.LayoutParams(-1,-1));
        }catch(Throwable t){
            showFatal("UI INIT",t);
            return;
        }

        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA},10);
        else root.postDelayed(()->{if(preview!=null)preview.start();},250);
    }

    private void configureDisplay(){
        try{
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            Display d=getWindowManager().getDefaultDisplay();
            Display.Mode best=null;
            for(Display.Mode m:d.getSupportedModes()){
                if(best==null||m.getRefreshRate()>best.getRefreshRate())best=m;
                if(m.getRefreshRate()>=119&&m.getRefreshRate()<=121.5f){best=m;break;}
            }
            WindowManager.LayoutParams lp=getWindow().getAttributes();
            if(best!=null)lp.preferredDisplayModeId=best.getModeId();
            lp.preferredRefreshRate=120f;
            getWindow().setAttributes(lp);
        }catch(Throwable ignored){}
    }

    private void showFatal(String where,Throwable t){
        TextView tv=new TextView(this);
        tv.setTextColor(Color.WHITE);tv.setBackgroundColor(Color.rgb(25,25,25));
        tv.setTextSize(18);tv.setPadding(30,30,30,30);
        tv.setText("Target Lock Core\nSAFE MODE\n\n"+where+"\n"+t.getClass().getSimpleName()+"\n"+String.valueOf(t.getMessage()));
        root.removeAllViews();root.addView(tv,new FrameLayout.LayoutParams(-1,-1));
    }

    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){
        super.onRequestPermissionsResult(r,p,g);
        if(r==10&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED&&preview!=null)
            root.postDelayed(preview::start,200);
    }

    @Override protected void onResume(){
        super.onResume();
        if(preview!=null&&checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)
            root.postDelayed(preview::start,250);
    }

    @Override protected void onPause(){
        if(preview!=null)preview.stop();
        super.onPause();
    }
}
