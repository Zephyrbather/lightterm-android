package com.lightterm.core.device

import android.os.Build
import java.util.Locale

class DeviceProfileResolver {
    fun resolve(): DeviceProfile {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase(Locale.US)
        val brand = Build.BRAND.orEmpty().lowercase(Locale.US)
        val model = Build.MODEL.orEmpty().lowercase(Locale.US)
        val device = Build.DEVICE.orEmpty().lowercase(Locale.US)
        val product = Build.PRODUCT.orEmpty().lowercase(Locale.US)
        val vivoX300Markers = setOf("x300", "v2509a", "v2509", "pd2509", "pd2509d")
        val isVivoX300 = (manufacturer == "vivo" || brand == "vivo") &&
            listOf(model, device, product).any { candidate ->
                vivoX300Markers.any(candidate::contains)
            }

        return if (isVivoX300) {
            // 真机上 Build.MODEL 可能表现为 V2509A、Build.DEVICE/PRODUCT 为 PD2509，
            // 因此不能只依赖市场名 "X300"；这里同时匹配 vivo 的公开名和内部代号。
            DeviceProfile(
                name = "vivo X300 专项配置",
                maxRefreshRateHz = 120,
                terminalFontSizeSp = 12.5f,
                offscreenPageLimit = 2,
                compactKeyboard = true,
                keepAliveForegroundSeconds = 15,
                keepAliveBackgroundSeconds = 40,
                activeRenderIntervalMs = 8L,
                idleRenderIntervalMs = 32L,
                ringBufferLineLimit = 1000,
                prewarmAdjacentTabs = true,
            )
        } else {
            DeviceProfile.generic()
        }
    }
}
