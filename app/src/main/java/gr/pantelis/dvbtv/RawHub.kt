package gr.pantelis.dvbtv

import kotlin.math.atan2

/**
 * MASTER RAW HUB — η «πλακέτα» (απόφαση Pantelis, από το TDA7330B board diagram
 * + το πάθημα build-73 όπου το πείραγμα του stereo έριξε το RDS ×5):
 * ΕΝΑΣ ιδιοκτήτης του σήματος, πολλοί ανεξάρτητοι καταναλωτές στο ΙΔΙΟ ωμό MPX.
 *
 *   I/Q bytes (pump thread) → rotate ×j^n → boxcar ÷3 → discriminator @342k
 *   → MPX FIR ÷2 → ΩΜΟ MPX @171k → ring buffer → Reader ανά service.
 *
 * Το όριο απομόνωσης = το ωμό MPX (όπως το MPXIN pin του chip): ο ήχος και το
 * RDS φιλτράρουν Ο ΚΑΘΕΝΑΣ δικά του από εκεί και κάτω — μηδέν κοινό state.
 * Slow reader χάνει ΔΙΚΑ του δείγματα (πηδάει μπροστά), ποτέ δεν φρενάρει το hub.
 */
class RawHub {
    companion object {
        const val MPX_RATE = 171_000
        private const val DECIM_IQ = 3              // 1026k → 342k
        private const val DISC_RATE = FmDemod.IN_RATE / DECIM_IQ
        private const val RING_BITS = 19            // 524288 samples ≈ 3.1s MPX
        private const val RING_SIZE = 1 shl RING_BITS
        private const val RING_MASK = RING_SIZE - 1
    }

    private val ring = FloatArray(RING_SIZE)

    // b100 AFC measurement — port του SDRangel FLL (freqlockcomplex.cpp:74-78,
    // μελέτη: sdrangel_afc_study/STUDY.md): eF = discriminator (το mpx342 μας),
    // fHat = EMA με a1 = 10/rate (τ≈0.1s). Δημοσίευση κάθε 50ms (το tick τους).
    private val afcA1 = 10.0 / DISC_RATE
    private var afcFHat = 0.0
    private var afcTick = 0

    @Volatile
    var afcFreqHz = 0f
        private set

    // b104 RF level: EMA ισχύος (ci²+cq²) στο σημείο του discriminator
    // (post-FIR, το «κανάλι» μας) → dBFS. Το FM status αντίστοιχο του rf της TV.
    private val rfA = 10.0 / DISC_RATE // τ≈0.1s
    private var rfEma = 0.0

    @Volatile
    var rfDb = -99f
        private set

    // b105 fine-AFC NCO @342k: ΣΥΝΕΧΗΣ, glitch-free αφαίρεση του carrier offset
    // πριν τον discriminator (y = x·e^{−jφ}) — το «ομαλά ακολουθεί τον πομπό»
    // του Pantelis. Hardware retune μόνο για coarse acquire (SDRangel split:
    // fine=ψηφιακό NCO, coarse=hardware). Slew 200 Hz/s = ακουστικά άφαντο.
    @Volatile
    var ncoTargetHz = 0f                        // γράφει ο AFC controller (FmEngine)
    private var ncoHz = 0.0                     // slewed τρέχουσα τιμή
    private var ncoPhC = 1.0                    // phasor e^{jφ}
    private var ncoPhS = 0.0
    private var ncoStC = 1.0                    // βήμα e^{jw} ανά sample @342k
    private var ncoStS = 0.0
    private var ncoRenorm = 0

    @Volatile
    private var wr = 0L // συνολικά γραμμένα samples (μονότονο)

    // ---- front-end state (τρέχει ΜΟΝΟ στο pump thread) ----
    private var rotPhase = 0
    private var prevI = 0f
    private var prevQ = 0f

