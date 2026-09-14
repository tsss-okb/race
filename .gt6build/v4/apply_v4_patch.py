#!/usr/bin/env python3
from pathlib import Path
import argparse, re, shutil

p=argparse.ArgumentParser()
p.add_argument('repo', nargs='?', default='.')
a=p.parse_args()
root=Path(a.repo).resolve()
kit=Path(__file__).resolve().parent
flight=root/'Flight/src/main/java/de/droiddrone/flight'
layout=root/'Flight/src/main/res/layout/activity_main.xml'
build=root/'Flight/build.gradle'
manifest=root/'Flight/src/main/AndroidManifest.xml'
strings=root/'Flight/src/main/res/values/strings.xml'
serial=flight/'Serial.java'
stream=flight/'StreamEncoder.java'
svc=flight/'DDService.java'
ma=flight/'MainActivity.java'

for q in [flight, layout, build, manifest, strings, serial, stream, svc, ma, root/'libuvccamera']:
    if not q.exists(): raise SystemExit(f'Missing expected file: {q}')

def replace_once(path, old, new, label=None):
    s=path.read_text(encoding='utf-8')
    if new in s:
        print('already patched:', label or path.relative_to(root)); return
    if old not in s:
        raise SystemExit(f'Anchor not found in {path}: {old[:120]!r}')
    path.write_text(s.replace(old,new,1),encoding='utf-8')
    print('patched:', label or path.relative_to(root))

def regex_once(path, pattern, repl, label=None):
    s=path.read_text(encoding='utf-8')
    n=re.subn(pattern,repl,s,count=1,flags=re.S)
    if n[1] != 1:
        raise SystemExit(f'Regex anchor mismatch in {path}: {pattern[:100]} count={n[1]}')
    path.write_text(n[0],encoding='utf-8')
    print('patched:', label or path.relative_to(root))

# New tracker backend files.
for name in ['TargetTracker.java','EncodedFrameAnalyzer.java']:
    shutil.copy2(kit/name, flight/name)
    print('copied:', (flight/name).relative_to(root))

# ---------------------------------------------------------------------
# StreamEncoder: tracker observes a copy of the encoded stream. Camera2
# session itself is untouched.
# ---------------------------------------------------------------------
replace_once(stream,
'''    private Surface surface;\n''',
'''    private Surface surface;\n    private volatile EncodedFrameAnalyzer targetAnalyzer;\n    private volatile boolean targetAnalyzerRequested;\n    private volatile boolean targetLockPending;\n    private float targetCx = 0.5f, targetCy = 0.5f, targetW = 0.18f, targetH = 0.18f;\n    private volatile String targetAnalyzerLastError = "";\n''')

replace_once(stream,
'''    public Surface initializeVideo() {\n''',
'''    public Surface initializeVideo() {\n        resetTargetAnalyzerForEncoderRestart();\n''')

old_read='''                ByteBuffer outputByteBuffer = codec.getOutputBuffer(index);\n                if (outputByteBuffer == null){\n                    codec.releaseOutputBuffer(index, false);\n                    return;\n                }\n                outputByteBuffer.get(buf);\n                codec.releaseOutputBuffer(index, false);\n'''
new_read='''                ByteBuffer outputByteBuffer = codec.getOutputBuffer(index);\n                if (outputByteBuffer == null){\n                    codec.releaseOutputBuffer(index, false);\n                    return;\n                }\n                ByteBuffer copy = outputByteBuffer.duplicate();\n                int start = Math.max(0, info.offset);\n                int end = Math.min(copy.capacity(), start + info.size);\n                copy.position(start);\n                copy.limit(end);\n                copy.get(buf, 0, Math.min(buf.length, copy.remaining()));\n                codec.releaseOutputBuffer(index, false);\n'''
replace_once(stream,old_read,new_read)

replace_once(stream,
'''            if (sendFrames || info.flags == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {\n''',
'''            EncodedFrameAnalyzer analyzer = targetAnalyzer;\n            if (analyzer != null) {\n                try { analyzer.offer(buf, info); }\n                catch (Exception e) { log("Target analyzer offer error: " + e); }\n            }\n            if (sendFrames || info.flags == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {\n''')

