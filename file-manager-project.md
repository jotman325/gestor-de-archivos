# 📂 Gestor de Archivos — Proyecto Android (Kotlin + Jetpack Compose)

Proyecto completo listo para abrir en **Android Studio** y compilar.

## Estructura del proyecto

```
FileManager/
├── build.gradle.kts                          ← raíz: plugins
├── settings.gradle.kts                       ← nombre y módulos
├── gradle.properties                         ← opciones JVM & AndroidX
├── gradle/libs.versions.toml                 ← catálogo de versiones
└── app/
    ├── build.gradle.kts                      ← dependencias de Compose, AGP
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml               ← permisos de almacenamiento
        ├── res/values/
        │   ├── strings.xml
        │   └── themes.xml
        └── java/com/example/filemanager/
            ├── FileItem.kt                   ← modelo de dato
            ├── FileManagerViewModel.kt       ← lógica de navegación
            ├── FileManagerScreen.kt          ← UI Compose (LazyColumn)
            ├── MainActivity.kt               ← Activity + permisos
            └── ui/theme/
                ├── Color.kt
                ├── Theme.kt                  ← Material 3 + Dynamic Color
                └── Type.kt
```

## Componentes clave

### Permisos ([AndroidManifest.xml](file:///c:/Users/rtrruetyb%20v/.gemini/scratch/rea-skill/extracted/rea-main/FileManager/app/src/main/AndroidManifest.xml))

| Permiso | API | Uso |
|---|---|---|
| `READ_EXTERNAL_STORAGE` | ≤ 32 | Lectura en Android 12 e inferiores |
| `WRITE_EXTERNAL_STORAGE` | ≤ 29 | Escritura en Android 10 e inferiores |
| `MANAGE_EXTERNAL_STORAGE` | ≥ 30 | Acceso total en Android 11+ |

La Activity solicita el permiso adecuado según la versión del sistema automáticamente al iniciar.

### ViewModel ([FileManagerViewModel.kt](file:///c:/Users/rtrruetyb%20v/.gemini/scratch/rea-skill/extracted/rea-main/FileManager/app/src/main/java/com/example/filemanager/FileManagerViewModel.kt))

- **`loadDirectory()`** — lista archivos con `java.io.File.listFiles()`, ordena carpetas primero, oculta archivos ocultos.
- **`navigateInto()`** — push al backStack + carga nuevo directorio.
- **`navigateBack()`** — pop del backStack + recarga; devuelve `false` en la raíz.
- **`delete()`** — elimina archivos o carpetas (`deleteRecursively()`).
- **`rename()`** — valida nombre vacío o duplicado, luego `renameTo()`.
- **`refresh()`** — recarga el directorio actual.

### UI Compose ([FileManagerScreen.kt](file:///c:/Users/rtrruetyb%20v/.gemini/scratch/rea-skill/extracted/rea-main/FileManager/app/src/main/java/com/example/filemanager/FileManagerScreen.kt))

- **`LazyColumn`** con `items(key = …)` para reciclaje eficiente.
- Carpetas con ícono 📁 azul y texto **semibold**; archivos con ícono 📄 gris.
- **Tap** en carpeta → navega dentro. **Long press** → menú contextual (renombrar / eliminar).
- Diálogos `AlertDialog` para confirmar eliminación y para introducir nuevo nombre.
- TopAppBar con ruta actual, botón atrás y botón de refresco.

### Navegación hacia atrás ([MainActivity.kt](file:///c:/Users/rtrruetyb%20v/.gemini/scratch/rea-skill/extracted/rea-main/FileManager/app/src/main/java/com/example/filemanager/MainActivity.kt))

El botón de atrás del sistema llama a `viewModel.navigateBack()`. Si la pila está vacía, se cierra la app normalmente.

## Cómo compilar

> [!IMPORTANT]
> Necesitas **Android Studio Koala (2024.1.1)** o posterior y el **SDK 34** instalado.

1. Abre la carpeta `FileManager/` como proyecto en Android Studio.
2. Deja que Gradle sincronice (descargará las dependencias del catálogo de versiones).
3. Ejecuta en un emulador o dispositivo con API ≥ 26.
4. Acepta el permiso de almacenamiento cuando la app lo solicite.

Para generar el APK desde terminal:

```bash
cd FileManager
./gradlew assembleDebug
# APK en: app/build/outputs/apk/debug/app-debug.apk
```

> [!NOTE]
> Para publicar en Google Play con `MANAGE_EXTERNAL_STORAGE`, necesitarás una declaración de uso justificado en la Play Console.
