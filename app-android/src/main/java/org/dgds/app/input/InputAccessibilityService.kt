// SPDX-License-Identifier: Apache-2.0
package org.dgds.app.input

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent

/**
 * Servicio de accesibilidad que ejecuta los toques enviados desde la PC.
 * El usuario lo activa una sola vez en Ajustes > Accesibilidad. No lee el contenido de la pantalla.
 */
class InputAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: InputAccessibilityService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var injector: TouchInjector? = null

    override fun onServiceConnected() {
        injector = TouchInjector(this)
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        injector = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /** [kind]: "d" bajar, "m" mover, "u" soltar. [nx], [ny]: posición de 0 a 1 sobre la pantalla. */
    fun touch(kind: String, nx: Float, ny: Float) {
        main.post {
            val inj = injector ?: return@post
            val dm = DisplayMetrics()
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION") display.getRealMetrics(dm)
            val x = nx * dm.widthPixels
            val y = ny * dm.heightPixels
            when (kind) {
                "d" -> inj.down(x, y)
                "m" -> inj.move(x, y)
                "u" -> inj.up(x, y)
            }
        }
    }
}
