package com.frameforge.ai

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * App-owned frame interpolation prototype.
 * It creates two synthetic source frames and blends them on the GPU.
 * This is genuine image-space frame blending, not motion-compensated/AI interpolation.
 */
class FrameInterpolationView(context: Context) : GLSurfaceView(context) {
    private val interpolationRenderer = InterpolationRenderer()

    init {
        setEGLContextClientVersion(2)
        setRenderer(interpolationRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }

    /** Update the in-between position from Compose without touching GL off its render thread. */
    fun setInterpolationPhase(phase: Float) {
        val safePhase = phase.coerceIn(0f, 1f)
        queueEvent { interpolationRenderer.setPhase(safePhase) }
    }
}

private class InterpolationRenderer : GLSurfaceView.Renderer {
    private var program = 0
    private var positionLoc = -1
    private var uvLoc = -1
    private var textureALoc = -1
    private var textureBLoc = -1
    private var blendLoc = -1
    private var motionLoc = -1
    @Volatile private var phase = 0.5f
    private val textures = IntArray(2)
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f
            )); position(0)
        }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.02f, 0.04f, 0.08f, 1f)
        val vertex = """
            attribute vec2 aPosition;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() { vUv = aUv; gl_Position = vec4(aPosition, 0.0, 1.0); }
        """.trimIndent()
        val fragment = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uFrameA;
            uniform sampler2D uFrameB;
            uniform float uBlend;
            uniform vec2 uMotion;
            void main() {
                // The demo object has a known horizontal translation. Warp both
                // source samples toward the requested intermediate position.
                // This is motion-compensated interpolation for this controlled
                // synthetic sequence, not general-purpose optical flow or AI.
                vec2 uvA = clamp(vUv - uMotion * uBlend, 0.001, 0.999);
                vec2 uvB = clamp(vUv + uMotion * (1.0 - uBlend), 0.001, 0.999);
                vec4 a = texture2D(uFrameA, uvA);
                vec4 b = texture2D(uFrameB, uvB);
                gl_FragColor = mix(a, b, uBlend);
            }
        """.trimIndent()
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glBindAttribLocation(program, 0, "aPosition")
        GLES20.glBindAttribLocation(program, 1, "aUv")
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val message = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            program = 0
            throw IllegalStateException("Interpolation shader link failed: $message")
        }
        positionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        uvLoc = GLES20.glGetAttribLocation(program, "aUv")
        textureALoc = GLES20.glGetUniformLocation(program, "uFrameA")
        textureBLoc = GLES20.glGetUniformLocation(program, "uFrameB")
        blendLoc = GLES20.glGetUniformLocation(program, "uBlend")
        motionLoc = GLES20.glGetUniformLocation(program, "uMotion")
        GLES20.glGenTextures(2, textures, 0)
        uploadTexture(textures[0], makeSourceFrame(0))
        uploadTexture(textures[1], makeSourceFrame(1))
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val message = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Interpolation shader compile failed: $message")
        }
        return shader
    }

    fun setPhase(value: Float) {
        phase = value.coerceIn(0f, 1f)
    }

    private fun makeSourceFrame(which: Int): ByteBuffer {
        val width = 256
        val height = 144
        val data = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        val centerX = if (which == 0) 76 else 180
        val centerY = 72
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - centerX
                val dy = y - centerY
                val inside = dx * dx + dy * dy <= 24 * 24
                val r = if (inside) 40 else 8 + (x * 12 / width)
                val g = if (inside) 190 else 20 + (y * 18 / height)
                val b = if (inside) 255 else 38 + (x * 20 / width)
                data.put(r.toByte()).put(g.toByte()).put(b.toByte()).put(255.toByte())
            }
        }
        data.position(0)
        return data
    }

    private fun uploadTexture(id: Int, pixels: ByteBuffer) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 256, 144, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (program == 0) return
        // Motion vector is derived from the known synthetic object's displacement:
        // (180 - 76) / 256 texture-width = 0.40625.
        val blend = phase
        GLES20.glUseProgram(program)
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionLoc)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 16, vertices.position(2))
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glUniform1i(textureALoc, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1])
        GLES20.glUniform1i(textureBLoc, 1)
        GLES20.glUniform1f(blendLoc, blend)
        GLES20.glUniform2f(motionLoc, 104f / 256f, 0f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
    }
}
