@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package hu.blazepod.vezerlo

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val perms = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF2EA8FF))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var granted by remember {
                        mutableStateOf(perms.all {
                            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
                        })
                    }
                    val permLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { res -> granted = res.values.all { it } }
                    val btLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) { if (vm.btEnabled()) vm.scanAndConnect() }

                    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(perms) }

                    if (!granted) {
                        Column(
                            Modifier.fillMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("A bólyák eléréséhez Bluetooth-engedély kell.", textAlign = TextAlign.Center)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { permLauncher.launch(perms) }) { Text("Engedélyezés") }
                        }
                    } else {
                        App(vm) {
                            if (vm.btEnabled()) vm.scanAndConnect()
                            else btLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun App(vm: MainViewModel, onScan: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("BlazePod Vezérlő") })
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                listOf("Bólyák", "Edzés", "Teszt").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (tab) {
                    0 -> PodsTab(vm, onScan)
                    1 -> DrillTab(vm)
                    else -> TestTab(vm)
                }
            }
        }
    }
}

// ======================= Bólyák =======================

@Composable
fun PodsTab(vm: MainViewModel, onScan: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = vm.podsVersion // újrarajzolás állapotváltozáskor

    SectionCard("Bólyák száma") {
        Stepper(
            value = "${vm.targetCount} db",
            onMinus = { if (vm.targetCount > 1) vm.targetCount-- },
            onPlus = { if (vm.targetCount < 12) vm.targetCount++ }
        )
        Text(
            "Az edzés és a teszt az első ${vm.targetCount} csatlakoztatott bólyát használja.",
            style = MaterialTheme.typography.bodySmall
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onScan, enabled = !vm.busy, modifier = Modifier.weight(1f)) {
            Text(if (vm.busy) "Folyamatban…" else "Keresés és csatlakozás")
        }
        OutlinedButton(onClick = { vm.disconnectAll() }) { Text("Leválaszt") }
    }
    if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(vm.status, style = MaterialTheme.typography.bodyMedium)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Csatlakoztatott bólyák (${vm.readyPods().size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { vm.refreshAll() }, enabled = vm.readyPods().isNotEmpty() && !vm.busy) {
            Text("Frissítés")
        }
    }
    if (vm.pods.isEmpty()) Text("Még nincs csatlakoztatott bólya.", style = MaterialTheme.typography.bodySmall)
    vm.pods.forEach { p -> PodCard(vm, p) }

    vm.diagTitle?.let { title ->
        AlertDialog(
            onDismissRequest = { vm.closeDiagnostics() },
            confirmButton = { TextButton(onClick = { vm.closeDiagnostics() }) { Text("Bezár") } },
            title = { Text("Diagnosztika – $title") },
            text = {
                Text(
                    vm.diagText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())
                )
            }
        )
    }
}

@Composable
fun PodCard(vm: MainViewModel, p: Pod) {
    val (stateText, stateColor) = when (p.state) {
        Pod.State.READY -> "Csatlakozva" to Color(0xFF66BB6A)
        Pod.State.CONNECTING -> "Csatlakozás…" to Color(0xFFFFCA28)
        Pod.State.FAILED -> "Sikertelen" to Color(0xFFEF5350)
        Pod.State.DISCONNECTED -> "Megszakadt" to Color(0xFFEF5350)
    }
    val lit = vm.mode == Mode.DRILL && vm.litPodIndex == p.index
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NumberBadge(p.index, if (p.state == Pod.State.READY) Color(0xFF2EA8FF) else Color.Gray, 48)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(stateColor))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stateText + if (lit) " • világít" else "",
                            color = stateColor, style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                BatteryView(p.battery)
            }
            Text(
                buildString {
                    append("MAC: ").append(p.address)
                    p.serial?.let { append("\nSorozatszám: ").append(it) }
                    p.model?.let { append("\nModell: ").append(it) }
                    p.firmware?.let { append("   FW: ").append(it) }
                    p.rssi?.let { append("\nJelerősség: ").append(it).append(" dBm") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB0B0B0)
            )
            Row {
                TextButton(
                    onClick = { vm.identifyOne(p) },
                    enabled = p.state == Pod.State.READY && vm.mode == Mode.IDLE
                ) { Text("Villant") }
                TextButton(
                    onClick = { vm.showDiagnostics(p) },
                    enabled = p.state == Pod.State.READY && vm.mode != Mode.DRILL
                ) { Text("Diagnosztika") }
            }
        }
    }
}

