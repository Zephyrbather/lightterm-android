package com.lightterm.core.device

import com.lightterm.domain.model.PowerMode

data class DeviceProfile(
    val name: String,
    val maxRefreshRateHz: Int,
    val terminalFontSizeSp: Float,
    val offscreenPageLimit: Int,
    val compactKeyboard: Boolean,
    val keepAliveForegroundSeconds: Long,
    val keepAliveBackgroundSeconds: Long,
    val activeRenderIntervalMs: Long,
    val idleRenderIntervalMs: Long,
    val ringBufferLineLimit: Int,
    val prewarmAdjacentTabs: Boolean,
) {
    fun summary(powerMode: PowerMode): String = buildString {
        append(name)
        append(" 优化已启用")
        append(" · ")
        append(maxRefreshRateHz)
        append("Hz 渲染上限")
        append(" · 键盘")
        append(if (compactKeyboard) "紧凑" else "常规")
        append(" · ")
        append(powerMode.label)
        append("模式")
    }

    companion object {
        fun generic(): DeviceProfile = DeviceProfile(
            name = "通用 Android 配置",
            maxRefreshRateHz = 60,
            terminalFontSizeSp = 13f,
            offscreenPageLimit = 1,
            compactKeyboard = false,
            keepAliveForegroundSeconds = 20,
            keepAliveBackgroundSeconds = 50,
            activeRenderIntervalMs = 16L,
            idleRenderIntervalMs = 40L,
            ringBufferLineLimit = 1000,
            prewarmAdjacentTabs = false,
        )
    }
}

