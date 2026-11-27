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

enum class Ease(val label: String) { Linear("Linear"), Smooth("Smooth") }

/** A transform keyframe. t is 0..1 of the clip's (output) duration. x,y in -1..1 frame units. */
data class Keyframe(
    val t: Float,
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val ease: Ease = Ease.Smooth
)

data class AudioGainPoint(val timeMs: Long, val gain: Float)

data class AudioTrack(
    val id: Long,
    val uri: Uri,
    val name: String,
    val volume: Float = 1f,
    val offsetMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val loop: Boolean = false,
    /** Source-media end in milliseconds; Long.MAX_VALUE preserves legacy full-source behavior. */
    val trimEndMs: Long = Long.MAX_VALUE,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val gainPoints: List<AudioGainPoint> = emptyList(),
    val isVoiceover: Boolean = false,
    val autoDuck: Boolean = false
)

fun trimmedDurationMs(startMs: Long, endMs: Long): Long = (endMs - startMs).coerceAtLeast(0L)

fun normalizedAudioTrimEndMs(trimEndMs: Long?, trimStartMs: Long): Long =
    (trimEndMs ?: Long.MAX_VALUE).coerceAtLeast(trimStartMs + 1L)

fun audioFadeGainAt(elapsedMs: Long, durationMs: Long, fadeInMs: Long, fadeOutMs: Long): Float {
    if (durationMs <= 0L) return 0f
    val position = elapsedMs.coerceIn(0L, durationMs)
    val fadeInGain = if (fadeInMs > 0L) (position.toFloat() / fadeInMs).coerceIn(0f, 1f) else 1f
    val remaining = durationMs - position
    val fadeOutGain = if (fadeOutMs > 0L) (remaining.toFloat() / fadeOutMs).coerceIn(0f, 1f) else 1f
    return minOf(fadeInGain, fadeOutGain)
}

fun audioKeyframeGainAt(points: List<AudioGainPoint>, elapsedMs: Long): Float {
    val sorted = points.sortedBy { it.timeMs }
    if (sorted.isEmpty()) return 1f
    if (elapsedMs < sorted.first().timeMs) return 1f
    if (elapsedMs == sorted.first().timeMs) return sorted.first().gain.coerceIn(0f, 2f)
    if (elapsedMs >= sorted.last().timeMs) return sorted.last().gain.coerceIn(0f, 2f)
    val i = sorted.indexOfLast { it.timeMs <= elapsedMs }
    val a = sorted[i]
    val b = sorted[i + 1]
    val span = (b.timeMs - a.timeMs).coerceAtLeast(1L)
    val fraction = (elapsedMs - a.timeMs).toFloat() / span
    return (a.gain + (b.gain - a.gain) * fraction).coerceIn(0f, 2f)
}

fun shouldDeleteStaleExportFile(fileName: String, lastModifiedMs: Long, cutoffMs: Long): Boolean =
    fileName.startsWith("export_") && lastModifiedMs < cutoffMs

fun outputDurationMs(startMs: Long, endMs: Long, speed: Float): Long {
    val trimmed = trimmedDurationMs(startMs, endMs)
    if (speed <= 0f || speed.isNaN() || speed.isInfinite()) return 0L
    return (trimmed / speed).toLong()
}

