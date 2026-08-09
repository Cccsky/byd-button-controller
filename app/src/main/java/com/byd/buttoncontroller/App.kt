package com.byd.buttoncontroller

import android.app.Application
import com.byd.buttoncontroller.config.ConfigRepository

/**
 * Application 入口。初始化全局配置仓库单例。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        ConfigRepository.init(this)
    }
}
