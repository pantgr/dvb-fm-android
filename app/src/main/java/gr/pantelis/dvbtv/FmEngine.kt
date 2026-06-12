package gr.pantelis.dvbtv

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.sdrtouch.core.SdrTcpArguments
import com.sdrtouch.core.devices.SdrDevice
import com.sdrtouch.rtlsdr.driver.RtlSdrDevice
import java.net.InetSocketAddress
import java.net.Socket

/**
 * FM ραδιόφωνο μέσω rtl_tcp_andro: ο RtlSdrDevice σηκώνει rtl_tcp server σε
 * localhost (SDR mode — raw I/Q, ίδιος μηχανισμός με το FM του Realtek Windows
 * driver απ' όπου γεννήθηκε το rtl-sdr), εμείς διαβάζουμε το I/Q stream και
 * στέλνουμε commands. rtl_tcp protocol out: 1 byte cmd + u32 big-endian
 * (0x01 freq, 0x02 samplerate, 0x03 tuner gain mode, 0x08 rtl agc).
 */
class FmEngine(private val onStatus: (String) -> Unit) {
    companion object {
        private const val TAG = "DvbTvFm"
        private const val PORT = 14550

        // rtl_fm recipe: capture 960k, tuner fs/4 ψηλότερα από τον σταθμό (DC spike dodge)
        const val SAMPLE_RATE = FmDemod.IN_RATE.toLong()
        const val TUNE_OFFSET = FmDemod.TUNE_OFFSET.toLong()

        // b104: gains σε ΔΕΚΑΤΑ dB ανά tuner (librtlsdr.c rtlsdr_get_tuner_gains —
        // ίδια source of truth με τον driver)· επιλογή με TCP_SET_TUNER_GAIN_BY_ID
        // (0x0d, index στη λίστα — ο driver κάνει bounds check). Τέλος το 0x7f
        // percentage που γέννησε το out-of-bounds (b73) και το «90% = overload
        // στον R820T» (44.5dB με FM κεραία ενώ το καλό είναι ~14dB, μέτρηση 12/6).
        private val TUNER_NAMES = arrayOf("UNKNOWN", "E4000", "FC0012", "FC0013", "FC2580", "R820T", "R828D")
        private val E4K_GAINS = intArrayOf(-10, 15, 40, 65, 90, 115, 140, 165, 190, 215, 240, 290, 340, 420)
        private val FC0012_GAINS = intArrayOf(-99, -40, 71, 179, 192)
        private val FC0013_GAINS = intArrayOf(-99, -73, -65, -63, -60, -58, -54, 58, 61,
            63, 65, 67, 68, 70, 71, 179, 181, 182, 184, 186, 188, 191, 197)
        private val R82XX_GAINS = intArrayOf(0, 9, 14, 27, 37, 77, 87, 125, 144, 157,
            166, 197, 207, 229, 254, 280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496)

        init {
            // κανονικά το κάνει ο RtlSdrDeviceProvider που παρακάμπτουμε
            System.loadLibrary("rtlSdrAndroid")
        }
    }

    private var sdr: SdrDevice? = null
    private var socket: Socket? = null
    private var pump: Thread? = null
    private var audioThread: Thread? = null
    private var rdsThread: Thread? = null
    private var hub: RawHub? = null
    private var demod: FmDemod? = null
    private var rds: RdsDecoder? = null

    // b100 AFC controller — port του SDRangel freqtrackersink.tick() (γρ.351-393,
    // μελέτη STUDY.md): 2ο EMA ανά 50ms (gated στο pilot = squelch τους),
    // διόρθωση ανά 500ms με deadband rate/1000 και decay cooldown. Διόρθωση =
    // hardware micro-retune (δικαιολόγηση: 1 σταθμός/tuner + FC0012 PLL βήμα
    // ~14Hz στην FM μπάντα, μετρημένο από tuner_fc0012.c:241) — FM mode ΜΟΝΟ.
    private var curFreqHz = 0L
    @Volatile
    private var afcTrim = 0L // διαβάζεται και από το UI tick (afcTrimApplied)
    private var afcAvg = 0.0
    private var afcTickMs = 0L
    private var afcStepMs = 0L
    private var afcLogMs = 0L // b109: περιοδικό status (το NCO following ήταν αόρατο στο log)