replace_once(stream,
'''        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {\n            log("encoderCallback - onOutputFormatChanged");\n        }\n''',
'''        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {\n            log("encoderCallback - onOutputFormatChanged");\n            startTargetAnalyzerIfNeeded(format);\n        }\n''')

anchor='''    private final MediaCodec.Callback encoderCallback = new MediaCodec.Callback() {\n'''
methods='''    public synchronized void requestCenterTargetLock(){\n        requestTargetLock(0.5f, 0.5f, 0.18f, 0.18f);\n    }\n\n    public synchronized void requestTargetLock(float centerX, float centerY, float width, float height){\n        targetCx = Math.max(0.02f, Math.min(0.98f, centerX));\n        targetCy = Math.max(0.02f, Math.min(0.98f, centerY));\n        targetW = Math.max(0.04f, Math.min(0.70f, width));\n        targetH = Math.max(0.04f, Math.min(0.70f, height));\n        targetAnalyzerRequested = true;\n        targetLockPending = true;\n        targetAnalyzerLastError = "";\n        startTargetAnalyzerIfNeeded(null);\n    }\n\n    private synchronized void startTargetAnalyzerIfNeeded(MediaFormat suppliedFormat){\n        if (!targetAnalyzerRequested || targetAnalyzer != null) return;\n        MediaFormat fmt = suppliedFormat;\n        if (fmt == null) {\n            try {\n                if (videoEncoder != null && isVideoEncoderInitialized) fmt = videoEncoder.getOutputFormat();\n            } catch (Exception ignore) {\n                return;\n            }\n        }\n        if (fmt == null) return;\n        try {\n            targetAnalyzer = new EncodedFrameAnalyzer(fmt, 12);\n            if (targetLockPending) {\n                targetAnalyzer.getTracker().requestLock(targetCx, targetCy, targetW, targetH);\n                targetLockPending = false;\n            }\n            log("Target analyzer started from encoded stream.");\n        } catch (Exception e) {\n            targetAnalyzerLastError = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());\n            targetAnalyzer = null;\n            log("Target analyzer start error: " + e);\n        }\n    }\n\n    private synchronized void resetTargetAnalyzerForEncoderRestart(){\n        EncodedFrameAnalyzer a = targetAnalyzer;\n        targetAnalyzer = null;\n        if (a != null) try { a.close(); } catch (Exception ignore) {}\n        if (targetAnalyzerRequested) targetLockPending = true;\n    }\n\n    public synchronized void clearTargetLock(){\n        targetAnalyzerRequested = false;\n        targetLockPending = false;\n        targetAnalyzerLastError = "";\n        EncodedFrameAnalyzer a = targetAnalyzer;\n        targetAnalyzer = null;\n        if (a != null) try { a.close(); } catch (Exception ignore) {}\n    }\n\n    public TargetTracker.Result getTargetTrackerResult(){\n        EncodedFrameAnalyzer a = targetAnalyzer;\n        return a != null ? a.getTracker().getResult() : TargetTracker.Result.idle();\n    }\n\n    public int getTargetTrackerFps(){\n        EncodedFrameAnalyzer a = targetAnalyzer;\n        return a != null ? a.getCurrentFps() : 0;\n    }\n\n    public String getTargetAnalyzerStatus(){\n        EncodedFrameAnalyzer a = targetAnalyzer;\n        if (!targetAnalyzerRequested) return "IDLE";\n        if (a != null) return a.getStatus();\n        if (targetAnalyzerLastError != null && !targetAnalyzerLastError.isEmpty()) return "ERROR: " + targetAnalyzerLastError;\n        return "WAIT VIDEO";\n    }\n\n'''+anchor
replace_once(stream,anchor,methods)

replace_once(stream,
'''    public void close(){\n        sendFrames = false;\n''',
'''    public void close(){\n        clearTargetLock();\n        sendFrames = false;\n''')

