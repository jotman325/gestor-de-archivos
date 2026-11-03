package com.example.filemanager

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.StrictMode
import android.os.storage.StorageManager
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class StorageStats(
    val totalBytes: Long = 0,
    val usedBytes: Long = 0,
    val freeBytes: Long = 0,
    val percentageUsed: Float = 0f
)

data class CategorySizeStats(
    val imagesBytes: Long = 0,
    val videosBytes: Long = 0,
    val audioBytes: Long = 0,
    val docsBytes: Long = 0,
    val downloadsBytes: Long = 0,
    val apksBytes: Long = 0,
    val othersBytes: Long = 0
)

data class AppItem(
    val name: String,
    val packageName: String,
    val sourceDir: String
)

data class DuplicateGroup(
    val fileSize: Long,
    val files: List<File>
)

enum class FileCategory {
    ALL, IMAGES, VIDEOS, AUDIO, DOCUMENTS, DOWNLOADS, APKS, OTHERS, APPS, TRASH, FTP_SERVER, DUPLICATES
}

enum class ViewMode {
    LIST, GRID
}

enum class SortOption {
    NAME_ASC, NAME_DESC, DATE_DESC, DATE_ASC, SIZE_DESC, SIZE_ASC
}

class FileManagerViewModel : ViewModel() {
    private val _currentDirectory = MutableStateFlow(Environment.getExternalStorageDirectory())
    val currentDirectory: StateFlow<File> = _currentDirectory

    private val _files = MutableStateFlow<List<File>>(emptyList())
    val files: StateFlow<List<File>> = _files

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private var clipboardFiles: List<File> = emptyList()
    private var isCutOperation: Boolean = false

    private val _hasClipboardData = MutableStateFlow(false)
    val hasClipboardData: StateFlow<Boolean> = _hasClipboardData

    private val _storageStats = MutableStateFlow(StorageStats())
    val storageStats: StateFlow<StorageStats> = _storageStats

    private val _categorySizeStats = MutableStateFlow(CategorySizeStats())
    val categorySizeStats: StateFlow<CategorySizeStats> = _categorySizeStats

    private val _selectedFiles = MutableStateFlow<Set<File>>(emptySet())
    val selectedFiles: StateFlow<Set<File>> = _selectedFiles

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode

    private val _currentCategory = MutableStateFlow(FileCategory.ALL)
    val currentCategory: StateFlow<FileCategory> = _currentCategory

    private val _showHiddenFiles = MutableStateFlow(false)
    val showHiddenFiles: StateFlow<Boolean> = _showHiddenFiles

    private val _viewMode = MutableStateFlow(ViewMode.LIST)
    val viewMode: StateFlow<ViewMode> = _viewMode

    private val _sortOption = MutableStateFlow(SortOption.NAME_ASC)
    val sortOption: StateFlow<SortOption> = _sortOption

    private val _installedApps = MutableStateFlow<List<AppItem>>(emptyList())
    val installedApps: StateFlow<List<AppItem>> = _installedApps

    private val _isFtpRunning = MutableStateFlow(false)
    val isFtpRunning: StateFlow<Boolean> = _isFtpRunning

    private val _ftpAddress = MutableStateFlow("")
    val ftpAddress: StateFlow<String> = _ftpAddress

    private var ftpServer: FtpServer? = null
    private var allScannedFiles: List<File> = emptyList()
    private val trashDir = File(Environment.getExternalStorageDirectory(), ".TrashBin")

    private val _duplicateFiles = MutableStateFlow<List<DuplicateGroup>>(emptyList())
    val duplicateFiles: StateFlow<List<DuplicateGroup>> = _duplicateFiles

    private val _isScanningDuplicates = MutableStateFlow(false)
    val isScanningDuplicates: StateFlow<Boolean> = _isScanningDuplicates

    init {
        if (!trashDir.exists()) trashDir.mkdirs()
        loadFiles()
    }

    fun setSortOption(option: SortOption) {
        _sortOption.value = option
        filterAndSortFiles()
    }

    fun navigateToDirectory(targetFile: File) {
        if (targetFile.exists() && targetFile.isDirectory && targetFile.canRead()) {
            _currentDirectory.value = targetFile
            _currentCategory.value = FileCategory.ALL
            updateSearchQuery("")
            loadFiles()
        }
    }

    // --- ANÁLISIS DE ESPACIO DE ALMACENAMIENTO POR CATEGORÍA ---
    fun analyzeCategorySizes() {
        viewModelScope.launch(Dispatchers.IO) {
            val rootDir = Environment.getExternalStorageDirectory()
            var imgBytes = 0L
            var vidBytes = 0L
            var audBytes = 0L
            var docBytes = 0L
            var apkBytes = 0L
            var othBytes = 0L

            fun scanSizes(dir: File) {
                val list = dir.listFiles() ?: return
                for (file in list) {
                    if (file.name.startsWith(".") || file.name == ".TrashBin") continue
                    if (file.isDirectory) {
                        scanSizes(file)
                    } else if (file.isFile) {
                        val len = file.length()
                        when (file.extension.lowercase()) {
                            "jpg", "jpeg", "png", "webp", "gif", "bmp" -> imgBytes += len
                            "mp4", "mkv", "avi", "mov", "webm" -> vidBytes += len
                            "mp3", "wav", "ogg", "flac", "m4a" -> audBytes += len
                            "pdf", "doc", "docx", "xls", "xlsx", "txt", "ppt", "pptx" -> docBytes += len
                            "apk" -> apkBytes += len
                            else -> othBytes += len
                        }
                    }
                }
            }

            scanSizes(rootDir)

            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            var dwBytes = 0L
            downloadDir.walkTopDown().forEach { if (it.isFile) dwBytes += it.length() }

            launch(Dispatchers.Main) {
                _categorySizeStats.value = CategorySizeStats(
                    imagesBytes = imgBytes,
                    videosBytes = vidBytes,
                    audioBytes = audBytes,
                    docsBytes = docBytes,
                    downloadsBytes = dwBytes,
                    apksBytes = apkBytes,
                    othersBytes = othBytes
                )
            }
        }
    }

