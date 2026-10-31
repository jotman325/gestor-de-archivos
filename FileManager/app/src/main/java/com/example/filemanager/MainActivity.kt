package com.example.filemanager

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

// --- VIEWMODEL (Lógica de archivos y portapapeles) ---
class FileManagerViewModel : ViewModel() {
    private val _currentDirectory = MutableStateFlow(Environment.getExternalStorageDirectory())
    val currentDirectory: StateFlow<File> = _currentDirectory

    private val _files = MutableStateFlow<List<File>>(emptyList())
    val files: StateFlow<List<File>> = _files

    private var clipboardFile: File? = null
    private var isCutOperation: Boolean = false

    private val _hasClipboardData = MutableStateFlow(false)
    val hasClipboardData: StateFlow<Boolean> = _hasClipboardData

    init { loadFiles() }

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
            if (isCutOperation) source.renameTo(target)
            else source.copyRecursively(target, overwrite = true)
        } catch (e: Exception) { false }
        
        if (success) {
            if (isCutOperation) clearClipboard()
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

// --- INTERFAZ GRAFICA (Compose) ---
@Composable
fun FileListScreen(viewModel: FileManagerViewModel) {
    val files by viewModel.files.collectAsState()
    val currentDir by viewModel.currentDirectory.collectAsState()
    val hasClipboardData by viewModel.hasClipboardData.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Ruta: ${currentDir?.absolutePath ?: "/"}", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { viewModel.navigateUp() }, enabled = currentDir?.parentFile != null) {
                Text("⬆ Subir")
            }
            if (hasClipboardData) {
                Row {
                    Button(onClick = { viewModel.pasteFile() }, modifier = Modifier.padding(end = 8.dp)) { Text("📋 Pegar") }
                    OutlinedButton(onClick = { viewModel.clearClipboard() }) { Text("❌") }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn {
            items(files) { file ->
                Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.openDirectory(file) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = if (file.isDirectory) "📁 ${file.name}" else "📄 ${file.name}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Row {
                        TextButton(onClick = { viewModel.copyFile(file) }) { Text("Copiar") }
                        TextButton(onClick = { viewModel.cutFile(file) }) { Text("Cortar") }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

// --- ACTIVIDAD PRINCIPAL ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
