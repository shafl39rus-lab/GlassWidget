package dev.glasswidget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.YearMonth

class SettingsActivity : Activity() {
    private lateinit var panel: LinearLayout
    private val prefs by lazy { settings() }
    private val intervals = listOf(15, 30, 60, 120, 240)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderSettings()
        when (intent.getStringExtra("screen")) {
            "beeline" -> openBeeline()
            "calendar" -> showCalendar()
        }
    }

    private fun renderSettings() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val initialPadding = padding
        val root = ScrollView(this).apply { addView(panel) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val edges = insets.getInsets(android.view.WindowInsets.Type.systemBars() or
                android.view.WindowInsets.Type.displayCutout())
            view.setPadding(edges.left, edges.top, edges.right, edges.bottom)
            insets
        }
        root.clipToPadding = false
        setContentView(root)
        root.requestApplyInsets()
        text("GlassWidget", 30f).setPadding(0, 0, 0, initialPadding)
        val transparencyText = text("", 16f)
        val transparency = SeekBar(this).apply {
            max = 100
            progress = prefs.getInt("transparency", 80)
            contentDescription = "Прозрачность фона"
        }
        transparencyText.text = getString(R.string.transparency_value, transparency.progress)
        panel.addView(transparency)
        transparency.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                transparencyText.text = getString(R.string.transparency_value, value)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        val theme = selector("Цветовая тема", listOf("Системная и обои", "Светлая", "Тёмная"),
            prefs.getInt("theme", 0))
        val city = input("Город", prefs.getString("city", WeatherJob.DEFAULT_CITY).orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val goal = input("Цель по шагам", prefs.getInt("goal", 10000).toString(), InputType.TYPE_CLASS_NUMBER)
        val period = selector("Обновление прогноза", intervals.map { "$it мин" },
            intervals.indexOf(prefs.getInt("minutes", 60)).coerceAtLeast(0))
        button("Сохранить настройки") {
            val enteredGoal = goal.text.toString().toIntOrNull()
            val name = city.text.toString().trim()
            if (name.length !in 2..100) {
                city.error = "Введите название города"
                return@button
            }
            if (enteredGoal == null || enteredGoal !in 1..1_000_000) {
                goal.error = "Введите цель от 1 до 1 000 000"
                return@button
            }
            val changedCity = name != prefs.getString("city", WeatherJob.DEFAULT_CITY)
            val edit = prefs.edit().putInt("transparency", transparency.progress)
                .putInt("theme", theme.selectedItemPosition).putString("city", name)
                .putInt("goal", enteredGoal).putInt("minutes", intervals[period.selectedItemPosition])
            if (changedCity) edit.remove("weather_error")
            edit.apply()
            WeatherJob.schedule(this, true)
            GlassWidget.refresh(this)
            toast("Настройки сохранены")
        }
        button("Обновить прогноз") {
            WeatherJob.schedule(this, true)
            toast("Запрошено обновление прогноза")
        }
        button("События месяца", ::showCalendar)
        button("Включить подсчёт шагов", ::enableSteps)
        button("Остановить подсчёт шагов") {
            stopService(Intent(this, StepService::class.java))
            prefs.edit().remove("step_raw").apply()
            GlassWidget.refresh(this)
            toast("Подсчёт остановлен")
        }
        button("Обновить баланс Билайн вручную", ::editBalance)
        button("Открыть Билайн", ::openBeeline)
        button("Добавить виджет на главный экран") {
            val manager = AppWidgetManager.getInstance(this)
            if (!manager.isRequestPinAppWidgetSupported ||
                !manager.requestPinAppWidget(ComponentName(this, GlassWidget::class.java), null, null)) {
                toast("На главном экране удерживайте свободное место и выберите GlassWidget в списке виджетов")
            }
        }
    }

    private fun showCalendar() {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 1)
            return
        }
        GlassWidget.renderer.execute {
            val events = monthEvents(this, YearMonth.now())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val message = when {
                    events == null -> "Календарь недоступен"
                    events.isEmpty() -> "В этом месяце нет событий"
                    else -> events.joinToString("\n") { "${it.date.format(shortDate)} · ${it.title}" }
                }
                AlertDialog.Builder(this).setTitle("События месяца").setMessage(message)
                    .setPositiveButton("Закрыть", null).show()
            }
        }
    }

    private fun enableSteps() {
        if (getSystemService(android.hardware.SensorManager::class.java)
                .getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER) == null) {
            toast("На устройстве нет датчика шагов")
            return
        }
        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 2)
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notification_requested", false)) {
            prefs.edit().putBoolean("notification_requested", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
            return
        }
        startForegroundService(Intent(this, StepService::class.java))
        toast("Подсчёт шагов включён")
    }

    @Deprecated("Android permission callback")
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        val granted = results.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (code) {
            1 -> if (granted) { GlassWidget.refresh(this); showCalendar() } else toast("Нет доступа к календарю")
            2 -> if (granted) enableSteps() else toast("Нет доступа к датчику шагов")
            3 -> enableSteps()
        }
    }

    private fun openBeeline() {
        val launch = packageManager.getLaunchIntentForPackage("ru.beeline.services")
        if (launch != null && runCatching { startActivity(launch) }.isSuccess) return
        AlertDialog.Builder(this).setTitle("Приложение Билайн недоступно")
            .setMessage("Последний сохранённый баланс можно обновить вручную.")
            .setPositiveButton("Ввести баланс") { _, _ -> editBalance() }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun editBalance() {
        val field = EditText(this).apply {
            hint = "Баланс в рублях"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(prefs.getString("balance", ""))
        }
        val dialog = AlertDialog.Builder(this).setTitle("Баланс Билайн")
            .setMessage("Введите сумму из приложения Билайн.")
            .setView(field).setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = field.text.toString().trim().replace(',', '.').toBigDecimalOrNull()
            if (value == null || value.scale() > 2 || value.abs() > 1_000_000_000.toBigDecimal()) {
                field.error = "Введите сумму с точностью до двух знаков"
                return@setOnClickListener
            }
            prefs.edit().putString("balance", value.stripTrailingZeros().toPlainString().replace('.', ','))
                .putString("balance_date", LocalDate.now().format(shortDate)).apply()
            GlassWidget.refresh(this)
            dialog.dismiss()
        }
    }

    private fun text(value: String, size: Float): TextView = TextView(this).apply {
        text = value
        textSize = size
        panel.addView(this)
    }

    private fun input(label: String, value: String, type: Int): EditText {
        val caption = text(label, 16f)
        return EditText(this).apply {
            id = android.view.View.generateViewId()
            caption.labelFor = id
            inputType = type
            setSingleLine()
            setText(value)
            panel.addView(this)
        }
    }

    private fun selector(label: String, values: List<String>, index: Int): Spinner {
        val caption = text(label, 16f)
        return Spinner(this).apply {
            id = android.view.View.generateViewId()
            caption.labelFor = id
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, values)
            setSelection(index)
            panel.addView(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun button(label: String, action: () -> Unit) {
        panel.addView(Button(this).apply { text = label; setOnClickListener { action() } })
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
