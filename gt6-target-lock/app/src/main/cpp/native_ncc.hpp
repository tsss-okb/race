#pragma once
#include <cstdint>
#include <vector>

struct NativeNccMatch {
    bool valid = false;
    int bestX = 0;
    int bestY = 0;
    float bestScore = -2.f;
    float secondScore = -2.f;
    float bestScale = 1.f;
    float currentScore = -2.f;
    float anchorScore = -2.f;
};

class NativeNccMatcher {
public:
    void clear();

    void setAnchor(
        const float* samples, int count,
        int halfW, int halfH, int step,
        double sum, double sumSq,
        float widthNorm, float heightNorm,
        bool copyToCurrent
    );

    void setCurrent(
        const float* samples, int count,
        int halfW, int halfH, int step,
        double sum, double sumSq,
        float widthNorm, float heightNorm
    );

    void resetCurrentToAnchor();

    NativeNccMatch match(
        const uint8_t* gray, int w, int h,
        int predictedX, int predictedY,
        int radiusX, int radiusY,
        int coarseStep, bool wideScales
    ) const;

private:
    struct TemplateData {
        int halfW = 0;
        int halfH = 0;
        int step = 1;
        std::vector<float> samples;
        double sum = 0.0;
        double sumSq = 0.0;
        float widthNorm = 0.f;
        float heightNorm = 0.f;
        bool valid = false;
    };

    TemplateData anchor_;
    TemplateData current_;
    bool sameTemplates_ = true;

    static bool fitsScaled(
        int w, int h, int cx, int cy,
        const TemplateData& t, float scale
    );

    static double correlation(
        const uint8_t* gray, int w, int h,
        int cx, int cy,
        const TemplateData& t, float scale
    );

    static TemplateData makeTemplate(
        const float* samples, int count,
        int halfW, int halfH, int step,
        double sum, double sumSq,
        float widthNorm, float heightNorm
    );
};
