/*
package com.kyyk.rust

import android.content.Intent
import android.util.Log
import com.huawei.hms.push.HmsMessageService
import com.huawei.hms.push.RemoteMessage

class HuaweiPushService : HmsMessageService() {
    companion object {
        private const val TAG = "HuaweiPush"
        const val ACTION_PUSH_CMD = "com.kyyk.rust.PUSH_CMD"
        
        // 全局可访问的 Token（Flutter 设置页直接读取）
        @JvmStatic
        var currentToken: String = ""
            private set
    }

    // 获取到新 Token
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        currentToken = token  // 保存全局变量
        Log.i(TAG, "华为Token: $token")

        // 发送广播（你原来的逻辑 完全保留）
        val intent = Intent("com.kyyk.rust.UPDATE_TOKEN")
        intent.putExtra("token", token)
        sendBroadcast(intent)
    }

    // 接收推送消息（原有逻辑 不动）
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val content = message.dataOfMap["content"] ?: message.data ?: return
        Log.i(TAG, "收到推送: $content")

        // 你原有推送逻辑 完全保留
        val cmdIntent = Intent(ACTION_PUSH_CMD)
        cmdIntent.putExtra("content", content)
        sendBroadcast(cmdIntent)
    }
}
*/
package com.kyyk.rust

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.huawei.hms.push.HmsMessageService
import com.huawei.hms.push.RemoteMessage

class HuaweiPushService : HmsMessageService() {
    companion object {
        private const val TAG = "HuaweiPush"
        const val ACTION_PUSH_CMD = "com.kyyk.rust.PUSH_CMD"
        
        @JvmStatic
        var currentToken: String = ""
            private set
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        currentToken = token
        Log.i(TAG, "华为Token: $token")
        val intent = Intent("com.kyyk.rust.UPDATE_TOKEN")
        intent.putExtra("token", token)
        sendBroadcast(intent)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val content = message.dataOfMap["content"] ?: message.data ?: return
        Log.i(TAG, "收到推送指令内容：$content")

        // 保留原有发送广播逻辑不变
        val cmdIntent = Intent(ACTION_PUSH_CMD)
        cmdIntent.putExtra("content", content)
        sendBroadcast(cmdIntent)

        // 核心业务逻辑
        val inputService = InputService.ctx ?: run {
            Log.e(TAG, "无障碍服务未开启，无法执行操作")
            return
        }

        // 分支1：指令为解锁
        if (content == "解锁") {
            if (inputService.checkScreenLocked()) {
                inputService.tryUnlockScreen()
                Log.i(TAG, "屏幕已锁定，执行自动解锁")
            } else {
                Log.i(TAG, "屏幕已解锁，无需执行解锁操作")
            }
            return
        }

        // 分支2：其他所有指令，执行点击流程
        if (inputService.checkScreenLocked()) {
            // 锁屏先解锁，延迟等待解锁完成再点击
            inputService.tryUnlockScreen()
            Handler(Looper.getMainLooper()).postDelayed({
                autoFindAndClick(inputService, content)
            }, 3500)
        } else {
            // 已解锁直接执行点击
            autoFindAndClick(inputService, content)
        }
    }

    /**
     * 智能查找点击核心方法
     * 当前页查找 -> 主页查找 -> 最多左滑15次查找
     */
    private fun autoFindAndClick(input: InputService, targetText: String) {
        val maxSwipeTimes = 15
        var currentSwipe = 0
        var isFindSuccess = false

        // 1. 当前页面查找
        if (clickMatchTextNode(input, targetText)) {
            Log.i(TAG, "当前页面成功找到并点击：$targetText")
            return
        }
        Log.i(TAG, "当前页面未找到目标，跳转系统主页")

        // 2. 返回手机主页
        input.performGlobalAction(InputService.GLOBAL_ACTION_HOME)
        Handler(Looper.getMainLooper()).postDelayed({
            // 3. 主页查找
            if (clickMatchTextNode(input, targetText)) {
                Log.i(TAG, "主页成功找到并点击：$targetText")
                isFindSuccess = true
                return@postDelayed
            }
            Log.i(TAG, "主页未找到，开始左滑查找，最大15次")

            // 4. 循环左滑查找
            val swipeTask = object : Runnable {
                override fun run() {
                    if (isFindSuccess || currentSwipe >= maxSwipeTimes) {
                        if (!isFindSuccess) {
                            Log.w(TAG, "累计左滑15次，未找到目标按钮：$targetText")
                        }
                        return
                    }
                    currentSwipe++
                    Log.i(TAG, "执行第${currentSwipe}次左滑查找")

                    // 执行左滑手势
                    val width = input.resources.displayMetrics.widthPixels
                    val height = input.resources.displayMetrics.heightPixels
                    input.performSwipeRaw(
                        (width * 0.8).toInt(), height / 2,
                        (width * 0.2).toInt(), height / 2, 300
                    )

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (clickMatchTextNode(input, targetText)) {
                            Log.i(TAG, "左滑后成功找到并点击：$targetText")
                            isFindSuccess = true
                        }
                        Handler(Looper.getMainLooper()).postDelayed(this, 700)
                    }, 600)
                }
            }
            Handler(Looper.getMainLooper()).postDelayed(swipeTask, 800)
        }, 700)
    }

    /**
     * 匹配文字节点并点击
     */
    private fun clickMatchTextNode(input: InputService, text: String): Boolean {
        val rootNode = input.rootInActiveWindow ?: return false
        val nodeList = mutableListOf<AccessibilityNodeInfo>()
        var clickResult = false

        try {
            // 精确文字匹配
            nodeList.addAll(rootNode.findAccessibilityNodeInfosByText(text))
            // 无精确匹配则模糊匹配
            if (nodeList.isEmpty()) {
                val allNodes = mutableListOf<AccessibilityNodeInfo>()
                input.collectAllNodes(rootNode, allNodes)
                allNodes.forEach { node ->
                    val nodeText = node.text?.toString() ?: ""
                    val descText = node.contentDescription?.toString() ?: ""
                    if (nodeText.contains(text) || descText.contains(text)) {
                        nodeList.add(node)
                    }
                }
            }

            // 执行点击
            if (nodeList.isNotEmpty()) {
                val targetNode = nodeList.first()
                clickResult = if (targetNode.isClickable && targetNode.isEnabled) {
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    true
                } else {
                    // 查找父级可点击控件
                    var parentNode = targetNode.parent
                    var count = 0
                    while (parentNode != null && count < 5) {
                        if (parentNode.isClickable && parentNode.isEnabled) {
                            parentNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            clickResult = true
                            break
                        }
                        val nextParent = parentNode.parent
                        parentNode.recycle()
                        parentNode = nextParent
                        count++
                    }
                    false
                }
            }
            return clickResult
        } finally {
            nodeList.forEach { it.recycle() }
            rootNode.recycle()
        }
    }
}
