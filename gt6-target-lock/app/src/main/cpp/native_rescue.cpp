#include "native_rescue.hpp"
#include <algorithm>
#include <cmath>

void NativeRescueEngine::clearCandidate(){
    haveCandidate_=false;
    confirmHits_=0;
    candidateLastFrame_=-100;
    candidateCx_=candidateCy_=candidateScore_=0.f;
}

void NativeRescueEngine::clear(){
    flow_.clear();
    core_.clear();
    context_.clear();
    hasContext_=false;
    frameSerial_=0;
    shockFrames_=shockCooldown_=0;
    wideFrames_=wideCooldown_=0;
    blurGuardFrames_=0;
    lastYawRate_=lastPitchRate_=0.f;
    clearCandidate();
}

void NativeRescueEngine::seedFlow(
    const uint8_t* gray,int w,int h,
    float x1,float y1,float x2,float y2){
    flow_.seed(gray,w,h,x1,y1,x2,y2);
    clearCandidate();
}

void NativeRescueEngine::setAnchor(
    const float* samples,int count,
    int halfW,int halfH,int step,
    double sum,double sumSq,
    float widthNorm,float heightNorm){
    core_.setAnchor(
        samples,count,halfW,halfH,step,
        sum,sumSq,widthNorm,heightNorm,true
    );
    clearCandidate();
}

void NativeRescueEngine::setCurrent(
    const float* samples,int count,
    int halfW,int halfH,int step,
    double sum,double sumSq,
    float widthNorm,float heightNorm){
    core_.setCurrent(
        samples,count,halfW,halfH,step,
        sum,sumSq,widthNorm,heightNorm
    );
}

void NativeRescueEngine::setContext(
    const float* samples,int count,
    int halfW,int halfH,int step,
    double sum,double sumSq,
    float widthNorm,float heightNorm){
    context_.setAnchor(
        samples,count,halfW,halfH,step,
        sum,sumSq,widthNorm,heightNorm,true
    );
    hasContext_=true;
}

