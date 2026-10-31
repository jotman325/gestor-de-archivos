package com.example.filemanager

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.filemanager.ui.theme.FileManagerTheme

class MainActivity : ComponentActivity() {

    private val viewModel: FileManagerViewModel by viewModels()

    /**
     * Launcher para solicitar MANAGE_EXTERNAL_STORAGE en Android 11+.
     * Al regresar, recargamos el directorio actual.
     */
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        ) {
            viewModel.refresh()
        }
    }

    /**
     * Launcher para solicitar READ/WRITE_EXTERNAL_STORAGE en Android ≤ 12.
     */
    private val legacyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            viewModel.refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestStoragePermission()

        setContent {
            FileManagerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FileManagerScreen(viewModel)
                }
            }
        }
    }

    // ── Permisos ────────────────────────────────────────────────────────

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ → MANAGE_EXTERNAL_STORAGE
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                manageStorageLauncher.launch(intent)
            }
        } else {
            // Android 10 e inferiores → permisos clásicos
            legacyPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    /**
     * Intercepta el botón de atrás del sistema para navegar al directorio
     * anterior dentro de la app antes de cerrar la Activity.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!viewModel.navigateBack()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
