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

const val SWIPE_UP_DELAY = 1000L
const val INPUT_DELAY = 2000L
const val DIGIT_INPUT_INTERVAL = 150L
const val UNLOCK_CHECK_DELAY = 8000L

private const val SHARED_PREFS_NAME = "flutter_shared_preferences"
private const val PREFS_KEY_UNLOCK_PASSWORD = "unlock_password"

// ========== 修复：使用object声明，与common.kt保持一致，避免重复定义 ==========
// 删除这里的重复定义，改为引用common.kt中的COMMON_SCREEN_INFO
// 或者如果确实需要不同的实例，使用不同的名称如INPUT_SCREEN_INFO

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
    private val longPressDuration = ViewConfiguration.getTapTimeout().toLong() + ViewConfiguration.getLongPressTimeout().toLong()

    private val wheelActionsQueue = LinkedList<GestureDescription>()
    private var isWheelActionsPolling = false
    private var isWaitingLongPress = false

    private var fakeEditTextForTextStateCalculation: EditText? = null

    private var lastX = 0
    private var lastY = 0

    // ========== 修复：使用导入的VolumeController，删除内部类定义 ==========
    private val volumeController: VolumeController by lazy { 
        VolumeController(applicationContext.getSystemService(AUDIO_SERVICE) as AudioManager) 
    }

    private var isUnlocking = false

    private fun getUnlockPassword(): String {
        try {
            val prefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
            val password = prefs.getString(PREFS_KEY_UNLOCK_PASSWORD, "") ?: ""
            
            if (password.isNotEmpty()) {
                Log.d(logTag, "从SharedPreferences API读取到解锁密码: ●●●●●●")
                return password
            }

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

    private fun isScreenLocked(): Boolean {
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
            
            val wakeLockTag = "${packageName}:InputServiceWakeLock"
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                wakeLockTag
            )
            wakeLock.acquire(15000)
            
            val mainHandler = Handler(Looper.getMainLooper())
            
            mainHandler.postDelayed({
                swipeUp()
                
                mainHandler.postDelayed({
                    val password = getUnlockPassword()
                    if (password.isNotEmpty()) {
                        inputPassword(password)
                    } else {
                        Log.d(logTag, "未设置解锁密码，跳过密码输入步骤")
                    }
                    
                    mainHandler.postDelayed({
                        val locked = isScreenLocked()
                        Log.d(logTag, if (!locked) "屏幕解锁成功" else "屏幕解锁失败")
                        if (wakeLock.isHeld) {
                            wakeLock.release()
                        }
                        isUnlocking = false
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

    private fun inputPassword(password: String) {
        Log.d(logTag, "开始输入密码: $password")
        
        val mainHandler = Handler(Looper.getMainLooper())
        for (i in password.indices) {
            val char = password[i]
            mainHandler.postDelayed({
                if (char in '0'..'9') {
                    clickDigit(char - '0')
                }
            }, i * DIGIT_INPUT_INTERVAL)
        }
        
        mainHandler.postDelayed({
            clickEnter()
        }, password.length * DIGIT_INPUT_INTERVAL)
    }

    private fun clickDigit(digit: Int) {
        Log.d(logTag, "点击数字: $digit")
        
        val rootNode = rootInActiveWindow ?: run {
            Log.e(logTag, "根节点为空，无法点击数字")
            return
        }
        
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
        
        if (targetNode == null) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(digit.toString())
            for (node in nodes) {
                if (node.isClickable) {
                    targetNode = node
                    break
                }
            }
        }
        
        targetNode?.let { node ->
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            performUnlockClick(bounds.centerX(), bounds.centerY(), 100)
            node.recycle()
        }
        
        rootNode.recycle()
    }

    private fun clickEnter() {
        Log.d(logTag, "尝试点击确认键")
        
        val rootNode = rootInActiveWindow ?: run {
            Log.e(logTag, "根节点为空，无法点击确认键")
            return
        }
        
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
        
        val displayMetrics = resources.displayMetrics
        val x = displayMetrics.widthPixels - 100
        val y = displayMetrics.heightPixels - 200
        performUnlockClick(x, y, 100)
        rootNode.recycle()
    }

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
        if (isScreenLocked()) {
            Log.d(logTag, "屏幕已锁定，先执行解锁，解锁后再处理鼠标操作")
            unlockScreen {
                onMouseInput(mask, _x, _y)
            }
            return
        }

        val x = max(0, _x)
        val y = max(0, _y)

        // ========== 修复：使用COMMON_SCREEN_INFO替代SCREEN_INFO ==========
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
            longPress(mouseX, mouseY)
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
        if (isScreenLocked()) {
            Log.d(logTag, "屏幕已锁定，先执行解锁，解锁后再处理触摸操作")
            unlockScreen {
                onTouchInput(mask, _x, _y)
            }
            return
        }

        when (mask) {
            TOUCH_PAN_UPDATE -> {
                // ========== 修复：使用COMMON_SCREEN_INFO替代SCREEN_INFO ==========
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
        val info = AccessibilityServiceInfo()
        if (Build.VERSION.SDK_INT >= 33) {
            info.flags = FLAG_INPUT_METHOD_EDITOR or FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        } else {
            info.flags = FLAG_RETRIEVE_INTERACTIVE_WINDOWS
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
        super.onDestroy()
    }

    override fun onInterrupt() {}
}
