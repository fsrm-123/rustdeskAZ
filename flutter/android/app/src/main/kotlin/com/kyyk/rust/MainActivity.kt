package com.kyyk.rust

import ffi.FFI

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.DisplayMetrics
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import com.hjq.permissions.XXPermissions
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlin.concurrent.thread

// ============== 华为 HMS ==============
import com.huawei.hms.push.HmsMessaging
import android.app.Activity
import android.provider.Settings

class MainActivity : FlutterActivity() {
    companion object {
        const val CHANNEL = "com.carriez.flutter_hbb/main"
        var flutterMethodChannel: MethodChannel? = null
        private var _rdClipboardManager: RdClipboardManager? = null
        val rdClipboardManager: RdClipboardManager?
            get() = _rdClipboardManager
            
        // 密码存储常量
        const val UNLOCK_PREFS_NAME = "rustdesk_unlock_config"
        const val PREFS_KEY_UNLOCK_PASSWORD = "screen_unlock_password"

        // ========== 屏幕录制权限所需常量（唯一保留的定义处）==========
        const val ACT_REQUEST_MEDIA_PROJECTION = "ACT_REQUEST_MEDIA_PROJECTION"
        const val ACT_INIT_MEDIA_PROJECTION_AND_SERVICE = "ACT_INIT_MEDIA_PROJECTION_AND_SERVICE"
        const val EXT_MEDIA_PROJECTION_RES_INTENT = "EXT_MEDIA_PROJECTION_RES_INTENT"
        const val REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION = 1001
        const val RES_FAILED = Activity.RESULT_FIRST_USER
    }

    // ====================== 推送发送通道 ======================
    private val PUSH_SENDER_CHANNEL = "com.kyyk.rust/push_sender"
    private val HUAWEI_TOKEN_CHANNEL = "com.kyyk.rust/huawei_token"

    private val channelTag = "mChannel"
    private val logTag = "mMainActivity"
    private var mainService: MainService? = null

    private var isAudioStart = false
    private val audioRecordHandle = AudioRecordHandle(this, { false }, { isAudioStart })
    private var isWaitingForMediaPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (_rdClipboardManager == null) {
            _rdClipboardManager = RdClipboardManager(getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            FFI.setClipboardManager(_rdClipboardManager!!)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        TokenRefreshReceiver.flutterEngine = flutterEngine

        if (MainService.isReady) {
            Intent(activity, MainService::class.java).also {
                bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }

        flutterMethodChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            channelTag
        )
        initFlutterChannel(flutterMethodChannel!!)

        // 推送发送通道
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, PUSH_SENDER_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "sendPushCommand" -> {
                    val targetToken = call.argument<String>("targetToken")
                    val command = call.argument<String>("command")
                    if (!targetToken.isNullOrBlank() && !command.isNullOrBlank()) {
                        PushSendUtil.sendCommand(this, targetToken, command)
                        result.success("OK")
                    } else {
                        result.error("INVALID_ARGS", "参数错误", null)
                    }
                }
                else -> result.notImplemented()
            }
        }