@Composable
fun BatteryView(level: Int?) {
    Column(horizontalAlignment = Alignment.End) {
        if (level == null) {
            Text("Akku: –", style = MaterialTheme.typography.bodySmall)
        } else {
            val c = when {
                level >= 50 -> Color(0xFF66BB6A)
                level >= 20 -> Color(0xFFFFCA28)
                else -> Color(0xFFEF5350)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(width = 38.dp, height = 18.dp)
                        .border(2.dp, c, RoundedCornerShape(3.dp)).padding(3.dp)
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(level / 100f).background(c))
                }
                Box(Modifier.size(width = 3.dp, height = 8.dp).background(c))
            }
            Text("$level %", fontWeight = FontWeight.Bold, color = c)
            Text(
                "≈ %.2f V".format(BlazeProtocol.estimateVoltage(level)),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// ======================= Edzés =======================

@Composable
fun DrillTab(vm: MainViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.podsVersion
    val running = vm.mode == Mode.DRILL

    if (running || vm.drillFinished) {
        DrillStats(vm, running)
    }

    if (!running) {
        SectionCard("Időtartam") {
            Stepper(
                value = formatTime(vm.durationSec * 1000L),
                onMinus = { vm.durationSec = (vm.durationSec - 15).coerceAtLeast(15) },
                onPlus = { vm.durationSec = (vm.durationSec + 15).coerceAtMost(30 * 60) }
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 120, 180, 300).forEach { s ->
                    FilterChip(
                        selected = vm.durationSec == s,
                        onClick = { vm.durationSec = s },
                        label = { Text(if (s < 60) "$s s" else "${s / 60} p") }
                    )
                }
            }
        }
        SectionCard("Szünet két felvillanás között") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PAUSES.forEachIndexed { i, p ->
                    FilterChip(selected = vm.pauseIndex == i, onClick = { vm.pauseIndex = i }, label = { Text(p.label) })
                }
            }
        }
        SectionCard("Egy felvillanás legfeljebb") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LIGHT_LIMITS.forEachIndexed { i, ms ->
                    FilterChip(
                        selected = vm.lightLimitIndex == i,
                        onClick = { vm.lightLimitIndex = i },
                        label = { Text(if (ms == 0L) "Amíg meg nem érinted" else "${ms / 1000} s") }
                    )
                }
            }
        }
        SectionCard("Szín") {
            ColorPicker(vm)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = vm.randomColor, onCheckedChange = { vm.randomColor = it })
                Spacer(Modifier.width(8.dp))
                Text("Véletlen szín minden felvillanásnál")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = vm.sound, onCheckedChange = { vm.sound = it })
            Spacer(Modifier.width(8.dp))
            Text("Hangjelzés")
        }
    }

    val active = vm.activePods().size
    Button(
        onClick = { if (running) vm.stopMode() else vm.startDrill() },
        enabled = running || (active > 0 && vm.mode == Mode.IDLE && !vm.busy),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (running) Color(0xFFD32F2F) else Color(0xFF2E7D32)
        ),
        modifier = Modifier.fillMaxWidth().height(64.dp)
    ) {
        Text(if (running) "LEÁLLÍTÁS" else "START  ($active bólya)", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
    if (active == 0) Text("Előbb csatlakoztass bólyákat a Bólyák fülön.", style = MaterialTheme.typography.bodySmall)
}

@Composable
fun DrillStats(vm: MainViewModel, running: Boolean) {
    val r = vm.reactions
    SectionCard(if (running) "Edzés folyamatban" else "Eredmény") {
        if (running && vm.countdown > 0) {
            Text(
                "${vm.countdown}", fontSize = 96.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
            )
        } else if (running) {
            Text(
                formatTime(vm.remainingMs), fontSize = 64.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
            )
            Text(
                if (vm.litPodIndex > 0) "Világít: #${vm.litPodIndex}" else " ",
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("Találat", "${r.size}", Color(0xFF66BB6A))
            Stat("Kihagyott", "${vm.misses}", Color(0xFFFFA726))
            Stat("Téves", "${vm.wrongTaps}", Color(0xFFEF5350))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("Utolsó", r.lastOrNull()?.let { "$it ms" } ?: "–")
            Stat("Átlag", if (r.isEmpty()) "–" else "${r.average().toLong()} ms")
            Stat("Legjobb", r.minOrNull()?.let { "$it ms" } ?: "–")
            Stat("Leglassabb", r.maxOrNull()?.let { "$it ms" } ?: "–")
        }
    }
}

// ======================= Teszt =======================

@Composable
fun TestTab(vm: MainViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.podsVersion
    val list = vm.activePods()
    val idle = vm.mode == Mode.IDLE

    SectionCard("Szín") { ColorPicker(vm) }

    SectionCard("Teszt üzemmódok") {
        Button(onClick = { vm.startTouchTest() }, enabled = idle && list.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("Érintésteszt – mind világít, érintésre kialszik")
        }
        Button(onClick = { vm.startIdentify() }, enabled = idle && list.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("Azonosítás – sorban felvillannak (#1, #2…)")
        }
        Button(onClick = { vm.startColorCycle() }, enabled = idle && list.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("Színkör – minden színt végigjátszik")
        }
        OutlinedButton(onClick = { vm.stopMode() }, enabled = !idle, modifier = Modifier.fillMaxWidth()) {
            Text("Leállítás / mind kikapcsol")
        }
    }

    if (list.isEmpty()) {
        Text("Előbb csatlakoztass bólyákat a Bólyák fülön.", style = MaterialTheme.typography.bodySmall)
        return
    }

    SectionCard(
        when (vm.mode) {
            Mode.TOUCH_TEST -> "Érintsd meg a világító bólyákat! (${vm.tiles.values.count { it == Tile.OK }}/${list.size})"
            Mode.IDENTIFY -> "Most világít: #${vm.identifyIndex}"
            Mode.COLOR_CYCLE -> "Színkör fut…"
            else -> "Bólyák"
        }
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            list.forEach { p ->
                val t = vm.tiles[p.address]
                val bg = when {
                    vm.mode == Mode.IDENTIFY && vm.identifyIndex == p.index -> Color(vm.selectedColor)
                    vm.mode == Mode.TOUCH_TEST && t == Tile.OK -> Color(0xFF2E7D32)
                    vm.mode == Mode.TOUCH_TEST && t == Tile.LIT -> Color(vm.selectedColor)
                    else -> Color(0xFF333333)
                }
                Box(
                    Modifier.size(78.dp).clip(RoundedCornerShape(16.dp)).background(bg),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("#${p.index}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        if (vm.mode == Mode.TOUCH_TEST && t == Tile.OK) {
                            Text("✓ ${vm.tileMs[p.address] ?: 0} ms", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

// ======================= Közös elemek =======================

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = onMinus) { Text("−", fontSize = 22.sp) }
        Text(
            value, fontSize = 28.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f), textAlign = TextAlign.Center
        )
        FilledTonalButton(onClick = onPlus) { Text("+", fontSize = 22.sp) }
    }
}

@Composable
fun ColorPicker(vm: MainViewModel) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PALETTE.forEachIndexed { i, c ->
            val sel = vm.colorIndex == i
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(c.argb))
                    .border(if (sel) 4.dp else 1.dp, if (sel) Color.White else Color.DarkGray, CircleShape)
                    .clickable { vm.colorIndex = i }
            )
        }
    }
    Text("Kiválasztva: ${PALETTE[vm.colorIndex].name}", style = MaterialTheme.typography.bodySmall)
}

@Composable
fun NumberBadge(n: Int, color: Color, sizeDp: Int = 36) {
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text("$n", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (sizeDp / 2).sp)
    }
}

@Composable
fun Stat(label: String, value: String, color: Color = Color.Unspecified) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

fun formatTime(ms: Long): String {
    val s = (ms + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
