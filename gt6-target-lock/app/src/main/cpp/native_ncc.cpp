#include "native_ncc.hpp"
#include <algorithm>
#include <cmath>

void NativeNccMatcher::clear() {
    anchor_ = TemplateData{};
    current_ = TemplateData{};
    sameTemplates_ = true;
}

NativeNccMatcher::TemplateData NativeNccMatcher::makeTemplate(
    const float* samples, int count,
    int halfW, int halfH, int step,
    double sum, double sumSq,
    float widthNorm, float heightNorm
) {
    TemplateData t;
    t.halfW = halfW;
    t.halfH = halfH;
    t.step = std::max(1, step);
    t.samples.assign(samples, samples + std::max(0, count));
    t.sum = sum;
    t.sumSq = sumSq;
    t.widthNorm = widthNorm;
    t.heightNorm = heightNorm;
    t.valid = count > 0 && halfW >= 1 && halfH >= 1;
    return t;
}

void NativeNccMatcher::setAnchor(
    const float* samples, int count,
    int halfW, int halfH, int step,
    double sum, double sumSq,
    float widthNorm, float heightNorm,
    bool copyToCurrent
) {
    anchor_ = makeTemplate(
        samples, count, halfW, halfH, step,
        sum, sumSq, widthNorm, heightNorm
    );
    if (copyToCurrent) {
        current_ = anchor_;
        sameTemplates_ = true;
    }
}

void NativeNccMatcher::setCurrent(
    const float* samples, int count,
    int halfW, int halfH, int step,
    double sum, double sumSq,
    float widthNorm, float heightNorm
) {
    current_ = makeTemplate(
        samples, count, halfW, halfH, step,
        sum, sumSq, widthNorm, heightNorm
    );
    sameTemplates_ = false;
}

bool NativeNccMatcher::fitsScaled(
    int w, int h, int cx, int cy,
    const TemplateData& t, float scale
) {
    const int hw = static_cast<int>(t.halfW * scale) + 2;
    const int hh = static_cast<int>(t.halfH * scale) + 2;
    return cx - hw >= 0 && cy - hh >= 0 &&
           cx + hw < w && cy + hh < h;
}

double NativeNccMatcher::correlation(
    const uint8_t* gray, int w, int h,
    int cx, int cy,
    const TemplateData& t, float scale
) {
    if (!t.valid || !fitsScaled(w, h, cx, cy, t, scale)) return -2.0;

    double sumC = 0.0;
    double sumSqC = 0.0;
    double dot = 0.0;
    int i = 0;

    for (int ty = -t.halfH; ty <= t.halfH; ty += t.step) {
        for (int tx = -t.halfW; tx <= t.halfW && i < static_cast<int>(t.samples.size()); tx += t.step) {
            const int sx = cx + static_cast<int>(tx * scale);
            const int sy = cy + static_cast<int>(ty * scale);
            const double c = static_cast<double>(gray[sy * w + sx]);
            const double tv = static_cast<double>(t.samples[i++]);
            sumC += c;
            sumSqC += c * c;
            dot += tv * c;
        }
    }

    const double n = std::max(1.0, static_cast<double>(i));
    const double cov = dot - t.sum * sumC / n;
    const double varT = t.sumSq - t.sum * t.sum / n;
    const double varC = sumSqC - sumC * sumC / n;
    const double denom = std::sqrt(std::max(1e-9, varT * varC));
    return std::clamp(cov / denom, -1.0, 1.0);
}

NativeNccMatch NativeNccMatcher::match(
    const uint8_t* gray, int w, int h,
    int predictedX, int predictedY,
    int radiusX, int radiusY,
    int coarseStep, bool wideScales
) const {
    NativeNccMatch out;
    if (!gray || !anchor_.valid || !current_.valid || w < 32 || h < 24) {
        return out;
    }

    const float narrow[3] = {0.94f, 1.00f, 1.06f};
    const float wide[3] = {0.88f, 1.00f, 1.12f};
    const float* scales = wideScales ? wide : narrow;

    double bestScore = -2.0;
    double secondScore = -2.0;
    int bestX = predictedX;
    int bestY = predictedY;
    float bestScale = 1.f;

    const int hwBase = std::max(anchor_.halfW, current_.halfW);
    const int hhBase = std::max(anchor_.halfH, current_.halfH);
    const int step = std::max(1, coarseStep);

    for (int si = 0; si < 3; ++si) {
        const float scale = scales[si];
        const int scaledHw = std::max(3, static_cast<int>(hwBase * scale));
        const int scaledHh = std::max(3, static_cast<int>(hhBase * scale));

        const int xStart = std::max(scaledHw + 2, predictedX - radiusX);
        const int xEnd = std::min(w - scaledHw - 3, predictedX + radiusX);
        const int yStart = std::max(scaledHh + 2, predictedY - radiusY);
        const int yEnd = std::min(h - scaledHh - 3, predictedY + radiusY);
        if (xStart >= xEnd || yStart >= yEnd) continue;

        for (int y = yStart; y <= yEnd; y += step) {
            for (int x = xStart; x <= xEnd; x += step) {
                const double curCorr = correlation(gray, w, h, x, y, current_, scale);
                const double anchorCorr = sameTemplates_
                    ? curCorr
                    : correlation(gray, w, h, x, y, anchor_, scale);

                const double fused = std::max(
                    0.64 * curCorr + 0.36 * anchorCorr,
                    0.90 * curCorr - 0.03
                );

                if (fused > bestScore) {
                    secondScore = bestScore;
                    bestScore = fused;
                    bestX = x;
                    bestY = y;
                    bestScale = scale;
                } else if (
                    fused > secondScore &&
                    std::abs(x - bestX) + std::abs(y - bestY) > 5
                ) {
                    secondScore = fused;
                }
            }
        }
    }

    if (bestScore <= -1.5) return out;

    for (int dy = -2; dy <= 2; ++dy) {
        for (int dx = -2; dx <= 2; ++dx) {
            const int x = bestX + dx;
            const int y = bestY + dy;
            if (!fitsScaled(w, h, x, y, current_, bestScale)) continue;

            const double curCorr = correlation(gray, w, h, x, y, current_, bestScale);
            const double anchorCorr = sameTemplates_
                ? curCorr
                : correlation(gray, w, h, x, y, anchor_, bestScale);

            const double fused = std::max(
                0.64 * curCorr + 0.36 * anchorCorr,
                0.90 * curCorr - 0.03
            );

            if (fused > bestScore) {
                bestScore = fused;
                bestX = x;
                bestY = y;
            }
        }
    }

    out.valid = true;
    out.bestX = bestX;
    out.bestY = bestY;
    out.bestScore = static_cast<float>(bestScore);
    out.secondScore = static_cast<float>(secondScore);
    out.bestScale = bestScale;
    const double finalCurrent = correlation(
        gray, w, h, bestX, bestY, current_, bestScale
    );
    const double finalAnchor = sameTemplates_
        ? finalCurrent
        : correlation(gray, w, h, bestX, bestY, anchor_, bestScale);
    out.currentScore = static_cast<float>(finalCurrent);
    out.anchorScore = static_cast<float>(finalAnchor);
    return out;
}
