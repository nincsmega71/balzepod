package hu.blazepod.vezerlo

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

data class PodColor(val name: String, val argb: Int)

val PALETTE = listOf(
    PodColor("Piros", 0xFFFF0000.toInt()),
    PodColor("Zöld", 0xFF00FF00.toInt()),
    PodColor("Kék", 0xFF0000FF.toInt()),
    PodColor("Sárga", 0xFFFFC800.toInt()),
    PodColor("Narancs", 0xFFFF5000.toInt()),
    PodColor("Lila", 0xFFA000FF.toInt()),
    PodColor("Cián", 0xFF00FFFF.toInt()),
    PodColor("Rózsaszín", 0xFFFF1070.toInt()),
    PodColor("Fehér", 0xFFFFFFFF.toInt()),
)

const val GREEN = 0xFF00FF00.toInt()
const val RED = 0xFFFF0000.toInt()
const val WHITE = 0xFFFFFFFF.toInt()

enum class Mode { IDLE, DRILL, TOUCH_TEST, COLOR_CYCLE, IDENTIFY }
enum class Tile { IDLE, LIT, OK }

/** Szünet két felvillanás között (ms) */
data class Pause(val label: String, val min: Long, val max: Long)

val PAUSES = listOf(
    Pause("Azonnal", 100, 300),
    Pause("0,5–1,5 s", 500, 1500),
    Pause("1–3 s", 1000, 3000),
    Pause("2–5 s", 2000, 5000),
)

/** Legfeljebb ennyi ideig világít egy bólya (0 = amíg meg nem érintik) */
val LIGHT_LIMITS = listOf(0L, 1000L, 2000L, 3000L, 5000L)

private data class Found(
    val device: android.bluetooth.BluetoothDevice,
    val mfr: ByteArray,
    val name: String,
    val rssi: Int
)

