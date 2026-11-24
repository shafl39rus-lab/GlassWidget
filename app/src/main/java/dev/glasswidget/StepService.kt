package dev.glasswidget

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import java.time.LocalDate

class StepService : Service(), SensorEventListener {
    companion object {
        @Volatile internal var running = false
            private set
    }

    private lateinit var sensors: SensorManager
    private var lastRefresh = -30_000L

    override fun onCreate() {
        super.onCreate()
        sensors = getSystemService(SensorManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("steps", "Подсчёт шагов", NotificationManager.IMPORTANCE_LOW))
        startForeground(10, Notification.Builder(this, "steps")
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentTitle("GlassWidget считает шаги")
            .setContentIntent(GlassWidget.open(this, "settings"))
            .setOngoing(true).build())
        if (!sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 5_000_000)) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        GlassWidget.refresh(this)
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
        val prefs = settings()
        val today = LocalDate.now().toString()
        val previousDay = prefs.getString("step_day", null)
        val raw = event.values[0].toLong()
        val previous = prefs.getLong("step_raw", -1)
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        val total = dailySteps(today == previousDay, boot == prefs.getInt("step_boot", -2),
            prefs.getLong("steps", 0), previous, raw)
        prefs.edit().putString("step_day", today).putLong("step_raw", raw)
            .putInt("step_boot", boot).putLong("steps", total).apply()
        val now = SystemClock.elapsedRealtime()
        if (now - lastRefresh >= 30_000 || today != previousDay) {
            lastRefresh = now
            GlassWidget.refresh(this)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        sensors.unregisterListener(this)
        running = false
        GlassWidget.refresh(this)
        super.onDestroy()
    }
}
