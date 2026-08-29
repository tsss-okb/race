package ru.racelab.phone.video

import android.content.Context

enum class VideoQualityMode { FHD, UHD }
enum class VideoCodecMode { AUTO, H264, H265 }

data class VideoSettings(
    val quality: VideoQualityMode = VideoQualityMode.FHD,
    val fps: Int = 30,
    val codec: VideoCodecMode = VideoCodecMode.AUTO,
    val bitrateMbps: Int = 24,
    val stabilization: Boolean = true,
    val audio: Boolean = true,
    val autoRecord: Boolean = true,
    val perLapClips: Boolean = true,
    val burnHud: Boolean = true
)

object VideoSettingsRepository {
    private const val PREFS = "racelab_video"

    fun load(context: Context): VideoSettings {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return VideoSettings(
            quality = runCatching { VideoQualityMode.valueOf(p.getString("quality", VideoQualityMode.FHD.name)!!) }.getOrDefault(VideoQualityMode.FHD),
            fps = p.getInt("fps", 30).let { if (it >= 60) 60 else 30 },
            codec = runCatching { VideoCodecMode.valueOf(p.getString("codec", VideoCodecMode.AUTO.name)!!) }.getOrDefault(VideoCodecMode.AUTO),
            bitrateMbps = p.getInt("bitrate", 24).coerceIn(8, 100),
            stabilization = p.getBoolean("stabilization", true),
            audio = p.getBoolean("audio", true),
            autoRecord = p.getBoolean("autoRecord", true),
            perLapClips = p.getBoolean("perLapClips", true),
            burnHud = p.getBoolean("burnHud", true)
        )
    }

    fun save(context: Context, s: VideoSettings) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("quality", s.quality.name)
            .putInt("fps", s.fps)
            .putString("codec", s.codec.name)
            .putInt("bitrate", s.bitrateMbps)
            .putBoolean("stabilization", s.stabilization)
            .putBoolean("audio", s.audio)
            .putBoolean("autoRecord", s.autoRecord)
            .putBoolean("perLapClips", s.perLapClips)
            .putBoolean("burnHud", s.burnHud)
            .apply()
    }
}
