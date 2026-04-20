package com.lightterm.ui.serverconfig

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lightterm.R
import com.lightterm.appContainer
import com.lightterm.databinding.ActivityServerConfigBinding
import com.lightterm.databinding.DialogDeleteServerBinding
import com.lightterm.databinding.ItemJumpHostEditorBinding
import com.lightterm.databinding.ItemSavedServerBinding
import com.lightterm.data.repository.ServerSortOrder
import com.lightterm.domain.model.AuthenticationMode
import com.lightterm.domain.model.ServerConfig
import com.lightterm.domain.model.SshKeyAlgorithm
import com.lightterm.ui.theme.resolveThemeColor
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ServerConfigActivity : AppCompatActivity() {
    private lateinit var binding: ActivityServerConfigBinding
    private val authModes = AuthenticationMode.values().toList()
    private val keyAlgorithms = listOf(SshKeyAlgorithm.ECDSA, SshKeyAlgorithm.RSA, SshKeyAlgorithm.ED25519)
    private val jumpHostBindings = mutableListOf<ItemJumpHostEditorBinding>()

    private val viewModel: ServerConfigViewModel by viewModels {
        ServerConfigViewModel.Factory(applicationContext.appContainer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applicationContext.appContainer.appSettingsRepository.applyActivityTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityServerConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.navigationIcon = androidx.appcompat.content.res.AppCompatResources.getDrawable(
            this,
            R.drawable.ic_nav_back_badge,
        )

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        setupDropdowns()
        setupInputs()

        binding.saveButton.setOnClickListener {
            viewModel.save()
        }
        binding.testConnectivityButton.setOnClickListener {
            viewModel.testConnectivity()
        }
        binding.newServerButton.setOnClickListener {
            viewModel.startNew()
            binding.formScroll.smoothScrollTo(0, binding.formCard.top)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch { viewModel.events.collect(::handleEvent) }
            }
        }
    }

    private fun setupDropdowns() {
        binding.authModeInput.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                authModes.map(::labelForAuthMode),
            ),
        )
        binding.authModeInput.setOnItemClickListener { _, _, position, _ ->
            viewModel.updateAuthMode(authModes[position])
        }

        binding.keyAlgorithmInput.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                keyAlgorithms.map(::labelForKeyAlgorithm),
            ),
        )
        binding.keyAlgorithmInput.setOnItemClickListener { _, _, position, _ ->
            viewModel.updateKeyAlgorithm(keyAlgorithms[position])
        }
    }

    private fun setupInputs() {
        binding.aliasInput.doAfterTextChanged { viewModel.updateAlias(it?.toString().orEmpty()) }
        binding.jumpHostSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateUseJumpHosts(isChecked)
        }
        binding.addJumpHostButton.setOnClickListener {
            viewModel.addJumpHost()
        }
        binding.hostInput.doAfterTextChanged { viewModel.updateHost(it?.toString().orEmpty()) }
        binding.portInput.doAfterTextChanged { viewModel.updatePort(it?.toString().orEmpty()) }
        binding.usernameInput.doAfterTextChanged { viewModel.updateUsername(it?.toString().orEmpty()) }
        binding.passwordInput.doAfterTextChanged { viewModel.updatePassword(it?.toString().orEmpty()) }
        binding.keepPasswordSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updatePreserveExistingPassword(isChecked)
        }
        binding.keyAliasInput.doAfterTextChanged { viewModel.updateKeyAlias(it?.toString().orEmpty()) }
        binding.reconnectAttemptsInput.doAfterTextChanged {
            viewModel.updateReconnectAttempts(it?.toString().orEmpty())
        }
        binding.reconnectIntervalInput.doAfterTextChanged {
            viewModel.updateReconnectIntervalSeconds(it?.toString().orEmpty())
        }
        binding.demoModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateDemoMode(isChecked)
        }
        binding.serverSortGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
                return@addOnButtonCheckedListener
            }
            val sortOrder = when (checkedId) {
                R.id.sortNameButton -> ServerSortOrder.NAME
                R.id.sortAddedButton -> ServerSortOrder.ADDED
                else -> ServerSortOrder.RECENTLY_USED
            }
            viewModel.updateServerSortOrder(sortOrder)
        }
    }

    private fun render(state: ServerConfigUiState) {
        syncText(binding.aliasInput, state.alias)
        syncText(binding.hostInput, state.host)
        syncText(binding.portInput, state.port)
        syncText(binding.usernameInput, state.username)
        syncText(binding.passwordInput, state.password)
        syncText(binding.keyAliasInput, state.keyAlias)
        syncText(binding.reconnectAttemptsInput, state.reconnectAttempts)
        syncText(binding.reconnectIntervalInput, state.reconnectIntervalSeconds)

        val authLabel = labelForAuthMode(state.authMode)
        if (binding.authModeInput.text?.toString() != authLabel) {
            binding.authModeInput.setText(authLabel, false)
        }

        val keyAlgorithmLabel = labelForKeyAlgorithm(state.keyAlgorithm)
        if (binding.keyAlgorithmInput.text?.toString() != keyAlgorithmLabel) {
            binding.keyAlgorithmInput.setText(keyAlgorithmLabel, false)
        }

        if (binding.jumpHostSwitch.isChecked != state.useJumpHosts) {
            binding.jumpHostSwitch.isChecked = state.useJumpHosts
        }
        if (binding.demoModeSwitch.isChecked != state.demoMode) {
            binding.demoModeSwitch.isChecked = state.demoMode
        }
        if (binding.keepPasswordSwitch.isChecked != state.preserveExistingPassword) {
            binding.keepPasswordSwitch.isChecked = state.preserveExistingPassword
        }
        val selectedSortButton = when (state.serverSortOrder) {
            ServerSortOrder.RECENTLY_USED -> R.id.sortRecentButton
            ServerSortOrder.NAME -> R.id.sortNameButton
            ServerSortOrder.ADDED -> R.id.sortAddedButton
        }
        if (binding.serverSortGroup.checkedButtonId != selectedSortButton) {
            binding.serverSortGroup.check(selectedSortButton)
        }

        binding.formTitle.text = if (state.isEditing) {
            getString(R.string.server_form_edit_title)
        } else {
            getString(R.string.server_form_new_title)
        }
        binding.formModeSummary.text = getString(
            R.string.server_form_mode_summary,
            getString(if (state.demoMode) R.string.server_form_demo_mode else R.string.server_form_real_mode),
            labelForAuthMode(state.authMode),
        )
        binding.jumpSectionSummary.text = if (state.useJumpHosts) {
            getString(R.string.server_form_jump_enabled_desc_count, state.jumpHosts.size)
        } else {
            getString(R.string.server_form_jump_disabled_desc)
        }
        binding.jumpSectionContent.isVisible = state.useJumpHosts
        binding.addJumpHostButton.isVisible = state.useJumpHosts
        renderJumpHosts(state.jumpHosts)
        binding.finalHopTitle.text = getString(
            if (state.useJumpHosts) R.string.server_form_final_hop_title else R.string.server_form_target_title,
        )
        binding.passwordLayout.isVisible = state.isPasswordMode
        binding.passwordHelperText.isVisible = state.isPasswordMode
        binding.passwordHelperText.text = if (state.showPreservePasswordToggle && state.preserveExistingPassword) {
            getString(R.string.server_form_password_helper_saved)
        } else {
            getString(R.string.server_form_password_helper_plain)
        }
        binding.keepPasswordRow.isVisible = state.showPreservePasswordToggle
        binding.passwordInput.isEnabled = state.shouldEditPassword
        binding.passwordLayout.alpha = if (state.shouldEditPassword) 1f else 0.6f
        binding.keyAliasLayout.isVisible = !state.isPasswordMode
        binding.keyAlgorithmLayout.isVisible = !state.isPasswordMode
        binding.keyHelperText.isVisible = !state.isPasswordMode
        binding.keyHelperText.text = getString(R.string.server_form_key_helper_text)
        binding.testConnectivityButton.isEnabled = !state.isTestingConnectivity && !state.isSaving
        binding.testConnectivityButton.text = if (state.isTestingConnectivity) {
            getString(R.string.server_form_testing)
        } else {
            getString(R.string.server_form_test_connectivity)
        }
        binding.connectivityResultText.isVisible = state.hasConnectivitySummary
        binding.connectivityResultText.text = state.connectivitySummary
        binding.saveButton.isEnabled = !state.isSaving
        binding.saveButton.text = if (state.isSaving) {
            getString(R.string.server_form_saving)
        } else {
            getString(R.string.server_form_save)
        }

        binding.savedServersEmptyText.isVisible = state.servers.isEmpty()
        renderSavedServers(state.servers, state.editingServerId)
    }

    private fun renderJumpHosts(jumpHosts: List<JumpHostUiState>) {
        if (jumpHostBindings.size != jumpHosts.size) {
            rebuildJumpHostBindings(jumpHosts.size)
        }

        jumpHosts.forEachIndexed { index, hop ->
            val itemBinding = jumpHostBindings[index]
            itemBinding.jumpHostTitle.text = getString(R.string.server_form_jump_step_title, index + 1)
            itemBinding.removeJumpHostButton.isVisible = jumpHosts.size > 1

            syncText(itemBinding.jumpHostInput, hop.host)
            syncText(itemBinding.jumpPortInput, hop.port)
            syncText(itemBinding.jumpUsernameInput, hop.username)
            syncText(itemBinding.jumpPasswordInput, hop.password)
            syncText(itemBinding.jumpKeyAliasInput, hop.keyAlias)

            val authLabel = labelForAuthMode(hop.authMode)
            if (itemBinding.jumpAuthModeInput.text?.toString() != authLabel) {
                itemBinding.jumpAuthModeInput.setText(authLabel, false)
            }

            val keyAlgorithmLabel = labelForKeyAlgorithm(hop.keyAlgorithm)
            if (itemBinding.jumpKeyAlgorithmInput.text?.toString() != keyAlgorithmLabel) {
                itemBinding.jumpKeyAlgorithmInput.setText(keyAlgorithmLabel, false)
            }

            if (itemBinding.keepJumpPasswordSwitch.isChecked != hop.preserveExistingPassword) {
                itemBinding.keepJumpPasswordSwitch.isChecked = hop.preserveExistingPassword
            }

            itemBinding.keepJumpPasswordRow.isVisible = hop.showPreservePasswordToggle
            itemBinding.jumpPasswordLayout.isVisible = hop.isPasswordMode
            itemBinding.jumpPasswordHelperText.isVisible = hop.isPasswordMode
            itemBinding.jumpPasswordHelperText.text = if (hop.showPreservePasswordToggle && hop.preserveExistingPassword) {
                getString(R.string.server_form_jump_password_helper_saved)
            } else {
                getString(R.string.server_form_jump_password_helper_plain)
            }
            itemBinding.jumpPasswordInput.isEnabled = hop.shouldEditPassword
            itemBinding.jumpPasswordLayout.alpha = if (hop.shouldEditPassword) 1f else 0.6f
            itemBinding.jumpKeyAliasLayout.isVisible = !hop.isPasswordMode
            itemBinding.jumpKeyAlgorithmLayout.isVisible = !hop.isPasswordMode
            itemBinding.jumpKeyHelperText.isVisible = !hop.isPasswordMode
            itemBinding.jumpKeyHelperText.text = getString(R.string.server_form_key_helper_text)
        }
    }

    private fun rebuildJumpHostBindings(count: Int) {
        binding.jumpHostsContainer.removeAllViews()
        jumpHostBindings.clear()

        repeat(count) { index ->
            val itemBinding = ItemJumpHostEditorBinding.inflate(
                LayoutInflater.from(this),
                binding.jumpHostsContainer,
                false,
            )

            itemBinding.jumpAuthModeInput.setAdapter(
                ArrayAdapter(
                    this,
                    android.R.layout.simple_list_item_1,
                    authModes.map(::labelForAuthMode),
                ),
            )
            itemBinding.jumpAuthModeInput.setOnItemClickListener { _, _, position, _ ->
                viewModel.updateJumpAuthMode(index, authModes[position])
            }

            itemBinding.jumpKeyAlgorithmInput.setAdapter(
                ArrayAdapter(
                    this,
                    android.R.layout.simple_list_item_1,
                    keyAlgorithms.map(::labelForKeyAlgorithm),
                ),
            )
            itemBinding.jumpKeyAlgorithmInput.setOnItemClickListener { _, _, position, _ ->
                viewModel.updateJumpKeyAlgorithm(index, keyAlgorithms[position])
            }

            itemBinding.jumpHostInput.doAfterTextChanged {
                viewModel.updateJumpHost(index, it?.toString().orEmpty())
            }
            itemBinding.jumpPortInput.doAfterTextChanged {
                viewModel.updateJumpPort(index, it?.toString().orEmpty())
            }
            itemBinding.jumpUsernameInput.doAfterTextChanged {
                viewModel.updateJumpUsername(index, it?.toString().orEmpty())
            }
            itemBinding.jumpPasswordInput.doAfterTextChanged {
                viewModel.updateJumpPassword(index, it?.toString().orEmpty())
            }
            itemBinding.keepJumpPasswordSwitch.setOnCheckedChangeListener { _, isChecked ->
                viewModel.updatePreserveExistingJumpPassword(index, isChecked)
            }
            itemBinding.jumpKeyAliasInput.doAfterTextChanged {
                viewModel.updateJumpKeyAlias(index, it?.toString().orEmpty())
            }
            itemBinding.removeJumpHostButton.setOnClickListener {
                viewModel.removeJumpHost(index)
            }

            binding.jumpHostsContainer.addView(itemBinding.root)
            jumpHostBindings += itemBinding
        }
    }

    private fun renderSavedServers(servers: List<ServerConfig>, editingServerId: Long?) {
        binding.savedServersContainer.removeAllViews()
        servers.forEach { server ->
            val itemBinding = ItemSavedServerBinding.inflate(
                LayoutInflater.from(this),
                binding.savedServersContainer,
                false,
            )

            itemBinding.aliasText.text = server.alias
            itemBinding.targetText.text = server.routeLabel()
            itemBinding.metaText.text = buildString {
                append(
                    getString(
                        R.string.server_form_mode_summary,
                        getString(if (server.demoMode) R.string.server_form_demo_mode else R.string.server_form_real_mode),
                        labelForAuthMode(server.authMode),
                    ),
                )
                append(" · ")
                append(
                    getString(
                        R.string.server_item_reconnect_summary,
                        server.reconnectAttempts,
                        server.reconnectIntervalSeconds,
                    ),
                )
            }
            itemBinding.editButton.setOnClickListener {
                viewModel.editServer(server.id)
            }
            itemBinding.deleteButton.setOnClickListener {
                showDeleteServerConfirmation(server)
            }
            itemBinding.openButton.setOnClickListener {
                viewModel.openServer(server.id)
            }

            val isEditing = editingServerId == server.id
            val highlight = if (isEditing) {
                resolveThemeColor(R.attr.lightTermAccentWarm)
            } else {
                resolveThemeColor(R.attr.lightTermSurfaceVariant)
            }
            itemBinding.root.strokeColor = highlight
            itemBinding.root.strokeWidth = if (isEditing) dpToPx(2) else dpToPx(1)

            binding.savedServersContainer.addView(itemBinding.root)
        }
    }

    private fun showDeleteServerConfirmation(server: ServerConfig) {
        val dialogBinding = DialogDeleteServerBinding.inflate(LayoutInflater.from(this))
        val dialog = Dialog(this).apply {
            setContentView(dialogBinding.root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setCancelable(true)
        }

        dialogBinding.deleteTitleText.text = getString(R.string.server_delete_dialog_title)
        dialogBinding.deleteSummaryText.text = getString(R.string.server_delete_dialog_message, server.alias)
        dialogBinding.deleteAliasText.text = server.alias
        dialogBinding.deleteRouteText.text = server.routeLabel()
        dialogBinding.deleteWarningText.text = getString(R.string.server_delete_dialog_warning)
        dialogBinding.cancelDeleteButton.setOnClickListener { dialog.dismiss() }
        dialogBinding.confirmDeleteButton.setOnClickListener {
            dialog.dismiss()
            viewModel.deleteServer(server.id)
        }

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun handleEvent(event: ServerConfigEvent) {
        when (event) {
            is ServerConfigEvent.ShowMessage -> {
                Toast.makeText(this, event.value, Toast.LENGTH_SHORT).show()
            }

            ServerConfigEvent.ScrollToForm -> {
                binding.formScroll.post {
                    binding.formScroll.smoothScrollTo(0, binding.formCard.top)
                }
            }

            ServerConfigEvent.ScrollToSavedList -> {
                binding.formScroll.post {
                    binding.formScroll.smoothScrollTo(0, 0)
                }
            }

            ServerConfigEvent.NavigateBack -> {
                finish()
            }
        }
    }

    private fun syncText(view: View, value: String) {
        when (view) {
            is com.google.android.material.textfield.TextInputEditText -> {
                if (view.text?.toString() != value) {
                    view.setText(value)
                    view.setSelection(view.text?.length ?: 0)
                }
            }
        }
    }

    private fun labelForAuthMode(mode: AuthenticationMode): String = when (mode) {
        AuthenticationMode.PASSWORD -> getString(R.string.server_auth_password)
        AuthenticationMode.PUBLIC_KEY -> getString(R.string.server_auth_public_key)
    }

    private fun labelForKeyAlgorithm(algorithm: SshKeyAlgorithm): String = when (algorithm) {
        SshKeyAlgorithm.ECDSA -> "ECDSA"
        SshKeyAlgorithm.RSA -> "RSA"
        SshKeyAlgorithm.ED25519 -> "ED25519"
    }

    private fun dpToPx(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
