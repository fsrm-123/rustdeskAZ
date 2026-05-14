package com.kyyk.rust

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.MethodChannel

class TokenRefreshReceiver : BroadcastReceiver() {
    companion object {
        private const val CHANNEL = "com.kyyk.rust/huawei_token"
        var activity: FlutterActivity? = null
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == "com.huawei.push.token.REFRESH") {
            // 这里必须改名，不能用 activity = activity
            val currentActivity = activity ?: return
            val engine = currentActivity.flutterEngine ?: return
            val binaryMessenger = engine.dartExecutor.binaryMessenger

            MethodChannel(binaryMessenger, CHANNEL).invokeMethod("onTokenRefresh", null)
        }
    }
}
