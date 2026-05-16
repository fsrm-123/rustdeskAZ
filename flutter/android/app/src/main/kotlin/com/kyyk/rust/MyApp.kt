package com.kyyk.rust

import android.app.Application
import android.util.Log
import com.huawei.hms.aaid.HmsInstanceId
import com.huawei.hms.api.HuaweiApiAvailability
import com.huawei.hms.common.ApiException

class MyApp : Application() {

    companion object {
        private const val TAG = "HMS_PUSH"
        private const val HUAWEI_APP_ID = "117721019"

        var localPushToken: String = ""
            private set

        fun updateToken(token: String) {
            localPushToken = token
            Log.i(TAG, "本机华为推送Token更新: $token")
        }
    }

    override fun onCreate() {
        super.onCreate()
        initHmsToken()
    }

    private fun initHmsToken() {
        Thread {
            try {
                val available = HuaweiApiAvailability.getInstance()
                    .isHuaweiMobileServicesAvailable(this)

                if (available != com.huawei.hms.api.ConnectionResult.SUCCESS) {
                    Log.e(TAG, "HMS 不可用")
                    return@Thread
                }

                val token = HmsInstanceId.getInstance(this).getToken(HUAWEI_APP_ID, "HCM")
                if (!token.isNullOrEmpty()) {
                    updateToken(token)
                }

            } catch (e: ApiException) {
                Log.e(TAG, "获取华为Token失败: ${e.message}")
            }
        }.start()
    }
}
