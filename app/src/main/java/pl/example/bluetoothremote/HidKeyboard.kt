package pl.example.bluetoothremote

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice

class HidKeyboard(private val hid: BluetoothHidDevice, private val device: BluetoothDevice) {
    companion object {
        const val REPORT_KEYBOARD = 1
        const val REPORT_CONSUMER = 2

        val REPORT_DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x85.toByte(), REPORT_KEYBOARD.toByte(),
            0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02, 0x95.toByte(), 0x01,
            0x75, 0x08, 0x81.toByte(), 0x01, 0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00,
            0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00, 0xC0.toByte(),
            0x05, 0x0C, 0x09, 0x01, 0xA1.toByte(), 0x01, 0x85.toByte(), REPORT_CONSUMER.toByte(),
            0x15, 0x00, 0x26, 0xFF.toByte(), 0x03, 0x19, 0x00, 0x2A, 0xFF.toByte(), 0x03,
            0x75, 0x10, 0x95.toByte(), 0x01, 0x81.toByte(), 0x00, 0xC0.toByte()
        )
    }

    fun key(keyCode: Int, modifier: Int = 0) {
        hid.sendReport(device, REPORT_KEYBOARD, byteArrayOf(modifier.toByte(), 0, keyCode.toByte(), 0, 0, 0, 0, 0))
        hid.sendReport(device, REPORT_KEYBOARD, ByteArray(8))
    }

    fun consumer(usage: Int) {
        hid.sendReport(device, REPORT_CONSUMER, byteArrayOf((usage and 0xff).toByte(), (usage shr 8).toByte()))
        hid.sendReport(device, REPORT_CONSUMER, byteArrayOf(0, 0))
    }

    fun text(value: String): Int {
        var skipped = 0
        value.forEach { char ->
            val stroke = usStroke(char)
            if (stroke == null) skipped++ else key(stroke.second, stroke.first)
        }
        return skipped
    }

    private fun usStroke(c: Char): Pair<Int, Int>? {
        val shift = 0x02
        if (c in 'a'..'z') return 0 to (0x04 + c.code - 'a'.code)
        if (c in 'A'..'Z') return shift to (0x04 + c.code - 'A'.code)
        if (c in '1'..'9') return 0 to (0x1E + c.code - '1'.code)
        return when (c) {
            '0' -> 0 to 0x27; '\n' -> 0 to 0x28; ' ' -> 0 to 0x2C
            '-' -> 0 to 0x2D; '_' -> shift to 0x2D; '=' -> 0 to 0x2E; '+' -> shift to 0x2E
            '[' -> 0 to 0x2F; '{' -> shift to 0x2F; ']' -> 0 to 0x30; '}' -> shift to 0x30
            '\\' -> 0 to 0x31; ';' -> 0 to 0x33; ':' -> shift to 0x33
            '\'' -> 0 to 0x34; '"' -> shift to 0x34; '`' -> 0 to 0x35; '~' -> shift to 0x35
            ',' -> 0 to 0x36; '<' -> shift to 0x36; '.' -> 0 to 0x37; '>' -> shift to 0x37
            '/' -> 0 to 0x38; '?' -> shift to 0x38
            '!' -> shift to 0x1E; '@' -> shift to 0x1F; '#' -> shift to 0x20; '$' -> shift to 0x21
            '%' -> shift to 0x22; '^' -> shift to 0x23; '&' -> shift to 0x24; '*' -> shift to 0x25
            '(' -> shift to 0x26; ')' -> shift to 0x27
            else -> null
        }
    }
}
