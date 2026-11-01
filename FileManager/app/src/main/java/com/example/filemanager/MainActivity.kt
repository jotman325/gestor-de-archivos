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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class StorageStats(
    val totalBytes: Long = 0,
    val usedBytes: Long = 0,
    val freeBytes: Long = 0,
    val percentageUsed: Float = 0f
)

data class AppItem(
    val name: String,
    val packageName: String,
    val sourceDir: String
)

enum class FileCategory {
    ALL, IMAGES, VIDEOS, AUDIO, DOCUMENTS, DOWNLOADS, APPS, TRASH, FTP_SERVER
}

enum class ViewMode {
    LIST, GRID
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

    private val _installedApps = MutableStateFlow<List<AppItem>>(emptyList())
    val installedApps: StateFlow<List<AppItem>> = _installedApps

    private val _isCleaningCache = MutableStateFlow(false)
    val isCleaningCache: StateFlow<Boolean> = _isCleaningCache

    // Estados del Servidor FTP
    private val _isFtpRunning = MutableStateFlow(false)
    val isFtpRunning: StateFlow<Boolean> = _isFtpRunning

    private val _ftpAddress = MutableStateFlow("")
    val ftpAddress: StateFlow<String> = _ftpAddress

    private var ftpServer: FtpServer? = null
    
    private var allScannedFiles: List<File> = emptyList()
    private val trashDir = File(Environment.getExternalStorageDirectory(), ".TrashBin")

    init {
        if (!trashDir.exists()) trashDir.mkdirs()
        loadFiles()
    }

    // --- FUNCIONES DEL SERVIDOR FTP ---
    fun toggleFtpServer(context: Context) {
        if (_isFtpRunning.value) {
            stopFtpServer()
        } else {
            startFtpServer(context)
        }
    }

    private fun getLocalIpAddress(context: Context): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val ipInt = wifiInfo.ipAddress
        return String.format(
            "%d.%d.%d.%d",
            ipInt and 0xff,
            ipInt shr 8 and 0xff,
            ipInt shr 16 and 0xff,
            ipInt shr 24 and 0xff
        )
    }

    private fun startFtpServer(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
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
                
                val authorities = listOf(WritePermission())
                user.authorities = authorities

                userManager.save(user)
                serverFactory.userManager = userManager

                ftpServer = serverFactory.createServer()
                ftpServer?.start()

                val ipAddress = getLocalIpAddress(context)
                
                launch(Dispatchers.Main) {
                    _ftpAddress.value = "ftp://$ipAddress:2121"
                    _isFtpRunning.value = true
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    Toast.makeText(context, "Error al iniciar servidor FTP", Toast.LENGTH_SHORT).show()
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- RESTO DE FUNCIONES (Lógica principal ampliada) ---
    fun toggleHiddenFiles() {
        _showHiddenFiles.value = !_showHiddenFiles.value
        if (_currentCategory.value == FileCategory.ALL || _currentCategory.value == FileCategory.TRASH) {
            loadFiles()
        } else if (_currentCategory.value != FileCategory.APPS && _currentCategory.value != FileCategory.FTP_SERVER) {
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

    fun cleanCache(context: Context) {
        if (_isCleaningCache.value) return
        _isCleaningCache.value = true
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var deletedBytes = 0L
                val cacheDirs = mutableListOf<File>()
                cacheDirs.add(context.cacheDir)
                context.externalCacheDir?.let { cacheDirs.add(it) }
                
                val androidDataDir = File(Environment.getExternalStorageDirectory(), "Android/data")
                if (androidDataDir.exists() && androidDataDir.canRead()) {
                    androidDataDir.listFiles()?.forEach { appDir ->
                        val appCacheDir = File(appDir, "cache")
                        if (appCacheDir.exists()) cacheDirs.add(appCacheDir)
                    }
                }

                cacheDirs.forEach { dir ->
                    if (dir.exists()) {
                        dir.walkBottomUp().forEach { file ->
                            if (file.isFile) {
                                val size = file.length()
                                if (file.delete()) deletedBytes += size
                            }
                        }
                    }
                }

                launch(Dispatchers.Main) {
                    _isCleaningCache.value = false
                    calculateStorageStats(context)
                    Toast.makeText(context, "Caché limpiada: ${formatSize(deletedBytes)} liberados", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    _isCleaningCache.value = false
                    Toast.makeText(context, "Error al limpiar la caché", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadApps(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
                .map { AppItem(it.loadLabel(pm).toString(), it.packageName, it.publicSourceDir) }
                .sortedBy { it.name.lowercase() }
            
            launch(Dispatchers.Main) {
                _installedApps.value = apps
            }
        }
    }

    fun extractApp(app: AppItem, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val source = File(app.sourceDir)
                val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dest = File(destDir, "${app.name.replace(" ", "_")}.apk")
                source.copyTo(dest, overwrite = true)
                launch(Dispatchers.Main) {
                    Toast.makeText(context, "APK guardado en Descargas", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { Toast.makeText(context, "Error al extraer", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        filterFiles()
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
            FileCategory.APPS -> {
                if (context != null) loadApps(context)
            }
            FileCategory.FTP_SERVER -> {
                // No carga archivos, solo cambia la vista
            }
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
                            FileCategory.DOCUMENTS -> if (ext in listOf("pdf", "doc", "docx", "xls", "xlsx", "txt")) resultFiles.add(file)
                            else -> {}
                        }
                    }
                }
            }
            
            searchDirectory(rootDir)
            resultFiles.sortByDescending { it.lastModified() }
            
            launch(Dispatchers.Main) {
                allScannedFiles = resultFiles
                filterFiles()
                clearSelection()
            }
        }
    }

    fun loadFiles() {
        allScannedFiles = _currentDirectory.value?.listFiles()?.toList() ?: emptyList()
        if (!_showHiddenFiles.value) {
            allScannedFiles = allScannedFiles.filter { !it.name.startsWith(".") }
        }
        allScannedFiles = allScannedFiles.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        filterFiles()
        clearSelection()
    }

    private fun filterFiles() {
        if (_currentCategory.value == FileCategory.APPS || _currentCategory.value == FileCategory.FTP_SERVER) return
        _files.value = if (_searchQuery.value.isBlank()) {
            allScannedFiles
        } else {
            allScannedFiles.filter { it.name.contains(_searchQuery.value, ignoreCase = true) }
        }
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
    
    fun goToRoot() {
        _currentCategory.value = FileCategory.ALL
        _currentDirectory.value = File("/")
        loadFiles()
    }

    fun toggleSelectionMode() {
        _isSelectionMode.value = !_isSelectionMode.value
        if (!_isSelectionMode.value) clearSelection()
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
                    val result = if (isCutOperation) source.renameTo(target)
                    else source.copyRecursively(target, overwrite = true)
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
    
    fun restoreSel
