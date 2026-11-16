package hu.blazepod.vezerlo

import java.util.UUID

/**
 * BlazePod BLE protokoll.
 * Forrás: github.com/sasodoma/blazepod-hacking (GPL-3.0) visszafejtése.
 */
object BlazeProtocol {
    val UART_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val UART_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    val COLOR_SERVICE: UUID = UUID.fromString("50c97bfa-4cb8-4c84-b745-0e58a0280cd6")
    val COLOR_CHAR: UUID = UUID.fromString("50c912a2-4cb8-4c84-b745-0e58a0280cd6")

    val TAP_SERVICE: UUID = UUID.fromString("50c928bd-4cb8-4c84-b745-0e58a0280cd6")
    val TAP_CHAR: UUID = UUID.fromString("50c9727e-4cb8-4c84-b745-0e58a0280cd6")

    // Szabványos Bluetooth szolgáltatások
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    val DIS_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val DIS_MODEL: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val DIS_SERIAL: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
    val DIS_FW: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")

    /**
     * Becsült cellafeszültség a töltöttségi százalékból (1 cellás Li-ion görbe).
     * A bólya maga csak százalékot küld, ezért ez közelítés.
     */
    fun estimateVoltage(percent: Int): Double {
        val pts = listOf(
            0 to 3.30, 5 to 3.45, 10 to 3.68, 20 to 3.74, 30 to 3.77, 40 to 3.79,
            50 to 3.82, 60 to 3.87, 70 to 3.92, 80 to 3.98, 90 to 4.06, 100 to 4.20
        )
        val p = percent.coerceIn(0, 100)
        for (i in 1 until pts.size) {
            val (p1, v1) = pts[i]
            val (p0, v0) = pts[i - 1]
            if (p <= p1) return v0 + (v1 - v0) * (p - p0) / (p1 - p0)
        }
        return 4.20
    }

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** A hirdetésben (advertisement) látható gyártói azonosító: D7-09 → 0x09D7 */
    const val COMPANY_ID = 0x09D7

    private const val MASK = 0xFFFFFFFFL

    /**
     * 7 bájtos hitelesítő csomag: "sea" + 4 bájt számított érték.
     * [mfr] a gyártóspecifikus adat a 2 bájtos cégazonosító NÉLKÜL.
     * Ellenőrzés: ...01 DE AD BE EF → 73 65 61 03 48 60 B6
     */
    fun authPayload(mfr: ByteArray): ByteArray {
        require(mfr.size >= 5) { "Túl rövid gyártói adat" }
        val tail = mfr.copyOfRange(mfr.size - 5, mfr.size)
        val offset = tail[0].toInt() and 0xFF
        val poly = (0xEDB88321L + (offset % 50)) and MASK

        var crc = 0xFFFFFFFFL
        for (i in 1..4) {
            crc = crc xor (tail[i].toLong() and 0xFFL)
            repeat(8) {
                crc = if ((crc and 1L) != 0L) ((crc ushr 1) xor poly) and MASK else (crc ushr 1)
            }
        }
        crc = crc xor MASK
        crc = (crc + (crc shl 3)) and MASK
        crc = (crc xor (crc ushr 11)) and MASK
        crc = (crc + (crc shl 15)) and MASK

        return byteArrayOf(
            0x73, 0x65, 0x61,
            (crc and 0xFF).toByte(),
            ((crc ushr 8) and 0xFF).toByte(),
            ((crc ushr 16) and 0xFF).toByte(),
            ((crc ushr 24) and 0xFF).toByte()
        )
    }

    /** Szín: a bólya G, B, R sorrendet vár. offOnTap = érintésre magától kialszik. */
    fun encodeColor(argb: Int, offOnTap: Boolean = false): ByteArray {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return if (offOnTap) byteArrayOf(g.toByte(), b.toByte(), r.toByte(), 0x01)
        else byteArrayOf(g.toByte(), b.toByte(), r.toByte())
    }

    val OFF: ByteArray = byteArrayOf(0, 0, 0)

    /** Érintés-értesítés: [állapot, ms LE x4, ?, ?, ?] */
    data class TapEvent(val state: Int, val elapsedMs: Long) {
        /** 0x21 = világított és égve maradt, 0x25 = világított és kialudt */
        val hitLit: Boolean get() = state == 0x21 || state == 0x25
    }

    fun decodeTap(data: ByteArray): TapEvent? {
        if (data.size < 5) return null
        val ms = (data[1].toLong() and 0xFF) or
                ((data[2].toLong() and 0xFF) shl 8) or
                ((data[3].toLong() and 0xFF) shl 16) or
                ((data[4].toLong() and 0xFF) shl 24)
        return TapEvent(data[0].toInt() and 0xFF, ms)
    }
}
