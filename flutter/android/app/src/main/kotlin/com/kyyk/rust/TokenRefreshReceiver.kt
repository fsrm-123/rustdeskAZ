package com.kyyk.rust

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.MethodChannel

class TokenRefreshReceiver : BroadcastReceiver() {
    companion object {
        // 必须和 MainActivity 里的通道一致
        private const val CHANNEL = "com.kyyk.rust/huawei_token"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == "com.kyyk.rust.UPDATE_TOKEN") {
            val token = intent.getStringExtra("token") ?: ""

            // 刷新 Flutter 界面（真正生效的代码）
            try {
                val activity = context as? FlutterActivity
                activity?.let {
                    MethodChannel(it.flutterEngine?.dartExecutor?.binaryMessenger, CHANNEL)
                        .invokeMethod("onTokenRefreshed", token)
                }
            } catch (e: Exception) {
                // 页面未打开时不崩溃
            }
        }
    }
}
