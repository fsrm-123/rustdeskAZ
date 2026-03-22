package com.carriez.flutter_hbb

// Inspired by https://github.com/yosemiteyss/flutter_volume_controller/blob/main/android/src/main/kotlin/com/yosemiteyss/flutter_volume_controller/VolumeController.kt

import android.media.AudioManager
import android.os.Build
import android.util.Log

class VolumeController(private val audioManager: AudioManager) {
    private val logTag = "volume controller"

    // ========== 修复：补全方法参数兼容，匹配InputService.kt的调用逻辑 ==========
    fun getVolume(streamType: Int): Double {
        val current = audioManager.getStreamVolume(streamType)
        val max = audioManager.getStreamMaxVolume(streamType)
        return current.toDouble() / max
    }

    // ========== 修复：新增重载方法，兼容InputService.kt的调用（step可为null） ==========
    fun raiseVolume(step: Double?, showSystemUI: Boolean, streamType: Int) {
        if (step == null) {
            audioManager.adjustStreamVolume(
                streamType,
                AudioManager.ADJUST_RAISE,
                if (showSystemUI) AudioManager.FLAG_SHOW_UI else 0
            )
        } else {
            val target = getVolume(streamType) + step
            setVolume(target, showSystemUI, streamType)
        }
    }

    // ========== 修复：新增重载方法，兼容InputService.kt的调用（step可为null） ==========
    fun lowerVolume(step: Double?, showSystemUI: Boolean, streamType: Int) {
        if (step == null) {
            audioManager.adjustStreamVolume(
                streamType,
                AudioManager.ADJUST_LOWER,
                if (showSystemUI) AudioManager.FLAG_SHOW_UI else 0
            )
        } else {
            val target = getVolume(streamType) - step
            setVolume(target, showSystemUI, streamType)
        }
    }

    // ========== 修复：补全setVolume方法实现 ==========
    fun setVolume(volume: Double, showSystemUI: Boolean, streamType: Int) {
        val max = audioManager.getStreamMaxVolume(streamType)
        val targetVolume = (max * volume).toInt().coerceIn(0, max) // 限制音量范围，避免越界
        audioManager.setStreamVolume(
            streamType,
            targetVolume,
            if (showSystemUI) AudioManager.FLAG_SHOW_UI else 0
        )
    }

    // ========== 修复：补全getMute方法，兼容低版本Android ==========
    fun getMute(streamType: Int): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.isStreamMute(streamType)
        } else {
            // 低版本兼容：音量为0视为静音
            audioManager.getStreamVolume(streamType) == 0
        }
    }

    // ========== 修复：将setMute改为public，或新增重载方法匹配调用 ==========
    fun toggleMute(showSystemUI: Boolean, streamType: Int) {
        val isMuted = getMute(streamType)
        setMute(!isMuted, showSystemUI, streamType)
    }

    // ========== 修复：setMute方法改为public，解决未解析引用 ==========
    fun setMute(isMuted: Boolean, showSystemUI: Boolean, streamType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6.0+ 推荐使用adjustStreamVolume
            audioManager.adjustStreamVolume(
                streamType,
                if (isMuted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                if (showSystemUI) AudioManager.FLAG_SHOW_UI else 0
            )
        } else {
            // 低版本兼容：使用setStreamMute
            @Suppress("DEPRECATION")
            audioManager.setStreamMute(streamType, isMuted)
        }
    }
}
