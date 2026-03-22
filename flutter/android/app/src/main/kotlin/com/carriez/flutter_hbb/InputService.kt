package com.carriez.flutter_hbb

/**
 * Handle remote input and dispatch android gesture
 *
 * Inspired by [droidVNC-NG] https://github.com/bk138/droidVNC-NG
 */

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.EditText
import android.view.accessibility.AccessibilityEvent
import android.view.ViewGroup.LayoutParams
import android.view.accessibility.AccessibilityNodeInfo
import android.view.KeyEvent as KeyEventAndroid
import android.view.ViewConfiguration
import android.graphics.Rect
import android.media.AudioManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
import android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.annotation.SuppressLint
import android.content.SharedPreferences
import java.util.*
import java.lang.Character
import kotlin.math.abs
import kotlin.math.max
import hbb.MessageOuterClass.KeyEvent
import hbb.MessageOuterClass.KeyboardMode
import hbb.KeyEventConverter
import java.io.File
import java.io.FileInputStream
import java.util.Properties

// const val BUTTON_UP = 2
// const val BUTTON_BACK = 0x08

const val LEFT_DOWN = 9
const val LEFT_MOVE = 8
const val LEFT_UP = 10
const val RIGHT_UP = 18
// (BUTTON_BACK << 3) | BUTTON_UP
const val BACK_UP = 66
const val WHEEL_BUTTON_DOWN = 33
const val WHEEL_BUTTON_UP = 34
const val WHEEL_DOWN = 523331
const val WHEEL_UP = 963

const val TOUCH_SCALE_START = 1
const val TOUCH_SCALE = 2
const val TOUCH_SCALE_END = 3
const val TOUCH_PAN_START = 4
const val TOUCH_PAN_UPDATE = 5
const val TOUCH_PAN_END = 6

const val WHEEL_STEP = 120
const val WHEEL_DURATION = 50L
const val LONG_TAP_DELAY = 200L

// 解锁相关常量（移除写死密码，新增SharedPreferences配置）
const val SWIPE_UP_DELAY = 1000L
const val INPUT_DELAY = 2000L
const val DIGIT_INPUT_INTERVAL = 150L
const val UNLOCK_CHECK_DELAY = 8000L
// SharedPreferences 相关常量（对应Flutter的shared_preferences存储）
private const val SHARED_PREFS_NAME = "flutter_shared_preferences"
private const val PREFS_KEY_UNLOCK_PASSWORD = "unlock_password"

// ========== 新增：补全缺失的SCREEN_INFO定义（核心修复） ==========
object SCREEN_INFO {
    var scale: Float = 1.0f
}

class InputService : AccessibilityService() {

    companion object {
        var ctx: InputService? = null
        val isOpen: Boolean
            get() = ctx != null
    }

    private val logTag = "input service"
    private var leftIsDown = false
    private var touchPath = Path()
    private var stroke: GestureDescription.StrokeDescription? = null
    private var lastTouchGestureStartTime = 0L
    private var mouseX = 0
    private var mouseY = 0
    private var timer = Timer()
    private var recentActionTask: TimerTask? = null
    // 100(tap timeout) + 400(long press timeout)
    private val longPressDuration = ViewConfiguration.getTapTimeout().toLong() + ViewConfiguration.getLongPressTimeout().toLong()

    private val wheelActionsQueue = LinkedList<GestureDescription>()
    private var isWheelActionsPolling = false
    private var isWaitingLongPress = false

    private var fakeEditTextForTextStateCalculation: EditText? = null

    private var lastX = 0
    private var lastY = 0

    private val volumeController: VolumeController by lazy { VolumeController(applicationContext.getSystemService(AUDIO_SERVICE) as AudioManager) }

    // 新增：标记是否正在解锁中，避免重复触发
    private var isUnlocking = false