    // b105 acquire→lock FSM (απαίτηση Pantelis: «διορθώσεις σε μερικούς μικρούς
    // κύκλους» και μετά κλείδωμα — όχι αέναο κυνήγι του θορύβου προγράμματος,
    // το discriminator DC κουνιέται ±350Hz με τη μουσική):
    //   ACQUIRE: κάθε 3s (2s settle + 1s μέτρηση) διόρθωση = bias-corrected EMA
    //            (Adam-style /(1−(1−α)^n) — αλλιώς η EMA από μηδενισμό υποτιμά
    //            και θέλει 12 κύκλους)· όταν |residual| < 300Hz → LOCK.
    //   LOCK:    κλειδωμένο στον carrier και τον ΑΚΟΛΟΥΘΕΙ ΟΜΑΛΑ (απαίτηση
    //            Pantelis): integrator → NCO του RawHub (συνεχές, ψηφιακό,
    //            glitch-free)· το hardware δεν ξανακουνιέται. Re-acquire μόνο
    //            αν ο πομπός πηδήξει >2kHz (rebase NCO → trim, coarse ξανά).
    private val afcAlpha = 0.02                 // EMA ανά 50ms tick → τ≈2.5s
    private val afcLockBand = 300.0             // residual για LOCK (πάνω από τον θόρυβο EMA)
    private val afcTrackG = 0.01                // integrator gain/tick (≈0.2/s, τ≈5s)
    private val afcReacqHz = 2_000.0            // έξοδος από LOCK: πραγματικό άλμα μόνο
    // b107: 0.02→0.05 — στους 88.6 (κενή/αδύναμη συχνότητα) ο FM θόρυβος
    // περνούσε το 0.02 (ψευτο-pilot) και το AFC κυνηγούσε ±12.5k. Πραγματικά
    // stereo stations: pilot ~0.1-0.175 — το 0.05 τα κρατάει όλα. Mono (χωρίς
    // pilot) = χωρίς AFC, σωστά: το AFC κλειδώνει σε ΣΤΑΘΜΟ, όχι σε θόρυβο.
    private val afcPilotGate = 0.05f            // squelch-equivalent: υπάρχει σταθμός;
    private val afcTrimMax = 50_000L            // hold range
    private var afcTicks = 0                    // ticks από το τελευταίο reset (bias corr.)

    @Volatile
    private var afcLockedV = false
    fun afcIsLocked(): Boolean = afcLockedV
    /** Συνολική εφαρμοσμένη διόρθωση: hardware trim + ψηφιακό NCO. */
    fun afcTrimApplied(): Long = afcTrim + (hub?.ncoTargetHz?.toLong() ?: 0L)

    // b104: ταυτότητα tuner από το rtl_tcp dongle header ("RTL0"+type+gains)
    @Volatile
    var tunerType = 0
        private set
    private var gains = intArrayOf(0)           // δέκατα dB, πίνακας του tuner
    private var gainIdx = 0
    // b117 test verdict (Pantelis, live): ο R820T hardware AGC κρατάει τη στάθμη
    // «σωστή αριθμητικά» αλλά ΣΠΛΑΤΑΡΕΙ (gain pumping = πολλαπλασιαστική
    // παραμόρφωση) → stereo φύσημα + RDS lock στο πάτωμα· manual gain = τζάμι.
    // b118: MANUAL default (pref), το auto μένει διαθέσιμο μόνο για πειράματα.
    @Volatile
    private var agcAuto = false
    private var prefs: android.content.SharedPreferences? = null

    @Volatile
    var running = false
        private set

    fun tunerName(): String = TUNER_NAMES.getOrElse(tunerType) { "?" }
    fun gainDb(): Float = gains.getOrElse(gainIdx) { 0 } / 10f

