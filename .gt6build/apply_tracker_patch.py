#!/usr/bin/env python3
from pathlib import Path
import argparse, shutil

p=argparse.ArgumentParser()
p.add_argument('repo', nargs='?', default='.')
a=p.parse_args()
root=Path(a.repo).resolve()
kit=Path(__file__).resolve().parent
flight=root/'Flight/src/main/java/de/droiddrone/flight'
layout=root/'Flight/src/main/res/layout/activity_main.xml'
build=root/'Flight/build.gradle'
for q in [flight/'InternalCamera.java',flight/'CameraManager.java',flight/'DDService.java',flight/'MainActivity.java',layout,build]:
    if not q.exists(): raise SystemExit(f'Missing expected DroidDrone v2.3.0 file: {q}')

def edit(path, old, new, count=None):
    s=path.read_text(encoding='utf-8')
    if new in s:
        print('already patched:', path.relative_to(root)); return
    n=s.count(old)
    if n < 1: raise SystemExit(f'Anchor not found in {path}: {old[:80]!r}')
    if count is not None and n < count: raise SystemExit(f'Anchor count mismatch in {path}: {n} < {count}')
    s=s.replace(old,new,1 if count is None else count)
    path.write_text(s,encoding='utf-8')
    print('patched:', path.relative_to(root))

for name in ['TargetTracker.java','FrameAnalyzer.java']:
    shutil.copy2(kit/'files/de/droiddrone/flight'/name, flight/name)
    print('copied:', (flight/name).relative_to(root))

cm=flight/'CameraManager.java'
anchor='''    public void close(){\n'''
insert='''    public void requestCenterTargetLock(){\n        if (internalCamera != null) internalCamera.requestCenterTargetLock();\n    }\n\n    public void requestTargetLock(float centerX, float centerY, float width, float height){\n        if (internalCamera != null) internalCamera.requestTargetLock(centerX, centerY, width, height);\n    }\n\n    public void clearTargetLock(){\n        if (internalCamera != null) internalCamera.clearTargetLock();\n    }\n\n    public TargetTracker.Result getTargetTrackerResult(){\n        return internalCamera != null ? internalCamera.getTargetTrackerResult() : TargetTracker.Result.idle();\n    }\n\n    public int getTargetTrackerFps(){\n        return internalCamera != null ? internalCamera.getTargetTrackerFps() : 0;\n    }\n\n    public void close(){\n'''
edit(cm,anchor,insert)

ic=flight/'InternalCamera.java'
edit(ic,'    private Surface streamEncoderSurface, recorderSurface;\n','    private Surface streamEncoderSurface, recorderSurface;\n    private Surface analysisSurface;\n    private FrameAnalyzer frameAnalyzer;\n    private Size analysisResolution = new Size(640, 360);\n')
edit(ic,'        return getCameraCharacteristics(config.getCameraId());\n','''        boolean ok = getCameraCharacteristics(config.getCameraId());\n        if (ok) {\n            if (frameAnalyzer != null) frameAnalyzer.close();\n            frameAnalyzer = new FrameAnalyzer(analysisResolution.getWidth(), analysisResolution.getHeight(), 30);\n            analysisSurface = frameAnalyzer.getSurface();\n        }\n        return ok;\n''')
edit(ic,'                outputConfigurations.add(outputConfig);\n','                outputConfigurations.add(outputConfig);\n                if (analysisSurface != null) outputConfigurations.add(new OutputConfiguration(analysisSurface));\n')
old='''                for (Surface target : targets) {\n                    OutputConfiguration outputConfig = new OutputConfiguration(target);\n                    outputConfigurations.add(outputConfig);\n                }\n'''
new=old+'''                if (analysisSurface != null) outputConfigurations.add(new OutputConfiguration(analysisSurface));\n'''
edit(ic,old,new)
edit(ic,'            captureRequest.addTarget(streamEncoderSurface);\n','            captureRequest.addTarget(streamEncoderSurface);\n            if (analysisSurface != null) captureRequest.addTarget(analysisSurface);\n')
edit(ic,'        StreamConfigurationMap configurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);\n','        StreamConfigurationMap configurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);\n        analysisResolution = FrameAnalyzer.chooseAnalysisSize(configurationMap);\n')
old='''    @Override\n    public boolean isFrontFacing(){\n        return frontFacing;\n    }\n'''
new=old+'''\n    public void requestCenterTargetLock(){\n        if (frameAnalyzer != null) frameAnalyzer.getTracker().requestCenterLock();\n    }\n\n    public void requestTargetLock(float centerX, float centerY, float width, float height){\n        if (frameAnalyzer != null) frameAnalyzer.getTracker().requestLock(centerX, centerY, width, height);\n    }\n\n    public void clearTargetLock(){\n        if (frameAnalyzer != null) frameAnalyzer.getTracker().clear();\n    }\n\n    public TargetTracker.Result getTargetTrackerResult(){\n        return frameAnalyzer != null ? frameAnalyzer.getTracker().getResult() : TargetTracker.Result.idle();\n    }\n\n    public int getTargetTrackerFps(){\n        return frameAnalyzer != null ? frameAnalyzer.getCurrentFps() : 0;\n    }\n'''
edit(ic,old,new)
edit(ic,'        isStarted = false;\n        if (camera != null){\n','''        isStarted = false;\n        if (frameAnalyzer != null){\n            frameAnalyzer.close();\n            frameAnalyzer = null;\n            analysisSurface = null;\n        }\n        if (camera != null){\n''')

