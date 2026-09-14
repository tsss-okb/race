#!/usr/bin/env python3
from pathlib import Path
import argparse

p = argparse.ArgumentParser()
p.add_argument('repo', nargs='?', default='.')
a = p.parse_args()
root = Path(a.repo).resolve()
ic = root/'Flight/src/main/java/de/droiddrone/flight/InternalCamera.java'
build = root/'Flight/build.gradle'
manifest = root/'Flight/src/main/AndroidManifest.xml'
strings = root/'Flight/src/main/res/values/strings.xml'


def replace_once(path, old, new):
    s = path.read_text(encoding='utf-8')
    if new in s:
        print('already fixed:', path.relative_to(root))
        return
    if old not in s:
        raise SystemExit(f'v3 anchor not found in {path}: {old[:100]!r}')
    path.write_text(s.replace(old, new, 1), encoding='utf-8')
    print('v3 fixed:', path.relative_to(root))

# Do not attach ImageReader at service startup. Keep original DroidDrone camera path first.
replace_once(ic,
'''        boolean ok = getCameraCharacteristics(config.getCameraId());
        if (ok) {
            if (frameAnalyzer != null) frameAnalyzer.close();
            frameAnalyzer = new FrameAnalyzer(analysisResolution.getWidth(), analysisResolution.getHeight(), 30);
            analysisSurface = frameAnalyzer.getSurface();
        }
        return ok;
''',
'''        // v3 safe mode: baseline camera starts without an analysis Surface.
        // The tracker ImageReader is attached only after an explicit LOCK request.
        return getCameraCharacteristics(config.getCameraId());
''')

replace_once(ic,
'''    private Size analysisResolution = new Size(640, 360);
''',
'''    private Size analysisResolution = new Size(640, 360);
    private boolean trackerSessionFallback;
''')

# If a second Camera2 output is unsupported, restore the baseline session automatically.
replace_once(ic,
'''        } catch (Exception e) {
            log("createCaptureSession error: " + e);
        }
''',
'''        } catch (Exception e) {
            log("createCaptureSession error: " + e);
            if (analysisSurface != null) fallbackToBaselineCamera(String.valueOf(e));
        }
''')

replace_once(ic,
'''        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession){
            log("CameraCaptureSession - onConfigureFailed");
        }
''',
'''        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession){
            log("CameraCaptureSession - onConfigureFailed");
            if (analysisSurface != null) fallbackToBaselineCamera("session configuration failed");
        }
''')

