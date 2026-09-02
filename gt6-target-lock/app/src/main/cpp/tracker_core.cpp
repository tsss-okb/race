#include "tracker_core.hpp"
#include <algorithm>
#include <cmath>
#include <limits>
#include <cstring>

float TrackerCore::clampf(float v, float lo, float hi) { return std::max(lo, std::min(hi, v)); }

void TrackerCore::reset() {
    hasTarget_ = false; state_ = 0; misses_ = 0; reacqConfirm_ = 0;
    cx_=cy_=bw_=bh_=vx_=vy_=prevCx_=prevCy_=jitterEma_=0;
    flowDxEma_=flowDyEma_=flowStrengthEma_=0;
    tpl_.clear();
    prevFrame_.clear(); prevW_=prevH_=0;
}

bool TrackerCore::init(const uint8_t* gray, int width, int height, float cx, float cy, float boxW, float boxH) {
    if (!gray || width < 64 || height < 64) return false;
    bw_ = clampf(boxW, 12.f, width * 0.32f);
    bh_ = clampf(boxH, 12.f, height * 0.36f);
    cx_ = clampf(cx, bw_/2, width-bw_/2);
    cy_ = clampf(cy, bh_/2, height-bh_/2);
    vx_=vy_=0; prevCx_=cx_; prevCy_=cy_; misses_=0; reacqConfirm_=0; jitterEma_=0;
    tpl_.assign(tplW_*tplH_, 0);
    captureTemplate(gray,width,height,cx_,cy_,bw_,bh_,false);
    updatePrevFrame(gray,width,height);
    flowDxEma_=flowDyEma_=flowStrengthEma_=0;
    hasTarget_ = true; state_ = 1;
    return true;
}

void TrackerCore::captureTemplate(const uint8_t* gray, int width, int height, float cx, float cy, float bw, float bh, bool blend) {
    if (tpl_.empty()) tpl_.assign(tplW_*tplH_, 0);
    for (int ty=0; ty<tplH_; ++ty) {
        float fy = cy - bh*0.5f + (ty + 0.5f) * bh / tplH_;
        int iy = std::max(0, std::min(height-1, (int)std::lround(fy)));
        for (int tx=0; tx<tplW_; ++tx) {
            float fx = cx - bw*0.5f + (tx + 0.5f) * bw / tplW_;
            int ix = std::max(0, std::min(width-1, (int)std::lround(fx)));
            uint8_t v = gray[iy*width + ix];
            int k = ty*tplW_+tx;
            tpl_[k] = blend ? (uint8_t)std::lround(0.965f*tpl_[k] + 0.035f*v) : v;
        }
    }
}

float TrackerCore::patchCost(const uint8_t* gray, int width, int height, float cx, float cy, float bw, float bh) const {
    if (tpl_.empty()) return 1e9f;
    if (cx-bw/2<1 || cy-bh/2<1 || cx+bw/2>=width-1 || cy+bh/2>=height-1) return 1e9f;

    // Two-pass mean-normalized SAD. It is still tiny (~121 samples) but
    // much less sensitive to auto-exposure changes than raw intensity SAD.
    int sumV=0, sumT=0, n=0;
    for (int ty=1; ty<tplH_-1; ty+=2) {
        float fy = cy - bh*0.5f + (ty + 0.5f) * bh / tplH_;
        int iy=(int)fy;
        for (int tx=1; tx<tplW_-1; tx+=2) {
            float fx = cx - bw*0.5f + (tx + 0.5f) * bw / tplW_;
            int ix=(int)fx;
            sumV += gray[iy*width+ix];
            sumT += tpl_[ty*tplW_+tx];
            ++n;
        }
    }
    if (!n) return 1e9f;

    const float meanV=(float)sumV/n, meanT=(float)sumT/n;
    long normSad=0, gradSad=0;
    for (int ty=1; ty<tplH_-1; ty+=2) {
        float fy = cy - bh*0.5f + (ty + 0.5f) * bh / tplH_;
        int iy=(int)fy;
        for (int tx=1; tx<tplW_-1; tx+=2) {
            float fx = cx - bw*0.5f + (tx + 0.5f) * bw / tplW_;
            int ix=(int)fx;
            int v=gray[iy*width+ix], t=tpl_[ty*tplW_+tx];
            normSad += (long)std::abs((v-meanV) - (t-meanT));

            int gx = (int)gray[iy*width+ix+1]-(int)gray[iy*width+ix-1];
            int gy = (int)gray[(iy+1)*width+ix]-(int)gray[(iy-1)*width+ix];
            int tgx = (int)tpl_[ty*tplW_+tx+1]-(int)tpl_[ty*tplW_+tx-1];
            int tgy = (int)tpl_[(ty+1)*tplW_+tx]-(int)tpl_[(ty-1)*tplW_+tx];
            gradSad += std::abs(gx-tgx) + std::abs(gy-tgy);
        }
    }
    return 0.66f*(float)normSad/(n*255.f) + 0.34f*(float)gradSad/(n*1020.f);
}