svc=flight/'DDService.java'
edit(svc,'public class DDService extends Service {\n','public class DDService extends Service {\n    private static volatile DDService instance;\n')
edit(svc,'    public void onCreate() {\n        super.onCreate();\n    }\n','''    public void onCreate() {\n        super.onCreate();\n        instance = this;\n    }\n\n    public static void requestCenterTargetLock(){\n        DDService s = instance;\n        if (s != null && s.cameraManager != null) s.cameraManager.requestCenterTargetLock();\n    }\n\n    public static void requestTargetLock(float centerX, float centerY, float width, float height){\n        DDService s = instance;\n        if (s != null && s.cameraManager != null) s.cameraManager.requestTargetLock(centerX, centerY, width, height);\n    }\n\n    public static void clearTargetLock(){\n        DDService s = instance;\n        if (s != null && s.cameraManager != null) s.cameraManager.clearTargetLock();\n    }\n\n    public static TargetTracker.Result getTargetTrackerResult(){\n        DDService s = instance;\n        return s != null && s.cameraManager != null ? s.cameraManager.getTargetTrackerResult() : TargetTracker.Result.idle();\n    }\n\n    public static int getTargetTrackerFps(){\n        DDService s = instance;\n        return s != null && s.cameraManager != null ? s.cameraManager.getTargetTrackerFps() : 0;\n    }\n''')
edit(svc,'    public void onDestroy() {\n        closeAll();\n    }\n','    public void onDestroy() {\n        closeAll();\n        instance = null;\n    }\n')

ma=flight/'MainActivity.java'
edit(ma,'    private Button bStartStopService;\n','    private Button bStartStopService, bTargetLock;\n')
edit(ma,'    private TextView tvNetworkStatus, tvFcStatus, tvConnectionModeHint;\n','    private TextView tvNetworkStatus, tvFcStatus, tvConnectionModeHint, tvTargetStatus;\n')
edit(ma,'        bStartStopService = findViewById(R.id.startStopService);\n','''        bStartStopService = findViewById(R.id.startStopService);\n        bTargetLock = findViewById(R.id.targetLock);\n        tvTargetStatus = findViewById(R.id.tvTargetStatus);\n        bTargetLock.setOnClickListener(v -> {\n            TargetTracker.Result r = DDService.getTargetTrackerResult();\n            if (r.state == TargetTracker.State.IDLE) DDService.requestCenterTargetLock();\n            else DDService.clearTargetLock();\n        });\n''')
edit(ma,'    private void updateUi(){\n        if (DDService.isRunning){\n','''    private void updateUi(){\n        TargetTracker.Result tr = DDService.getTargetTrackerResult();\n        if (tvTargetStatus != null) {\n            tvTargetStatus.setText(String.format(java.util.Locale.US,\n                    "TARGET: %s  conf %.2f  dx %.3f  dy %.3f  %d fps",\n                    tr.state, tr.confidence, tr.errorX, tr.errorY, DDService.getTargetTrackerFps()));\n        }\n        if (bTargetLock != null) {\n            bTargetLock.setText(tr.state == TargetTracker.State.IDLE ? "LOCK CENTER" : "CLEAR LOCK");\n            bTargetLock.setEnabled(DDService.isRunning);\n        }\n        if (DDService.isRunning){\n''')

xml=layout
anchor='''    <LinearLayout\n        android:layout_width="match_parent"\n        android:layout_height="0dip"\n'''
block='''    <LinearLayout\n        android:layout_width="match_parent"\n        android:layout_height="wrap_content"\n        android:orientation="horizontal">\n\n        <Button\n            android:id="@+id/targetLock"\n            android:layout_width="wrap_content"\n            android:layout_height="wrap_content"\n            android:text="LOCK CENTER" />\n\n        <TextView\n            android:id="@+id/tvTargetStatus"\n            android:layout_width="0dp"\n            android:layout_height="wrap_content"\n            android:layout_weight="1"\n            android:paddingStart="10dp"\n            android:text="TARGET: IDLE" />\n    </LinearLayout>\n\n'''+anchor
edit(xml,anchor,block)

bs=build.read_text(encoding='utf-8')
if 'versionName "2.3.0-gt6-tracker1"' not in bs:
    bs=bs.replace('versionCode 9','versionCode 10',1).replace('versionName "2.3.0"','versionName "2.3.0-gt6-tracker1"',1)
    build.write_text(bs,encoding='utf-8'); print('patched: Flight/build.gradle')
print('GT6 tracker patch applied successfully.')
