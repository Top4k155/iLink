package com.ilink.lamp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Протокол лампы iLink + очередь отправки + BLE (BluetoothGatt). */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BleController(private val ctx: Context) {

    interface Listener {
        fun onStatus(text: String)
        fun onDevices(items: List<String>, selected: Int)
    }

    companion object {
        val SVC: UUID = UUID.fromString("0000a032-0000-1000-8000-00805f9b34fb")
        val WRITE_UUID: UUID = UUID.fromString("0000a040-0000-1000-8000-00805f9b34fb")
        val READ_UUID: UUID = UUID.fromString("0000a041-0000-1000-8000-00805f9b34fb")

        const val CMD_CLOCK_SET = 0x0501
        val ALARM_SET = mapOf(1 to 0x0503, 2 to 0x0507, 3 to 0x050B)
        val ALARM_ON = mapOf(1 to 0x0505, 2 to 0x0509, 3 to 0x050D)
        val ALARM_OFF = mapOf(1 to 0x0506, 2 to 0x050A, 3 to 0x050E)
        const val LED_ON_SET = 0x0514
        const val LED_OFF_SET = 0x0515
        const val LED_TIMER_ON = 0x0516
        const val LED_TIMER_OFF = 0x0517

        val FADE = linkedMapOf(
            "Перелив 1 (Радуга)" to 0x01,
            "Перелив 2 (Тёплые тона)" to 0x02,
            "Перелив 3 (Холодные тона)" to 0x05,
            "Перелив 4 (Мягкий RGB)" to 0x06,
            "Перелив 5 (Романтика)" to 0x09,
            "Перелив 6 (Ночной)" to 0x0A
        )
        val RANDOM = linkedMapOf(
            "Смена 1 (7 цветов)" to 0x03,
            "Смена 2 (3 цвета RGB)" to 0x04,
            "Смена 3 (Пульсация)" to 0x07,
            "Смена 4 (Вспышки)" to 0x08,
            "Смена 5 (Праздник)" to 0x0B,
            "Смена 6 (Диско)" to 0x0C
        )
        val RHYTHM = linkedMapOf(
            "Резкая вспышка" to 0x0D,
            "Ритм 2 (14)" to 0x0E,
            "Ритм 3 (15)" to 0x0F
        )

        fun buildPacket(cmd: Int, payload: IntArray = IntArray(0)): ByteArray {
            val body = ArrayList<Int>()
            body.add(payload.size and 0xFF)
            body.add((cmd shr 8) and 0xFF)
            body.add(cmd and 0xFF)
            for (p in payload) body.add(p and 0xFF)
            val checksum = (0x100 - (body.sum() and 0xFF)) and 0xFF
            val out = ByteArray(body.size + 3)
            out[0] = 0x55
            out[1] = 0xAA.toByte()
            for (i in body.indices) out[i + 2] = body[i].toByte()
            out[out.size - 1] = checksum.toByte()
            return out
        }

        fun cmdPower(on: Boolean) = buildPacket(0x0805, intArrayOf(if (on) 1 else 0))
        fun cmdMute(on: Boolean) = buildPacket(if (on) 0x0407 else 0x0408)
        fun cmdVolume(v: Int) = buildPacket(0x0403, intArrayOf(v.coerceIn(0, 32)))
        fun cmdBrightness(percent: Int): ByteArray {
            val v = Math.rint(percent.coerceIn(1, 100) * 255 / 100.0).toInt()
            return buildPacket(0x0801, intArrayOf(v))
        }
        fun cmdRgb(r: Int, g: Int, b: Int): ByteArray =
            if (r == 0 && g == 0 && b == 0) buildPacket(0x0802, intArrayOf(1, 1, 1))
            else buildPacket(0x0802, intArrayOf(r, g, b))
        fun cmdCoolWarm(warm: Int) = buildPacket(0x0807, intArrayOf(warm.coerceIn(0, 255)))
        fun cmdScene(mode: Int) = buildPacket(0x0806, intArrayOf(mode and 0xFF))
        fun cmdRhythmFull(variant: Int, lumin: Int, speed: Int) =
            buildPacket(0x0806, intArrayOf(variant and 0xFF, lumin.coerceIn(1, 255), speed.coerceIn(1, 100)))
    }

    var listener: Listener? = null
    var started = false

    private val main = Handler(Looper.getMainLooper())
    private val txThread = HandlerThread("ilink-tx").also { it.start() }
    private val tx = Handler(txThread.looper)
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    // очередь отправки с троттлингом 130 мс (хранится только последний пакет)
    private val lock = Any()
    private var latest: ByteArray? = null
    private var scheduled = false
    private var lastTx = 0L

    // GATT
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var wChar: BluetoothGattCharacteristic? = null
    @Volatile private var rChar: BluetoothGattCharacteristic? = null
    @Volatile private var ready = false
    @Volatile private var latch: CountDownLatch? = null
    @Volatile private var opStatus = -1
    @Volatile private var readVal: ByteArray? = null
    private var connName = ""

    // сканирование
    private var scanning = false
    private var scanEnd: Runnable? = null
    private val scanFound = LinkedHashMap<String, Triple<BluetoothDevice, String, Boolean>>()
    private val devices = LinkedHashMap<String, BluetoothDevice>()
    private var lastStatus = "Поиск лампы iLink..."
    private var lastItems: List<String> = emptyList()
    private var lastSel = 0

    val isLinked: Boolean get() = ready && gatt != null

    fun setStatus(s: String) {
        main.post {
            lastStatus = s
            listener?.onStatus(s)
        }
    }

    fun replay() {
        listener?.onStatus(lastStatus)
        listener?.onDevices(lastItems, lastSel)
    }

    fun deviceFor(label: String): BluetoothDevice? = devices[label]

    // ---------- Сканирование и подключение ----------

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val rec = result.scanRecord
            val raw = d.name ?: rec?.deviceName
            val name = if (raw.isNullOrEmpty()) "Без имени" else raw
            val lamp = rec?.serviceUuids?.any { it.uuid == SVC } == true || name.lowercase().contains("ilink")
            scanFound[d.address] = Triple(d, name, lamp)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            scanEnd?.let { main.removeCallbacks(it) }
            setStatus("Ошибка BLE: сканирование не удалось (код $errorCode)")
        }
    }

    fun scanAndConnect(target: BluetoothDevice?) {
        if (scanning) return
        val ad = adapter
        if (ad == null || !ad.isEnabled) {
            setStatus("Включите Bluetooth и нажмите «Переподключить»")
            return
        }
        closeGatt()
        scanFound.clear()
        scanning = true
        setStatus("Поиск лампы iLink...")
        try {
            ad.bluetoothLeScanner!!.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCb
            )
        } catch (e: Exception) {
            scanning = false
            setStatus("Ошибка BLE: ${e.message}")
            return
        }
        val r = Runnable { finishScan(target) }
        scanEnd = r
        main.postDelayed(r, 5000)
    }

    private fun finishScan(target: BluetoothDevice?) {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCb)
        } catch (_: Exception) {
        }
        devices.clear()
        val labels = ArrayList<String>()
        var auto: BluetoothDevice? = target
        for ((addr, t) in scanFound) {
            val (dev, name, lamp) = t
            val label = "$name ($addr)" + if (lamp) " [iLink]" else ""
            devices[label] = dev
            labels.add(label)
            if (lamp && auto == null) auto = dev
        }
        val items = labels.sortedBy { "[iLink]" !in it }
        val a = auto
        val sel = if (a == null) 0 else items.indexOfFirst { devices[it]?.address == a.address }.coerceAtLeast(0)
        lastItems = items
        lastSel = sel
        listener?.onDevices(items, sel)
        if (a == null) {
            setStatus("Лампа не найдена (проверьте питание, Bluetooth и геолокацию)")
            return
        }
        connect(a)
    }

    private val connTimeout = Runnable {
        if (!ready) {
            closeGatt()
            setStatus("Ошибка BLE: время подключения истекло")
        }
    }

    private fun connect(d: BluetoothDevice) {
        connName = d.name ?: d.address
        setStatus("Подключение к $connName...")
        closeGatt()
        gatt = if (Build.VERSION.SDK_INT >= 23)
            d.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        else d.connectGatt(ctx, false, gattCb)
        main.postDelayed(connTimeout, 15000)
    }

    private fun closeGatt() {
        ready = false
        main.removeCallbacks(connTimeout)
        val g = gatt
        gatt = null
        wChar = null
        rChar = null
        try {
            g?.disconnect()
            g?.close()
        } catch (_: Exception) {
        }
    }

    private fun findChar(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        for (s in g.services) s.getCharacteristic(uuid)?.let { return it }
        return null
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g != gatt) {
                try { g.close() } catch (_: Exception) {}
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val was = ready
                main.removeCallbacks(connTimeout)
                closeGatt()
                setStatus(if (was) "Связь с лампой потеряна" else "Ошибка BLE: не удалось подключиться (код $status)")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g != gatt) return
            main.removeCallbacks(connTimeout)
            val w = findChar(g, WRITE_UUID)
            if (status == BluetoothGatt.GATT_SUCCESS && w != null) {
                wChar = w
                rChar = findChar(g, READ_UUID)
                ready = true
                setStatus("Подключено: $connName")
            } else {
                closeGatt()
                setStatus("Ошибка BLE: сервис лампы iLink не найден")
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            opStatus = status
            latch?.countDown()
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            readVal = c.value
            opStatus = status
            latch?.countDown()
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int
        ) {
            readVal = value
            opStatus = status
            latch?.countDown()
        }
    }

    // ---------- Запись / чтение ----------

    private fun write(pkt: ByteArray, fallback: Boolean): Boolean {
        val g = gatt
        val c = wChar
        if (!ready || g == null || c == null) return false
        val types = if (fallback)
            intArrayOf(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        else intArrayOf(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        for (t in types) {
            c.writeType = t
            c.value = pkt
            val l = CountDownLatch(1)
            latch = l
            opStatus = -1
            if (!g.writeCharacteristic(c)) continue
            if (l.await(2, TimeUnit.SECONDS) && opStatus == BluetoothGatt.GATT_SUCCESS) return true
        }
        return false
    }

    private fun read(): ByteArray? {
        val g = gatt
        val c = rChar
        if (!ready || g == null || c == null) return null
        val l = CountDownLatch(1)
        latch = l
        opStatus = -1
        readVal = null
        if (!g.readCharacteristic(c)) return null
        if (!l.await(2, TimeUnit.SECONDS) || opStatus != BluetoothGatt.GATT_SUCCESS) return null
        return readVal
    }

    private val drain = Runnable {
        val p = synchronized(lock) {
            val x = latest
            latest = null
            scheduled = false
            x
        }
        if (p != null && isLinked) {
            lastTx = SystemClock.elapsedRealtime()
            if (!write(p, true)) setStatus("Связь с лампой потеряна")
        }
    }

    /** Для слайдеров: сохраняется только последний пакет, не чаще 1 раза в 130 мс. */
    fun sendQueued(p: ByteArray) {
        if (!isLinked) return
        synchronized(lock) {
            latest = p
            if (scheduled) return
            scheduled = true
        }
        val wait = (130 - (SystemClock.elapsedRealtime() - lastTx)).coerceAtLeast(0)
        tx.postDelayed(drain, wait)
    }

    /** Для кнопок и режимов: отправка сразу (устаревший пакет слайдера отбрасывается). */
    fun sendDirect(p: ByteArray) {
        if (!isLinked) return
        synchronized(lock) { latest = null }
        tx.post { if (!write(p, true)) setStatus("Связь с лампой потеряна") }
    }

    /** Таймеры/будильники: пакеты по очереди, пауза 300 мс, чтение подтверждения из a041. */
    fun sendSequence(items: List<Pair<Int, IntArray>>, done: (List<Pair<Int, String>>) -> Unit) {
        tx.post {
            val res = ArrayList<Pair<Int, String>>()
            for ((cmd, payload) in items) {
                if (!isLinked) {
                    res.add(cmd to "нет связи")
                    break
                }
                if (!write(buildPacket(cmd, payload), false)) {
                    res.add(cmd to "ошибка записи")
                    break
                }
                SystemClock.sleep(300)
                val reply = read()
                val ok = reply != null && reply.size >= 5 &&
                    reply[0] == 0x55.toByte() && reply[1] == 0xAA.toByte() &&
                    (reply[3].toInt() and 0xFF) == ((cmd shr 8) or 0x80) &&
                    (reply[4].toInt() and 0xFF) == (cmd and 0xFF)
                res.add(cmd to if (ok) "записано, ответ лампы получен" else "записано, ответа нет")
                SystemClock.sleep(300)
            }
            main.post { done(res) }
        }
    }

    fun shutdown() {
        listener = null
        main.removeCallbacksAndMessages(null)
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCb)
        } catch (_: Exception) {
        }
        closeGatt()
        txThread.quitSafely()
    }
}
