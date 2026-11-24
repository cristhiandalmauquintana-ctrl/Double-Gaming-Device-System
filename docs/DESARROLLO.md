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
- Al girar el teléfono, `ScreenCaptureService` detecta el cambio con un `DisplayListener` y
  `MjpegStreamer.resize()` ajusta el `VirtualDisplay` (`resize` + `setSurface`) sin reiniciar.
- Cada visor recibe siempre el último fotograma disponible, así que una red lenta baja los fps
  pero no acumula retraso.
- Es una solución temporal: los navegadores solo permiten decodificar H.264 (WebCodecs) en
  direcciones seguras (HTTPS o `localhost`), y una IP local `http://` no lo es.
  Las opciones para la siguiente fase son H.264 en fMP4 con Media Source Extensions o HTTPS con certificado propio.

## Controles
- El visor abre un WebSocket a `ws://IP:8080/control?pin=XXXXXX` (`ControlChannel`, implementado a mano
  sobre el mismo servidor, sin dependencias). Mensajes de texto `tipo,x,y` con x e y de 0 a 1 sobre la
  pantalla: `d` bajar, `m` mover, `u` soltar. El servidor responde `noservice` si el servicio de
  accesibilidad no está activo.
- `InputAccessibilityService` convierte las coordenadas a píxeles reales y `TouchInjector` ejecuta el
  gesto con `dispatchGesture` como un trazo continuo en tramos (`continueStroke`), descartando posiciones
  intermedias para no acumular retraso.
- **Seguridad:** el control exige un PIN de 6 cifras generado por transmisión (`ControlSession`), con
  bloqueo tras 5 intentos fallidos. El video sigue sin contraseña. Pendiente: proteger también el video
  y cifrar el tráfico.
- Android 13+ restringe activar servicios de accesibilidad en APK instalados fuera de tienda
  ("ajustes restringidos"); la guía para usuarios está en `docs/CONTROLES.md`.
- Si la app llegara a Google Play, su política limita el uso de la API de accesibilidad a fines
  de accesibilidad, así que habría que revisarla antes de publicar allí.

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
    input/ControlChannel.kt          WebSocket de control + PIN de sesión
    input/InputAccessibilityService.kt  servicio de accesibilidad (recibe los toques)
    input/TouchInjector.kt           convierte bajar/mover/soltar en gestos
```

## Limitaciones técnicas conocidas
- El tamaño máximo (lado mayor de 1280 px) es fijo; aún no se puede cambiar desde la app.
- Contenido con `FLAG_SECURE` se captura en negro.
- Sin audio, sin cifrado y sin autenticación.
- MJPEG usa más CPU y ancho de banda que H.264.

## Gráficos
El arte (logos, íconos, fondos) lo aportan los diseñadores del proyecto; el código no genera
imágenes. Los recursos van en las carpetas de recursos de Android o en `assets/`.

## Licencia
Apache 2.0. Cada archivo fuente lleva `SPDX-License-Identifier: Apache-2.0`.
