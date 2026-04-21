package com.lightterm.ui.session

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.GridLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.lightterm.R
import com.lightterm.appContainer
import com.lightterm.core.session.RemoteDirectoryListing
import com.lightterm.core.session.RemoteFileEntry
import com.lightterm.core.session.RemoteFileKind
import com.lightterm.core.session.RemoteTextFile
import com.lightterm.core.session.remoteParentPath
import com.lightterm.databinding.DialogRemoteFileEditorBinding
import com.lightterm.databinding.DialogRemoteFileManagerBinding
import com.lightterm.databinding.FragmentSessionBinding
import com.lightterm.databinding.ItemRemoteFileBinding
import com.lightterm.domain.model.SessionConnectionState
import com.lightterm.ui.theme.resolveThemeColor
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SessionFragment : Fragment(R.layout.fragment_session) {
    private var _binding: FragmentSessionBinding? = null
    private val binding: FragmentSessionBinding
        get() = checkNotNull(_binding)
    private var followTerminalOutput = true
    private var lastSubmitAtMs = 0L
    private var fileManagerDialog: Dialog? = null
    private var fileEditorDialog: AlertDialog? = null
    private var fileManagerBinding: DialogRemoteFileManagerBinding? = null
    private var fileManagerJob: Job? = null
    private var currentRemoteListing: RemoteDirectoryListing? = null
    private var currentRemoteDirectory: String? = null
    private var remoteHomeDirectory: String? = null
    private var currentRemoteEntries: List<RemoteFileEntry> = emptyList()
    private var fileManagerSortMode = FileManagerSortMode.NAME_ASC
    private var fileManagerBusy = false
    private var pendingUploadDirectory: String? = null
    private var pendingDownloadEntry: RemoteFileEntry? = null
    private val recentRemoteDirectories = mutableListOf<String>()
    private val recentRemoteFiles = mutableListOf<String>()

    private val openLocalDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        handleSelectedLocalDocument(uri)
    }
    private val createLocalDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        handleCreatedLocalDocument(uri)
    }

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
        parentFragmentManager.setFragmentResultListener(
            REQUEST_KEY_OPEN_FILE_MANAGER,
            viewLifecycleOwner,
        ) { _, result ->
            if (result.getString(RESULT_KEY_SESSION_ID) == sessionId) {
                showFileManagerDialog()
            }
        }
    }

    override fun onDestroyView() {
        fileManagerJob?.cancel()
        fileManagerDialog?.dismiss()
        fileEditorDialog?.dismiss()
        fileManagerDialog = null
        fileEditorDialog = null
        fileManagerBinding = null
        currentRemoteListing = null
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

        if (state.connectionState != SessionConnectionState.CONNECTED && fileManagerBinding != null) {
            setFileManagerBusy(false)
            showFileManagerStatus(getString(R.string.file_manager_status_disconnected))
            updateFileManagerControls()
        }
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

    private fun showFileManagerDialog() {
        if (fileManagerDialog?.isShowing == true) {
            return
        }

        val dialogBinding = DialogRemoteFileManagerBinding.inflate(layoutInflater)
        fileManagerBinding = dialogBinding
        dialogBinding.currentPathText.text = currentRemoteDirectory.orEmpty()
        dialogBinding.sortFilesButton.setOnClickListener {
            if (!fileManagerBusy) {
                showSortModeMenu(dialogBinding.sortFilesButton)
            }
        }
        dialogBinding.refreshFilesButton.setOnClickListener {
            if (!fileManagerBusy) {
                loadRemoteDirectory(currentRemoteDirectory)
            }
        }
        dialogBinding.homeDirectoryButton.setOnClickListener {
            val homePath = remoteHomeDirectory ?: return@setOnClickListener
            if (!fileManagerBusy) {
                loadRemoteDirectory(homePath)
            }
        }
        dialogBinding.upDirectoryButton.setOnClickListener {
            val parentPath = currentRemoteDirectory?.let(::remoteParentPath) ?: return@setOnClickListener
            if (!fileManagerBusy) {
                loadRemoteDirectory(parentPath)
            }
        }
        dialogBinding.uploadFileButton.setOnClickListener {
            if (fileManagerBusy) {
                return@setOnClickListener
            }
            pendingUploadDirectory = currentRemoteDirectory
            openLocalDocumentLauncher.launch(arrayOf("*/*"))
        }
        val dialog = Dialog(requireContext()).apply {
            setContentView(dialogBinding.root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setCancelable(true)
            setCanceledOnTouchOutside(true)
        }
        dialogBinding.closeFileManagerButton.setOnClickListener { dialog.dismiss() }
        renderSortMode()
        renderHistorySections()
        updateFileManagerControls()

        dialog.setOnDismissListener {
            if (fileManagerDialog === dialog) {
                fileManagerDialog = null
                fileManagerBinding = null
                fileManagerBusy = false
            }
        }
        fileManagerDialog = dialog
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        loadRemoteDirectory(currentRemoteDirectory)
    }

    private fun loadRemoteDirectory(path: String?) {
        val dialogBinding = fileManagerBinding ?: return
        fileManagerJob?.cancel()
        setFileManagerBusy(true)
        showFileManagerStatus(getString(R.string.file_manager_status_loading))

        fileManagerJob = viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                viewModel.listRemoteDirectory(path)
            }.onSuccess { listing ->
                if (fileManagerBinding !== dialogBinding) {
                    return@onSuccess
                }
                applyRemoteDirectoryListing(listing)
                setFileManagerBusy(false)
                showCurrentListingStatus()
            }.onFailure { throwable ->
                if (fileManagerBinding !== dialogBinding) {
                    return@onFailure
                }
                setFileManagerBusy(false)
                showFileManagerStatus(readableMessage(throwable))
                showToast(readableMessage(throwable))
            }
        }
    }

    private fun applyRemoteDirectoryListing(listing: RemoteDirectoryListing) {
        val dialogBinding = fileManagerBinding ?: return
        currentRemoteListing = listing
        remoteHomeDirectory = listing.homePath
        currentRemoteDirectory = listing.currentPath
        recordRecentDirectory(listing.currentPath)
        dialogBinding.currentPathText.text = listing.currentPath
        renderSortMode()
        renderHistorySections()
        currentRemoteEntries = sortRemoteFileEntriesForDisplay(
            entries = listing.entries,
            sortMode = fileManagerSortMode,
        )
        dialogBinding.emptyFileText.isVisible = currentRemoteEntries.isEmpty()
        dialogBinding.fileGridContainer.removeAllViews()
        dialogBinding.fileGridContainer.columnCount = FILE_GRID_COLUMN_COUNT

        currentRemoteEntries.forEachIndexed { index, entry ->
            val itemBinding = ItemRemoteFileBinding.inflate(
                layoutInflater,
                dialogBinding.fileGridContainer,
                false,
            )
            itemBinding.fileTypeIcon.setImageResource(iconForRemoteFileKind(entry.kind))
            itemBinding.fileNameText.text = entry.name
            itemBinding.fileMetaText.text = buildRemoteFileMeta(entry)
            itemBinding.fileMetaText.isVisible = itemBinding.fileMetaText.text.isNotBlank()
            itemBinding.primaryFileActionButton.contentDescription =
                getString(R.string.file_manager_cd_open_entry, entry.name)
            itemBinding.secondaryFileActionButton.isVisible = !entry.isDirectory
            itemBinding.secondaryFileActionButton.contentDescription =
                getString(R.string.file_manager_cd_download_entry, entry.name)
            itemBinding.root.layoutParams = buildGridItemLayoutParams(index)

            itemBinding.root.setOnClickListener {
                if (fileManagerBusy) {
                    return@setOnClickListener
                }
                if (entry.isDirectory) {
                    loadRemoteDirectory(entry.path)
                } else {
                    openRemoteFile(entry.path, entry.name)
                }
            }
            itemBinding.primaryFileActionButton.setOnClickListener {
                if (entry.isDirectory) {
                    if (!fileManagerBusy) {
                        loadRemoteDirectory(entry.path)
                    }
                } else if (!fileManagerBusy) {
                    openRemoteFile(entry.path, entry.name)
                }
            }
            itemBinding.secondaryFileActionButton.setOnClickListener {
                if (!fileManagerBusy) {
                    pendingDownloadEntry = entry
                    createLocalDocumentLauncher.launch(entry.name)
                }
            }
            dialogBinding.fileGridContainer.addView(itemBinding.root)
        }
        updateFileManagerControls()
    }

    private fun openRemoteFile(
        remoteFilePath: String,
        displayName: String,
    ) {
        setFileManagerBusy(true)
        showFileManagerStatus(getString(R.string.file_manager_status_opening, displayName))

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                viewModel.readRemoteTextFile(remoteFilePath)
            }.onSuccess { remoteFile ->
                recordRecentFile(remoteFile.path)
                renderHistorySections()
                setFileManagerBusy(false)
                showCurrentListingStatus()
                showRemoteFileEditor(remoteFile)
            }.onFailure { throwable ->
                setFileManagerBusy(false)
                showFileManagerStatus(readableMessage(throwable))
                showToast(readableMessage(throwable))
            }
        }
    }

    private fun showRemoteFileEditor(remoteFile: RemoteTextFile) {
        fileEditorDialog?.dismiss()
        val editorBinding = DialogRemoteFileEditorBinding.inflate(layoutInflater)
        editorBinding.remoteFilePathText.text = remoteFile.path
        editorBinding.remoteFileMetaText.text = buildRemoteFileMeta(
            RemoteFileEntry(
                name = remoteFile.name,
                path = remoteFile.path,
                kind = RemoteFileKind.FILE,
                sizeBytes = remoteFile.sizeBytes,
                modifiedAtEpochMs = remoteFile.modifiedAtEpochMs,
            ),
        )
        editorBinding.remoteFileContentInput.setText(remoteFile.content)
        editorBinding.remoteFileEditorStatusText.text = ""
        editorBinding.remoteFileEditorStatusText.isVisible = false

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.file_editor_title) + " · " + remoteFile.name)
            .setView(editorBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.file_editor_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val updatedContent = editorBinding.remoteFileContentInput.text?.toString().orEmpty()
                editorBinding.remoteFileEditorStatusText.isVisible = true
                editorBinding.remoteFileEditorStatusText.text =
                    getString(R.string.file_manager_status_saving, remoteFile.name)
                editorBinding.remoteFileContentInput.isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false

                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching {
                        viewModel.writeRemoteTextFile(remoteFile.path, updatedContent)
                    }.onSuccess {
                        showToast(getString(R.string.file_manager_message_saved, remoteFile.name))
                        dialog.dismiss()
                        currentRemoteDirectory?.let(::loadRemoteDirectory)
                    }.onFailure { throwable ->
                        editorBinding.remoteFileEditorStatusText.text = readableMessage(throwable)
                        editorBinding.remoteFileContentInput.isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            if (fileEditorDialog === dialog) {
                fileEditorDialog = null
            }
        }
        fileEditorDialog = dialog
        dialog.show()
    }

    private fun handleSelectedLocalDocument(uri: Uri?) {
        val directoryPath = pendingUploadDirectory
        pendingUploadDirectory = null
        if (uri == null || directoryPath.isNullOrBlank() || _binding == null) {
            return
        }

        val originalName = resolveDisplayName(uri)
            ?.let(::sanitizeRemoteFileName)
            ?.takeIf { it.isNotBlank() }
        if (originalName == null) {
            showToast(getString(R.string.file_manager_message_missing_local_name))
            return
        }

        if (currentRemoteEntries.any { it.name == originalName }) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.file_manager_message_overwrite_title)
                .setMessage(getString(R.string.file_manager_message_overwrite_body, originalName))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    uploadLocalDocument(uri, directoryPath, originalName)
                }
                .show()
            return
        }

        uploadLocalDocument(uri, directoryPath, originalName)
    }

    private fun uploadLocalDocument(
        uri: Uri,
        remoteDirectoryPath: String,
        remoteFileName: String,
    ) {
        setFileManagerBusy(true)
        showFileManagerStatus(getString(R.string.file_manager_status_uploading, remoteFileName))

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException(getString(R.string.file_manager_error_open_local_input))
                try {
                    viewModel.uploadRemoteFile(remoteDirectoryPath, remoteFileName, inputStream)
                } finally {
                    runCatching { inputStream.close() }
                }
            }.onSuccess {
                showToast(getString(R.string.file_manager_message_upload_done, remoteFileName))
                loadRemoteDirectory(remoteDirectoryPath)
            }.onFailure { throwable ->
                setFileManagerBusy(false)
                showFileManagerStatus(readableMessage(throwable))
                showToast(readableMessage(throwable))
            }
        }
    }

    private fun handleCreatedLocalDocument(uri: Uri?) {
        val entry = pendingDownloadEntry
        pendingDownloadEntry = null
        if (uri == null || entry == null || _binding == null) {
            return
        }

        setFileManagerBusy(true)
        showFileManagerStatus(getString(R.string.file_manager_status_downloading, entry.name))

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val outputStream = requireContext().contentResolver.openOutputStream(uri, "w")
                    ?: throw IllegalStateException(getString(R.string.file_manager_error_open_local_output))
                try {
                    viewModel.downloadRemoteFile(entry.path, outputStream)
                } finally {
                    runCatching { outputStream.close() }
                }
            }.onSuccess {
                setFileManagerBusy(false)
                showCurrentListingStatus()
                showToast(getString(R.string.file_manager_message_download_done, entry.name))
            }.onFailure { throwable ->
                setFileManagerBusy(false)
                showFileManagerStatus(readableMessage(throwable))
                showToast(readableMessage(throwable))
            }
        }
    }

    private fun setFileManagerBusy(isBusy: Boolean) {
        fileManagerBusy = isBusy
        val dialogBinding = fileManagerBinding ?: return
        dialogBinding.filesProgressIndicator.isVisible = isBusy
        updateFileManagerControls()
    }

    private fun updateFileManagerControls() {
        val dialogBinding = fileManagerBinding ?: return
        val isConnected = viewModel.uiState.value.connectionState == SessionConnectionState.CONNECTED
        dialogBinding.refreshFilesButton.isEnabled = isConnected && !fileManagerBusy
        dialogBinding.sortFilesButton.isEnabled = isConnected && !fileManagerBusy
        dialogBinding.uploadFileButton.isEnabled =
            isConnected && !fileManagerBusy && !currentRemoteDirectory.isNullOrBlank()
        dialogBinding.homeDirectoryButton.isEnabled =
            isConnected && !fileManagerBusy && !remoteHomeDirectory.isNullOrBlank()
        dialogBinding.upDirectoryButton.isEnabled =
            isConnected && !fileManagerBusy && currentRemoteDirectory?.let(::remoteParentPath) != null
    }

    private fun showFileManagerStatus(message: String) {
        val dialogBinding = fileManagerBinding ?: return
        dialogBinding.filesStatusText.text = message
    }

    private fun buildRemoteFileMeta(entry: RemoteFileEntry): String {
        val context = context ?: return ""
        val parts = buildList {
            if (!entry.isDirectory) {
                entry.sizeBytes?.let { add(Formatter.formatShortFileSize(context, it)) }
            }
            entry.modifiedAtEpochMs?.let { modifiedAt ->
                add(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(modifiedAt)))
            }
        }
        return parts.joinToString(" · ")
    }

    private fun buildGridItemLayoutParams(index: Int): GridLayout.LayoutParams {
        val column = index % FILE_GRID_COLUMN_COUNT
        return GridLayout.LayoutParams().apply {
            width = 0
            height = GridLayout.LayoutParams.WRAP_CONTENT
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(
                if (column == 0) 0 else dpToPx(5),
                0,
                if (column == FILE_GRID_COLUMN_COUNT - 1) 0 else dpToPx(5),
                dpToPx(10),
            )
        }
    }

    private fun renderSortMode() {
        val dialogBinding = fileManagerBinding ?: return
        dialogBinding.fileSortText.text = getString(fileManagerSortMode.labelRes)
    }

    private fun showSortModeMenu(anchor: View) {
        val popupMenu = PopupMenu(
            ContextThemeWrapper(requireContext(), R.style.ThemeOverlay_LightTerm_ToolbarMenu),
            anchor,
        )
        FileManagerSortMode.values().forEachIndexed { index, mode ->
            popupMenu.menu.add(0, index, index, getString(mode.labelRes))
        }
        popupMenu.setOnMenuItemClickListener { item ->
            val selectedMode = FileManagerSortMode.values().getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
            if (selectedMode != fileManagerSortMode) {
                fileManagerSortMode = selectedMode
                renderSortMode()
                currentRemoteListing?.let(::applyRemoteDirectoryListing)
                showCurrentListingStatus()
            }
            true
        }
        popupMenu.show()
    }

    private fun renderHistorySections() {
        val dialogBinding = fileManagerBinding ?: return
        dialogBinding.recentDirectoryCard.isVisible = recentRemoteDirectories.isNotEmpty()
        dialogBinding.recentFileCard.isVisible = recentRemoteFiles.isNotEmpty()
        dialogBinding.recentDirectoryHistoryRow.removeAllViews()
        dialogBinding.recentFileHistoryRow.removeAllViews()

        recentRemoteDirectories.forEach { path ->
            dialogBinding.recentDirectoryHistoryRow.addView(
                createHistoryButton(
                    label = historyLabelForDirectory(path),
                    iconRes = R.drawable.ic_file_folder_20,
                    onClick = { loadRemoteDirectory(path) },
                ),
            )
        }
        recentRemoteFiles.forEach { path ->
            dialogBinding.recentFileHistoryRow.addView(
                createHistoryButton(
                    label = historyLabelForFile(path),
                    iconRes = R.drawable.ic_file_text_20,
                    onClick = { openRemoteFile(path, historyLabelForFile(path)) },
                ),
            )
        }
    }

    private fun createHistoryButton(
        label: String,
        iconRes: Int,
        onClick: () -> Unit,
    ): MaterialButton {
        val context = requireContext()
        return MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = label
            isAllCaps = false
            minimumWidth = 0
            minHeight = dpToPx(34)
            minimumHeight = dpToPx(34)
            cornerRadius = dpToPx(16)
            insetTop = 0
            insetBottom = 0
            iconPadding = dpToPx(6)
            setIconResource(iconRes)
            setTextColor(context.resolveThemeColor(R.attr.lightTermOnSurface))
            iconTint = ColorStateList.valueOf(context.resolveThemeColor(R.attr.lightTermOnSurfaceVariant))
            backgroundTintList = ColorStateList.valueOf(context.resolveThemeColor(R.attr.lightTermChipFill))
            strokeColor = ColorStateList.valueOf(context.resolveThemeColor(R.attr.lightTermChipStroke))
            layoutParams = GridLayout.LayoutParams().apply {
                width = GridLayout.LayoutParams.WRAP_CONTENT
                height = GridLayout.LayoutParams.WRAP_CONTENT
            }
            setPadding(dpToPx(12), 0, dpToPx(12), 0)
            val marginParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            marginParams.marginEnd = dpToPx(8)
            layoutParams = marginParams
            setOnClickListener {
                if (!fileManagerBusy) {
                    onClick()
                }
            }
        }
    }

    private fun recordRecentDirectory(path: String) {
        recordRecentTarget(recentRemoteDirectories, path)
    }

    private fun recordRecentFile(path: String) {
        recordRecentTarget(recentRemoteFiles, path)
    }

    private fun recordRecentTarget(
        targets: MutableList<String>,
        path: String,
    ) {
        targets.remove(path)
        targets.add(0, path)
        if (targets.size > MAX_HISTORY_ENTRIES) {
            targets.subList(MAX_HISTORY_ENTRIES, targets.size).clear()
        }
    }

    private fun showCurrentListingStatus() {
        showFileManagerStatus(
            getString(
                R.string.file_manager_status_items_sorted,
                currentRemoteEntries.size,
                getString(fileManagerSortMode.labelRes),
            ),
        )
    }

    private fun historyLabelForDirectory(path: String): String {
        val normalized = path.trim().trimEnd('/').ifBlank { "/" }
        if (normalized == "/") {
            return getString(R.string.file_manager_history_root)
        }
        if (normalized == remoteHomeDirectory) {
            return getString(R.string.file_manager_history_home)
        }
        return normalized.substringAfterLast('/')
    }

    private fun historyLabelForFile(path: String): String {
        val normalized = path.trim().trimEnd('/')
        return normalized.substringAfterLast('/')
    }

    private fun iconForRemoteFileKind(kind: RemoteFileKind): Int = when (kind) {
        RemoteFileKind.DIRECTORY -> R.drawable.ic_file_folder_20
        RemoteFileKind.FILE -> R.drawable.ic_file_text_20
        RemoteFileKind.SYMLINK -> R.drawable.ic_file_link_20
    }

    private fun sanitizeRemoteFileName(name: String): String {
        return name.replace('/', '_').replace('\\', '_').trim()
    }

    private fun resolveDisplayName(uri: Uri): String? {
        requireContext().contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (columnIndex >= 0) {
                    return cursor.getString(columnIndex)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun readableMessage(throwable: Throwable): String {
        return throwable.message?.takeIf { it.isNotBlank() } ?: getString(R.string.session_status_connection_failed)
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
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
        const val REQUEST_KEY_OPEN_FILE_MANAGER = "open_file_manager"
        const val RESULT_KEY_SESSION_ID = "request_session_id"
        private const val ARG_SESSION_ID = "session_id"
        private const val SUBMIT_DEBOUNCE_MS = 250L
        private const val MAX_HISTORY_ENTRIES = 6
        private const val FILE_GRID_COLUMN_COUNT = 2

        fun newInstance(sessionId: String): SessionFragment {
            return SessionFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SESSION_ID, sessionId)
                }
            }
        }
    }
}