    // ========== 修复：读取Flutter保存的解锁密码（更安全的方式） ==========
    private fun getUnlockPassword(): String {
        try {
            // 方式1：优先通过SharedPreferences API读取（避免文件权限问题）
            val prefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
            val password = prefs.getString(PREFS_KEY_UNLOCK_PASSWORD, "") ?: ""
            
            if (password.isNotEmpty()) {
                Log.d(logTag, "从SharedPreferences API读取到解锁密码: ●●●●●●")
                return password
            }

            // 方式2：兜底读取文件（兼容Flutter的存储格式）
            val dataDir = applicationContext.applicationInfo.dataDir ?: return ""
            val prefsFile = File("$dataDir/shared_prefs/", "$SHARED_PREFS_NAME.xml")
            
            if (!prefsFile.exists()) {
                Log.d(logTag, "SharedPreferences文件不存在，返回空密码")
                return ""
            }

            val properties = Properties()
            FileInputStream(prefsFile).use { inputStream ->
                properties.loadFromXML(inputStream)
            }
            
            val filePassword = properties.getProperty(PREFS_KEY_UNLOCK_PASSWORD, "")
            Log.d(logTag, "从SharedPreferences文件读取到解锁密码: ${if (filePassword.isNotEmpty()) "●●●●●●" else "未设置"}")
            return filePassword
        } catch (e: Exception) {
            Log.e(logTag, "读取解锁密码失败: ${e.message}", e)
            return ""
        }
    }

    // ========== 锁屏判断方法 ==========
    private fun isScreenLocked(): Boolean {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        // 兼容 Android 13+ (TIRAMISU)
        val isKeyguardLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            keyguardManager.isDeviceLocked()
        } else {
            keyguardManager.isKeyguardLocked()
        }
        
        // 补充判断屏幕是否休眠（黑屏）
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isScreenOff = !powerManager.isInteractive
        
