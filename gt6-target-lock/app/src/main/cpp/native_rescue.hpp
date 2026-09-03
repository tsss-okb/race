#pragma once
#include <cstdint>
#include "native_sparse_flow.hpp"
#include "native_ncc.hpp"

struct NativeRescueResult {
    bool flowValid=false;
    float dxNorm=0.f, dyNorm=0.f;
    float targetConsistency=0.f, globalConsistency=0.f;
    float globalDxNorm=0.f, globalDyNorm=0.f;
    float blurRisk=0.f;
    bool blurHigh=false;
    bool pyramidUsed=false;
    bool shockActive=false;
    bool wideActive=false;
    int confirmHits=0;
    bool accept=false;
    float candidateCx=0.f, candidateCy=0.f;
    float candidateScore=0.f, candidateUnique=0.f;
    int targetPoints=0, backgroundPoints=0;
};

class NativeRescueEngine {
public:
    void clear();

    void seedFlow(const uint8_t* gray,int w,int h,
                  float x1,float y1,float x2,float y2);

    void setAnchor(const float* samples,int count,
                   int halfW,int halfH,int step,
                   double sum,double sumSq,
                   float widthNorm,float heightNorm);

    void setCurrent(const float* samples,int count,
                    int halfW,int halfH,int step,
                    double sum,double sumSq,
                    float widthNorm,float heightNorm);

    void setContext(const float* samples,int count,
                    int halfW,int halfH,int step,
                    double sum,double sumSq,
                    float widthNorm,float heightNorm);

    NativeRescueResult process(
        const uint8_t* gray,int w,int h,
        float x1,float y1,float x2,float y2,
        float yawRateDegS,float pitchRateDegS,
        float dtSec,float losJitterDegS,
        float visualScore
    );

private:
    NativeSparseFlow flow_;
    NativeNccMatcher core_;
    NativeNccMatcher context_;

    bool hasContext_=false;
    long long frameSerial_=0;

    int shockFrames_=0;
    int shockCooldown_=0;
    int wideFrames_=0;
    int wideCooldown_=0;
    int blurGuardFrames_=0;

    float lastYawRate_=0.f;
    float lastPitchRate_=0.f;

    bool haveCandidate_=false;
    int confirmHits_=0;
    long long candidateLastFrame_=-100;
    float candidateCx_=0.f;
    float candidateCy_=0.f;
    float candidateScore_=0.f;

    void clearCandidate();
};
