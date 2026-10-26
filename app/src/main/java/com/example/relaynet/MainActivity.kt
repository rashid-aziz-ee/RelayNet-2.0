package com.example.relaynet

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.relaynet.data.RelayNetDatabase
import com.example.relaynet.ui.ChatScreen
import com.example.relaynet.ui.RelayNetTheme
import com.example.relaynet.ui.RelayViewModel
import com.example.relaynet.ui.RelayViewModelFactory

class MainActivity : ComponentActivity() {

    private var meshService: com.example.relaynet.mesh.MeshService? = null
    private var isBound = false
    private val PERMISSION_REQUEST_CODE = 101

    // Initialize Room Database and DAO
    private val database by lazy { RelayNetDatabase.getDatabase(this) }
    private val messageDao by lazy { database.messageDao() }

    // Lazy initialization of Device ID and Name
    private val myDeviceId by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).take(6)
    }
    private val myName by lazy {
        val prefs = getSharedPreferences("relaynet_user_prefs", android.content.Context.MODE_PRIVATE)
        val savedPhone = prefs.getString("user_phone", "") ?: ""
        if (savedPhone.isNotEmpty()) savedPhone else "Device_$myDeviceId"
    }

    // View Model with Factory to pass DAO and device configurations
    private val viewModel: RelayViewModel by viewModels {
        RelayViewModelFactory(messageDao, myDeviceId, myName, this)
    }

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val binder = service as com.example.relaynet.mesh.MeshService.MeshBinder
            meshService = binder.getService()
            isBound = true

            // Start mesh engine through service
            meshService?.startMeshEngine { incomingEnvelope ->
                viewModel.onMessageReceived(incomingEnvelope)
            }

            // Bind ViewModel hooks to Service MeshManager
            meshService?.meshManager?.let { meshManager ->
                viewModel.sendLambdaWithId = { text, msgId ->
                    val isBroadcast = text.startsWith("🆘") || viewModel.selectedRecipientId.value.isEmpty()
                    val recipientId = if (isBroadcast) "" else viewModel.selectedRecipientId.value
                    val recipientName = if (isBroadcast) "" else viewModel.selectedRecipientName.value
                    meshManager.broadcastNewMessage(text, myName, recipientId, recipientName, msgId)
                }

                viewModel.sendLambda = { text ->
                    val isBroadcast = text.startsWith("🆘") || viewModel.selectedRecipientId.value.isEmpty()
                    val recipientId = if (isBroadcast) "" else viewModel.selectedRecipientId.value
                    val recipientName = if (isBroadcast) "" else viewModel.selectedRecipientName.value
                    meshManager.broadcastNewMessage(text, myName, recipientId, recipientName)
                }

                meshManager.onPeersMapChangedCallback = { peersMap ->
                    viewModel.onPeersMapChanged(peersMap)
                }

                meshManager.onPeersChangedCallback = { peers ->
                    viewModel.onPeersChanged(peers)
                }

                // Set initial peers
                viewModel.onPeersMapChanged(meshManager.getActivePeersMap())

                viewModel.refreshLambda = {
                    meshManager.fullMeshRefresh()
                }

                // NEW: Initialize and link BenchmarkManager for automated latency & PDR testing
                val benchManager = com.example.relaynet.util.BenchmarkManager(
                    context = this@MainActivity,
                    myDeviceId = myDeviceId,
                    sendRawPacketLambda = { packet -> meshManager.sendRawPacket(packet) }
                )
                viewModel.benchmarkManager = benchManager
                meshManager.onRawPacketReceivedCallback = { rawData, fromEndpoint ->
                    benchManager.handleIncomingBenchmarkPacket(rawData, fromEndpoint)
                }
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            meshService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Compose content set karna
        setContent {
            RelayNetTheme {
                ChatScreen(viewModel = viewModel)
            }
        }

        // Permissions check karke mesh network start karna
        if (checkEssentialPermissions()) {
            startAndBindMeshService()
            checkAndWarnLocationService()
            Toast.makeText(this, "Mesh Network Started As: $myName", Toast.LENGTH_SHORT).show()
        } else {
            requestRequiredPermissions()
        }
    }

    // NEW: restart discovery/advertising whenever the app comes back to the foreground.
    // Nearby Connections' session can go stale after the app is backgrounded (screen lock,
    // switching apps, etc.), which previously required a full force-stop + relaunch to
    // recover. Re-calling startMesh() here re-establishes advertising/discovery cleanly
    // without needing that manual workaround.
    override fun onResume() {
        super.onResume()
        if (isBound) {
            meshService?.meshManager?.restartMesh()
        } else if (checkEssentialPermissions()) {
            startAndBindMeshService()
        }
        checkAndWarnLocationService()
    }

    private fun startAndBindMeshService() {
        val serviceIntent = android.content.Intent(this, com.example.relaynet.mesh.MeshService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, android.content.Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    // --- Permissions Handling Logic ---
    private fun checkEssentialPermissions(): Boolean {
        for (permission in getEssentialPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                if (permission == Manifest.permission.ACCESS_COARSE_LOCATION &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    continue
                }
                return false
            }
        }
        return true
    }

    private fun getEssentialPermissions(): Array<String> {
        val list = mutableListOf<String>()
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31+)
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            // Android 11 and below
            list.add(Manifest.permission.BLUETOOTH)
            list.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+) only
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        return list.toTypedArray()
    }

    private fun getAllRequiredPermissions(): Array<String> {
        return getEssentialPermissions() + arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private fun requestRequiredPermissions() {
        ActivityCompat.requestPermissions(this, getAllRequiredPermissions(), PERMISSION_REQUEST_CODE)
    }

    private fun isLocationServiceEnabled(): Boolean {
        val locationManager = getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager
        return locationManager?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true ||
                locationManager?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) == true
    }

    private fun checkAndWarnLocationService() {
        if (!isLocationServiceEnabled()) {
            Toast.makeText(
                this,
                "⚠️ Please turn ON 'Location' (GPS) in phone quick settings so Bluetooth Mesh can discover peers!",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (checkEssentialPermissions()) {
                startAndBindMeshService()
                checkAndWarnLocationService()
                Toast.makeText(this, "Permissions Granted. Mesh Started!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Essential permissions were denied. Mesh needs Bluetooth + Nearby Devices + Location to work.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
