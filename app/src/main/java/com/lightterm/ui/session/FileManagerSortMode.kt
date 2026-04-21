package com.lightterm.ui.session

import com.lightterm.R
import com.lightterm.core.session.RemoteFileEntry

enum class FileManagerSortMode(
    val labelRes: Int,
) {
    NAME_ASC(R.string.file_manager_sort_name),
    MODIFIED_DESC(R.string.file_manager_sort_modified),
    SIZE_DESC(R.string.file_manager_sort_size),
}

fun sortRemoteFileEntriesForDisplay(
    entries: List<RemoteFileEntry>,
    sortMode: FileManagerSortMode,
): List<RemoteFileEntry> {
    return when (sortMode) {
        FileManagerSortMode.NAME_ASC -> {
            entries.sortedWith(compareBy<RemoteFileEntry>({ !it.isDirectory }, { it.name.lowercase() }, { it.name }))
        }

        FileManagerSortMode.MODIFIED_DESC -> {
            entries.sortedWith(
                compareBy<RemoteFileEntry>({ !it.isDirectory })
                    .thenByDescending { it.modifiedAtEpochMs ?: Long.MIN_VALUE }
                    .thenBy { it.name.lowercase() },
            )
        }

        FileManagerSortMode.SIZE_DESC -> {
            entries.sortedWith(
                compareBy<RemoteFileEntry>({ !it.isDirectory })
                    .thenByDescending { it.sizeBytes ?: Long.MIN_VALUE }
                    .thenBy { it.name.lowercase() },
            )
        }
    }
}
