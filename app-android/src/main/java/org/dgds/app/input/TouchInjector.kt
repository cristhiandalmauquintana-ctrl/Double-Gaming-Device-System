// SPDX-License-Identifier: Apache-2.0
package org.dgds.app.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

/**
 * Convierte "bajar / mover / soltar" en gestos táctiles reales.
 *
 * Un gesto de Android no puede modificarse mientras se ejecuta, así que el arrastre se
 * hace como un trazo continuo en tramos cortos: cuando termina un tramo se envía el
 * siguiente, siempre hacia la última posición recibida (las intermedias se descartan,
 * para que el retraso no se acumule). Debe usarse solo desde el hilo principal.
 */
class TouchInjector(private val service: AccessibilityService) {

    private companion object {
        const val TAP_MS = 20L
        const val MOVE_MS = 16L
    }

    private var stroke: GestureDescription.StrokeDescription? = null
    private var inFlight = false
    private var gen = 0 // invalida los avisos de gestos anteriores
    private var lastX = 0f
    private var lastY = 0f
    private var pendingX = 0f
    private var pendingY = 0f
    private var hasPending = false
    private var endRequested = false

    fun down(x: Float, y: Float) {
        reset()
        lastX = x
        lastY = y
        val path = Path().apply { moveTo(x, y) }
        val s = GestureDescription.StrokeDescription(path, 0, TAP_MS, true)
        stroke = s
        dispatch(s)
    }

    fun move(x: Float, y: Float) {
        if (stroke == null) return
        pendingX = x
        pendingY = y
        hasPending = true
        flush()
    }

    fun up(x: Float, y: Float) {
        if (stroke == null) return
        pendingX = x
        pendingY = y
        hasPending = true
        endRequested = true
        flush()
    }

    private fun flush() {
        val s = stroke ?: return
        if (inFlight || (!hasPending && !endRequested)) return
        val tx = if (hasPending) pendingX else lastX
        val ty = if (hasPending) pendingY else lastY
        val path = Path().apply {
            moveTo(lastX, lastY)
            if (tx != lastX || ty != lastY) lineTo(tx, ty)
        }
        val willContinue = !endRequested
        val next = s.continueStroke(path, 0, MOVE_MS, willContinue)
        lastX = tx
        lastY = ty
        hasPending = false
        if (willContinue) {
            stroke = next
        } else {
            stroke = null
            endRequested = false
        }
        dispatch(next)
    }

    private fun dispatch(s: GestureDescription.StrokeDescription) {
        val myGen = gen
        inFlight = true
        val accepted = service.dispatchGesture(
            GestureDescription.Builder().addStroke(s).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (myGen == gen) {
                        inFlight = false
                        flush()
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (myGen == gen) reset()
                }
            },
            null,
        )
        if (!accepted) reset()
    }

    private fun reset() {
        gen++
        stroke = null
        inFlight = false
        hasPending = false
        endRequested = false
    }
}
