@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.app.videoeditor

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.io.IOException

/** Feathered rectangle/ellipse alpha mask shared by preview and Media3 export. */
class ClipMaskEffect(
    private val shape: ClipMaskShape, private val centerX: Float, private val centerY: Float,
    private val width: Float, private val height: Float, private val feather: Float, private val invert: Boolean
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ClipMaskShaderProgram(context, useHdr, shape, centerX, centerY, width, height, feather, invert)
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = shape == ClipMaskShape.None
}

private class ClipMaskShaderProgram(
    context: Context, useHdr: Boolean, shape: ClipMaskShape, centerX: Float, centerY: Float,
    width: Float, height: Float, feather: Float, invert: Boolean
) : BaseGlShaderProgram(useHdr, 1) {
    private val program: GlProgram
    init {
        try {
            program = GlProgram(context, R.raw.mask_vertex_shader_es2, R.raw.mask_fragment_shader_es2)
        } catch (error: IOException) { throw VideoFrameProcessingException(error)
        } catch (error: GlUtil.GlException) { throw VideoFrameProcessingException(error) }
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        val identity = GlUtil.create4x4IdentityMatrix()
        program.setFloatsUniform("uTransformationMatrix", identity)
        program.setFloatsUniform("uTexTransformationMatrix", identity)
        program.setFloatsUniform("uMaskCenter", floatArrayOf(centerX, centerY))
        program.setFloatsUniform("uMaskHalfSize", floatArrayOf(width * 0.5f, height * 0.5f))
        program.setFloatUniform("uMaskShape", if (shape == ClipMaskShape.Ellipse) 1f else 0f)
        program.setFloatUniform("uMaskFeather", feather)
        program.setFloatUniform("uMaskInvert", if (invert) 1f else 0f)
    }
    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)
    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (error: GlUtil.GlException) { throw VideoFrameProcessingException(error, presentationTimeUs) }
    }
    override fun release() {
        try { program.delete() } catch (_: GlUtil.GlException) { }
        super.release()
    }
}