# ---------------------------------------------------------------------
# DDService: bridge UI to StreamEncoder; survive process/service restore
# without assuming MainActivity.config is already initialized.
# ---------------------------------------------------------------------
replace_once(svc,
'''public class DDService extends Service {\n''',
'''public class DDService extends Service {\n    private static volatile DDService instance;\n''')
replace_once(svc,
'''    public void onCreate() {\n        super.onCreate();\n    }\n''',
'''    public void onCreate() {\n        super.onCreate();\n        instance = this;\n    }\n\n    public static void requestCenterTargetLock(){\n        DDService s = instance;\n        if (s != null && s.streamEncoder != null) s.streamEncoder.requestCenterTargetLock();\n    }\n\n    public static void requestTargetLock(float centerX, float centerY, float width, float height){\n        DDService s = instance;\n        if (s != null && s.streamEncoder != null) s.streamEncoder.requestTargetLock(centerX, centerY, width, height);\n    }\n\n    public static void clearTargetLock(){\n        DDService s = instance;\n        if (s != null && s.streamEncoder != null) s.streamEncoder.clearTargetLock();\n    }\n\n    public static TargetTracker.Result getTargetTrackerResult(){\n        DDService s = instance;\n        return s != null && s.streamEncoder != null ? s.streamEncoder.getTargetTrackerResult() : TargetTracker.Result.idle();\n    }\n\n    public static int getTargetTrackerFps(){\n        DDService s = instance;\n        return s != null && s.streamEncoder != null ? s.streamEncoder.getTargetTrackerFps() : 0;\n    }\n\n    public static String getTargetAnalyzerStatus(){\n        DDService s = instance;\n        return s != null && s.streamEncoder != null ? s.streamEncoder.getTargetAnalyzerStatus() : "SERVICE OFF";\n    }\n''')
replace_once(svc,
'''    public int onStartCommand(Intent intent, int flags, int startId) {\n        isConnected = false;\n''',
'''    public int onStartCommand(Intent intent, int flags, int startId) {\n        isConnected = false;\n        if (MainActivity.config == null) MainActivity.config = new Config(this, SettingsCommon.versionCompatibleCode);\n''')
replace_once(svc,
'''    public void onDestroy() {\n        closeAll();\n    }\n''',
'''    public void onDestroy() {\n        closeAll();\n        instance = null;\n        super.onDestroy();\n    }\n''')

# ---------------------------------------------------------------------
# MainActivity diagnostic/lock UI.
# ---------------------------------------------------------------------
replace_once(ma,
'''    private Button bStartStopService;\n''',
'''    private Button bStartStopService, bTargetLock;\n''')
replace_once(ma,
'''    private TextView tvNetworkStatus, tvFcStatus, tvConnectionModeHint;\n''',
'''    private TextView tvNetworkStatus, tvFcStatus, tvConnectionModeHint, tvTargetStatus;\n''')
replace_once(ma,
'''        bStartStopService = findViewById(R.id.startStopService);\n''',
'''        bStartStopService = findViewById(R.id.startStopService);\n        bTargetLock = findViewById(R.id.targetLock);\n        tvTargetStatus = findViewById(R.id.tvTargetStatus);\n        bTargetLock.setOnClickListener(v -> {\n            TargetTracker.Result r = DDService.getTargetTrackerResult();\n            String backend = DDService.getTargetAnalyzerStatus();\n            if (r.state == TargetTracker.State.IDLE && !backend.startsWith("RUNNING")) DDService.requestCenterTargetLock();\n            else if (r.state == TargetTracker.State.IDLE) DDService.requestCenterTargetLock();\n            else DDService.clearTargetLock();\n        });\n''')
replace_once(ma,
'''    private void updateUi(){\n        if (DDService.isRunning){\n''',
'''    private void updateUi(){\n        TargetTracker.Result tr = DDService.getTargetTrackerResult();\n        String trackerBackend = DDService.getTargetAnalyzerStatus();\n        if (tvTargetStatus != null) {\n            tvTargetStatus.setText(String.format(java.util.Locale.US,\n                    "TRACKER: %s / %s  conf %.2f  dx %.3f  dy %.3f  %d fps",\n                    trackerBackend, tr.state, tr.confidence, tr.errorX, tr.errorY, DDService.getTargetTrackerFps()));\n            if (trackerBackend.startsWith("ERROR")) tvTargetStatus.setTextColor(Color.RED);\n            else if (tr.state == TargetTracker.State.LOCKED) tvTargetStatus.setTextColor(Color.GREEN);\n            else if (tr.state == TargetTracker.State.LOST) tvTargetStatus.setTextColor(Color.RED);\n            else tvTargetStatus.setTextColor(Color.BLACK);\n        }\n        if (bTargetLock != null) {\n            bTargetLock.setText(tr.state == TargetTracker.State.IDLE ? "LOCK CENTER" : "CLEAR LOCK");\n            bTargetLock.setEnabled(DDService.isRunning);\n        }\n        if (DDService.isRunning){\n''')

