// SPDX-License-Identifier: Apache-2.0
package org.dgds.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.dgds.app.core.LocalNetwork
import org.dgds.app.core.MjpegStreamer
import org.dgds.app.core.ScreenCaptureService
import org.dgds.app.input.ControlSession
import org.dgds.app.input.InputAccessibilityService

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var toggle: Button
    private lateinit var controlsStatus: TextView

    // Android 13+: permiso para mostrar la notificación del servicio. Se continúa aunque se rechace.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestCapture() }

    private val capture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, ScreenCaptureService::class.java)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(ScreenCaptureService.EXTRA_DATA, data)
                startForegroundService(intent)
                toggle.postDelayed({ refresh() }, 400)
            } else {
                status.setText(R.string.status_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        info = findViewById(R.id.info)
        toggle = findViewById(R.id.toggle)
        controlsStatus = findViewById(R.id.controls_status)

        toggle.setOnClickListener {
            if (ScreenCaptureService.running) {
                startService(
                    Intent(this, ScreenCaptureService::class.java)
                        .setAction(ScreenCaptureService.ACTION_STOP)
                )
                toggle.postDelayed({ refresh() }, 400)
            } else {
                askControlsThen { startFlow() }
            }
        }

        findViewById<Button>(R.id.btn_controls).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        // Para "Permitir ajustes restringidos" (Android 13+): esa opción está en la info de la app.
        findViewById<Button>(R.id.btn_app_info).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /**
     * Android no permite pedir Accesibilidad con un aviso de permiso como el de notificaciones:
     * hay que activarla en Ajustes. Este aviso propio lleva al usuario hasta allí, o deja seguir sin controles.
     */
    private fun askControlsThen(next: () -> Unit) {
        val prefs = getSharedPreferences("dgds", MODE_PRIVATE)
        if (InputAccessibilityService.instance != null || prefs.getBoolean("skip_controls_prompt", false)) {
            next()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_controls_title)
            .setMessage(R.string.dialog_controls_message)
            .setPositiveButton(R.string.dialog_controls_enable) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.dialog_controls_later) { _, _ -> next() }
            .setNeutralButton(R.string.dialog_controls_never) { _, _ ->
                prefs.edit().putBoolean("skip_controls_prompt", true).apply()
                next()
            }
            .show()
    }

    private fun startFlow() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestCapture()
        }
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        capture.launch(mpm.createScreenCaptureIntent())
    }

    private fun refresh() {
        val running = ScreenCaptureService.running
        status.text = if (running) getString(R.string.status_running, MjpegStreamer.PORT)
        else getString(R.string.status_idle)
        toggle.setText(if (running) R.string.btn_stop else R.string.btn_start)

        val urls = LocalNetwork.localIpv4Addresses().map { "http://$it:${MjpegStreamer.PORT}" }
        info.text = if (urls.isEmpty()) getString(R.string.info_no_network)
        else getString(R.string.info_template, urls.joinToString("\n"))

        val enabled = InputAccessibilityService.instance != null
        controlsStatus.text = buildString {
            append(getString(if (enabled) R.string.controls_on else R.string.controls_off))
            append("\n\n")
            append(
                if (running && ControlSession.pin.isNotEmpty()) getString(R.string.controls_pin, ControlSession.pin)
                else getString(R.string.controls_pin_idle)
            )
        }
    }
}