fun interpKeyframes(kfs: List<Keyframe>, t: Float): Keyframe {
    if (kfs.isEmpty()) return Keyframe(t)
    if (t <= kfs.first().t) return kfs.first().copy(t = t)
    if (t >= kfs.last().t) return kfs.last().copy(t = t)
    val i = kfs.indexOfLast { it.t <= t }
    val a = kfs[i]
    val b = kfs[i + 1]
    var f = (t - a.t) / (b.t - a.t).coerceAtLeast(0.0001f)
    if (a.ease == Ease.Smooth) f = f * f * (3f - 2f * f)
    fun l(x: Float, y: Float) = x + (y - x) * f
    return Keyframe(t, l(a.x, b.x), l(a.y, b.y), l(a.scale, b.scale), l(a.rotation, b.rotation), l(a.opacity, b.opacity), a.ease)
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
    val anim: TextAnim = TextAnim.None,
    val rotation: Float = 0f,          // degrees, counter-clockwise
    val shadow: Boolean = false,
    val outline: Boolean = false,
    val align: Int = 1                 // 0 left, 1 centre, 2 right (matters for multi-line text)
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
    val layers: List<TextLayer> = emptyList(),
    val keyframes: List<Keyframe> = emptyList()
) {
    val trimmedMs: Long get() = trimmedDurationMs(startMs, endMs)
    val outputMs: Long get() = outputDurationMs(startMs, endMs, speed)
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
    put("kf", JSONArray().apply {
        keyframes.forEach { k ->
            put(JSONObject().apply {
                put("t", k.t.toDouble()); put("x", k.x.toDouble()); put("y", k.y.toDouble())
                put("s", k.scale.toDouble()); put("r", k.rotation.toDouble()); put("o", k.opacity.toDouble())
                put("e", k.ease.name)
            })
        }
    })
    put("layers", JSONArray().apply {
        layers.forEach { l ->
            put(JSONObject().apply {
                put("id", l.id); put("text", l.text); put("color", l.color); put("scale", l.scale.toDouble())
                put("x", l.x.toDouble()); put("y", l.y.toDouble()); put("bold", l.bold)
                put("bg", l.background); put("font", l.font)
                put("rot", l.rotation.toDouble()); put("shadow", l.shadow); put("outline", l.outline); put("align", l.align)
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
            rotation = l.optDouble("rot", 0.0).toFloat(), shadow = l.optBoolean("shadow", false),
            outline = l.optBoolean("outline", false), align = l.optInt("align", 1),
            startFrac = l.optDouble("sf", 0.0).toFloat(), endFrac = l.optDouble("ef", 1.0).toFloat(),
            anim = runCatching { TextAnim.valueOf(l.getString("anim")) }.getOrDefault(TextAnim.None)
        )
    }
    val kfs = mutableListOf<Keyframe>()
    val kArr = o.optJSONArray("kf")
    if (kArr != null) for (i in 0 until kArr.length()) {
        val k = kArr.getJSONObject(i)
        kfs += Keyframe(
            t = k.optDouble("t", 0.0).toFloat(), x = k.optDouble("x", 0.0).toFloat(), y = k.optDouble("y", 0.0).toFloat(),
            scale = k.optDouble("s", 1.0).toFloat(), rotation = k.optDouble("r", 0.0).toFloat(),
            opacity = k.optDouble("o", 1.0).toFloat(),
            ease = runCatching { Ease.valueOf(k.getString("e")) }.getOrDefault(Ease.Smooth)
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
        fadeInMs = o.optLong("fin", 0L), fadeOutMs = o.optLong("fout", 0L), layers = layers,
        keyframes = kfs.sortedBy { it.t }
    )
}

fun AudioTrack.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("uri", uri.toString()); put("name", name); put("vol", volume.toDouble())
    put("off", offsetMs); put("trim", trimStartMs); put("trimEnd", trimEndMs); put("loop", loop); put("fadeIn", fadeInMs); put("fadeOut", fadeOutMs); put("gainPoints", JSONArray().apply { gainPoints.forEach { put(JSONObject().put("time", it.timeMs).put("gain", it.gain.toDouble())) } }); put("voiceover", isVoiceover); put("autoDuck", autoDuck)
}

fun audioFromJson(o: JSONObject): AudioTrack = AudioTrack(
    id = o.getLong("id"), uri = Uri.parse(o.getString("uri")), name = o.optString("name", "Audio"),
    volume = o.optDouble("vol", 1.0).toFloat(), offsetMs = o.optLong("off", 0L),
    trimStartMs = o.optLong("trim", 0L), loop = o.optBoolean("loop", false),
    trimEndMs = normalizedAudioTrimEndMs(if (o.has("trimEnd")) o.optLong("trimEnd") else null, o.optLong("trim", 0L)),
    fadeInMs = o.optLong("fadeIn", 0L).coerceAtLeast(0L),
    fadeOutMs = o.optLong("fadeOut", 0L).coerceAtLeast(0L),
    gainPoints = (o.optJSONArray("gainPoints")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { AudioGainPoint(it.optLong("time", 0L).coerceAtLeast(0L), it.optDouble("gain", 1.0).toFloat().coerceIn(0f, 2f)) } } } ?: emptyList()),
    isVoiceover = o.optBoolean("voiceover", false),
    autoDuck = o.optBoolean("autoDuck", false)
)
