package top.rootu.dddplayer.renderer

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import top.rootu.dddplayer.model.StereoInputType
import top.rootu.dddplayer.model.StereoOutputMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

interface OnFpsUpdatedListener {
    fun onFpsUpdated(fps: Int)
}

interface OnSurfaceReadyListener {
    fun onSurfaceReady(surface: Surface)
}

class StereoRenderer(
    private val glSurfaceView: GLSurfaceView,
    private val surfaceReadyListener: OnSurfaceReadyListener,
    // Контекст создан как OpenGL ES 3.0 (в ES 2.0 контексте шейдеры 300 es не компилируются)
    private val contextIsEs3: Boolean,
    preferEs3Shaders: Boolean
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private val tAG = "StereoRenderer"
    private var program: Int = 0

    // Handles
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var textureHandle: Int = 0
    private var texMatrixHandle: Int = 0

    // Матрицы анаглифа
    private var lRow1Handle: Int = 0
    private var lRow2Handle: Int = 0
    private var lRow3Handle: Int = 0
    private var rRow1Handle: Int = 0
    private var rRow2Handle: Int = 0
    private var rRow3Handle: Int = 0

    // Параметры сцены
    private var inputTypeHandle: Int = 0
    private var outputModeHandle: Int = 0
    private var swapEyesHandle: Int = 0
    private var linearBlendHandle: Int = -1
    private var videoDimensionsHandle: Int = 0
    private var screenAspectRatioHandle: Int = 0
    private var singleFrameDimensionsHandle: Int = 0
    private var depthHandle: Int = 0

    // VR Handles
    private var k1Handle: Int = 0
    private var k2Handle: Int = 0
    private var distortScaleHandle: Int = 0
    private var screenSeparationHandle: Int = 0

    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    @Volatile
    private var currentInputType: StereoInputType = StereoInputType.NONE

    // Шейдеры ES 3.0 доступны: контекст ES 3.0 и они успешно собрались
    @Volatile
    private var es3ShadersAvailable: Boolean = contextIsEs3

    // Использовать ли шейдеры ES 3.0 прямо сейчас
    @Volatile
    private var useEs3Shaders: Boolean = contextIsEs3 && preferEs3Shaders

    /**
     * Вызывается (в GL потоке), если шейдеры ES 3.0 не поддерживаются драйвером
     * и рендерер откатился на ES 2.0.
     */
    @Volatile
    var onEs3ShadersUnavailable: (() -> Unit)? = null

    // Флаг: нужно ли перекомпилировать программу на следующем кадре
    @Volatile
    private var shouldReloadShaders: Boolean = false

    @Volatile
    private var currentOutputMode: StereoOutputMode = StereoOutputMode.ANAGLYPH

    @Volatile
    private var currentAnaglyphType: AnaglyphType = AnaglyphType.RC_DUBOIS

    @Volatile
    private var swapEyes: Boolean = false

    @Volatile
    private var videoWidth: Int = 1920

    @Volatile
    private var videoHeight: Int = 1080
    private var screenAspectRatio: Float = 16f / 9f

    @Volatile
    private var singleFrameWidth: Float = 1920f

    @Volatile
    private var singleFrameHeight: Float = 1080f

    @Volatile
    private var rawDepth: Int = 0

    @Volatile
    private var screenSeparation: Float = 0f

    @Volatile
    private var currentDepth: Float = 0f

    // VR параметры
    @Volatile
    private var k1: Float = 0.34f

    @Volatile
    private var k2: Float = 0.10f

    @Volatile
    private var distortScale: Float = 1.2f

    // Текущие матрицы анаглифа (по умолчанию Identity/Red-Cyan placeholder)
    @Volatile
    private var matrixL: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    @Volatile
    private var matrixR: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    private val textureId = IntArray(1)
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private val texMatrix = FloatArray(16)

    @Volatile
    private var frameAvailable = false

    private val vertices = floatArrayOf(
        -1.0f, -1.0f,
        1.0f, -1.0f,
        -1.0f, 1.0f,
        1.0f, 1.0f
    )

    private val texCoords = floatArrayOf(
        0.0f, 1.0f,
        1.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 0.0f
    )

    init {
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertexBuffer.put(vertices).position(0)
        texCoordBuffer =
            ByteBuffer.allocateDirect(texCoords.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        texCoordBuffer.put(texCoords).position(0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        program = 0 // Новый контекст: старая программа уже недействительна
        setupShaders()
        setupTexture()

        surfaceTexture = SurfaceTexture(textureId[0])
        surfaceTexture?.setOnFrameAvailableListener(this)
        videoSurface = Surface(surfaceTexture)

        videoSurface?.let { surfaceReadyListener.onSurfaceReady(it) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        screenAspectRatio = if (height > 0) width.toFloat() / height.toFloat() else 1f
        glSurfaceView.requestRender()
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(this) {
            // 1. Проверяем, нужно ли обновить текстуру
            if (frameAvailable) {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(texMatrix)
                frameAvailable = false
            }

            // 2. Проверяем, нужно ли сменить шейдеры (Hot Swap)
            if (shouldReloadShaders) {
                setupShaders()
                shouldReloadShaders = false
            }
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId[0])
        GLES20.glUniform1i(textureHandle, 0)

        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)

        texCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        updateShaderUniforms()

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        synchronized(this) {
            frameAvailable = true
            glSurfaceView.requestRender()
        }
    }

    private fun setupTexture() {
        GLES20.glGenTextures(1, textureId, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
    }

    /**
     * Переключает набор шейдеров.
     * @return false, если переключение невозможно без пересоздания GL контекста
     * (контекст ES 2.0, а запрошены шейдеры ES 3.0).
     */
    fun setRenderApi(forceLegacy: Boolean): Boolean {
        val newUseEs3 = !forceLegacy
        if (newUseEs3 && !contextIsEs3) return false
        if (newUseEs3 && !es3ShadersAvailable) return true // Уже откатились на ES 2.0

        if (useEs3Shaders != newUseEs3) {
            useEs3Shaders = newUseEs3
            shouldReloadShaders = true // Сигнал для onDrawFrame
            glSurfaceView.requestRender()
        }
        return true
    }

    private fun setupShaders() {
        // Удаляем старую программу, если она была
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }

        if (useEs3Shaders) {
            program = if (hasEssl3ExternalImageSupport()) {
                buildProgram(ShaderSource.VERTEX_SHADER_300, ShaderSource.FRAGMENT_SHADER_300)
            } else {
                Log.w(tAG, "GL_OES_EGL_image_external_essl3 is not supported")
                0
            }
            if (program == 0) {
                // Драйвер не собрал шейдеры ES 3.0 - откатываемся на ES 2.0, чтобы не было черного экрана
                Log.w(tAG, "Falling back to GLES 2.0 shaders")
                useEs3Shaders = false
                es3ShadersAvailable = false
                onEs3ShadersUnavailable?.invoke()
            }
        }

        if (program == 0) {
            program = buildProgram(ShaderSource.VERTEX_SHADER, ShaderSource.FRAGMENT_SHADER)
        }
        if (program == 0) return

        positionHandle = GLES20.glGetAttribLocation(program, "a_position")
        texCoordHandle = GLES20.glGetAttribLocation(program, "a_texCoord")
        textureHandle = GLES20.glGetUniformLocation(program, "u_texture")
        texMatrixHandle = GLES20.glGetUniformLocation(program, "u_texMatrix")

        lRow1Handle = GLES20.glGetUniformLocation(program, "u_l_row1")
        lRow2Handle = GLES20.glGetUniformLocation(program, "u_l_row2")
        lRow3Handle = GLES20.glGetUniformLocation(program, "u_l_row3")
        rRow1Handle = GLES20.glGetUniformLocation(program, "u_r_row1")
        rRow2Handle = GLES20.glGetUniformLocation(program, "u_r_row2")
        rRow3Handle = GLES20.glGetUniformLocation(program, "u_r_row3")

        inputTypeHandle = GLES20.glGetUniformLocation(program, "u_inputType")
        outputModeHandle = GLES20.glGetUniformLocation(program, "u_outputMode")
        swapEyesHandle = GLES20.glGetUniformLocation(program, "u_swapEyes")
        linearBlendHandle = GLES20.glGetUniformLocation(program, "u_linearBlend") // -1 в ES 2.0
        videoDimensionsHandle = GLES20.glGetUniformLocation(program, "u_videoDimensions")
        screenAspectRatioHandle = GLES20.glGetUniformLocation(program, "u_screenAspectRatio")
        singleFrameDimensionsHandle =
            GLES20.glGetUniformLocation(program, "u_singleFrameDimensions")
        depthHandle = GLES20.glGetUniformLocation(program, "u_depth")

        k1Handle = GLES20.glGetUniformLocation(program, "u_k1")
        k2Handle = GLES20.glGetUniformLocation(program, "u_k2")
        distortScaleHandle = GLES20.glGetUniformLocation(program, "u_distortScale")
        screenSeparationHandle = GLES20.glGetUniformLocation(program, "u_screenSeparation")
    }

    private fun hasEssl3ExternalImageSupport(): Boolean {
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: return false
        return extensions.contains("GL_OES_EGL_image_external_essl3")
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return 0
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) {
            GLES20.glDeleteShader(vertexShader)
            return 0
        }

        val newProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(newProgram, vertexShader)
        GLES20.glAttachShader(newProgram, fragmentShader)
        GLES20.glLinkProgram(newProgram)

        // Шейдеры больше не нужны: программа держит их до своего удаления
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(newProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(tAG, "Could not link program: " + GLES20.glGetProgramInfoLog(newProgram))
            GLES20.glDeleteProgram(newProgram)
            return 0
        }
        return newProgram
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            Log.e(tAG, "Could not compile shader $type: " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    /**
     * Размер буфера входной поверхности, когда кадры рисуются на ней через Canvas
     * (тестовые картинки калибровки). Видео плеер задает размер сам.
     */
    fun setInputBufferSize(width: Int, height: Int) {
        surfaceTexture?.setDefaultBufferSize(width, height)
    }

    fun setInputType(type: StereoInputType) {
        currentInputType = type
        glSurfaceView.requestRender()
    }

    fun setOutputMode(mode: StereoOutputMode) {
        currentOutputMode = mode
        glSurfaceView.requestRender()
    }

    fun setAnaglyphType(type: AnaglyphType) {
        currentAnaglyphType = type
        glSurfaceView.requestRender()
    }

    fun setAnaglyphMatrices(l: FloatArray, r: FloatArray) {
        this.matrixL = l
        this.matrixR = r
        glSurfaceView.requestRender()
    }

    fun setSwapEyes(swap: Boolean) {
        swapEyes = swap
        glSurfaceView.requestRender()
    }

    fun setVideoDimensions(width: Int, height: Int) { // todo удалить если не пригодиться
        videoWidth = width
        videoHeight = height
        glSurfaceView.requestRender()
    }

    fun setSingleFrameDimensions(width: Float, height: Float) {
        this.singleFrameWidth = width
        this.singleFrameHeight = height
        updateDepthUniform()
        glSurfaceView.requestRender()
    }

    fun setDepth(depth: Int) {
        this.rawDepth = depth
        updateDepthUniform()
        glSurfaceView.requestRender()
    }

    fun setScreenSeparation(separation: Float) {
        this.screenSeparation = separation
        glSurfaceView.requestRender()
    }

    fun setDistortion(k1: Float, k2: Float, scale: Float) {
        this.k1 = k1
        this.k2 = k2
        this.distortScale = scale
        glSurfaceView.requestRender()
    }

    private fun updateDepthUniform() {
        if (singleFrameWidth > 0) {
            this.currentDepth = rawDepth.toFloat() / singleFrameWidth
        } else {
            this.currentDepth = 0f
        }
    }

    private fun updateShaderUniforms() {
        GLES20.glUniform1i(inputTypeHandle, currentInputType.ordinal)
        GLES20.glUniform1i(outputModeHandle, currentOutputMode.ordinal)
        GLES20.glUniform1i(swapEyesHandle, if (swapEyes) 1 else 0)
        if (linearBlendHandle >= 0) {
            GLES20.glUniform1i(linearBlendHandle, if (usesLinearMatrix(currentAnaglyphType)) 1 else 0)
        }
        GLES20.glUniform2f(videoDimensionsHandle, videoWidth.toFloat(), videoHeight.toFloat())
        GLES20.glUniform1f(screenAspectRatioHandle, screenAspectRatio)
        GLES20.glUniform2f(singleFrameDimensionsHandle, singleFrameWidth, singleFrameHeight)
        GLES20.glUniform1f(depthHandle, currentDepth)

        GLES20.glUniform1f(k1Handle, k1)
        GLES20.glUniform1f(k2Handle, k2)
        GLES20.glUniform1f(distortScaleHandle, distortScale)
        GLES20.glUniform1f(screenSeparationHandle, screenSeparation)

        if (currentOutputMode == StereoOutputMode.ANAGLYPH) {
            // Используем матрицы, переданные через setAnaglyphMatrices
            GLES20.glUniform3f(lRow1Handle, matrixL[0], matrixL[1], matrixL[2])
            GLES20.glUniform3f(lRow2Handle, matrixL[3], matrixL[4], matrixL[5])
            GLES20.glUniform3f(lRow3Handle, matrixL[6], matrixL[7], matrixL[8])
            GLES20.glUniform3f(rRow1Handle, matrixR[0], matrixR[1], matrixR[2])
            GLES20.glUniform3f(rRow2Handle, matrixR[3], matrixR[4], matrixR[5])
            GLES20.glUniform3f(rRow3Handle, matrixR[6], matrixR[7], matrixR[8])
        }
    }

    // Матрицы Dubois/Optimized и Custom (DuboisMath) рассчитаны для линейного RGB.
    // Mono/Half Color построены на весах яркости Rec.601 для гамма-значений,
    // а Color лишь копирует каналы - для них смешивание остается в гамме.
    private fun usesLinearMatrix(type: AnaglyphType): Boolean =
        type.name.endsWith("_DUBOIS") || type.name.endsWith("_CUSTOM") ||
                type == AnaglyphType.RC_OPTIMIZED

    fun release() {
        // Освобождаем Java-объекты Surface, чтобы ExoPlayer перестал в них писать
        try {
            surfaceTexture?.release()
            videoSurface?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        surfaceTexture = null
        videoSurface = null

        // Примечание: glDeleteProgram и glDeleteTextures должны вызываться в GL потоке.
        // Обычно GLSurfaceView сам уничтожает контекст при детаче, поэтому явное удаление
        // текстур здесь может быть избыточным или вызвать ошибку, если контекст уже мертв.
        // Главное - освободить SurfaceTexture.
    }

    enum class AnaglyphType {
        // Red-Cyan
        RC_DUBOIS, RC_HALF_COLOR, RC_COLOR, RC_MONO, RC_OPTIMIZED, RC_CUSTOM,

        // Yellow-Blue
        YB_DUBOIS, YB_HALF_COLOR, YB_COLOR, YB_MONO, YB_CUSTOM,

        // Green-Magenta
        GM_DUBOIS, GM_HALF_COLOR, GM_COLOR, GM_MONO, GM_CUSTOM,
        RB_MONO
    }
}