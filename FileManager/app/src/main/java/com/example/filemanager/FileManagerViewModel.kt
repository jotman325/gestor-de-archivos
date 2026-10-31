package com.example.filemanager

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Estado de la UI del gestor de archivos.
 */
data class FileManagerUiState(
    /** Directorio que se está visualizando actualmente. */
    val currentDirectory: File = File("/storage/emulated/0"),
    /** Lista de elementos dentro del directorio actual. */
    val items: List<FileItem> = emptyList(),
    /** Pila de directorios visitados para navegación hacia atrás. */
    val backStack: List<File> = emptyList(),
    /** Mensaje de error o información para el usuario. */
    val userMessage: String? = null,
    /** Indica si estamos en la raíz del almacenamiento. */
    val isAtRoot: Boolean = true
)

/**
 * ViewModel que gestiona la lógica de navegación y operaciones del gestor de archivos.
 *
 * Usa [java.io.File] para recorrer el almacenamiento local y expone un
 * [StateFlow] con el estado reactivo para Jetpack Compose.
 */
class FileManagerViewModel : ViewModel() {

    companion object {
        /** Directorio raíz del almacenamiento externo. */
        val ROOT = File("/storage/emulated/0")
    }

    private val _uiState = MutableStateFlow(FileManagerUiState())
    val uiState: StateFlow<FileManagerUiState> = _uiState.asStateFlow()

    init {
        loadDirectory(ROOT)
    }

    // ── Navegación ──────────────────────────────────────────────────────

    /**
     * Carga el contenido de [directory] y actualiza el estado.
     * Los elementos se ordenan: carpetas primero, luego archivos, ambos
     * alfabéticamente sin distinguir mayúsculas/minúsculas.
     */
    fun loadDirectory(directory: File) {
        val files = directory.listFiles()
        if (files == null) {
            _uiState.update { it.copy(userMessage = "No se puede acceder a: ${directory.absolutePath}") }
            return
        }

        val items = files
            .filter { !it.isHidden }
            .map { FileItem(it) }
            .sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name.lowercase() })

        _uiState.update {
            it.copy(
                currentDirectory = directory,
                items = items,
                isAtRoot = directory.absolutePath == ROOT.absolutePath,
                userMessage = null
            )
        }
    }

    /**
     * Entra en una carpeta, guardando el directorio actual en la pila de retroceso.
     */
    fun navigateInto(folder: FileItem) {
        if (!folder.isDirectory) return
        _uiState.update { state ->
            state.copy(backStack = state.backStack + state.currentDirectory)
        }
        loadDirectory(folder.file)
    }

    /**
     * Retrocede al directorio anterior de la pila.
     * @return `true` si se pudo retroceder, `false` si ya estábamos en la raíz.
     */
    fun navigateBack(): Boolean {
        val state = _uiState.value
        if (state.backStack.isEmpty()) return false

        val previous = state.backStack.last()
        _uiState.update { it.copy(backStack = it.backStack.dropLast(1)) }
        loadDirectory(previous)
        return true
    }

    // ── Operaciones sobre archivos ──────────────────────────────────────

    /**
     * Elimina un archivo o carpeta (recursivamente si es directorio).
     */
    fun delete(item: FileItem) {
        val success = if (item.isDirectory) {
            item.file.deleteRecursively()
        } else {
            item.file.delete()
        }

        if (success) {
            refresh()
        } else {
            _uiState.update { it.copy(userMessage = "No se pudo eliminar: ${item.name}") }
        }
    }

    /**
     * Renombra un archivo o carpeta.
     *
     * @param item    Elemento a renombrar.
     * @param newName Nuevo nombre (sólo el nombre, sin ruta).
     */
    fun rename(item: FileItem, newName: String) {
        if (newName.isBlank()) {
            _uiState.update { it.copy(userMessage = "El nombre no puede estar vacío.") }
            return
        }

        val destination = File(item.file.parentFile, newName)
        if (destination.exists()) {
            _uiState.update { it.copy(userMessage = "Ya existe un elemento llamado \"$newName\".") }
            return
        }

        val success = item.file.renameTo(destination)
        if (success) {
            refresh()
        } else {
            _uiState.update { it.copy(userMessage = "No se pudo renombrar: ${item.name}") }
        }
    }

    /** Recarga el directorio actual. */
    fun refresh() {
        loadDirectory(_uiState.value.currentDirectory)
    }

    /** Descarta el mensaje de usuario visible. */
    fun clearMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }
}
