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
import android.os.HandlerThread
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
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.annotation.SuppressLint
import android.content.SharedPreferences
import java.util.*
import java.lang.Character
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.max
import hbb.MessageOuterClass.KeyEvent
import hbb.MessageOuterClass.KeyboardMode
import hbb.KeyEventConverter
import java.io.File
import java.io.FileInputStream
import java.util.Properties

const val LEFT_DOWN = 9
const val LEFT_MOVE = 8
const val LEFT_UP = 10
const val RIGHT_UP = 18
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

// 解锁相关常量
const val SWIPE_UP_DELAY = 300L
const val INPUT_DELAY = 500L
const val DIGIT_INPUT_INTERVAL = 100L
const val UNLOCK_CHECK_DELAY = 1500L

// SharedPreferences 配置
private const val UNLOCK_PREFS_NAME = "rustdesk_unlock_config"
private const val PREFS_KEY_UNLOCK_PASSWORD = "screen_unlock_password"

class InputService : AccessibilityService() {

    companion object {
        var ctx: InputService? = null
        val isOpen: Boolean
            get() = ctx != null
            
        @JvmStatic
        fun performRemoteUnlock(password: String? = null) {
            ctx?.let { service ->
                val pwd = password ?: service.getUnlockPassword()
                service.unlockScreen(pwd, null)
            }
        }
        
        @JvmStatic
        fun isScreenLocked(): Boolean {
            return ctx?.checkScreenLocked() ?: false
        }
    }

    private val logTag = "InputService"
    private var leftIsDown = false
    private var touchPath = Path()
    private var stroke: GestureDescription.StrokeDescription? = null
    private var lastTouchGestureStartTime = 0L
    private var mouseX = 0
    private var mouseY = 0
    private var timer = Timer()
    private var recentActionTask: TimerTask? = null
    private val longPressDuration = ViewConfiguration.getTapTimeout().toLong() + 
                                     ViewConfiguration.getLongPressTimeout().toLong()

    private val wheelActionsQueue = LinkedList<GestureDescription>()
    private var isWheelActionsPolling = false
    private var isWaitingLongPress = false
    private var fakeEditTextForTextStateCalculation: EditText? = null
    private var lastX = 0
    private var lastY = 0

    private val volumeController: VolumeController by lazy { 
        VolumeController(applicationContext.getSystemService(AUDIO_SERVICE) as AudioManager) 
    }

    // ========== 解锁状态管理（线程安全） ==========
    // 使用 AtomicBoolean 确保线程安全的状态检查
    private val isUnlocking = AtomicBoolean(false)
    // 使用 Lock 确保解锁操作的互斥性
    private val unlockLock = ReentrantLock()
    private val unlockCondition = unlockLock.newCondition()
    // 标记本次会话是否已经解锁过（防止重复解锁）
    private var hasUnlockedInThisSession = false
    
    private lateinit var backgroundHandler: Handler
    private lateinit var backgroundThread: HandlerThread

    // ========== 密码管理 ==========
    fun saveUnlockPassword(password: String) {
        getSharedPreferences(UNLOCK_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFS_KEY_UNLOCK_PASSWORD, password)
            .apply()
        Log.d(logTag, "保存解锁密码: ${if (password.isNotEmpty()) "●●●●●●" else "空密码"}")
    }

    fun getUnlockPassword(): String {
        return getSharedPreferences(UNLOCK_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREFS_KEY_UNLOCK_PASSWORD, "") ?: ""
    }

    fun clearUnlockPassword() {
        getSharedPreferences(UNLOCK_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREFS_KEY_UNLOCK_PASSWORD)
            .apply()
        Log.d(logTag, "清除解锁密码")
    }

    // ========== 屏幕状态检测 ==========
    fun checkScreenLocked(): Boolean {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val isKeyguardLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            keyguardManager.isDeviceLocked()
        } else {
            keyguardManager.isKeyguardLocked()
        }
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isScreenOff = !powerManager.isInteractive
        