void TrackerCore::updatePrevFrame(const uint8_t* gray, int width, int height) {
    if (!gray || width<=0 || height<=0) return;
    const size_t n=(size_t)width*(size_t)height;
    if (prevFrame_.size()!=n) prevFrame_.resize(n);
    std::memcpy(prevFrame_.data(),gray,n);
    prevW_=width; prevH_=height;
}

bool TrackerCore::estimateOpticalFlow(const uint8_t* gray, int width, int height,
                                      float cx, float cy, float bw, float bh,
                                      float& dx, float& dy, float& strength) const {
    dx=dy=strength=0.f;
    if (!gray || prevFrame_.empty() || prevW_!=width || prevH_!=height) return false;

    // Sparse Lucas-Kanade over the target core. No pyramid/OpenCV allocation.
    int x0=std::max(2,(int)std::floor(cx-bw*0.42f));
    int x1=std::min(width-3,(int)std::ceil(cx+bw*0.42f));
    int y0=std::max(2,(int)std::floor(cy-bh*0.42f));
    int y1=std::min(height-3,(int)std::ceil(cy+bh*0.42f));
    if (x1-x0<6 || y1-y0<6) return false;

    double a11=0,a12=0,a22=0,b1=0,b2=0;
    double gradEnergy=0;
    int n=0;
    const uint8_t* prev=prevFrame_.data();

    for (int y=y0; y<=y1; y+=2) {
        for (int x=x0; x<=x1; x+=2) {
            int idx=y*width+x;
            float ix=0.25f*((int)prev[idx+1]-(int)prev[idx-1] +
                            (int)gray[idx+1]-(int)gray[idx-1]);
            float iy=0.25f*((int)prev[idx+width]-(int)prev[idx-width] +
                            (int)gray[idx+width]-(int)gray[idx-width]);
            float it=(float)gray[idx]-(float)prev[idx];

            float g2=ix*ix+iy*iy;
            if (g2<20.f) continue;

            a11+=ix*ix; a12+=ix*iy; a22+=iy*iy;
            b1+=-ix*it; b2+=-iy*it;
            gradEnergy+=g2;
            ++n;
        }
    }

    if (n<12) return false;
    double det=a11*a22-a12*a12;
    double trace=a11+a22;
    if (det<1e-4 || trace<1e-3) return false;

    float u=(float)((a22*b1-a12*b2)/det);
    float v=(float)((a11*b2-a12*b1)/det);

    // Single-level LK is a local predictor. Large values are unreliable and
    // are left to the template matcher / YOLO reacquire.
    float mag=std::sqrt(u*u+v*v);
    if (!std::isfinite(mag) || mag>12.f) return false;

    // Structure quality: high only when gradients constrain both axes.
    double quality=4.0*det/(trace*trace+1e-9);
    float q=clampf((float)quality,0.f,1.f);
    float texture=clampf((float)(gradEnergy/(n*900.0)),0.f,1.f);
    strength=q*texture;
    if (strength<0.06f) return false;

    dx=u; dy=v;
    return true;
}

TrackerCore::Candidate TrackerCore::search(const uint8_t* gray,int width,int height,float predX,float predY,float radius,bool wide) {
    Candidate best; best.cost=1e9f;
    const float scales[] = {0.94f,1.0f,1.06f};
    int coarseStep = wide ? 7 : 4;
    float maxR = std::min(radius, wide ? std::max(width,height)*0.32f : 52.f);
    for (float s: scales) {
        float tw=bw_*s, th=bh_*s;
        for (int dy=-(int)maxR; dy<=(int)maxR; dy+=coarseStep) {
            for (int dx=-(int)maxR; dx<=(int)maxR; dx+=coarseStep) {
                if (dx*dx+dy*dy > maxR*maxR) continue;
                float x=predX+dx, y=predY+dy;
                float c=patchCost(gray,width,height,x,y,tw,th);
                if (c<best.cost) best={x,y,tw,th,c};
            }
        }
    }
    Candidate fine=best;
    if (best.cost<1e8f) {
        for (int dy=-coarseStep; dy<=coarseStep; ++dy) for (int dx=-coarseStep; dx<=coarseStep; ++dx) {
            float x=best.cx+dx, y=best.cy+dy;
            float c=patchCost(gray,width,height,x,y,best.w,best.h);
            if (c<fine.cost) fine={x,y,best.w,best.h,c};
        }
    }
    return fine;
}

