#include "tracker_core.hpp"
#include <algorithm>
#include <cmath>
#include <limits>

float TrackerCore::clampf(float v, float lo, float hi) { return std::max(lo, std::min(hi, v)); }

void TrackerCore::reset() {
    hasTarget_ = false; state_ = 0; misses_ = 0; reacqConfirm_ = 0;
    cx_=cy_=bw_=bh_=vx_=vy_=prevCx_=prevCy_=jitterEma_=0;
    tpl_.clear();
}

bool TrackerCore::init(const uint8_t* gray, int width, int height, float cx, float cy, float boxW, float boxH) {
    if (!gray || width < 64 || height < 64) return false;
    bw_ = clampf(boxW, 24.f, width * 0.45f);
    bh_ = clampf(boxH, 24.f, height * 0.45f);
    cx_ = clampf(cx, bw_/2, width-bw_/2);
    cy_ = clampf(cy, bh_/2, height-bh_/2);
    vx_=vy_=0; prevCx_=cx_; prevCy_=cy_; misses_=0; reacqConfirm_=0; jitterEma_=0;
    tpl_.assign(tplW_*tplH_, 0);
    captureTemplate(gray,width,height,cx_,cy_,bw_,bh_,false);
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
            tpl_[k] = blend ? (uint8_t)std::lround(0.92f*tpl_[k] + 0.08f*v) : v;
        }
    }
}

float TrackerCore::patchCost(const uint8_t* gray, int width, int height, float cx, float cy, float bw, float bh) const {
    if (tpl_.empty()) return 1e9f;
    if (cx-bw/2<1 || cy-bh/2<1 || cx+bw/2>=width-1 || cy+bh/2>=height-1) return 1e9f;
    long sad=0; long gradSad=0; int n=0;
    for (int ty=2; ty<tplH_-2; ty+=2) {
        float fy = cy - bh*0.5f + (ty + 0.5f) * bh / tplH_;
        int iy=(int)fy;
        for (int tx=2; tx<tplW_-2; tx+=2) {
            float fx = cx - bw*0.5f + (tx + 0.5f) * bw / tplW_;
            int ix=(int)fx;
            int v=gray[iy*width+ix], t=tpl_[ty*tplW_+tx];
            sad += std::abs(v-t);
            int gx = (int)gray[iy*width+ix+1]-(int)gray[iy*width+ix-1];
            int tgx = (int)tpl_[ty*tplW_+tx+1]-(int)tpl_[ty*tplW_+tx-1];
            gradSad += std::abs(gx-tgx);
            ++n;
        }
    }
    if (!n) return 1e9f;
    return 0.72f*(float)sad/(n*255.f) + 0.28f*(float)gradSad/(n*510.f);
}

TrackerCore::Candidate TrackerCore::search(const uint8_t* gray,int width,int height,float predX,float predY,float radius,bool wide) {
    Candidate best; best.cost=1e9f;
    const float scales[] = {0.94f,1.0f,1.06f};
    int coarseStep = wide ? 8 : 5;
    float maxR = std::min(radius, wide ? std::max(width,height)*0.45f : 150.f);
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

    float predX=cx_+vx_*(float)dt, predY=cy_+vy_*(float)dt;
    float speed=std::sqrt(vx_*vx_+vy_*vy_);
    bool wide = state_==2;
    float radius = wide ? std::max(180.f, std::min(420.f, 0.32f*std::max(width,height)))
                        : std::max(48.f, std::min(135.f, 30.f + 0.055f*speed));
    Candidate c=search(gray,width,height,predX,predY,radius,wide);
    float conf = c.cost>=1e8f ? 0.f : clampf(1.f - c.cost/0.34f,0.f,1.f);
    float threshold = wide ? 0.55f : 0.48f;

    if (conf >= threshold) {
        if (state_==2) {
            if (reacqConfirm_==0 || std::hypot(c.cx-cx_,c.cy-cy_) < std::max(80.f,bw_*1.5f)) {
                ++reacqConfirm_; cx_=c.cx; cy_=c.cy; bw_=c.w; bh_=c.h;
            } else reacqConfirm_=1;
            if (reacqConfirm_>=2) { state_=1; misses_=0; vx_=vy_=0; captureTemplate(gray,width,height,cx_,cy_,bw_,bh_,true); }
        } else {
            float oldX=cx_, oldY=cy_;
            float alpha = conf>0.75f ? 0.78f : 0.62f;
            float beta  = conf>0.75f ? 0.20f : 0.12f;
            float ex=c.cx-predX, ey=c.cy-predY;
            cx_=predX+alpha*ex; cy_=predY+alpha*ey;
            vx_=vx_ + beta*ex/(float)dt; vy_=vy_ + beta*ey/(float)dt;
            bw_=0.85f*bw_+0.15f*c.w; bh_=0.85f*bh_+0.15f*c.h;
            misses_=0;
            float d=std::hypot(cx_-oldX,cy_-oldY);
            jitterEma_=0.90f*jitterEma_+0.10f*d;
            if (conf>0.78f && d<std::max(35.f,0.35f*bw_)) captureTemplate(gray,width,height,cx_,cy_,bw_,bh_,true);
        }
    } else {
        ++misses_;
        if (state_==1) {
            cx_=predX; cy_=predY;
            if (misses_>=3) { state_=2; reacqConfirm_=0; vx_=vy_=0; }
        }
    }

    cx_=clampf(cx_,bw_/2,width-bw_/2); cy_=clampf(cy_,bh_/2,height-bh_/2);
    r.state=state_; r.cx=cx_; r.cy=cy_; r.w=bw_; r.h=bh_; r.confidence=conf; r.jitter=jitterEma_; r.misses=misses_;
    prevCx_=cx_; prevCy_=cy_;
    return r;
}
