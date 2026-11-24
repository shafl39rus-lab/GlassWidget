package dev.glasswidget

import android.app.Application
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract

class GlassApplication : Application() {
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { GlassWidget.refresh(this) }
    private var lastBattery = -1

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    if (level == lastBattery) return
                    lastBattery = level
                }
                scheduleRefresh()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
        getSystemService(WallpaperManager::class.java).addOnColorsChangedListener(
            { _, flags -> if (flags and WallpaperManager.FLAG_SYSTEM != 0) scheduleRefresh() }, handler
        )
        // A later grant is handled by the activity and periodic widget refresh.
        runCatching {
            contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true,
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) = scheduleRefresh()
                })
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 500)
    }
}
