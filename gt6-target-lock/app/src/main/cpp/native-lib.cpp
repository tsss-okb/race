#include <jni.h>
#include <chrono>
#include <mutex>
#include "tracker_core.hpp"
#include "native_sparse_flow.hpp"
#include "native_ncc.hpp"
#include "native_rescue.hpp"

static TrackerCore gTracker;
static std::mutex gMutex;
static NativeSparseFlow gSparseFlow;
static std::mutex gSparseFlowMutex;
static NativeNccMatcher gNcc;
static std::mutex gNccMutex;
static NativeNccMatcher gNccContext;
static std::mutex gNccContextMutex;
static NativeRescueEngine gRescue;
static std::mutex gRescueMutex;

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


extern "C" JNIEXPORT void JNICALL
Java_com_tsss_gt6lock_NativeSparseFlowGmcTracker_nativeClear(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gSparseFlowMutex);
    gSparseFlow.clear();
}

extern "C" JNIEXPORT void JNICALL
Java_com_tsss_gt6lock_NativeSparseFlowGmcTracker_nativeSeed(
    JNIEnv* env, jobject, jbyteArray y, jint w, jint h,
    jfloat x1, jfloat y1, jfloat x2, jfloat y2) {
    jbyte* p = env->GetByteArrayElements(y, nullptr);
    {
        std::lock_guard<std::mutex> lk(gSparseFlowMutex);
        gSparseFlow.seed(reinterpret_cast<uint8_t*>(p), w, h, x1, y1, x2, y2);
    }
    env->ReleaseByteArrayElements(y, p, JNI_ABORT);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_tsss_gt6lock_NativeSparseFlowGmcTracker_nativeTrack(
    JNIEnv* env, jobject, jbyteArray y, jint w, jint h,
    jfloat x1, jfloat y1, jfloat x2, jfloat y2) {
    jbyte* p = env->GetByteArrayElements(y, nullptr);
    NativeFlowResult r;
    {
        std::lock_guard<std::mutex> lk(gSparseFlowMutex);
        r = gSparseFlow.track(
            reinterpret_cast<uint8_t*>(p), w, h, x1, y1, x2, y2
        );
    }
    env->ReleaseByteArrayElements(y, p, JNI_ABORT);

    float out[11] = {
        r.valid ? 1.f : 0.f,
        r.dxNorm, r.dyNorm,
        r.targetConsistency, r.globalConsistency,
        (float)r.targetPoints, (float)r.backgroundPoints,
        r.globalDxNorm, r.globalDyNorm,
        r.blurRisk,
        r.pyramidUsed ? 1.f : 0.f
    };
    jfloatArray arr = env->NewFloatArray(11);
    env->SetFloatArrayRegion(arr, 0, 11, out);
    return arr;
}


extern "C" JNIEXPORT void JNICALL
Java_com_tsss_gt6lock_NativeNccMatcher_nativeClear(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gNccMutex);
    gNcc.clear();
}

