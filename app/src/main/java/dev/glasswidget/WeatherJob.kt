package dev.glasswidget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask

internal fun Context.settings() = getSharedPreferences("glass", Context.MODE_PRIVATE)

class WeatherJob : JobService() {
    private val tasks = ConcurrentHashMap<Int, FutureTask<Unit>>()

    override fun onStartJob(params: JobParameters): Boolean {
        val prefs = settings()
        val city = prefs.getString("city", DEFAULT_CITY) ?: DEFAULT_CITY
        lateinit var task: FutureTask<Unit>
        task = FutureTask {
            val result = runCatching { forecast(city) }
            mainExecutor.execute {
                if (!tasks.remove(params.jobId, task)) return@execute
                if (prefs.getString("city", DEFAULT_CITY) == city) {
                    val edit = prefs.edit()
                    result.onSuccess {
                        edit.putString("forecast", it.toString()).putString("forecast_city", city)
                            .putLong("forecast_at", System.currentTimeMillis()).remove("weather_error")
                    }.onFailure {
                        edit.putString("weather_error", if (it is NoSuchElementException)
                            "Город не найден" else "Ошибка обновления")
                    }
                    edit.apply()
                    GlassWidget.refresh(this)
                }
                jobFinished(params, false)
            }
        }
        tasks.put(params.jobId, task)?.cancel(true)
        Thread(task, "Open-Meteo").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        tasks.remove(params.jobId)?.cancel(true)
        return true
    }

    private fun request(address: String): JSONObject {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            check(connection.responseCode == 200)
            return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }

    private fun forecast(city: String): JSONObject {
        val (latitude, longitude) = if (city.equals(DEFAULT_CITY, ignoreCase = true)) {
            54.7104 to 20.4522
        } else {
            val name = URLEncoder.encode(city, Charsets.UTF_8.name())
            val matches = request("https://geocoding-api.open-meteo.com/v1/search?name=$name&count=1&language=ru")
                .optJSONArray("results") ?: throw NoSuchElementException()
            val match = matches.optJSONObject(0) ?: throw NoSuchElementException()
            match.getDouble("latitude") to match.getDouble("longitude")
        }
        return request(
            "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                "&daily=temperature_2m_min,temperature_2m_max,precipitation_sum,wind_speed_10m_max,wind_gusts_10m_max" +
                "&wind_speed_unit=ms&timezone=auto&forecast_days=7"
        ).getJSONObject("daily")
    }

    companion object {
        const val DEFAULT_CITY = "Калининград"
        fun schedule(context: Context, immediate: Boolean = false) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val component = ComponentName(context, WeatherJob::class.java)
            val minutes = context.settings().getInt("minutes", 60)
            val period = minutes * 60_000L
            if (scheduler.getPendingJob(1)?.intervalMillis != period) {
                scheduler.schedule(JobInfo.Builder(1, component).setPeriodic(period)
                    .setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
            }
            if (immediate) {
                scheduler.schedule(JobInfo.Builder(2, component)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
            }
        }
    }
}