xml=layout.read_text(encoding='utf-8')
layout_anchor='''    <LinearLayout\n        android:layout_width="match_parent"\n        android:layout_height="0dip"\n'''
layout_block='''    <LinearLayout\n        android:layout_width="match_parent"\n        android:layout_height="wrap_content"\n        android:orientation="horizontal">\n\n        <Button\n            android:id="@+id/targetLock"\n            android:layout_width="wrap_content"\n            android:layout_height="wrap_content"\n            android:text="LOCK CENTER" />\n\n        <TextView\n            android:id="@+id/tvTargetStatus"\n            android:layout_width="0dp"\n            android:layout_height="wrap_content"\n            android:layout_weight="1"\n            android:paddingStart="8dp"\n            android:text="TRACKER: SERVICE OFF" />\n    </LinearLayout>\n\n'''+layout_anchor
if '@+id/targetLock' not in xml:
    if layout_anchor not in xml: raise SystemExit('layout anchor missing')
    layout.write_text(xml.replace(layout_anchor,layout_block,1),encoding='utf-8')
    print('patched:', layout.relative_to(root))

# ---------------------------------------------------------------------
# Android 16 / 16 KB native compatibility. Native serial is not needed on
# the GT6 USB-OTG path, so remove its old 4 KB prebuilt dependency and use
# usb-serial-for-android exclusively.
# ---------------------------------------------------------------------
s=serial.read_text(encoding='utf-8')
s=s.replace('import android_serialport_api.SerialPortFinder;\n','')
s=s.replace('import tp.xmaihh.serialport.SerialHelper;\n','')
s=s.replace('import tp.xmaihh.serialport.bean.ComBean;\n','')
s=re.sub(r'    // native serial\n.*?    private final Context context;\n','    private final Context context;\n',s,flags=re.S,count=1)
s=s.replace('    private SerialHelper serialHelper;\n','')
old_branch='''                    if (config.isUseNativeSerialPort()){\n                        if (openNativeSerialPort()) {\n                            log("Native serial port is opened");\n                            return;\n                        }\n                    }else {\n                        if (findDevice()) {\n                            if (checkPermission()) {\n                                if (openUsbSerialPort()) {\n                                    log("USB Serial port is opened");\n                                    return;\n                                }\n                            }\n                        }\n                    }\n'''
new_branch='''                    if (config.isUseNativeSerialPort()) {\n                        log("GT6 build: native /dev serial is disabled; using USB-OTG serial.");\n                    }\n                    if (findDevice()) {\n                        if (checkPermission()) {\n                            if (openUsbSerialPort()) {\n                                log("USB Serial port is opened");\n                                return;\n                            }\n                        }\n                    }\n'''
if old_branch not in s: raise SystemExit('Serial init branch anchor missing')
s=s.replace(old_branch,new_branch,1)
s=re.sub(r'    private boolean openNativeSerialPort\(\)\{.*?\n    private boolean openUsbSerialPort\(\)\{',
'''    private boolean openNativeSerialPort(){\n        log("GT6 build: native serial port backend is disabled.");\n        return false;\n    }\n\n    private boolean openUsbSerialPort(){''',s,flags=re.S,count=1)
s=s.replace('''            if (config.isUseNativeSerialPort()){\n                if (serialHelper != null && serialHelper.isOpen()) serialHelper.send(data);\n            }else{\n                if (port != null && port.isOpen()) port.write(data, serialPortReadWriteTimeoutMs);\n            }\n''','''            if (port != null && port.isOpen()) port.write(data, serialPortReadWriteTimeoutMs);\n''')
s=s.replace('''        try {\n            if (serialHelper != null) serialHelper.close();\n        } catch (Exception e) {\n            //\n        }\n''','')
if 'SerialHelper' in s or 'ComBean' in s or 'SerialPortFinder' in s:
    raise SystemExit('Native serial symbols remain after patch')
