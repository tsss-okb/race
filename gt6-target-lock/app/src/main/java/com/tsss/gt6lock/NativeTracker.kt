package com.tsss.gt6lock

class NativeTracker {
    init { System.loadLibrary("gt6lock") }
    external fun nativeInit(y: ByteArray, w: Int, h: Int, cx: Float, cy: Float, bw: Float, bh: Float): Boolean
    external fun nativeProcess(y: ByteArray, w: Int, h: Int, dt: Double): FloatArray
    external fun nativeReset()
}
