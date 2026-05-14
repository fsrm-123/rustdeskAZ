package com.kyyk.rust

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import io.flutter.embedding.android.FlutterActivity

class TokenDisplayManager {
    companion object {
        fun init(activity: FlutterActivity) {
            // 创建悬浮文本
            val tv = TextView(activity).apply {
                text = "获取华为Token中..."
                textSize = 10f
                setTextColor(Color.WHITE)
                setBackgroundColor(0xAA000000.toInt())
                setPadding(10, 10, 10, 10)
                isClickable = false
            }

            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.LEFT
                setMargins(10, 10, 10, 100)
            }

            val decorView = activity.window.decorView as ViewGroup
            decorView.addView(tv, params)

            // 监听Token更新广播
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val token = intent?.getStringExtra("token") ?: ""
                    tv.text = "华为推送Token:\n$token"
                }
            }

           val filter = IntentFilter("com.kyyk.rust.HMS_TOKEN")
            activity.registerReceiver(receiver, filter)
        }
    }
}
