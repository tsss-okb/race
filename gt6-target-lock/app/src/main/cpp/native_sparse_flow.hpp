#pragma once
#include <cstdint>
#include <vector>

struct NativeFlowResult {
    bool valid=false;
    float dxNorm=0.f, dyNorm=0.f;
    float targetConsistency=0.f, globalConsistency=0.f;
    float globalDxNorm=0.f, globalDyNorm=0.f;
    float blurRisk=0.f;
    bool pyramidUsed=false;
    int targetPoints=0, backgroundPoints=0;
};

class NativeSparseFlow {
public:
    void clear();
    void seed(const uint8_t* gray, int w, int h,
              float x1n, float y1n, float x2n, float y2n);
    NativeFlowResult track(const uint8_t* gray, int w, int h,
                           float x1n, float y1n, float x2n, float y2n);

private:
    struct P { int x=0,y=0; };
    struct Move { P from; float dx=0,dy=0; };

    std::vector<uint8_t> prev_;
    int w_=0,h_=0,frameCount_=0;
    std::vector<P> targetPts_, bgPts_;
    float lastDx_=0.f,lastDy_=0.f;
    float sharpRef_=0.f;

    static int pix(const uint8_t* g,int w,int x,int y){ return g[y*w+x]; }
    static float median(std::vector<float> v);
    static float consistency(const std::vector<Move>& m,float dx,float dy);
    static std::vector<Move> robust(std::vector<Move> m);

    std::vector<P> chooseGradient(const uint8_t* g,int w,int h,
                                  int x1,int y1,int x2,int y2,int maxCount) const;
    std::vector<P> chooseTarget(const uint8_t* g,int w,int h,
                                float x1n,float y1n,float x2n,float y2n,int maxCount) const;
    std::vector<P> chooseBg(const uint8_t* g,int w,int h,
                            float x1n,float y1n,float x2n,float y2n,int maxCount) const;

    static int patchSad(const uint8_t* a,const uint8_t* b,int w,
                        int ax,int ay,int bx,int by);
    static bool bestMatch(const uint8_t* a,const uint8_t* b,int w,int h,
                          int x,int y,int radius,int& dx,int& dy);
    static bool bestMatchAround(const uint8_t* a,const uint8_t* b,int w,int h,
                                int sx,int sy,int ex,int ey,int radius,int& bx,int& by);
    static std::vector<Move> trackFb(const uint8_t* prev,const uint8_t* cur,
                                     int w,int h,const std::vector<P>& pts,int radius);
    static std::vector<Move> trackFbPrior(const uint8_t* prev,const uint8_t* cur,
                                          int w,int h,const std::vector<P>& pts,
                                          int priorDx,int priorDy,int radius);
    static float sharpness(const uint8_t* g,int w,int h);
    static bool coarseGlobalShift(const uint8_t* prev,const uint8_t* cur,
                                  int w,int h,
                                  float x1n,float y1n,float x2n,float y2n,
                                  int& dx,int& dy,float& quality);
};
