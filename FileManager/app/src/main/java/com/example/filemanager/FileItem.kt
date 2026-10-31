package com.example.filemanager

import java.io.File

/**
 * Representa un elemento del sistema de archivos con metadata relevante.
 */
data class FileItem(
    val file: File,
    val name: String = file.name,
    val isDirectory: Boolean = file.isDirectory,
    val size: Long = if (file.isFile) file.length() else 0L,
    val lastModified: Long = file.lastModified()
)