    // --- LISTAR TODAS LAS APLICACIONES (SISTEMA + USUARIO) ---
    private fun loadApps(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            // Se eliminan los filtros restrictivos para devolver TODAS las aplicaciones instaladas
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .map { AppItem(it.loadLabel(pm).toString(), it.packageName, it.publicSourceDir) }
                .sortedBy { it.name.lowercase() }
            
            launch(Dispatchers.Main) { _installedApps.value = apps }
        }
    }

    fun extractApp(app: AppItem, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val source = File(app.sourceDir)
                val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dest = File(destDir, "${app.name.replace(" ", "_")}.apk")
                source.copyTo(dest, overwrite = true)
                launch(Dispatchers.Main) { Toast.makeText(context, "APK extraído en Descargas", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { Toast.makeText(context, "Error al extraer APK", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun shareFile(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = context.contentResolver.getType(uri) ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Compartir archivo con:"))
        } catch (e: Exception) {
            Toast.makeText(context, "Error al compartir: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // --- SERVIDOR FTP ---
    fun toggleFtpServer(context: Context) {
        if (_isFtpRunning.value) stopFtpServer() else startFtpServer(context)
    }

    private fun getLocalIpAddress(context: Context): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val ipInt = wifiInfo.ipAddress
        return String.format(
            "%d.%d.%d.%d",
            ipInt and 0xff, ipInt shr 8 and 0xff, ipInt shr 16 and 0xff, ipInt shr 24 and 0xff
        )
    }

    private fun startFtpServer(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ftpServer?.stop()
                ftpServer = null

                val ipAddress = getLocalIpAddress(context)
                if (ipAddress == "0.0.0.0" || ipAddress.isEmpty()) {
                    launch(Dispatchers.Main) { Toast.makeText(context, "Conéctate a WiFi primero", Toast.LENGTH_LONG).show() }
                    return@launch
                }

                val serverFactory = FtpServerFactory()
                val factory = ListenerFactory()
                factory.port = 2121
                serverFactory.addListener("default", factory.createListener())

                val userManagerFactory = PropertiesUserManagerFactory()
                val userManager = userManagerFactory.createUserManager()

                val user = BaseUser()
                user.name = "admin"
                user.password = "1234"
                user.homeDirectory = Environment.getExternalStorageDirectory().absolutePath
                user.authorities = listOf(WritePermission())

                userManager.save(user)
                serverFactory.userManager = userManager

                ftpServer = serverFactory.createServer()
                ftpServer?.start()

                launch(Dispatchers.Main) {
                    _ftpAddress.value = "ftp://$ipAddress:2121"
                    _isFtpRunning.value = true
                    Toast.makeText(context, "Servidor FTP iniciado", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    _isFtpRunning.value = false
                    Toast.makeText(context, "Error FTP: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun stopFtpServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ftpServer?.stop()
                launch(Dispatchers.Main) {
                    _isFtpRunning.value = false
                    _ftpAddress.value = ""
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // --- ANÁLISIS DE DUPLICADOS ---
    private fun getFileHash(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { inputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) { "" }
    }

    fun scanForDuplicates(context: Context) {
        if (_isScanningDuplicates.value) return
        _isScanningDuplicates.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val rootDir = Environment.getExternalStorageDirectory()
            val filesBySize = mutableMapOf<Long, MutableList<File>>()

            fun collectFiles(dir: File) {
                val list = dir.listFiles() ?: return
                for (file in list) {
                    if (file.name.startsWith(".") || file.name == ".TrashBin") continue
                    if (file.isDirectory) collectFiles(file)
                    else if (file.isFile && file.length() > 0) {
                        filesBySize.getOrPut(file.length()) { mutableListOf() }.add(file)
                    }
                }
            }

            collectFiles(rootDir)
            val duplicatesList = mutableListOf<DuplicateGroup>()
            filesBySize.filter { it.value.size > 1 }.forEach { (size, candidateFiles) ->
                val filesByHash = candidateFiles.groupBy { getFileHash(it) }
                filesByHash.filter { it.key.isNotEmpty() && it.value.size > 1 }.forEach { (_, matchedFiles) ->
                    duplicatesList.add(DuplicateGroup(size, matchedFiles))
                }
            }

            duplicatesList.sortByDescending { it.fileSize * it.files.size }

            launch(Dispatchers.Main) {
                _duplicateFiles.value = duplicatesList
                _isScanningDuplicates.value = false
                Toast.makeText(context, "Análisis finalizado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- GESTIÓN DE CATEGORÍAS Y NAVEGACIÓN ---
    fun toggleHiddenFiles() {
        _showHiddenFiles.value = !_showHiddenFiles.value
        if (_currentCategory.value == FileCategory.ALL || _currentCategory.value == FileCategory.TRASH) {
            loadFiles()
        } else if (_currentCategory.value != FileCategory.APPS && _currentCategory.value != FileCategory.FTP_SERVER && _currentCategory.value != FileCategory.DUPLICATES) {
            scanAllFilesByCategory(_currentCategory.value)
        }
    }

    fun toggleViewMode() {
        _viewMode.value = if (_viewMode.value == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
    }

    fun calculateStorageStats(context: Context) {
        try {
            val total: Long
            val free: Long
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                val uuid = StorageManager.UUID_DEFAULT
                val storageStatsManager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
                total = storageStatsManager.getTotalBytes(uuid)
                free = storageStatsManager.getFreeBytes(uuid)
            } else {
                val statFs = StatFs(Environment.getExternalStorageDirectory().path)
                total = statFs.totalBytes
                free = statFs.availableBytes
            }
            val used = total - free
            val percentage = if (total > 0) (used.toFloat() / total.toFloat()) else 0f
            _storageStats.value = StorageStats(total, used, free, percentage)
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun formatSize(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024 * 1024)
        if (gb >= 1.0) return String.format("%.1f GB", gb)
        val mb = bytes / (1024.0 * 1024)
        return String.format("%.1f MB", mb)
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        filterAndSortFiles()
    }

    fun setCategory(category: FileCategory, context: Context? = null) {
        _currentCategory.value = category
        when (category) {
            FileCategory.ALL -> loadFiles()
            FileCategory.DOWNLOADS -> {
                _currentDirectory.value = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                _currentCategory.value = FileCategory.ALL
                loadFiles()
            }
            FileCategory.TRASH -> {
                _currentDirectory.value = trashDir
                loadFiles()
            }
            FileCategory.APPS -> if (context != null) loadApps(context)
            FileCategory.DUPLICATES -> if (context != null) scanForDuplicates(context)
            FileCategory.FTP_SERVER -> {}
            else -> scanAllFilesByCategory(category)
        }
    }

    private fun scanAllFilesByCategory(category: FileCategory) {
        viewModelScope.launch(Dispatchers.IO) {
            val rootDir = Environment.getExternalStorageDirectory()
            val resultFiles = mutableListOf<File>()
            
            fun searchDirectory(dir: File) {
                val files = dir.listFiles() ?: return
                for (file in files) {
                    if (!_showHiddenFiles.value && file.name.startsWith(".")) continue
                    if (file.isDirectory && file.name != "Android" && file.name != ".TrashBin") {
                        searchDirectory(file)
                    } else if (file.isFile) {
                        val ext = file.extension.lowercase()
                        when (category) {
                            FileCategory.IMAGES -> if (ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) resultFiles.add(file)
                            FileCategory.VIDEOS -> if (ext in listOf("mp4", "mkv", "avi", "mov", "webm")) resultFiles.add(file)
                            FileCategory.AUDIO -> if (ext in listOf("mp3", "wav", "ogg", "flac", "m4a")) resultFiles.add(file)
                            FileCategory.DOCUMENTS -> if (ext in listOf("pdf", "doc", "docx", "xls", "xlsx", "txt", "ppt", "pptx")) resultFiles.add(file)
                            FileCategory.APKS -> if (ext == "apk") resultFiles.add(file)
                            FileCategory.OTHERS -> if (ext !in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "mp4", "mkv", "avi", "mov", "webm", "mp3", "wav", "ogg", "flac", "m4a", "pdf", "doc", "docx", "xls", "xlsx", "txt", "ppt", "pptx", "apk")) resultFiles.add(file)
                            else -> {}
                        }
                    }
                }
            }
            
            searchDirectory(rootDir)
            
            launch(Dispatchers.Main) {
                allScannedFiles = resultFiles
                filterAndSortFiles()
                clearSelection()
            }
        }
    }

    fun loadFiles() {
        allScannedFiles = _currentDirectory.value?.listFiles()?.toList() ?: emptyList()
        if (!_showHiddenFiles.value) {
            allScannedFiles = allScannedFiles.filter { !it.name.startsWith(".") }
        }
        filterAndSortFiles()
        clearSelection()
    }

    private fun filterAndSortFiles() {
        if (_currentCategory.value == FileCategory.APPS || _currentCategory.value == FileCategory.FTP_SERVER || _currentCategory.value == FileCategory.DUPLICATES) return

        val list = if (_searchQuery.value.isBlank()) allScannedFiles else allScannedFiles.filter { it.name.contains(_searchQuery.value, ignoreCase = true) }

        val comparator = when (_sortOption.value) {
            SortOption.NAME_ASC -> compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
            SortOption.NAME_DESC -> compareBy<File> { !it.isDirectory }.thenByDescending { it.name.lowercase() }
            SortOption.DATE_DESC -> compareBy<File> { !it.isDirectory }.thenByDescending { it.lastModified() }
            SortOption.DATE_ASC -> compareBy<File> { !it.isDirectory }.thenBy { it.lastModified() }
            SortOption.SIZE_DESC -> compareBy<File> { !it.isDirectory }.thenByDescending { it.length() }
            SortOption.SIZE_ASC -> compareBy<File> { !it.isDirectory }.thenBy { it.length() }
        }

        _files.value = list.sortedWith(comparator)
    }

    fun openDirectory(file: File) {
        if (file.isDirectory) {
            _currentDirectory.value = file
            _currentCategory.value = FileCategory.ALL
            updateSearchQuery("")
            loadFiles()
        }
    }

    fun navigateUp(): Boolean {
        if (_currentCategory.value != FileCategory.ALL) {
            _currentCategory.value = FileCategory.ALL
            _currentDirectory.value = Environment.getExternalStorageDirectory()
            updateSearchQuery("")
            loadFiles()
            return true
        }

        val parent = _currentDirectory.value?.parentFile
        if (parent != null && parent.canRead()) {
            _currentDirectory.value = parent
            updateSearchQuery("")
            loadFiles()
            return true
        }
        return false
    }

    fun selectSingleFile(file: File) {
        _selectedFiles.value = setOf(file)
        _isSelectionMode.value = true
    }

    fun toggleFileSelection(file: File) {
        val currentSelection = _selectedFiles.value.toMutableSet()
        if (currentSelection.contains(file)) {
            currentSelection.remove(file)
            if (currentSelection.isEmpty()) _isSelectionMode.value = false
        } else {
            currentSelection.add(file)
            _isSelectionMode.value = true
        }
        _selectedFiles.value = currentSelection
    }

    fun selectAll() {
        _selectedFiles.value = _files.value.toSet()
        _isSelectionMode.value = true
    }

    fun clearSelection() {
        _selectedFiles.value = emptySet()
        _isSelectionMode.value = false
    }

    fun copySelectedFiles() {
        clipboardFiles = _selectedFiles.value.toList()
        isCutOperation = false
        _hasClipboardData.value = true
        clearSelection()
    }

    fun cutSelectedFiles() {
        clipboardFiles = _selectedFiles.value.toList()
        isCutOperation = true
        _hasClipboardData.value = true
        clearSelection()
    }

    fun pasteFiles(): Boolean {
        if (clipboardFiles.isEmpty()) return false
        var successCount = 0
        clipboardFiles.forEach { source ->
            if (source.exists()) {
                val target = File(_currentDirectory.value, source.name)
                try {
                    val result = if (isCutOperation) source.renameTo(target) else source.copyRecursively(target, overwrite = true)
                    if (result) successCount++
                } catch (e: Exception) { }
            }
        }
        if (successCount > 0) {
            if (isCutOperation) clearClipboard()
            if (_currentCategory.value == FileCategory.ALL || _currentCategory.value == FileCategory.TRASH) loadFiles() else setCategory(_currentCategory.value)
            return true
        }
        return false
    }

    fun clearClipboard() {
        clipboardFiles = emptyList()
        isCutOperation = false
        _hasClipboardData.value = false
    }

    fun deleteSelectedFiles(permanent: Boolean = false) {
        _selectedFiles.value.forEach { file ->
            if (permanent || _currentCategory.value == FileCategory.TRASH) {
                if (file.isDirectory) file.deleteRecursively() else file.delete()
            } else {
                file.renameTo(File(trashDir, file.name))
            }
        }
        clearSelection()
        if (_currentCategory.value == FileCategory.ALL || _currentCategory.value == FileCategory.TRASH) loadFiles() else setCategory(_currentCategory.value)
    }

    fun restoreSelectedFiles() {
        val destDir = Environment.getExternalStorageDirectory()
        _selectedFiles.value.forEach { file -> file.renameTo(File(destDir, file.name)) }
        clearSelection()
        loadFiles()
    }

    fun renameFile(file: File, newName: String) {
        val newFile = File(file.parent, newName)
        if (file.renameTo(newFile)) {
            if (_currentCategory.value == FileCategory.ALL) loadFiles() else setCategory(_currentCategory.value)
        }
    }

    fun zipSelectedFiles(zipName: String, context: Context) {
        if (_selectedFiles.value.isEmpty()) return
        val targetDir = if (_currentCategory.value == FileCategory.ALL) _currentDirectory.value else Environment.getExternalStorageDirectory()
        val zipFile = File(targetDir, if (zipName.endsWith(".zip")) zipName else "$zipName.zip")
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                    _selectedFiles.value.forEach { file -> zipFileOrDirectory(file, file.name, zos) }
                }
                launch(Dispatchers.Main) {
                    clearSelection()
                    if (_currentCategory.value == FileCategory.ALL) loadFiles() else setCategory(_currentCategory.value)
                    Toast.makeText(context, "ZIP creado", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { Toast.makeText(context, "Error al comprimir", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun zipFileOrDirectory(fileToZip: File, fileName: String, zos: ZipOutputStream) {
        if (fileToZip.isHidden) return
        if (fileToZip.isDirectory) {
            val entryName = if (fileName.endsWith("/")) fileName else "$fileName/"
            zos.putNextEntry(ZipEntry(entryName))
            zos.closeEntry()
            fileToZip.listFiles()?.forEach { zipFileOrDirectory(it, "$fileName/${it.name}", zos) }
        } else {
            FileInputStream(fileToZip).use { fis ->
                zos.putNextEntry(ZipEntry(fileName))
                val bytes = ByteArray(1024)
                var length: Int
                while (fis.read(bytes).also { length = it } >= 0) zos.write(bytes, 0, length)
            }
        }
    }

    fun unzipFile(zipFile: File, context: Context) {
        val destDir = File(zipFile.parent, zipFile.nameWithoutExtension)
        if (!destDir.exists()) destDir.mkdirs()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                ZipInputStream(FileInputStream(zipFile)).use { zis ->
                    var zipEntry = zis.nextEntry
                    while (zipEntry != null) {
                        val newFile = File(destDir, zipEntry.name)
                        if (zipEntry.isDirectory) {
                            newFile.mkdirs()
                        } else {
                            newFile.parentFile?.mkdirs()
                            FileOutputStream(newFile).use { fos ->
                                val buffer = ByteArray(1024)
                                var len: Int
                                while (zis.read(buffer).also { len = it } > 0) fos.write(buffer, 0, len)
                            }
                        }
                        zipEntry = zis.nextEntry
                    }
                    zis.closeEntry()
                }
                launch(Dispatchers.Main) {
                    if (_currentCategory.value == FileCategory.ALL || _currentCategory.value == FileCategory.TRASH) loadFiles() else setCategory(_currentCategory.value)
                    Toast.makeText(context, "Extraído", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { Toast.makeText(context, "Error al extraer", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun openFile(context: Context, file: File) {
        try {
            if (!file.exists()) {
                Toast.makeText(context, "El archivo no existe", Toast.LENGTH_SHORT).show()
                return
            }

            val extension = file.extension.lowercase()

            if (extension == "apk" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val installIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(installIntent)
                    Toast.makeText(context, "Autoriza la instalación", Toast.LENGTH_LONG).show()
                    return
                }
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            var mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            if (extension == "apk") mimeType = "application/vnd.android.package-archive"

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No hay aplicación para abrir este archivo", Toast.LENGTH_LONG).show()
        }
    }
}

// --- VISTAS EN JETPACK COMPOSE (MODERN DESIGN + TOUCH PAD DE ANÁLISIS) ---

@Composable
fun DrawerContent(viewModel: FileManagerViewModel, context: Context, onCloseDrawer: () -> Unit) {
    val currentCategory by viewModel.currentCategory.collectAsState()

    ModalDrawerSheet(
        drawerContainerColor = Color(0xFFF7F9FC),
        drawerContentColor = Color(0xFF1A1C1E)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Gestor de Archivos",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1C1E),
                modifier = Modifier.padding(vertical = 16.dp, horizontal = 12.dp)
            )

            HorizontalDivider(color = Color(0xFFE1E2E5), modifier = Modifier.padding(bottom = 12.dp))

            val navigationItems = listOf(
                Triple("Carpetas", "📁", FileCategory.ALL),
                Triple("Duplicados", "👯", FileCategory.DUPLICATES),
                Triple("PC (FTP)", "💻", FileCategory.FTP_SERVER),
                Triple("Imágenes", "🖼️", FileCategory.IMAGES),
                Triple("Vídeos", "🎬", FileCategory.VIDEOS),
                Triple("Audio", "🎵", FileCategory.AUDIO),
                Triple("Documentos", "📄", FileCategory.DOCUMENTS),
                Triple("Descargas", "⬇️", FileCategory.DOWNLOADS),
                Triple("APKs", "📦", FileCategory.APKS),
                Triple("Otros", "🗂️", FileCategory.OTHERS),
                Triple("Apps", "📱", FileCategory.APPS),
                Triple("Papelera", "🗑️", FileCategory.TRASH)
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(navigationItems) { (title, icon, category) ->
                    val isSelected = currentCategory == category
                    NavigationDrawerItem(
                        label = { Text(text = "$icon  $title", fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                        selected = isSelected,
                        onClick = {
                            viewModel.setCategory(category, context)
                            onCloseDrawer()
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = Color(0xFFE2EDF8),
                            unselectedContainerColor = Color.Transparent,
                            selectedTextColor = Color(0xFF001D33),
                            unselectedTextColor = Color(0xFF42474E)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StorageInfoCard(viewModel: FileManagerViewModel, onOpenSortDialog: () -> Unit) {
    val stats by viewModel.storageStats.collectAsState()
    val categoryStats by viewModel.categorySizeStats.collectAsState()
    val showHidden by viewModel.showHiddenFiles.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.analyzeCategorySizes()
    }
    
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F4F8)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Memoria Interna", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = Color(0xFF1A1C1E))
                Row {
                    IconButton(onClick = onOpenSortDialog) { Text("🔀", fontSize = 20.sp) }
                    IconButton(onClick = { viewModel.toggleViewMode() }) { Text(if (viewMode == ViewMode.LIST) "🔲" else "📄", fontSize = 20.sp) }
                    IconButton(onClick = { viewModel.toggleHiddenFiles() }) { Text(if (showHidden) "👁️" else "🙈", fontSize = 20.sp) }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { stats.percentageUsed },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color(0xFFDCE3E9)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text("${viewModel.formatSize(stats.freeBytes)} libres de ${viewModel.formatSize(stats.totalBytes)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF42474E))

            Spacer(modifier = Modifier.height(12.dp))
            Text("Análisis por Categoría (Touch Pad):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF72777D))
            Spacer(modifier = Modifier.height(6.dp))

            // Touch Pad Horizontal para navegar directamente a la sección según el espacio ocupado
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TouchPadChip("🖼️ Img", viewModel.formatSize(categoryStats.imagesBytes)) { viewModel.setCategory(FileCategory.IMAGES, context) }
                TouchPadChip("🎬 Vid", viewModel.formatSize(categoryStats.videosBytes)) { viewModel.setCategory(FileCategory.VIDEOS, context) }
                TouchPadChip("🎵 Aud", viewModel.formatSize(categoryStats.audioBytes)) { viewModel.setCategory(FileCategory.AUDIO, context) }
                TouchPadChip("📄 Doc", viewModel.formatSize(categoryStats.docsBytes)) { viewModel.setCategory(FileCategory.DOCUMENTS, context) }
                TouchPadChip("⬇️ Desc", viewModel.formatSize(categoryStats.downloadsBytes)) { viewModel.setCategory(FileCategory.DOWNLOADS, context) }
                TouchPadChip("📦 APK", viewModel.formatSize(categoryStats.apksBytes)) { viewModel.setCategory(FileCategory.APKS, context) }
                TouchPadChip("🗂️ Otros", viewModel.formatSize(categoryStats.othersBytes)) { viewModel.setCategory(FileCategory.OTHERS, context) }
            }
        }
    }
}

@Composable
fun TouchPadChip(label: String, sizeText: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() },
        color = Color(0xFFE2EDF8)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF001D33))
            Text(sizeText, style = MaterialTheme.typography.labelSmall, color = Color(0xFF42474E))
        }
    }
}

@Composable
fun ActionBottomSheet(
    selectedCount: Int,
    isTrash: Boolean,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onZip: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFF7F9FC),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "$selectedCount elemento(s) seleccionado(s)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1C1E),
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isTrash) {
                    BottomSheetActionItem("Restaurar", "🔄") { onRestore(); onDismiss() }
                    BottomSheetActionItem("Eliminar", "🗑️") { onDelete(); onDismiss() }
                } else {
                    BottomSheetActionItem("Copiar", "📋") { onCopy(); onDismiss() }
                    BottomSheetActionItem("Cortar", "✂️") { onCut(); onDismiss() }
                    BottomSheetActionItem("Compartir", "🔗") { onShare(); onDismiss() }
                    BottomSheetActionItem("ZIP", "📦") { onZip(); onDismiss() }
                    if (selectedCount == 1) {
                        BottomSheetActionItem("Renombrar", "✏️") { onRename(); onDismiss() }
                    }
                    BottomSheetActionItem("Borrar", "🗑️") { onDelete(); onDismiss() }
                }
            }
        }
    }
}

@Composable
fun BottomSheetActionItem(label: String, icon: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() }.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 28.sp)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF1A1C1E))
    }
}

@Composable
fun SortDialog(currentSort: SortOption, onSelectSort: (SortOption) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ordenar y Filtrar") },
        text = {
            Column {
                SortOptionItem("Nombre (A-Z)", SortOption.NAME_ASC, currentSort, onSelectSort)
                SortOptionItem("Nombre (Z-A)", SortOption.NAME_DESC, currentSort, onSelectSort)
                SortOptionItem("Fecha (Más reciente)", SortOption.DATE_DESC, currentSort, onSelectSort)
                SortOptionItem("Fecha (Más antiguo)", SortOption.DATE_ASC, currentSort, onSelectSort)
                SortOptionItem("Tamaño (Mayor a Menor)", SortOption.SIZE_DESC, currentSort, onSelectSort)
                SortOptionItem("Tamaño (Menor a Mayor)", SortOption.SIZE_ASC, currentSort, onSelectSort)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

@Composable
fun SortOptionItem(label: String, option: SortOption, currentSort: SortOption, onSelectSort: (SortOption) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onSelectSort(option) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = (option == currentSort), onClick = { onSelectSort(option) })
        Text(text = label, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun PathBreadcrumbs(currentDir: File?, onPathClick: (File) -> Unit) {
    val pathSegments = remember(currentDir) {
        val segments = mutableListOf<File>()
        var curr = currentDir
        while (curr != null) {
            segments.add(0, curr)
            curr = curr.parentFile
        }
        segments
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        pathSegments.forEachIndexed { index, file ->
            val displayName = if (file.path == "/") "Raíz (/)" else if (file.name == "0" && file.parent?.endsWith("emulated") == true) "Almacenamiento" else file.name
            
            Text(
                text = displayName,
                fontWeight = if (index == pathSegments.lastIndex) FontWeight.Bold else FontWeight.Normal,
                color = if (index == pathSegments.lastIndex) MaterialTheme.colorScheme.primary else Color(0xFF1A1C1E),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onPathClick(file) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium
            )
            if (index < pathSegments.lastIndex) {
                Text(" > ", color = Color(0xFF72777D), fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun FileDetailsDialog(file: File, onDismiss: () -> Unit) {
    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    val lastModifiedStr = dateFormat.format(Date(file.lastModified()))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Detalles del Archivo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Nombre: ${file.name}", fontWeight = FontWeight.Bold)
                Text("Ruta: ${file.absolutePath}", style = MaterialTheme.typography.bodySmall)
                Text("Tipo: ${if (file.isDirectory) "Carpeta" else file.extension.ifEmpty { "Desconocido" }}")
                if (file.isFile) {
                    Text("Tamaño: ${file.length()} bytes (${String.format("%.2f MB", file.length() / (1024.0 * 1024.0))})")
                } else {
                    val count = file.listFiles()?.size ?: 0
                    Text("Contenido: $count elementos")
                }
                Text("Última Modificación: $lastModifiedStr")
                Text("Permisos: ${if (file.canRead()) "R" else ""}${if (file.canWrite()) "W" else ""}")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

@Composable
fun FtpServerScreen(viewModel: FileManagerViewModel) {
    val isRunning by viewModel.isFtpRunning.collectAsState()
    val ftpAddress by viewModel.ftpAddress.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("💻 Acceso desde PC (Servidor FTP)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F4F8)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (isRunning) {
                    Text("Servidor Activo", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Introduce esta dirección en el Explorador de Archivos de tu PC:", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(ftpAddress, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp))
                    Text("Usuario: admin", style = MaterialTheme.typography.bodyLarge)
                    Text("Contraseña: 1234", style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text("Servidor Detenido", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Inicia el servidor para acceder a los archivos del dispositivo desde la misma red WiFi.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { viewModel.toggleFtpServer(context) }) {
                    Text(if (isRunning) "Detener Servidor" else "Iniciar Servidor FTP")
                }
            }
        }
    }
}

@Composable
fun DuplicatesScreen(viewModel: FileManagerViewModel) {
    val duplicates by viewModel.duplicateFiles.collectAsState()
    val isScanning by viewModel.isScanningDuplicates.collectAsState()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("👯 Archivos Duplicados", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Button(onClick = { viewModel.scanForDuplicates(context) }, enabled = !isScanning) {
                Text(if (isScanning) "Escaneando..." else "🔍 Re-escanear")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (isScanning) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (duplicates.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No se encontraron archivos duplicados.") }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(duplicates) { group ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F4F8)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Tamaño: ${viewModel.formatSize(group.fileSize)} por archivo (${group.files.size} copias)",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            group.files.forEach { file ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                        Text(file.parent ?: "", style = MaterialTheme.typography.labelSmall, color = Color(0xFF72777D), maxLines = 1)
                                    }
                                    IconButton(onClick = { if (file.delete()) viewModel.scanForDuplicates(context) }) { Text("🗑️") }
                                }
                                HorizontalDivider(color = Color(0xFFE1E2E5))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListScreen(viewModel: FileManagerViewModel) {
    val files by viewModel.files.collectAsState()
    val apps by viewModel.installedApps.collectAsState()
    val currentDir by viewModel.currentDirectory.collectAsState()
    val hasClipboardData by viewModel.hasClipboardData.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val selectedFiles by viewModel.selectedFiles.collectAsState()
    val currentCategory by viewModel.currentCategory.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val currentSort by viewModel.sortOption.collectAsState()
    val context = LocalContext.current

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.calculateStorageStats(context) }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var fileToRename by remember { mutableStateOf<File?>(null) }
    var fileForDetails by remember { mutableStateOf<File?>(null) }
    var newFileName by remember { mutableStateOf("") }
    var showZipDialog by remember { mutableStateOf(false) }
    var zipFileName by remember { mutableStateOf("") }

    val isRoot = currentDir?.parentFile == null && currentCategory == FileCategory.ALL

    BackHandler(enabled = !isRoot || isSelectionMode || drawerState.isOpen) {
        if (drawerState.isOpen) {
            coroutineScope.launch { drawerState.close() }
        } else if (isSelectionMode) {
            viewModel.clearSelection()
        } else {
            viewModel.navigateUp()
        }
    }

    if (showSortDialog) {
        SortDialog(
            currentSort = currentSort,
            onSelectSort = {
                viewModel.setSortOption(it)
                showSortDialog = false
            },
            onDismiss = { showSortDialog = false }
        )
    }

    if (showDeleteConfirm) {
        val isTrash = currentCategory == FileCategory.TRASH
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(if (isTrash) "Eliminación Definitiva" else "Mover a Papelera") },
            text = { Text(if (isTrash) "¿Eliminar ${selectedFiles.size} elemento(s) para siempre?" else "¿Mover ${selectedFiles.size} elemento(s) a la papelera?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSelectedFiles(permanent = isTrash)
                    showDeleteConfirm = false
                    viewModel.calculateStorageStats(context)
                }) { Text(if (isTrash) "Destruir" else "Mover", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancelar") } }
        )
    }

    if (fileToRename != null) {
        AlertDialog(
            onDismissRequest = { fileToRename = null },
            title = { Text("Renombrar archivo") },
            text = { OutlinedTextField(value = newFileName, onValueChange = { newFileName = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (newFileName.isNotBlank()) fileToRename?.let { viewModel.renameFile(it, newFileName) }
                    fileToRename = null
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { fileToRename = null }) { Text("Cancelar") } }
        )
    }

    if (fileForDetails != null) {
        FileDetailsDialog(file = fileForDetails!!, onDismiss = { fileForDetails = null })
    }

    if (showZipDialog) {
        AlertDialog(
            onDismissRequest = { showZipDialog = false },
            title = { Text("Comprimir archivos") },
            text = { OutlinedTextField(value = zipFileName, onValueChange = { zipFileName = it }, label = { Text("Nombre .zip") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (zipFileName.isNotBlank()) {
                        viewModel.zipSelectedFiles(zipFileName, context)
                        viewModel.calculateStorageStats(context)
                    }
                    showZipDialog = false
                }) { Text("Comprimir") }
            },
            dismissButton = { TextButton(onClick = { showZipDialog = false }) { Text("Cancelar") } }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(viewModel = viewModel, context = context) {
                coroutineScope.launch { drawerState.close() }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Gestor de Archivos", fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1C1E)) },
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Text("☰", fontSize = 24.sp, color = Color(0xFF1A1C1E))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            bottomBar = {
                // Al mantener pulsado un archivo se despliega la barra inferior contextual
                if (isSelectionMode) {
                    ActionBottomSheet(
                        selectedCount = selectedFiles.size,
                        isTrash = currentCategory == FileCategory.TRASH,
                        onCopy = { viewModel.copySelectedFiles() },
                        onCut = { viewModel.cutSelectedFiles() },
                        onDelete = { showDeleteConfirm = true },
                        onZip = { showZipDialog = true },
                        onRename = {
                            selectedFiles.firstOrNull()?.let {
                                fileToRename = it
                                newFileName = it.name
                            }
                        },
                        onShare = {
                            selectedFiles.firstOrNull()?.let { viewModel.shareFile(context, it) }
                        },
                        onRestore = { viewModel.restoreSelectedFiles() },
                        onDismiss = { viewModel.clearSelection() }
                    )
                }
            },
            containerColor = Color.White
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
            ) {
                StorageInfoCard(viewModel = viewModel, onOpenSortDialog = { showSortDialog = true })

                if (currentCategory != FileCategory.APPS && currentCategory != FileCategory.FTP_SERVER && currentCategory != FileCategory.DUPLICATES) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.updateSearchQuery(it) },
                        label = { Text("🔎 Buscar...") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                if (currentCategory == FileCategory.ALL || currentCategory == FileCategory.TRASH) {
                    PathBreadcrumbs(currentDir = currentDir, onPathClick = { viewModel.navigateToDirectory(it) })
                }

                if (currentCategory != FileCategory.FTP_SERVER && currentCategory != FileCategory.DUPLICATES) {
                    if (hasClipboardData && (currentCategory == FileCategory.ALL || currentCategory == FileCategory.TRASH)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                            Button(onClick = {
                                viewModel.pasteFiles()
                                viewModel.calculateStorageStats(context)
                            }, modifier = Modifier.padding(end = 8.dp)) { Text("📋 Pegar") }
                            OutlinedButton(onClick = { viewModel.clearClipboard() }) { Text("❌") }
                        }
                    }
                }

                when (currentCategory) {
                    FileCategory.FTP_SERVER -> FtpServerScreen(viewModel)
                    FileCategory.DUPLICATES -> DuplicatesScreen(viewModel)
                    FileCategory.APPS -> {
                        LazyColumn {
                            items(apps) { app ->
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("📱", modifier = Modifier.padding(end = 12.dp), fontSize = 44.sp)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = app.name, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF1A1C1E))
                                        Text(text = app.packageName, style = MaterialTheme.typography.labelSmall, color = Color(0xFF72777D))
                                    }
                                    TextButton(onClick = { viewModel.extractApp(app, context) }) { Text("Extraer APK") }
                                }
                                HorizontalDivider(color = Color(0xFFE1E2E5))
                            }
                        }
                    }
                    else -> {
                        if (viewMode == ViewMode.LIST) {
                            LazyColumn {
                                items(files) { file ->
                                    FileListItem(
                                        file = file,
                                        isSelectionMode = isSelectionMode,
                                        isSelected = selectedFiles.contains(file),
                                        viewModel = viewModel,
                                        context = context,
                                        onRename = { fileToRename = it; newFileName = it.name },
                                        onShowDetails = { fileForDetails = it }
                                    )
                                }
                            }
                        } else {
                            LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize()) {
                                items(files) { file ->
                                    FileGridItem(
                                        file = file,
                                        isSelectionMode = isSelectionMode,
                                        isSelected = selectedFiles.contains(file),
                                        viewModel = viewModel,
                                        context = context,
                                        onShowDetails = { fileForDetails = it }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItem(
    file: File,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    viewModel: FileManagerViewModel,
    context: Context,
    onRename: (File) -> Unit,
    onShowDetails: (File) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) Color(0xFFE2EDF8) else Color.Transparent)
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) viewModel.toggleFileSelection(file)
                    else if (file.isDirectory) viewModel.openDirectory(file)
                    else if (file.extension.lowercase() == "zip") viewModel.unzipFile(file, context)
                    else viewModel.openFile(context, file)
                },
                onLongClick = {
                    // Acción al dejar pulsado: activa el submenú contextual de opciones
                    viewModel.selectSingleFile(file)
                }
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode) {
            Checkbox(checked = isSelected, onCheckedChange = { viewModel.toggleFileSelection(file) }, modifier = Modifier.padding(end = 8.dp))
        }

        val ext = file.extension.lowercase()
        val isImage = ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
        val isDoc = ext in listOf("pdf", "doc", "docx", "xls", "xlsx", "txt", "ppt", "pptx")

        if (isImage || isDoc) {
            AsyncImage(
                model = file,
                contentDescription = "Miniatura",
                modifier = Modifier.size(64.dp).padding(end = 12.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            val iconEmoji = when {
                file.isDirectory -> "📁"
                ext == "zip" -> "📦"
                ext == "apk" -> "🤖"
                ext in listOf("mp3", "wav", "ogg", "flac") -> "🎵"
                ext in listOf("mp4", "mkv", "avi", "mov") -> "🎬"
                else -> "📄"
            }
            Text(
                text = iconEmoji,
                modifier = Modifier.padding(end = 12.dp),
                fontSize = 44.sp
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(text = file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, color = Color(0xFF1A1C1E))
            Text(text = if (file.isDirectory) "Carpeta" else viewModel.formatSize(file.length()), style = MaterialTheme.typography.labelSmall, color = Color(0xFF72777D))
        }

        if (!isSelectionMode) {
            IconButton(onClick = { onShowDetails(file) }) { Text("ℹ️", fontSize = 20.sp) }
        }
    }
    HorizontalDivider(color = Color(0xFFE1E2E5))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridItem(
    file: File,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    viewModel: FileManagerViewModel,
    context: Context,
    onShowDetails: (File) -> Unit
) {
    Column(
        modifier = Modifier
            .padding(6.dp)
            .background(if (isSelected) Color(0xFFE2EDF8) else Color(0xFFF7F9FC), RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) viewModel.toggleFileSelection(file)
                    else if (file.isDirectory) viewModel.openDirectory(file)
                    else if (file.extension.lowercase() == "zip") viewModel.unzipFile(file, context)
                    else viewModel.openFile(context, file)
                },
                onLongClick = {
                    viewModel.selectSingleFile(file)
                }
            )
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (isSelectionMode) {
                Checkbox(checked = isSelected, onCheckedChange = { viewModel.toggleFileSelection(file) })
            } else {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "ℹ️",
                    modifier = Modifier.clickable { onShowDetails(file) }.padding(2.dp),
                    fontSize = 18.sp
                )
            }
        }
        val ext = file.extension.lowercase()
        val isImage = ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
        val isDoc = ext in listOf("pdf", "doc", "docx", "xls", "xlsx", "txt", "ppt", "pptx")

        if (isImage || isDoc) {
            AsyncImage(
                model = file,
                contentDescription = "Miniatura",
                modifier = Modifier.size(80.dp).padding(bottom = 6.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            val iconEmoji = when {
                file.isDirectory -> "📁"
                ext == "zip" -> "📦"
                ext == "apk" -> "🤖"
                ext in listOf("mp3", "wav", "ogg", "flac") -> "🎵"
                ext in listOf("mp4", "mkv", "avi", "mov") -> "🎬"
                else -> "📄"
            }
            Text(text = iconEmoji, fontSize = 56.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(text = file.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color(0xFF1A1C1E))
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val builder = StrictMode.VmPolicy.Builder()
        StrictMode.setVmPolicy(builder.build())
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }

        setContent {
            MaterialTheme {
                Surface {
                    val viewModel: FileManagerViewModel = viewModel()
                    FileListScreen(viewModel = viewModel)
                }
            }
        }
    }
}