        return isKeyguardLocked || isScreenOff
    }

    // ========== 解锁屏幕核心方法 ==========
    @SuppressLint("WakelockTimeout")
    private fun unlockScreen(onFinish: () -> Unit) {
        if (isUnlocking) {
            Log.d(logTag, "正在解锁中，跳过重复触发")
            return
        }
        isUnlocking = true
        Log.d(logTag, "开始执行屏幕解锁操作")
        
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            
            // 规范WakeLock标签（包名格式）
            val wakeLockTag = "${packageName}:InputServiceWakeLock"
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                wakeLockTag
            )
            wakeLock.acquire(15000) // 超时兜底释放
            
            // 使用Handler实现非阻塞延迟
            val mainHandler = Handler(Looper.getMainLooper())
            
            // 步骤1：亮屏后延迟上滑
            mainHandler.postDelayed({
                swipeUp()
                
                // 步骤2：上滑后延迟输入密码（改为读取动态密码）
                mainHandler.postDelayed({
                    val password = getUnlockPassword()
                    // 未设置密码时跳过输入步骤
                    if (password.isNotEmpty()) {
                        inputPassword(password)
                    } else {
                        Log.d(logTag, "未设置解锁密码，跳过密码输入步骤")
                    }
                    
                    // 步骤3：输入密码后检查解锁结果+释放WakeLock
                    mainHandler.postDelayed({
                        val locked = isScreenLocked()
                        Log.d(logTag, if (!locked) "屏幕解锁成功" else "屏幕解锁失败")
                        // 显式释放WakeLock
                        if (wakeLock.isHeld) {
                            wakeLock.release()
                        }
                        isUnlocking = false
                        // 解锁完成后执行原操作
                        onFinish()
                    }, UNLOCK_CHECK_DELAY)
                    
                }, INPUT_DELAY)
                
            }, SWIPE_UP_DELAY)
            
        } catch (e: Exception) {
            Log.e(logTag, "解锁屏幕失败: ${e.message}", e)
            isUnlocking = false
            onFinish()
        }
    }

    // ========== 上滑解锁界面 ==========
    private fun swipeUp() {
        val displayMetrics = resources.displayMetrics
        val x = displayMetrics.widthPixels / 2
        val startY = (displayMetrics.heightPixels * 0.8).toInt()
        val endY = (displayMetrics.heightPixels * 0.2).toInt()
        
        val path = Path().apply {
            moveTo(x.toFloat(), startY.toFloat())
            lineTo(x.toFloat(), endY.toFloat())
        }
        
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        
        dispatchGesture(gesture, null, null)
        Log.d(logTag, "执行上滑操作: ($x, $startY) -> ($x, $endY)")
    }

    // ========== 输入解锁密码（非阻塞） ==========
    private fun inputPassword(password: String) {
        Log.d(logTag, "开始输入密码: $password")
        
        val mainHandler = Handler(Looper.getMainLooper())
        // 非阻塞间隔输入数字
        for (i in password.indices) {
            val char = password[i]
            mainHandler.postDelayed({
                if (char in '0'..'9') {
                    clickDigit(char - '0')
                }
            }, i * DIGIT_INPUT_INTERVAL)
        }
        
        // 所有数字输入完成后点击确认键
        mainHandler.postDelayed({
            clickEnter()
        }, password.length * DIGIT_INPUT_INTERVAL)
    }

    // ========== 点击数字键 ==========
    private fun clickDigit(digit: Int) {
        Log.d(logTag, "点击数字: $digit")
        
        val rootNode = rootInActiveWindow ?: run {
            Log.e(logTag, "根节点为空，无法点击数字")
            return
        }
        
        // 尝试通过资源ID查找
        val possibleIds = arrayOf(
            "com.android.systemui:id/key$digit",
            "key$digit",
            "digit$digit"
        )
        
        var targetNode: AccessibilityNodeInfo? = null
        
        for (id in possibleIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                targetNode = nodes[0]
                break
            }
        }
        
        // ID查找失败，尝试通过文本查找
        if (targetNode == null) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(digit.toString())
            for (node in nodes) {
                if (node.isClickable) {
                    targetNode = node
                    break
                }
            }
        }
        
        // 执行点击（使用重命名后的方法避免冲突）
        targetNode?.let { node ->
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            performUnlockClick(bounds.centerX(), bounds.centerY(), 100)
            node.recycle()
        }
        
        rootNode.recycle()
    }

    // ========== 点击确认/解锁键 ==========
    private fun clickEnter() {
        Log.d(logTag, "尝试点击确认键")
        
        val rootNode = rootInActiveWindow ?: run {
            Log.e(logTag, "根节点为空，无法点击确认键")
            return
        }
        
        // 优先通过文本查找（兼容多语言/多厂商）
        val confirmTexts = arrayOf("确定", "确认", "OK", "完成", "解锁", "下一步")
        for (text in confirmTexts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    performUnlockClick(bounds.centerX(), bounds.centerY(), 100)
                    node.recycle()
                    rootNode.recycle()
                    return
                }
            }
        }
        
        // 降级到坐标点击（兜底方案）
        val displayMetrics = resources.displayMetrics
        val x = displayMetrics.widthPixels - 100
        val y = displayMetrics.heightPixels - 200
        performUnlockClick(x, y, 100)
        rootNode.recycle()
    }

    // ========== 解锁专用点击方法（避免与原有performClick冲突） ==========
    private fun performUnlockClick(x: Int, y: Int, duration: Long) {
        val safeX = max(0, x)
        val safeY = max(0, y)
        
        val path = Path().apply {
            moveTo(safeX.toFloat(), safeY.toFloat())
        }
        
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        
        dispatchGesture(gesture, null, null)
        Log.d(logTag, "执行解锁点击: ($safeX, $safeY), 持续时间: $duration")
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun onMouseInput(mask: Int, _x: Int, _y: Int) {
        // 新增：鼠标输入前先判断锁屏状态，若锁屏则先解锁，解锁后再执行原操作
        if (isScreenLocked()) {
            Log.d(logTag, "屏幕已锁定，先执行解锁，解锁后再处理鼠标操作")
            unlockScreen {
                onMouseInput(mask, _x, _y)
            }
            return
        }

        val x = max(0, _x)
        val y = max(0, _y)

        if (mask == 0 || mask == LEFT_MOVE) {
            val oldX = mouseX
            val oldY = mouseY
            mouseX = (x * SCREEN_INFO.scale).toInt() // 修复：添加toInt()避免类型错误
            mouseY = (y * SCREEN_INFO.scale).toInt()
            if (isWaitingLongPress) {
                val delta = abs(oldX - mouseX) + abs(oldY - mouseY)
                Log.d(logTag,"delta:$delta")
                if (delta > 8) {
                    isWaitingLongPress = false
                }
            }
        }

        // left button down, was up
        if (mask == LEFT_DOWN) {
            isWaitingLongPress = true
            timer.schedule(object : TimerTask() {
                override fun run() {
                    if (isWaitingLongPress) {
                        isWaitingLongPress = false
                        continueGesture(mouseX, mouseY)
                    }
                }
            }, longPressDuration)

            leftIsDown = true
            startGesture(mouseX, mouseY)
            return
        }

        // left down, was down
        if (leftIsDown) {
            continueGesture(mouseX, mouseY)
        }

        // left up, was down
        if (mask == LEFT_UP) {
            if (leftIsDown) {
                leftIsDown = false
                isWaitingLongPress = false
                endGesture(mouseX, mouseY)
                return
            }
        }

        if (mask == RIGHT_UP) {
            longPress(mouseX, mouseY)
            return
        }

        if (mask == BACK_UP) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        // long WHEEL_BUTTON_DOWN -> GLOBAL_ACTION_RECENTS
        if (mask == WHEEL_BUTTON_DOWN) {
            timer.purge()
            recentActionTask = object : TimerTask() {
                override fun run() {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    recentActionTask = null
                }
            }
            timer.schedule(recentActionTask, LONG_TAP_DELAY)
        }

        // wheel button up
        if (mask == WHEEL_BUTTON_UP) {
            if (recentActionTask != null) {
                recentActionTask!!.cancel()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            return
        }

        if (mask == WHEEL_DOWN) {
            if (mouseY < WHEEL_STEP) {
                return
            }
            val path = Path()
            path.moveTo(mouseX.toFloat(), mouseY.toFloat())
            path.lineTo(mouseX.toFloat(), (mouseY - WHEEL_STEP).toFloat())
            val stroke = GestureDescription.StrokeDescription(
                path,
                0,
                WHEEL_DURATION
            )
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            wheelActionsQueue.offer(builder.build())
            consumeWheelActions()

        }

        if (mask == WHEEL_UP) {
            if (mouseY < WHEEL_STEP) {
                return
            }
            val path = Path()
            path.moveTo(mouseX.toFloat(), mouseY.toFloat())
            path.lineTo(mouseX.toFloat(), (mouseY + WHEEL_STEP).toFloat())
            val stroke = GestureDescription.StrokeDescription(
                path,
                0,
                WHEEL_DURATION
            )
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            wheelActionsQueue.offer(builder.build())
            consumeWheelActions()
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun onTouchInput(mask: Int, _x: Int, _y: Int) {
        // 新增：触摸输入前先判断锁屏状态，若锁屏则先解锁，解锁后再执行原操作
        if (isScreenLocked()) {
            Log.d(logTag, "屏幕已锁定，先执行解锁，解锁后再处理触摸操作")
            unlockScreen {
                onTouchInput(mask, _x, _y)
            }
            return
        }

        when (mask) {
            TOUCH_PAN_UPDATE -> {
                mouseX -= (_x * SCREEN_INFO.scale).toInt() // 修复：添加toInt()
                mouseY -= (_y * SCREEN_INFO.scale).toInt()
                mouseX = max(0, mouseX);
                mouseY = max(0, mouseY);
                continueGesture(mouseX, mouseY)
            }
            TOUCH_PAN_START -> {
                mouseX = (max(0, _x) * SCREEN_INFO.scale).toInt() // 修复：添加toInt()
                mouseY = (max(0, _y) * SCREEN_INFO.scale).toInt()
                startGesture(mouseX, mouseY)
            }
            TOUCH_PAN_END -> {
                endGesture(mouseX, mouseY)
                mouseX = (max(0, _x) * SCREEN_INFO.scale).toInt() // 修复：添加toInt()
                mouseY = (max(0, _y) * SCREEN_INFO.scale).toInt()
            }
            else -> {}
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun consumeWheelActions() {
        if (isWheelActionsPolling) {
            return
        } else {
            isWheelActionsPolling = true
        }
        wheelActionsQueue.poll()?.let {
            dispatchGesture(it, null, null)
            timer.purge()
            timer.schedule(object : TimerTask() {
                override fun run() {
                    isWheelActionsPolling = false
                    consumeWheelActions()
                }
            }, WHEEL_DURATION + 10)
        } ?: let {
            isWheelActionsPolling = false
            return
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun performClick(x: Int, y: Int, duration: Long) {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        try {
            val longPressStroke = GestureDescription.StrokeDescription(path, 0, duration)
            val builder = GestureDescription.Builder()
            builder.addStroke(longPressStroke)
            Log.d(logTag, "performClick x:$x y:$y time:$duration")
            dispatchGesture(builder.build(), null, null)
        } catch (e: Exception) {
            Log.e(logTag, "performClick, error:$e")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun longPress(x: Int, y: Int) {
        performClick(x, y, longPressDuration)
    }

    private fun startGesture(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            touchPath.reset()
        } else {
            touchPath = Path()
        }
        touchPath.moveTo(x.toFloat(), y.toFloat())
        lastTouchGestureStartTime = System.currentTimeMillis()
        lastX = x
        lastY = y
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun doDispatchGesture(x: Int, y: Int, willContinue: Boolean) {
        touchPath.lineTo(x.toFloat(), y.toFloat())
        var duration = System.currentTimeMillis() - lastTouchGestureStartTime
        if (duration <= 0) {
            duration = 1
        }
        try {
            if (stroke == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    stroke = GestureDescription.StrokeDescription(
                        touchPath,
                        0,
                        duration,
                        willContinue
                    )
                } else {
                    stroke = GestureDescription.StrokeDescription(
                        touchPath,
                        0,
                        duration
                    )
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    stroke = stroke?.continueStroke(touchPath, 0, duration, willContinue)
                } else {
                    stroke = null
                    stroke = GestureDescription.StrokeDescription(
                        touchPath,
                        0,
                        duration
                    )
                }
            }
            stroke?.let {
                val builder = GestureDescription.Builder()
                builder.addStroke(it)
                Log.d(logTag, "doDispatchGesture x:$x y:$y time:$duration")
                dispatchGesture(builder.build(), null, null)
            }
        } catch (e: Exception) {
            Log.e(logTag, "doDispatchGesture, willContinue:$willContinue, error:$e")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun continueGesture(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            doDispatchGesture(x, y, true)
            touchPath.reset()
            touchPath.moveTo(x.toFloat(), y.toFloat())
            lastTouchGestureStartTime = System.currentTimeMillis()
            lastX = x
            lastY = y
        } else {
            touchPath.lineTo(x.toFloat(), y.toFloat())
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun endGestureBelowO(x: Int, y: Int) {
        try {
            touchPath.lineTo(x.toFloat(), y.toFloat())
            var duration = System.currentTimeMillis() - lastTouchGestureStartTime
            if (duration <= 0) {
                duration = 1
            }
            val stroke = GestureDescription.StrokeDescription(
                touchPath,
                0,
                duration
            )
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            Log.d(logTag, "end gesture x:$x y:$y time:$duration")
            dispatchGesture(builder.build(), null, null)
        } catch (e: Exception) {
            Log.e(logTag, "endGesture error:$e")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun endGesture(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            doDispatchGesture(x, y, false)
            touchPath.reset()
            stroke = null
        } else {
            endGestureBelowO(x, y)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun onKeyEvent(data: ByteArray) {
        // 新增：键盘输入前先判断锁屏状态，若锁屏则先解锁，解锁后再执行原操作
        if (isScreenLocked()) {
            Log.d(logTag, "屏幕已锁定，先执行解锁，解锁后再处理键盘操作")
            unlockScreen {
                onKeyEvent(data)
            }
            return
        }

        val keyEvent = KeyEvent.parseFrom(data)
        val keyboardMode = keyEvent.getMode()

        var textToCommit: String? = null

        // [down] indicates the key's state(down or up).
        // [press] indicates a click event(down and up).
        // https://github.com/rustdesk/rustdesk/blob/3a7594755341f023f56fa4b6a43b60d6b47df88d/flutter/lib/models/input_model.dart#L688
        if (keyEvent.hasSeq()) {
            textToCommit = keyEvent.getSeq()
        } else if (keyboardMode == KeyboardMode.Legacy) {
            if (keyEvent.hasChr() && (keyEvent.getDown() || keyEvent.getPress())) {
                val chr = keyEvent.getChr()
                if (chr != null) {
                    textToCommit = String(Character.toChars(chr))
                }
            }
        } else if (keyboardMode == KeyboardMode.Translate) {
        } else {
        }

        Log.d(logTag, "onKeyEvent $keyEvent textToCommit:$textToCommit")

        var ke: KeyEventAndroid? = null
        if (Build.VERSION.SDK_INT < 33 || textToCommit == null) {
            ke = KeyEventConverter.toAndroidKeyEvent(keyEvent)
        }
        ke?.let { event ->
            if (tryHandleVolumeKeyEvent(event)) {
                return
            } else if (tryHandlePowerKeyEvent(event)) {
                return
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            getInputMethod()?.let { inputMethod ->
                inputMethod.getCurrentInputConnection()?.let { inputConnection ->
                    if (textToCommit != null) {
                        textToCommit?.let { text ->
                            inputConnection.commitText(text, 1, null)
                        }
                    } else {
                        ke?.let { event ->
                            inputConnection.sendKeyEvent(event)
                            if (keyEvent.getPress()) {
                                val actionUpEvent = KeyEventAndroid(KeyEventAndroid.ACTION_UP, event.keyCode)
                                inputConnection.sendKeyEvent(actionUpEvent)
                            }
                        }
                    }
                }
            }
        } else {
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                ke?.let { event ->
                    val possibleNodes = possibleAccessibiltyNodes()
                    Log.d(logTag, "possibleNodes:$possibleNodes")
                    for (item in possibleNodes) {
                        val success = trySendKeyEvent(event, item, textToCommit)
                        if (success) {
                            if (keyEvent.getPress()) {
                                val actionUpEvent = KeyEventAndroid(KeyEventAndroid.ACTION_UP, event.keyCode)
                                trySendKeyEvent(actionUpEvent, item, textToCommit)
                            }
                            break
                        }
                    }
                }
            }
        }
    }

    private fun tryHandleVolumeKeyEvent(event: KeyEventAndroid): Boolean {
        when (event.keyCode) {
            KeyEventAndroid.KEYCODE_VOLUME_UP -> {
                if (event.action == KeyEventAndroid.ACTION_DOWN) {
                    volumeController.raiseVolume(null, true, AudioManager.STREAM_SYSTEM)
                }
                return true
            }
            KeyEventAndroid.KEYCODE_VOLUME_DOWN -> {
                if (event.action == KeyEventAndroid.ACTION_DOWN) {
                    volumeController.lowerVolume(null, true, AudioManager.STREAM_SYSTEM)
                }
                return true
            }
            KeyEventAndroid.KEYCODE_VOLUME_MUTE -> {
                if (event.action == KeyEventAndroid.ACTION_DOWN) {
                    volumeController.toggleMute(true, AudioManager.STREAM_SYSTEM)
                }
                return true
            }
            else -> {
                return false
            }
        }
    }

    private fun tryHandlePowerKeyEvent(event: KeyEventAndroid): Boolean {
        if (event.keyCode == KeyEventAndroid.KEYCODE_POWER) {
            // Perform power dialog action when action is up
            if (event.action == KeyEventAndroid.ACTION_UP) {
                performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
            }
            return true
        }
        return false
    }

    private fun insertAccessibilityNode(list: LinkedList<AccessibilityNodeInfo>, node: AccessibilityNodeInfo) {
        if (node == null) {
            return
        }
        if (list.contains(node)) {
            return
        }
        list.add(node)
    }

    private fun findChildNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) {
            return null
        }
        if (node.isEditable() && node.isFocusable()) {
            return node
        }
        val childCount = node.getChildCount()
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (child.isEditable() && child.isFocusable()) {
                    return child
                }
                if (Build.VERSION.SDK_INT < 33) {
                    child.recycle()
                }
            }
        }
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val result = findChildNode(child)
                if (Build.VERSION.SDK_INT < 33) {
                    if (child != result) {
                        child.recycle()
                    }
                }
                if (result != null) {
                    return result
                }
            }
        }
        return null
    }

    private fun possibleAccessibiltyNodes(): LinkedList<AccessibilityNodeInfo> {
        val linkedList = LinkedList<AccessibilityNodeInfo>()
        val latestList = LinkedList<AccessibilityNodeInfo>()

        val focusInput = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        var focusAccessibilityInput = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

        val rootInActiveWindow = getRootInActiveWindow()

        Log.d(logTag, "focusInput:$focusInput focusAccessibilityInput:$focusAccessibilityInput rootInActiveWindow:$rootInActiveWindow")

        if (focusInput != null) {
            if (focusInput.isFocusable() && focusInput.isEditable()) {
                insertAccessibilityNode(linkedList, focusInput)
            } else {
                insertAccessibilityNode(latestList, focusInput)
            }
        }

        if (focusAccessibilityInput != null) {
            if (focusAccessibilityInput.isFocusable() && focusAccessibilityInput.isEditable()) {
                insertAccessibilityNode(linkedList, focusAccessibilityInput)
            } else {
                insertAccessibilityNode(latestList, focusAccessibilityInput)
            }
        }

        val childFromFocusInput = findChildNode(focusInput)
        Log.d(logTag, "childFromFocusInput:$childFromFocusInput")

        if (childFromFocusInput != null) {
            insertAccessibilityNode(linkedList, childFromFocusInput)
        }

        val childFromFocusAccessibilityInput = findChildNode(focusAccessibilityInput)
        if (childFromFocusAccessibilityInput != null) {
            insertAccessibilityNode(linkedList, childFromFocusAccessibilityInput)
        }
        Log.d(logTag, "childFromFocusAccessibilityInput:$childFromFocusAccessibilityInput")

        if (rootInActiveWindow != null) {
            insertAccessibilityNode(linkedList, rootInActiveWindow)
        }

        for (item in latestList) {
            insertAccessibilityNode(linkedList, item)
        }

        return linkedList
    }

    private fun trySendKeyEvent(event: KeyEventAndroid, node: AccessibilityNodeInfo, textToCommit: String?): Boolean {
        node.refresh()
        this.fakeEditTextForTextStateCalculation?.setSelection(0,0)
        this.fakeEditTextForTextStateCalculation?.setText(null)

        val text = node.getText()
        var isShowingHint = false
        if (Build.VERSION.SDK_INT >= 26) {
            isShowingHint = node.isShowingHintText()
        }

        var textSelectionStart = node.textSelectionStart
        var textSelectionEnd = node.textSelectionEnd

        if (text != null) {
            if (textSelectionStart > text.length) {
                textSelectionStart = text.length
            }
            if (textSelectionEnd > text.length) {
                textSelectionEnd = text.length
            }
            if (textSelectionStart > textSelectionEnd) {
                textSelectionStart = textSelectionEnd
            }
        }

        var success = false

        Log.d(logTag, "existing text:$text textToCommit:$textToCommit textSelectionStart:$textSelectionStart textSelectionEnd:$textSelectionEnd")

        if (textToCommit != null) {
            if ((textSelectionStart == -1) || (textSelectionEnd == -1)) {
                val newText = textToCommit
                this.fakeEditTextForTextStateCalculation?.setText(newText)
                success = updateTextForAccessibilityNode(node)
            } else if (text != null) {
                this.fakeEditTextForTextStateCalculation?.setText(text)
                this.fakeEditTextForTextStateCalculation?.setSelection(
                    textSelectionStart,
                    textSelectionEnd
                )
                this.fakeEditTextForTextStateCalculation?.text?.insert(textSelectionStart, textToCommit)
                success = updateTextAndSelectionForAccessibiltyNode(node)
            }
        } else {
            if (isShowingHint) {
                this.fakeEditTextForTextStateCalculation?.setText(null)
            } else {
                this.fakeEditTextForTextStateCalculation?.setText(text)
            }
            if (textSelectionStart != -1 && textSelectionEnd != -1) {
                Log.d(logTag, "setting selection $textSelectionStart $textSelectionEnd")
                this.fakeEditTextForTextStateCalculation?.setSelection(
                    textSelectionStart,
                    textSelectionEnd
                )
            }

            this.fakeEditTextForTextStateCalculation?.let {
                // This is essiential to make sure layout object is created. OnKeyDown may not work if layout is not created.
                val rect = Rect()
                node.getBoundsInScreen(rect)

                it.layout(rect.left, rect.top, rect.right, rect.bottom)
                it.onPreDraw()
                if (event.action == KeyEventAndroid.ACTION_DOWN) {
                    val succ = it.onKeyDown(event.getKeyCode(), event)
                    Log.d(logTag, "onKeyDown $succ")
                } else if (event.action == KeyEventAndroid.ACTION_UP) {
                    val success = it.onKeyUp(event.getKeyCode(), event)
                    Log.d(logTag, "keyup $success")
                } else {}
            }

            success = updateTextAndSelectionForAccessibiltyNode(node)
        }
        return success
    }

    fun updateTextForAccessibilityNode(node: AccessibilityNodeInfo): Boolean {
        var success = false
        this.fakeEditTextForTextStateCalculation?.text?.let {
            val arguments = Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                it.toString()
            )
            success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return success
    }

    fun updateTextAndSelectionForAccessibiltyNode(node: AccessibilityNodeInfo): Boolean {
        var success = updateTextForAccessibilityNode(node)

        if (success) {
            val selectionStart = this.fakeEditTextForTextStateCalculation?.selectionStart
            val selectionEnd = this.fakeEditTextForTextStateCalculation?.selectionEnd

            if (selectionStart != null && selectionEnd != null) {
                val arguments = Bundle()
                arguments.putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                    selectionStart
                )
                arguments.putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                    selectionEnd
                )
                success = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
                Log.d(logTag, "Update selection to $selectionStart $selectionEnd success:$success")
            }
        }

        return success
    }


    override fun onAccessibilityEvent(event: AccessibilityEvent) {
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ctx = this
        val info = AccessibilityServiceInfo()
        if (Build.VERSION.SDK_INT >= 33) {
            info.flags = FLAG_INPUT_METHOD_EDITOR or FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        } else {
            info.flags = FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        setServiceInfo(info)
        fakeEditTextForTextStateCalculation = EditText(this)
        // Size here doesn't matter, we won't show this view.
        fakeEditTextForTextStateCalculation?.layoutParams = LayoutParams(100, 100)
        fakeEditTextForTextStateCalculation?.onPreDraw()
        val layout = fakeEditTextForTextStateCalculation?.getLayout()
        Log.d(logTag, "fakeEditTextForTextStateCalculation layout:$layout")
        Log.d(logTag, "onServiceConnected!")
    }

    override fun onDestroy() {
        ctx = null
        super.onDestroy()
    }

    override fun onInterrupt() {}
}

// ========== 补全缺失的VolumeController类（核心修复） ==========
class VolumeController(private val audioManager: AudioManager) {
    fun raiseVolume(streamType: Int?, showUi: Boolean, defaultStream: Int) {
        val stream = streamType ?: defaultStream
        audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, if (showUi) AudioManager.FLAG_SHOW_UI else 0)
    }

    fun lowerVolume(streamType: Int?, showUi: Boolean, defaultStream: Int) {
        val stream = streamType ?: defaultStream
        audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, if (showUi) AudioManager.FLAG_SHOW_UI else 0)
    }

    fun toggleMute(showUi: Boolean, defaultStream: Int) {
        val stream = defaultStream
        val currentVolume = audioManager.getStreamVolume(stream)
        val maxVolume = audioManager.getStreamMaxVolume(stream)
        
        if (currentVolume > 0) {
            audioManager.setStreamVolume(stream, 0, if (showUi) AudioManager.FLAG_SHOW_UI else 0)
        } else {
            audioManager.setStreamVolume(stream, maxVolume / 2, if (showUi) AudioManager.FLAG_SHOW_UI else 0)
        }
    }
}
