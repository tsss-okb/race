#!/usr/bin/env python3
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
s = path.read_text(encoding="utf-8")

def rep(old: str, new: str, count: int = 1):
    global s
    if s.count(old) < count:
        raise SystemExit(f"v2.6.4 patch pattern missing: {old[:120]!r}")
    s = s.replace(old, new, count)

rep("GT6 Visual Tracker v2.6.3 — STABLE 60 LOCK",
    "GT6 Visual Tracker v2.6.4 — GEOMETRIC LOCK")
rep("preventing edge/template drift on Realme GT6.",
    "preventing edge/template drift and false edge-lock on Realme GT6.")

rep("""    private var anchor: Template? = null
    private var contextAnchor: Template? = null
    private var adaptive: Template? = null
""", """    private var anchor: Template? = null
    private var contextAnchor: Template? = null
    private var adaptive: Template? = null
    private var supportAnchors: List<SupportAnchor> = emptyList()
""")

rep("""        anchor = null
        contextAnchor = null
        adaptive = null
        publish(reason)
""", """        anchor = null
        contextAnchor = null
        adaptive = null
        supportAnchors = emptyList()
        publish(reason)
""")

rep("""        val accepted = result != null && accept(
            result = result,
            anchorScore = anchorScore,
            contextScore = contextScore,
            current = r,
            frame = frame,
            predictedCx = predictedCx,
            predictedCy = predictedCy
        )
""", """        // v2.6.4: several independent patches must agree with the same translation.
        // One shiny/parallel edge may have excellent NCC, but it can no longer drag
        // the target box by itself.
        val supportCheck = if (result != null) {
            checkSupport(frame, result.best, r)
        } else {
            SupportCheck(score = 0f, votes = 0, total = supportAnchors.size)
        }

        val accepted = result != null && accept(
            result = result,
            anchorScore = anchorScore,
            contextScore = contextScore,
            supportCheck = supportCheck,
            current = r,
            frame = frame,
            predictedCx = predictedCx,
            predictedCy = predictedCy
        )
""")

rep("""            confidence = (
                0.28f + best.ncc * 0.58f + min(uniqueness, 0.60f) * 0.14f
            ).coerceIn(0.36f, 0.99f)
""", """            val supportConfidence = if (supportCheck.total > 0) supportCheck.score else best.ncc
            confidence = (
                0.24f +
                    best.ncc * 0.50f +
                    min(uniqueness, 0.60f) * 0.10f +
                    supportConfidence * 0.16f
            ).coerceIn(0.34f, 0.99f)
""")

rep("""                contextScore >= 0.72f &&
                uniqueness >= 0.24f &&
""", """                contextScore >= 0.72f &&
                (supportCheck.total == 0 || supportCheck.score >= 0.70f) &&
                uniqueness >= 0.24f &&
""")

accept_re = re.compile(r"""    private fun accept\(
.*?
    \}

    // -------------------------------------------------------------------------
    // Search""", re.S)
m = accept_re.search(s)
if not m:
    raise SystemExit("v2.6.4 accept block missing")
s = s[:m.start()] + """    private fun accept(
        result: SearchResult,
        anchorScore: Float,
        contextScore: Float,
        supportCheck: SupportCheck,
        current: RectF,
        frame: Gray,
        predictedCx: Float,
        predictedCy: Float
    ): Boolean {
        val best = result.best
        val nccFloor = when {
            lostFrames >= 12 -> 0.64f
            lostFrames >= 3 -> 0.62f
            else -> 0.64f
        }
        if (best.ncc < nccFloor) return false

        val cx = best.cx.toFloat() / frame.width
        val cy = best.cy.toFloat() / frame.height
        val jump = abs(cx - current.centerX()) + abs(cy - current.centerY())
        val predError = abs(cx - predictedCx) + abs(cy - predictedCy)

        val supportRequired = when {
            supportCheck.total >= 5 -> 3
            supportCheck.total >= 3 -> 2
            supportCheck.total >= 1 -> 1
            else -> 0
        }
        val supportOk = supportRequired == 0 ||
            (supportCheck.votes >= supportRequired && supportCheck.score >= 0.52f)

        if (lostFrames == 0) {
            if (!supportOk) return false
            if (predError > max(0.075f, current.width() * 1.10f) && best.ncc < 0.88f) return false
            if (anchorScore < 0.54f && best.ncc < 0.88f) return false
            if (contextScore < 0.48f && best.ncc < 0.90f) return false
            return true
        }

        if (jump > 0.12f) {
            if (!supportOk || supportCheck.score < 0.60f) return false
            if (anchorScore < 0.70f) return false
            if (contextScore < 0.56f) return false
            if (result.uniqueness < 0.12f) return false
            if (best.ncc < 0.74f) return false
        } else {
            if (!supportOk && best.ncc < 0.92f) return false
            if ((anchorScore < 0.56f || contextScore < 0.48f) && best.ncc < 0.80f) return false
        }

        return true
    }

    // -------------------------------------------------------------------------
    // Search""" + s[m.end():]

marker = "    private data class Template(\n"
idx = s.find(marker)
if idx < 0:
    raise SystemExit("v2.6.4 Template marker missing")

