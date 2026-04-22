package com.lightterm.ui.session

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.text.format.Formatter
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.lightterm.R
import com.lightterm.appContainer
import com.lightterm.databinding.DialogCommandTemplateBinding
import com.lightterm.databinding.DialogHistorySearchBinding
import com.lightterm.core.session.RemoteDirectoryListing
import com.lightterm.core.session.RemoteFileEntry
import com.lightterm.core.session.RemoteFileKind
import com.lightterm.core.session.RemoteTextFile
import com.lightterm.core.session.remoteParentPath
import com.lightterm.databinding.DialogRemoteFileEditorBinding
import com.lightterm.databinding.DialogRemoteFileManagerBinding
import com.lightterm.databinding.FragmentSessionBinding
import com.lightterm.databinding.ItemCommandHistoryBinding
import com.lightterm.databinding.ItemCommandTemplateBinding
import com.lightterm.databinding.ItemRemoteFileBinding
import com.lightterm.databinding.ItemTerminalSearchResultBinding
import com.lightterm.domain.model.CommandTemplate
import com.lightterm.domain.model.CommandTemplatePlaceholder
import com.lightterm.domain.model.SessionConnectionState
import com.lightterm.domain.model.renderCommandTemplate
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
    private var isCommandComposerVisible = false
    private var fileManagerDialog: Dialog? = null
    private var fileEditorDialog: AlertDialog? = null
    private var commandTemplateDialog: AlertDialog? = null
    private var historySearchDialog: AlertDialog? = null
    private var fileManagerBinding: DialogRemoteFileManagerBinding? = null
    private var fileManagerJob: Job? = null
    private var currentRemoteListing: RemoteDirectoryListing? = null
    private var currentRemoteDirectory: String? = null
    private var remoteHomeDirectory: String? = null
    private var currentRemoteEntries: List<RemoteFileEntry> = emptyList()
    private var fileManagerSortMode = FileManagerSortMode.MODIFIED_DESC
    private var fileManagerBusy = false
    private var pendingUploadDirectory: String? = null
    private var pendingDownloadEntry: RemoteFileEntry? = null
    private val recentRemoteDirectories = mutableListOf<String>()
    private val recentRemoteFiles = mutableListOf<String>()
    private var editingCommandTemplateId: String? = null
    private var historySearchMode = HistorySearchMode.COMMAND_HISTORY

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
            commandTemplateRepository = requireContext().appContainer.commandTemplateRepository,
            commandHistoryRepository = requireContext().appContainer.commandHistoryRepository,
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSessionBinding.bind(view)
        isCommandComposerVisible = savedInstanceState?.getBoolean(STATE_COMMAND_COMPOSER_VISIBLE) ?: false

        binding.terminalView.setTerminalFontSize(
            requireContext().appContainer.appSettingsRepository.settings.value.terminalFontSizeSp,
        )
        binding.terminalView.onTerminalSizeChanged = viewModel::resize
        binding.terminalView.onFontScaleStepRequested = { step ->
            requireContext().appContainer.appSettingsRepository.adjustTerminalFont(step)
        }
        binding.terminalView.onInputSequence = viewModel::sendTerminalInput
        binding.terminalView.setOnClickListener {
            followTerminalOutput = true
            scrollTerminalToBottom()
        }
        binding.commandInput.doAfterTextChanged {
            updateCommandComposerState()
            renderCommandAutocomplete()
        }
        binding.commandInput.setOnEditorActionListener { _, actionId, event ->
            val isSendAction =
                actionId == EditorInfo.IME_ACTION_SEND ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER)
            if (isSendAction) {
                sendCommandFromComposer()
                true
            } else {
                false
            }
        }
        binding.sendCommandButton.setOnClickListener {
            sendCommandFromComposer()
        }
        binding.closeCommandComposerButton.setOnClickListener {
            hideCommandComposer()
        }
        renderCommandComposerVisibility()
        restoreRemoteFileHistory()
        binding.terminalScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            binding.terminalView.updateViewport(binding.terminalScroll.width, binding.terminalScroll.height)
            if (followTerminalOutput) {
                scrollTerminalToBottom()
            }
        }
        binding.terminalScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            followTerminalOutput = isTerminalNearBottom()
        }
        binding.reconnectButton.setOnClickListener {
            viewModel.reconnect()
        }
        binding.refreshButton.setOnClickListener {
            viewModel.refresh()
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
        parentFragmentManager.setFragmentResultListener(
            REQUEST_KEY_OPEN_COMMAND_TEMPLATES,
            viewLifecycleOwner,
        ) { _, result ->
            if (result.getString(RESULT_KEY_SESSION_ID) == sessionId) {
                showDialogAfterMenuDismiss(::showCommandTemplateDialog)
            }
        }
        parentFragmentManager.setFragmentResultListener(
            REQUEST_KEY_OPEN_HISTORY_SEARCH,
            viewLifecycleOwner,
        ) { _, result ->
            if (result.getString(RESULT_KEY_SESSION_ID) == sessionId) {
                showDialogAfterMenuDismiss(::showHistorySearchDialog)
            }
        }
        parentFragmentManager.setFragmentResultListener(
            REQUEST_KEY_OPEN_COMMAND_COMPOSER,
            viewLifecycleOwner,
        ) { _, result ->
            if (result.getString(RESULT_KEY_SESSION_ID) == sessionId) {
                showDialogAfterMenuDismiss(::showCommandComposer)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_COMMAND_COMPOSER_VISIBLE, isCommandComposerVisible)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        fileManagerJob?.cancel()
        fileManagerDialog?.dismiss()
        fileEditorDialog?.dismiss()
        commandTemplateDialog?.dismiss()
        historySearchDialog?.dismiss()
        fileManagerDialog = null
        fileEditorDialog = null
        commandTemplateDialog = null
        historySearchDialog = null
        fileManagerBinding = null
        currentRemoteListing = null
        _binding?.terminalView?.onTerminalSizeChanged = null
        _binding?.terminalView?.onFontScaleStepRequested = null
        _binding?.terminalView?.onInputSequence = null
        _binding = null
        super.onDestroyView()
    }

    private fun render(state: com.lightterm.core.session.SessionUiState) {
        val stickToBottom = followTerminalOutput || isTerminalNearBottom()

        binding.terminalView.renderSnapshot(state.terminalSnapshot)
        binding.terminalView.setInputEnabled(state.inputEnabled)
        binding.commandInputLayout.isEnabled = true
        binding.commandInput.isEnabled = true
        updateCommandComposerState()
        renderCommandAutocomplete()
        if (stickToBottom) {
            scrollTerminalToBottom()
            followTerminalOutput = true
        }

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
        requireContext().appContainer.remoteFileHistoryRepository.recordDirectory(
            server = viewModel.uiState.value.server,
            path = path,
        )
    }

    private fun recordRecentFile(path: String) {
        recordRecentTarget(recentRemoteFiles, path)
        requireContext().appContainer.remoteFileHistoryRepository.recordFile(
            server = viewModel.uiState.value.server,
            path = path,
        )
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

    private fun restoreRemoteFileHistory() {
        val history = requireContext().appContainer.remoteFileHistoryRepository.historyFor(
            viewModel.uiState.value.server,
        )
        recentRemoteDirectories.clear()
        recentRemoteDirectories.addAll(history.directories.take(MAX_HISTORY_ENTRIES))
        recentRemoteFiles.clear()
        recentRemoteFiles.addAll(history.files.take(MAX_HISTORY_ENTRIES))
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

    private fun showCommandTemplateDialog() {
        commandTemplateDialog?.dismiss()
        editingCommandTemplateId = null
        val dialogBinding = DialogCommandTemplateBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.command_template_title)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun refresh() {
            renderCommandTemplateList(dialogBinding, viewModel.commandTemplates.value)
            syncCommandTemplateEditor(dialogBinding)
        }

        dialogBinding.saveTemplateButton.setOnClickListener {
            val label = dialogBinding.templateLabelInput.text?.toString().orEmpty()
            val template = dialogBinding.templateCommandInput.text?.toString().orEmpty()
            val message = editingCommandTemplateId?.let { templateId ->
                viewModel.updateCommandTemplate(
                    id = templateId,
                    label = label,
                    template = template,
                )
            } ?: viewModel.addCommandTemplate(
                label = label,
                template = template,
            )
            if (message != null) {
                showToast(message)
                return@setOnClickListener
            }
            clearCommandTemplateEditor(dialogBinding)
            refresh()
        }
        dialogBinding.cancelTemplateEditButton.setOnClickListener {
            clearCommandTemplateEditor(dialogBinding)
            refresh()
        }
        dialogBinding.resetTemplatesButton.setOnClickListener {
            viewModel.resetCommandTemplates()
            clearCommandTemplateEditor(dialogBinding)
            refresh()
        }

        dialog.setOnDismissListener {
            if (commandTemplateDialog === dialog) {
                commandTemplateDialog = null
                editingCommandTemplateId = null
            }
        }
        commandTemplateDialog = dialog
        refresh()
        dialog.show()
    }

    private fun renderCommandTemplateList(
        dialogBinding: DialogCommandTemplateBinding,
        templates: List<CommandTemplate>,
    ) {
        dialogBinding.templateListContainer.removeAllViews()
        dialogBinding.emptyTemplateText.isVisible = templates.isEmpty()

        templates.forEach { template ->
            val itemBinding = ItemCommandTemplateBinding.inflate(
                layoutInflater,
                dialogBinding.templateListContainer,
                false,
            )
            itemBinding.templateLabelText.text = template.label
            itemBinding.templateCommandText.text = template.template
            val placeholderSummary = runCatching(template::placeholders)
                .getOrDefault(emptyList())
                .joinToString(separator = " · ") { placeholder ->
                    placeholder.key + placeholder.defaultValue?.let { "=$it" }.orEmpty()
                }
            itemBinding.templateParamText.text = if (placeholderSummary.isBlank()) {
                getString(R.string.command_template_param_none)
            } else {
                getString(R.string.command_template_param_summary, placeholderSummary)
            }
            itemBinding.root.setOnClickListener {
                useCommandTemplate(template)
            }
            itemBinding.useTemplateButton.setOnClickListener {
                useCommandTemplate(template)
            }
            itemBinding.editTemplateButton.setOnClickListener {
                editingCommandTemplateId = template.id
                dialogBinding.templateLabelInput.setText(template.label)
                dialogBinding.templateCommandInput.setText(template.template)
                dialogBinding.templateCommandInput.text?.length?.let { selectionEnd ->
                    dialogBinding.templateCommandInput.setSelection(selectionEnd)
                }
                syncCommandTemplateEditor(dialogBinding)
            }
            itemBinding.deleteTemplateButton.setOnClickListener {
                viewModel.removeCommandTemplate(template.id)
                if (editingCommandTemplateId == template.id) {
                    clearCommandTemplateEditor(dialogBinding)
                }
                renderCommandTemplateList(dialogBinding, viewModel.commandTemplates.value)
                syncCommandTemplateEditor(dialogBinding)
            }
            dialogBinding.templateListContainer.addView(itemBinding.root)
        }
    }

    private fun syncCommandTemplateEditor(dialogBinding: DialogCommandTemplateBinding) {
        val editingTemplate = viewModel.commandTemplates.value.firstOrNull { it.id == editingCommandTemplateId }
        if (editingCommandTemplateId != null && editingTemplate == null) {
            editingCommandTemplateId = null
        }
        val resolvedEditingTemplate = viewModel.commandTemplates.value.firstOrNull { it.id == editingCommandTemplateId }
        dialogBinding.templateEditorModeText.isVisible = resolvedEditingTemplate != null
        dialogBinding.templateEditorModeText.text = resolvedEditingTemplate?.let {
            getString(R.string.command_template_editing, it.label)
        }.orEmpty()
        dialogBinding.cancelTemplateEditButton.isVisible = resolvedEditingTemplate != null
        dialogBinding.saveTemplateButton.text = getString(
            if (resolvedEditingTemplate != null) {
                R.string.command_template_save
            } else {
                R.string.command_template_add
            },
        )
    }

    private fun clearCommandTemplateEditor(dialogBinding: DialogCommandTemplateBinding) {
        editingCommandTemplateId = null
        dialogBinding.templateLabelInput.setText("")
        dialogBinding.templateCommandInput.setText("")
    }

    private fun useCommandTemplate(template: CommandTemplate) {
        val placeholders = runCatching(template::placeholders).getOrElse { throwable ->
            showToast(readableMessage(throwable))
            return
        }
        if (placeholders.isEmpty()) {
            if (stageCommandInComposer(template.template)) {
                commandTemplateDialog?.dismiss()
            }
            return
        }
        runCatching {
            showCommandTemplateParameterDialog(template, placeholders)
        }.onFailure { throwable ->
            showToast(readableMessage(throwable))
        }
    }

    private fun showCommandTemplateParameterDialog(
        template: CommandTemplate,
        placeholders: List<CommandTemplatePlaceholder>,
    ) {
        if (placeholders.isEmpty()) {
            if (stageCommandInComposer(template.template)) {
                commandTemplateDialog?.dismiss()
            }
            return
        }

        val formContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
        }
        val helpText = TextView(requireContext()).apply {
            text = getString(R.string.command_template_parameter_help)
            setTextColor(requireContext().resolveThemeColor(R.attr.lightTermOnSurfaceVariant))
            textSize = 13f
        }
        formContainer.addView(helpText)

        val valueInputs = linkedMapOf<String, TextInputEditText>()
        placeholders.forEachIndexed { index, placeholder ->
            val inputLayout = TextInputLayout(requireContext()).apply {
                hint = placeholder.displayLabel
                helperText = placeholder.key
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = if (index == 0) dpToPx(12) else dpToPx(10)
                }
            }
            val editText = TextInputEditText(inputLayout.context).apply {
                setText(placeholder.defaultValue.orEmpty())
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                text?.length?.let(::setSelection)
            }
            inputLayout.addView(
                editText,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            valueInputs[placeholder.key] = editText
            formContainer.addView(inputLayout)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(template.label)
            .setView(
                ScrollView(requireContext()).apply {
                    addView(
                        formContainer,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                },
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.command_template_fill, null)
            .setPositiveButton(R.string.send, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (stageCommandInComposer(renderTemplate(template, valueInputs))) {
                    dialog.dismiss()
                    commandTemplateDialog?.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (sendCommandOrStage(renderTemplate(template, valueInputs))) {
                    dialog.dismiss()
                    commandTemplateDialog?.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun renderTemplate(
        template: CommandTemplate,
        valueInputs: Map<String, TextInputEditText>,
    ): String {
        return renderCommandTemplate(
            template = template.template,
            values = valueInputs.mapValues { (_, input) ->
                input.text?.toString().orEmpty()
            },
        )
    }

    private fun showHistorySearchDialog() {
        historySearchDialog?.dismiss()
        historySearchMode = HistorySearchMode.COMMAND_HISTORY
        val dialogBinding = DialogHistorySearchBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.history_search_title)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialogBinding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
                return@addOnButtonCheckedListener
            }
            historySearchMode = when (checkedId) {
                R.id.outputModeButton -> HistorySearchMode.TERMINAL_OUTPUT
                else -> HistorySearchMode.COMMAND_HISTORY
            }
            renderHistorySearchResults(dialogBinding)
        }
        dialogBinding.searchInput.doAfterTextChanged {
            renderHistorySearchResults(dialogBinding)
        }
        dialogBinding.clearHistoryButton.setOnClickListener {
            viewModel.clearCommandHistory()
            renderHistorySearchResults(dialogBinding)
        }

        dialog.setOnDismissListener {
            if (historySearchDialog === dialog) {
                historySearchDialog = null
            }
        }
        historySearchDialog = dialog
        dialog.show()
        dialogBinding.modeToggleGroup.check(R.id.historyModeButton)
        renderHistorySearchResults(dialogBinding)
    }

    private fun renderHistorySearchResults(dialogBinding: DialogHistorySearchBinding) {
        val query = dialogBinding.searchInput.text?.toString().orEmpty()
        dialogBinding.resultListContainer.removeAllViews()
        when (historySearchMode) {
            HistorySearchMode.COMMAND_HISTORY -> {
                val history = viewModel.searchCommandHistory(query)
                dialogBinding.searchInputLayout.hint = getString(R.string.history_search_history_hint)
                dialogBinding.resultStatusText.text = getString(
                    R.string.history_search_history_status,
                    history.size,
                )
                dialogBinding.clearHistoryButton.isVisible = viewModel.currentCommandHistory().isNotEmpty()
                history.forEach { command ->
                    val itemBinding = ItemCommandHistoryBinding.inflate(
                        layoutInflater,
                        dialogBinding.resultListContainer,
                        false,
                    )
                    itemBinding.commandText.text = command
                    itemBinding.fillCommandButton.setOnClickListener {
                        if (stageCommandInComposer(command)) {
                            historySearchDialog?.dismiss()
                        }
                    }
                    itemBinding.sendCommandButton.setOnClickListener {
                        if (sendCommandOrStage(command)) {
                            historySearchDialog?.dismiss()
                        }
                    }
                    dialogBinding.resultListContainer.addView(itemBinding.root)
                }
                dialogBinding.emptyResultText.text = getString(
                    if (query.isBlank()) {
                        R.string.history_search_history_empty
                    } else {
                        R.string.history_search_history_no_match
                    },
                )
            }

            HistorySearchMode.TERMINAL_OUTPUT -> {
                val matches = buildTerminalSearchMatches(query)
                dialogBinding.searchInputLayout.hint = getString(R.string.history_search_output_hint)
                dialogBinding.resultStatusText.text = getString(
                    if (query.isBlank()) {
                        R.string.history_search_output_recent_status
                    } else {
                        R.string.history_search_output_match_status
                    },
                    matches.size,
                )
                dialogBinding.clearHistoryButton.isVisible = false
                matches.forEach { match ->
                    val itemBinding = ItemTerminalSearchResultBinding.inflate(
                        layoutInflater,
                        dialogBinding.resultListContainer,
                        false,
                    )
                    itemBinding.lineNumberText.text = getString(
                        R.string.history_search_output_line_number,
                        match.lineIndex + 1,
                    )
                    itemBinding.lineText.text = match.lineText.ifBlank {
                        getString(R.string.history_search_output_blank_line)
                    }
                    itemBinding.root.setOnClickListener {
                        jumpToTerminalLine(match.lineIndex)
                        historySearchDialog?.dismiss()
                    }
                    dialogBinding.resultListContainer.addView(itemBinding.root)
                }
                dialogBinding.emptyResultText.text = getString(
                    if (query.isBlank()) {
                        R.string.history_search_output_empty
                    } else {
                        R.string.history_search_output_no_match
                    },
                )
            }
        }
        dialogBinding.emptyResultText.isVisible = dialogBinding.resultListContainer.childCount == 0
    }

    private fun buildTerminalSearchMatches(query: String): List<TerminalSearchMatch> {
        val normalizedQuery = query.trim()
        return viewModel.uiState.value.terminalSnapshot.lines
            .mapIndexed { index, line ->
                TerminalSearchMatch(
                    lineIndex = index,
                    lineText = line,
                )
            }
            .asReversed()
            .filter { match ->
                if (normalizedQuery.isBlank()) {
                    match.lineText.isNotBlank()
                } else {
                    match.lineText.contains(normalizedQuery, ignoreCase = true)
                }
            }
            .take(MAX_TERMINAL_SEARCH_RESULTS)
            .toList()
    }

    private fun jumpToTerminalLine(lineIndex: Int) {
        val currentBinding = _binding ?: return
        currentBinding.terminalView.highlightLine(lineIndex)
        followTerminalOutput = false
        currentBinding.terminalScroll.post {
            if (_binding !== currentBinding) {
                return@post
            }
            currentBinding.terminalScroll.smoothScrollTo(
                0,
                currentBinding.terminalView.scrollYForLine(lineIndex),
            )
        }
    }

    private fun sendCommandOrStage(command: String): Boolean {
        val currentBinding = _binding ?: return false
        if (!viewModel.uiState.value.inputEnabled) {
            stageCommandInComposer(command)
            showToast(getString(R.string.command_template_message_staged))
            return true
        }
        followTerminalOutput = true
        scrollTerminalToBottom()
        viewModel.sendCommand(command)
        if (isCommandComposerVisible) {
            scheduleCommandComposerFocus()
        } else {
            currentBinding.terminalView.requestTerminalInput()
        }
        return true
    }

    private fun readableMessage(throwable: Throwable): String {
        return throwable.message?.takeIf { it.isNotBlank() } ?: getString(R.string.session_status_connection_failed)
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun stageCommandInComposer(command: String): Boolean {
        val currentBinding = _binding ?: return false
        showCommandComposer(focus = false)
        currentBinding.commandInput.setText(command)
        currentBinding.commandInput.setSelection(command.length)
        updateCommandComposerState()
        renderCommandAutocomplete()
        scheduleCommandComposerFocus()
        return true
    }

    private fun showCommandComposer() {
        showCommandComposer(focus = true)
    }

    private fun showCommandComposer(focus: Boolean) {
        if (_binding == null) {
            return
        }
        isCommandComposerVisible = true
        renderCommandComposerVisibility()
        updateCommandComposerState()
        renderCommandAutocomplete()
        if (focus) {
            scheduleCommandComposerFocus()
        }
    }

    private fun hideCommandComposer() {
        val currentBinding = _binding ?: return
        isCommandComposerVisible = false
        renderCommandComposerVisibility()
        currentBinding.commandInput.clearFocus()
        hideSoftInput(currentBinding.commandInput)
        if (viewModel.uiState.value.inputEnabled) {
            currentBinding.terminalView.requestTerminalInput()
        }
    }

    private fun renderCommandComposerVisibility() {
        val currentBinding = _binding ?: return
        currentBinding.commandComposerCard.isVisible = isCommandComposerVisible
        if (!isCommandComposerVisible) {
            currentBinding.commandSuggestionRow.removeAllViews()
            currentBinding.commandSuggestionScroll.isVisible = false
        }
    }

    private fun updateCommandComposerState() {
        val currentBinding = _binding ?: return
        currentBinding.sendCommandButton.isEnabled =
            viewModel.uiState.value.inputEnabled &&
                currentBinding.commandInput.text?.toString().orEmpty().isNotBlank()
    }

    private fun focusCommandComposer() {
        val currentBinding = _binding ?: return
        currentBinding.commandInput.requestFocus()
        context?.getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(currentBinding.commandInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun scheduleCommandComposerFocus() {
        val currentBinding = _binding ?: return
        currentBinding.root.post {
            if (_binding !== currentBinding) {
                return@post
            }
            focusCommandComposer()
        }
    }

    private fun sendCommandFromComposer() {
        if (_binding == null) {
            return
        }
        val command = binding.commandInput.text?.toString().orEmpty()
        if (command.isBlank()) {
            updateCommandComposerState()
            return
        }
        if (!viewModel.uiState.value.inputEnabled) {
            showToast(getString(R.string.command_template_message_staged))
            scheduleCommandComposerFocus()
            return
        }
        followTerminalOutput = true
        scrollTerminalToBottom()
        viewModel.sendCommand(command)
        binding.commandInput.setText("")
        updateCommandComposerState()
        renderCommandAutocomplete()
        scheduleCommandComposerFocus()
    }

    private fun renderCommandAutocomplete() {
        val currentBinding = _binding ?: return
        if (!isCommandComposerVisible) {
            currentBinding.commandSuggestionRow.removeAllViews()
            currentBinding.commandSuggestionScroll.isVisible = false
            return
        }
        val suggestions = buildCommandAutocompleteSuggestions(
            query = currentBinding.commandInput.text?.toString().orEmpty(),
        )

        currentBinding.commandSuggestionRow.removeAllViews()
        suggestions.forEach { suggestion ->
            currentBinding.commandSuggestionRow.addView(
                createCommandSuggestionButton(suggestion),
            )
        }
        currentBinding.commandSuggestionScroll.isVisible = suggestions.isNotEmpty()
    }

    private fun buildCommandAutocompleteSuggestions(query: String): List<CommandAutocompleteSuggestion> {
        return CommandAutocompleteEngine.buildSuggestions(
            query = query,
            history = viewModel.currentCommandHistory(),
            templates = viewModel.commandTemplates.value,
            recentRemoteDirectories = recentRemoteDirectories,
            recentRemoteFiles = recentRemoteFiles,
            maxItems = MAX_COMMAND_AUTOCOMPLETE_ITEMS,
        )
    }

    private fun createCommandSuggestionButton(
        suggestion: CommandAutocompleteSuggestion,
    ): MaterialButton {
        val context = requireContext()
        return MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = suggestion.displayLabel
            isAllCaps = false
            minimumWidth = 0
            minHeight = dpToPx(36)
            minimumHeight = dpToPx(36)
            cornerRadius = dpToPx(16)
            insetTop = 0
            insetBottom = 0
            setTextColor(context.resolveThemeColor(R.attr.lightTermOnSurface))
            backgroundTintList = ColorStateList.valueOf(context.resolveThemeColor(R.attr.lightTermChipFill))
            strokeColor = ColorStateList.valueOf(context.resolveThemeColor(R.attr.lightTermChipStroke))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dpToPx(8)
            }
            setOnClickListener {
                if (_binding == null) {
                    return@setOnClickListener
                }
                binding.commandInput.setText(suggestion.fillValue)
                binding.commandInput.setSelection(suggestion.fillValue.length)
                scheduleCommandComposerFocus()
            }
        }
    }

    private fun hideSoftInput(view: View) {
        context?.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun showDialogAfterMenuDismiss(action: () -> Unit) {
        val currentBinding = _binding ?: return
        currentBinding.root.post {
            if (_binding !== currentBinding) {
                return@post
            }
            if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                return@post
            }
            runCatching(action).onFailure { throwable ->
                showToast(readableMessage(throwable))
            }
        }
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
        const val REQUEST_KEY_OPEN_COMMAND_TEMPLATES = "open_command_templates"
        const val REQUEST_KEY_OPEN_HISTORY_SEARCH = "open_history_search"
        const val REQUEST_KEY_OPEN_COMMAND_COMPOSER = "open_command_composer"
        const val RESULT_KEY_SESSION_ID = "request_session_id"
        private const val ARG_SESSION_ID = "session_id"
        private const val STATE_COMMAND_COMPOSER_VISIBLE = "state_command_composer_visible"
        private const val MAX_HISTORY_ENTRIES = 6
        private const val FILE_GRID_COLUMN_COUNT = 2
        private const val MAX_TERMINAL_SEARCH_RESULTS = 60
        private const val MAX_COMMAND_AUTOCOMPLETE_ITEMS = 8

        fun newInstance(sessionId: String): SessionFragment {
            return SessionFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SESSION_ID, sessionId)
                }
            }
        }
    }

    private enum class HistorySearchMode {
        COMMAND_HISTORY,
        TERMINAL_OUTPUT,
    }

    private data class TerminalSearchMatch(
        val lineIndex: Int,
        val lineText: String,
    )
}
