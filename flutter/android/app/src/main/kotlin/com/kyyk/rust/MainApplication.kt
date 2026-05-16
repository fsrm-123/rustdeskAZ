/*
package com.kyyk.rust

import android.app.Application
import android.util.Log
import ffi.FFI

class MainApplication : Application() {
    companion object {
        private const val TAG = "MainApplication"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "App start")
        FFI.onAppStart(applicationContext)
    }
}
*/
package com.kyyk.rust

import android.app.Application
import android.util.Log
import ffi.FFI

// 👇 新增：华为推送必需导入
import com.huawei.hms.aaid.HmsInstanceId
import com.huawei.hms.api.HuaweiApiAvailability
import com.huawei.hms.common.ApiException

class MainApplication : Application() {
    companion object {
        private const val TAG = "MainApplication"
        
        // 👇 新增：华为推送配置（使用你后台真实信息）
        const val HUAWEI_APP_ID = "117666823"
        var localHmsToken = ""
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "App start")
        FFI.onAppStart(applicationContext)

        // 👇 新增：只加这一行 → 初始化华为推送
        initHmsPush()
    }

    // 👇 新增：华为推送初始化方法
    private fun initHmsPush() {
        Thread {
            try {
                val available = HuaweiApiAvailability.getInstance()
                    .isHuaweiMobileServicesAvailable(this)
                
                if (available != com.huawei.hms.api.ConnectionResult.SUCCESS) {
                    Log.i("HMS", "HMS 不可用")
                    return@Thread
                }

                val token = HmsInstanceId.getInstance(this).getToken(HUAWEI_APP_ID, "HCM")
                if (!token.isNullOrEmpty()) {
                    localHmsToken = token
                    Log.i("HMS", "获取华为Token成功: $token")
                }

            } catch (e: ApiException) {
                Log.e("HMS", "获取Token失败", e)
            }
        }.start()
    }
}
