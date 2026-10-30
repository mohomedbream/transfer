package com.mb.transfer

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// ربط الجوالين: كابل USB، نقل سريع مباشر (نقطة اتصال محلية)، وبحث بالبلوتوث فقط
@SuppressLint("MissingPermission")
class Link(private val act: Activity) {
    companion object {
        val SERVICE: UUID = UUID.fromString("6d62b7a0-1c4e-4f5a-9b1e-4d4254524d31")
        val CHAR: UUID = UUID.fromString("6d62b7a1-1c4e-4f5a-9b1e-4d4254524d31")
        const val PORT = 8765
    }

    private val cm: ConnectivityManager get() = act.getSystemService(ConnectivityManager::class.java)
    private val wm: WifiManager get() = act.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    // ================= كابل USB =================
    private fun isUsbName(n: String) = Regex("^(rndis|usb|ncm|eth)\\d*").containsMatchIn(n)

    fun usbIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && isUsbName(it.name) }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }?.hostAddress
    } catch (e: Exception) { null }

    // يبحث في شبكة الكابل عن الطرف الآخر (الكمبيوتر أو الجوال) الذي يفتح المنفذ
    fun findOnUsb(): String? {
        val mine = usbIp() ?: return null
        val prefix = mine.substringBeforeLast('.')
        val pool = Executors.newFixedThreadPool(48)
        val found = AtomicReference<String?>(null)
        val latch = CountDownLatch(254)
        for (i in 1..254) pool.execute {
            val h = "$prefix.$i"
            if (h != mine && found.get() == null) {
                try {
                    Socket().use { it.connect(InetSocketAddress(h, PORT), 400) }
                    found.compareAndSet(null, "$h:$PORT")
                } catch (e: Exception) { }
            }
            latch.countDown()
        }
        latch.await(15, TimeUnit.SECONDS)
        pool.shutdownNow()
        return found.get()
    }

    // ================= النقل السريع المباشر (بدون راوتر) =================
    fun fastSupported() = Build.VERSION.SDK_INT >= 29 && try { wm.is5GHzBandSupported } catch (e: Exception) { false }

    private var hotspot: WifiManager.LocalOnlyHotspotReservation? = null

    // الجوال القديم: ينشئ شبكة محلية خاصة بالنقل ويعيد (الاسم، كلمة السر)
    fun startHotspot(): Pair<String, String>? {
        val latch = CountDownLatch(1)
        var res: Pair<String, String>? = null
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    hotspot = r
                    res = if (Build.VERSION.SDK_INT >= 30) {
                        val c = r.softApConfiguration
                        (c.ssid ?: "") to (c.passphrase ?: "")
                    } else {
                        @Suppress("DEPRECATION") val c = r.wifiConfiguration
                        (c?.SSID ?: "").trim('"') to (c?.preSharedKey ?: "").trim('"')
                    }
                    latch.countDown()
                }
                override fun onFailed(reason: Int) { latch.countDown() }
            }, Handler(act.mainLooper))
        } catch (e: Exception) { return null }
        latch.await(10, TimeUnit.SECONDS)
        return res?.takeIf { it.first.isNotEmpty() && it.second.isNotEmpty() }
    }

    fun apIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && Regex("^(ap|swlan|softap|wlan1|wigig)\\d*").containsMatchIn(it.name) }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }?.hostAddress
    } catch (e: Exception) { null }

    private var netCb: ConnectivityManager.NetworkCallback? = null

    // الجوال الجديد: ينضم للشبكة المباشرة ويعيد عنوان الجوال القديم (البوابة)
    fun join(ssid: String, pass: String, done: (String?) -> Unit) {
        val fired = AtomicBoolean(false)
        fun finish(v: String?) { if (fired.compareAndSet(false, true)) done(v) }
        fun gateway(lp: LinkProperties?): String? {
            lp ?: return null
            lp.routes.firstOrNull { val g = it.gateway; g is Inet4Address && !g.isAnyLocalAddress }
                ?.gateway?.hostAddress?.let { return it }
            val mine = lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull()
            return mine?.hostAddress?.substringBeforeLast('.')?.let { "$it.1" }
        }
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(pass).build()
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { cm.bindProcessToNetwork(network) }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                gateway(lp)?.let { finish(it) }
            }
            override fun onUnavailable() { finish(null) }
        }
        netCb = cb
        try { cm.requestNetwork(req, cb, 40000) } catch (e: Exception) { finish(null) }
    }

    fun joinBlocking(ssid: String, pass: String): String? {
        val latch = CountDownLatch(1)
        var gw: String? = null
        join(ssid, pass) { gw = it; latch.countDown() }
        latch.await(45, TimeUnit.SECONDS)
        return gw
    }

    // ================= البلوتوث: للبحث فقط =================
    private var gattServer: BluetoothGattServer? = null
    private var adv: BluetoothLeAdvertiser? = null
    private var advCb: AdvertiseCallback? = null

    // الجوال القديم: يعلن عن نفسه ويعطي عنوان الاتصال فقط (الرمز السري لا يُرسل أبدًا)
    fun bleStart(info: String): Boolean {
        val bm = act.getSystemService(BluetoothManager::class.java) ?: return false
        val ad = bm.adapter ?: return false
        if (!ad.isEnabled) return false
        val le = ad.bluetoothLeAdvertiser ?: return false
        val value = info.toByteArray()
        val srv = bm.openGattServer(act, object : BluetoothGattServerCallback() {
            override fun onCharacteristicReadRequest(d: BluetoothDevice, id: Int, off: Int, c: BluetoothGattCharacteristic) {
                val part = if (off <= value.size) value.copyOfRange(off, value.size) else ByteArray(0)
                gattServer?.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, off, part)
            }
        }) ?: return false
        val svc = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(BluetoothGattCharacteristic(CHAR,
            BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        srv.addService(svc); gattServer = srv
        val st = AdvertiseSettings.Builder().setConnectable(true)
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).setIncludeDeviceName(false).build()
        val cb = object : AdvertiseCallback() {}
        advCb = cb; adv = le
        le.startAdvertising(st, data, cb)
        return true
    }

    // الجوال الجديد: يبحث عن الجوال القديم ويقرأ عنوانه
    fun bleFind(timeoutMs: Long): String? {
        val bm = act.getSystemService(BluetoothManager::class.java) ?: return null
        val ad = bm.adapter ?: return null
        if (!ad.isEnabled) return null
        val sc = ad.bluetoothLeScanner ?: return null
        val dev = AtomicReference<BluetoothDevice?>(null)
        val latch = CountDownLatch(1)
        val scb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (dev.compareAndSet(null, result.device)) latch.countDown()
            }
        }
        sc.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scb)
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        sc.stopScan(scb)
        val d = dev.get() ?: return null
        val got = CountDownLatch(1)
        var out: String? = null
        val g = d.connectGatt(act, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices() else got.countDown()
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val c = g.getService(SERVICE)?.getCharacteristic(CHAR)
                if (c == null || !g.readCharacteristic(c)) got.countDown()
            }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) out = String(c.value ?: ByteArray(0))
                got.countDown()
            }
        })
        got.await(10, TimeUnit.SECONDS)
        try { g.close() } catch (e: Exception) { }
        return out
    }

    fun stopAll() {
        try { adv?.stopAdvertising(advCb) } catch (e: Exception) { }
        try { gattServer?.close() } catch (e: Exception) { }
        try { hotspot?.close() } catch (e: Exception) { }
        try { cm.bindProcessToNetwork(null) } catch (e: Exception) { }
        try { netCb?.let { cm.unregisterNetworkCallback(it) } } catch (e: Exception) { }
    }
}
