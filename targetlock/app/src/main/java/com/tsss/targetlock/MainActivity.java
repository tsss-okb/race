package com.tsss.targetlock;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.view.*;
import android.widget.FrameLayout;

public class MainActivity extends Activity {
    private CameraPreview preview;
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout root=new FrameLayout(this);
        preview=new CameraPreview(this);
        root.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        root.addView(new HudView(this,preview.getTracker(),preview.getDetector()),new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA},10);
        else preview.start();
    }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){
        super.onRequestPermissionsResult(r,p,g);
        if(r==10&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED) preview.start();
    }
    @Override protected void onResume(){super.onResume();if(preview!=null&&checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)preview.start();}
    @Override protected void onPause(){if(preview!=null)preview.stop();super.onPause();}
}