support_code = """    private data class SupportAnchor(
        val template: Template,
        val offsetXNorm: Float,
        val offsetYNorm: Float
    )

    private data class SupportCheck(
        val score: Float,
        val votes: Int,
        val total: Int
    )

    private fun buildSupportAnchors(frame: Gray, r: RectF): List<SupportAnchor> {
        val offsets = arrayOf(
            -0.18f to 0.00f,
             0.18f to 0.00f,
             0.00f to -0.18f,
             0.00f to 0.18f,
            -0.14f to -0.14f,
             0.14f to 0.14f
        )
        val result = ArrayList<SupportAnchor>(offsets.size)
        val subW = (r.width() * 0.48f).coerceAtLeast(0.028f)
        val subH = (r.height() * 0.48f).coerceAtLeast(0.034f)

        for ((fx, fy) in offsets) {
            val ox = fx * r.width()
            val oy = fy * r.height()
            val sub = makeBox(r.centerX() + ox, r.centerY() + oy, subW, subH)
            val t = buildTemplate(frame, sub) ?: continue
            result.add(SupportAnchor(t, ox, oy))
        }
        return result
    }

    private fun checkSupport(frame: Gray, best: Candidate, current: RectF): SupportCheck {
        val supports = supportAnchors
        val a = anchor
        if (supports.isEmpty() || a == null) return SupportCheck(1f, 0, 0)

        val targetScale = if (contextAnchor != null && best.template === contextAnchor) {
            (current.width() / a.widthNorm.coerceAtLeast(0.001f)).coerceIn(0.58f, 1.70f)
        } else {
            ((best.template.widthNorm * best.scale) / a.widthNorm.coerceAtLeast(0.001f))
                .coerceIn(0.58f, 1.70f)
        }

        var sum = 0f
        var votes = 0
        var valid = 0
        for (support in supports) {
            val sx = best.cx + (support.offsetXNorm * frame.width * targetScale).roundToInt()
            val sy = best.cy + (support.offsetYNorm * frame.height * targetScale).roundToInt()
            if (!fits(frame, support.template, sx, sy, targetScale)) continue

            val n = ncc01(frame, support.template, sx, sy, targetScale)
            sum += n
            valid++
            if (n >= 0.57f) votes++
        }

        if (valid == 0) return SupportCheck(0f, 0, supports.size)
        return SupportCheck(
            score = (sum / valid).coerceIn(0f, 1f),
            votes = votes,
            total = valid
        )
    }

"""
s = s[:idx] + support_code + s[idx:]

seed_re = re.compile(r"""    private fun seed\(frame: Gray, nx: Float, ny: Float\) \{
.*?
    \}

    private fun buildTemplate""", re.S)
m = seed_re.search(s)
if not m:
    raise SystemExit("v2.6.4 seed block missing")

s = s[:m.start()] + """    private fun seed(frame: Gray, nx: Float, ny: Float) {
        // Preserve the user's tap as the semantic target center.
        // The previous strongest-gradient snap could turn a whole object into an edge target.
        val boxW = 0.090f
        val boxH = 0.115f
        val initial = makeBox(nx, ny, boxW, boxH)
        val t = buildTemplate(frame, initial)
        box = initial

        if (t == null) {
            anchor = null
            contextAnchor = null
            adaptive = null
            supportAnchors = emptyList()
            state = "LOW TEXTURE"
            confidence = 0.12f
            nccScore = 0f
            uniqueness = 0f
            lostFrames = 0
            goodFrames = 0
            vx = 0f
            vy = 0f
            return
        }

        anchor = t
        contextAnchor = buildTemplate(frame, expandBox(initial, 1.70f, 1.60f))
        supportAnchors = buildSupportAnchors(frame, initial)
        adaptive = t
        state = "LOCK"
        confidence = 0.95f
        nccScore = 1f
        uniqueness = 1f
        lostFrames = 0
        goodFrames = 2
        framesSinceRefresh = 0
        vx = 0f
        vy = 0f
        ax = 0f
        ay = 0f
        lastDx = 0f
        lastDy = 0f
        lastGoodNs = System.nanoTime()
    }

    private fun buildTemplate""" + s[m.end():]

rep("""        val halfW = (r.width() * frame.width * 0.30f).roundToInt().coerceIn(6, 28)
        val halfH = (r.height() * frame.height * 0.30f).roundToInt().coerceIn(6, 28)
""", """        val halfW = (r.width() * frame.width * 0.44f).roundToInt().coerceIn(6, 36)
        val halfH = (r.height() * frame.height * 0.44f).roundToInt().coerceIn(6, 34)
""")
rep("""        val gridX = 9
        val gridY = 9
""", """        val gridX = 11
        val gridY = 11
""")

if s.count("{") != s.count("}"):
    raise SystemExit("v2.6.4 brace mismatch")
for token in ("supportAnchors", "SupportCheck", "val boxW = 0.090f", "val gridX = 11"):
    if token not in s:
        raise SystemExit(f"v2.6.4 token missing: {token}")

path.write_text(s, encoding="utf-8")
print("v2.6.4 geometric patch applied")
