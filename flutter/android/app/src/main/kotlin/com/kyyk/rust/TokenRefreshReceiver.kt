package com.kyyk.rust

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class TokenRefreshReceiver : BroadcastReceiver() {
    companion object {
        private const val CHANNEL = "com.kyyk.rust/huawei_token"
        
        // 🔥 关键改动：我们直接存 FlutterEngine，不存 Activity
        var flutterEngine: FlutterEngine? = null
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == "com.huawei.push.token.REFRESH") {
            val engine = flutterEngine ?: return
            val binaryMessenger = engine.dartExecutor.binaryMessenger

            MethodChannel(binaryMessenger, CHANNEL).invokeMethod("onTokenRefresh", null)
        }
    }
}