        // 华为 Token 通道
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, HUAWEI_TOKEN_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getHuaweiToken" -> {
                    result.success(HuaweiPushService.currentToken)
                }
                "refreshHuaweiToken" -> {
                    try {
                        thread {
                            try {
                                HmsMessaging.getInstance(this@MainActivity).isAutoInitEnabled = true
                                HmsMessaging.getInstance(this@MainActivity).turnOnPush()
                                Log.i("HuaweiPush", "刷新按钮：已触发重新获取Token")
                            } catch (e: Exception) {
                                Log.e("HuaweiPush", "刷新失败", e)
                            }
                        }
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e("HuaweiPush", "刷新按钮：获取失败", e)
                        result.success(false)
                    }
                }
                else -> result.notImplemented()
            }
        }

        thread {
            try {
                setCodecInfo()
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to setCodecInfo: ${e.message}", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val inputPer = InputService.isOpen
        activity.runOnUiThread {
            flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "input", "value" to inputPer.toString())
            )
        }
    }

    private fun requestMediaProjection() {
        val intent = Intent(this, PermissionRequestTransparentActivity::class.java).apply {
            action = ACT_REQUEST_MEDIA_PROJECTION
        }
        startActivityForResult(intent, REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION && resultCode == RES_FAILED) {
            flutterMethodChannel?.invokeMethod("on_media_projection_canceled", null)
        }
    }

    override fun onDestroy() {
        Log.e(logTag, "onDestroy")
        mainService?.let {
            unbindService(serviceConnection)
        }
        super.onDestroy()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(logTag, "onServiceConnected")
            val binder = service as MainService.LocalBinder
            mainService = binder.getService()

            if (MainService.isReady && !MainService.isStart) {
                mainService?.startCapture()
            }
            if (isWaitingForMediaPermission && MainService.isReady) {
                mainService?.startCapture()
                isWaitingForMediaPermission = false
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(logTag, "onServiceDisconnected")
            mainService = null
        }
    }

    // ========== 缺失的函数 ==========
    private fun startAction(context: Context, action: String) {
        when (action) {
            "accessibility" -> {
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(logTag, "Failed to open accessibility settings", e)
                }
            }
            else -> Log.w(logTag, "Unknown START_ACTION: $action")
        }
    }

    private fun isSupportVoiceCall(): Boolean {
        return true
    }

    private fun requestPermission(context: Context, permission: String) {
        XXPermissions.with(this)
            .permission(permission)
            .request { _, _ -> }
    }

    private fun initFlutterChannel(flutterMethodChannel: MethodChannel) {
        flutterMethodChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "init_service" -> {
                    Intent(activity, MainService::class.java).also {
                        bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
                    }
                    if (MainService.isReady) {
                        mainService?.let {
                            if (!MainService.isStart) {
                                it.startCapture()
                            }
                        }
                        result.success(false)
                        return@setMethodCallHandler
                    }
                    isWaitingForMediaPermission = true
                    requestMediaProjection()
                    result.success(true)
                }
                "start_capture" -> {
                    mainService?.let {
                        result.success(it.startCapture())
                    } ?: result.success(false)
                }
                "stop_service" -> {
                    Log.d(logTag, "Stop service")
                    mainService?.let {
                        it.destroy()
                        result.success(true)
                    } ?: result.success(false)
                }
                "check_permission" -> {
                    val permission = call.arguments as? String
                    if (permission != null) {
                        result.success(XXPermissions.isGranted(context, permission))
                    } else {
                        result.success(false)
                    }
                }
                "request_permission" -> {
                    val permission = call.arguments as? String
                    if (permission != null) {
                        requestPermission(context, permission)
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                "START_ACTION" -> {
                    val action = call.arguments as? String
                    if (action != null) {
                        startAction(context, action)
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                "check_video_permission" -> {
                    mainService?.let {
                        result.success(it.checkMediaPermission())
                    } ?: result.success(false)
                }
                "check_service" -> {
                    Companion.flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "input", "value" to InputService.isOpen.toString())
                    )
                    Companion.flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "media", "value" to MainService.isReady.toString())
                    )
                    result.success(true)
                }
                "stop_input" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        InputService.ctx?.disableSelf()
                    }
                    InputService.ctx = null
                    Companion.flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "input", "value" to InputService.isOpen.toString())
                    )
                    result.success(true)
                }
                "cancel_notification" -> {
                    val id = call.arguments as? Int
                    if (id != null) {
                        mainService?.cancelNotification(id)
                    }
                    result.success(true)
                }
                "enable_soft_keyboard" -> {
                    val enable = call.arguments as? Boolean ?: false
                    if (enable) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    } else {
                        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    }
                    result.success(true)
                }
                "try_sync_clipboard" -> {
                    rdClipboardManager?.syncClipboard(true)
                    result.success(true)
                }
                "GET_START_ON_BOOT_OPT" -> {
                    val prefs = getSharedPreferences("KEY_SHARED_PREFERENCES", MODE_PRIVATE)
                    result.success(prefs.getBoolean("KEY_START_ON_BOOT_OPT", false))
                }
                "SET_START_ON_BOOT_OPT" -> {
                    val enable = call.arguments as? Boolean
                    if (enable != null) {
                        val prefs = getSharedPreferences("KEY_SHARED_PREFERENCES", MODE_PRIVATE)
                        prefs.edit().putBoolean("KEY_START_ON_BOOT_OPT", enable).apply()
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                "SYNC_APP_DIR_CONFIG_PATH" -> {
                    val path = call.arguments as? String
                    if (path != null) {
                        val prefs = getSharedPreferences("KEY_SHARED_PREFERENCES", MODE_PRIVATE)
                        prefs.edit().putString("KEY_APP_DIR_CONFIG_PATH", path).apply()
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                "get_value" -> {
                    val key = call.arguments as? String
                    if (key == "KEY_IS_SUPPORT_VOICE_CALL") {
                        result.success(isSupportVoiceCall())
                    } else {
                        result.error("-1", "No such key", null)
                    }
                }
                "on_voice_call_started" -> {
                    onVoiceCallStarted()
                    result.success(true)
                }
                "on_voice_call_closed" -> {
                    onVoiceCallClosed()
                    result.success(true)
                }
                "save_unlock_password" -> {
                    val password = call.argument<String>("password") ?: ""
                    try {
                        // 修改：使用 Activity 自身的 getSharedPreferences，避免 applicationContext 导致文件路径不一致
                        val sp = getSharedPreferences(UNLOCK_PREFS_NAME, Context.MODE_PRIVATE)
                        // 使用 commit() 同步写入，确保立即保存
                        val success = sp.edit().putString(PREFS_KEY_UNLOCK_PASSWORD, password).commit()
                        if (success) {
                            Log.d(logTag, "保存解锁密码成功，长度：${password.length}")
                            result.success(true)
                        } else {
                            Log.e(logTag, "保存解锁密码失败")
                            result.error("1", "保存失败", null)
                        }
                    } catch (e: Exception) {
                        Log.e(logTag, "保存密码失败", e)
                        result.error("1", "保存失败", e.message)
                    }
                }
                "get_unlock_password" -> {
                    try {
                        // 修改：同样使用 getSharedPreferences 保持一致
                        val sp = getSharedPreferences(UNLOCK_PREFS_NAME, Context.MODE_PRIVATE)
                        val pwd = sp.getString(PREFS_KEY_UNLOCK_PASSWORD, "") ?: ""
                        Log.d(logTag, "读取解锁密码成功，已设置：${pwd.isNotEmpty()}")
                        result.success(pwd)
                    } catch (e: Exception) {
                        Log.e(logTag, "读取密码失败", e)
                        result.error("1", "读取失败", e.message)
                    }
                }
                else -> {
                    result.error("-1", "No such method", null)
                }
            }
        }
    }

    private fun setCodecInfo() {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val codecs = codecList.codecInfos
        val codecArray = JSONArray()

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val wh = getScreenSize(windowManager)
        var w = wh.first
        var h = wh.second
        val align = 64
        w = (w + align - 1) / align * align
        h = (h + align - 1) / align * align
        codecs.forEach { codec ->
            val codecObject = JSONObject()
            codecObject.put("name", codec.name)
            codecObject.put("is_encoder", codec.isEncoder)
            var hw: Boolean? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                hw = codec.isHardwareAccelerated
            } else {
                if (listOf("OMX.google.", "OMX.SEC.", "c2.android").any { codec.name.startsWith(it, true) }) {
                    hw = false
                } else if (listOf("c2.qti", "OMX.qcom.video", "OMX.Exynos", "OMX.hisi", "OMX.MTK", "OMX.Intel", "OMX.Nvidia").any { codec.name.startsWith(it, true) }) {
                    hw = true
                }
            }
            if (hw != true) {
                return@forEach
            }
            codecObject.put("hw", hw)
            var mime_type = ""
            codec.supportedTypes.forEach { type ->
                if (listOf("video/avc", "video/hevc").contains(type)) {
                    mime_type = type
                }
            }
            if (mime_type.isNotEmpty()) {
                codecObject.put("mime_type", mime_type)
                val caps = codec.getCapabilitiesForType(mime_type)
                if (codec.isEncoder) {
                    if (!caps.videoCapabilities.isSizeSupported(w, h) && !caps.videoCapabilities.isSizeSupported(h, w)) {
                        return@forEach
                    }
                }
                codecObject.put("min_width", caps.videoCapabilities.supportedWidths.lower)
                codecObject.put("max_width", caps.videoCapabilities.supportedWidths.upper)
                codecObject.put("min_height", caps.videoCapabilities.supportedHeights.lower)
                codecObject.put("max_height", caps.videoCapabilities.supportedHeights.upper)
                val surface = caps.colorFormats.contains(COLOR_FormatSurface)
                codecObject.put("surface", surface)
                val nv12 = caps.colorFormats.contains(COLOR_FormatYUV420SemiPlanar)
                codecObject.put("nv12", nv12)
                if (!(nv12 || surface)) {
                    return@forEach
                }
                codecObject.put("min_bitrate", caps.videoCapabilities.bitrateRange.lower / 1000)
                codecObject.put("max_bitrate", caps.videoCapabilities.bitrateRange.upper / 1000)
                if (!codec.isEncoder) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        codecObject.put("low_latency", caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
                    }
                }
                if (!codec.isEncoder) {
                    return@forEach
                }
                codecArray.put(codecObject)
            }
        }
      
