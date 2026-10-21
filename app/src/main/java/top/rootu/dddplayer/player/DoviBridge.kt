package top.rootu.dddplayer.player

import android.util.Log

// Перенесено из edde746/plezy (GPL-3.0): JNI-мост к конвертации RPU Profile 7 -> 8.1 в libdovi
enum class DvConversionMode { DISABLED, DV81, HEVC_STRIP }

object DoviBridge {
    private const val TAG = "DoviBridge"
    const val CONVERT_FAILED = -1
    const val DESTINATION_TOO_SMALL = -2

    private val ready: Boolean by lazy {
        try {
            System.loadLibrary("dovi_bridge")
            nativeIsConversionPathReady()
        } catch (e: Throwable) {
            Log.w(TAG, "Native lib not available", e)
            false
        }
    }

    fun isAvailable(): Boolean = ready

    fun convertRpuNalu(
        payload: ByteArray,
        payloadOffset: Int,
        payloadLength: Int,
        output: ByteArray,
        outputOffset: Int,
        outputCapacity: Int,
        mode: Int = 2
    ): Int {
        if (!ready || payloadLength <= 0) return CONVERT_FAILED
        return runCatching {
            nativeConvertDv7RpuToDv81(payload, payloadOffset, payloadLength, output, outputOffset, outputCapacity, mode)
        }.onFailure { Log.w(TAG, "RPU conversion failed: ${it.message}") }
            .getOrDefault(CONVERT_FAILED)
    }

    @JvmStatic
    private external fun nativeConvertDv7RpuToDv81(
        payload: ByteArray, payloadOffset: Int, payloadLength: Int,
        output: ByteArray, outputOffset: Int, outputCapacity: Int, mode: Int
    ): Int

    @JvmStatic
    private external fun nativeIsConversionPathReady(): Boolean
}
