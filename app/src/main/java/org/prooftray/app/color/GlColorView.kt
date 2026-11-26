package org.prooftray.app.color

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import org.prooftray.app.domain.LutPreviewData

/** On-demand GLES 3 preview. The full-resolution export uses the equivalent C++ color pipeline. */
class GlColorView(context: Context, onError: (String) -> Unit) : GLSurfaceView(context) {
    private data class Frame(val bitmap: Bitmap, val lut: LutPreviewData?)
    private val pending = AtomicReference<Frame?>(null)
    private val renderer = ColorRenderer { message -> post { onError(message) } }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun update(bitmap: Bitmap, lut: LutPreviewData?) {
        pending.set(Frame(bitmap, lut))
        requestRender()
    }

    /** Stop the GL worker before its source bitmap lease is released by the editor. */
    fun releasePreview() {
        onPause()
        pending.set(null)
        renderer.clearImageReference()
    }

    private inner class ColorRenderer(private val error: (String) -> Unit) : Renderer {
        private var program = 0
        private var failed = false
        private var width = 1
        private var height = 1
        private var image: Bitmap? = null
        private var lutBuffer: FloatBuffer? = null
        fun clearImageReference() { image = null; lutBuffer = null }
        private var lutId: String? = null
        private val textures = IntArray(2)
        private val vertexArray = IntArray(1)
        private val vertexBuffer = IntArray(1)
        private val vertices = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer()

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            failed = false
            image = null
            lutId = null
            try {
                val vertex = shader(GLES30.GL_VERTEX_SHADER, VERTEX)
                val fragment = shader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT)
                program = GLES30.glCreateProgram()
                GLES30.glAttachShader(program, vertex); GLES30.glAttachShader(program, fragment); GLES30.glLinkProgram(program)
                GLES30.glDeleteShader(vertex); GLES30.glDeleteShader(fragment)
                val status = IntArray(1)
                GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
                check(status[0] == GLES30.GL_TRUE) { GLES30.glGetProgramInfoLog(program) }
                GLES30.glGenVertexArrays(1, vertexArray, 0)
                GLES30.glBindVertexArray(vertexArray[0])
                GLES30.glGenBuffers(1, vertexBuffer, 0)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer[0])
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 64, null, GLES30.GL_DYNAMIC_DRAW)
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
                GLES30.glEnableVertexAttribArray(1)
                GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
                GLES30.glGenTextures(2, textures, 0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textures[1])
                configureTexture(GLES30.GL_TEXTURE_3D, GLES30.GL_NEAREST)
                val identity = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f,
                    0f, 0f, 1f, 1f, 0f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)
                uploadLut(2, identity)
                GLES30.glDisable(GLES30.GL_BLEND)
                GLES30.glClearColor(0.12f, 0.12f, 0.12f, 1f)
            } catch (failure: Exception) { fail(failure) }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            this.width = width.coerceAtLeast(1); this.height = height.coerceAtLeast(1)
            GLES30.glViewport(0, 0, this.width, this.height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            if (failed || program == 0) return
            val frame = pending.get() ?: return
            try {
                GLES30.glUseProgram(program)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[0])
                if (image !== frame.bitmap) {
                    configureTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR)
                    GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, frame.bitmap, 0)
                    image = frame.bitmap
                }
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textures[1])
                val lut = frame.lut
                if (lut != null && lut.id != lutId) {
                    uploadLut(lut.size, lut.values)
                    lutId = lut.id
                }
                GLES30.glUniform1i(location("imageTexture"), 0)
                GLES30.glUniform1i(location("lutTexture"), 1)
                GLES30.glUniform1i(location("hasLut"), if (lut == null) 0 else 1)
                GLES30.glUniform1i(location("lutSize"), lut?.size ?: 2)
                GLES30.glUniform1i(location("premultiplied"), if (frame.bitmap.isPremultiplied) 1 else 0)
                val aspect = frame.bitmap.width.toFloat() / frame.bitmap.height
                val viewport = width.toFloat() / height
                val x = minOf(1f, aspect / viewport)
                val y = minOf(1f, viewport / aspect)
                vertices.clear()
                vertices.put(floatArrayOf(-x, -y, 0f, 1f, x, -y, 1f, 1f, -x, y, 0f, 0f, x, y, 1f, 0f)).position(0)
                GLES30.glBindVertexArray(vertexArray[0])
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer[0])
                GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, 64, vertices)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "GPU preview returned an OpenGL error" }
            } catch (failure: Exception) { fail(failure) }
        }

        private fun fail(failure: Exception) { if (!failed) { failed = true; error(failure.message ?: "实时预览不可用") } }
        private fun location(name: String): Int = GLES30.glGetUniformLocation(program, name)
        private fun configureTexture(target: Int, filter: Int) {
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, filter)
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, filter)
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            if (target == GLES30.GL_TEXTURE_3D) GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        }
        private fun uploadLut(size: Int, values: FloatArray) {
            val buffer = lutBuffer?.takeIf { it.capacity() >= values.size } ?: ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().also { lutBuffer = it }
            buffer.clear()
            buffer.put(values)
            buffer.flip()
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB32F, size, size, size, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, buffer)
        }
        private fun shader(type: Int, source: String): Int {
            val id = GLES30.glCreateShader(type)
            GLES30.glShaderSource(id, source); GLES30.glCompileShader(id)
            val compiled = IntArray(1)
            GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, compiled, 0)
            check(compiled[0] == GLES30.GL_TRUE) { GLES30.glGetShaderInfoLog(id) }
            return id
        }
    }

    private companion object {
        val VERTEX = """#version 300 es
            layout(location=0) in vec2 position;
            layout(location=1) in vec2 textureCoordinate;
            out vec2 uv;
            void main() { gl_Position = vec4(position, 0.0, 1.0); uv = textureCoordinate; }
        """.trimIndent()
        val FRAGMENT = """#version 300 es
            precision highp float;
            precision highp sampler3D;
            in vec2 uv;
            uniform sampler2D imageTexture;
            uniform sampler3D lutTexture;
            uniform int hasLut, lutSize, premultiplied;
            out vec4 result;
            vec3 lookup(vec3 color) {
                vec3 position = color * float(lutSize-1);
                ivec3 base = min(ivec3(floor(position)), ivec3(lutSize-2));
                vec3 fraction = position - vec3(base);
                ivec3 order = ivec3(0,1,2);
                int t;
                if (fraction[order.x] < fraction[order.y]) { t=order.x; order.x=order.y; order.y=t; }
                if (fraction[order.y] < fraction[order.z]) { t=order.y; order.y=order.z; order.z=t; }
                if (fraction[order.x] < fraction[order.y]) { t=order.x; order.x=order.y; order.y=t; }
                vec3 previous = texelFetch(lutTexture, base, 0).rgb;
                vec3 value = previous;
                for (int i=0; i<3; ++i) {
                    int axis = order[i]; base[axis] += 1;
                    vec3 next = texelFetch(lutTexture, base, 0).rgb;
                    value += fraction[axis] * (next-previous); previous=next;
                }
                return value;
            }
            void main() {
                vec4 source = texture(imageTexture, uv);
                vec3 original = premultiplied == 1 ? source.rgb / max(source.a, 0.00001) : source.rgb;
                original = clamp(original, 0.0, 1.0);
                vec3 color = hasLut == 1 ? clamp(lookup(original), 0.0, 1.0) : original;
                result = vec4(color * source.a + vec3(0.12) * (1.0-source.a), 1.0);
            }
        """.trimIndent()
    }
}
