package hu.blazepod.vezerlo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Egy BlazePod bólya BLE kapcsolata. A GATT műveletek sorban, egyenként futnak. */
@SuppressLint("MissingPermission")
class Pod(
    private val ctx: Context,
    val device: BluetoothDevice,
    private val mfr: ByteArray,
    val name: String,
    private val onTap: (Pod, BlazeProtocol.TapEvent) -> Unit,
    private val onChange: () -> Unit
) {
    enum class State { DISCONNECTED, CONNECTING, READY, FAILED }

    @Volatile var state: State = State.DISCONNECTED
        private set
    var index: Int = 0
    val address: String get() = device.address

    private var gatt: BluetoothGatt? = null
    private var colorChar: BluetoothGattCharacteristic? = null
    private val opMutex = Mutex()

    @Volatile private var pending: CompletableDeferred<Int>? = null
    @Volatile private var connectDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var servicesDeferred: CompletableDeferred<Boolean>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectDeferred?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectDeferred?.complete(false)
                servicesDeferred?.complete(false)
                pending?.complete(-1)
                try { g.close() } catch (_: Exception) {}
                if (gatt === g) gatt = null
                if (state == State.READY) {
                    state = State.DISCONNECTED
                    onChange()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status)
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(c, value)
        }

        // Android 12 és régebbi
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) handleNotify(c, c.value ?: return)
        }
    }

    private fun handleNotify(c: BluetoothGattCharacteristic, value: ByteArray) {
        if (c.uuid != BlazeProtocol.TAP_CHAR) return
        val ev = BlazeProtocol.decodeTap(value) ?: return
        onTap(this, ev)
    }

    /** Csatlakozás + hitelesítés + érintés-értesítés feliratkozás. */
    suspend fun connect(): Boolean {
        state = State.CONNECTING
        onChange()
        val ok = withTimeoutOrNull(15_000) { doConnect() } ?: false
        if (ok) {
            state = State.READY
        } else {
            state = State.FAILED
            closeGatt()
        }
        onChange()
        return ok
    }

    private suspend fun doConnect(): Boolean {
        val cd = CompletableDeferred<Boolean>()
        connectDeferred = cd
        val g = withContext(Dispatchers.Main) {
            device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        } ?: return false
        gatt = g
        if (!cd.await()) return false
        delay(300)

        val sd = CompletableDeferred<Boolean>()
        servicesDeferred = sd
        if (!g.discoverServices()) return false
        if (!sd.await()) return false

        // 1) Hitelesítés
        val uart = g.getService(BlazeProtocol.UART_SERVICE)?.getCharacteristic(BlazeProtocol.UART_RX)
            ?: return false
        val authType = if (uart.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (!write(uart, BlazeProtocol.authPayload(mfr), authType)) return false
        delay(150)

        // 2) Érintés-értesítések
        val tap = g.getService(BlazeProtocol.TAP_SERVICE)?.getCharacteristic(BlazeProtocol.TAP_CHAR)
            ?: return false
        if (!g.setCharacteristicNotification(tap, true)) return false
        val cccd = tap.getDescriptor(BlazeProtocol.CCCD) ?: return false
        if (!writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) return false

        // 3) Szín karakterisztika
        colorChar = g.getService(BlazeProtocol.COLOR_SERVICE)?.getCharacteristic(BlazeProtocol.COLOR_CHAR)
            ?: return false
        return true
    }

    suspend fun setColor(argb: Int, offOnTap: Boolean = false): Boolean {
        val c = colorChar ?: return false
        if (state != State.READY) return false
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return write(c, BlazeProtocol.encodeColor(argb, offOnTap), type)
    }

    suspend fun off(): Boolean = setColor(0)

    @Suppress("DEPRECATION")
    private suspend fun write(c: BluetoothGattCharacteristic, data: ByteArray, type: Int): Boolean =
        opMutex.withLock {
            val g = gatt ?: return@withLock false
            repeat(4) {
                val d = CompletableDeferred<Int>()
                pending = d
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
                } else {
                    c.writeType = type
                    c.value = data
                    g.writeCharacteristic(c)
                }
                if (started) {
                    val st = withTimeoutOrNull(2000) { d.await() }
                    pending = null
                    // írás válasz nélkül: ha nincs visszajelzés, akkor is sikeresnek vesszük
                    return@withLock st == BluetoothGatt.GATT_SUCCESS ||
                            (st == null && type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                }
                pending = null
                delay(30) // a stack foglalt, újrapróbáljuk
            }
            false
        }

    @Suppress("DEPRECATION")
    private suspend fun writeDescriptor(d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        opMutex.withLock {
            val g = gatt ?: return@withLock false
            val def = CompletableDeferred<Int>()
            pending = def
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            } else {
                d.value = value
                g.writeDescriptor(d)
            }
            if (!started) { pending = null; return@withLock false }
            val st = withTimeoutOrNull(3000) { def.await() }
            pending = null
            st == BluetoothGatt.GATT_SUCCESS
        }

    fun disconnect() {
        closeGatt()
        state = State.DISCONNECTED
        onChange()
    }

    private fun closeGatt() {
        val g = gatt
        gatt = null
        colorChar = null
        try { g?.disconnect() } catch (_: Exception) {}
        try { g?.close() } catch (_: Exception) {}
    }
}