NativeRescueResult NativeRescueEngine::process(
    const uint8_t* gray,int w,int h,
    float x1,float y1,float x2,float y2,
    float yawRateDegS,float pitchRateDegS,
    float dtSec,float losJitterDegS,
    float visualScore
){
    NativeRescueResult out;
    if(!gray||w<80||h<45) return out;

    ++frameSerial_;

    const auto flow=flow_.track(gray,w,h,x1,y1,x2,y2);
    out.flowValid=flow.valid;
    out.dxNorm=flow.dxNorm;
    out.dyNorm=flow.dyNorm;
    out.targetConsistency=flow.targetConsistency;
    out.globalConsistency=flow.globalConsistency;
    out.globalDxNorm=flow.globalDxNorm;
    out.globalDyNorm=flow.globalDyNorm;
    out.blurRisk=flow.blurRisk;
    out.pyramidUsed=flow.pyramidUsed;
    out.targetPoints=flow.targetPoints;
    out.backgroundPoints=flow.backgroundPoints;

    if(flow.blurRisk>=0.38f){
        blurGuardFrames_=3;
    }else if(blurGuardFrames_>0){
        --blurGuardFrames_;
    }
    out.blurHigh=blurGuardFrames_>0;

    const float dt=std::clamp(dtSec,1.f/240.f,0.12f);
    const float ownRateMag=std::hypot(yawRateDegS,pitchRateDegS);
    const float rateJerk=std::hypot(
        yawRateDegS-lastYawRate_,
        pitchRateDegS-lastPitchRate_
    )/dt;
    lastYawRate_=yawRateDegS;
    lastPitchRate_=pitchRateDegS;

    const bool shockEvent=
        ownRateMag>=45.f ||
        rateJerk>=850.f ||
        (losJitterDegS>=10.f && ownRateMag>=16.f);

    if(shockEvent && shockCooldown_==0){
        shockFrames_=6;
        shockCooldown_=12;
    }
    out.shockActive=shockFrames_>0;

    const float globalShiftPx=std::hypot(
        flow.globalDxNorm*w,
        flow.globalDyNorm*h
    );
    const bool hardFlowFail=
        !flow.valid || flow.targetConsistency<0.36f;
    const bool flowJump=
        !flow.valid ||
        std::fabs(flow.dxNorm)+std::fabs(flow.dyNorm)>0.050f ||
        flow.targetConsistency<0.44f;

    const bool wideTrigger=
        shockEvent ||
        (flow.pyramidUsed && globalShiftPx>=10.f) ||
        (losJitterDegS>=7.5f && flowJump) ||
        (hardFlowFail && visualScore<0.62f);

    if(wideTrigger && wideCooldown_==0){
        wideFrames_=6;
        wideCooldown_=12;
        clearCandidate();
    }

    out.wideActive=wideFrames_>0;

    const bool runWide=
        out.wideActive &&
        !out.blurHigh &&
        (frameSerial_%2LL==0LL) &&
        hasContext_;

    if(runWide){
        const int centerX=(int)std::lround(((x1+x2)*0.5f)*w);
        const int centerY=(int)std::lround(((y1+y2)*0.5f)*h);

        const auto coarse=context_.match(
            gray,w,h,
            centerX,centerY,
            (int)(w*0.46f),(int)(h*0.42f),
            8,true
        );

        if(coarse.valid){
            const float coarseScore=std::clamp(
                (coarse.bestScore+1.f)*0.5f,0.f,1.f
            );
            const float coarseUnique=std::clamp(
                coarse.bestScore-coarse.secondScore,0.f,1.f
            );

            if(coarseScore>=0.70f && coarseUnique>=0.025f){
                const auto fine=core_.match(
                    gray,w,h,
                    coarse.bestX,coarse.bestY,
                    54,42,2,true
                );

                if(fine.valid){
                    const float score=std::clamp(
                        (fine.bestScore+1.f)*0.5f,0.f,1.f
                    );
                    const float unique=std::clamp(
                        fine.bestScore-fine.secondScore,0.f,1.f
                    );
                    const float currentVote=std::clamp(
                        (fine.currentScore+1.f)*0.5f,0.f,1.f
                    );
                    const float anchorVote=std::clamp(
                        (fine.anchorScore+1.f)*0.5f,0.f,1.f
                    );

                    if(score>=0.74f &&
                       unique>=0.030f &&
                       currentVote>=0.66f &&
                       anchorVote>=0.68f){
                        const float combinedScore=std::min(
                            std::min(coarseScore,score),
                            std::min(currentVote,anchorVote)
                        );
                        const float combinedUnique=
                            std::min(coarseUnique,unique);

                        const float cx=(float)fine.bestX/w;
                        const float cy=(float)fine.bestY/h;

                        bool confirms=false;
                        if(haveCandidate_){
                            const long long gap=
                                frameSerial_-candidateLastFrame_;
                            const float distPx=std::hypot(
                                (cx-candidateCx_)*w,
                                (cy-candidateCy_)*h
                            );
                            const bool scoreStable=
                                std::fabs(combinedScore-candidateScore_)<=0.10f;
                            confirms=
                                gap>=1 && gap<=3 &&
                                distPx<=48.f &&
                                scoreStable;
                        }

                        confirmHits_=confirms
                            ? std::min(2,confirmHits_+1)
                            : 1;
                        haveCandidate_=true;
                        candidateLastFrame_=frameSerial_;
                        candidateCx_=cx;
                        candidateCy_=cy;
                        candidateScore_=combinedScore;

                        out.confirmHits=confirmHits_;
                        out.candidateCx=cx;
                        out.candidateCy=cy;
                        out.candidateScore=combinedScore;
                        out.candidateUnique=combinedUnique;

                        if(confirmHits_>=2){
                            out.accept=true;

                            const float bw=std::max(0.005f,x2-x1);
                            const float bh=std::max(0.005f,y2-y1);
                            const float nx1=std::clamp(cx-bw*0.5f,0.f,1.f);
                            const float ny1=std::clamp(cy-bh*0.5f,0.f,1.f);
                            const float nx2=std::clamp(cx+bw*0.5f,0.f,1.f);
                            const float ny2=std::clamp(cy+bh*0.5f,0.f,1.f);

                            core_.resetCurrentToAnchor();
                            flow_.seed(gray,w,h,nx1,ny1,nx2,ny2);
                            wideFrames_=0;
                            clearCandidate();
                        }
                    }else{
                        clearCandidate();
                    }
                }else{
                    clearCandidate();
                }
            }else{
                clearCandidate();
            }
        }else{
            clearCandidate();
        }
    }else{
        out.confirmHits=confirmHits_;
    }

    if(shockFrames_>0) --shockFrames_;
    if(shockCooldown_>0) --shockCooldown_;
    if(wideFrames_>0) --wideFrames_;
    if(wideCooldown_>0) --wideCooldown_;

    if(wideFrames_==0 && !out.accept){
        clearCandidate();
    }

    return out;
}
