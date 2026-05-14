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
