// SPDX-License-Identifier: Apache-2.0
package org.dgds.app.input

import android.util.Base64
import java.io.DataInputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * PIN de la sesión. Se genera al iniciar la transmisión y hay que escribirlo en la PC
 * para poder controlar el teléfono. Tras 5 intentos fallidos se bloquea hasta la
 * siguiente transmisión.
 */
object ControlSession {
    private const val MAX_FAILURES = 5
    private val failures = AtomicInteger(0)

    @Volatile var pin: String = ""
        private set

    fun newSession() {
        pin = "%06d".format(SecureRandom().nextInt(1_000_000))
        failures.set(0)
    }

    fun clear() {
        pin = ""
    }

    fun check(candidate: String?): Boolean {
        if (pin.isEmpty() || failures.get() >= MAX_FAILURES) return false
        val ok = candidate == pin
        if (!ok) failures.incrementAndGet()
        return ok
    }
}

/**
 * Canal de control por WebSocket (ws://IP:8080/control?pin=...).
 * Mensajes de texto "tipo,x,y" con x e y entre 0 y 1: d = bajar, m = mover, u = soltar.
 */
object ControlChannel {
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private const val MAX_PAYLOAD = 4096L

    /** Atiende una conexión hasta que se cierre. Lanza IOException al desconectarse. */
    fun serve(socket: Socket, key: String?, query: String) {
        val out = socket.getOutputStream()
        val pin = query.split('&').firstOrNull { it.startsWith("pin=") }?.substring(4)
        if (key == null || !ControlSession.check(pin)) {
            out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray()),
            Base64.NO_WRAP,
        )
        out.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray()
        )
        out.flush()
        if (InputAccessibilityService.instance == null) sendFrame(out, 0x1, "noservice".toByteArray())

        val input = DataInputStream(socket.getInputStream())
        while (true) {
            val b0 = input.read()
            val b1 = input.read()
            if (b0 < 0 || b1 < 0) return
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = input.readUnsignedShort().toLong()
            else if (len == 127L) len = input.readLong()
            if (len < 0 || len > MAX_PAYLOAD) return
            val mask = ByteArray(4)
            if (masked) input.readFully(mask)
            val payload = ByteArray(len.toInt())
            input.readFully(payload)
            if (masked) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
            }
            when (opcode) {
                0x1 -> onMessage(String(payload, Charsets.UTF_8))
                0x8 -> return
                0x9 -> sendFrame(out, 0xA, payload) // ping -> pong
            }
        }
    }

    private fun onMessage(text: String) {
        val p = text.split(',')
        if (p.size < 3) return
        val x = p[1].toFloatOrNull() ?: return
        val y = p[2].toFloatOrNull() ?: return
        InputAccessibilityService.instance?.touch(p[0], x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }

    private fun sendFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
        out.write(0x80 or opcode)
        if (payload.size < 126) {
            out.write(payload.size)
        } else {
            out.write(126)
            out.write(payload.size shr 8)
            out.write(payload.size and 0xFF)
        }
        out.write(payload)
        out.flush()
    }
}