serial.write_text(s,encoding='utf-8')
print('patched:', serial.relative_to(root))

# Build config: separate installable package, compressed JNI libs (avoids old
# APK zip-alignment limitations), no old native serial dependency.
bs=build.read_text(encoding='utf-8')
bs=bs.replace('applicationId "de.droiddrone.flight"','applicationId "de.droiddrone.flight.gt6v4"',1)
bs=bs.replace('versionCode 9','versionCode 12',1)
bs=bs.replace('versionName "2.3.0"','versionName "2.3.0-gt6-v4"',1)
bs=bs.replace("    implementation 'io.github.xmaihh:serialport:2.1.2'\n",'')
if 'useLegacyPackaging true' not in bs:
    bs=bs.replace('''    compileOptions {\n''','''    packagingOptions {\n        jniLibs {\n            useLegacyPackaging true\n        }\n    }\n    compileOptions {\n''',1)
build.write_text(bs,encoding='utf-8')
print('patched:', build.relative_to(root))

ms=manifest.read_text(encoding='utf-8')
ms=ms.replace('android:name=".DDService"','android:name="de.droiddrone.flight.DDService"')
ms=ms.replace('android:name=".MainActivity"','android:name="de.droiddrone.flight.MainActivity"')
if 'android:extractNativeLibs=' not in ms:
    ms=ms.replace('''    <application\n        android:icon="@mipmap/ic_launcher"''','''    <application\n        android:extractNativeLibs="true"\n        android:icon="@mipmap/ic_launcher"''',1)
manifest.write_text(ms,encoding='utf-8')
print('patched:', manifest.relative_to(root))

ss=strings.read_text(encoding='utf-8')
ss=ss.replace('<string name="app_name">DroidDrone - Flight</string>', '<string name="app_name">GT6 DroidDrone v4 SAFE</string>')
strings.write_text(ss,encoding='utf-8')
print('patched:', strings.relative_to(root))

# 16 KB ELF alignment for every shared library built by libuvccamera (NDK r26).
ldflags='LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384\n'
native_files=[
    root/'libuvccamera/src/main/jni/UVCCamera/Android.mk',
    root/'libuvccamera/src/main/jni/libusb/android/jni/libusb.mk',
    root/'libuvccamera/src/main/jni/libuvc/android/jni/Android.mk',
]
for nf in native_files:
    if not nf.exists(): raise SystemExit(f'native makefile missing: {nf}')
    ns=nf.read_text(encoding='utf-8')
    if 'max-page-size=16384' in ns:
        continue
    if nf.name == 'Android.mk' and 'UVCCamera/Android.mk' in str(nf):
        anchor='include $(CLEAR_VARS)\n\n'
    elif 'libusb' in str(nf):
        anchor='include $(CLEAR_VARS)\n\n'
    else:
        anchor='# libuvc\ninclude $(CLEAR_VARS)\n\n'
    if anchor not in ns: raise SystemExit(f'linker anchor missing: {nf}')
    ns=ns.replace(anchor,anchor+ldflags,1)
    nf.write_text(ns,encoding='utf-8')
    print('16KB patched:', nf.relative_to(root))

print('GT6 DroidDrone v4 robust patch applied successfully.')
