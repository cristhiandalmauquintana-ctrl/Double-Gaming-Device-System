// SPDX-License-Identifier: Apache-2.0
package org.dgds.app.core

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.view.Surface
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Captura la pantalla, la codifica en H.264 por hardware y la envía por TCP
 * como flujo H.264 "Annex-B" crudo (lo entiende ffplay, mpv y FFmpeg).
 *
 * Un solo cliente a la vez. Si la red no da abasto se descartan fotogramas
 * y se pide un fotograma clave nuevo, para que la latencia no se acumule.
 */
class VideoStreamer(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val port: Int = PORT,
) {
    companion object {
        const val PORT = 7878
        const val BITRATE = 8_000_000
        const val FPS = 60
        private const val QUEUE_SIZE = 6
    }

    @Volatile private var running = false
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var display: VirtualDisplay? = null
    private var server: ServerSocket? = null
    private val threads = mutableListOf<Thread>()

    @Volatile private var client: Socket? = null
    @Volatile private var config: ByteArray? = null
    @Volatile private var waitingKey = true
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_SIZE)

    fun start() {
        running = true

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // tiempo real
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 0)
            // Si la pantalla no cambia, repetir el último fotograma cada 100 ms.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        }

        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        encoder = enc
        inputSurface = surface

        display = projection.createVirtualDisplay(
            "dgds", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null,
        )

        threads += thread(name = "dgds-server") { acceptLoop() }
        threads += thread(name = "dgds-sender") { sendLoop() }
        threads += thread(name = "dgds-encoder") { encodeLoop(enc) }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        client?.closeQuietly()
        display?.release()
        threads.forEach { runCatching { it.join(1000) } }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { inputSurface?.release() }
        encoder = null
        inputSurface = null
        display = null
    }

    private fun acceptLoop() {
        val ss = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        server = ss
        while (running) {
            val s = try { ss.accept() } catch (e: IOException) { break }
            s.tcpNoDelay = true
            client?.closeQuietly()
            queue.clear()
            waitingKey = true
            client = s
            requestKeyFrame()
        }
    }

    private fun sendLoop() {
        while (running) {
            val data = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val s = client ?: continue
            try {
                s.getOutputStream().write(data)
            } catch (e: IOException) {
                s.closeQuietly()
                if (client === s) client = null
            }
        }
    }

    private fun encodeLoop(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try {
                enc.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                break
            }
            if (index < 0) continue
            val buf = enc.getOutputBuffer(index)
            if (buf != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                buf.get(bytes)
                onEncoded(bytes, info.flags)
            }
            enc.releaseOutputBuffer(index, false)
        }
    }

    private fun onEncoded(bytes: ByteArray, flags: Int) {
        if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            config = bytes // SPS/PPS
            return
        }
        if (client == null) return
        val isKey = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (waitingKey && !isKey) return // el cliente nuevo debe empezar en un fotograma clave
        val out = if (isKey) (config?.plus(bytes) ?: bytes) else bytes
        if (isKey) waitingKey = false
        if (!queue.offer(out)) { // red lenta: descartar y resincronizar
            queue.clear()
            waitingKey = true
            requestKeyFrame()
        }
    }

    private fun requestKeyFrame() {
        runCatching {
            encoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    private fun Socket.closeQuietly() {
        try { close() } catch (_: IOException) {}
    }
}
