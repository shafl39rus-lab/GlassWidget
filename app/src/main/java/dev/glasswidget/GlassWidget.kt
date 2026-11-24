package dev.glasswidget

import android.Manifest
import android.app.PendingIntent
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import android.widget.RemoteViews
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

internal val russian = Locale.forLanguageTag("ru-RU")
internal val shortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM", russian)

class GlassWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WeatherJob.schedule(context, !context.settings().contains("forecast"))
        updateAsync(context)
    }

    companion object {
        internal val renderer = Executors.newSingleThreadExecutor()

        internal fun open(context: Context, screen: String): PendingIntent =
            PendingIntent.getActivity(context, screen.hashCode(),
                Intent(context, SettingsActivity::class.java).setAction("dev.glasswidget.$screen")
                    .putExtra("screen", screen), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        internal fun refresh(context: Context) {
            val app = context.applicationContext
            renderer.execute {
                val manager = AppWidgetManager.getInstance(app)
                val ids = manager.getAppWidgetIds(ComponentName(app, GlassWidget::class.java))
                if (ids.isNotEmpty()) manager.updateAppWidget(ids, render(app))
            }
        }

        private fun render(context: Context): RemoteViews {
            val prefs = context.settings()
            val today = LocalDate.now()
            val month = YearMonth.from(today)
            val events = monthEvents(context, month)
            val eventDates = events?.mapTo(hashSetOf()) { it.date }.orEmpty()
            val city = prefs.getString("city", WeatherJob.DEFAULT_CITY) ?: WeatherJob.DEFAULT_CITY
            val cached = if (prefs.getString("forecast_city", null) == city) {
                runCatching { JSONObject(prefs.getString("forecast", null).orEmpty()) }.getOrNull()
            } else null
            val theme = prefs.getInt("theme", 0)
            val dark = theme == 2 || (theme == 0 &&
                context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
            val wallpaper = runCatching {
                WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
            }.getOrNull()
            val accent = wallpaper ?: context.getColor(android.R.color.system_accent1_500)
            val palette = Palette(dark, accent, prefs.getInt("transparency", 80))
            val views = RemoteViews(context.packageName, R.layout.widget)

            val caption = month.format(DateTimeFormatter.ofPattern("LLLL yyyy", russian)).replaceFirstChar { it.titlecase(russian) }
            val calendarStatus = when {
                events == null -> "Календарь недоступен"
                events.isEmpty() -> "Нет событий"
                else -> {
                    val selected = events.firstOrNull { it.date >= today } ?: events.last()
                    "${selected.date.format(shortDate)} · ${selected.title}"
                }
            }
            views.card(context, R.id.calendar, palette, 180, "calendar", "$caption. $calendarStatus") {
                title(caption)
                listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEachIndexed { index, label ->
                    label(label, 42f + index * 52, 45f, 13f, centered = true)
                }
                val offset = month.atDay(1).dayOfWeek.value - 1
                for (day in 1..month.lengthOfMonth()) {
                    val slot = offset + day - 1
                    val x = 42f + slot % 7 * 52
                    val y = 63f + slot / 7 * 16
                    val date = month.atDay(day)
                    if (date == today) circle(x, y - 4, 10f, palette.foreground)
                    label(day.toString(), x, y, 13f, centered = true, inverse = date == today)
                    if (date in eventDates) circle(x, y + 3, 1.8f,
                        if (date == today) palette.solid else palette.foreground)
                }
                label(calendarStatus, 19f, 166f, 12f, maxWidth = 362f)
            }

            val dateRows = cached?.optJSONArray("time")
            fun weatherValue(key: String, index: Int): String =
                cached?.optJSONArray(key)?.optDouble(index)?.takeIf { it.isFinite() }
                    ?.let { if (it == it.roundToInt().toDouble()) it.roundToInt().toString()
                        else String.format(russian, "%.1f", it) } ?: "—"

            val description = mutableListOf("Погода · $city")
            val weather = Card(palette, 225).apply {
                title("Погода · $city")
                val error = prefs.getString("weather_error", null)
                if (dateRows == null || dateRows.length() == 0) {
                    val status = error ?: "Прогноз пока недоступен"
                    label(status, 19f, 57f, 15f)
                    description.add(status)
                } else {
                    label("Дата       Мин / макс °C     Осадки мм     Ветер / порывы м/с", 19f, 49f, 12f)
                    for (i in 0 until minOf(7, dateRows.length())) {
                        val date = runCatching { LocalDate.parse(dateRows.getString(i)).format(shortDate) }.getOrDefault("—")
                        val min = weatherValue("temperature_2m_min", i)
                        val max = weatherValue("temperature_2m_max", i)
                        val rain = weatherValue("precipitation_sum", i)
                        val wind = weatherValue("wind_speed_10m_max", i)
                        val gust = weatherValue("wind_gusts_10m_max", i)
                        val y = 71f + 20 * i
                        label(date, 19f, y, 14f)
                        label("$min / $max", 124f, y, 14f, centered = true)
                        label(rain, 221f, y, 14f, centered = true)
                        label("$wind / $gust", 328f, y, 14f, centered = true)
                        description.add("$date. Температура $min / $max °C, осадки $rain мм, ветер $wind, порывы $gust м/с")
                    }
                    val updated = prefs.getLong("forecast_at", 0L).takeIf { it > 0 }?.let {
                        java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("dd.MM HH:mm", russian))
                    }
                    val source = listOfNotNull("Open-Meteo", updated, error).joinToString(" · ")
                    label(source, 19f, 211f, 11f)
                    description.add(source)
                }
            }
            views.setImageViewBitmap(R.id.weather, weather.bitmap)
            views.setContentDescription(R.id.weather, description.joinToString(". "))
            views.setOnClickPendingIntent(R.id.weather, open(context, "settings"))

            val steps = prefs.getLong("steps", 0)
            val goal = prefs.getInt("goal", 10000)
            val hasSensor = context.getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
            val canReadSteps = context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
            val hasStepData = hasSensor && canReadSteps && prefs.getString("step_day", null) == today.toString()
            val stepStatus = when {
                !hasSensor -> "Нет датчика"
                !canReadSteps -> "Нет разрешения"
                !hasStepData && !StepService.running -> "Подсчёт выключен"
                !hasStepData -> "Нет данных"
                !StepService.running -> "$steps · пауза"
                else -> "$steps / $goal"
            }
            val battery = context.getSystemService(BatteryManager::class.java)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
            val charge = battery?.let { "$it%" } ?: "Нет данных"
            views.card(context, R.id.meters, palette, 96, "settings", "Шаги. $stepStatus. Заряд. $charge") {
                ring(49f, if (hasStepData) steps.toFloat() / goal else null)
                label("Шаги", 85f, 39f, 16f)
                label(stepStatus, 85f, 61f, 13f, maxWidth = 115f)
                ring(246f, battery?.div(100f))
                label("Заряд", 283f, 39f, 16f)
                label(charge, 283f, 61f, 13f, maxWidth = 97f)
            }
            val balance = prefs.getString("balance", null)?.let { "$it ₽ · ${prefs.getString("balance_date", "")}" }
                ?: "Баланс не указан"
            views.card(context, R.id.balance, palette, 62, "beeline", "Билайн. $balance. Открыть приложение") {
                label("Билайн", 19f, 36f, 18f)
                label(balance, 122f, 36f, 15f, maxWidth = 225f)
                label("›", 365f, 36f, 23f)
            }
            return views
        }
    }
}