    // b98 — ΤΟ FIX ΤΩΝ ΚΑΡΦΙΩΝ (root cause 2026-06-12, αποδεδειγμένο offline
    // στο iq_box.bin: A=177 blocks → E=374, groups 1→17): ο FC0012 βγάζει
    // DC spur 66dB + spur −98k/60dB ενώ ο σταθμός είναι 38dB· το γυμνό
    // [1,1,1] boxcar τα άφηνε να μπουν στον discriminator (beat products σε
    // όλο το MPX). 49-tap Kaiser LPF (pass ±110k, stop ≥158k @1.026M),
    // polyphase: υπολογίζεται ΜΟΝΟ στα output instants (342k) ≈ 33M MAC/s.
    // Συντελεστές από το validated python (box_chain_test.py variant E).
    private val iqTaps = floatArrayOf(
        0.000879340f, 0.000040680f, -0.001602391f, -0.002927665f, -0.002420761f,
        0.000543347f, 0.004650067f, 0.006907212f, 0.004527723f, -0.002580178f,
        -0.010623046f, -0.013510123f, -0.006902286f, 0.007683960f, 0.021921108f,
        0.024610745f, 0.009103343f, -0.020128903f, -0.046578038f, -0.048484629f,
        -0.010663579f, 0.065027136f, 0.157134137f, 0.232564457f, 0.261656690f,
        0.232564457f, 0.157134137f, 0.065027136f, -0.010663579f, -0.048484629f,
        -0.046578038f, -0.020128903f, 0.009103343f, 0.024610745f, 0.021921108f,
        0.007683960f, -0.006902286f, -0.013510123f, -0.010623046f, -0.002580178f,
        0.004527723f, 0.006907212f, 0.004650067f, 0.000543347f, -0.002420761f,
        -0.002927665f, -0.001602391f, 0.000040680f, 0.000879340f
    )
    private val iqRingI = FloatArray(iqTaps.size)
    private val iqRingQ = FloatArray(iqTaps.size)
    private var iqIdx = 0
    private var iqCount = 0

    // MPX FIR @342k (fc=85.5k — βλ. ιστορικό στο FmDemod: build-54 γόνατο)
    private val mpxTaps = FloatArray(23).also { t ->
        val fc = 85500.0 / DISC_RATE
        val m = t.size - 1
        var sum = 0.0
        for (i in t.indices) {
            val x = i - m / 2.0
            val sinc = if (x == 0.0) 2 * fc else kotlin.math.sin(2 * Math.PI * fc * x) / (Math.PI * x)
            val w = 0.54 - 0.46 * kotlin.math.cos(2 * Math.PI * i / m)
            t[i] = (sinc * w).toFloat()
            sum += t[i]
        }
        for (i in t.indices) t[i] = (t[i] / sum).toFloat()
    }
    private val mpxRing = FloatArray(mpxTaps.size)
    private var mpxIdx = 0
    private var mpxToggle = 0

    /** Καλείται από το pump thread με raw I/Q bytes του rtl_tcp (u8 interleaved). */
    fun feedIq(buf: ByteArray, len: Int) {
        var p = 0
        while (p + 1 < len) {
            val i = ((buf[p].toInt() and 0xFF) - 127) / 128f
            val q = ((buf[p + 1].toInt() and 0xFF) - 127) / 128f
            p += 2

            // rotate × j^n (σταθμός από −fs/4 στο κέντρο — DC spike dodge)
            val ri: Float
            val rq: Float
            when (rotPhase) {
                0 -> { ri = i; rq = q }
                1 -> { ri = -q; rq = i }
                2 -> { ri = -i; rq = -q }
                else -> { ri = q; rq = -i }
            }
            rotPhase = (rotPhase + 1) and 3

            iqRingI[iqIdx] = ri
            iqRingQ[iqIdx] = rq
            iqIdx = (iqIdx + 1) % iqTaps.size
            if (++iqCount < DECIM_IQ) continue
            iqCount = 0
            // 49-tap FIR στα output instants (polyphase ÷3)
            var ci = 0f
            var cq = 0f
            var ik = iqIdx
            for (c in iqTaps) {
                ik = if (ik == 0) iqTaps.size - 1 else ik - 1
                ci += c * iqRingI[ik]
                cq += c * iqRingQ[ik]
            }

            // b105 NCO: y = x·e^{−jφ} (αφαίρεση offset ΠΡΙΝ τον discriminator)
            val yi = (ci * ncoPhC + cq * ncoPhS).toFloat()
            val yq = (cq * ncoPhC - ci * ncoPhS).toFloat()
            val nc = ncoPhC * ncoStC - ncoPhS * ncoStS
            val ns = ncoPhC * ncoStS + ncoPhS * ncoStC
            ncoPhC = nc
            ncoPhS = ns
            if (++ncoRenorm >= 8192) { // κράτα |phasor|=1 (αριθμητικό drift)
                ncoRenorm = 0
                val m = 1.0 / Math.sqrt(nc * nc + ns * ns)
                ncoPhC *= m
                ncoPhS *= m
            }

            // quadrature discriminator @342k — πλήρες Carson band
            val re = yi * prevI + yq * prevQ
            val im = yq * prevI - yi * prevQ
            prevI = yi
            prevQ = yq
            val mpx342 = atan2(im, re)

            // AFC FLL: fHat = a1·eF + (1−a1)·fHat (SDRangel γρ.75) — μετράει το
            // ΥΠΟΛΟΙΠΟ offset (μετά το NCO), αυτό που βλέπει ο integrator
            afcFHat += afcA1 * (mpx342 - afcFHat)
            rfEma += rfA * ((yi * yi + yq * yq) - rfEma)
            if (++afcTick >= DISC_RATE / 20) { // 50ms tick
                afcTick = 0
                afcFreqHz = (afcFHat * DISC_RATE / (2.0 * Math.PI)).toFloat()
                rfDb = (10.0 * Math.log10(rfEma + 1e-12)).toFloat()
                // NCO slew: ομαλά (200 Hz/s) προς τον στόχο του controller
                val d = (ncoTargetHz.toDouble() - ncoHz).coerceIn(-10.0, 10.0)
                if (d != 0.0) {
                    ncoHz += d
                    val w = 2.0 * Math.PI * ncoHz / DISC_RATE
                    ncoStC = Math.cos(w)
                    ncoStS = Math.sin(w)
                }
            }

            // MPX FIR + ÷2 → 171k ωμό MPX
            mpxRing[mpxIdx] = mpx342
            mpxIdx = (mpxIdx + 1) % mpxTaps.size
            if (++mpxToggle < 2) continue
            mpxToggle = 0
            var mpx = 0f
            var mk = mpxIdx
            for (c in mpxTaps) {
                mk = if (mk == 0) mpxTaps.size - 1 else mk - 1
                mpx += c * mpxRing[mk]
            }
            val w = wr
            ring[(w and RING_MASK.toLong()).toInt()] = mpx
            wr = w + 1 // volatile publish ΜΕΤΑ το γράψιμο
        }
    }