old_methods = '''    public void requestCenterTargetLock(){
        if (frameAnalyzer != null) frameAnalyzer.getTracker().requestCenterLock();
    }

    public void requestTargetLock(float centerX, float centerY, float width, float height){
        if (frameAnalyzer != null) frameAnalyzer.getTracker().requestLock(centerX, centerY, width, height);
    }

    public void clearTargetLock(){
        if (frameAnalyzer != null) frameAnalyzer.getTracker().clear();
    }

    public TargetTracker.Result getTargetTrackerResult(){
        return frameAnalyzer != null ? frameAnalyzer.getTracker().getResult() : TargetTracker.Result.idle();
    }

    public int getTargetTrackerFps(){
        return frameAnalyzer != null ? frameAnalyzer.getCurrentFps() : 0;
    }
'''
new_methods = '''    private synchronized boolean ensureFrameAnalyzer(){
        if (frameAnalyzer != null) return true;
        try {
            frameAnalyzer = new FrameAnalyzer(analysisResolution.getWidth(), analysisResolution.getHeight(), 24);
            analysisSurface = frameAnalyzer.getSurface();
            trackerSessionFallback = false;
            return true;
        } catch (Exception e) {
            log("Target analyzer init failed: " + e);
            disableFrameAnalyzer();
            return false;
        }
    }

    private synchronized void disableFrameAnalyzer(){
        if (frameAnalyzer != null){
            try { frameAnalyzer.close(); } catch (Exception ignore) {}
        }
        frameAnalyzer = null;
        analysisSurface = null;
    }

    private void restartPreviewSession(){
        try {
            if (mCameraCaptureSession != null) mCameraCaptureSession.close();
        } catch (Exception ignore) {}
        if (handlerThread != null) {
            new Handler(handlerThread.getLooper()).postDelayed(this::startPreview, 150);
        }
    }

    private void fallbackToBaselineCamera(String reason){
        if (analysisSurface == null || trackerSessionFallback) return;
        trackerSessionFallback = true;
        log("Target analyzer disabled; restoring baseline camera: " + reason);
        disableFrameAnalyzer();
        restartPreviewSession();
    }

    public synchronized void requestCenterTargetLock(){
        boolean needRestart = frameAnalyzer == null;
        if (!ensureFrameAnalyzer()) return;
        frameAnalyzer.getTracker().requestCenterLock();
        if (needRestart && isOpened) restartPreviewSession();
    }

    public synchronized void requestTargetLock(float centerX, float centerY, float width, float height){
        boolean needRestart = frameAnalyzer == null;
        if (!ensureFrameAnalyzer()) return;
        frameAnalyzer.getTracker().requestLock(centerX, centerY, width, height);
        if (needRestart && isOpened) restartPreviewSession();
    }

    public synchronized void clearTargetLock(){
        boolean hadAnalyzer = frameAnalyzer != null;
        if (frameAnalyzer != null) frameAnalyzer.getTracker().clear();
        if (hadAnalyzer) {
            try { if (mCameraCaptureSession != null) mCameraCaptureSession.close(); } catch (Exception ignore) {}
            disableFrameAnalyzer();
            if (handlerThread != null && isOpened) {
                new Handler(handlerThread.getLooper()).postDelayed(this::startPreview, 150);
            }
        }
    }

    public TargetTracker.Result getTargetTrackerResult(){
        return frameAnalyzer != null ? frameAnalyzer.getTracker().getResult() : TargetTracker.Result.idle();
    }

    public int getTargetTrackerFps(){
        return frameAnalyzer != null ? frameAnalyzer.getCurrentFps() : 0;
    }
'''
replace_once(ic, old_methods, new_methods)

# Existing v2 close code remains safe; use helper to avoid double-close logic.
replace_once(ic,
'''        if (frameAnalyzer != null){
            frameAnalyzer.close();
            frameAnalyzer = null;
            analysisSurface = null;
        }
''',
'''        disableFrameAnalyzer();
''')

# Separate package = no signature collision with the previous baseline/debug APK.
bs = build.read_text(encoding='utf-8')
bs = bs.replace('applicationId "de.droiddrone.flight"', 'applicationId "de.droiddrone.flight.gt6tracker"', 1)
bs = bs.replace('versionCode 10', 'versionCode 11', 1)
bs = bs.replace('versionName "2.3.0-gt6-tracker1"', 'versionName "2.3.0-gt6-tracker-v3"', 1)
build.write_text(bs, encoding='utf-8')
print('v3 fixed: Flight/build.gradle')

ms = manifest.read_text(encoding='utf-8')
ms = ms.replace('android:name=".DDService"', 'android:name="de.droiddrone.flight.DDService"')
ms = ms.replace('android:name=".MainActivity"', 'android:name="de.droiddrone.flight.MainActivity"')
manifest.write_text(ms, encoding='utf-8')
print('v3 fixed: Flight/src/main/AndroidManifest.xml')

ss = strings.read_text(encoding='utf-8')
ss = ss.replace('<string name="app_name">DroidDrone - Flight</string>', '<string name="app_name">GT6 DroidDrone Tracker v3</string>')
strings.write_text(ss, encoding='utf-8')
print('v3 fixed: Flight/src/main/res/values/strings.xml')
print('GT6 v3 safe-camera fix applied successfully.')
