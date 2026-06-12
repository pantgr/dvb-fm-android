package gr.pantelis.dvbtv

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.hardware.usb.UsbDevice
import info.martinmarinov.drivers.DeliverySystem
import info.martinmarinov.drivers.DvbDevice
import info.martinmarinov.drivers.DvbStatus
import info.martinmarinov.drivers.usb.DvbUsbDevice
import info.martinmarinov.drivers.usb.DvbUsbDeviceRegistry
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val TAG = "DvbTv"
        private const val BANDWIDTH_HZ = 8_000_000L
        private const val BANNER_MS = 5000L

        // Ελληνικά muxes, known-good από το dvb_tv (Windows) scan
        private val MUX_MHZ = listOf(490, 514, 538, 562, 578, 586, 626)
    }

    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var statusView: TextView
    private lateinit var channelList: ListView
    private lateinit var btnScan: Button
    private lateinit var leftPanel: LinearLayout
    private lateinit var adapter: ArrayAdapter<String>

    private lateinit var banner: LinearLayout
    private lateinit var bannerTitle: TextView
    private lateinit var bannerSignal: TextView
    private lateinit var bannerNow: TextView
    private lateinit var bannerNext: TextView
    private lateinit var bannerDesc: TextView
    private lateinit var bannerProgress: ProgressBar
    private var bannerExpanded = false
    private var epgOffset = 0 // 0 = τώρα· δεξιά/αριστερά ξεφυλλίζουν το πρόγραμμα

    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null

    // 🔴 Driver design (όπως το demo DVB-T Driver app): το device ανοίγει ΜΙΑ φορά,
    // το getTransportStream() καλείται ΜΙΑ φορά, και το stream μένει ανοιχτό για πάντα.
    // Close/reopen → κλειστός demux + SIGSEGV στο usbxfer. Ο TsRouter το διαβάζει συνέχεια
    // και μοιράζει τα bytes στον εκάστοτε sink (scan parser ή pipe προς VLC + EIT).
    private var device: DvbDevice? = null
    private var tsStream: InputStream? = null
    private var tsRouter: Thread? = null
    @Volatile private var tsSink: ((ByteArray, Int) -> Unit)? = null
    private var vlcWrite: ParcelFileDescriptor.AutoCloseOutputStream? = null

    private val eit = EitParser()
    private var statusThread: Thread? = null
    @Volatile private var lastSignal = ""

    // ΟΛΕΣ οι device δουλειές (open/tune/scan/play) σε ένα single thread — μηδέν races
    private val deviceExec = Executors.newSingleThreadExecutor { r -> Thread(r, "DvbWorker") }

    private var channels = listOf<ChannelStore.Channel>()
    @Volatile private var current: ChannelStore.Channel? = null
    private var fullscreen = false

    // FM mode
    private lateinit var btnFm: Button
    private lateinit var radioPanel: LinearLayout
    private lateinit var radioFreq: TextView
    private lateinit var radioSt: TextView
    private lateinit var radioCt: TextView
    private lateinit var radioPs: TextView
    private lateinit var radioRt: TextView
    private lateinit var radioRtpTitle: TextView
    private lateinit var radioRtpArtist: TextView
    private lateinit var radioSig: TextView
    private var fmEngine: FmEngine? = null
    @Volatile private var fmMode = false
    private var fmFreqHz = 98_000_000L

    // b108 — λειτουργική ταυτοποίηση sticks (ιδέα Pantelis): το stick με τη UHF
    // κεραία ΚΛΕΙΔΩΝΕΙ σε γνωστό DVB-T mux, το FM όχι. Identity = κεραία —
    // self-healing σε αλλαγή θύρας/stick. Cache μέχρι USB attach/detach
    // (τα deviceNames αλλάζουν σε re-enumeration). ΕΓΓΥΗΣΗ (απαίτηση Pantelis):
    // το FM παίρνει stick ΜΟΝΟ με θετική ταυτοποίηση (DVB lock στο άλλο) ή
    // fallback με warning — ποτέ σιωπηλά το TV stick.
    @Volatile private var tvStickName: String? = null
    @Volatile private var fmStickName: String? = null

    private fun rtlSticks(): List<UsbDevice> =
        (getSystemService(USB_SERVICE) as UsbManager).deviceList.values
            .filter { it.vendorId == 0x0bda }.sortedBy { it.deviceName }

    /** Καταγραφή ρόλων μετά από επιβεβαιωμένο DVB lock στο d. */
    private fun recordTvStick(d: DvbDevice?) {
        val name = (d as? DvbUsbDevice)?.usbDevice?.deviceName ?: return
        tvStickName = name
        fmStickName = rtlSticks().firstOrNull { it.deviceName != name }?.deviceName
        Log.i(TAG, "stick roles: TV=$tvStickName FM=$fmStickName")
    }

    /** v25: dot-matrix bargraph 8 κελιών — γεμάτα/άδεια μπλοκ. */
    private fun bar(v: Float, lo: Float, hi: Float, cells: Int = 8): String {
        val n = (((v - lo) / (hi - lo)) * cells).toInt().coerceIn(0, cells)
        return "▮".repeat(n) + "▯".repeat(cells - n)
    }

    private val fmTick = object : Runnable {
        override fun run() {
            if (!fmMode) return
            // Display emulation κατά τα standards (NRSC-G300-C §6.8-6.9, AN243,
            // RDS eBook Ch.6): δύο ΑΝΕΞΑΡΤΗΤΕΣ οθόνες, καμία ένωση/marquee.
            // PS = το τρέχον 8-char καρέ όπως το στέλνει ο encoder (2× confirmed
            // στον decoder) — quasi-static, αλλάζει με τον ρυθμό του σταθμού.
            // RT = ολόκληρο το 64-char field, 2× confirmed, μένει μέχρι το επόμενο.
            // v25 vintage: STEREO = ΚΟΚΚΙΝΟ λαμπάκι / mono = ΠΡΑΣΙΝΟ (v5
            // σύμβαση, αίτημα Pantelis 12/6)· CT σε δικό του άμπερ πεδίο
            val stereoOn = fmEngine?.stereoDetected() == true
            radioSt.text = if (stereoOn)
                "● STEREO %d%%".format(((fmEngine?.blend() ?: 0f) * 100).toInt())
            else "mono"
            radioSt.setTextColor(if (stereoOn) 0xFFFF5252.toInt() else 0xFF00C853.toInt())
            radioCt.text = fmEngine?.rdsCt() ?: ""
            // b102 signal status, v25 dot-matrix bargraphs (RF dBFS / RDS blk/s)
            // b105: AFC = εφαρμοσμένο trim + lock state (✓), ΟΧΙ το ωμό estimate
            val rf = fmEngine?.rfDb() ?: -99f
            val blk = fmEngine?.sigBlocksSec() ?: 0f
            val lk = fmEngine?.sigLock() ?: 0f
            val afcT = fmEngine?.afcTrimApplied() ?: 0L
            val afcL = fmEngine?.afcIsLocked() == true
            radioSig.text = String.format(Locale.US,
                "RF %s  RDS %s  lock %4.1f  AFC %s%+d Hz",
                bar(rf, -45f, -10f), bar(blk, 0f, 46.2f), lk,
                if (afcL) "✓" else "~", afcT)
            val ps = fmEngine?.rdsPsMsg() ?: ""
            radioPs.text = if (ps.isEmpty()) "        " else ps.padEnd(8).take(8)
            radioRt.text = fmEngine?.rdsRt() ?: ""
            // RT+ priority πάνω από raw RT (NRSC G300 p.41): tagged πεδία σε
            // δικές τους quasi-static γραμμές όταν ο σταθμός τα στέλνει
            val t = fmEngine?.rdsRtpTitle() ?: ""
            val a = fmEngine?.rdsRtpArtist() ?: ""
            radioRtpTitle.text = t
            radioRtpArtist.text = a
            radioRtpTitle.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
            radioRtpArtist.visibility = if (a.isEmpty()) View.GONE else View.VISIBLE
            ui.postDelayed(this, 300)
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val hideBanner = Runnable { banner.visibility = View.GONE }

    private lateinit var numOverlay: TextView
    private lateinit var subOverlay: TextView
    private val numBuffer = StringBuilder()
    private val commitNum = Runnable { commitNumber() }
    private val hideSubOverlay = Runnable { subOverlay.visibility = View.GONE }

    // stick βγήκε/μπήκε — teardown και auto-resume ΣΤΟ MODE ΠΟΥ ΕΙΣΑΙ.
    // build-82: πριν, το ATTACHED έπαιζε ΠΑΝΤΑ το last TV channel — replug με
    // ανοιχτό ράδιο σε εκτόξευε στην TV (οργή Pantelis, δικαίως).
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> deviceExec.execute {
                    tvStickName = null // b108: re-enumeration αλλάζει deviceNames
                    fmStickName = null
                    if (fmMode) fmEngine?.stop() else teardownDevice()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> deviceExec.execute {
                    tvStickName = null // b108: νέα enumeration = νέα ονόματα
                    fmStickName = null
                    // ΠΡΟΘΕΣΗ (pref), όχι τρέχον mode: αν το auto-FM απέτυχε σε
                    // stale stick, το fmMode είναι false αλλά ο χρήστης ΘΕΛΕΙ
                    // ράδιο — το ATTACHED (re-enumeration) είναι η ευκαιρία
                    // ανάκαμψης, όχι εισιτήριο για last TV channel «σαν σφαίρα».
                    val wantFm = fmMode || getSharedPreferences("dvbtv", MODE_PRIVATE)
                        .getBoolean("lastFm", false)
                    if (wantFm) enterFm()
                    else current?.let { play(it) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        videoLayout = findViewById(R.id.videoLayout)
        statusView = findViewById(R.id.status)
        channelList = findViewById(R.id.channelList)
        btnScan = findViewById(R.id.btnScan)
        leftPanel = findViewById(R.id.leftPanel)
        banner = findViewById(R.id.banner)
        bannerTitle = findViewById(R.id.bannerTitle)
        bannerSignal = findViewById(R.id.bannerSignal)
        bannerNow = findViewById(R.id.bannerNow)
        bannerNext = findViewById(R.id.bannerNext)
        bannerDesc = findViewById(R.id.bannerDesc)
        bannerProgress = findViewById(R.id.bannerProgress)
        numOverlay = findViewById(R.id.numOverlay)
        subOverlay = findViewById(R.id.subOverlay)
        btnFm = findViewById(R.id.btnFm)
        radioPanel = findViewById(R.id.radioPanel)
        radioFreq = findViewById(R.id.radioFreq)
        radioSt = findViewById(R.id.radioSt)
        radioCt = findViewById(R.id.radioCt)
        radioPs = findViewById(R.id.radioPs)
        radioRt = findViewById(R.id.radioRt)
        radioRtpTitle = findViewById(R.id.radioRtpTitle)
        radioRtpArtist = findViewById(R.id.radioRtpArtist)
        radioSig = findViewById(R.id.radioSig)
        videoLayout.keepScreenOn = true

        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_EXPORTED)
        else registerReceiver(usbReceiver, usbFilter)

        libVlc = LibVLC(this)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        channelList.adapter = adapter

        channels = ChannelStore.load(this)
        refreshList()

        btnScan.setOnClickListener { deviceExec.execute { scan() } }
        btnFm.setOnClickListener { deviceExec.execute { enterFm() } }
        channelList.setOnItemClickListener { _, _, pos, _ ->
            val ch = channels.getOrNull(pos) ?: return@setOnItemClickListener
            deviceExec.execute { play(ch) }
        }

        if (channels.isEmpty()) btnScan.requestFocus() else channelList.requestFocus()

        // auto-play του τελευταίου καναλιού — σαν κανονική TV
        // b104: cleanup στο ΚΑΘΕ startup (εντολή Pantelis 12/6) — κανένα
        // κατάλοιπο dump/capture/log αρχείο στο filesDir· μόνο το channels.json.
        deviceExec.execute {
            filesDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name != "channels.json") {
                    Log.i(TAG, "cleanup: ${f.name} (${f.length()} bytes)")
                    f.delete()
                }
            }
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        }

        val prefs = getSharedPreferences("dvbtv", MODE_PRIVATE)
        fmFreqHz = prefs.getLong("fmHz", 98_000_000L)
        if (prefs.getBoolean("lastFm", false)) {
            // έκλεισε στο ράδιο → άνοιξε στο ράδιο
            deviceExec.execute { enterFm() }
        } else {
            val lastSid = prefs.getInt("lastSid", -1)
            val lastFreq = prefs.getLong("lastFreq", -1L)
            channels.firstOrNull { it.sid == lastSid && it.freqHz == lastFreq }?.let { last ->
                deviceExec.execute { play(last) }
            }
        }
    }

    // ---- TV-style χειρισμός με το τηλεκοντρόλ ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // αριθμοί τηλεκοντρόλ → κανάλι με αυτό τον αριθμό (σε κάθε mode)
        val digit = when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        if (digit >= 0 && channels.isNotEmpty()) {
            if (numBuffer.length < 3) numBuffer.append(digit)
            numOverlay.text = numBuffer
            numOverlay.visibility = View.VISIBLE
            ui.removeCallbacks(commitNum)
            ui.postDelayed(commitNum, 1800)
            return true
        }
        // MENU (☰) ή κουμπί υποτίτλων → εναλλαγή subtitle track
        if ((keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_CAPTIONS) && player != null) {
            cycleSubs()
            return true
        }
        if (fmMode) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { fmStep(+100_000); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { fmStep(-100_000); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { fmStep(+1_000_000); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { fmStep(-1_000_000); return true }
                KeyEvent.KEYCODE_MENU -> { cycleFmGain(); return true }
                // b104: ppm ΚΑΤΑΡΓΗΘΗΚΕ (AFC το κάνει) — CH± = ±1 MHz όπως ▲▼
                // b121: CH± = RF gain ± (αίτημα Pantelis — «τις συχνότητες
                // τις πάω από τη ρόδα»)· το MENU μένει gain+ για συμβατότητα
                KeyEvent.KEYCODE_CHANNEL_UP -> { cycleFmGain(+1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { cycleFmGain(-1); return true }
                KeyEvent.KEYCODE_BACK -> { exitFm(); return true }
            }
            return super.onKeyDown(keyCode, event)
        }
        if (fullscreen) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(+1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // OK-κύκλος: κρυφό → banner → +περιγραφή → κρυφό
                    when {
                        banner.visibility != View.VISIBLE -> showBanner()
                        !bannerExpanded -> expandBanner()
                        else -> { ui.removeCallbacks(hideBanner); banner.visibility = View.GONE }
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (banner.visibility == View.VISIBLE) {
                        epgOffset++
                        refreshBanner()
                        repostHide()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (banner.visibility == View.VISIBLE) {
                        if (epgOffset > 0) epgOffset--
                        refreshBanner()
                        repostHide()
                    }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> { exitFullscreen(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun repostHide() {
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, if (bannerExpanded) BANNER_MS * 3 else BANNER_MS * 2)
    }

    private fun cycleSubs() {
        val p = player ?: return
        val tracks = p.spuTracks
        if (tracks == null || tracks.isEmpty()) {
            showSubOverlay("Χωρίς υπότιτλους στο κανάλι")
            return
        }
        val ids = tracks.map { it.id } // περιλαμβάνει το -1 (Disable)
        val idx = ids.indexOf(p.spuTrack)
        val nextId = ids[(idx + 1) % ids.size]
        p.setSpuTrack(nextId)
        val name = tracks.first { it.id == nextId }.name
        showSubOverlay(if (nextId == -1) "Υπότιτλοι: OFF" else "Υπότιτλοι: $name")
    }

    private fun showSubOverlay(s: String) {
        subOverlay.text = s
        subOverlay.visibility = View.VISIBLE
        ui.removeCallbacks(hideSubOverlay)
        ui.postDelayed(hideSubOverlay, 2500)
    }

    private fun commitNumber() {
        val n = numBuffer.toString().toIntOrNull()
        numBuffer.clear()
        numOverlay.visibility = View.GONE
        if (n == null) return
        if (fmMode) {
            // 984 → 98.4 MHz
            if (n in 875..1080) {
                fmFreqHz = n * 100_000L
                getSharedPreferences("dvbtv", MODE_PRIVATE).edit().putLong("fmHz", fmFreqHz).apply()
                val f = fmFreqHz
                deviceExec.execute { fmEngine?.retune(f) }
                updateFmDisplay()
            } else setStatus("FM: 875–1080 (π.χ. 984 = 98.4)")
            return
        }
        val ch = channels.firstOrNull { it.lcn == n }
        if (ch != null) deviceExec.execute { play(ch) }
        else setStatus("δεν υπάρχει κανάλι $n")
    }

    // ---- FM mode ----

    private fun enterFm() {
        try {
            stopPlayback()
            teardownDevice(quiet = true) // αφήνει το stick ελεύθερο για τον SDR driver
            // b108: με 2 sticks και άγνωστους ρόλους, DVB-lock probe ΠΡΙΝ το FM
            // — διασφαλίζει ότι το FM tune ΔΕΝ θα πάει στο DVB-T stick (Pantelis)
            ensureStickRoles()
            val engine = fmEngine ?: FmEngine { s -> setStatus(s) }.also { fmEngine = it }
            // build-82: retry — μετά από force-stop/restart το stick είναι συχνά
            // stale και η 1η προσπάθεια σκάει· πριν, μία εξαίρεση = παρατημένος
            // στην TV λίστα αντί για ράδιο.
            var fail: Exception? = null
            for (att in 1..3) {
                try {
                    setStatus(if (att == 1) "FM: άνοιγμα stick..." else "FM: ξαναπροσπάθεια $att/3...")
                    engine.start(this, fmFreqHz, fmStickName)
                    fail = null
                    break
                } catch (e: Exception) {
                    fail = e
                    Log.e(TAG, "fm attempt $att failed", e)
                    try { engine.stop() } catch (_: Exception) {}
                    Thread.sleep(1000)
                }
            }
            if (fail != null) throw fail
            fmMode = true
            getSharedPreferences("dvbtv", MODE_PRIVATE).edit().putBoolean("lastFm", true).apply()
            runOnUiThread {
                leftPanel.visibility = View.GONE
                banner.visibility = View.GONE
                radioPanel.visibility = View.VISIBLE
                updateFmDisplay()
                ui.removeCallbacks(fmTick)
                ui.postDelayed(fmTick, 1000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fm failed", e)
            setStatus("FM ERROR: ${e.message}")
        }
    }

    private fun exitFm() {
        fmMode = false
        getSharedPreferences("dvbtv", MODE_PRIVATE).edit().putBoolean("lastFm", false).apply()
        ui.removeCallbacks(fmTick)
        deviceExec.execute { fmEngine?.stop() }
        radioPanel.visibility = View.GONE
        leftPanel.visibility = View.VISIBLE
        fullscreen = false
        channelList.requestFocus()
        setStatus("${channels.size} κανάλια")
    }

    private fun fmStep(d: Long) {
        fmFreqHz = (fmFreqHz + d).coerceIn(87_500_000L, 108_000_000L)
        getSharedPreferences("dvbtv", MODE_PRIVATE).edit().putLong("fmHz", fmFreqHz).apply()
        val f = fmFreqHz
        deviceExec.execute { fmEngine?.retune(f) } // socket I/O ΕΚΤΟΣ main thread (NetworkOnMainThreadException)
        updateFmDisplay()
    }

    private fun cycleFmGain(dir: Int = 1) {
        // b104: βήμα στον πραγματικό πίνακα gains του tuner (dB) — όχι ποσοστά
        deviceExec.execute {
            val label = fmEngine?.cycleGain(dir) ?: return@execute
            runOnUiThread { showSubOverlay(label) }
        }
    }

    private fun updateFmDisplay() {
        radioFreq.text = String.format(Locale.US, "%.1f", fmFreqHz / 1e6)
    }

    private fun teardownDevice(quiet: Boolean = false) {
        stopPlayback()
        tsSink = null
        tsRouter?.interrupt()
        tsRouter = null
        try { tsStream?.close() } catch (_: IOException) {}
        tsStream = null
        try { device?.close() } catch (_: IOException) {}
        device = null
        if (!quiet) setStatus("το stick βγήκε — βάλ' το ξανά και ξαναδιάλεξε κανάλι")
    }

    private fun zap(dir: Int) {
        val list = channels
        if (list.isEmpty()) return
        val cur = current
        val idx = list.indexOfFirst { it.sid == cur?.sid && it.freqHz == cur.freqHz }
        val next = list[((if (idx < 0) 0 else idx) + dir + list.size) % list.size]
        deviceExec.execute { play(next) }
    }

    private fun enterFullscreen() {
        fullscreen = true
        leftPanel.visibility = View.GONE
    }

    private fun exitFullscreen() {
        fullscreen = false
        leftPanel.visibility = View.VISIBLE
        banner.visibility = View.GONE
        ui.removeCallbacks(hideBanner)
        // στάσου πάνω στο κανάλι που παίζει, όχι στην κορυφή
        val cur = current
        val idx = channels.indexOfFirst { it.sid == cur?.sid && it.freqHz == cur?.freqHz }
        if (idx >= 0) channelList.setSelection(idx)
        channelList.requestFocus()
    }

    private fun showBanner() {
        bannerExpanded = false
        epgOffset = 0
        bannerDesc.visibility = View.GONE
        refreshBanner()
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, BANNER_MS)
    }

    private fun expandBanner() {
        bannerExpanded = true
        bannerDesc.visibility = View.VISIBLE
        refreshBanner()
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, BANNER_MS * 3) // με την περιγραφή θέλει χρόνο να διαβαστεί
    }

    private fun refreshBanner() {
        val ch = current ?: return
        bannerTitle.text = "${ch.lcn}  ${ch.name}"
        bannerSignal.text = lastSignal
        val evs = eit.events(ch.sid)
        if (epgOffset > evs.size - 1) epgOffset = (evs.size - 1).coerceAtLeast(0)
        val sel = evs.getOrNull(epgOffset)
        if (sel != null) {
            val label = when (epgOffset) {
                0 -> "Τώρα"
                1 -> "Μετά"
                else -> "+$epgOffset"
            }
            bannerNow.text = "$label  ${fmtEvTime(sel.startUtcSec)}–${fmtEvTime(sel.endUtcSec)}   ${sel.name}"
            if (epgOffset == 0 && sel.durSec > 0) {
                bannerProgress.progress =
                    ((System.currentTimeMillis() / 1000 - sel.startUtcSec) * 100 / sel.durSec).toInt().coerceIn(0, 100)
                bannerProgress.visibility = View.VISIBLE
            } else {
                bannerProgress.visibility = View.GONE
            }
        } else {
            bannerNow.text = "Τώρα  —"
            bannerProgress.visibility = View.GONE
        }
        val after = evs.getOrNull(epgOffset + 1)
        bannerNext.text = after?.let { "Έπειτα  ${fmtEvTime(it.startUtcSec)}   ${it.name}" } ?: ""
        if (bannerExpanded)
            bannerDesc.text = sel?.desc?.ifBlank { null } ?: "(χωρίς περιγραφή)"
    }

    // ώρα τοπική· αν δεν είναι σήμερα, δείξε και μέρα (π.χ. "Τετ 21:00")
    private fun fmtEvTime(utcSec: Long): String {
        val d = Date(utcSec * 1000)
        val dayFmt = SimpleDateFormat("yyyyDDD", Locale.getDefault())
        val sameDay = dayFmt.format(d) == dayFmt.format(Date())
        return if (sameDay) SimpleDateFormat("HH:mm", Locale.getDefault()).format(d)
        else SimpleDateFormat("EEE HH:mm", Locale("el")).format(d)
    }

    private fun refreshList() {
        // ίδιο κανάλι από 2 πομπούς → δείξε και τη συχνότητα να ξεχωρίζουν
        val dupKeys = channels.groupingBy { "${it.sid}/${it.name}" }.eachCount().filterValues { it > 1 }.keys
        adapter.clear()
        adapter.addAll(channels.map {
            val base = if (it.lcn > 0) "${it.lcn}.  ${it.name}" else it.name
            if ("${it.sid}/${it.name}" in dupKeys) "$base  (${it.freqHz / 1_000_000})" else base
        })
        adapter.notifyDataSetChanged()
        if (channels.isNotEmpty()) setStatus("${channels.size} κανάλια")
    }

    private fun setStatus(s: String) = runOnUiThread { statusView.text = s }

    // ---- device: open once, stream once, router forever ----

    private fun dev(avoid: String? = null): DvbDevice? {
        device?.let { return it }
        setStatus("opening device...")
        val found = DvbUsbDeviceRegistry.getUsbDvbDevices(applicationContext)
        if (found.isEmpty()) {
            setStatus("no DVB USB device found")
            return null
        }
        // b104/b108: 2 ίδια sticks — η TV προτιμά το γνωστό TV stick (DVB-lock
        // probe) και αποφεύγει το FM stick / serial / avoid (no-lock failover)·
        // δοκιμή με τη σειρά (busy/λάθος αποτυγχάνει στο open → επόμενο).
        val fmSerial = getSharedPreferences("dvbtv", MODE_PRIVATE)
            .getString("fmSerial", "FM000001")
        val ordered = found.sortedBy { dvb ->
            val u = (dvb as? DvbUsbDevice)?.usbDevice
            val sn = try { u?.serialNumber } catch (e: Exception) { null }
            var rank = 0
            if (u?.deviceName == tvStickName) rank -= 4 // επιβεβαιωμένο TV: πρώτο
            if (sn == fmSerial) rank += 2
            if (u?.deviceName == fmStickName) rank += 4 // probed FM: τελευταίο
            if (u?.deviceName == avoid) rank += 8       // μόλις απέτυχε σε lock
            rank
        }
        var d: DvbDevice? = null
        var lastErr: Exception? = null
        for (cand in ordered) {
            try {
                cand.open() // μπλοκάρει στο USB permission dialog αν χρειάζεται
                d = cand
                break
            } catch (e: Exception) {
                Log.w(TAG, "DVB open failed (${cand.debugString}): ${e.message} — επόμενο stick")
                lastErr = e
            }
        }
        if (d == null) {
            setStatus("DVB open failed: ${lastErr?.message}")
            return null
        }

        val ts = d.getTransportStream(object : DvbDevice.StreamCallback {
            override fun onStreamException(exception: IOException) {
                Log.e(TAG, "stream exception", exception)
                setStatus("stream error: ${exception.message}")
            }

            override fun onStoppedStreaming() {
                Log.i(TAG, "streaming stopped")
            }
        })
        tsStream = ts

        tsRouter = Thread {
            val buf = ByteArray(65536)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val n = ts.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        try {
                            tsSink?.invoke(buf, n)
                        } catch (e: IOException) {
                            // π.χ. ο VLC έκλεισε το pipe — απλώς σταματάμε να ταΐζουμε
                            Log.i(TAG, "sink closed: ${e.message}")
                            tsSink = null
                        }
                    }
                }
            } catch (e: IOException) {
                Log.i(TAG, "ts router ended: ${e.message}")
            }
        }.apply {
            name = "TsRouter"
            priority = Thread.MAX_PRIORITY
            start()
        }

        device = d
        setStatus("open: ${d.debugString}")
        return d
    }

    private fun waitLock(dev: DvbDevice, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                if (dev.status.contains(DvbStatus.FE_HAS_LOCK)) return true
            } catch (e: Exception) { /* frontend όχι έτοιμο ακόμα */ }
            Thread.sleep(100)
        }
        return false
    }

    /**
     * b108 probe: κλειδώνει το συγκεκριμένο stick σε γνωστό DVB-T mux;
     * open→tune→status→close ΜΟΝΟ — ποτέ getTransportStream (το αρχιτεκτονικό
     * gotcha του driver ζει στο stream lifecycle, όχι στο frontend).
     */
    private fun probeDvbLock(name: String, freqHz: Long): Boolean {
        var d: DvbDevice? = null
        return try {
            d = DvbUsbDeviceRegistry.getUsbDvbDevices(applicationContext).firstOrNull {
                (it as? DvbUsbDevice)?.usbDevice?.deviceName == name
            } ?: return false
            d.open()
            d.tune(freqHz, BANDWIDTH_HZ, DeliverySystem.DVBT)
            val lock = waitLock(d, 3000)
            Log.i(TAG, "probe $name @${freqHz / 1_000_000}MHz lock=$lock")
            lock
        } catch (e: Exception) {
            Log.w(TAG, "probe $name failed: ${e.message}")
            false
        } finally {
            try { d?.close() } catch (_: Exception) {}
        }
    }

    /**
     * b108: ταυτοποίηση ρόλων με DVB-lock probe όταν υπάρχουν 2 sticks και οι
     * ρόλοι είναι άγνωστοι. Θετική απόδειξη μόνο: όποιο κλειδώσει = TV.
     * Κανένα lock (πεσμένο UHF σήμα;) = μένει άγνωστο → fallback + warning.
     */
    private fun ensureStickRoles() {
        if (tvStickName != null) return
        val cands = rtlSticks()
        if (cands.size < 2) return
        val muxHz = channels.firstOrNull()?.freqHz ?: return // pre-scan: άγνωστο mux
        setStatus("ταυτοποίηση sticks (DVB probe)...")
        for (c in cands) {
            if (probeDvbLock(c.deviceName, muxHz)) {
                tvStickName = c.deviceName
                fmStickName = cands.first { it.deviceName != c.deviceName }.deviceName
                Log.i(TAG, "stick roles (probe): TV=$tvStickName FM=$fmStickName")
                return
            }
        }
        Log.w(TAG, "stick probe: ΚΑΝΕΝΑ DVB lock — ρόλοι άγνωστοι, fallback")
        setStatus("προσοχή: αδύνατη ταυτοποίηση sticks (κανένα DVB lock)")
    }

    // ---- scan ----

    private fun scan() {
        try {
            var d = dev() ?: return
            stopPlayback()
            channels = emptyList()
            runOnUiThread { adapter.clear(); adapter.notifyDataSetChanged() }
            val found = ArrayList<ChannelStore.Channel>()
            val parser = TsPsiParser()
            var anyLock = false
            var retried = false
            var i = 0
            while (i < MUX_MHZ.size) {
                val mhz = MUX_MHZ[i]
                setStatus("scan $mhz MHz...")
                d.tune(mhz * 1_000_000L, BANDWIDTH_HZ, DeliverySystem.DVBT)
                d.disablePidFilter()
                if (!waitLock(d, 2500)) {
                    setStatus("scan $mhz: no lock")
                    // b108: 2 πρώτα muxes χωρίς lock σε 2-stick setup → μάλλον
                    // κρατάμε το FM stick (λάθος κεραία) — άλλαξε ΜΙΑ φορά
                    if (!anyLock && !retried && i >= 1 && rtlSticks().size >= 2) {
                        retried = true
                        val tried = (d as? DvbUsbDevice)?.usbDevice?.deviceName
                        Log.w(TAG, "scan: κανένα lock στο $tried — δοκιμή του άλλου stick")
                        setStatus("scan: δοκιμή 2ου stick...")
                        teardownDevice(quiet = true)
                        d = dev(avoid = tried) ?: return
                        i = 0
                        continue
                    }
                    i++
                    continue
                }
                if (!anyLock) {
                    anyLock = true
                    recordTvStick(d) // b108: πρώτο lock = επιβεβαιωμένος TV ρόλος
                }
                parser.reset()
                tsSink = { b, n -> parser.feed(b, n) }
                val deadline = System.currentTimeMillis() + 5000
                while (!parser.complete && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50)
                }
                tsSink = null
                val snr = try { d.readSnr() } catch (e: Exception) { 0 }
                val svcs = parser.tvServices()
                for (s in svcs)
                    found.add(ChannelStore.Channel(s.name, s.sid, mhz * 1_000_000L, 0, snr))
                setStatus("scan $mhz: ${svcs.size} κανάλια")
                Log.i(TAG, "scan $mhz MHz snr=$snr: ${svcs.joinToString { "${it.name}(${it.sid})" }}")
                i++
            }
            // κρατάμε ΚΑΙ τους 2 πομπούς για τα ίδια κανάλια (538 & 578) — επιλογή Pantelis.
            // Αρίθμηση: αύξων αριθμός με τη σειρά του scan, όπως το Windows dvb_tv.
            channels = found.mapIndexed { i, c -> c.copy(lcn = i + 1) }
            ChannelStore.save(this, channels)
            runOnUiThread {
                refreshList()
                setStatus("scan done: ${channels.size} κανάλια")
                if (channels.isNotEmpty()) channelList.requestFocus()
            }
        } catch (e: Exception) {
            Log.e(TAG, "scan failed", e)
            setStatus("scan ERROR: ${e.message}")
        }
    }

    // ---- playback ----

    private fun play(ch: ChannelStore.Channel) {
        try {
            var d = dev() ?: return
            stopPlayback()
            setStatus("tuning ${ch.name}...")
            current = ch
            getSharedPreferences("dvbtv", MODE_PRIVATE).edit()
                .putInt("lastSid", ch.sid).putLong("lastFreq", ch.freqHz).apply()
            d.tune(ch.freqHz, BANDWIDTH_HZ, DeliverySystem.DVBT)
            d.disablePidFilter()
            if (waitLock(d, 2500)) {
                recordTvStick(d) // b108: επιβεβαιωμένος TV ρόλος
            } else {
                // b108 failover: no lock + 2 sticks = ίσως ανοίξαμε το FM stick
                // (λάθος κεραία) → κλείσε, δοκίμασε το άλλο, κατέγραψε ρόλους
                val tried = (d as? DvbUsbDevice)?.usbDevice?.deviceName
                if (rtlSticks().size >= 2 && tried != null && tried != tvStickName) {
                    Log.w(TAG, "no lock στο $tried — δοκιμή του άλλου stick")
                    setStatus("no lock — δοκιμή 2ου stick...")
                    teardownDevice(quiet = true)
                    d = dev(avoid = tried) ?: return
                    d.tune(ch.freqHz, BANDWIDTH_HZ, DeliverySystem.DVBT)
                    d.disablePidFilter()
                    if (waitLock(d, 2500)) recordTvStick(d)
                }
            }
            eit.reset()

            // νέο pipe προς VLC, το ταΐζει ο router (+ EIT parser στο ίδιο stream)
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            vlcWrite = writeSide
            tsSink = { b, n ->
                writeSide.write(b, 0, n)
                eit.feed(b, n)
            }

            runOnUiThread {
                val vlc = libVlc ?: return@runOnUiThread
                val media = Media(vlc, readSide.fileDescriptor)
                media.setHWDecoderEnabled(true, false)
                media.addOption(":file-caching=1500")
                media.addOption(":live-caching=1500")
                media.addOption(":program=${ch.sid}")
                // υπότιτλοι: default OFF — ανάβουν μόνο χειροκίνητα με MENU (cycleSubs)
                val p = MediaPlayer(vlc)
                p.attachViews(videoLayout, null, true, false) // true = subtitle surface
                p.media = media
                media.release()
                player = p
                p.play()
                p.volume = 100 // libvlc: 100 = nominal/0 dB (unity, κανένα software boost)
                enterFullscreen()
                showBanner()
            }
            startStatusLoop(d, ch)
        } catch (e: Exception) {
            Log.e(TAG, "play failed", e)
            setStatus("play ERROR: ${e.message}")
        }
    }

    private fun stopPlayback() {
        statusThread?.interrupt()
        statusThread = null
        tsSink = null
        try { vlcWrite?.close() } catch (_: IOException) {}
        vlcWrite = null
        runOnUiThread {
            player?.stop()
            player?.detachViews()
            player?.release()
            player = null
        }
    }

    private fun startStatusLoop(dev: DvbDevice, ch: ChannelStore.Channel) {
        statusThread = Thread {
            val mhz = ch.freqHz / 1_000_000
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val snr = dev.readSnr()
                    val rf = dev.readRfStrengthPercentage()
                    val ber = dev.readBitErrorRate()
                    val dropped = dev.readDroppedUsbFps()
                    val lock = if (dev.status.contains(DvbStatus.FE_HAS_LOCK)) "LOCK" else "----"
                    lastSignal = "${mhz}MHz $lock snr=$snr rf=$rf% ber=$ber drop=$dropped"
                    setStatus("▶ ${ch.name}  $lastSignal")
                    runOnUiThread { if (banner.visibility == View.VISIBLE) refreshBanner() }
                } catch (e: Exception) {
                    Log.w(TAG, "status read failed: ${e.message}")
                }
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }.apply {
            name = "DvbStatus"
            isDaemon = true
            start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(hideBanner)
        ui.removeCallbacks(commitNum)
        ui.removeCallbacks(hideSubOverlay)
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        fmEngine?.stop()
        deviceExec.shutdownNow()
        statusThread?.interrupt()
        tsSink = null
        tsRouter?.interrupt()
        player?.stop()
        player?.detachViews()
        player?.release()
        libVlc?.release()
        try { vlcWrite?.close() } catch (_: IOException) {}
        try { tsStream?.close() } catch (_: IOException) {}
        try { device?.close() } catch (_: IOException) {}
    }
}
