package com.app.videoeditor

import android.net.Uri
import org.json.JSONObject

/**
 * A secondary visual layer placed above the existing primary clip sequence.
 *
 * Times on the source are expressed in milliseconds. timelineStartMs is the layer's
 * start on the project timeline. This model is intentionally independent of Clip so
 * existing primary-track projects and editing behaviour remain unchanged.
 */
data class OverlayClip(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val startMs: Long = 0L,
    val endMs: Long = durationMs,
    val timelineStartMs: Long = 0L,
    val isImage: Boolean = false,
    val speed: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 0.35f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val volume: Float = 1f,
    val muted: Boolean = true,
    val layerOrder: Int = 0
) {
    val trimmedMs: Long get() = trimmedDurationMs(startMs, endMs)
    val outputMs: Long get() = outputDurationMs(startMs, endMs, speed)
    val timelineEndMs: Long get() = timelineStartMs + outputMs

    fun isVisibleAt(projectTimeMs: Long): Boolean =
        projectTimeMs >= timelineStartMs && projectTimeMs < timelineEndMs
}

fun OverlayClip.isValidOverlayClip(): Boolean =
    id > 0L &&
        durationMs > 0L &&
        width > 0 && height > 0 &&
        startMs >= 0L && endMs > startMs && endMs <= durationMs &&
        timelineStartMs >= 0L &&
        speed > 0f && speed.isFinite() &&
        scale > 0f && scale.isFinite() &&
        opacity in 0f..1f &&
        volume in 0f..2f &&
        x.isFinite() && y.isFinite() && rotation.isFinite()

fun OverlayClip.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("uri", uri.toString())
    put("name", name)
    put("dur", durationMs)
    put("w", width)
    put("h", height)
    put("start", startMs)
    put("end", endMs)
    put("timelineStart", timelineStartMs)
    put("img", isImage)
    put("speed", speed.toDouble())
    put("x", x.toDouble())
    put("y", y.toDouble())
    put("scale", scale.toDouble())
    put("rotation", rotation.toDouble())
    put("opacity", opacity.toDouble())
    put("volume", volume.toDouble())
    put("muted", muted)
    put("order", layerOrder)
}

fun overlayClipFromJson(o: JSONObject): OverlayClip? = runCatching {
    val duration = o.getLong("dur")
    val clip = OverlayClip(
        id = o.getLong("id"),
        uri = Uri.parse(o.getString("uri")),
        name = o.optString("name", "Overlay"),
        durationMs = duration,
        width = o.getInt("w"),
        height = o.getInt("h"),
        startMs = o.optLong("start", 0L),
        endMs = o.optLong("end", duration),
        timelineStartMs = o.optLong("timelineStart", 0L),
        isImage = o.optBoolean("img", false),
        speed = o.optDouble("speed", 1.0).toFloat(),
        x = o.optDouble("x", 0.0).toFloat(),
        y = o.optDouble("y", 0.0).toFloat(),
        scale = o.optDouble("scale", 0.35).toFloat(),
        rotation = o.optDouble("rotation", 0.0).toFloat(),
        opacity = o.optDouble("opacity", 1.0).toFloat(),
        volume = o.optDouble("volume", 1.0).toFloat(),
        muted = o.optBoolean("muted", true),
        layerOrder = o.optInt("order", 0)
    )
    clip.takeIf { it.isValidOverlayClip() }
}.getOrNull()
