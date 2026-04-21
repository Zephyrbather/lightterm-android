package com.lightterm.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.lightterm.R
import com.lightterm.appContainer
import com.lightterm.data.repository.AppSettings
import com.lightterm.data.repository.AppThemeMode
import com.lightterm.databinding.ActivityMainBinding
import com.lightterm.databinding.DialogShortcutEditorBinding
import com.lightterm.databinding.ItemShortcutEditorBinding
import com.lightterm.domain.model.SessionConnectionState
import com.lightterm.domain.model.VirtualKey
import com.lightterm.ui.session.SessionFragment
import com.lightterm.ui.serverconfig.ServerConfigActivity
import com.lightterm.ui.theme.resolveThemeColor
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var pagerAdapter: SessionPagerAdapter
    private lateinit var recentServerAdapter: RecentServerAdapter
    private var tabMediator: TabLayoutMediator? = null

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(applicationContext.appContainer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applicationContext.appContainer.appSettingsRepository.applyActivityTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.logo = AppCompatResources.getDrawable(this, R.drawable.ic_lightterm_toolbar_logo)
        binding.toolbar.subtitle = null
        binding.toolbar.overflowIcon?.setTint(resolveThemeColor(R.attr.lightTermOnSurfaceVariant))

        pagerAdapter = SessionPagerAdapter(this)
        recentServerAdapter = RecentServerAdapter { server ->
            viewModel.openServer(server.id)
        }
        binding.viewPager.adapter = pagerAdapter
        binding.recentHistoryList.layoutManager = LinearLayoutManager(this)
        binding.recentHistoryList.adapter = recentServerAdapter
        binding.virtualKeyBar.bindKeys(
            keys = viewModel.uiState.value.shortcuts,
            compactMode = viewModel.uiState.value.deviceProfile.compactKeyboard,
            onKeyTap = viewModel::sendVirtualKey,
        )

        tabMediator = TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            bindTab(tab, position)
        }.also { it.attach() }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_new_session -> {
                    if (viewModel.uiState.value.availableServers.isEmpty()) {
                        startActivity(Intent(this, ServerConfigActivity::class.java))
                    } else {
                        viewModel.openNextSession()
                    }
                    true
                }

                R.id.action_customize_shortcuts -> {
                    showShortcutEditor()
                    true
                }

                R.id.action_font_decrease -> {
                    viewModel.decreaseTerminalFont()
                    true
                }

                R.id.action_font_increase -> {
                    viewModel.increaseTerminalFont()
                    true
                }

                R.id.action_file_manager -> {
                    val activeSessionId = viewModel.uiState.value.activeSessionId ?: return@setOnMenuItemClickListener true
                    supportFragmentManager.setFragmentResult(
                        SessionFragment.REQUEST_KEY_OPEN_FILE_MANAGER,
                        bundleOf(SessionFragment.RESULT_KEY_SESSION_ID to activeSessionId),
                    )
                    true
                }

                R.id.action_language -> {
                    showLanguageDialog()
                    true
                }

                R.id.action_theme -> {
                    showThemeDialog()
                    true
                }

                R.id.action_manage_servers -> {
                    startActivity(Intent(this, ServerConfigActivity::class.java))
                    true
                }

                R.id.action_close_session -> {
                    viewModel.closeActiveSession()
                    true
                }

                else -> false
            }
        }

        binding.viewPager.registerOnPageChangeCallback(
            object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    viewModel.selectSession(position)
                }
            },
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    override fun onDestroy() {
        tabMediator?.detach()
        super.onDestroy()
    }

    private fun render(state: MainUiState) {
        pagerAdapter.submitTabs(state.sessionTabs)
        syncTabs()

        binding.virtualKeyBar.bindKeys(
            keys = state.shortcuts,
            compactMode = state.deviceProfile.compactKeyboard,
            onKeyTap = viewModel::sendVirtualKey,
        )
        binding.viewPager.offscreenPageLimit = state.deviceProfile.offscreenPageLimit

        val refreshRate = state.deviceProfile.maxRefreshRateHz.toFloat()
        val params = window.attributes
        if (params.preferredRefreshRate != refreshRate) {
            params.preferredRefreshRate = refreshRate
            window.attributes = params
        }

        binding.emptyStateText.isVisible = state.sessionTabs.isEmpty()
        binding.viewPager.isVisible = state.sessionTabs.isNotEmpty()
        binding.tabLayout.isVisible = state.sessionTabs.isNotEmpty()
        binding.emptyStateContainer.isVisible = state.sessionTabs.isEmpty()

        val showRecentHistory = state.sessionTabs.isEmpty() && state.recentServers.isNotEmpty()
        recentServerAdapter.submitList(state.recentServers)
        binding.recentHistorySection.isVisible = showRecentHistory
        binding.emptyStateContainer.gravity = if (showRecentHistory) {
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        } else {
            Gravity.CENTER
        }

        val activeIndex = state.sessionTabs.indexOfFirst { it.sessionId == state.activeSessionId }
        if (activeIndex >= 0 && binding.viewPager.currentItem != activeIndex) {
            binding.viewPager.setCurrentItem(activeIndex, false)
        }

        binding.emptyStateText.text = when {
            state.availableServers.isEmpty() -> getString(R.string.empty_state_no_server)
            showRecentHistory -> getString(R.string.home_recent_empty_state)
            else -> getString(R.string.empty_state)
        }
        binding.toolbar.menu.findItem(R.id.action_font_decrease)?.isEnabled = state.appSettings.canDecreaseFont
        binding.toolbar.menu.findItem(R.id.action_font_increase)?.isEnabled = state.appSettings.canIncreaseFont
        val activeTab = state.sessionTabs.firstOrNull { it.sessionId == state.activeSessionId }
        binding.toolbar.menu.findItem(R.id.action_file_manager)?.apply {
            isVisible = activeTab != null
            isEnabled = activeTab?.state == SessionConnectionState.CONNECTED
            icon?.setTint(resolveThemeColor(R.attr.lightTermOnSurfaceVariant))
        }
        binding.toolbar.menu.findItem(R.id.action_language)?.title = languageMenuTitle()
        binding.toolbar.menu.findItem(R.id.action_theme)?.title = themeMenuTitle()
    }

    private fun syncTabs() {
        repeat(binding.tabLayout.tabCount) { index ->
            binding.tabLayout.getTabAt(index)?.let { bindTab(it, index) }
        }
    }

    private fun bindTab(
        tab: TabLayout.Tab,
        position: Int,
    ) {
        val item = pagerAdapter.getTab(position) ?: return
        val tint = resolveTabStatusColor(item.state)
        tab.text = item.title
        tab.icon = AppCompatResources.getDrawable(this, R.drawable.ic_tab_status_dot)
            ?.mutate()
            ?.apply { setTint(tint) }
        tab.contentDescription = "${item.title} ${labelForConnectionState(item.state)}"
    }

    private fun resolveTabStatusColor(state: SessionConnectionState): Int = when (state) {
        SessionConnectionState.CONNECTED -> resolveThemeColor(R.attr.lightTermStatusConnected)
        SessionConnectionState.CONNECTING -> resolveThemeColor(R.attr.lightTermStatusConnecting)
        SessionConnectionState.RECONNECTING -> resolveThemeColor(R.attr.lightTermStatusReconnecting)
        SessionConnectionState.DISCONNECTED -> resolveThemeColor(R.attr.lightTermStatusDisconnected)
        SessionConnectionState.ERROR -> resolveThemeColor(R.attr.lightTermDanger)
    }

    private fun labelForConnectionState(state: SessionConnectionState): String = when (state) {
        SessionConnectionState.DISCONNECTED -> getString(R.string.session_state_disconnected)
        SessionConnectionState.CONNECTING -> getString(R.string.session_state_connecting)
        SessionConnectionState.CONNECTED -> getString(R.string.session_state_connected)
        SessionConnectionState.RECONNECTING -> getString(R.string.session_state_reconnecting)
        SessionConnectionState.ERROR -> getString(R.string.session_state_error)
    }

    private fun showLanguageDialog() {
        val tags = listOf(
            AppSettings.LANGUAGE_ZH,
            AppSettings.LANGUAGE_EN,
        )
        val labels = arrayOf(
            getString(R.string.language_option_chinese),
            getString(R.string.language_option_english),
        )
        val selectedIndex = tags.indexOf(viewModel.currentLanguageTag()).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.language_dialog_title)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                dialog.dismiss()
                if (tags[which] != viewModel.currentLanguageTag()) {
                    viewModel.setLanguage(tags[which])
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showThemeDialog() {
        val modes = listOf(
            AppThemeMode.PURE_BLACK,
            AppThemeMode.PURE_WHITE,
            AppThemeMode.SYSTEM_COLOR,
        )
        val labels = arrayOf(
            getString(R.string.theme_option_pure_black),
            getString(R.string.theme_option_pure_white),
            getString(R.string.theme_option_system_color),
        )
        val selectedIndex = modes.indexOf(viewModel.currentThemeMode()).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.theme_dialog_title)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                val selectedMode = modes[which]
                dialog.dismiss()
                if (selectedMode != viewModel.currentThemeMode()) {
                    viewModel.setThemeMode(selectedMode)
                    window.decorView.post { recreate() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun languageMenuTitle(): String {
        val label = when (viewModel.currentLanguageTag()) {
            AppSettings.LANGUAGE_EN -> getString(R.string.language_option_english)
            else -> getString(R.string.language_option_chinese)
        }
        return getString(R.string.action_language) + " · " + label
    }

    private fun themeMenuTitle(): String {
        val label = when (viewModel.currentThemeMode()) {
            AppThemeMode.PURE_BLACK -> getString(R.string.theme_option_pure_black)
            AppThemeMode.PURE_WHITE -> getString(R.string.theme_option_pure_white)
            AppThemeMode.SYSTEM_COLOR -> getString(R.string.theme_option_system_color)
        }
        return getString(R.string.action_theme) + " · " + label
    }

    private fun showShortcutEditor() {
        val dialogBinding = DialogShortcutEditorBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.shortcut_editor_title)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun refreshEditor() {
            renderShortcutEditor(dialogBinding, viewModel.uiState.value.shortcuts)
        }

        dialogBinding.addShortcutButton.setOnClickListener {
            val message = viewModel.addVirtualKey(
                label = dialogBinding.shortcutLabelInput.text?.toString().orEmpty(),
                definition = dialogBinding.shortcutDefinitionInput.text?.toString().orEmpty(),
            )
            if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialogBinding.shortcutLabelInput.setText("")
            dialogBinding.shortcutDefinitionInput.setText("")
            refreshEditor()
        }
        dialogBinding.resetShortcutsButton.setOnClickListener {
            viewModel.resetVirtualKeys()
            refreshEditor()
        }

        refreshEditor()
        dialog.show()
    }

    private fun renderShortcutEditor(
        dialogBinding: DialogShortcutEditorBinding,
        shortcuts: List<VirtualKey>,
    ) {
        dialogBinding.shortcutListContainer.removeAllViews()
        dialogBinding.emptyShortcutText.isVisible = shortcuts.isEmpty()

        shortcuts.forEachIndexed { index, shortcut ->
            val itemBinding = ItemShortcutEditorBinding.inflate(
                layoutInflater,
                dialogBinding.shortcutListContainer,
                false,
            )

            itemBinding.shortcutLabelText.text = shortcut.label
            itemBinding.shortcutDefinitionText.text = shortcut.definition
            itemBinding.moveUpButton.isEnabled = index > 0
            itemBinding.moveDownButton.isEnabled = index < shortcuts.lastIndex
            itemBinding.moveUpButton.setOnClickListener {
                viewModel.moveVirtualKey(shortcut.id, -1)
                renderShortcutEditor(dialogBinding, viewModel.uiState.value.shortcuts)
            }
            itemBinding.moveDownButton.setOnClickListener {
                viewModel.moveVirtualKey(shortcut.id, 1)
                renderShortcutEditor(dialogBinding, viewModel.uiState.value.shortcuts)
            }
            itemBinding.deleteButton.setOnClickListener {
                viewModel.removeVirtualKey(shortcut.id)
                renderShortcutEditor(dialogBinding, viewModel.uiState.value.shortcuts)
            }

            dialogBinding.shortcutListContainer.addView(itemBinding.root)
        }
    }
}
