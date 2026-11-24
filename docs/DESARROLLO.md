# Guía para desarrolladores

## Compilar
**Con GitHub Actions (sin instalar nada):** cada `git push` a `main` compila el APK
(`.github/workflows/build.yml`). Descárgalo en la pestaña **Actions** → ejecución → artefacto `dgds-debug-apk`.
Si falla, el error está en el paso *Compilar APK de depuración*.

**Con Android Studio:** abre la carpeta del proyecto y pulsa Run. Las versiones de Gradle,
AGP y Kotlin son las del momento de escribir el proyecto; acepta las actualizaciones que sugiera.

## Publicar una versión
Las versiones para usuarios se publican en la pestaña **Releases**. Al crear una etiqueta
que empiece por `v`, `.github/workflows/release.yml` compila y adjunta el APK:

```bash
git tag v0.1.0
git push origin v0.1.0
```

Por ahora el APK está firmado con la clave de depuración. Antes de una versión estable
conviene firmar con una clave propia.

## Cómo funciona (versión actual)
- La app captura la pantalla con `MediaProjection` dentro de un servicio en primer plano.
- Cada fotograma se comprime a JPEG y se sirve por HTTP en el puerto 8080:
  - `/` → visor (`app-android/src/main/assets/viewer.html`, editable sin tocar Kotlin)
  - `/stream` → video MJPEG
- Cada visor recibe siempre el último fotograma disponible, así que una red lenta baja los fps
  pero no acumula retraso.
- Es una solución temporal: los navegadores solo permiten decodificar H.264 (WebCodecs) en
  direcciones seguras (HTTPS o `localhost`), y una IP local `http://` no lo es.
  Las opciones para la siguiente fase son H.264 en fMP4 con Media Source Extensions o HTTPS con certificado propio.

## Estructura
```
app-android/src/main/
  assets/viewer.html                 página que se abre en el navegador
  java/org/dgds/app/
    MainActivity.kt                  pantalla principal
    core/ScreenCaptureService.kt     servicio en primer plano + captura
    core/MjpegStreamer.kt            captura + servidor web (beta)
    core/VideoStreamer.kt            codificador H.264 (reservado, aún sin usar)
    core/LocalNetwork.kt             detecta las IP locales (Wi-Fi, punto de acceso, USB)
```

## Limitaciones técnicas conocidas
- La resolución y la orientación se fijan al iniciar la transmisión.
- Contenido con `FLAG_SECURE` se captura en negro.
- Sin audio, sin cifrado y sin autenticación.
- MJPEG usa más CPU y ancho de banda que H.264.

## Gráficos
El arte (logos, íconos, fondos) lo aportan los diseñadores del proyecto; el código no genera
imágenes. Los recursos van en las carpetas de recursos de Android o en `assets/`.

## Licencia
Apache 2.0. Cada archivo fuente lleva `SPDX-License-Identifier: Apache-2.0`.
