package com.app.videoeditor

import android.net.Uri
import org.json.JSONObject

enum class Filter(val label: String) {
    None("None"), Gray("B&W"), Sepia("Sepia"), Invert("Invert"), Warm("Warm"), Cool("Cool"), Vivid("Vivid")
}

enum class Aspect(val label: String, val ratio: Float?) {
    Original("Original", null), R16x9("16:9", 16f / 9f), R9x16("9:16", 9f / 16f),
    R1x1("1:1", 1f), R4x5("4:5", 4f / 5f), R21x9("21:9", 21f / 9f)
}

data class Clip(
    val id: Long,
    val uri: Uri,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val startMs: Long = 0L,
    val endMs: Long = durationMs,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val zoom: Float = 1f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val filter: Filter = Filter.None,
    val text: String = "",
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val textScale: Float = 1f,
    val textY: Float = 0.7f
) {
    val trimmedMs: Long get() = endMs - startMs
    val outputMs: Long get() = (trimmedMs / speed).toLong()
}

sealed class ExportState {
    object Idle : ExportState()
    data class Running(val progress: Float) : ExportState()
    data class Done(val uri: Uri) : ExportState()
    data class Failed(val message: String) : ExportState()
}

fun Clip.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("uri", uri.toString()); put("dur", durationMs); put("w", width); put("h", height)
    put("start", startMs); put("end", endMs); put("speed", speed.toDouble()); put("vol", volume.toDouble())
    put("muted", muted); put("rot", rotation); put("fh", flipH); put("fv", flipV); put("zoom", zoom.toDouble())
    put("br", brightness.toDouble()); put("co", contrast.toDouble()); put("sa", saturation.toDouble())
    put("wa", warmth.toDouble()); put("filter", filter.name)
    put("text", text); put("tc", textColor); put("ts", textScale.toDouble()); put("ty", textY.toDouble())
}

fun clipFromJson(o: JSONObject): Clip = Clip(
    id = o.getLong("id"), uri = Uri.parse(o.getString("uri")), durationMs = o.getLong("dur"),
    width = o.getInt("w"), height = o.getInt("h"), startMs = o.getLong("start"), endMs = o.getLong("end"),
    speed = o.getDouble("speed").toFloat(), volume = o.getDouble("vol").toFloat(), muted = o.getBoolean("muted"),
    rotation = o.getInt("rot"), flipH = o.getBoolean("fh"), flipV = o.getBoolean("fv"),
    zoom = o.getDouble("zoom").toFloat(), brightness = o.getDouble("br").toFloat(),
    contrast = o.getDouble("co").toFloat(), saturation = o.getDouble("sa").toFloat(),
    warmth = o.getDouble("wa").toFloat(),
    filter = runCatching { Filter.valueOf(o.getString("filter")) }.getOrDefault(Filter.None),
    text = o.getString("text"), textColor = o.getInt("tc"),
    textScale = o.getDouble("ts").toFloat(), textY = o.getDouble("ty").toFloat()
)
