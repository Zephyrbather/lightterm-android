package com.lightterm.data.repository

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.color.DynamicColors
import com.lightterm.R
import com.lightterm.domain.model.ServerConfig
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode {
    PURE_BLACK,
    PURE_WHITE,
    SYSTEM_COLOR,
    ;

    fun storageValue(): String = name.lowercase(Locale.US)

    fun themeResId(): Int = when (this) {
        PURE_BLACK -> R.style.Theme_LightTerm_PureBlack
        PURE_WHITE -> R.style.Theme_LightTerm_PureWhite
        SYSTEM_COLOR -> R.style.Theme_LightTerm_SystemColor
    }

    fun nightMode(): Int = when (this) {
        PURE_BLACK -> AppCompatDelegate.MODE_NIGHT_YES
        PURE_WHITE -> AppCompatDelegate.MODE_NIGHT_NO
        SYSTEM_COLOR -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    companion object {
        fun fromStorage(value: String?): AppThemeMode = entries.firstOrNull {
            it.storageValue() == value
        } ?: PURE_BLACK
    }
}

enum class ServerSortOrder {
    RECENTLY_USED,
    NAME,
    ADDED,
    ;

    fun storageValue(): String = name.lowercase(Locale.US)

    companion object {
        fun fromStorage(value: String?): ServerSortOrder = entries.firstOrNull {
            it.storageValue() == value
        } ?: RECENTLY_USED
    }
}

data class AppSettings(
    val terminalFontSizeSp: Float,
    val languageTag: String,
    val themeMode: AppThemeMode,
    val serverSortOrder: ServerSortOrder,
) {
    val canIncreaseFont: Boolean
        get() = terminalFontSizeSp < MAX_TERMINAL_FONT_SP

    val canDecreaseFont: Boolean
        get() = terminalFontSizeSp > MIN_TERMINAL_FONT_SP

    companion object {
        const val MIN_TERMINAL_FONT_SP = 8f
        const val MAX_TERMINAL_FONT_SP = 22f
        const val TERMINAL_FONT_STEP_SP = 1f
        const val LANGUAGE_ZH = "zh"
        const val LANGUAGE_EN = "en"
    }
}

class AppSettingsRepository(
    context: Context,
    defaultFontSizeSp: Float,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(loadSettings(defaultFontSizeSp))

    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        applyLanguage(_settings.value.languageTag)
        applyThemeMode(_settings.value.themeMode)
    }

    fun increaseTerminalFont() {
        updateTerminalFont(AppSettings.TERMINAL_FONT_STEP_SP)
    }

    fun decreaseTerminalFont() {
        updateTerminalFont(-AppSettings.TERMINAL_FONT_STEP_SP)
    }

    fun adjustTerminalFont(step: Int) {
        if (step == 0) {
            return
        }
        updateTerminalFont(step * AppSettings.TERMINAL_FONT_STEP_SP)
    }

    fun updateLanguage(languageTag: String) {
        val normalized = languageTag
            .trim()
            .ifBlank { AppSettings.LANGUAGE_ZH }
        val updated = _settings.value.copy(languageTag = normalized)
        _settings.value = updated
        preferences.edit().putString(KEY_LANGUAGE_TAG, normalized).apply()
        applyLanguage(normalized)
    }

    fun updateThemeMode(themeMode: AppThemeMode) {
        if (_settings.value.themeMode == themeMode) {
            return
        }
        val updated = _settings.value.copy(themeMode = themeMode)
        _settings.value = updated
        preferences.edit().putString(KEY_THEME_MODE, themeMode.storageValue()).apply()
        applyThemeMode(themeMode)
    }

    fun updateServerSortOrder(serverSortOrder: ServerSortOrder) {
        if (_settings.value.serverSortOrder == serverSortOrder) {
            return
        }
        val updated = _settings.value.copy(serverSortOrder = serverSortOrder)
        _settings.value = updated
        preferences.edit().putString(KEY_SERVER_SORT_ORDER, serverSortOrder.storageValue()).apply()
    }

    fun applyActivityTheme(activity: Activity) {
        activity.setTheme(_settings.value.themeMode.themeResId())
        if (_settings.value.themeMode == AppThemeMode.SYSTEM_COLOR) {
            DynamicColors.applyToActivityIfAvailable(activity)
        }
    }

    private fun updateTerminalFont(deltaSp: Float) {
        val current = _settings.value
        val updatedFont = (current.terminalFontSizeSp + deltaSp)
            .coerceIn(AppSettings.MIN_TERMINAL_FONT_SP, AppSettings.MAX_TERMINAL_FONT_SP)
        if (updatedFont == current.terminalFontSizeSp) {
            return
        }

        val updated = current.copy(terminalFontSizeSp = updatedFont)
        _settings.value = updated
        preferences.edit().putFloat(KEY_TERMINAL_FONT_SP, updatedFont).commit()
    }

    private fun loadSettings(defaultFontSizeSp: Float): AppSettings {
        val fontSize = preferences.getFloat(
            KEY_TERMINAL_FONT_SP,
            defaultFontSizeSp.coerceIn(
                AppSettings.MIN_TERMINAL_FONT_SP,
                AppSettings.MAX_TERMINAL_FONT_SP,
            ),
        ).coerceIn(AppSettings.MIN_TERMINAL_FONT_SP, AppSettings.MAX_TERMINAL_FONT_SP)
        val languageTag = preferences.getString(KEY_LANGUAGE_TAG, AppSettings.LANGUAGE_ZH)
            ?: AppSettings.LANGUAGE_ZH
        val themeMode = AppThemeMode.fromStorage(preferences.getString(KEY_THEME_MODE, null))
        val serverSortOrder = ServerSortOrder.fromStorage(
            preferences.getString(KEY_SERVER_SORT_ORDER, null),
        )
        return AppSettings(
            terminalFontSizeSp = fontSize,
            languageTag = languageTag,
            themeMode = themeMode,
            serverSortOrder = serverSortOrder,
        )
    }

    private fun applyLanguage(languageTag: String) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(languageTag),
        )
    }

    private fun applyThemeMode(themeMode: AppThemeMode) {
        AppCompatDelegate.setDefaultNightMode(themeMode.nightMode())
    }

    private companion object {
        private const val PREFERENCES_NAME = "app_settings"
        private const val KEY_TERMINAL_FONT_SP = "terminal_font_sp"
        private const val KEY_LANGUAGE_TAG = "language_tag"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_SERVER_SORT_ORDER = "server_sort_order"
    }
}

fun List<ServerConfig>.sortedForDisplay(serverSortOrder: ServerSortOrder): List<ServerConfig> {
    return when (serverSortOrder) {
        ServerSortOrder.RECENTLY_USED -> sortedWith(
            compareByDescending<ServerConfig> { it.lastUsedAtEpochMillis }
                .thenBy { it.alias.lowercase(Locale.getDefault()) }
                .thenBy { it.id },
        )

        ServerSortOrder.NAME -> sortedWith(
            compareBy<ServerConfig> { it.alias.lowercase(Locale.getDefault()) }
                .thenBy { it.id },
        )

        ServerSortOrder.ADDED -> sortedBy { it.id }
    }
}
