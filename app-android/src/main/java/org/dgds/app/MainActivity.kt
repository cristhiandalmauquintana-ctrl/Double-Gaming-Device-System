// SPDX-License-Identifier: Apache-2.0
package org.dgds.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.dgds.app.core.LocalNetwork
import org.dgds.app.core.ScreenCaptureService
import org.dgds.app.core.MjpegStreamer

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var toggle: Button

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

        toggle.setOnClickListener {
            if (ScreenCaptureService.running) {
                startService(
                    Intent(this, ScreenCaptureService::class.java)
                        .setAction(ScreenCaptureService.ACTION_STOP)
                )
                toggle.postDelayed({ refresh() }, 400)
            } else if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                requestCapture()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
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
    }
}
