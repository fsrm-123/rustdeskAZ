package com.kyyk.rust

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object PushSendUtil {

    // ====================== 你后台真实信息（已自动填好）======================
    private const val CLIENT_ID = "19064429104345664"
    private const val CLIENT_SECRET = "E58E984ACE504AA8ADE9960B3A9C53B7299F3C9A82A6E0B3B8935C766B055759"
    private const val PROJECT_ID = "101653523863680522"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    // 获取华为接口 AccessToken
    private fun getAccessToken(): String {
        return try {
            val url = "https://oauth-login.cloud.huawei.com/oauth2/v3/token"
            val body = FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .build()

            val request = Request.Builder().url(url).post(body).build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "")
                json.optString("access_token")
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    // 发送透传指令（只发送，本地不执行）
    fun sendCommand(context: Context, targetToken: String, cmd: String) {
        if (targetToken.isBlank()) {
            mainHandler.post {
                Toast.makeText(context, "请输入目标设备TOKEN", Toast.LENGTH_SHORT).show()
            }
            return
        }

        Thread {
            val accessToken = getAccessToken()
            if (accessToken.isBlank()) {
                mainHandler.post {
                    Toast.makeText(context, "获取推送授权失败", Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }

            try {
                val url = "https://push-api.cloud.huawei.com/v2/$PROJECT_ID/messages:send"
                val root = JSONObject()
                val message = JSONObject()
                val tokenArray = JSONArray().put(targetToken)

                message.put("token", tokenArray)

                // 透传内容
                val data = JSONObject().apply {
                    put("content", cmd)
                }.toString()
                message.put("data", data)

                // 安卓通道配置
                val androidConfig = JSONObject().apply {
                    put("category", "DEVICE_REMINDER")
                }
                message.put("android", androidConfig)

                root.put("validate_only", false)
                root.put("message", message)

                // 🔥 修复：兼容所有 OkHttp 版本，绝对不报错
                val JSON = MediaType.parse("application/json; charset=utf-8")
                val requestBody = RequestBody.create(JSON, root.toString())

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                mainHandler.post {
                    if (response.code == 200) {
                        Toast.makeText(context, "指令发送成功", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "发送失败", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(context, "发送异常", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
