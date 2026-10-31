package com.example.filemanager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StrictMode
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

// --- VIEWMODEL (Lógica principal) ---
class FileManagerViewModel : ViewModel() {
    private val _currentDirectory = MutableStateFlow(Environment.getExternalStorageDirectory())
    val currentDirectory: StateFlow<File> = _currentDirectory

    private val _files = MutableStateFlow<List<File>>(emptyList())
    val files: StateFlow<List<File>> = _files

    // Estado del buscador
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private var clipboardFile: File? = null
    private var isCutOperation: Boolean = false

    private val _hasClipboardData = MutableStateFlow(false)
    val hasClipboardData: StateFlow<Boolean> = _hasClipboardData

    init { loadFiles() }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        loadFiles()
    }

    fun loadFiles() {
        val allFiles = _currentDirectory.value?.listFiles()?.toList() ?: emptyList()
        _files.value = if (_searchQuery.value.isBlank()) {
            allFiles
        } else {
            allFiles.filter { it.name.contains(_searchQuery.value, ignoreCase = true) }
        }
    }

    fun openDirectory(file: File) {
        if (file.isDirectory) {
            _currentDirectory.value = file
            updateSearchQuery("") // Limpiar búsqueda al cambiar de carpeta
        }
    }

    fun navigateUp(): Boolean {
        val parent = _currentDirectory.value?.parentFile
        if (parent != null && parent.canRead()) {
            _currentDirectory.value = parent
            updateSearchQuery("")
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

    fun deleteFile(file: File) {
        if (file.isDirectory) file.deleteRecursively() else file.delete()
        loadFiles()
    }

    // NUEVO: Renombrar archivo
    fun renameFile(file: File, newName: String) {
        val newFile = File(file.parent, newName)
        if (file.renameTo(newFile)) {
            loadFiles()
        }
    }

    fun openFile(context: Context, file: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val uri = Uri.fromFile(file)
            val extension = file.extension.lowercase()
            
            var mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            
            // Integración explícita del instalador de paquetes APK
            if (extension == "apk") {
                mimeType = "application/vnd.android.package-archive"
            }

            intent.setDataAndType(uri, mimeType ?: "*/*")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No hay aplicación para abrir este archivo", Toast.LENGTH_SHORT).show()
        }
    }
}

// --- INTERFAZ GRÁFICA (Compose) ---
@Composable
fun FileListScreen(viewModel: FileManagerViewModel) {
    val files by viewModel.files.collectAsState()
    val currentDir by viewModel.currentDirectory.collectAsState()
    val hasClipboardData by viewModel.hasClipboardData.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val context = LocalContext.current 
    
    // Control de diálogos
    var fileToDelete by remember { mutableStateOf<File?>(null) }
    var fileToRename by remember { mutableStateOf<File?>(null) }
    var newFileName by remember { mutableStateOf("") }
    
    val isRoot = currentDir?.parentFile == null
    BackHandler(enabled = !isRoot) { viewModel.navigateUp() }

    // Diálogo de confirmación de borrado
    if (fileToDelete != null) {
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Confirmar eliminación") },
            text = { Text("¿Eliminar '${fileToDelete?.name}' permanentemente?") },
            confirmButton = {
                TextButton(onClick = {
                    fileToDelete?.let { viewModel.deleteFile(it) }
                    fileToDelete = null
                }) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { fileToDelete = null }) { Text("Cancelar") } }
        )
    }

    // Diálogo para renombrar
    if (fileToRename != null) {
        AlertDialog(
            onDismissRequest = { fileToRename = null },
            title = { Text("Renombrar archivo") },
            text = {
                OutlinedTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    label = { Text("Nuevo nombre con extensión") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newFileName.isNotBlank()) {
                        fileToRename?.let { viewModel.renameFile(it, newFileName) }
                    }
                    fileToRename = null
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { fileToRename = null }) { Text("Cancelar") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // NUEVO: Buscador de archivos
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.updateSearchQuery(it) },
            label = { Text("🔎 Buscar archivo...") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            singleLine = true
        )
        
        Text(text = "Ruta: ${currentDir?.absolutePath ?: "/"}", style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { viewModel.navigateUp() }, enabled = !isRoot) { Text("⬆ Subir") }
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (file.isDirectory) viewModel.openDirectory(file)
                            else viewModel.openFile(context, file)
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // NUEVO: Sistema de miniaturas
                    val isImage = file.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
                    if (isImage) {
                        AsyncImage(
                            model = file,
                            contentDescription = "Miniatura",
                            modifier = Modifier.size(40.dp).padding(end = 12.dp),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            text = if (file.isDirectory) "📁" else "📄",
                            modifier = Modifier.padding(end = 12.dp),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }

                    Text(text = file.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    
                    // Botones de acción en scroll horizontal
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { viewModel.copyFile(file) }) { Text("Copiar") }
                        TextButton(onClick = { viewModel.cutFile(file) }) { Text("Cortar") }
                        TextButton(onClick = { 
                            fileToRename = file
                            newFileName = file.name
                        }) { Text("Renombrar") }
                        TextButton(onClick = { fileToDelete = file }) { Text("Borrar") }
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