extern "C" JNIEXPORT void JNICALL
Java_com_tsss_gt6lock_NativeNccMatcher_nativeSetAnchor(
    JNIEnv* env, jobject,
    jfloatArray samples,
    jint halfW, jint halfH, jint step,
    jdouble sum, jdouble sumSq,
    jfloat widthNorm, jfloat heightNorm,
    jboolean copyToCurrent
) {
    jfloat* p = env->GetFloatArrayElements(samples, nullptr);
    const int count = env->GetArrayLength(samples);
    {
        std::lock_guard<std::mutex> lk(gNccMutex);
        gNcc.setAnchor(
            p, count, halfW, halfH, step,
            sum, sumSq, widthNorm, heightNorm,
            copyToCurrent == JNI_TRUE
        );
    }
    env->ReleaseFloatArrayElements(samples, p, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tsss_gt6lock_NativeNccMatcher_nativeSetCurrent(
    JNIEnv* env, jobject,
    jfloatArray samples,
    jint halfW, jint halfH, jint step,
    jdouble sum, jdouble sumSq,
    jfloat widthNorm, jfloat heightNorm
) {
    jfloat* p = env->GetFloatArrayElements(samples, nullptr);
    const int count = env->GetArrayLength(samples);
    {
        std::lock_guard<std::mutex> lk(gNccMutex);
        gNcc.setCurrent(
            p, count, halfW, halfH, step,
            sum, sumSq, widthNorm, heightNorm
        );
    }
    env->ReleaseFloatArrayElements(samples, p, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tsss_gt6lock_NativeNccMatcher_nativeClearContext(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gNccContextMutex);
    gNccContext.clear();
}

extern "C" JNIEXPORT void JNICALL
Java_com_tsss_gt6lock_NativeNccMatcher_nativeSetContext(
    JNIEnv* env, jobject,
    jfloatArray samples,
    jint halfW, jint halfH, jint step,
    jdouble sum, jdouble sumSq,
    jfloat widthNorm, jfloat heightNorm
) {
    jfloat* p = env->GetFloatArrayElements(samples, nullptr);
    const int count = env->GetArrayLength(samples);
    {
        std::lock_guard<std::mutex> lk(gNccContextMutex);
        gNccContext.setAnchor(
            p, count, halfW, halfH, step,
            sum, sumSq, widthNorm, heightNorm,
            true
        );
    }
    env->ReleaseFloatArrayElements(samples, p, JNI_ABORT);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_tsss_gt6lock_NativeNccMatcher_nativeMatchContext(
    JNIEnv* env, jobject,
    jbyteArray y, jint w, jint h,
    jint predictedX, jint predictedY,
    jint radiusX, jint radiusY,
    jint coarseStep, jboolean wideScales
) {
    jbyte* p = env->GetByteArrayElements(y, nullptr);
    NativeNccMatch r;
    {
        std::lock_guard<std::mutex> lk(gNccContextMutex);
        r = gNccContext.match(
            reinterpret_cast<uint8_t*>(p), w, h,
            predictedX, predictedY,
            radiusX, radiusY,
            coarseStep, wideScales == JNI_TRUE
        );
    }
    env->ReleaseByteArrayElements(y, p, JNI_ABORT);

    float out[8] = {
        r.valid ? 1.f : 0.f,
        static_cast<float>(r.bestX),
        static_cast<float>(r.bestY),
        r.bestScore,
        r.secondScore,
        r.bestScale,
        r.currentScore,
        r.anchorScore
    };
    jfloatArray arr = env->NewFloatArray(8);
    env->SetFloatArrayRegion(arr, 0, 8, out);
    return arr;
}


extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_tsss_gt6lock_NativeNccMatcher_nativeMatch(
    JNIEnv* env, jobject,
    jbyteArray y, jint w, jint h,
    jint predictedX, jint predictedY,
    jint radiusX, jint radiusY,
    jint coarseStep, jboolean wideScales
) {
    jbyte* p = env->GetByteArrayElements(y, nullptr);
    NativeNccMatch r;
    {
        std::lock_guard<std::mutex> lk(gNccMutex);
        r = gNcc.match(
            reinterpret_cast<uint8_t*>(p), w, h,
            predictedX, predictedY,
            radiusX, radiusY,
            coarseStep, wideScales == JNI_TRUE
        );
    }
    env->ReleaseByteArrayElements(y, p, JNI_ABORT);

    float out[8] = {
        r.valid ? 1.f : 0.f,
        static_cast<float>(r.bestX),
        static_cast<float>(r.bestY),
        r.bestScore,
        r.secondScore,
        r.bestScale,
        r.currentScore,
        r.anchorScore
    };
    jfloatArray arr = env->NewFloatArray(8);
    env->SetFloatArrayRegion(arr, 0, 8, out);
    return arr;
}


extern "C" JNIEXPORT void JNICALL
Java_com_tsss_gt6lock_NativeRescueEngine_nativeClear(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gRescueMutex);
    gRescue.clear();
}

extern "C" JNIEXPORT void JNICALL
Java_com_tsss_gt6lock_NativeRescueEngine_nativeSeedFlow(
    JNIEnv* env, jobject, jbyteArray y, jint w, jint h,
    jfloat x1, jfloat y1, jfloat x2, jfloat y2) {
    jbyte* p=env->GetByteArrayElements(y,nullptr);
    {
        std::lock_guard<std::mutex> lk(gRescueMutex);
        gRescue.seedFlow(
            reinterpret_cast<uint8_t*>(p),w,h,x1,y1,x2,y2
        );
    }
    env->ReleaseByteArrayElements(y,p,JNI_ABORT);
}

static void setRescueTemplate(
    JNIEnv* env,jfloatArray samples,
    jint halfW,jint halfH,jint step,
    jdouble sum,jdouble sumSq,
    jfloat widthNorm,jfloat heightNorm,
    int kind) {
    jfloat* p=env->GetFloatArrayElements(samples,nullptr);
    const int count=env->GetArrayLength(samples);
    {
        std::lock_guard<std::mutex> lk(gRescueMutex);
        if(kind==0) {
            gRescue.setAnchor(
                p,count,halfW,halfH,step,
                sum,sumSq,widthNorm,heightNorm
            );
        } else if(kind==1) {
            gRescue.setCurrent(
                p,count,halfW,halfH,step,
                sum,sumSq,widthNorm,heightNorm
            );
        } else {
            gRescue.setContext(
                p,count,halfW,halfH,step,
                sum,sumSq,widthNorm,heightNorm
            );
        }
    }
    env->ReleaseFloatArrayElements(samples,p,JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tsss_gt6lock_NativeRescueEngine_nativeSetAnchor(
    JNIEnv* env,jobject,jfloatArray samples,
    jint halfW,jint halfH,jint step,
    jdouble sum,jdouble sumSq,
    jfloat widthNorm,jfloat heightNorm) {
    setRescueTemplate(
        env,samples,halfW,halfH,step,sum,sumSq,
        widthNorm,heightNorm,0
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_tsss_gt6lock_NativeRescueEngine_nativeSetCurrent(
    JNIEnv* env,jobject,jfloatArray samples,
    jint halfW,jint halfH,jint step,
    jdouble sum,jdouble sumSq,
    jfloat widthNorm,jfloat heightNorm) {
    setRescueTemplate(
        env,samples,halfW,halfH,step,sum,sumSq,
        widthNorm,heightNorm,1
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_tsss_gt6lock_NativeRescueEngine_nativeSetContext(
    JNIEnv* env,jobject,jfloatArray samples,
    jint halfW,jint halfH,jint step,
    jdouble sum,jdouble sumSq,
    jfloat widthNorm,jfloat heightNorm) {
    setRescueTemplate(
        env,samples,halfW,halfH,step,sum,sumSq,
        widthNorm,heightNorm,2
    );
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_tsss_gt6lock_NativeRescueEngine_nativeProcess(
    JNIEnv* env,jobject,
    jbyteArray y,jint w,jint h,
    jfloat x1,jfloat y1,jfloat x2,jfloat y2,
    jfloat yawRateDegS,jfloat pitchRateDegS,
    jfloat dtSec,jfloat losJitterDegS,
    jfloat visualScore) {
    jbyte* p=env->GetByteArrayElements(y,nullptr);
    NativeRescueResult r;
    {
        std::lock_guard<std::mutex> lk(gRescueMutex);
        r=gRescue.process(
            reinterpret_cast<uint8_t*>(p),w,h,
            x1,y1,x2,y2,
            yawRateDegS,pitchRateDegS,
            dtSec,losJitterDegS,visualScore
        );
    }
    env->ReleaseByteArrayElements(y,p,JNI_ABORT);

    float out[20]={
        r.flowValid?1.f:0.f,
        r.dxNorm,r.dyNorm,
        r.targetConsistency,r.globalConsistency,
        r.globalDxNorm,r.globalDyNorm,
        r.blurRisk,
        r.blurHigh?1.f:0.f,
        r.pyramidUsed?1.f:0.f,
        r.shockActive?1.f:0.f,
        r.wideActive?1.f:0.f,
        (float)r.confirmHits,
        r.accept?1.f:0.f,
        r.candidateCx,r.candidateCy,
        r.candidateScore,r.candidateUnique,
        (float)r.targetPoints,
        (float)r.backgroundPoints
    };
    jfloatArray arr=env->NewFloatArray(20);
    env->SetFloatArrayRegion(arr,0,20,out);
    return arr;
}
