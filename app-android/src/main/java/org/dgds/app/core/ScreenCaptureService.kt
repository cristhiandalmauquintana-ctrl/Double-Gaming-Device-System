// SPDX-License-Identifier: Apache-2.0
package org.dgds.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import org.dgds.app.R
import kotlin.math.max
import kotlin.math.min

/** Servicio en primer plano que mantiene viva la captura y la transmisión. */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_STOP = "org.dgds.app.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "dgds_stream"
        private const val NOTIFICATION_ID = 1
        private const val MAX_SIDE = 1280 // lado mayor máximo: ligero para laptops modestas

        @Volatile var running = false
            private set
    }

    private var projection: MediaProjection? = null
    private var streamer: MjpegStreamer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (streamer != null) return START_NOT_STICKY

        startAsForeground() // debe hacerse antes de obtener la proyección (Android 14+)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(resultCode, data)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))
        projection = mp

        val dm = DisplayMetrics()
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION") display.getRealMetrics(dm)
        val scale = min(1f, MAX_SIDE.toFloat() / max(dm.widthPixels, dm.heightPixels))
        val w = max(16, (dm.widthPixels * scale).toInt() and 7.inv()) // múltiplo de 8
        val h = max(16, (dm.heightPixels * scale).toInt() and 7.inv())

        try {
            streamer = MjpegStreamer(applicationContext, mp, w, h, dm.densityDpi).also { it.start() }
            running = true
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        streamer?.stop()
        streamer = null
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.stat_sys_upload) // provisional: reemplazar con tu ícono
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }
}
