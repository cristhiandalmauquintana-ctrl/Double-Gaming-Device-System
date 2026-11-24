# DGDS (Deep Gaming Device Stream)

Transmite la pantalla de tu teléfono Android a una laptop de bajos recursos para jugar.
La laptop solo decodifica el video; el juego corre en el teléfono.

**Estado: fase 1 (MVP).** Teléfono → laptop por Wi-Fi. Todavía no hay controles,
cable USB, pantalla oscura ni cliente de escritorio propio.

## Compilar (sin Android Studio)
GitHub Actions compila el APK en cada `git push` (ver `.github/workflows/build.yml`).
1. Sube el proyecto a un repositorio de GitHub (rama `main`).
2. Pestaña **Actions** → ejecución más reciente → descarga el artefacto **dgds-debug-apk**.
3. Descomprime el zip, pasa el `.apk` al teléfono e instálalo
   (hay que permitir "instalar apps de orígenes desconocidos").

Si la compilación falla, abre la ejecución en Actions y copia el error del paso
**Compilar APK de depuración**.

Con Android Studio también funciona: abre la carpeta y pulsa Run.

## Probar
1. Teléfono y laptop en la **misma red Wi-Fi** (mejor a 5 GHz).
2. En la laptop instala ffmpeg (`sudo apt install ffmpeg` en Linux).
3. En el teléfono abre DGDS → **Iniciar transmisión** → acepta el permiso.
4. La app muestra el comando exacto con tu IP. Ejemplo:

```
ffplay -fflags nobuffer -flags low_delay -framedrop -probesize 32 -analyzeduration 0 -f h264 tcp://192.168.1.50:7878
```

## Limitaciones conocidas
- La resolución se fija al iniciar. **Inicia la transmisión con el teléfono en la orientación del juego**
  (horizontal para la mayoría). Girar durante la transmisión se resuelve en una fase posterior.
- Juegos y apps con `FLAG_SECURE` se ven en negro: Android bloquea su captura.
- Sin audio todavía.
- Un solo cliente a la vez; sin cifrado ni contraseña, úsalo solo en redes de confianza.

## Estructura
```
app-android/src/main/java/org/dgds/app/
  MainActivity.kt                 pantalla principal
  core/ScreenCaptureService.kt    servicio en primer plano + captura
  core/VideoStreamer.kt           codificador H.264 + servidor TCP
  core/LocalNetwork.kt            detecta las IP locales
```

## Licencia
Apache 2.0. Falta añadir el texto oficial como `LICENSE`
(https://www.apache.org/licenses/LICENSE-2.0.txt). El archivo `NOTICE` ya está incluido.
