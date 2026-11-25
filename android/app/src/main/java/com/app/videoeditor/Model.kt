package com.app.videoeditor

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

enum class Filter(val label: String) {
    None("None"), Gray("B&W"), Noir("Noir"), Sepia("Sepia"), Vintage("Vintage"), Cinema("Cinema"),
    Warm("Warm"), Cool("Cool"), Vivid("Vivid"), Fade("Faded"), Pop("Pop"), Invert("Invert")
}

enum class Anim(val label: String) {
    None("None"), ZoomIn("Zoom in"), ZoomOut("Zoom out"), PanLeft("Pan ←"), PanRight("Pan →"), Pulse("Pulse")
}

enum class TextAnim(val label: String) {
    None("None"), Fade("Fade in"), Pop("Pop"), SlideUp("Slide up")
}

enum class Aspect(val label: String, val ratio: Float?) {
    Original("Original", null), R9x16("9:16 Reels", 9f / 16f), R16x9("16:9 YouTube", 16f / 9f),
    R1x1("1:1", 1f), R4x5("4:5 Insta", 4f / 5f), R21x9("21:9", 21f / 9f)
}

val FONT_NAMES = listOf("sans-serif", "serif", "monospace", "cursive", "casual", "sans-serif-condensed")
val FONT_LABELS = listOf("Clean", "Serif", "Mono", "Script", "Casual", "Narrow")

data class TextLayer(
    val id: Long,
    val text: String = "Your text",
    val color: Int = 0xFFFFFFFF.toInt(),
    val scale: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0.6f,
    val bold: Boolean = true,
    val background: Boolean = false,
    val font: Int = 0,
    val startFrac: Float = 0f,
    val endFrac: Float = 1f,
    val anim: TextAnim = TextAnim.None
)

data class ProjectInfo(val id: String, val name: String, val updated: Long, val clipCount: Int, val durationMs: Long)

data class Clip(
    val id: Long,
    val uri: Uri,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val startMs: Long = 0L,
    val endMs: Long = durationMs,
    val isImage: Boolean = false,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val zoom: Float = 1f,
    val cropL: Float = 0f,
    val cropR: Float = 0f,
    val cropT: Float = 0f,
    val cropB: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val hue: Float = 0f,
    val filter: Filter = Filter.None,
    val anim: Anim = Anim.None,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val layers: List<TextLayer> = emptyList()
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
    put("start", startMs); put("end", endMs); put("img", isImage)
    put("speed", speed.toDouble()); put("vol", volume.toDouble()); put("muted", muted)
    put("rot", rotation); put("fh", flipH); put("fv", flipV); put("zoom", zoom.toDouble())
    put("cl", cropL.toDouble()); put("cr", cropR.toDouble()); put("ct", cropT.toDouble()); put("cb", cropB.toDouble())
    put("br", brightness.toDouble()); put("co", contrast.toDouble()); put("sa", saturation.toDouble())
    put("wa", warmth.toDouble()); put("hue", hue.toDouble())
    put("filter", filter.name); put("anim", anim.name); put("fin", fadeInMs); put("fout", fadeOutMs)
    put("layers", JSONArray().apply {
        layers.forEach { l ->
            put(JSONObject().apply {
                put("id", l.id); put("text", l.text); put("color", l.color); put("scale", l.scale.toDouble())
                put("x", l.x.toDouble()); put("y", l.y.toDouble()); put("bold", l.bold)
                put("bg", l.background); put("font", l.font)
                put("sf", l.startFrac.toDouble()); put("ef", l.endFrac.toDouble()); put("anim", l.anim.name)
            })
        }
    })
}

fun clipFromJson(o: JSONObject): Clip {
    val layers = mutableListOf<TextLayer>()
    val arr = o.optJSONArray("layers")
    if (arr != null) for (i in 0 until arr.length()) {
        val l = arr.getJSONObject(i)
        layers += TextLayer(
            id = l.getLong("id"), text = l.optString("text", ""), color = l.optInt("color", -1),
            scale = l.optDouble("scale", 1.0).toFloat(), x = l.optDouble("x", 0.0).toFloat(),
            y = l.optDouble("y", 0.6).toFloat(), bold = l.optBoolean("bold", true),
            background = l.optBoolean("bg", false), font = l.optInt("font", 0),
            startFrac = l.optDouble("sf", 0.0).toFloat(), endFrac = l.optDouble("ef", 1.0).toFloat(),
            anim = runCatching { TextAnim.valueOf(l.getString("anim")) }.getOrDefault(TextAnim.None)
        )
    }
    return Clip(
        id = o.getLong("id"), uri = Uri.parse(o.getString("uri")), durationMs = o.getLong("dur"),
        width = o.getInt("w"), height = o.getInt("h"), startMs = o.getLong("start"), endMs = o.getLong("end"),
        isImage = o.optBoolean("img", false),
        speed = o.optDouble("speed", 1.0).toFloat(), volume = o.optDouble("vol", 1.0).toFloat(),
        muted = o.optBoolean("muted", false), rotation = o.optInt("rot", 0),
        flipH = o.optBoolean("fh", false), flipV = o.optBoolean("fv", false),
        zoom = o.optDouble("zoom", 1.0).toFloat(),
        cropL = o.optDouble("cl", 0.0).toFloat(), cropR = o.optDouble("cr", 0.0).toFloat(),
        cropT = o.optDouble("ct", 0.0).toFloat(), cropB = o.optDouble("cb", 0.0).toFloat(),
        brightness = o.optDouble("br", 0.0).toFloat(), contrast = o.optDouble("co", 0.0).toFloat(),
        saturation = o.optDouble("sa", 0.0).toFloat(), warmth = o.optDouble("wa", 0.0).toFloat(),
        hue = o.optDouble("hue", 0.0).toFloat(),
        filter = runCatching { Filter.valueOf(o.getString("filter")) }.getOrDefault(Filter.None),
        anim = runCatching { Anim.valueOf(o.getString("anim")) }.getOrDefault(Anim.None),
        fadeInMs = o.optLong("fin", 0L), fadeOutMs = o.optLong("fout", 0L), layers = layers
    )
}