        return isKeyguardLocked || isScreenOff
    }

    // ========== 会话管理 ==========
    // 新会话开始时调用（控制端连接时）
    fun onSessionStarted() {
        unlockLock.withLock {
            hasUnlockedInThisSession = false
            Log.d(logTag, "新会话开始，重置解锁状态")
        }
    }

    // ========== 核心：线程安全的解锁机制 ==========
    
    /**
     * 确保屏幕已解锁。如果正在解锁中，则等待解锁完成。
     * 如果屏幕已解锁或本次会话已解锁过，直接返回。
     * 
     * @return true 表示屏幕已解锁（或原本就未锁定），可以继续操作
     *         false 表示解锁失败或无需解锁
     */
    private fun ensureUnlocked(): Boolean {
        // 快速路径：检查是否需要解锁
        if (!checkScreenLocked()) {
            return true // 屏幕未锁定，直接继续
        }
        
        // 检查是否已在本会话解锁过
        unlockLock.withLock {
            if (hasUnlockedInThisSession) {
                Log.d(logTag, "本次会话已解锁过，跳过")
                return true
            }
        }
        
        // 尝试获取解锁权限
        if (!isUnlocking.compareAndSet(false, true)) {
            // 其他方法正在解锁，等待其完成
            Log.d(logTag, "其他方法正在解锁，等待中...")
            unlockLock.withLock {
                while (isUnlocking.get()) {
                    try {
                        unlockCondition.awaitNanos(100_000_000) // 等待100ms
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
            }
            Log.d(logTag, "等待解锁完成，继续操作")
            return !checkScreenLocked() // 返回当前屏幕状态
        }
        
        // 获取到解锁权限，执行解锁
        try {
            val password = getUnlockPassword()
            Log.d(logTag, "获取解锁权限，开始解锁，密码: ${if (password.isNotEmpty()) "已设置" else "未设置"}")
            
            val unlockSuccess = performUnlockInternal(password)
            
            unlockLock.withLock {
                hasUnlockedInThisSession = true
            }
            
            return unlockSuccess
            
        } finally {
            // 释放解锁状态并通知等待的线程
            unlockLock.withLock {
                isUnlocking.set(false)
                unlockCondition.signalAll() // 唤醒所有等待的线程
            }
        }
    }
    
    /**
     * 内部解锁实现（同步执行）
     */
    private fun performUnlockInternal(password: String): Boolean {
        return try {
            wakeUpScreen()
            Thread.sleep(SWIPE_UP_DELAY)
            
            performSwipeUp()
            Thread.sleep(INPUT_DELAY)
            
            if (password.isNotEmpty()) {
                inputPassword(password)
                Thread.sleep(password.length * DIGIT_INPUT_INTERVAL + 300L)
            } else {
                Log.d(logTag, "无密码，跳过密码输入")
                // 等待系统响应
                Thread.sleep(800L)
            }
            
            val stillLocked = checkScreenLocked()
            if (stillLocked) {
                Log.w(logTag, "解锁后屏幕仍锁定，可能密码错误或解锁失败")
            } else {
                Log.d(logTag, "解锁成功")
            }
            !stillLocked
            
        } catch (e: Exception) {
            Log.e(logTag, "解锁过程异常: ${e.message}", e)
            false
        }
    }

    private fun wakeUpScreen() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "RustDesk:Unlock"
        )
        wakeLock.acquire(5000)
        Log.d(logTag, "点亮屏幕")
    }

    private fun performSwipeUp() {
        val displayMetrics = resources.displayMetrics
        val x = displayMetrics.widthPixels / 2
        val startY = (displayMetrics.heightPixels * 0.85).toInt()
        val endY = (displayMetrics.heightPixels * 0.15).toInt()
        
        val path = Path().apply {
            moveTo(x.toFloat(), startY.toFloat())
            lineTo(x.toFloat(), endY.toFloat())
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
            .build()
        
        dispatchGesture(gesture, null, null)
        Log.d(logTag, "执行上滑: ($x, $startY) -> ($x, $endY)")
    }

    private fun inputPassword(password: String) {
        Log.d(logTag, "输入密码，长度: ${password.length}")
        
        password.forEachIndexed { index, char ->
            backgroundHandler.postDelayed({
                when {
                    char in '0'..'9' -> clickDigit(char - '0')
                    char == '\n' || char == '\r' -> clickEnter()
                    else -> clickCharacter(char)
                }
            }, index * DIGIT_INPUT_INTERVAL)
        }
        
        // 最后点击确认
        backgroundHandler.postDelayed({
            clickEnter()
        }, password.length * DIGIT_INPUT_INTERVAL + 100L)
    }

    private fun clickDigit(digit: Int) {
        val rootNode = rootInActiveWindow ?: run {
            Log.e(logTag, "根节点为空，无法点击数字")
            return
        }
        
        val possibleIds = arrayOf(
            "com.android.systemui:id/key$digit",
            "com.android.keyguard:id:key$digit",
            "key$digit",
            "digit_$digit",
            "pin$digit"
        )
        
        for (id in possibleIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty() && nodes[0].isClickable) {
                performClick(nodes[0])
                nodes[0].recycle()
                rootNode.recycle()
                Log.d(logTag, "点击数字 $digit (ID: $id)")
                return
            }
            nodes.forEach { it.recycle() }
        }
        
        val textNodes = rootNode.findAccessibilityNodeInfosByText(digit.toString())
        for (node in textNodes) {
            if (node.isClickable && isPinKey(node)) {
                performClick(node)
                node.recycle()
                rootNode.recycle()
                Log.d(logTag, "点击数字 $digit (文本查找)")
                return
            }
            node.recycle()
        }
        
        rootNode.recycle()
    }

    private fun clickCharacter(char: Char) {
        val rootNode = rootInActiveWindow ?: return
        val textNodes = rootNode.findAccessibilityNodeInfosByText(char.toString())
        for (node in textNodes) {
            if (node.isClickable) {
                performClick(node)
                node.recycle()
                break
            }
            node.recycle()
        }
        rootNode.recycle()
    }

    private fun clickEnter() {
        val rootNode = rootInActiveWindow ?: run {
            Log.e(logTag, "根节点为空，无法点击确认")
            return
        }
        
        val confirmTexts = arrayOf("确定", "确认", "OK", "完成", "解锁", "下一步", "Enter", "↵")
        for (text in confirmTexts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) {
                    performClick(node)
                    node.recycle()
                    rootNode.recycle()
                    Log.d(logTag, "点击确认: $text")
                    return
                }
                node.recycle()
            }
        }
        
        // 备用方案：点击屏幕右下角
        val displayMetrics = resources.displayMetrics
        val x = displayMetrics.widthPixels * 0.8
        val y = displayMetrics.heightPixels * 0.9
        performClickRaw(x.toInt(), y.toInt(), 100)
        rootNode.recycle()
    }

    private fun performClick(node: AccessibilityNodeInfo) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        performClickRaw(bounds.centerX(), bounds.centerY(), 100)
    }

    private fun performClickRaw(x: Int, y: Int, duration: Long = 100) {
        val safeX = max(0, x).coerceAtMost(resources.displayMetrics.widthPixels - 1)
        val safeY = max(0, y).coerceAtMost(resources.displayMetrics.heightPixels - 1)
        
        val path = Path().apply {
            moveTo(safeX.toFloat(), safeY.toFloat())
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        
        dispatchGesture(gesture, null, null)
    }

    private fun isPinKey(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds.centerY() > resources.displayMetrics.heightPixels * 0.5
    }

    // ========== 远程输入处理（统一的解锁检查） ==========
    
    @RequiresApi(Build.VERSION_CODES.N)
    fun onMouseInput(mask: Int, _x: Int, _y: Int) {
        // 确保屏幕已解锁（线程安全，不会重复触发）
        if (!ensureUnlocked()) {
            Log.w(logTag, "屏幕锁定且解锁失败，跳过鼠标输入")
            return
        }

        val x = max(0, _x)
        val y = max(0, _y)

        if (mask == 0 || mask == LEFT_MOVE) {
            val oldX = mouseX
            val oldY = mouseY
            mouseX = (x * COMMON_SCREEN_INFO.scale).toInt()
            mouseY = (y * COMMON_SCREEN_INFO.scale).toInt()
            if (isWaitingLongPress) {
                val delta = abs(oldX - mouseX) + abs(oldY - mouseY)
                Log.d(logTag,"delta:$delta")
                if (delta > 8) {
                    isWaitingLongPress = false
                }
            }
        }

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

        if (leftIsDown) {
            continueGesture(mouseX, mouseY)
        }

        if (mask == LEFT_UP) {
            if (leftIsDown) {
                leftIsDown = false
                isWaitingLongPress = false
                endGesture(mouseX, mouseY)
                return
            }
        }

        if (mask == RIGHT_UP) {
            longPressRaw(mouseX, mouseY)
            return
        }

        if (mask == BACK_UP) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

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
        // 确保屏幕已解锁（线程安全，不会重复触发）
        if (!ensureUnlocked()) {
            Log.w(logTag, "屏幕锁定且解锁失败，跳过触摸输入")
            return
        }

        when (mask) {
            TOUCH_PAN_UPDATE -> {
                mouseX -= (_x * COMMON_SCREEN_INFO.scale).toInt()
                mouseY -= (_y * COMMON_SCREEN_INFO.scale).toInt()
                mouseX = max(0, mouseX);
                mouseY = max(0, mouseY);
                continueGesture(mouseX, mouseY)
            }
            TOUCH_PAN_START -> {
                mouseX = (max(0, _x) * COMMON_SCREEN_INFO.scale).toInt()
                mouseY = (max(0, _y) * COMMON_SCREEN_INFO.scale).toInt()
                startGesture(mouseX, mouseY)
            }
            TOUCH_PAN_END -> {
                endGesture(mouseX, mouseY)
                mouseX = (max(0, _x) * COMMON_SCREEN_INFO.scale).toInt()
                mouseY = (max(0, _y) * COMMON_SCREEN_INFO.scale).toInt()
            }
            else -> {}
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun onKeyEvent(data: ByteArray) {
        // 确保屏幕已解锁（线程安全，不会重复触发）
        if (!ensureUnlocked()) {
            Log.w(logTag, "屏幕锁定且解锁失败，跳过键盘输入")
            return
        }

        val keyEvent = KeyEvent.parseFrom(data)
        val keyboardMode = keyEvent.getMode()

        var textToCommit: String? = null

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

    private fun longPressRaw(x: Int, y: Int) {
        performClickRaw(x, y, longPressDuration)
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
        
        backgroundThread = HandlerThread("InputServiceBackground")
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
        
        // 重置会话状态
        unlockLock.withLock {
            hasUnlockedInThisSession = false
            isUnlocking.set(false)
        }
        
        val info = AccessibilityServiceInfo()
        if (Build.VERSION.SDK_INT >= 33) {
            info.flags = FLAG_INPUT_METHOD_EDITOR
        } else {
            info.flags = 0
        }
        setServiceInfo(info)
        
        fakeEditTextForTextStateCalculation = EditText(this)
        fakeEditTextForTextStateCalculation?.layoutParams = LayoutParams(100, 100)
        fakeEditTextForTextStateCalculation?.onPreDraw()
        val layout = fakeEditTextForTextStateCalculation?.getLayout()
        Log.d(logTag, "fakeEditTextForTextStateCalculation layout:$layout")
        Log.d(logTag, "onServiceConnected!")
    }

    override fun onDestroy() {
        ctx = null
        // 确保释放锁，避免死锁
        unlockLock.withLock {
            isUnlocking.set(false)
            unlockCondition.signalAll()
        }
        backgroundThread.quitSafely()
        super.onDestroy()
    }

    override fun onInterrupt() {}
}
