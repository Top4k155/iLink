package com.ilink.lamp

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider
import com.ilink.lamp.databinding.ActivityMainBinding
import com.ilink.lamp.databinding.DialogTimersBinding
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private var shared: BleController? = null
        private val PRESETS = arrayOf(
            intArrayOf(255, 59, 48), intArrayOf(255, 149, 0), intArrayOf(255, 204, 0),
            intArrayOf(52, 199, 89), intArrayOf(0, 199, 190), intArrayOf(50, 173, 230),
            intArrayOf(0, 122, 255), intArrayOf(88, 86, 214), intArrayOf(175, 82, 222),
            intArrayOf(255, 45, 85)
        )
    }

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var ctrl: BleController
    private val dp by lazy { resources.displayMetrics.density }

    private var cr = 59
    private var cg = 130
    private var cb = 246
    private var modeName = "Белый свет"
    private var volume = 17
    private var mute = false
    private var rLumin = 255
    private var rSpeed = 100
    private var fadeStyle = "Перелив 1 (Радуга)"
    private var randomStyle = "Смена 1 (7 цветов)"
    private var rhythmStyle = "Резкая вспышка"

    private var tb: DialogTimersBinding? = null
    private var timersStatus = ""
    private val alarmT = arrayOf("07:00", "07:00", "07:00")
    private var ledOnT = "19:00"
    private var ledOffT = "23:00"

    private var afterPerm: (() -> Unit)? = null
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val next = afterPerm
            afterPerm = null
            if (res.values.all { it }) next?.invoke()
            else ctrl.setStatus("Нет разрешений Bluetooth: выдайте их в настройках приложения")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("ilink", MODE_PRIVATE)
        val dark = prefs.getString("theme", "dark") != "light"
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        ctrl = shared ?: BleController(applicationContext).also { shared = it }

        fadeStyle = pick("fade_style", BleController.FADE.keys, fadeStyle)
        randomStyle = pick("random_style", BleController.RANDOM.keys, randomStyle)
        rhythmStyle = pick("rhythm_style", BleController.RHYTHM.keys, rhythmStyle)
        rLumin = prefs.getInt("rhythm_lumin", 255).coerceIn(1, 255)
        rSpeed = prefs.getInt("rhythm_speed", 100).coerceIn(1, 100)

        setupTopBar()
        setupMainCard()
        setupModes()

        ctrl.listener = object : BleController.Listener {
            override fun onStatus(text: String) {
                b.tvConn.text = text
            }

            override fun onDevices(items: List<String>, selected: Int) {
                b.spDevices.adapter = adapterOf(items)
                if (items.isNotEmpty()) b.spDevices.setSelection(selected)
            }
        }
        ctrl.replay()

        if (!ctrl.started) {
            ctrl.started = true
            b.root.postDelayed({ ensurePerms { ctrl.scanAndConnect(null) } }, 300)
        }
    }

    override fun onPause() {
        prefs.edit().putInt("rhythm_lumin", rLumin).putInt("rhythm_speed", rSpeed).apply()
        super.onPause()
    }

    override fun onDestroy() {
        ctrl.listener = null
        tb = null
        if (isFinishing) {
            ctrl.shutdown()
            shared = null
        }
        super.onDestroy()
    }

    // ---------- Вспомогательное ----------

    private fun pick(key: String, keys: Set<String>, def: String): String {
        val v = prefs.getString(key, def) ?: def
        return if (v in keys) v else def
    }

    private fun adapterOf(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun bindSpinner(sp: Spinner, items: List<String>, current: String, onPick: (String) -> Unit) {
        sp.adapter = adapterOf(items)
        sp.setSelection(items.indexOf(current).coerceAtLeast(0), false)
        var touched = false
        sp.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) touched = true
            false
        }
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (touched) {
                    touched = false
                    onPick(items[pos])
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun neededPerms(): Array<String> = when {
        Build.VERSION.SDK_INT >= 31 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        Build.VERSION.SDK_INT >= 23 -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyArray()
    }

    private fun ensurePerms(next: () -> Unit) {
        val missing = neededPerms().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) next()
        else {
            afterPerm = next
            permLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requireLink(): Boolean {
        if (ctrl.isLinked) return true
        ctrl.setStatus("Нет связи с лампой. Нажмите «Переподключить»")
        return false
    }

    private fun refreshCard() {
        val d = GradientDrawable()
        d.cornerRadius = 14 * dp
        d.setColor(Color.rgb(cr, cg, cb))
        d.setStroke((2 * dp).toInt(), ContextCompat.getColor(this, R.color.card_border))
        b.viewPreview.background = d
        b.etHex.setText(String.format(Locale.US, "#%02X%02X%02X", cr, cg, cb))
        b.tvRgb.text = "R: $cr\nG: $cg\nB: $cb"
    }

    // ---------- Верхняя панель и подключение ----------

    private fun setupTopBar() {
        b.btnTheme.setOnClickListener {
            val dark = prefs.getString("theme", "dark") != "light"
            prefs.edit().putString("theme", if (dark) "light" else "dark").apply()
            AppCompatDelegate.setDefaultNightMode(
                if (dark) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
            )
        }
        b.btnTimers.setOnClickListener { openTimers() }
        b.btnBtSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        b.btnReconnect.setOnClickListener {
            val label = b.spDevices.selectedItem as? String
            val dev = label?.let { ctrl.deviceFor(it) }
            ensurePerms { ctrl.scanAndConnect(dev) }
        }
    }

    // ---------- Основная карточка ----------

    private fun setupMainCard() {
        b.btnOn.setOnClickListener {
            if (!requireLink()) return@setOnClickListener
            refreshCard()
            ctrl.sendDirect(BleController.cmdPower(true))
        }
        b.btnOff.setOnClickListener {
            if (!requireLink()) return@setOnClickListener
            refreshCard()
            ctrl.sendDirect(BleController.cmdPower(false))
        }
        b.btnMute.setOnClickListener {
            if (!requireLink()) return@setOnClickListener
            mute = !mute
            b.btnMute.isSelected = mute
            ctrl.sendDirect(BleController.cmdMute(mute))
        }

        b.slBright.addOnChangeListener { _, v, fromUser ->
            if (!fromUser || !requireLink()) return@addOnChangeListener
            val br = v.toInt()
            b.tvBright.text = "Яркость: $br%"
            refreshCard()
            ctrl.sendQueued(BleController.cmdBrightness(br))
        }

        b.slVol.value = volume.toFloat()
        b.tvVol.text = "Громкость лампы: $volume из 32"
        b.slVol.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            volume = v.toInt()
            b.tvVol.text = "Громкость лампы: $volume из 32"
        }
        b.slVol.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                if (requireLink()) ctrl.sendDirect(BleController.cmdVolume(volume))
            }
        })

        b.slTemp.addOnChangeListener { _, v, fromUser ->
            if (!fromUser || !requireLink()) return@addOnChangeListener
            val warm = v.toInt()
            val tone = if (warm > 60) "Тёплый" else if (warm < 40) "Холодный" else "Нейтральный"
            b.tvTemp.text = "Температура белого: $tone ($warm%)"
            modeName = "Белый свет"
            refreshCard()
            ctrl.sendQueued(BleController.cmdCoolWarm(Math.rint(warm * 255 / 100.0).toInt()))
        }

        b.picker.setRgb(cr, cg, cb)
        b.picker.onColorChange = { r, g, bl -> onPickerRgb(r, g, bl) }

        b.etHex.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) {
                onHex()
                true
            } else false
        }

        for (p in PRESETS) {
            val v = View(this)
            val d = GradientDrawable()
            d.cornerRadius = 9 * dp
            d.setColor(Color.rgb(p[0], p[1], p[2]))
            v.background = d
            val lp = LinearLayout.LayoutParams(0, (32 * dp).toInt(), 1f)
            lp.setMargins((3 * dp).toInt(), 0, (3 * dp).toInt(), 0)
            v.layoutParams = lp
            v.setOnClickListener { selectPreset(p[0], p[1], p[2]) }
            b.llPresets.addView(v)
        }
        refreshCard()
    }

    private fun onPickerRgb(r: Int, g: Int, bl: Int) {
        if (!requireLink()) return
        cr = r; cg = g; cb = bl
        modeName = "Цветной (RGB)"
        refreshCard()
        ctrl.sendQueued(BleController.cmdRgb(r, g, bl))
    }

    private fun selectPreset(r: Int, g: Int, bl: Int) {
        b.picker.setRgb(r, g, bl)
        onPickerRgb(r, g, bl)
    }

    private fun onHex() {
        val raw = b.etHex.text.toString().trim().trimStart('#')
        if (raw.length != 6) return
        try {
            var r = raw.substring(0, 2).toInt(16)
            var g = raw.substring(2, 4).toInt(16)
            var bl = raw.substring(4, 6).toInt(16)
            if (r == 0 && g == 0 && bl == 0) {
                r = 1; g = 1; bl = 1
            }
            selectPreset(r, g, bl)
        } catch (_: NumberFormatException) {
        }
    }

    // ---------- Режимы ----------

    private fun setupModes() {
        bindSpinner(b.spFade, BleController.FADE.keys.toList(), fadeStyle) {
            fadeStyle = it
            prefs.edit().putString("fade_style", it).apply()
            setModeFade()
        }
        bindSpinner(b.spRandom, BleController.RANDOM.keys.toList(), randomStyle) {
            randomStyle = it
            prefs.edit().putString("random_style", it).apply()
            setModeRandom()
        }
        bindSpinner(b.spRhythm, BleController.RHYTHM.keys.toList(), rhythmStyle) {
            rhythmStyle = it
            prefs.edit().putString("rhythm_style", it).apply()
            setModeRhythm()
        }
        b.btnFade.setOnClickListener { setModeFade() }
        b.btnRandom.setOnClickListener { setModeRandom() }
        b.btnRhythm.setOnClickListener { setModeRhythm() }

        b.slRhythmLumin.value = rLumin.toFloat()
        b.tvRhythmLumin.text = "Яркость ритма: $rLumin из 255"
        b.slRhythmLumin.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            rLumin = v.toInt()
            b.tvRhythmLumin.text = "Яркость ритма: $rLumin из 255"
            sendRhythmTuned()
        }
        b.slRhythmSpeed.value = rSpeed.toFloat()
        b.tvRhythmSpeed.text = "Скорость ритма: $rSpeed из 100"
        b.slRhythmSpeed.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            rSpeed = v.toInt()
            b.tvRhythmSpeed.text = "Скорость ритма: $rSpeed из 100"
            sendRhythmTuned()
        }
    }

    private fun setModeFade() {
        if (!requireLink()) return
        modeName = "Плавный ($fadeStyle)"
        refreshCard()
        ctrl.sendDirect(BleController.cmdScene(BleController.FADE[fadeStyle] ?: 0x01))
    }

    private fun setModeRandom() {
        if (!requireLink()) return
        modeName = "Смена ($randomStyle)"
        refreshCard()
        ctrl.sendDirect(BleController.cmdScene(BleController.RANDOM[randomStyle] ?: 0x03))
    }

    private fun setModeRhythm() {
        if (!requireLink()) return
        modeName = "Цветомузыка ($rhythmStyle)"
        refreshCard()
        val code = BleController.RHYTHM[rhythmStyle] ?: 0x0D
        ctrl.sendDirect(BleController.cmdRhythmFull(code, rLumin, rSpeed))
    }

    private fun sendRhythmTuned() {
        val code = BleController.RHYTHM[rhythmStyle]
        if (code == null || !modeName.startsWith("Цветомузыка") || !ctrl.isLinked) return
        ctrl.sendQueued(BleController.cmdRhythmFull(code, rLumin, rSpeed))
    }

    // ---------- Таймеры и будильники ----------

    private fun hm(s: String): Pair<Int, Int> {
        val p = s.split(":")
        return p[0].toInt() to p[1].toInt()
    }

    private fun fmt(h: Int, m: Int) = String.format(Locale.US, "%02d:%02d", h, m)

    private fun setTimersStatus(s: String) {
        timersStatus = s
        tb?.tvTimersStatus?.text = s
    }

    private fun pickTime(cur: String, onDone: (String) -> Unit) {
        val (h, m) = hm(cur)
        TimePickerDialog(this, { _, hh, mm -> onDone(fmt(hh, mm)) }, h, m, true).show()
    }

    private fun sendTimers(items: List<Pair<Int, IntArray>>, title: String) {
        if (!ctrl.isLinked) {
            setTimersStatus("Нет связи с лампой. Подключите её и повторите.")
            return
        }
        setTimersStatus("$title: отправка...")
        ctrl.sendSequence(items) { res ->
            val text = res.joinToString("; ") { String.format(Locale.US, "0x%04X %s", it.first, it.second) }
            setTimersStatus("$title: $text")
        }
    }

    private fun openTimers() {
        val t = DialogTimersBinding.inflate(layoutInflater)
        tb = t
        val cbs = listOf(t.cbA1, t.cbA2, t.cbA3)
        val times = listOf(t.btnA1Time, t.btnA2Time, t.btnA3Time)
        val applies = listOf(t.btnA1Apply, t.btnA2Apply, t.btnA3Apply)

        for (i in 0..2) {
            val n = i + 1
            alarmT[i] = prefs.getString("alarm$n", "07:00") ?: "07:00"
            cbs[i].isChecked = prefs.getBoolean("alarm${n}_on", false)
            times[i].text = alarmT[i]
            times[i].setOnClickListener {
                pickTime(alarmT[i]) { alarmT[i] = it; times[i].text = it }
            }
            applies[i].setOnClickListener {
                val (h, m) = hm(alarmT[i])
                val on = cbs[i].isChecked
                prefs.edit().putString("alarm$n", alarmT[i]).putBoolean("alarm${n}_on", on).apply()
                val items = listOf(
                    BleController.ALARM_SET.getValue(n) to intArrayOf(h, m),
                    (if (on) BleController.ALARM_ON.getValue(n) else BleController.ALARM_OFF.getValue(n)) to IntArray(0)
                )
                sendTimers(items, "Будильник $n: ${fmt(h, m)}, ${if (on) "включён" else "выключен"}")
            }
        }

        ledOnT = prefs.getString("led_on", "19:00") ?: "19:00"
        ledOffT = prefs.getString("led_off", "23:00") ?: "23:00"
        t.cbLed.isChecked = prefs.getBoolean("led_enabled", false)
        t.btnLedOnTime.text = ledOnT
        t.btnLedOffTime.text = ledOffT
        t.btnLedOnTime.setOnClickListener { pickTime(ledOnT) { ledOnT = it; t.btnLedOnTime.text = it } }
        t.btnLedOffTime.setOnClickListener { pickTime(ledOffT) { ledOffT = it; t.btnLedOffTime.text = it } }
        t.btnLedApply.setOnClickListener {
            val (h1, m1) = hm(ledOnT)
            val (h2, m2) = hm(ledOffT)
            val on = t.cbLed.isChecked
            prefs.edit().putString("led_on", ledOnT).putString("led_off", ledOffT)
                .putBoolean("led_enabled", on).apply()
            val items = listOf(
                BleController.LED_ON_SET to intArrayOf(h1, m1),
                BleController.LED_OFF_SET to intArrayOf(h2, m2),
                (if (on) BleController.LED_TIMER_ON else BleController.LED_TIMER_OFF) to IntArray(0)
            )
            sendTimers(
                items,
                "Таймер подсветки: вкл ${fmt(h1, m1)}, выкл ${fmt(h2, m2)}, ${if (on) "включён" else "выключен"}"
            )
        }

        t.btnSyncClock.setOnClickListener {
            val c = Calendar.getInstance()
            val h = c.get(Calendar.HOUR_OF_DAY)
            val m = c.get(Calendar.MINUTE)
            val s = c.get(Calendar.SECOND)
            sendTimers(
                listOf(BleController.CMD_CLOCK_SET to intArrayOf(h, m, s)),
                String.format(Locale.US, "Часы %02d:%02d:%02d", h, m, s)
            )
        }

        t.tvTimersStatus.text = timersStatus
        AlertDialog.Builder(this)
            .setTitle("Таймеры и будильники")
            .setView(t.root)
            .setPositiveButton("Закрыть", null)
            .setOnDismissListener { tb = null }
            .show()
    }
}
