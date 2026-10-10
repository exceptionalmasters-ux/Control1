package pl.example.bluetoothremote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val bluetoothAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private var hidDevice: BluetoothHidDevice? = null
    private var selectedDevice: BluetoothDevice? = null
    private var keyboard: HidKeyboard? = null
    private var bondedDevices = emptyList<BluetoothDevice>()

    private lateinit var status: TextView
    private lateinit var spinner: Spinner

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startHid() else setStatus("Brak zgody na połączenia Bluetooth")
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            hidDevice = proxy as BluetoothHidDevice
            registerHidApp()
        }
        override fun onServiceDisconnected(profile: Int) {
            hidDevice = null
            keyboard = null
            setStatus("Usługa Bluetooth została rozłączona")
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            runOnUiThread {
                if (registered) {
                    refreshDevices()
                    setStatus("Gotowy — wybierz sparowany telewizor")
                } else setStatus("Telefon nie udostępnił profilu pilota HID")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            runOnUiThread {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        selectedDevice = device
                        keyboard = hidDevice?.let { HidKeyboard(it, device) }
                        setStatus("Połączono z ${safeName(device)}")
                    }
                    BluetoothProfile.STATE_CONNECTING -> setStatus("Łączenie z ${safeName(device)}…")
                    else -> {
                        if (selectedDevice == device) keyboard = null
                        setStatus("Rozłączono")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)
        spinner = findViewById(R.id.deviceSpinner)

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        findViewById<Button>(R.id.connectButton).setOnClickListener { connectSelected() }
        keyboardButton(R.id.upButton, 0x52)
        keyboardButton(R.id.downButton, 0x51)
        keyboardButton(R.id.leftButton, 0x50)
        keyboardButton(R.id.rightButton, 0x4F)
        keyboardButton(R.id.okButton, 0x28)
        consumerButton(R.id.backButton, 0x0224)
        consumerButton(R.id.homeButton, 0x0223)
        consumerButton(R.id.volumeDownButton, 0x00EA)
        consumerButton(R.id.volumeUpButton, 0x00E9)
        consumerButton(R.id.playPauseButton, 0x00CD)
        findViewById<Button>(R.id.sendTextButton).setOnClickListener {
            val text = findViewById<EditText>(R.id.textInput).text.toString()
            val skipped = keyboard?.text(text)
            if (skipped == null) setStatus("Najpierw połącz telewizor")
            else if (skipped > 0) setStatus("Wysłano tekst; pominięto $skipped nieobsługiwanych znaków")
            else setStatus("Wysłano tekst")
        }
        ensurePermissionAndStart()
    }

    override fun onResume() {
        super.onResume()
        if (hasBluetoothPermission()) refreshDevices()
    }

    override fun onDestroy() {
        hidDevice?.unregisterApp()
        hidDevice?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        super.onDestroy()
    }

    private fun ensurePermissionAndStart() {
        if (Build.VERSION.SDK_INT >= 31 && !hasBluetoothPermission()) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else startHid()
    }

    private fun hasBluetoothPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun startHid() {
        val adapter = bluetoothAdapter
        if (adapter == null) return setStatus("Ten telefon nie obsługuje Bluetooth")
        if (!adapter.isEnabled) return setStatus("Włącz Bluetooth i wróć do aplikacji")
        val requested = adapter.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE)
        if (!requested) setStatus("Telefon nie udostępnia profilu Bluetooth HID Device")
    }

    private fun registerHidApp() {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Pilot Android TV", "Klawiatura i pilot Bluetooth", "Prototyp",
            BluetoothHidDevice.SUBCLASS1_COMBO, HidKeyboard.REPORT_DESCRIPTOR
        )
        val ok = hidDevice?.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), hidCallback) == true
        if (!ok) setStatus("Nie udało się zarejestrować profilu HID")
    }

    private fun refreshDevices() {
        if (!hasBluetoothPermission()) return
        bondedDevices = bluetoothAdapter?.bondedDevices?.sortedBy { safeName(it) } ?: emptyList()
        val names = bondedDevices.map { "${safeName(it)}  •  ${it.address}" }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            if (names.isEmpty()) listOf("Brak sparowanych urządzeń") else names)
    }

    private fun connectSelected() {
        if (bondedDevices.isEmpty()) return setStatus("Najpierw sparuj telewizor w ustawieniach Bluetooth")
        selectedDevice = bondedDevices[spinner.selectedItemPosition]
        val sent = hidDevice?.connect(selectedDevice) == true
        if (!sent) setStatus("Nie udało się rozpocząć połączenia HID")
    }

    private fun keyboardButton(id: Int, keyCode: Int) {
        findViewById<Button>(id).setOnClickListener {
            keyboard?.key(keyCode) ?: setStatus("Najpierw połącz telewizor")
        }
    }

    private fun consumerButton(id: Int, usage: Int) {
        findViewById<Button>(id).setOnClickListener {
            keyboard?.consumer(usage) ?: setStatus("Najpierw połącz telewizor")
        }
    }

    private fun safeName(device: BluetoothDevice): String = try { device.name ?: "Urządzenie Bluetooth" } catch (_: SecurityException) { "Urządzenie Bluetooth" }
    private fun setStatus(message: String) { status.text = message }
}
