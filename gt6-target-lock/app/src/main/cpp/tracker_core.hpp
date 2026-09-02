#pragma once
#include <cstdint>
#include <vector>
#include <array>

struct TrackResult {
    int state = 0; // 0 SEARCH, 1 TRACK, 2 LOST
    float cx = 0, cy = 0, w = 0, h = 0;
    float confidence = 0;
    float jitter = 0;
    int misses = 0;
};

class TrackerCore {
public:
    void reset();
    bool init(const uint8_t* gray, int width, int height, float cx, float cy, float boxW, float boxH);
    TrackResult process(const uint8_t* gray, int width, int height, double dt);
    bool hasTarget() const { return hasTarget_; }

private:
    struct Candidate { float cx=0, cy=0, w=0, h=0, cost=1e9f; };
    Candidate search(const uint8_t* gray, int width, int height, float predX, float predY, float radius, bool wide);
    float patchCost(const uint8_t* gray, int width, int height, float cx, float cy, float bw, float bh) const;
    void captureTemplate(const uint8_t* gray, int width, int height, float cx, float cy, float bw, float bh, bool blend);
    bool estimateOpticalFlow(const uint8_t* gray, int width, int height,
                             float cx, float cy, float bw, float bh,
                             float& dx, float& dy, float& strength) const;
    void updatePrevFrame(const uint8_t* gray, int width, int height);
    static float clampf(float v, float lo, float hi);

    bool hasTarget_ = false;
    int state_ = 0;
    int misses_ = 0;
    int reacqConfirm_ = 0;
    float cx_=0, cy_=0, bw_=0, bh_=0, vx_=0, vy_=0;
    float prevCx_=0, prevCy_=0;
    float jitterEma_=0;
    int tplW_=24, tplH_=24;
    std::vector<uint8_t> tpl_;
    std::vector<uint8_t> prevFrame_;
    int prevW_=0, prevH_=0;
    float flowDxEma_=0, flowDyEma_=0, flowStrengthEma_=0;
};
