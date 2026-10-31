package com.example.filemanager

import android.os.Environment
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class FileManagerViewModel : ViewModel() {
    private val _currentDirectory = MutableStateFlow(Environment.getExternalStorageDirectory())
    val currentDirectory: StateFlow<File> = _currentDirectory

    private val _files = MutableStateFlow<List<File>>(emptyList())
    val files: StateFlow<List<File>> = _files

    // Clipboard state
    private var clipboardFile: File? = null
    private var isCutOperation: Boolean = false

    private val _hasClipboardData = MutableStateFlow(false)
    val hasClipboardData: StateFlow<Boolean> = _hasClipboardData

    init {
        loadFiles()
    }

    fun loadFiles() {
        _files.value = _currentDirectory.value?.listFiles()?.toList() ?: emptyList()
    }

    fun openDirectory(file: File) {
        if (file.isDirectory) {
            _currentDirectory.value = file
            loadFiles()
        }
    }

    fun navigateUp(): Boolean {
        val parent = _currentDirectory.value?.parentFile
        if (parent != null && parent.canRead()) {
            _currentDirectory.value = parent
            loadFiles()
            return true
        }
        return false
    }

    fun deleteFile(file: File): Boolean {
        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (deleted) loadFiles()
        return deleted
    }

    fun copyFile(file: File) {
        clipboardFile = file
        isCutOperation = false
        _hasClipboardData.value = true
    }

    fun cutFile(file: File) {
        clipboardFile = file
        isCutOperation = true
        _hasClipboardData.value = true
    }

    fun pasteFile(): Boolean {
        val source = clipboardFile ?: return false
        if (!source.exists()) return false

        val target = File(_currentDirectory.value, source.name)
        
        val success = try {
            if (isCutOperation) {
                source.renameTo(target)
            } else {
                source.copyRecursively(target, overwrite = true)
            }
        } catch (e: Exception) {
            false
        }

        if (success) {
            if (isCutOperation) {
                clearClipboard()
            }
            loadFiles()
        }
        return success
    }

    fun clearClipboard() {
        clipboardFile = null
        isCutOperation = false
        _hasClipboardData.value = false
    }
}