TrackResult TrackerCore::process(const uint8_t* gray, int width, int height, double dt) {
    TrackResult r;
    if (!hasTarget_ || !gray) { r.state=0; return r; }
    dt = std::max(1.0/120.0, std::min(0.08, dt));

    float flowDx=0.f, flowDy=0.f, flowStrength=0.f;
    bool flowOk = state_==1 && estimateOpticalFlow(gray,width,height,cx_,cy_,bw_,bh_,
                                                   flowDx,flowDy,flowStrength);
    if (flowOk) {
        float k = flowStrength>0.35f ? 0.45f : 0.25f;
        flowDxEma_=(1.f-k)*flowDxEma_+k*flowDx;
        flowDyEma_=(1.f-k)*flowDyEma_+k*flowDy;
        flowStrengthEma_=0.75f*flowStrengthEma_+0.25f*flowStrength;
    } else {
        flowDxEma_*=0.72f; flowDyEma_*=0.72f; flowStrengthEma_*=0.75f;
    }

    float flowWeight=clampf(0.25f+0.70f*flowStrengthEma_,0.25f,0.82f);
    float predX=cx_+vx_*(float)dt + flowWeight*flowDxEma_;
    float predY=cy_+vy_*(float)dt + flowWeight*flowDyEma_;
    float speed=std::sqrt(vx_*vx_+vy_*vy_);
    bool wide = state_==2;
    float radius = wide ? std::max(46.f, std::min(96.f, 0.28f*std::max(width,height)))
                        : std::max(16.f, std::min(50.f, 13.f + 0.042f*speed));
    if (!wide && flowStrengthEma_>0.30f) radius*=0.78f;
    if (wide) { vx_*=0.94f; vy_*=0.94f; }
    Candidate c=search(gray,width,height,predX,predY,radius,wide);
    float conf = c.cost>=1e8f ? 0.f : clampf(1.f - c.cost/0.30f,0.f,1.f);
    float threshold = wide ? 0.50f : 0.44f;

    if (conf >= threshold) {
        if (state_==2) {
            if (reacqConfirm_==0 || std::hypot(c.cx-cx_,c.cy-cy_) < std::max(42.f,bw_*2.2f)) {
                ++reacqConfirm_; cx_=c.cx; cy_=c.cy; bw_=c.w; bh_=c.h;
            } else reacqConfirm_=1;
            if (reacqConfirm_>=2) {
                state_=1; misses_=0;
                if (!(flowOk && flowStrength>0.10f)) vx_=vy_=0;
                captureTemplate(gray,width,height,cx_,cy_,bw_,bh_,true);
            }
        } else {
            float oldX=cx_, oldY=cy_;
            float alpha = conf>0.72f ? 0.82f : 0.66f;
            float beta  = conf>0.72f ? 0.24f : 0.14f;
            float ex=c.cx-predX, ey=c.cy-predY;
            cx_=predX+alpha*ex; cy_=predY+alpha*ey;
            vx_=vx_ + beta*ex/(float)dt; vy_=vy_ + beta*ey/(float)dt;
            if (flowOk && flowStrength>0.10f) {
                float fv=clampf(0.08f+0.18f*flowStrength,0.08f,0.22f);
                vx_=(1.f-fv)*vx_ + fv*flowDx/(float)dt;
                vy_=(1.f-fv)*vy_ + fv*flowDy/(float)dt;
            }
            bw_=0.85f*bw_+0.15f*c.w; bh_=0.85f*bh_+0.15f*c.h;
            misses_=0;
            float d=std::hypot(cx_-oldX,cy_-oldY);
            jitterEma_=0.90f*jitterEma_+0.10f*d;
            if (conf>0.84f && d<std::max(5.f,0.22f*bw_)) captureTemplate(gray,width,height,cx_,cy_,bw_,bh_,true);
        }
    } else {
        ++misses_;
        if (state_==1) {
            cx_=predX; cy_=predY;
            if (flowOk && flowStrength>0.12f) {
                vx_=0.88f*vx_+0.12f*flowDx/(float)dt;
                vy_=0.88f*vy_+0.12f*flowDy/(float)dt;
            }
            if (misses_>=6) { state_=2; reacqConfirm_=0; vx_*=0.84f; vy_*=0.84f; }
        }
    }

    cx_=clampf(cx_,bw_/2,width-bw_/2); cy_=clampf(cy_,bh_/2,height-bh_/2);
    r.state=state_; r.cx=cx_; r.cy=cy_; r.w=bw_; r.h=bh_; r.confidence=conf; r.jitter=jitterEma_; r.misses=misses_;
    prevCx_=cx_; prevCy_=cy_;
    updatePrevFrame(gray,width,height);
    return r;
}
