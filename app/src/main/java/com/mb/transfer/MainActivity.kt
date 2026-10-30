package com.mb.transfer

import android.Manifest
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.CalendarContract
import android.text.InputType
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import android.accounts.AccountManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.PowerManager
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URL
import java.net.URLEncoder

class MainActivity : Activity() {

    private class Item(val name: String, val open: () -> InputStream)

    private val cats = linkedMapOf(
        "contacts" to "جهات الاتصال", "images" to "الصور", "video" to "الفيديو",
        "audio" to "الصوتيات", "docs" to "المستندات والتنزيلات",
        "sms" to "الرسائل SMS", "calls" to "سجل المكالمات",
        "apps" to "التطبيقات (ملفات التثبيت فقط)", "accounts" to "الحسابات (القائمة فقط)",
        "whatsapp" to "واتساب - المحادثات والنسخ الاحتياطية", "wamedia" to "واتساب - الوسائط",
        "files" to "باقي ملفات التخزين",
        "calendar" to "التقويم (المحلي فقط)")
    private val boxes = LinkedHashMap<String, CheckBox>()
    private lateinit var ip: EditText
    private lateinit var pin: EditText
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var qrView: ImageView
    private lateinit var link: Link
    private lateinit var fastCb: CheckBox

    private fun autoUsb() {
        if (ip.text.isNotBlank() || link.usbIp() == null) return
        say("جارٍ البحث عن الطرف الآخر عبر كابل USB...")
        link.findOnUsb()?.let { a -> runOnUiThread { ip.setText(a) }; Thread.sleep(300) }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun glass(r: Int = 20, fill: Int = 0x26FFFFFF, stroke: Int = 0x40FFFFFF) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(fill); setStroke(dp(1), stroke)
    }

