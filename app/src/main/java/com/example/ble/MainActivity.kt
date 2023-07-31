package com.example.ble

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Build.VERSION
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import java.util.*


class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val BLUETOOTH_PERMISSION_REQUEST_CODE = 1

        // UUID for the VBUS Service and Characteristic
        private val VBUS_SERVICE_UUID: UUID =
            UUID.fromString("00000001-0000-1000-8000-00805f9b34fb")
        private val VBUS_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("00000001-0000-1000-8000-00805f9b34fb")

    }

    private fun generateMessageDigest(): String {
        val hash = MessageDigest.getInstance("MD5")
        val bytes = hash.digest("103122300001enydarten".toByteArray())
        return bytes.joinToString("") {
            "%02x".format(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d(TAG, "onCreate generateMessageDigest : ${generateMessageDigest()}")

    }

    override fun onResume() {
        super.onResume()
        requestBluetoothPermissions()
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Permissions for Android 12 and above
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ),
                    BLUETOOTH_PERMISSION_REQUEST_CODE
                )
            } else {
                // Permissions already granted
                enableBluetooth()
            }
        } else {
            enableBluetooth()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted
                Log.d(TAG, "onScanResult: Permission granted")
                enableBluetooth()
            } else {
                // Permission denied
                // Handle the scenario when Bluetooth permissions are denied
            }
        }
        Log.d(TAG, "onScanResult: requestCode : $requestCode")
        enableBluetooth()
    }

    private fun enableBluetooth() {
        Log.d(TAG, "enableBluetooth")
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled) {
            Log.d(TAG, "onScanResult: enableBluetooth not enabled")
            val enableBluetoothIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivity(enableBluetoothIntent)
        } else {
            // Bluetooth is already enabled and start the scan
            // Start scanning
            Log.d(TAG, "enableBluetooth enabled")
            startScan(bluetoothAdapter)

        }
    }

    private fun startScan(bluetoothAdapter: BluetoothAdapter) {
        Log.d(TAG, "startScan")
        val bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        val scanSettings = getScanSettings()
        val scanFilters = getScanFilters()
        bluetoothLeScanner.startScan(scanFilters, scanSettings, scanCallback)
    }

    private fun getScanFilters(): MutableList<ScanFilter?>? {

        var filters: MutableList<ScanFilter?>? = null
        filters = ArrayList()
        val filter = ScanFilter.Builder()
            .setDeviceName("IOSiX ELD")
            .setDeviceAddress("E0:E2:E6:1A:BB:52")
            .build()
        filters.add(filter)
        return filters
    }

    private fun getScanSettings(): ScanSettings? {
        val builder = ScanSettings.Builder()
        builder.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) //Uses very low power so it is ideal for long scanning times
        builder.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE) //In this mode, Android will match the scan results with the filters provided.
        return builder.build()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)

            result?.device?.let {
                // Handle the discovered BLE device here
                // You can perform connection, read, write, and notify operations with it
                Log.d(TAG, "onScanResult: ${it.name}")
                Log.d(TAG, "onScanResult: ${it.address}")
                it.connectGatt(applicationContext, false, gattCallback)
            }
        }
    }

    /*
    Read/write operations are asynchronous.
    This means that the command you issue will return immediately and you will receive the data a bit later via a callback function.
    E.g in onCharacteristicRead() or onCharacteristicWrite()
    You can only do 1 asynchronous operation at a time.
    You have to wait for one operation to complete before you can do the next operation.
    If you look at the source code of BluetoothGatt you see that a lock variable is set when an operation is started and it is cleared when the callback is done.
    This is the part Google forgot to mention in their documentation…
    * */

    private val commandQueue: Queue<Runnable>? = LinkedList<Runnable>()
    private var commandQueueBusy = false

    private fun writeCharacteristic(
        bluetoothGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        writeType: Int
    ): Boolean {
        Log.d(TAG, "writeCharacteristic");
        if (bluetoothGatt == null) {
            Log.d(TAG, "lost connection");
            return false;
        }
        commandQueue?.add(Runnable {
            characteristic?.value = data;
            characteristic?.writeType = writeType;
            Log.d(TAG, "writeCharacteristic characteristic: $characteristic");
            val success = bluetoothGatt?.writeCharacteristic(characteristic);
            if (!success!!) {
                Log.e(TAG, "writeCharacteristic failed");
            }
        });
        nextCommand()
        return true;
    }

    private fun nextCommand() {
        Log.d(TAG, "nextCommand commandQueue?.size: ${commandQueue?.size}");
        if (commandQueue?.size == 0) {
            return;
        }
        if (commandQueueBusy) {
            return;
        }
        commandQueueBusy = true;
        val command = commandQueue?.peek();
        command?.run();
    }

    private fun setCharacteristicNotification(
        bluetoothGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        enable: Boolean
    ): Boolean {
        Log.d(TAG, "setCharacteristicNotification");
        if (bluetoothGatt == null) {
            Log.d(TAG, "lost connection");
            return false;
        }
        if (!isSupportNotification(characteristic)) {
            return false;
        }
        Log.d(TAG, "Characteristic ${characteristic.uuid} does support notification")
        commandQueue?.add(Runnable {
            val success = bluetoothGatt?.setCharacteristicNotification(characteristic, enable);
            if (!success!!) {
                Log.e(TAG, "Subscription to notifications failed");
            }
            Log.d(TAG, "Subscription to notifications success: $success");
            val descriptor = characteristic.getDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            );
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            val success1 = bluetoothGatt?.writeDescriptor(descriptor);
            Log.d(TAG, "writeDescriptor is success: $success1")
            if (!success1!!) {
                Log.e(TAG, "writeDescriptor failed");
            }
        });
        //nextCommand()
        return true;
    }

    private fun completeCommand() {
        Log.d(TAG, "completeCommand commandQueue?.size: ${commandQueue?.size}");
        commandQueue?.remove();
        commandQueueBusy = false;
        nextCommand();
    }

    private fun isSupportNotification(characteristic: BluetoothGattCharacteristic?): Boolean {
        if (characteristic == null) return false
        val canNotify =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        if (!canNotify)
            Log.d(TAG, "Characteristic ${characteristic.uuid} doesn't support notification")
        return canNotify
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {

            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d(TAG, "onConnectionStateChange: Connected to device")
                gatt?.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.d(TAG, "onConnectionStateChange: Disconnected from device")
                // Disconnected from GATT server.
            }
        }


        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            Log.d(TAG, "onServicesDiscovered: ")
            gatt?.services?.forEach { service ->
                Log.d(TAG, "onServicesDiscovered: service: ${service.uuid}")
                service.characteristics.forEach { characteristic ->
                    Log.d(TAG, "onServicesDiscovered: characteristic: ${characteristic.uuid}")
                    Log.d(TAG, "onServicesDiscovered: characteristic: ${characteristic.properties}")
                }
            }
            val service = gatt?.getService(VBUS_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(VBUS_CHARACTERISTIC_UUID)

            gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
            if (VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                gatt?.requestMtu(512);
            }

            // All requests to a BluetoothGatt object must be serialised. You can't request mtu while you have another pending operation.

            Handler(Looper.getMainLooper()!!).postDelayed({
                gatt?.setCharacteristicNotification(characteristic!!, true)
                if (gatt != null) {
                    if (characteristic != null) {
                        writeCharacteristic(
                            gatt,
                            characteristic,
                            "\"SETUP,103122300001,4b225f068355192981d680c764d3e849,1\"".toByteArray(
                                Charsets.UTF_8
                            ),
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        )
                        writeCharacteristic(
                            gatt,
                            characteristic,
                            "\"INFOCONNECT\"".toByteArray(Charsets.UTF_8),
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        )
                    }
                }
            }, 1500)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            if (descriptor?.uuid?.equals(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) == true) {
                Log.d(
                    TAG,
                    "onDescriptorWrite: descriptor: ${descriptor.value.toString(Charsets.UTF_8)}"
                )
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "onDescriptorWrite: success")
                    val value: ByteArray = descriptor.value
                    Log.d(
                        TAG, "onDescriptorWrite: descriptor " +
                                "value: ${value[0]}"
                    )
                } else {
                    Log.d(TAG, "onDescriptorWrite: failed")
                }

            }
            completeCommand()

        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            Log.d(TAG, "onMtuChanged: mtu: $mtu")
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, value, status)
            Log.d(TAG, "onCharacteristicRead: ${value.toString(Charsets.UTF_8)}")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            Log.d(TAG, "onCharacteristicWrite: ")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "onCharacteristicWrite: success")
                completeCommand()
            } else {
                Log.d(TAG, "onCharacteristicWrite: failed")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            // Log.d(TAG, "onCharacteristicChanged: ")
            with(characteristic) {
                when (this?.uuid) {
                    VBUS_CHARACTERISTIC_UUID -> {
                        Log.d(
                            TAG,
                            "onCharacteristicChanged value: ${
                                value?.toString(
                                    Charsets.UTF_8
                                )
                            }"
                        )

                    }
                    else -> {
                        Log.d(
                            TAG,
                            "onCharacteristicChanged else : ${this?.value?.toString(Charsets.UTF_8)}"
                        )
                    }
                }
            }
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            super.onServiceChanged(gatt)
            Log.d(TAG, "onServiceChanged: ")
        }

    }

}