    /** preferName (b108): το stick που ταυτοποίησε το DVB-lock probe ως FM. */
    fun start(ctx: Context, freqHz: Long, preferName: String? = null) {
        stop()
        curFreqHz = freqHz
        afcTrim = 0L
        afcAvg = 0.0
        afcTicks = 0
        afcLockedV = false
        afcTickMs = System.currentTimeMillis()
        afcStepMs = afcTickMs
        prefs = ctx.getSharedPreferences("dvbtv", Context.MODE_PRIVATE)
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        // b104: 2 ΠΑΝΟΜΟΙΟΤΥΠΑ μαύρα sticks (R820T, εργοστασιακό serial
        // '00000001' και στα δύο) — σταθερή ταυτοποίηση μόνο με δικό μας
        // EEPROM serial (rtl_eeprom -s FM000001 στο stick της FM κεραίας).
        // Επιλογή: serial match πρώτα → fmDevIdx pref fallback. Όποιο κρατάει
        // η TV είναι busy και αποτυγχάνει στο open → φυσικό fallback.
        val cands = usb.deviceList.values
            .filter { it.vendorId == 0x0bda }
            .sortedBy { it.deviceName }
        if (cands.isEmpty()) throw RuntimeException("δεν βρέθηκε RTL stick")
        val wantSerial = prefs!!.getString("fmSerial", "FM000001")
        var prefIdx = -1
        for ((i, c) in cands.withIndex()) {
            val sn = try { c.serialNumber } catch (e: Exception) { "n/a" }
            Log.i(TAG, "USB[$i] ${c.deviceName} prod='${c.productName}' serial='$sn'")
            if (prefIdx < 0 && sn == wantSerial) prefIdx = i
        }
        // b108: το probe (DVB-lock identity) υπερισχύει όλων
        if (preferName != null) {
            val i = cands.indexOfFirst { it.deviceName == preferName }
            if (i >= 0) prefIdx = i
        }
        if (prefIdx < 0) prefIdx = prefs!!.getInt("fmDevIdx", 0).coerceIn(0, cands.size - 1)
        val dev = cands[prefIdx]
        Log.i(TAG, "FM stick: USB[$prefIdx] ${dev.deviceName}")

        val rtl = RtlSdrDevice(ctx, dev)
        rtl.addOnStatusListener(object : SdrDevice.OnStatusListener {
            override fun onOpen(sdrDevice: SdrDevice) {
                Log.i(TAG, "sdr open")
            }

            override fun onClosed(e: Throwable?) {
                Log.i(TAG, "sdr closed: ${e?.message}")
                if (running) onStatus("FM: ο driver έκλεισε (${e?.message ?: "ok"})")
            }
        })
        sdr = rtl
        // b104: χωρίς -g (το σωστό gain μπαίνει με 0x0d μόλις μάθουμε τον tuner)
        // και χωρίς -P (ppm ΚΑΤΑΡΓΗΘΗΚΕ — το +34 ήταν μέτρηση του μπλε stick,
        // λάθος για κάθε άλλο· το AFC απορροφά πλέον κάθε σταθερό RF offset).
        rtl.openAsync(SdrTcpArguments.fromString("-a 127.0.0.1 -p $PORT -s $SAMPLE_RATE -f ${freqHz + TUNE_OFFSET}"))

        // ο server σηκώνεται async (μετά το USB permission) — retry connect
        var s: Socket? = null
        for (i in 0 until 50) {
            try {
                s = Socket().also { it.connect(InetSocketAddress("127.0.0.1", PORT), 400) }
                break
            } catch (e: Exception) {
                Thread.sleep(200)
            }
        }
        socket = s ?: throw RuntimeException("ο rtl_tcp server δεν σηκώθηκε")
        s.tcpNoDelay = true

        // b104: dongle header ΣΥΓΧΡΟΝΑ εδώ (το στέλνει αμέσως στο accept):
        // "RTL0" + tuner type u32 BE + gain count u32 BE. Πριν, διαβαζόταν και
        // ΠΕΤΙΟΤΑΝ στο pump — εκεί ζούσε η ταυτότητα που ψάχναμε.
        val hdr = ByteArray(12)
        var got = 0
        while (got < 12) {
            val n = s.getInputStream().read(hdr, got, 12 - got)
            if (n < 0) throw RuntimeException("rtl_tcp header EOF")
            got += n
        }
        tunerType = ((hdr[4].toInt() and 0xFF) shl 24) or ((hdr[5].toInt() and 0xFF) shl 16) or
                ((hdr[6].toInt() and 0xFF) shl 8) or (hdr[7].toInt() and 0xFF)
        gains = when (tunerType) {
            1 -> E4K_GAINS
            2 -> FC0012_GAINS
            3 -> FC0013_GAINS
            5, 6 -> R82XX_GAINS
            else -> intArrayOf(0)
        }
        // default ανά tuner: R82xx → 14.4dB (το «30%» που μετρήθηκε καλό με την
        // FM κεραία 12/6 — το 44.5dB ήταν overload, lock 1.5 → 3.6 στο live log)·
        // FC0012 → 19.2dB max (το χρυσό b70-77 config)· αλλιώς τελευταίο entry.
        val defIdx = when (tunerType) {
            5, 6 -> 8
            2 -> FC0012_GAINS.size - 1
            else -> gains.size - 1
        }
        gainIdx = prefs!!.getInt("fmGainIdx_t$tunerType", defIdx).coerceIn(0, gains.size - 1)
        Log.i(TAG, "tuner=${tunerName()} gains=${gains.size} gainIdx=$gainIdx (${gainDb()} dB)")

        running = true
        // RawHub αρχιτεκτονική: pump → front-end → ωμό MPX ring· ο ήχος και το
        // RDS = ανεξάρτητα services, καθένα με δικό του thread+Reader στο ΙΔΙΟ
        // raw. Κανένα κοινό state — το στέρεο δεν μπορεί να ξανασπάσει το RDS.
        val h = RawHub()
        val d = FmDemod()
        val r = RdsDecoder()
        hub = h
        demod = d
        rds = r
        pump = Thread {
            try {
                val input = s.getInputStream() // header ήδη διαβασμένο στο start()
                val buf = ByteArray(16384)
                while (running) {
                    val n = input.read(buf)
                    if (n < 0) break
                    h.feedIq(buf, n)
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "fm pump: ${e.message}")
            }
        }.apply {
            name = "FmPump"
            priority = Thread.MAX_PRIORITY
            start()
        }
        audioThread = Thread {
            val reader = h.attach()
            val chunk = FloatArray(8192)
            try {
                d.start()
                while (running) {
                    val n = reader.read(chunk)
                    if (n == 0) {
                        Thread.sleep(5) // ~850 samples @171k — αναπνοή του ring
                        continue
                    }
                    // v24 blend driver: RSSI antenna-referred (το «IF level meter»
                    // του TDA1591 pin16) — hub measurement, κανονικοποιημένο gain.
                    // b117: σε hardware AGC το πραγματικό gain είναι άγνωστο →
                    // ωμό rfDb (το AGC το κρατάει ~σταθερό = ήρεμο blend driver)
                    d.signalDb = h.rfDb - (if (agcAuto) 0f else gainDb())
                    for (k in 0 until n) d.processMpx(chunk[k])
                    d.flushPcm()
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "audio svc: ${e.message}")
            } finally {
                d.stop()
            }
        }.apply {
            name = "FmAudioSvc"
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
        rdsThread = Thread {
            val reader = h.attach()
            val chunk = FloatArray(8192)
            var lastDropLog = 0L
            try {
                while (running) {
                    val n = reader.read(chunk)
                    if (n == 0) {
                        Thread.sleep(5)
                        continue
                    }
                    for (k in 0 until n) r.process(chunk[k], 0f)
                    if (reader.drops != lastDropLog) { // ring overrun = αλλοίωση bits
                        lastDropLog = reader.drops
                        Log.w("DvbTvRds", "rds reader drops=${reader.drops}")
                    }
                    // b105 AFC FSM: acquire (μικροί κύκλοι/3s) → lock (σιωπή).
                    val now = System.currentTimeMillis()
                    if (now - afcTickMs >= 50) {
                        afcTickMs = now
                        r.psTick(now) // b115: PS confirm-ή-timeout + dwell publisher
                        val settled = now - afcStepMs > 2_000 // τύφλωση μετά από retune
                        if (settled && d.pilotLevelDbg > afcPilotGate) {
                            afcAvg = afcAlpha * h.afcFreqHz + (1.0 - afcAlpha) * afcAvg
                            afcTicks++
                        }
                        // bias-corrected εκτίμηση (EMA από reset υποτιμά κατά 1−(1−α)^n)
                        val est = if (afcTicks == 0) 0.0
                            else afcAvg / (1.0 - Math.pow(1.0 - afcAlpha, afcTicks.toDouble()))
                        val mag = if (est < 0) -est else est
                        // b109: το NCO following ήταν αόρατο στο log — darkness.
                        // b110: +MHz — χωρίς συχνότητα στη γραμμή, αποδώσαμε log
                        // σε λάθος σταθμό (13:18 12/6, με τσάκωσε ο Pantelis).
                        if (now - afcLogMs >= 15_000) {
                            afcLogMs = now
                            // + v24: blend/sig για live calibration των SIG_FULL/SIG_MONO
                            Log.i(TAG, "AFC: %.1f %s trim=%d nco=%d est=%d pilot=%.3f blend=%.2f sig=%.1f".format(
                                curFreqHz / 1e6, if (afcLockedV) "LOCK" else "ACQ",
                                afcTrim, h.ncoTargetHz.toInt(), est.toInt(), d.pilotLevelDbg,
                                d.blendDbg, d.signalDb))
                        }
                        if (!afcLockedV) {
                            // b106: ≥1s πραγματικών μετρήσεων πριν από κάθε
                            // απόφαση (το b105 κλείδωνε ψεύτικα σε est=0/0 ticks)
                            if (settled && afcTicks >= 20 && now - afcStepMs >= 3_000) {
                                if (mag > afcLockBand) {
                                    afcStepMs = now
                                    afcTrim = (afcTrim + est.toLong())
                                        .coerceIn(-afcTrimMax, afcTrimMax)
                                    Log.i(TAG, "AFC: est ${est.toInt()} Hz -> trim $afcTrim Hz")
                                    sendCmd(0x01, curFreqHz + TUNE_OFFSET + afcTrim)
                                    afcAvg = 0.0
                                    afcTicks = 0
                                } else {
                                    afcLockedV = true
                                    Log.i(TAG, "AFC: LOCKED trim $afcTrim Hz (residual ${est.toInt()} Hz)")
                                }
                            }
                        } else {
                            // LOCKED: ομαλή παρακολούθηση — integrator στο NCO
                            // (κανένα hardware step, κανένα glitch)
                            val nco = (h.ncoTargetHz + (afcTrackG * est).toFloat())
                                .coerceIn(-10_000f, 10_000f)
                            h.ncoTargetHz = nco
                            if (mag > afcReacqHz) { // ο πομπός πήδηξε → coarse ξανά
                                afcTrim = (afcTrim + nco.toLong() + est.toLong())
                                    .coerceIn(-afcTrimMax, afcTrimMax)
                                h.zeroNco() // ακαριαία (το retune καλύπτει το glitch)
                                afcLockedV = false
                                afcAvg = 0.0
                                afcTicks = 0
                                afcStepMs = now
                                Log.i(TAG, "AFC: re-acquire -> trim $afcTrim Hz")
                                sendCmd(0x01, curFreqHz + TUNE_OFFSET + afcTrim)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "rds svc: ${e.message}") // πεθαίνει ΜΟΝΟ το RDS
            }
        }.apply {
            name = "RdsSvc"
            priority = Thread.NORM_PRIORITY + 1
            start()
        }

        // 🔴 build-70 — η τελική διάγνωση του «κωλοdriver» (μετρημένη βήμα-βήμα):
        // 1) Το tuner AUTO gain του FC0012 είναι ΝΕΚΡΟ σε αυτόν τον driver →
        //    gain ελάχιστο → pilot 0.011 (1/10) → ήχος-υπόγειο. (Windows: auto OK.)
        // 2) Το RTL digital AGC (0x08=1) που είχαμε ήταν τσιρότο πάνω στο (1):
        //    ψηφιακό boost σήματος+θορύβου → ήχος «έπαιζε», RDS θαμμένο
        //    (γραμμή 26.8dB/kurtosis 6.4 vs 62dB/3.0 ίδιο stick στο Windows).
        // Σωστό: MANUAL tuner gain ψηλά + RTL AGC OFF (= το config που έβγαλε
        // 99.4% decode στο Windows). Το MENU συνεχίζει να κάνει κύκλο αν χρειαστεί.
        // 🧪 b117 TEST (αίτημα Pantelis «το gain κάνει κόλπα — δεν έχει auto;»):
        // hardware tuner AGC στον R820T — το b70 verdict ήταν ΜΟΝΟ για FC0012,
        // ο R820T δεν δοκιμάστηκε ποτέ σε αυτό το path. MENU = escape σε manual.
        if (agcAuto) {
            sendCmd(0x03, 0)  // tuner gain mode: AUTO (hardware AGC test)
            sendCmd(0x08, 0)  // rtl digital agc: ΠΑΝΤΑ OFF (b70: δολοφόνος RDS)
            Log.i(TAG, "b117: tuner AGC = hardware AUTO (MENU για manual)")
        } else {
            sendCmd(0x03, 1)  // tuner gain mode: MANUAL
            sendCmd(0x0d, gainIdx.toLong()) // gain by index (πραγματικά dB)
            sendCmd(0x08, 0)  // rtl agc: OFF
        }
        // Το DC spike dodge γίνεται host-side με το fs/4 offset (rtl_fm recipe, βλ. FmDemod)
    }

    fun retune(freqHz: Long) {
        curFreqHz = freqHz
        afcTrim = 0L // νέος σταθμός = καθαρό AFC, από acquire
        afcAvg = 0.0
        afcTicks = 0
        afcLockedV = false
        afcStepMs = System.currentTimeMillis() // 2s settle blanking και στο zap
        sendCmd(0x01, freqHz + TUNE_OFFSET)
        hub?.resetFrontEnd() // καθαρός discriminator για τον νέο σταθμό
        rds?.resetSync()  // b95 NWSY: μόνο decoder/text — ο Costas κρατάει
                          // (in-spec σταθμοί ±6Hz → pull-in ακαριαίο, όχι 4.6s FFC)
        demod?.resetPll() // και καθαρό PLL (αλλιώς windup → δεν ξανακλειδώνει stereo)
    }

    fun stereoDetected(): Boolean = demod?.stereoDetected == true
    fun pilotLevel(): Float = demod?.pilotLevelDbg ?: 0f
    fun blend(): Float = demod?.blendDbg ?: 0f
    fun rdsPs(): String = rds?.ps ?: ""
    fun rdsPsMsg(): String = rds?.psMsg ?: ""
    fun rdsRt(): String = rds?.rt ?: ""
    fun rdsCt(): String = rds?.ct ?: ""
    fun rdsRtpTitle(): String = rds?.rtpTitle ?: ""
    fun rdsRtpArtist(): String = rds?.rtpArtist ?: ""
    fun sigLock(): Float = rds?.sigLock ?: 0f
    fun sigBlocksSec(): Float = rds?.sigBlocksSec ?: 0f
    fun rfDb(): Float = hub?.rfDb ?: -99f

    /**
     * b104: MENU = κύκλος στον ΠΡΑΓΜΑΤΙΚΟ πίνακα gains του tuner (dB), βήμα
     * dir=±1, persist ανά tuner type. Επιστρέφει label για το overlay.
     */
    fun cycleGain(dir: Int = 1): String {
        if (gains.size <= 1) return "RF gain: n/a"
        if (agcAuto) {
            agcAuto = false // b117: πρώτο MENU = έξοδος από hardware AGC σε manual
        } else {
            gainIdx = (gainIdx + dir + gains.size) % gains.size
        }
        prefs?.edit()?.putInt("fmGainIdx_t$tunerType", gainIdx)?.apply()
        sendCmd(0x03, 1) // manual
        sendCmd(0x0d, gainIdx.toLong())
        // b107: αλλαγή gain = ασυνέχεια στάθμης → ο discriminator κλωτσάει·
        // τύφλωσε τη μέτρηση AFC (όχι το trim/lock) για το settle παράθυρο
        afcAvg = 0.0
        afcTicks = 0
        afcStepMs = System.currentTimeMillis()
        return "RF gain: %.1f dB (%d/%d)".format(gainDb(), gainIdx + 1, gains.size)
    }

    private fun sendCmd(cmd: Int, value: Long) {
        try {
            val out = socket?.getOutputStream()
            if (out == null) {
                Log.w(TAG, "sendCmd: no socket")
                return
            }
            val b = ByteArray(5)
            b[0] = cmd.toByte()
            b[1] = ((value shr 24) and 0xFF).toByte()
            b[2] = ((value shr 16) and 0xFF).toByte()
            b[3] = ((value shr 8) and 0xFF).toByte()
            b[4] = (value and 0xFF).toByte()
            out.write(b)
            out.flush()
            Log.i(TAG, "tcp cmd=0x%02x val=%d sent".format(cmd, value))
        } catch (e: Exception) {
            Log.w(TAG, "sendCmd failed: ${e.message}")
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        pump?.interrupt()
        pump = null
        audioThread?.interrupt()
        audioThread = null
        rdsThread?.interrupt()
        rdsThread = null
        try { sdr?.close() } catch (_: Exception) {}
        sdr = null
        demod?.stop()
        demod = null
        rds = null
        hub = null
    }
}
