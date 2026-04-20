package com.lightterm.ui.session

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lightterm.R
import com.lightterm.appContainer
import com.lightterm.databinding.FragmentSessionBinding
import com.lightterm.domain.model.SessionConnectionState
import com.lightterm.ui.theme.resolveThemeColor
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class SessionFragment : Fragment(R.layout.fragment_session) {
    private var _binding: FragmentSessionBinding? = null
    private val binding: FragmentSessionBinding
        get() = checkNotNull(_binding)
    private var followTerminalOutput = true
    private var lastSubmitAtMs = 0L

    private val sessionId: String
        get() = requireArguments().getString(ARG_SESSION_ID).orEmpty()

    private val viewModel: SessionViewModel by viewModels {
        SessionViewModel.Factory(
            sessionId = sessionId,
            sessionManager = requireContext().appContainer.sessionManager,
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSessionBinding.bind(view)

        binding.terminalView.setTerminalFontSize(
            requireContext().appContainer.appSettingsRepository.settings.value.terminalFontSizeSp,
        )
        binding.terminalView.onTerminalSizeChanged = viewModel::resize
        binding.terminalView.onFontScaleStepRequested = { step ->
            requireContext().appContainer.appSettingsRepository.adjustTerminalFont(step)
        }
        binding.terminalScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            binding.terminalView.updateViewport(binding.terminalScroll.width, binding.terminalScroll.height)
            if (followTerminalOutput) {
                scrollTerminalToBottom()
            }
        }
        binding.terminalScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            followTerminalOutput = isTerminalNearBottom()
        }

        binding.sendButton.setOnClickListener {
            submitCommand()
        }
        binding.reconnectButton.setOnClickListener {
            viewModel.reconnect()
        }
        binding.refreshButton.setOnClickListener {
            viewModel.refresh()
        }
        binding.commandInput.setOnEditorActionListener { _, actionId, event ->
            val isHardwareEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            val isImeSend = event == null && actionId == EditorInfo.IME_ACTION_SEND
            if (isHardwareEnter || isImeSend) {
                submitCommand()
                true
            } else {
                false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                requireContext().appContainer.appSettingsRepository.settings.collect { settings ->
                    binding.terminalView.setTerminalFontSize(settings.terminalFontSizeSp)
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding?.terminalView?.onTerminalSizeChanged = null
        _binding?.terminalView?.onFontScaleStepRequested = null
        _binding = null
        super.onDestroyView()
    }

    private fun render(state: com.lightterm.core.session.SessionUiState) {
        val stickToBottom = followTerminalOutput || isTerminalNearBottom()

        binding.sessionAliasText.text = state.title
        binding.sessionTargetText.text = state.server.targetLabel()
        binding.sessionStatusChip.text = labelForConnectionState(state.connectionState)
        binding.sessionStatusChip.backgroundTintList = ColorStateList.valueOf(resolveStatusColor(state.connectionState))
        val hintParts = buildList {
            state.statusDetail.takeIf { it.isNotBlank() }?.let(::add)
            add(getString(R.string.session_hint_keepalive, state.keepAliveIntervalSeconds))
            state.lastLatencyMs?.let {
                add(getString(R.string.session_hint_latency, it))
            }
        }
        binding.sessionHintText.text = hintParts.joinToString(" · ")
        binding.sessionHintText.isVisible = hintParts.isNotEmpty()
        binding.terminalView.renderSnapshot(state.terminalSnapshot)
        if (stickToBottom) {
            scrollTerminalToBottom()
            followTerminalOutput = true
        }
        binding.commandInput.isEnabled = state.inputEnabled
        binding.sendButton.isEnabled = state.inputEnabled

        val showRecoveryActions =
            state.connectionState == SessionConnectionState.ERROR ||
                state.connectionState == SessionConnectionState.DISCONNECTED
        binding.recoveryActionsRow.isVisible = showRecoveryActions
    }

    private fun resolveStatusColor(state: SessionConnectionState): Int = when (state) {
        SessionConnectionState.CONNECTED -> requireContext().resolveThemeColor(R.attr.lightTermStatusConnected)
        SessionConnectionState.CONNECTING -> requireContext().resolveThemeColor(R.attr.lightTermStatusConnecting)
        SessionConnectionState.RECONNECTING -> requireContext().resolveThemeColor(R.attr.lightTermStatusReconnecting)
        SessionConnectionState.DISCONNECTED -> requireContext().resolveThemeColor(R.attr.lightTermStatusDisconnected)
        SessionConnectionState.ERROR -> requireContext().resolveThemeColor(R.attr.lightTermDanger)
    }

    private fun labelForConnectionState(state: SessionConnectionState): String = when (state) {
        SessionConnectionState.DISCONNECTED -> getString(R.string.session_state_disconnected)
        SessionConnectionState.CONNECTING -> getString(R.string.session_state_connecting)
        SessionConnectionState.CONNECTED -> getString(R.string.session_state_connected)
        SessionConnectionState.RECONNECTING -> getString(R.string.session_state_reconnecting)
        SessionConnectionState.ERROR -> getString(R.string.session_state_error)
    }

    private fun submitCommand() {
        if (shouldIgnoreDuplicateSubmit()) {
            return
        }
        val command = binding.commandInput.text?.toString().orEmpty()
        viewModel.sendCommand(command)
        binding.commandInput.setText("")
    }

    private fun shouldIgnoreDuplicateSubmit(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSubmitAtMs <= SUBMIT_DEBOUNCE_MS) {
            return true
        }
        lastSubmitAtMs = now
        return false
    }

    private fun isTerminalNearBottom(): Boolean {
        if (_binding == null) {
            return true
        }
        val maxScroll = (binding.terminalView.height - binding.terminalScroll.height).coerceAtLeast(0)
        return maxScroll == 0 || binding.terminalScroll.scrollY >= maxScroll - dpToPx(24)
    }

    private fun scrollTerminalToBottom() {
        val currentBinding = _binding ?: return
        val terminalScroll = currentBinding.terminalScroll
        val terminalView = currentBinding.terminalView
        terminalScroll.post {
            if (_binding !== currentBinding) {
                return@post
            }
            val maxScroll = (terminalView.height - terminalScroll.height).coerceAtLeast(0)
            terminalScroll.scrollTo(0, maxScroll)
        }
    }

    private fun dpToPx(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val ARG_SESSION_ID = "session_id"
        private const val SUBMIT_DEBOUNCE_MS = 250L

        fun newInstance(sessionId: String): SessionFragment {
            return SessionFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SESSION_ID, sessionId)
                }
            }
        }
    }
}
