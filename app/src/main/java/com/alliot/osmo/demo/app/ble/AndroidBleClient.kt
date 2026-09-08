package com.alliot.osmo.demo.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.alliot.osmo.demo.ble.BleClient
import com.alliot.osmo.demo.ble.BleConnectionState
import com.alliot.osmo.demo.ble.BleEvent
import com.alliot.osmo.demo.ble.BleScanResult
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidBleClient(
    private val context: Context,
) : BleClient {
    private val bluetoothManager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner
    private val advertiser: BluetoothLeAdvertiser? get() = adapter?.bluetoothLeAdvertiser

    private val _scanResults = MutableStateFlow(emptyList<BleScanResult>())
    override val scanResults = _scanResults.asStateFlow()

    private val _connectionState = MutableStateFlow(
        BleConnectionState(
            isBluetoothEnabled = adapter?.isEnabled == true,
            isConnected = false,
            localAdapterAddress = readLocalAdapterAddress(),
            supportsWakeAdvertising = supportsWakeAdvertising(),
        ),
    )
    override val connectionState = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<BleEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<BleEvent> = _events.asSharedFlow()

    private val discoveredDevices = linkedMapOf<String, BleScanResult>()
    private var scanCallback: ScanCallback? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var pendingConnect: CancellableContinuation<Unit>? = null
    private var pendingWrite: CancellableContinuation<Unit>? = null
    private var advertiserStopCallback: AdvertiseCallback? = null
    private var suppressNextDisconnectEvent = false
    private val writeMutex = Mutex()

    @SuppressLint("MissingPermission")
    override suspend fun startScan() {
        _connectionState.value = _connectionState.value.copy(
            isBluetoothEnabled = adapter?.isEnabled == true,
            localAdapterAddress = readLocalAdapterAddress(),
            supportsWakeAdvertising = supportsWakeAdvertising(),
        )
        val activeAdapter = adapter
        val activeScanner = scanner
        if (activeAdapter?.isEnabled != true || activeScanner == null) {
            emitError("Bluetooth LE scan is unavailable.")
            return
        }

        stopScanInternal()
        discoveredDevices.clear()
        _scanResults.value = emptyList()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                consumeScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::consumeScanResult)
            }

            override fun onScanFailed(errorCode: Int) {
                emitError("BLE scan failed: $errorCode")
            }
        }
        scanCallback = callback

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(REMOTE_SERVICE_UUID))
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        runCatching {
            activeScanner.startScan(filters, settings, callback)
        }.onFailure { error ->
            scanCallback = null
            emitError("Failed to start scan: ${error.message}")
            return
        }

        Log.d(TAG, "BLE scan started with FFF0 filter")
        _events.tryEmit(BleEvent.ScanStarted)
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopScan() {
        stopScanInternal()
        _events.tryEmit(BleEvent.ScanStopped)
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(macAddress: String) {
        val activeAdapter = adapter
        if (activeAdapter?.isEnabled != true) {
            throw IllegalStateException("Bluetooth adapter is unavailable.")
        }

        stopScanInternal()
        disconnectCurrentGatt()

        suspendCancellableCoroutine { continuation ->
            pendingConnect?.resumeWithException(IllegalStateException("Superseded by another connect request."))
            pendingConnect = continuation

            val device = runCatching { activeAdapter.getRemoteDevice(macAddress) }.getOrElse { error ->
                pendingConnect = null
                continuation.resumeWithException(IllegalArgumentException("Invalid device address: $macAddress", error))
                return@suspendCancellableCoroutine
            }

            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDeviceTransport.LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }

            if (gatt == null) {
                pendingConnect = null
                continuation.resumeWithException(IllegalStateException("Failed to create GATT connection."))
                return@suspendCancellableCoroutine
            }

            bluetoothGatt = gatt
            Log.d(TAG, "Connecting GATT to $macAddress")
            continuation.invokeOnCancellation {
                if (pendingConnect === continuation) {
                    pendingConnect = null
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect() {
        stopScanInternal()
        pendingConnect?.let {
            pendingConnect = null
            it.resume(Unit)
        }
        pendingWrite?.let {
            pendingWrite = null
            it.resume(Unit)
        }
        suppressNextDisconnectEvent = true
        disconnectCurrentGatt()
    }

    @SuppressLint("MissingPermission")
    override suspend fun write(bytes: ByteArray) {
        val gatt = bluetoothGatt ?: throw IllegalStateException("No active GATT connection.")
        val characteristic = writeCharacteristic ?: throw IllegalStateException("Write characteristic 0xFFF5 is unavailable.")

        writeMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                pendingWrite = continuation

                val queued = runCatching {
                    writeCharacteristic(gatt, characteristic, bytes)
                }.getOrElse { error ->
                    if (pendingWrite === continuation) {
                        pendingWrite = null
                    }
                    continuation.resumeWithException(error)
                    return@suspendCancellableCoroutine
                }

                if (!queued) {
                    if (pendingWrite === continuation) {
                        pendingWrite = null
                    }
                    continuation.resumeWithException(IllegalStateException("BluetoothGatt rejected the write request."))
                    return@suspendCancellableCoroutine
                }

                Log.d(TAG, "FFF5 write queued: ${bytes.toHex()}")

                continuation.invokeOnCancellation {
                    if (pendingWrite === continuation) {
                        pendingWrite = null
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun startWakeAdvertising(reversedMac: ByteArray) {
        val activeAdapter = adapter
        if (activeAdapter?.isEnabled != true) {
            emitError("Wake advertising requires Bluetooth.")
            return
        }
        val activeAdvertiser = advertiser
        if (activeAdvertiser == null || !activeAdapter.isMultipleAdvertisementSupported) {
            emitError("Wake advertising is not supported on this Android device.")
            return
        }

        advertiserStopCallback?.let { activeAdvertiser.stopAdvertising(it) }
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.d(TAG, "Wake advertising started successfully for 2s.")
            }

            override fun onStartFailure(errorCode: Int) {
                emitError("Wake advertising start failed: $errorCode")
            }
        }
        advertiserStopCallback = callback

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            // Android manufacturer data always prepends the company id in little-endian order.
            // Use 0x4B57 so the transmitted payload becomes 'W','K','P',<reversed-mac>.
            .addManufacturerData(WAKE_MANUFACTURER_ID, byteArrayOf('P'.code.toByte()) + reversedMac)
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setTimeout(2_000)
            .build()

        runCatching {
            activeAdvertiser.startAdvertising(settings, data, callback)
        }.onFailure { error ->
            emitError("Wake advertising failed: ${error.message}")
        }
        Log.d(TAG, "Wake advertising requested (WKP+reversed-mac): ${reversedMac.toHex()}")
    }

    @SuppressLint("MissingPermission")
    private fun consumeScanResult(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return
        val name = device.name ?: result.scanRecord?.deviceName ?: ""
        val manufacturerData = result.scanRecord?.manufacturerSpecificData?.let { data ->
            if (data.size() > 0) data.valueAt(0)?.copyOf() else null
        }
        discoveredDevices[address] = BleScanResult(
            name = name,
            macAddress = address,
            rssi = result.rssi,
            manufacturerData = manufacturerData,
        )
        _scanResults.value = discoveredDevices.values.toList()
        Log.d(TAG, "Scan result: name=${name.ifBlank { "<blank>" }} mac=$address rssi=${result.rssi}")
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        val callback = scanCallback ?: return
        runCatching { scanner?.stopScan(callback) }
        scanCallback = null
    }

    @SuppressLint("MissingPermission")
    private fun disconnectCurrentGatt() {
        notifyCharacteristic = null
        writeCharacteristic = null
        val gatt = bluetoothGatt
        bluetoothGatt = null
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        val connectedAddress = _connectionState.value.connectedAddress
        _connectionState.value = BleConnectionState(
            isBluetoothEnabled = adapter?.isEnabled == true,
            isConnected = false,
            localAdapterAddress = readLocalAdapterAddress(),
            supportsWakeAdvertising = supportsWakeAdvertising(),
        )
        if (connectedAddress != null) {
            _events.tryEmit(BleEvent.Disconnected(connectedAddress))
        }
    }

    private fun emitError(message: String) {
        Log.e(TAG, message)
        _events.tryEmit(BleEvent.Error(message))
    }

    @SuppressLint("MissingPermission")
    private fun onServicesReady(gatt: BluetoothGatt, service: BluetoothGattService) {
        val notify = service.getCharacteristic(REMOTE_NOTIFY_UUID)
        val write = service.getCharacteristic(REMOTE_WRITE_UUID)
        if (notify == null || write == null) {
            failConnect("Remote service is missing FFF4/FFF5 characteristics.")
            return
        }

        val descriptor = notify.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor == null) {
            failConnect("Notification descriptor 0x2902 is unavailable.")
            return
        }

        notifyCharacteristic = notify
        writeCharacteristic = write
        Log.d(TAG, "Service ready. notify=${notify.uuid} write=${write.uuid}")
        gatt.setCharacteristicNotification(notify, true)
        if (!writeDescriptor(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
            failConnect("Failed to enable FFF4 notifications.")
        }
    }

    private fun failConnect(message: String, exception: Throwable? = null) {
        emitError(message)
        pendingConnect?.let { continuation ->
            pendingConnect = null
            if (exception != null) {
                continuation.resumeWithException(exception)
            } else {
                continuation.resumeWithException(IllegalStateException(message))
            }
        }
    }

    private fun resolveConnect(address: String) {
        _connectionState.value = BleConnectionState(
            isBluetoothEnabled = adapter?.isEnabled == true,
            isConnected = true,
            connectedAddress = address,
            localAdapterAddress = readLocalAdapterAddress(),
            supportsWakeAdvertising = supportsWakeAdvertising(),
        )
        Log.d(TAG, "GATT ready for $address")
        _events.tryEmit(BleEvent.Connected(address))
        pendingConnect?.resume(Unit)
        pendingConnect = null
    }

    private fun resolveWrite(bytes: ByteArray) {
        Log.d(TAG, "FFF5 write confirmed: ${bytes.toHex()}")
        _events.tryEmit(BleEvent.Write(bytes))
        pendingWrite?.let {
            pendingWrite = null
            it.resume(Unit)
        }
    }

    private fun failWrite(message: String) {
        emitError(message)
        pendingWrite?.let {
            pendingWrite = null
            it.resumeWithException(IllegalStateException(message))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState device=${gatt.device?.address}")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "STATE_CONNECTED reached; preparing MTU request sdk=${Build.VERSION.SDK_INT}")
                        val mtuRequested = runCatching {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                gatt.requestMtu(REQUEST_MTU)
                            } else {
                                false
                            }
                        }.onFailure { error ->
                            Log.e(TAG, "requestMtu($REQUEST_MTU) threw: ${error.message}", error)
                        }.getOrDefault(false)
                        Log.d(TAG, "requestMtu($REQUEST_MTU) result=$mtuRequested")
                        if (!mtuRequested) {
                            Log.w(TAG, "Falling back to discoverServices without MTU change")
                            gatt.discoverServices()
                        }
                    } else {
                        failConnect("GATT connect failed with status $status.")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val address = gatt.device?.address
                    bluetoothGatt = null
                    notifyCharacteristic = null
                    writeCharacteristic = null
                    _connectionState.value = BleConnectionState(
                        isBluetoothEnabled = adapter?.isEnabled == true,
                        isConnected = false,
                        localAdapterAddress = readLocalAdapterAddress(),
                        supportsWakeAdvertising = supportsWakeAdvertising(),
                    )
                    if (suppressNextDisconnectEvent) {
                        suppressNextDisconnectEvent = false
                    } else {
                        _events.tryEmit(BleEvent.Disconnected(address, if (status == BluetoothGatt.GATT_SUCCESS) null else "status=$status"))
                    }
                    pendingConnect?.resumeWithException(IllegalStateException("Disconnected during connect: $status"))
                    pendingConnect = null
                    pendingWrite?.let {
                        pendingWrite = null
                        it.resumeWithException(IllegalStateException("Disconnected during write: $status"))
                    }
                    gatt.close()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged status=$status mtu=$mtu device=${gatt.device?.address}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "MTU request failed with status $status, continuing with service discovery.")
            }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status device=${gatt.device?.address}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnect("Service discovery failed with status $status.")
                return
            }

            val service = gatt.getService(REMOTE_SERVICE_UUID)
            if (service == null) {
                failConnect("Remote service 0xFFF0 was not found.")
                return
            }
            onServicesReady(gatt, service)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID) return
            Log.d(TAG, "onDescriptorWrite status=$status uuid=${descriptor.uuid}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnect("Notification enable failed with status $status.")
                return
            }
            val address = gatt.device?.address ?: "unknown"
            resolveConnect(address)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid != REMOTE_WRITE_UUID) return
            Log.d(TAG, "onCharacteristicWrite status=$status uuid=${characteristic.uuid}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failWrite("FFF5 write failed with status $status.")
                return
            }
            resolveWrite(readCharacteristicValue(characteristic))
        }

        @Deprecated("BluetoothGattCallback compatibility overload")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != REMOTE_NOTIFY_UUID) return
            val value = readCharacteristicValue(characteristic)
            Log.d(TAG, "FFF4 notify(legacy): ${value.toHex()}")
            _events.tryEmit(BleEvent.Notification(value))
        }

        @Deprecated("BluetoothGattCallback compatibility overload")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid != REMOTE_NOTIFY_UUID) return
            Log.d(TAG, "FFF4 notify: ${value.toHex()}")
            _events.tryEmit(BleEvent.Notification(value))
        }
    }

    private object BluetoothDeviceTransport {
        const val LE = 2
    }

    private companion object {
        private const val TAG = "OsmoBle"
        private const val REQUEST_MTU = 517
        private val REMOTE_SERVICE_UUID: UUID = uuid16(0xFFF0)
        private val REMOTE_NOTIFY_UUID: UUID = uuid16(0xFFF4)
        private val REMOTE_WRITE_UUID: UUID = uuid16(0xFFF5)
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = uuid16(0x2902)
        private const val WAKE_MANUFACTURER_ID = 0x4B57

        private fun uuid16(shortUuid: Int): UUID =
            UUID.fromString("0000${shortUuid.toString(16).padStart(4, '0')}-0000-1000-8000-00805f9b34fb")
    }

    @Suppress("DEPRECATION")
    private fun readCharacteristicValue(characteristic: BluetoothGattCharacteristic): ByteArray =
        characteristic.value ?: ByteArray(0)

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.value = bytes
                gatt.writeCharacteristic(characteristic)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptor(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    // BLUETOOTH_CONNECT / BLUETOOTH_ADVERTISE are runtime permissions on API 31+. The BLE
    // client is constructed before the user grants them, so these reads must not throw.
    @SuppressLint("MissingPermission")
    private fun readLocalAdapterAddress(): String? =
        runCatching { adapter?.address }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun supportsWakeAdvertising(): Boolean =
        runCatching {
            adapter?.isMultipleAdvertisementSupported == true && advertiser != null
        }.getOrDefault(false)

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
}