    private fun card(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = glass()
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(14)) }
        v.forEach { addView(it) }
    }

    private fun styled(b: Button, primary: Boolean): Button {
        b.background = if (primary)
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xCC5EA8FF.toInt(), 0xCC7C6BFF.toInt()))
                .apply { cornerRadius = dp(16).toFloat() }
        else glass(16, 0x1FFFFFFF, 0x33FFFFFF)
        b.setTextColor(0xFFF2F6FF.toInt()); b.isAllCaps = false; b.textSize = 15f
        b.stateListAnimator = null
        b.layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(0, dp(5), 0, dp(5)) }
        return b
    }

    private fun label(t: String, size: Float = 14f, color: Int = 0xFFB9C4DC.toInt(), bold: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun field(h: String) = EditText(this).apply {
        hint = h; setHintTextColor(0xFF8E9BB8.toInt()); setTextColor(0xFFF2F6FF.toInt()); textSize = 15f
        background = glass(14, 0x1AFFFFFF, 0x33FFFFFF); setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(5), 0, dp(5)) }
    }

    private fun blob(size: Int, color: Int, g: Int, mx: Int, my: Int) = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = FrameLayout.LayoutParams(dp(size), dp(size), g).apply { setMargins(dp(mx), dp(my), dp(mx), dp(my)) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        window.statusBarColor = 0xFF0F1B33.toInt(); window.navigationBarColor = 0xFF24405C.toInt()
        val white = 0xFFF2F6FF.toInt()
        fun btn(t: String, primary: Boolean = false) = styled(Button(this).apply { text = t }, primary)

        ip = field("عنوان الجوال القديم أو الكمبيوتر  مثال 192.168.1.5:8765")
        pin = field("الرمز").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
        qrView = ImageView(this).apply {
            visibility = View.GONE; setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(18).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(230), dp(230)).apply { gravity = Gravity.CENTER; setMargins(0, dp(8), 0, dp(8)) }
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
            .apply { progressTintList = ColorStateList.valueOf(0xFF7FB2FF.toInt()) }
        status = label("جاهز", 14f, 0xFFD5DEF2.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        wizTitle = label("", 17f, white, true)
        wizText = label("", 14f, 0xFFD5DEF2.toInt())
        wizAction = btn("", true)
        wizNext = btn("", false)
        wizPanel = card(wizTitle, wizText, wizAction, wizNext)
            .apply { visibility = View.GONE; background = glass(18, 0x33FFFFFF, 0x55FFFFFF) }

        val bigOld = btn("هذا هو الجوال القديم — ابدأ النقل", true)
        val bigNew = btn("هذا هو الجوال الجديد — استقبل", true)
        val scan = btn("مسح رمز QR")
        val wizOld = btn("معالج واتساب — الجوال القديم")
        val wizNew = btn("معالج واتساب — الجوال الجديد")
        val grant = btn("تفعيل جميع الصلاحيات")
        val send = btn("إرسال إلى الكمبيوتر")
        val recv = btn("استقبال من الكمبيوتر")
        val report = btn("عرض تقرير النقل")
        val clear = btn("بدء نقل جديد (مسح سجل الاستئناف)")
        val all = btn("تحديد الكل / إلغاء الكل")
        val full = btn("نقل كامل — تحديد كل شيء بضغطة واحدة", true)
        val usb = btn("النقل بكابل USB")
        fastCb = CheckBox(this).apply {
            text = "النقل السريع المباشر تلقائيًا (إن دعمه الجوالان)"; isChecked = true
            setTextColor(0xFFE4EBFA.toInt()); buttonTintList = ColorStateList.valueOf(0xFF7FB2FF.toInt())
        }

        val checks = cats.map { (k, t) ->
            CheckBox(this).apply {
                text = t; isChecked = true; setTextColor(0xFFE4EBFA.toInt())
                buttonTintList = ColorStateList.valueOf(0xFF7FB2FF.toInt())
            }.also { boxes[k] = it }
        }.toTypedArray()

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(44), dp(16), dp(24)) }
        listOf(
            card(label("نقل الجوال", 26f, white, true), label("انقل بياناتك بين جوالين بخطوة واحدة", 14f), credit()),
            card(bigOld, bigNew,
                label("١) في الجوال القديم اضغط «ابدأ».  ٢) في الجديد اضغط «استقبل» وامسح الرمز أو اكتبه.", 12.5f)),
            card(label("الاتصال", 16f, white, true), ip, pin, qrView, scan, fastCb, usb),
            card(label("اختر ما تريد نقله", 16f, white, true), full, *checks, all),
            card(label("أدوات", 16f, white, true), wizOld, wizNew, wizPanel, grant, send, recv, report, clear),
            card(bar, status),
            credit()
        ).forEach { col.addView(it) }

        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0F1B33.toInt(), 0xFF1B2F52.toInt(), 0xFF24405C.toInt()))
            addView(blob(280, 0x665E7CFF, Gravity.TOP or Gravity.END, -70, 30))
            addView(blob(240, 0x5540C8B8, Gravity.BOTTOM or Gravity.START, -60, 80))
            addView(ScrollView(this@MainActivity).apply { addView(col) })
        }
        setContentView(root)
        link = Link(this)

        requestPermissions(allPerms, 1)
        val flt = IntentFilter(ACTION_INSTALL)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(installReceiver, flt, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(installReceiver, flt)

        bigOld.setOnClickListener { afterPerms { startOld() } }
        bigNew.setOnClickListener { afterPerms { startNew() } }
        scan.setOnClickListener { scanQr() }
        wizOld.setOnClickListener { afterPerms { startWiz(true) } }
        wizNew.setOnClickListener { afterPerms { startWiz(false) } }
        wizNext.setOnClickListener { stepNext() }
        grant.setOnClickListener { wiz = 0; requestPermissions(allPerms, 1) }
        full.setOnClickListener {
            boxes.values.forEach { it.isChecked = true }
            requestPermissions(allPerms, 1)
            say("تم تحديد كل الأنواع للنقل الكامل")
        }
        all.setOnClickListener {
            val on = boxes.values.any { !it.isChecked }
            boxes.values.forEach { it.isChecked = on }
        }
        usb.setOnClickListener {
            android.app.AlertDialog.Builder(this).setTitle("النقل بكابل USB")
                .setMessage("١) وصّل الكابل بين الجوال والكمبيوتر (الأكثر ثباتًا)، أو بين الجوالين.\n٢) في الجوال المرسل فعّل «مشاركة الإنترنت عبر USB».\n٣) اضغط «ابدأ» في القديم و«استقبل» في الجديد، وسيجد التطبيق الطرف الآخر وحده.\nبعض الجوالات لا تقبل الربط المباشر بجوال آخر، وحينها استخدم الكمبيوتر وسيطًا.")
                .setPositiveButton("فتح الإعدادات") { _, _ ->
                    try { startActivity(Intent("android.settings.TETHER_SETTINGS")) }
                    catch (e: Exception) { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
                }
                .setNegativeButton("إغلاق", null).show()
        }
        send.setOnClickListener { afterPerms { go(false) { autoUsb(); sendAll() } } }
        recv.setOnClickListener { afterPerms { go(true) { autoUsb(); receiveAll() } } }
        report.setOnClickListener { showReport() }
        clear.setOnClickListener { doneFile.delete(); say("تم مسح سجل الاستئناف") }
    }

    private fun go(recv: Boolean, f: () -> Unit) = Thread {
        try { if (ensureFiles(recv)) f() } catch (e: Exception) { say("خطأ: ${e.message}") }
    }.start()

    private fun say(s: String) = runOnUiThread { status.text = s }
    private fun base() = "http://${ip.text}"
    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun chosen() = boxes.filter { it.value.isChecked }.keys

    private fun catOf(name: String): String {
        val p = name.split('/')
        return if (p[0] == "media" && p.size > 1) p[1] else p[0]
    }

    // صلاحيات خاصة تُطلب فقط عند الحاجة
    private fun ensureFiles(recv: Boolean): Boolean {
        if (chosen().any { it in fileCats } && !Environment.isExternalStorageManager()) {
            runOnUiThread { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            say("فعّل صلاحية الوصول لكل الملفات ثم أعد المحاولة")
            return false
        }
        if (recv && "apps" in chosen() && !packageManager.canRequestPackageInstalls()) {
            runOnUiThread { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
            say("اسمح لهذا التطبيق بتثبيت التطبيقات ثم أعد المحاولة")
            return false
        }
        return true
    }

    // ================= جمع العناصر =================
    private fun bytesItem(name: String, data: ByteArray) = Item(name) { ByteArrayInputStream(data) }

    private fun collect(cat: String): List<Item> = when (cat) {
        "contacts" -> {
            val keys = ArrayList<String>()
            contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.LOOKUP_KEY), null, null, null)?.use {
                while (it.moveToNext()) keys.add(it.getString(0))
            }
            if (keys.isEmpty()) emptyList() else {
                val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_MULTI_VCARD_URI,
                    Uri.encode(keys.joinToString(":")))
                val data = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                listOf(bytesItem("contacts/contacts.vcf", data))
            }
        }
        "images" -> media(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "images")
        "video" -> media(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video")
        "audio" -> media(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio")
        "docs" -> {
            val root = Environment.getExternalStorageDirectory()
            listOf("Download", "Documents").flatMap { d ->
                File(root, d).walkTopDown().filter { it.isFile && !it.path.contains("/Transfer/") }.map {
                    Item("docs/" + it.relativeTo(root).path) { it.inputStream() }
                }.toList()
            }
        }
        "sms" -> {
            val arr = JSONArray()
            contentResolver.query(Telephony.Sms.CONTENT_URI,
                arrayOf("address", "body", "date", "type"), null, null, "date ASC")?.use {
                while (it.moveToNext()) arr.put(JSONObject()
                    .put("a", it.getString(0)).put("b", it.getString(1))
                    .put("d", it.getLong(2)).put("t", it.getInt(3)))
            }
            listOf(bytesItem("sms/sms.json", arr.toString().toByteArray()))
        }
        "calls" -> {
            val arr = JSONArray()
            contentResolver.query(CallLog.Calls.CONTENT_URI,
                arrayOf("number", "date", "duration", "type", "name"), null, null, "date ASC")?.use {
                while (it.moveToNext()) arr.put(JSONObject()
                    .put("n", it.getString(0)).put("d", it.getLong(1))
                    .put("u", it.getLong(2)).put("t", it.getInt(3)).put("c", it.getString(4) ?: ""))
            }
            listOf(bytesItem("calls/calls.json", arr.toString().toByteArray()))
        }
        "whatsapp", "wamedia" -> listOf("com.whatsapp", "com.whatsapp.w4b").flatMap { pkg ->
            val wa = File(root, "Android/media/$pkg/WhatsApp")
            val subs = if (cat == "whatsapp") listOf("Databases", "Backups") else listOf("Media")
            subs.flatMap { walk(cat, File(wa, it)) }
        }
        "calendar" -> {
            val ids = ArrayList<Long>()
            contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.ACCOUNT_TYPE}=?", arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL), null)?.use {
                while (it.moveToNext()) ids.add(it.getLong(0))
            }
            val arr = JSONArray()
            if (ids.isNotEmpty()) contentResolver.query(CalendarContract.Events.CONTENT_URI,
                arrayOf("title", "description", "dtstart", "dtend", "allDay", "eventLocation", "eventTimezone", "rrule", "duration"),
                "calendar_id IN (${ids.joinToString(",")}) AND deleted=0", null, null)?.use {
                while (it.moveToNext()) arr.put(JSONObject()
                    .put("t", it.getString(0) ?: "").put("d", it.getString(1) ?: "")
                    .put("s", it.getLong(2)).put("e", if (it.isNull(3)) 0L else it.getLong(3))
                    .put("a", it.getInt(4)).put("l", it.getString(5) ?: "").put("z", it.getString(6) ?: "")
                    .put("r", it.getString(7) ?: "").put("u", it.getString(8) ?: ""))
            }
            listOf(bytesItem("calendar/calendar.json", arr.toString().toByteArray()))
        }
        "files" -> walk("files", root) { it == File(root, "Android") || it.name == "Transfer" }
        "apps" -> packageManager.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != packageName }
            .flatMap { ai ->
                val files = listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
                files.map { path -> Item("apps/${ai.packageName}/${File(path).name}") { File(path).inputStream() } }
            }
        "accounts" -> {
            val arr = JSONArray()
            AccountManager.get(this).accounts.forEach { arr.put(JSONObject().put("n", it.name).put("t", it.type)) }
            listOf(bytesItem("accounts/accounts.json", arr.toString().toByteArray()))
        }
        else -> emptyList()
    }

    private fun media(tbl: Uri, dir: String): List<Item> {
        val out = ArrayList<Item>()
        contentResolver.query(tbl, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            null, null, null)?.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val uri = Uri.withAppendedPath(tbl, id.toString())
                out.add(Item("media/$dir/${id}_${it.getString(1)}") { contentResolver.openInputStream(uri)!! })
            }
        }
        return out
    }

    // ================= إرسال =================
    private fun sendAll() {
        val items = chosen().flatMap { collect(it) }
        runOnUiThread { bar.max = items.size; bar.progress = 0 }
        items.forEachIndexed { i, item ->
            say("إرسال ${i + 1} من ${items.size}")
            val c = URL("${base()}/upload?pin=${code()}&name=${q(item.name)}").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.setChunkedStreamingMode(0)
            c.outputStream.use { o -> item.open().use { it.copyTo(o) } }
            if (c.responseCode != 200) throw Exception("رفض الخادم (${c.responseCode})")
            c.disconnect()
            runOnUiThread { bar.progress = i + 1 }
        }
        say("تم إرسال ${items.size} عنصر")
    }

    // ================= استقبال =================
    private fun receiveAll() {
        okCnt.clear(); failCnt.clear(); failNames.clear(); skipped = 0; installLog.clear()
        val ls = fetch("/list", null)
        val raw = ls.use { it.readBytes() }
        if (ls is TailHashStream && !ls.verified) throw Exception("فشل التحقق من قائمة الملفات")
        val arr = JSONArray(String(raw))
        val done = loadDone()
        val wanted = (0 until arr.length()).map { arr.getString(it) }.filter { catOf(it) in chosen() }
        val names = wanted.filter { it !in done }
        skipped = wanted.size - names.size
        runOnUiThread { bar.max = names.size; bar.progress = 0 }
        names.forEachIndexed { i, name ->
            say("استقبال ${i + 1} من ${names.size}")
            val cat = catOf(name)
            try {
                val st = fetch("/file", name)
                st.use { save(name, it) }
                if (st is TailHashStream && !st.verified) throw Exception("فشل التحقق من سلامة الملف")
                markDone(name)
                okCnt[cat] = (okCnt[cat] ?: 0) + 1
            } catch (e: Exception) {
                failCnt[cat] = (failCnt[cat] ?: 0) + 1
                failNames.add("${name.substringAfterLast('/')}: ${e.message}")
            }
            runOnUiThread { bar.progress = i + 1 }
        }
        if (names.any { catOf(it) == "apps" }) installApps()
        say("تم: ${okCnt.values.sum()}  •  فشل: ${failCnt.values.sum()}  •  متخطى: $skipped")
        showReport()
    }

    private fun save(name: String, inp: InputStream) {
        val file = name.substringAfterLast('/')
        when (catOf(name)) {
            "contacts" -> {
                val u = toDownloads(file, "text/x-vcard", inp)
                runOnUiThread { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "text/x-vcard")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            }
            "images" -> toMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, file, "Pictures/Transfer", inp)
            "video" -> toMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, file, "Movies/Transfer", inp)
            "audio" -> toMedia(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, file, "Music/Transfer", inp)
            "docs" -> {
                val f = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "Transfer/" + name.removePrefix("docs/"))
                f.parentFile!!.mkdirs()
                f.outputStream().use { inp.copyTo(it) }
            }
            "apps" -> {
                val p = name.split('/')
                val f = File(getExternalFilesDir(null), "apps-in/${p[1]}/${p[2]}")
                f.parentFile!!.mkdirs()
                f.outputStream().use { inp.copyTo(it) }
            }
            "whatsapp", "wamedia", "files" -> {
                val f = File(root, name.substringAfter('/')).canonicalFile
                if (!f.path.startsWith(root.canonicalPath)) return
                f.parentFile!!.mkdirs()
                f.outputStream().use { inp.copyTo(it) }
            }
            "accounts" -> showAccounts(inp.readBytes())
            "calendar" -> restoreCalendar(inp.readBytes())
            "calls" -> restoreCalls(inp.readBytes())
            // الرسائل: أندرويد لا يسمح بكتابتها إلا لتطبيق الرسائل الافتراضي، فتُحفظ في التنزيلات
            "sms" -> toDownloads(file, "application/json", inp)
        }
    }

    private fun toMedia(tbl: Uri, file: String, rel: String, inp: InputStream) {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
        }
        val u = contentResolver.insert(tbl, v) ?: return
        contentResolver.openOutputStream(u)!!.use { inp.copyTo(it) }
    }

    private fun toDownloads(file: String, mime: String, inp: InputStream): Uri {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
        }
        val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
        contentResolver.openOutputStream(u)!!.use { inp.copyTo(it) }
        return u
    }

    private fun restoreCalls(data: ByteArray) {
        val arr = JSONArray(String(data))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val v = ContentValues().apply {
                put(CallLog.Calls.NUMBER, o.getString("n")); put(CallLog.Calls.DATE, o.getLong("d"))
                put(CallLog.Calls.DURATION, o.getLong("u")); put(CallLog.Calls.TYPE, o.getInt("t"))
                put(CallLog.Calls.CACHED_NAME, o.optString("c"))
            }
            contentResolver.insert(CallLog.Calls.CONTENT_URI, v)
        }
    }

    private val root: File get() = Environment.getExternalStorageDirectory()
    private val fileCats = setOf("docs", "whatsapp", "wamedia", "files")

    private fun walk(prefix: String, dir: File, skip: (File) -> Boolean = { false }): List<Item> =
        if (!dir.exists()) emptyList()
        else dir.walkTopDown().onEnter { !skip(it) }.filter { it.isFile }
            .map { f -> Item("$prefix/" + f.relativeTo(root).path) { f.inputStream() } }.toList()

    private fun credit() = label("تم عمل التطبيق للمبرمج محمد البريم (أبو نصر)", 12.5f)
        .apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }

    // ================= الرمز والتشفير =================
    private fun code() = pin.text.toString().trim().uppercase().replace("-", "").replace(" ", "")
    private fun secure() = code().length > 4   // رمز الجوال (10 خانات) مشفّر، ورمز الكمبيوتر (4 أرقام) عادي
    private var keyCache: Pair<String, SecretKeySpec>? = null

    @Synchronized private fun keyFor(code: String): SecretKeySpec {
        keyCache?.let { if (it.first == code) return it.second }
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = SecretKeySpec(f.generateSecret(PBEKeySpec(code.toCharArray(), "mbtransfer-v1".toByteArray(), 20000, 256)).encoded, "AES")
        keyCache = code to k
        return k
    }

    private fun verifier(code: String) =
        MessageDigest.getInstance("SHA-256").digest(("auth:$code").toByteArray()).joinToString("") { "%02x".format(it) }

    // يقرأ المحتوى المشفّر ويتحقق من بصمته (SHA-256) في النهاية
    private class TailHashStream(private val src: InputStream) : InputStream() {
        private val md = MessageDigest.getInstance("SHA-256")
        private var tail = ByteArray(0)
        private var out = ByteArray(0)
        private var op = 0
        private var eof = false
        var verified = false
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (op >= out.size) { if (eof) return -1; fill() }
            val n = minOf(len, out.size - op)
            System.arraycopy(out, op, b, off, n); op += n
            return n
        }
        private fun fill() {
            val buf = ByteArray(65536)
            val n = src.read(buf)
            if (n < 0) { eof = true; verified = tail.size == 32 && md.digest().contentEquals(tail); out = ByteArray(0); op = 0; return }
            val all = tail + buf.copyOf(n)
            val keep = minOf(32, all.size)
            val emit = all.size - keep
            md.update(all, 0, emit)
            out = all.copyOf(emit); op = 0
            tail = all.copyOfRange(emit, all.size)
        }
    }

    private fun fetch(path: String, name: String?): InputStream {
        val auth = if (secure()) verifier(code()) else code()
        val u = "${base()}$path?pin=$auth" + (if (name != null) "&name=${q(name)}" else "")
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 60000
        val raw = c.inputStream
        if (!secure()) return raw
        val iv = ByteArray(16); var got = 0
        while (got < 16) { val n = raw.read(iv, got, 16 - got); if (n < 0) throw Exception("اتصال غير صالح"); got += n }
        val cp = Cipher.getInstance("AES/CTR/NoPadding")
        cp.init(Cipher.DECRYPT_MODE, keyFor(code()), IvParameterSpec(iv))
        return TailHashStream(CipherInputStream(raw, cp))
    }

    // ================= الاستئناف والتقرير =================
    private val okCnt = HashMap<String, Int>()
    private val failCnt = HashMap<String, Int>()
    private val failNames = ArrayList<String>()
    private val installLog = ArrayList<String>()
    private var skipped = 0
    private val doneFile get() = File(filesDir, "done.txt")

    private fun loadDone(): Set<String> {
        val cut = System.currentTimeMillis() - 24 * 3600_000L
        if (!doneFile.exists()) return emptySet()
        return doneFile.readLines().mapNotNull {
            val p = it.split('\t', limit = 2)
            if (p.size == 2 && (p[0].toLongOrNull() ?: 0L) > cut) p[1] else null
        }.toSet()
    }

    private fun markDone(name: String) = doneFile.appendText("${System.currentTimeMillis()}\t$name\n")

    private fun reportText(): String {
        val sb = StringBuilder()
        for ((k, t) in cats) {
            val o = okCnt[k] ?: 0; val f = failCnt[k] ?: 0
            if (o + f > 0) sb.append("$t: $o تم" + (if (f > 0) "، $f فشل" else "") + "\n")
        }
        if (skipped > 0) sb.append("متخطى (نُقل سابقًا): $skipped\n")
        if (failNames.isNotEmpty()) {
            sb.append("\nملفات فشلت:\n").append(failNames.take(15).joinToString("\n"))
            if (failNames.size > 15) sb.append("\n... و${failNames.size - 15} أخرى")
            sb.append("\n")
        }
        if (installLog.isNotEmpty()) sb.append("\nالتطبيقات:\n").append(installLog.joinToString("\n"))
        return if (sb.isEmpty()) "لا يوجد تقرير بعد" else sb.toString()
    }

    private fun showReport() = runOnUiThread {
        android.app.AlertDialog.Builder(this).setTitle("تقرير النقل")
            .setMessage(reportText()).setPositiveButton("حسنًا", null).show()
    }

    // ================= رمز QR والاكتشاف التلقائي =================
    private fun makeQr(text: String): android.graphics.Bitmap {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
        val bmp = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.RGB_565)
        for (x in 0 until 512) for (y in 0 until 512)
            bmp.setPixel(x, y, if (m[x, y]) 0xFF0F1B33.toInt() else 0xFFFFFFFF.toInt())
        return bmp
    }

    private fun scanQr() {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, opts).startScan()
            .addOnSuccessListener { bc ->
                val raw = bc.rawValue ?: ""
                val m = Regex("mbtransfer://([^/]+)/([A-Z0-9]+)").find(raw)
                if (m == null) { say("الرمز غير صالح"); return@addOnSuccessListener }
                ip.setText(m.groupValues[1]); pin.setText(m.groupValues[2])
                val cr = Regex("[?]s=([^&]*)&p=(.*)$").find(raw)
                if (cr != null) {
                    val ss = URLDecoder.decode(cr.groupValues[1], "UTF-8")
                    val pp = URLDecoder.decode(cr.groupValues[2], "UTF-8")
                    say("جارٍ الاتصال بالنقل السريع المباشر...")
                    afterPerms {
                        link.join(ss, pp) { gw ->
                            if (gw == null) say("تعذر الاتصال بالشبكة المباشرة، جرّب إطفاء النقل السريع في القديم")
                            else { runOnUiThread { ip.setText("$gw:8765") }; go(true) { receiveAll() } }
                        }
                    }
                } else afterPerms { go(true) { receiveAll() } }
            }
            .addOnFailureListener { say("تعذر مسح الرمز: ${it.message}") }
    }

    private var nsdReg: NsdManager.RegistrationListener? = null
    private var nsdDisc: NsdManager.DiscoveryListener? = null

    private fun advertise(port: Int) {
        val nsd = getSystemService(NsdManager::class.java)
        nsdReg?.let { try { nsd.unregisterService(it) } catch (e: Exception) { } }
        val info = NsdServiceInfo().apply { serviceName = "MBTransfer"; serviceType = "_mbtransfer._tcp."; setPort(port) }
        val l = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
        }
        nsdReg = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    private fun discover() {
        val nsd = getSystemService(NsdManager::class.java)
        nsdDisc?.let { try { nsd.stopServiceDiscovery(it) } catch (e: Exception) { } }
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                try {
                    nsd.resolveService(i, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
                        override fun onServiceResolved(s: NsdServiceInfo) {
                            val h = s.host
                            if (h is Inet4Address) runOnUiThread { ip.setText("${h.hostAddress}:${s.port}") }
                        }
                    })
                } catch (e: Exception) { }
            }
        }
        nsdDisc = l
        nsd.discoverServices("_mbtransfer._tcp.", NsdManager.PROTOCOL_DNS_SD, l)
    }

    private fun restoreCalendar(data: ByteArray) {
        var calId = -1L
        contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?", arrayOf("500"), null)?.use {
            if (it.moveToFirst()) calId = it.getLong(0)
        }
        if (calId < 0) throw Exception("لا يوجد تقويم قابل للكتابة")
        val arr = JSONArray(String(data))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val v = ContentValues().apply {
                put("calendar_id", calId); put("title", o.optString("t")); put("description", o.optString("d"))
                put("dtstart", o.getLong("s")); put("allDay", o.optInt("a")); put("eventLocation", o.optString("l"))
                put("eventTimezone", o.optString("z").ifEmpty { java.util.TimeZone.getDefault().id })
                val r = o.optString("r")
                if (r.isNotEmpty()) { put("rrule", r); put("duration", o.optString("u").ifEmpty { "PT1H" }) }
                else put("dtend", if (o.optLong("e") > 0) o.getLong("e") else o.getLong("s") + 3600000L)
            }
            contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
        }
    }

    // ================= نقل مباشر من الجوال القديم إلى الجديد (بدون كمبيوتر) =================
    private var pending: (() -> Unit)? = null
    private var serverSocket: ServerSocket? = null
    @Volatile private var served = 0

    private fun permsOk() = Environment.isExternalStorageManager() && packageManager.canRequestPackageInstalls() &&
        checkSelfPermission(Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // يطلب كل الصلاحيات مرة واحدة ثم يكمل العملية تلقائيًا
    private fun afterPerms(f: () -> Unit) {
        if (permsOk()) f() else { pending = f; wiz = 0; requestPermissions(allPerms, 1) }
    }

    private fun startOld() {
        boxes.values.forEach { it.isChecked = true }
        val begin = { go(false) { serve() } }
        if (waPkg() != null && !backupFresh()) {
            android.app.AlertDialog.Builder(this).setTitle("واتساب")
                .setMessage("لنقل المحادثات اعمل نسخة احتياطية في واتساب أولًا: الإعدادات ← المحادثات ← النسخ الاحتياطي ← نسخ احتياطي. ثم ارجع واضغط ابدأ.")
                .setPositiveButton("فتح واتساب") { _, _ -> openWa() }
                .setNegativeButton("متابعة بدونها") { _, _ -> begin() }.show()
        } else begin()
    }

    private fun startNew() {
        boxes.values.forEach { it.isChecked = true }
        if (code().isEmpty()) { discover(); scanQr(); return }
        go(true) {
            fun cur() = ip.text.toString().trim()
            if (cur().isEmpty() && link.usbIp() != null) autoUsb()
            if (cur().isEmpty()) {
                discover(); say("جارٍ البحث عن الجوال القديم...")
                var t = 0
                while (cur().isEmpty() && t < 100) { Thread.sleep(100); t++ }
            }
            if (cur().isEmpty()) {
                say("جارٍ البحث بالبلوتوث...")
                val info = link.bleFind(12000)
                if (info != null) {
                    val j = org.json.JSONObject(info)
                    val a = if (j.has("s")) {
                        say("تم العثور عليه، جارٍ الربط بالنقل السريع...")
                        link.joinBlocking(j.getString("s"), j.getString("p"))?.let { "$it:8765" }
                    } else j.optString("a")
                    if (!a.isNullOrEmpty()) { runOnUiThread { ip.setText(a) }; Thread.sleep(300) }
                }
            }
            if (cur().isEmpty()) throw Exception("لم أجد الجوال القديم. تأكد من الشبكة أو الكابل أو اكتب العنوان")
            receiveAll()
        }
    }

    private fun localIp(): String =
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress ?: "؟"

    private fun serve() {
        served = 0
        say("جارٍ تجهيز البيانات...")
        val items = chosen().flatMap { collect(it) }.associateBy { it.name }
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = SecureRandom()
        val code = (1..10).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        serverSocket?.close()
        val ss = ServerSocket(8765); serverSocket = ss
        var addr = "${link.usbIp() ?: localIp()}:8765"
        var extra = ""
        var mode = if (link.usbIp() != null) "كابل USB" else "الواي فاي"
        var hs: Pair<String, String>? = null
        if (link.usbIp() == null && fastCb.isChecked && link.fastSupported()) {
            say("جارٍ تجهيز النقل السريع المباشر...")
            hs = link.startHotspot()
            if (hs != null) {
                extra = "?s=${q(hs.first)}&p=${q(hs.second)}"; mode = "نقل سريع مباشر"
                addr = "${link.apIp() ?: localIp()}:8765"
            }
        }
        advertise(8765)
        try {
            val j = JSONObject().put("a", addr)
            if (hs != null) j.put("s", hs.first).put("p", hs.second)
            link.bleStart(j.toString())
        } catch (e: Exception) { }
        runOnUiThread {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            qrView.setImageBitmap(makeQr("mbtransfer://$addr/$code$extra")); qrView.visibility = View.VISIBLE
        }
        say("في الجوال الجديد امسح الرمز، أو اكتب هذا الرمز:\n${code.chunked(5).joinToString("-")}\n(${items.size} عنصر جاهز • $mode • $addr)")
        while (!ss.isClosed) {
            val c = try { ss.accept() } catch (e: Exception) { break }
            Thread { try { handle(c, items, code) } catch (e: Exception) { } }.start()
        }
    }

    private fun handle(c: Socket, items: Map<String, Item>, code: String) {
        c.use { sock ->
            val rd = sock.getInputStream().bufferedReader()
            val first = rd.readLine() ?: return@use
            while (true) { val h = rd.readLine(); if (h.isNullOrEmpty()) break }
            val target = first.split(' ').getOrNull(1) ?: return@use
            val path = target.substringBefore('?')
            val params = target.substringAfter('?', "").split('&').filter { '=' in it }
                .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val out = sock.getOutputStream()
            if (params["pin"] != verifier(code)) { out.write("HTTP/1.0 401 Unauthorized\r\n\r\n".toByteArray()); return@use }
            val key = keyFor(code)
            fun enc(): CipherOutputStream {
                val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
                out.write(iv)
                val cp = Cipher.getInstance("AES/CTR/NoPadding")
                cp.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
                return CipherOutputStream(out, cp)
            }
            when (path) {
                "/list" -> {
                    val body = JSONArray(items.keys.toList()).toString().toByteArray()
                    out.write("HTTP/1.0 200 OK\r\n\r\n".toByteArray())
                    val cos = enc()
                    cos.write(body); cos.write(MessageDigest.getInstance("SHA-256").digest(body)); cos.close()
                }
                "/file" -> {
                    val item = items[params["name"]]
                    if (item == null) { out.write("HTTP/1.0 404 Not Found\r\n\r\n".toByteArray()); return@use }
                    out.write("HTTP/1.0 200 OK\r\n\r\n".toByteArray())
                    val cos = enc(); val md = MessageDigest.getInstance("SHA-256")
                    item.open().use { inp ->
                        val buf = ByteArray(65536)
                        while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n); cos.write(buf, 0, n) }
                    }
                    cos.write(md.digest()); cos.close()
                    served++
                    say("أُرسل $served من ${items.size}")
                }
            }
        }
    }

    override fun onDestroy() {
        serverSocket?.close()
        try { link.stopAll() } catch (e: Exception) { }
        try { nsdReg?.let { getSystemService(NsdManager::class.java).unregisterService(it) } } catch (e: Exception) { }
        try { nsdDisc?.let { getSystemService(NsdManager::class.java).stopServiceDiscovery(it) } } catch (e: Exception) { }
        super.onDestroy()
    }

    // ================= معالج نقل واتساب خطوة بخطوة =================
    private class Step(val title: String, val text: String, val label: String?,
                       val act: (() -> Unit)?, val ok: (() -> String?)?)

    private lateinit var wizPanel: LinearLayout
    private lateinit var wizTitle: TextView
    private lateinit var wizText: TextView
    private lateinit var wizAction: Button
    private lateinit var wizNext: Button
    private var steps: List<Step> = emptyList()
    private var si = 0

    private fun waPkg(): String? = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull {
        try { packageManager.getPackageInfo(it, 0); true } catch (e: Exception) { false }
    }

    private fun openWa() {
        val i = waPkg()?.let { packageManager.getLaunchIntentForPackage(it) }
        if (i != null) startActivity(i) else say("واتساب غير مثبّت")
    }

    private fun openStore() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.whatsapp"))) }
        catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.whatsapp")))
        }
    }

    private fun backupFresh(): Boolean {
        val cut = System.currentTimeMillis() - 3 * 3600_000L
        return listOf("com.whatsapp", "com.whatsapp.w4b").any { pkg ->
            File(root, "Android/media/$pkg/WhatsApp/Databases").listFiles()
                ?.any { it.name.startsWith("msgstore") && it.lastModified() > cut } == true
        }
    }

    private fun addrOk(): String? =
        if (ip.text.isBlank() || pin.text.isBlank()) "اكتب عنوان الكمبيوتر والـ PIN في الحقلين أدناه" else null

    private val connectText = "شغّل برنامج الكمبيوتر، ثم اكتب العنوان والـ PIN الظاهرين فيه في الحقلين أدناه. " +
        "يجب أن يكون الجوالان والكمبيوتر على نفس شبكة الواي فاي."

    private fun oldSteps() = listOf(
        Step("اتصل بالكمبيوتر", connectText, null, null) { addrOk() },
        Step("نسخة احتياطية في واتساب",
            "سيفتح واتساب. اضغط ⋮ ثم الإعدادات ثم المحادثات ثم النسخ الاحتياطي، واضغط «نسخ احتياطي» وانتظر حتى ينتهي. " +
            "ثم ارجع إلى هذا التطبيق واضغط التالي، وسأتحقق من وجود النسخة تلقائيًا.",
            "فتح واتساب", { openWa() }) { if (backupFresh()) null else "لم أجد نسخة احتياطية جديدة (آخر 3 ساعات). أعد النسخ ثم حاول." },
        Step("إرسال المحادثات",
            "سيرسل التطبيق نسخة واتساب إلى الكمبيوتر. لا تُغلق الشاشة حتى ينتهي، وسينتقل للخطوة التالية وحده.",
            "ابدأ الإرسال", { go(false) { sendAll(); runOnUiThread { stepNext() } } }, null),
        Step("انتهى الجوال القديم",
            "افتح هذا التطبيق في الجوال الجديد واختر «هذا هو الجوال الجديد». لا تحذف واتساب من هنا قبل التأكد من الاستعادة.",
            null, null, null))

    private fun newSteps() = listOf(
        Step("اتصل بالكمبيوتر", connectText, null, null) { addrOk() },
        Step("ثبّت واتساب ولا تفتحه",
            "سيفتح متجر Play. ثبّت واتساب فقط ولا تفتحه الآن، ثم ارجع واضغط التالي.",
            "فتح المتجر", { openStore() }) { if (waPkg() != null) null else "واتساب غير مثبّت بعد" },
        Step("استقبال المحادثات",
            "سيستقبل التطبيق نسخة واتساب من الكمبيوتر إلى هذا الجوال. انتظر حتى ينتهي، وسينتقل للخطوة التالية وحده.",
            "ابدأ الاستقبال", { go(true) { receiveAll(); runOnUiThread { stepNext() } } }, null),
        Step("استعادة في واتساب",
            "سيفتح واتساب. سجّل بنفس رقم الهاتف القديم. سيصلك رمز التحقق برسالة أو مكالمة على الشريحة، " +
            "اكتبه إن لم يُقرأ تلقائيًا. وعندما يعرض واتساب النسخة الموجودة اضغط «استعادة» وانتظر انتهاءها.",
            "فتح واتساب", { openWa() }, null))

    private fun startWiz(old: Boolean) {
        if (!Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            say("اسمح بالوصول لكل الملفات ثم اضغط الزر مرة أخرى"); return
        }
        boxes.forEach { (k, c) -> c.isChecked = (k == "whatsapp" || k == "wamedia") }
        steps = if (old) oldSteps() else newSteps()
        si = 0
        wizPanel.visibility = View.VISIBLE
        showStep()
    }

    private fun showStep() {
        val st = steps[si]
        wizTitle.text = "الخطوة ${si + 1} من ${steps.size}: ${st.title}"
        wizText.text = st.text
        wizAction.visibility = if (st.label == null) View.GONE else View.VISIBLE
        wizAction.text = st.label ?: ""
        wizAction.setOnClickListener { st.act?.invoke() }
        wizNext.text = if (si == steps.size - 1) "إنهاء" else "التالي (يتحقق تلقائيًا)"
    }

    private fun stepNext() {
        val err = steps[si].ok?.invoke()
        if (err != null) { say(err); return }
        if (si == steps.size - 1) { wizPanel.visibility = View.GONE; say("انتهى المعالج"); return }
        si++; showStep()
    }

    // ================= التطبيقات والحسابات والصلاحيات =================
    private val ACTION_INSTALL = "com.mb.transfer.INSTALL_RESULT"
    private var wiz = -1

    private val allPerms = arrayOf(
        Manifest.permission.READ_CONTACTS, Manifest.permission.GET_ACCOUNTS,
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG, Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES,
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pkg = i.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "?"
            when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> installLog.add("✔ $pkg")
                else -> installLog.add("✘ $pkg: ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (wiz >= 0) nextStep()
    }

    // معالج الصلاحيات الخاصة: ملفات، تثبيت تطبيقات، تجاهل توفير البطارية
    private fun nextStep() {
        val s = wiz; wiz++
        when (s) {
            0 -> if (!Environment.isExternalStorageManager()) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))); return
            }
            1 -> if (!packageManager.canRequestPackageInstalls()) {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))); return
            }
            2 -> if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))); return
            }
            else -> { wiz = -1; say("تم تفعيل جميع الصلاحيات"); pending?.let { pending = null; it() }; return }
        }
        nextStep()
    }

    private fun installApps() {
        val pi = packageManager.packageInstaller
        val root = File(getExternalFilesDir(null), "apps-in")
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return
        dirs.forEachIndexed { n, d ->
            say("تثبيت ${n + 1} من ${dirs.size}: ${d.name}")
            val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
            pi.openSession(id).use { ses ->
                d.listFiles()!!.forEach { f ->
                    f.inputStream().use { inp -> ses.openWrite(f.name, 0, f.length()).use { o -> inp.copyTo(o); ses.fsync(o) } }
                }
                val pend = android.app.PendingIntent.getBroadcast(this, id,
                    Intent(ACTION_INSTALL).setPackage(packageName), android.app.PendingIntent.FLAG_MUTABLE)
                ses.commit(pend.intentSender)
            }
        }
        root.deleteRecursively()
    }

    private fun showAccounts(data: ByteArray) {
        val arr = JSONArray(String(data))
        val txt = (0 until arr.length()).joinToString("\n") {
            val o = arr.getJSONObject(it); "${o.getString("n")}  (${o.getString("t")})"
        }
        runOnUiThread {
            android.app.AlertDialog.Builder(this).setTitle("الحسابات في الجوال القديم")
                .setMessage(txt + "\n\nكلمات المرور لا تُنقل. سجّل الدخول بكل حساب يدويًا.")
                .setPositiveButton("إضافة حساب") { _, _ -> startActivity(Intent(Settings.ACTION_ADD_ACCOUNT)) }
                .setNegativeButton("لاحقًا", null).show()
        }
    }
}