    /** Καθαρό ξεκίνημα front-end (νέο tune/start). Οι Readers πηδάνε μπροστά μόνοι. */
    fun resetFrontEnd() {
        rotPhase = 0
        iqRingI.fill(0f); iqRingQ.fill(0f); iqIdx = 0; iqCount = 0
        prevI = 0f; prevQ = 0f
        mpxRing.fill(0f)
        mpxIdx = 0
        mpxToggle = 0
        afcFHat = 0.0
        afcTick = 0
        afcFreqHz = 0f
        rfEma = 0.0
        rfDb = -99f
        ncoTargetHz = 0f
        ncoHz = 0.0
        ncoPhC = 1.0; ncoPhS = 0.0
        ncoStC = 1.0; ncoStS = 0.0
        ncoRenorm = 0
    }

    /**
     * b106: ακαριαίο μηδένισμα NCO στο re-acquire — το αργό slew (200 Hz/s)
     * μόλυνε τις μετρήσεις των acquire κύκλων (overshoot −9395 στο b105 log)·
     * το ταυτόχρονο hardware retune καλύπτει το στιγμιαίο glitch.
     */
    fun zeroNco() {
        ncoTargetHz = 0f
        ncoHz = 0.0
        ncoStC = 1.0
        ncoStS = 0.0
    }

    fun attach() = Reader()

    /** Ανεξάρτητος δείκτης ανάγνωσης στο κοινό ωμό MPX. Ένας ανά service. */
    inner class Reader {
        private var rd = wr // ξεκίνα από το παρόν
        var drops = 0L      // πόσα samples πήδηξε λόγω αργοπορίας
            private set

        /** Γεμίζει το out με έως out.size samples. 0 = τίποτα νέο ακόμη. */
        fun read(out: FloatArray): Int {
            val w = wr
            var available = w - rd
            if (available <= 0) return 0
            if (available > RING_SIZE - 4096) {
                // μείναμε πίσω — πήδα στο φρέσκο μισό (χάνουμε ΔΙΚΑ μας samples)
                val skipTo = w - RING_SIZE / 2
                drops += skipTo - rd
                rd = skipTo
                available = w - rd
            }
            val n = minOf(available.toInt(), out.size)
            for (k in 0 until n) {
                out[k] = ring[((rd + k) and RING_MASK.toLong()).toInt()]
            }
            rd += n
            return n
        }
    }
}