private fun RemoteViews.card(context: Context, id: Int, palette: Palette, height: Int,
                             screen: String, description: String, draw: Card.() -> Unit) {
    setImageViewBitmap(id, Card(palette, height).apply(draw).bitmap)
    setContentDescription(id, description)
    setOnClickPendingIntent(id, GlassWidget.open(context, screen))
}

private class Palette(dark: Boolean, private val accent: Int, transparency: Int) {
    val foreground = if (dark) Color.rgb(250, 250, 255) else Color.rgb(18, 25, 42)
    val solid = if (dark) Color.rgb(18, 25, 42) else Color.WHITE
    private val alpha = ((100 - transparency) * 255 / 100).coerceIn(0, 255)
    private fun tint(amount: Float): Int {
        fun blend(base: Int, color: Int) = (base * (1 - amount) + color * amount).roundToInt()
        return Color.argb(alpha, blend(Color.red(solid), Color.red(accent)),
            blend(Color.green(solid), Color.green(accent)), blend(Color.blue(solid), Color.blue(accent)))
    }
    val top = tint(.15f)
    val bottom = tint(.48f)
}

private class Card(private val palette: Palette, height: Int) {
    val bitmap: Bitmap = Bitmap.createBitmap(480, height * 6 / 5, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap).apply { scale(1.2f, 1.2f) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        paint.shader = LinearGradient(0f, 0f, 400f, height.toFloat(),
            palette.top, palette.bottom, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(3f, 3f, 397f, height - 3f, 19f, 19f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = 0x55ffffff
        canvas.drawRoundRect(3f, 3f, 397f, height - 3f, 19f, 19f, paint)
        paint.style = Paint.Style.FILL
    }

    fun title(value: String) = label(value, 19f, 25f, 19f, maxWidth = 360f)

    fun label(value: String, x: Float, y: Float, size: Float, centered: Boolean = false,
              inverse: Boolean = false, maxWidth: Float = 380f) {
        paint.color = if (inverse) palette.solid else palette.foreground
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = size
        paint.textAlign = if (centered) Paint.Align.CENTER else Paint.Align.LEFT
        val line = value.replace('\n', ' ')
        val text = if (paint.measureText(line) <= maxWidth) line
        else line.take(paint.breakText(line, true, maxWidth - paint.measureText("…"), null)) + "…"
        if (!inverse) paint.setShadowLayer(2f, 0f, 0f, palette.solid)
        canvas.drawText(text, x, y, paint)
        paint.clearShadowLayer()
    }

    fun circle(x: Float, y: Float, radius: Float, color: Int) {
        paint.color = color
        canvas.drawCircle(x, y, radius, paint)
    }

    fun ring(x: Float, fraction: Float?) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = (palette.foreground and 0x00ffffff) or 0x40000000
        canvas.drawCircle(x, 47f, 24f, paint)
        if (fraction != null && fraction > 0) {
            paint.color = palette.foreground
            canvas.drawArc(x - 24, 23f, x + 24, 71f, -90f, 360 * fraction.coerceIn(0f, 1f), false, paint)
        }
        paint.style = Paint.Style.FILL
        if (fraction == null) label("—", x, 52f, 14f, centered = true)
    }
}

class DeviceChanges : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) updateAsync(context)
    }
}

private fun BroadcastReceiver.updateAsync(context: Context) {
    val result = goAsync()
    GlassWidget.refresh(context)
    GlassWidget.renderer.execute { result.finish() }
}
