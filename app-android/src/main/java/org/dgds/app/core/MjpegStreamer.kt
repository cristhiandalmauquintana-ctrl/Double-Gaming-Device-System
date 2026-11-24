// SPDX-License-Identifier: Apache-2.0
package org.dgds.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import org.dgds.app.input.ControlChannel
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Beta: sirve la pantalla del teléfono como página web.
 *
 *   http://IP_DEL_TELEFONO:8080/         -> visor (assets/viewer.html)
 *   http://IP_DEL_TELEFONO:8080/stream   -> video MJPEG (JPEG tras JPEG)
 *
 * Cualquier navegador sin instalar nada. Cada cliente recibe siempre el último
 * fotograma disponible, así que una red lenta baja los fps pero no acumula retraso.
 * Más adelante se sustituirá por H.264 (menos ancho de banda y menos CPU).
 */
class MjpegStreamer(
    context: Context,
    private val projection: MediaProjection,
    private var width: Int,
    private var height: Int,
    private val dpi: Int,
    private val port: Int = PORT,
) {
    companion object {
        const val PORT = 8080
        const val QUALITY = 60            // calidad JPEG 1-100
        const val FPS = 30
        private const val IDLE_INTERVAL_MS = 500L // sin visores, solo mantener un fotograma reciente
    }

    private val appContext = context.applicationContext

    @Volatile private var running = false
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val clients = AtomicInteger(0)

    // Último fotograma (protegido por lock)
    private val lock = ReentrantLock()
    private val newFrame = lock.newCondition()
    private var latest: ByteArray? = null
    private var seq = 0L

    // Solo se usan en el hilo de captura
    private var bitmap: Bitmap? = null
    private val jpeg = ByteArrayOutputStream(256 * 1024)
    private var lastFrame = 0L
    private var pending = false

    fun start() {
        running = true
        val ht = HandlerThread("dgds-capture").also { it.start() }
        val h = Handler(ht.looper)
        handlerThread = ht
        handler = h

        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ schedule() }, h)
        reader = r

        display = projection.createVirtualDisplay(
            "dgds", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, h,
        )
        thread(name = "dgds-http") { acceptLoop() }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        sockets.forEach { runCatching { it.close() } }
        lock.withLock { newFrame.signalAll() }
        pool.shutdownNow()
        display?.release()
        display = null
        handlerThread?.quitSafely()
        runCatching { handlerThread?.join(500) }
        handlerThread = null
        reader?.close()
        reader = null
        bitmap?.recycle()
        bitmap = null
    }

    /** Adapta la captura a un nuevo tamaño (p. ej. al girar el teléfono) sin reiniciar la transmisión. */
    fun resize(newWidth: Int, newHeight: Int) {
        handler?.post { applyResize(newWidth, newHeight) }
    }

    // Se ejecuta en el hilo de captura, el mismo que codifica, así que no hay carreras.
    private fun applyResize(newWidth: Int, newHeight: Int) {
        if (!running || (newWidth == width && newHeight == height)) return
        val d = display ?: return
        val old = reader
        val fresh = ImageReader.newInstance(newWidth, newHeight, PixelFormat.RGBA_8888, 3)
        fresh.setOnImageAvailableListener({ schedule() }, handler)
        width = newWidth
        height = newHeight
        reader = fresh
        d.resize(newWidth, newHeight, dpi)
        d.setSurface(fresh.surface)
        bitmap?.recycle()
        bitmap = null
        pending = false
        old?.close()
    }

    // ---- Captura y codificación (hilo dgds-capture) ----

    private fun schedule() {
        val interval = if (clients.get() > 0) 1000L / FPS else IDLE_INTERVAL_MS
        val wait = interval - (SystemClock.elapsedRealtime() - lastFrame)
        if (wait <= 0) {
            encodeLatest()
        } else if (!pending) {
            // No se descarta el último cambio de pantalla: se codifica cuando toque.
            pending = true
            handler?.postDelayed({ encodeLatest() }, wait)
        }
    }

    private fun encodeLatest() {
        pending = false
        val r = reader ?: return
        val image = try { r.acquireLatestImage() } catch (e: IllegalStateException) { null }
        if (image == null) return
        try {
            lastFrame = SystemClock.elapsedRealtime()
            val plane = image.planes[0]
            val padded = plane.rowStride / plane.pixelStride
            var bmp = bitmap
            if (bmp == null || bmp.width != padded) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(padded, height, Bitmap.Config.ARGB_8888)
                bitmap = bmp
            }
            bmp.copyPixelsFromBuffer(plane.buffer)
            val visible = if (padded == width) bmp else Bitmap.createBitmap(bmp, 0, 0, width, height)
            jpeg.reset()
            visible.compress(Bitmap.CompressFormat.JPEG, QUALITY, jpeg)
            if (visible !== bmp) visible.recycle()
            val bytes = jpeg.toByteArray()
            lock.withLock {
                latest = bytes
                seq++
                newFrame.signalAll()
            }
        } catch (e: Exception) {
            // fotograma perdido; el siguiente lo compensa
        } finally {
            image.close()
        }
    }

    // ---- Servidor HTTP ----

    private fun acceptLoop() {
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (e: IOException) {
            return
        }
        server = ss
        while (running) {
            val s = try { ss.accept() } catch (e: IOException) { break }
            sockets.add(s)
            try { pool.execute { handle(s) } } catch (e: Exception) { s.closeQuietly() }
        }
    }

    private fun handle(s: Socket) {
        try {
            s.tcpNoDelay = true
            s.soTimeout = 5000
            val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = input.readLine() ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val l = input.readLine()
                if (l.isNullOrEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            s.soTimeout = 0
            val target = requestLine.split(" ").getOrNull(1) ?: "/"
            when (target.substringBefore('?')) {
                "/", "/index.html" -> sendPage(s)
                "/stream" -> sendStream(s)
                "/control" -> ControlChannel.serve(s, headers["sec-websocket-key"], target.substringAfter('?', ""))
                else -> sendNotFound(s)
            }
        } catch (e: IOException) {
            // cliente desconectado
        } finally {
            sockets.remove(s)
            s.closeQuietly()
        }
    }

    private fun sendPage(s: Socket) {
        val body = appContext.assets.open("viewer.html").use { it.readBytes() }
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        val out = s.getOutputStream()
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun sendNotFound(s: Socket) {
        val out = s.getOutputStream()
        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun sendStream(s: Socket) {
        val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-store\r\nPragma: no-cache\r\nConnection: close\r\n\r\n").toByteArray()
        )
        clients.incrementAndGet()
        try {
            var last = 0L
            while (running) {
                val frame: ByteArray = lock.withLock {
                    while (running && seq == last) newFrame.await(1, TimeUnit.SECONDS)
                    if (!running) return
                    last = seq
                    latest!!
                }
                out.write(
                    "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray()
                )
                out.write(frame)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } finally {
            clients.decrementAndGet()
        }
    }

    private fun Socket.closeQuietly() {
        try { close() } catch (_: IOException) {}
    }
}
