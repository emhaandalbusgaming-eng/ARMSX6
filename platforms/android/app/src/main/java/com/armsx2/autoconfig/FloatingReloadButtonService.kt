package com.armsx2.autoconfig

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.armsx2.config.Settings

/**
 * Floating "Reload Graphics -1 Level" button, shown over the emulator surface
 * while a game is running. Tap = AutoConfigEngine.downgradeOneLevel().
 *
 * Requires SYSTEM_ALERT_WINDOW (overlay) permission, which on Android 6+
 * needs an explicit user grant via Settings.ACTION_MANAGE_OVERLAY_PERMISSION
 * — see [hasOverlayPermission] / [requestOverlayPermission]. The service
 * itself is started/stopped by the emulation activity around VM start/stop
 * (see integration notes); it does nothing on its own if no game is running.
 */
class FloatingReloadButtonService : Service() {

    companion object {
        private const val CHANNEL_ID = "autoconfig_overlay"
        private const val NOTIF_ID = 4201

        /** Supplies the Settings the engine should mutate — set by the
         *  emulation activity/runtime whenever the running config changes,
         *  so the service always downgrades against the *current* settings,
         *  not a stale snapshot from when it was started. */
        @Volatile
        var currentSettingsProvider: (() -> Settings)? = null

        fun hasOverlayPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < 23 || AndroidSettings.canDrawOverlays(context)

        fun requestOverlayPermission(context: Context) {
            val intent = Intent(
                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        fun start(context: Context) {
            if (!hasOverlayPermission(context)) return
            val intent = Intent(context, FloatingReloadButtonService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingReloadButtonService::class.java))
        }
    }

    private var windowManager: WindowManager? = null
    private var buttonView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Graphics overlay", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("AetherSX2 graphics overlay active")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(NOTIF_ID, notification)
        addOverlayButton()
    }

    private fun addOverlayButton() {
        if (!hasOverlayPermission(this)) { stopSelf(); return }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val button = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            setBackgroundColor(0x66000000)
            alpha = 0.85f
        }

        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            120, 120, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 200
        }

        // Simple drag-to-move + tap-to-trigger, distinguished by movement distance
        // so the button can be repositioned without accidentally firing a downgrade.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragged = false
        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        dragged = true
                        params.x = startX + dx
                        params.y = startY + dy
                        runCatching { windowManager?.updateViewLayout(v, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) onReloadTapped()
                    true
                }
                else -> false
            }
        }

        runCatching { windowManager?.addView(button, params) }
            .onFailure { stopSelf() }
        buttonView = button
    }

    private fun onReloadTapped() {
        val settings = currentSettingsProvider?.invoke() ?: return
        val description = AutoConfigEngine.downgradeOneLevel(settings)
        Toast.makeText(this, description, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        buttonView?.let { runCatching { windowManager?.removeView(it) } }
        buttonView = null
    }
}
