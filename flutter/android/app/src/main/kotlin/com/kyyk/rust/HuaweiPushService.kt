package com.kyyk.rust

import android.content.Intent
import android.util.Log
import com.huawei.hms.push.HmsMessageService
import com.huawei.hms.push.RemoteMessage

class HuaweiPushService : HmsMessageService() {
    companion object {
        private const val TAG = "HuaweiPush"
        const val ACTION_PUSH_CMD = "com.kyyk.rust.PUSH_CMD"
        var currentToken: String = ""
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        currentToken = token
        Log.i(TAG, "华为Token: $token")

        // 发送广播给界面显示
        val intent = Intent("com.kyyk.rust.UPDATE_TOKEN")
        intent.putExtra("token", token)
        sendBroadcast(intent)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val content = message.dataOfMap["content"] ?: message.data ?: return
        Log.i(TAG, "收到推送: $content")
    }
}
