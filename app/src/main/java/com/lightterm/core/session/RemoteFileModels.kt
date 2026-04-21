package com.lightterm.core.session

data class RemoteDirectoryListing(
    val homePath: String,
    val currentPath: String,
    val entries: List<RemoteFileEntry>,
)

data class RemoteFileEntry(
    val name: String,
    val path: String,
    val kind: RemoteFileKind,
    val sizeBytes: Long?,
    val modifiedAtEpochMs: Long?,
) {
    val isDirectory: Boolean
        get() = kind == RemoteFileKind.DIRECTORY
}

enum class RemoteFileKind {
    DIRECTORY,
    FILE,
    SYMLINK,
}

data class RemoteTextFile(
    val name: String,
    val path: String,
    val content: String,
    val sizeBytes: Long,
    val modifiedAtEpochMs: Long?,
)

internal const val MAX_INLINE_TEXT_FILE_BYTES = 512 * 1024

fun remoteParentPath(path: String): String? {
    val normalized = normalizeRemotePath(path)
    if (normalized == "/") {
        return null
    }
    val separatorIndex = normalized.lastIndexOf('/')
    return if (separatorIndex <= 0) {
        "/"
    } else {
        normalized.substring(0, separatorIndex)
    }
}

internal fun remoteBaseName(path: String): String {
    val normalized = normalizeRemotePath(path)
    if (normalized == "/") {
        return "/"
    }
    return normalized.substringAfterLast('/')
}

internal fun remoteChildPath(parent: String, name: String): String {
    val normalizedParent = normalizeRemotePath(parent)
    return if (normalizedParent == "/") {
        "/$name"
    } else {
        "$normalizedParent/$name"
    }
}

internal fun sortRemoteFileEntries(entries: List<RemoteFileEntry>): List<RemoteFileEntry> {
    return entries.sortedWith(
        compareBy<RemoteFileEntry>({ !it.isDirectory }, { it.name.lowercase() }, { it.name }),
    )
}

private fun normalizeRemotePath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isEmpty() || trimmed == "/") {
        return "/"
    }
    val withoutTrailingSlash = trimmed.trimEnd('/')
    return if (withoutTrailingSlash.isEmpty()) "/" else withoutTrailingSlash
}