@SuppressLint("MissingPermission")
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()
    private val btManager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = btManager.adapter

    // ---- Bólyák ----
    val pods = mutableStateListOf<Pod>()
    var podsVersion by mutableIntStateOf(0)
        private set
    var targetCount by mutableIntStateOf(4)
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("Kapcsold be a bólyákat, majd nyomd meg a Keresés gombot.")
        private set
    var foundCount by mutableIntStateOf(0)
        private set

    // ---- Beállítások ----
    var durationSec by mutableIntStateOf(60)
    var pauseIndex by mutableIntStateOf(1)
    var lightLimitIndex by mutableIntStateOf(0)
    var colorIndex by mutableIntStateOf(0)
    var randomColor by mutableStateOf(false)
    var sound by mutableStateOf(true)

    // ---- Futás ----
    var mode by mutableStateOf(Mode.IDLE)
        private set
    var countdown by mutableIntStateOf(0)
        private set
    var remainingMs by mutableLongStateOf(0L)
        private set
    val reactions = mutableStateListOf<Long>()
    var misses by mutableIntStateOf(0)
        private set
    var wrongTaps by mutableIntStateOf(0)
        private set
    var drillFinished by mutableStateOf(false)
        private set
    var litPodIndex by mutableIntStateOf(0)
        private set

    // ---- Teszt ----
    val tiles = mutableStateMapOf<String, Tile>()
    val tileMs = mutableStateMapOf<String, Long>()
    var identifyIndex by mutableIntStateOf(0)
        private set

    // ---- Diagnosztika ----
    var diagTitle by mutableStateOf<String?>(null)
        private set
    var diagText by mutableStateOf("")
        private set

    private var job: Job? = null
    private var connectJob: Job? = null
    private val taps = Channel<Pair<Pod, BlazeProtocol.TapEvent>>(Channel.UNLIMITED)
    private val tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 90) } catch (_: Exception) { null }

    init {
        // Akku és jelerősség frissítése 30 mp-enként (edzés közben nem)
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                if (mode != Mode.DRILL && !busy) readyPods().forEach { it.refreshInfo() }
            }
        }
    }

    fun refreshAll() {
        if (busy) return
        viewModelScope.launch { readyPods().forEach { it.refreshInfo() } }
    }

    fun showDiagnostics(pod: Pod) {
        if (mode == Mode.DRILL) return
        diagTitle = "#${pod.index} ${pod.name}"
        diagText = "Kiolvasás…"
        viewModelScope.launch { diagText = pod.dumpGatt() }
    }

    fun closeDiagnostics() { diagTitle = null }

    fun btEnabled(): Boolean = adapter?.isEnabled == true
    fun readyPods(): List<Pod> = pods.filter { it.state == Pod.State.READY }
    fun activePods(): List<Pod> = readyPods().take(targetCount)
    val selectedColor: Int get() = PALETTE[colorIndex].argb

    private fun beep(type: Int = ToneGenerator.TONE_PROP_BEEP, ms: Int = 120) {
        if (sound) tone?.startTone(type, ms)
    }

    private fun bump() {
        viewModelScope.launch(Dispatchers.Main) { podsVersion++ }
    }

    // ================= Keresés és csatlakozás =================

    fun scanAndConnect() {
        if (busy) return
        val ad = adapter
        if (ad == null || !ad.isEnabled) {
            status = "A Bluetooth ki van kapcsolva."
            return
        }
        stopMode()
        connectJob = viewModelScope.launch {
            busy = true
            try {
                // a megszakadt / hibás kapcsolatokat eltávolítjuk
                pods.filter { it.state != Pod.State.READY }.forEach { it.disconnect(); pods.remove(it) }
                val need = targetCount - pods.size
                if (need <= 0) {
                    status = "Már ${pods.size} bólya csatlakozik."
                    return@launch
                }
                val already = pods.map { it.address }.toSet()
                val found = ConcurrentHashMap<String, Found>()
                foundCount = 0
                status = "Keresés…"

                val scanner = ad.bluetoothLeScanner
                if (scanner == null) {
                    status = "A BLE keresés nem elérhető."
                    return@launch
                }
                val cb = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, r: ScanResult) {
                        handleScan(r, found, already)
                    }
                    override fun onBatchScanResults(results: MutableList<ScanResult>) {
                        results.forEach { handleScan(it, found, already) }
                    }
                }
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                scanner.startScan(null, settings, cb)
                val start = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - start < 8000) {
                    delay(250)
                    foundCount = found.size
                    status = "Keresés… talált: ${found.size}"
                    if (found.size >= need && SystemClock.elapsedRealtime() - start > 2500) break
                }
                try { scanner.stopScan(cb) } catch (_: Exception) {}

                val candidates = found.values.sortedByDescending { it.rssi }.take(need)
                if (candidates.isEmpty()) {
                    status = "Nem találtam bólyát. Kapcsold be őket (nyomd meg egyszer a tetejüket), és próbáld újra."
                    return@launch
                }
                var okCount = 0
                for (c in candidates) {
                    status = "Csatlakozás: ${c.name} (${okCount + 1}/${candidates.size})…"
                    val pod = Pod(ctx, c.device, c.mfr, c.name, ::onTap, ::bump)
                    pods.add(pod)
                    renumber()
                    if (pod.connect()) {
                        okCount++
                        renumber()
                        flash(pod, GREEN, 2)
                        pod.refreshInfo()
                    } else {
                        pods.remove(pod)
                        renumber()
                    }
                }
                status = "Kész: ${readyPods().size} bólya csatlakozva" +
                        if (okCount < candidates.size) " (${candidates.size - okCount} sikertelen)" else ""
            } finally {
                busy = false
                bump()
            }
        }
    }

    private fun handleScan(r: ScanResult, found: ConcurrentHashMap<String, Found>, skip: Set<String>) {
        val addr = r.device.address
        if (addr in skip) return
        val rec = r.scanRecord ?: return
        val name = rec.deviceName ?: (try { r.device.name } catch (_: SecurityException) { null }) ?: ""
        val msd = rec.manufacturerSpecificData ?: return
        var data: ByteArray? = msd.get(BlazeProtocol.COMPANY_ID)
        val lname = name.lowercase()
        val nameOk = lname.startsWith("blazepod") || lname.startsWith("bp")
        if (data == null && nameOk) {
            for (i in 0 until msd.size()) {
                val v = msd.valueAt(i)
                if (v != null && v.size >= 5) { data = v; break }
            }
        }
        if (data == null || data.size < 5) return
        found[addr] = Found(r.device, data, name.ifEmpty { "BlazePod" }, r.rssi)
    }

    private fun renumber() {
        pods.forEachIndexed { i, p -> p.index = i + 1 }
        podsVersion++
    }

    fun disconnectAll() {
        stopMode()
        connectJob?.cancel()
        pods.forEach { it.disconnect() }
        pods.clear()
        tiles.clear()
        status = "Minden bólya leválasztva."
        podsVersion++
    }

    fun identifyOne(pod: Pod) {
        if (mode != Mode.IDLE) return
        viewModelScope.launch { flash(pod, WHITE, 3) }
    }

    // ================= Közös segédek =================

    private suspend fun flash(pod: Pod, color: Int, times: Int, onMs: Long = 250, offMs: Long = 200) {
        repeat(times) { i ->
            pod.setColor(color)
            delay(onMs)
            pod.off()
            if (i < times - 1) delay(offMs)
        }
    }

    private suspend fun allColor(list: List<Pod>, color: Int, offOnTap: Boolean = false) {
        list.map { p -> viewModelScope.launch { p.setColor(color, offOnTap) } }.joinAll()
    }

    private suspend fun allOff(list: List<Pod>) = allColor(list, 0)

    private fun onTap(pod: Pod, ev: BlazeProtocol.TapEvent) {
        viewModelScope.launch(Dispatchers.Main) {
            when (mode) {
                Mode.DRILL -> taps.trySend(pod to ev)
                Mode.TOUCH_TEST -> {
                    if (ev.hitLit) {
                        tiles[pod.address] = Tile.OK
                        tileMs[pod.address] = ev.elapsedMs
                        beep()
                        flash(pod, GREEN, 2)
                    } else {
                        // kialudt bólyát érintettek: jelezzük, hogy az érzékelő működik
                        beep(ToneGenerator.TONE_PROP_ACK, 80)
                        flash(pod, GREEN, 1, 150)
                    }
                }
                else -> {}
            }
        }
    }

    fun stopMode() {
        job?.cancel()
        job = null
    }

    private fun runMode(m: Mode, block: suspend () -> Unit) {
        if (busy) return
        stopMode()
        val list = activePods()
        if (list.isEmpty()) {
            status = "Nincs csatlakoztatott bólya."
            return
        }
        job = viewModelScope.launch {
            mode = m
            try {
                block()
            } finally {
                withContext(NonCancellable) {
                    allOff(list)
                    mode = Mode.IDLE
                    countdown = 0
                    identifyIndex = 0
                    litPodIndex = 0
                }
            }
        }
    }

    // ================= Edzés: véletlen felvillanás =================

    fun startDrill() = runMode(Mode.DRILL) {
        val list = activePods()
        reactions.clear(); misses = 0; wrongTaps = 0; drillFinished = false
        val pause = PAUSES[pauseIndex]
        val limit = LIGHT_LIMITS[lightLimitIndex]
        remainingMs = durationSec * 1000L

        // visszaszámlálás: 3-2-1, minden bólya pirosan villan
        for (n in 3 downTo 1) {
            countdown = n
            beep()
            allColor(list, RED)
            delay(300)
            allOff(list)
            delay(700)
        }
        countdown = 0
        beep(ToneGenerator.TONE_PROP_BEEP2, 300)

        val endAt = SystemClock.elapsedRealtime() + durationSec * 1000L
        val ticker = viewModelScope.launch {
            while (isActive) {
                remainingMs = (endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                delay(100)
            }
        }
        try {
            var last: Pod? = null
            while (SystemClock.elapsedRealtime() < endAt) {
                val pool = if (list.size > 1) list.filter { it !== last } else list
                val pod = pool.random()
                last = pod
                val color = if (randomColor) PALETTE.random().argb else selectedColor

                while (taps.tryReceive().isSuccess) { /* régi érintések eldobása */ }
                pod.setColor(color, offOnTap = true)
                litPodIndex = pod.index

                val left = endAt - SystemClock.elapsedRealtime()
                val wait = if (limit > 0) minOf(limit, left) else left
                val hit: BlazeProtocol.TapEvent? = withTimeoutOrNull(wait.coerceAtLeast(1)) {
                    var res: BlazeProtocol.TapEvent? = null
                    while (res == null) {
                        val (p, ev) = taps.receive()
                        if (p === pod && ev.hitLit) res = ev
                        else if (p !== pod) wrongTaps++
                    }
                    res
                }
                litPodIndex = 0
                if (hit != null) {
                    reactions.add(hit.elapsedMs)
                    beep(ToneGenerator.TONE_PROP_BEEP, 60)
                    if (hit.state == 0x21) pod.off()
                } else {
                    pod.off()
                    if (SystemClock.elapsedRealtime() < endAt) misses++
                }
                val now = SystemClock.elapsedRealtime()
                if (now >= endAt) break
                val p = Random.nextLong(pause.min, pause.max + 1)
                delay(minOf(p, endAt - now))
            }
        } finally {
            ticker.cancel()
            remainingMs = 0
        }
        drillFinished = true
        beep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
        repeat(2) {
            allColor(list, GREEN); delay(300); allOff(list); delay(200)
        }
    }

    // ================= Teszt üzemmódok =================

    fun startTouchTest() = runMode(Mode.TOUCH_TEST) {
        val list = activePods()
        tiles.clear(); tileMs.clear()
        list.forEach { tiles[it.address] = Tile.LIT }
        allColor(list, selectedColor, offOnTap = true)
        while (true) delay(1000) // a Leállítás gombig fut
    }

    fun startColorCycle() = runMode(Mode.COLOR_CYCLE) {
        val list = activePods()
        while (true) {
            for (c in PALETTE) {
                allColor(list, c.argb)
                delay(700)
            }
        }
    }

    fun startIdentify() = runMode(Mode.IDENTIFY) {
        val list = activePods()
        for (p in list) {
            identifyIndex = p.index
            p.setColor(selectedColor)
            beep(ToneGenerator.TONE_PROP_BEEP, 80)
            delay(1200)
            p.off()
            delay(200)
        }
    }

    override fun onCleared() {
        stopMode()
        pods.forEach { it.disconnect() }
        tone?.release()
        super.onCleared()
    }
}
