#include <jni.h>
#include <chrono>
#include <mutex>
#include "tracker_core.hpp"

static TrackerCore gTracker;
static std::mutex gMutex;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tsss_gt6lock_NativeTracker_nativeInit(JNIEnv* env, jobject, jbyteArray y, jint w, jint h, jfloat cx, jfloat cy, jfloat bw, jfloat bh) {
    jbyte* p=env->GetByteArrayElements(y,nullptr);
    std::lock_guard<std::mutex> lk(gMutex);
    bool ok=gTracker.init(reinterpret_cast<uint8_t*>(p),w,h,cx,cy,bw,bh);
    env->ReleaseByteArrayElements(y,p,JNI_ABORT);
    return ok;
}

extern "C" JNIEXPORT void JNICALL
Java_com_tsss_gt6lock_NativeTracker_nativeReset(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gMutex); gTracker.reset();
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_tsss_gt6lock_NativeTracker_nativeProcess(JNIEnv* env, jobject, jbyteArray y, jint w, jint h, jdouble dt) {
    auto t0=std::chrono::steady_clock::now();
    jbyte* p=env->GetByteArrayElements(y,nullptr);
    TrackResult r;
    { std::lock_guard<std::mutex> lk(gMutex); r=gTracker.process(reinterpret_cast<uint8_t*>(p),w,h,dt); }
    env->ReleaseByteArrayElements(y,p,JNI_ABORT);
    auto t1=std::chrono::steady_clock::now();
    float ms=std::chrono::duration<float,std::milli>(t1-t0).count();
    float out[9]={(float)r.state,r.cx,r.cy,r.w,r.h,r.confidence,r.jitter,ms,(float)r.misses};
    jfloatArray arr=env->NewFloatArray(9); env->SetFloatArrayRegion(arr,0,9,out); return arr;
}
