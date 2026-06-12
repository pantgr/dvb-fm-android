package gr.pantelis.dvbtv

import android.util.Log
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * RDS decoder v4 — η αρχιτεκτονική του redsea: φιλτράρισμα ΠΡΙΝ το carrier tracking.
 * Στο v3 ο Costas έβλεπε ΟΛΟ το MPX (μόνο one-pole arms) και το L−R splatter
 * (23-53k, 20-30dB πάνω από το RDS) τον έπνιγε: lock=1.0, τυχαία bits.
 * Chain v4: MPX @171k → mix με ΣΤΑΘΕΡΟ 57k = fs/3 ΑΚΡΙΒΩΣ (LUT 3 φάσεων, μηδέν
 * NCO drift — το ppm +34 του κρυστάλλου το διορθώνει ήδη ο tuner) →
 * [1,2,3,2,1]÷3 → 57k (ΔΙΠΛΟ null στο fold του mono bass: −52dB) →
 * 95-tap LPF ±2.0k @57k → ΚΑΘΑΡΟ baseband BPSK → Costas phase tracker
 * (capture ±25 Hz, residual μόνο, gear-shift A 0.008→0.002 μετά το lock) →
 * I-arm ÷3 → 19k → biphase matched filter (sine weights, 16 samples) →
 * 16 παράλληλα bit paths (φάση συμβόλου + biphase ambiguity) →
 * differential decode → 26-bit blocks → groups → PS/RT.
 */
class RdsDecoder {
    companion object {
        private const val POLY = 0x5B9
        // 🔴 build-67: οι παλιές τιμές (0x3D8/3D4/25C/3CC/258 — «βιβλιογραφικές»
        // EN50067) αντιστοιχούν σε ΑΛΛΟΝ αλγόριθμο syndrome. Για τον δικό μας
        // shift-register υπολογισμό οι σωστές (validated: synthetic MPX → 79/80
        // groups, BER=0) είναι αυτές. Με τις παλιές ΚΑΝΕΝΑ block δεν πέρναγε ποτέ
        // — όλα τα ιστορικά g (και το χθεσινό g=23) ήταν τυχαίες συμπτώσεις.
        private const val SYN_A = 0x17F
        private const val SYN_B = 0x00E
        private const val SYN_C = 0x12F
        private const val SYN_C2 = 0x078
        private const val SYN_D = 0x297

        // mixer 57k = fs/3: e^{-j2πn/3} παίρνει μόνο 3 τιμές
        private val MIX_C = floatArrayOf(1f, -0.5f, -0.5f)
        private val MIX_S = floatArrayOf(0f, -0.8660254f, 0.8660254f)

        private const val BB_RATE = 57_000.0
        // gear-shift: φαρδύ A για acquisition, στενό μετά το κλείδωμα (λιγότερο
        // phase jitter στα bits). Υστέρηση στο lock metric για να μην ταλαντώνει.
        private const val COSTAS_A_ACQ = 0.008f
        private const val COSTAS_A_TRK = 0.002f
        // 🔴 build-95: στο box (FC0012, ~45dB line) το lq έχει ΟΡΟΦΗ ~1.3 —
        // με LOCK_UP=1.5 το gear έμενε ΓΙΑ ΠΑΝΤΑ σε A (μετρημένο 92.0:
        // lock 1.19-1.35 επί 9′, 63% blocks ενώ το PC ίδιο σταθμό 94%).
        // v11 μέτρηση: decode υγιές και με lock 1.1. Gates κατεβασμένα.
        private const val LOCK_UP = 1.25f
        private const val LOCK_DOWN = 1.08f
        private const val COSTAS_B = 1e-6f
        // 🔴 build-59 μέτρηση (FFT στο MPX dump): ο encoder του σταθμού free-runs
        // έως και −105 Hz από το 57k (δικός του κρύσταλλος, ±24Hz ψ wander ήταν
        // υποτίμηση). ±25 Hz clamp = αδύνατο να τον φτάσει ο Costas → lock=1.0.
        // Μέσα στο ±2.4k LPF δεν υπάρχει τίποτα άλλο να κλειδώσει.
        // build-95: ±200→±450 — ο 93.6 μετρήθηκε στα −215 Hz (rtplus_scan
        // 09:00) = ΕΞΩ από το clamp → «RDS δεν έρχεται ποτέ» σε εκείνον.
        private val FREQ_MAX = (2.0 * Math.PI * 450.0 / BB_RATE).toFloat()
        // Acquisition saga (builds 60-66): sweep πέρασε χωρίς grab → lag-1
        // πνίγηκε στον θόρυβο → FFT 1.15s ανεπαρκές (noise spikes > γραμμή).
        // Τελική μορφή, validated offline στο costas_sim: COHERENT FFT 4.6s
        // ΜΕ Hann (χωρίς παράθυρο το leakage του z² γεννά ψεύτικα peaks) +
        // quality gate: εφαρμογή ΜΟΝΟ αν peak ≥10dB πάνω από το median του
        // search range — χωρίς gate οι τυχαίες εκτιμήσεις ΚΛΩΤΣΑΝΕ το loop.
        private const val FFC_DEC = 8
        private const val FFC_N = 32768
        private const val FFC_GATE = 10f // λόγος ισχύος peak/median (=10 dB)
        private const val TWO_PI = (2.0 * Math.PI).toFloat()
        private const val PI_F = Math.PI.toFloat()

        // v7 (SAA6588): θέση block ανά syndrome, σειρά blocks, flywheel όριο
        private val SYNPOS = hashMapOf(SYN_A to 0, SYN_B to 1, SYN_C to 2, SYN_C2 to 2, SYN_D to 3)
        private val NEXTPOS = intArrayOf(1, 2, 3, 0)
        private const val FLY_MAX = 32 // chip default
    }

    @Volatile
    var ps = ""
        private set

    @Volatile
    var rt = ""
        private set

    // build-83: text layer = redsea RDSString (sequential_length). Ένα string
    // ολοκληρώνεται ΜΟΝΟ με συνεχόμενη σειριακή λήψη segments από το 0 —
    // κενό/άλμα παγώνει τον μετρητή μέχρι το επόμενο καθαρό πέρασμα. Καμία
    // ευρετική (double-confirm/value-change clears b76-b82 = ΟΛΕΣ λάθος
    // στρώμα): οι χίμαιρες πεθαίνουν δομικά, το έλλειμμα blocks το λύνει το FEC.
    private val psBuf = arrayOfNulls<String>(4)
    private var psSeqLen = 0
    private var psPrevAddr = -1

    // SAA6588-host practice (AN243): το PS step εκπέμπεται 3-4× — δείξε το όταν
    // 2 διαδοχικές πλήρεις λήψεις συμπέσουν. Latency = δευτερόλεπτα, όπως το
    // αμάξι. (Το master message layer + άγκυρα + psName ευρετική αφαιρέθηκαν
    // 2026-06-12: λεπτά καθυστέρηση χωρίς αντίκρισμα — το chip δεν έχει τίποτα
    // τέτοιο, βλ. SAA6588.pdf Fig.1.)
    @Volatile
    var psMsg = "" // τρέχον PS step (καθαρή αλυσίδα) — ακολουθεί ζωντανά το scroll του σταθμού
        private set

    // b115 confirm-ή-timeout + dwell state (βλ. psPublish/psTick)
    private var psPend = ""
    private var psPendOk = false
    private var psPendClean = false
    private var psPendMs = 0L
    private var psMsgMs = 0L
    private var psChainCls = 0

    // b119: ο ΜΟΝΑΔΙΚΟΣ τροφοδότης του text layer (βλ. accept() — root cause
    // του PS freeze: ισόπαλα γειτονικά paths τάιζαν parseGroup ταυτόχρονα)
    private var textOwner: BitPath? = null

    // build-95: RT = CUMULATIVE assembly (AN243 Table 4 — κάθε CRC-καθαρό
    // segment στη θέση του, framing = A/B flag wipe). Το strict-sequential
    // έμεινε ΜΟΝΟ στο PS (proof3/dynamic-PS χίμαιρες): στο RT με 12-segment
    // μηνύματα + 2× confirm + A/B εναλλαγή = starvation κάτω από ~95% blocks
    // (μετρημένο: 85% capture sequential=ΚΕΝΟ, cumulative=πλήρες RT).
    private val rtBuf = arrayOfNulls<String>(16)
    private var rtFlag = -1 // A/B flag toggle = νέο μήνυμα → καθαρό buffer (πρότυπο)

    // 4A: Clock Time από τον σταθμό (build-78)
    @Volatile
    var ct = ""
        private set

    // RT+ (v14, validated offline + redsea oracle): ODA AID 0x4BD7 σε 3A →
    // app group με tags {content type, start, len} πάνω στο ΤΡΕΧΟΝ RT.
    // Item toggle flip = νέο τραγούδι → purge. Tags εφαρμόζονται ΜΟΝΟ στο
    // 2×-confirmed rt (NRSC G300 p.41: «fully received twice»).
    private var rtpAppGt = -1   // gtype του RT+ application group (από το 3A)
    private var rtpToggle = -1

    @Volatile
    var rtpTitle = ""
        private set

    @Volatile
    var rtpArtist = ""
        private set

    // mixer + [1,2,3,2,1] decimator ÷3
    private var mixPhase = 0
    private var triCnt = 0
    private var d1I = 0f; private var d2I = 0f; private var d3I = 0f; private var d4I = 0f
    private var d1Q = 0f; private var d2Q = 0f; private var d3Q = 0f; private var d4Q = 0f

    // 53-tap LPF @57k: pass ±2.4k, stop ±6k (σκοτώνει το L−R splatter ΠΡΙΝ τον Costas)
    // 🔴 build-56: το 95-tap (±2.0k) πάτωσε τον demod thread στο 100% (CPU-bound) →
    // sample drops → pilot 0.175→0.12 και lock 1.0 σε ΟΛΟ το chain. Στενότερο LPF
    // μόνο με decimate-first refactor (Costas @19k), όχι με περισσότερα taps εδώ.
    private val bbTaps = FloatArray(53).also { t ->
        val fc = 4200.0 / BB_RATE // μέσο μετάβασης 2.4k→6k
        val m = t.size - 1
        var sum = 0.0
        for (i in t.indices) {
            val xx = i - m / 2.0
            val sinc = if (xx == 0.0) 2 * fc else sin(2 * Math.PI * fc * xx) / (Math.PI * xx)
            val wnd = 0.54 - 0.46 * cos(2 * Math.PI * i / m)
            t[i] = (sinc * wnd).toFloat()
            sum += t[i]
        }
        for (i in t.indices) t[i] = (t[i] / sum).toFloat()
    }
    private val bbRingI = FloatArray(bbTaps.size)
    private val bbRingQ = FloatArray(bbTaps.size)
    private var bbIdx = 0

    // Costas (residual phase/freq μόνο — το mix είναι ήδη πάνω στο 57k)
    private var w = 0f
    private var freqInt = 0f
    private var costasA = COSTAS_A_ACQ

    // FFC: baseband ÷8 → z² @7125 → FFT 8192 → peak = 2×offset (μόνο χωρίς lock)
    private var fdN = 0
    private val fcBufI = FloatArray(FFC_N)
    private val fcBufQ = FloatArray(FFC_N)
    private var fcIdx = 0
    private val fftRe = FloatArray(FFC_N)
    private val fftIm = FloatArray(FFC_N)
    private val hann = FloatArray(FFC_N) {
        (0.5 - 0.5 * cos(2.0 * Math.PI * it / (FFC_N - 1))).toFloat()
    }
    private var ffcApplied = false // για το log

    // lock quality: locked BPSK → ενέργεια στο I, θόρυβος στο Q → ratio >> 1
    private var emaI2 = 0f
    private var emaQ2 = 0f

    // ÷3 → 19k
    private var dAcc = 0f
    private var dCount = 0

    // bit clock @19k: ΟΧΙ σταθερά 16 samples/bit — ο encoder βγάζει το bit clock
    // από τον ΔΙΚΟ του carrier (bitrate = carrier/48, EN 50067). Στα −105 Hz
    // offset αυτό είναι 1185.3 bps, όχι 1187.5: σταθερό 16άρι window γλιστράει
    // 1 sample / ~34 bits και σκοτώνει κάθε block. NCO δεμένο στο tracked foff.
    private var tick = 0
    private var slotNco = 0.0
    private var lastSlot = 0L
    private val ring = FloatArray(32)
    private var ringIdx = 0

    // matched filter: το biphase σύμβολο μετά το cos-rolloff shaping ≈ ένας
    // πλήρης κύκλος sine στα 16 samples (+ μισό στο παλιό half, − στο νέο).
    private val mfW = FloatArray(16) { sin(Math.PI * (it + 0.5) / 8.0).toFloat() }

    // build-83 FEC (redsea policy, EN 50067 error protection): διόρθωση ΜΟΝΟ
    // 1-2 bit bursts. Ο (26,16) κώδικας είναι ΓΡΑΜΜΙΚΟΣ: syn(received) =
    // SYN_offset XOR syn(error) → lookup του syn(error) δίνει το error pattern.
    // Πάνω από 2 bits ΔΕΝ διορθώνουμε — παραπάνω «διόρθωση» γεννάει ψευτο-blocks.
    // Μέτρηση 93.0: block success 44% χωρίς FEC → 0.44^4 = 3.7% πλήρη groups·
    // καμία text-layer ευρετική δεν σώζει τέτοιο έλλειμμα — μόνο το FEC.
    // CHIP EMU (SAA6588): bursts 1-5 bits με ΚΛΑΣΗ — ERDA(0)/ERDB(≤2)/ERDC(3-5)/
    // ERDD(3=άχρηστο). 367 syndromes, L αύξουσα ώστε σε σύγκρουση να κερδίζει
    // το κοντύτερο burst. Αλυσίδα/flywheel τρέφονται και από ERDC· ΚΕΙΜΕΝΟ μόνο
    // ≤ERDB· CT μόνο ERDA. Μετρημένο (rds-chipemu): blocks +7%/+23%, 3ο τραγούδι
    // πλήρες, μηδέν νέο text junk.
    private val fecTable = HashMap<Int, Pair<Long, Int>>().also { t ->
        for (L in 1..5) {
            val pats = if (L == 1) listOf(1L)
            else (0 until (1 shl (L - 2))).map { inner ->
                (1L shl (L - 1)) or 1L or (inner.toLong() shl 1)
            }
            val cls = if (L <= 2) 1 else 2
            for (p in pats) for (pos in 0..(26 - L)) {
                val s = syndrome(p shl pos)
                if (!t.containsKey(s)) t[s] = (p shl pos) to cls
            }
        }
    }

    // 16 παράλληλοι decoders — ένας ανά φάση συμβόλου. Καλύπτει ΚΑΙ το biphase
    // ambiguity (η +8 είναι μία από τις 16). Όποιος βγάζει CRC groups κερδίζει.
    private val paths = Array(16) { BitPath() }

    // diagnostics
    private var dbgAHits = 0
    private var dbgGoodBlocks = 0
    private var dbgBadBlocks = 0
    private var dbgGroups = 0
    private var dbgFec = 0
    private var dbgSlips = 0
    private var emaPeak = 0f
    private var dbgBits = 0L

    // b102: signal status για το UI (όπως το LOCK/snr της TV) — ανανεώνονται
    // στο ίδιο 5s παράθυρο με το log, μηδέν επιπλέον κόστος
    @Volatile
    var sigLock = 0f
        private set

    @Volatile
    var sigBlocksSec = 0f
        private set

    private var sigLastGood = 0

    /** v7 BitPath — μέθοδοι SAA6588, validated offline (rds-msg-v7/v71):
     *  any-pair sync, bit-slip ±1 (exact-only), flywheel-32, B&D-only dispatch. */
    private inner class BitPath {
        var prevBit = 0
        var bitReg = 0L
        var bitsSinceBlock = 0
        var synced = false
        var expectedBlock = 0
        var fly = 0
        val blocks = IntArray(4)
        val okFlags = BooleanArray(4)
        val okCls = IntArray(4) { 3 } // ERDA=0/ERDB=1/ERDC=2/ERDD=3 ανά block
        var good = 0
        var groups = 0
        var nbits = 0L
        var usPos = -1      // unsynced: θέση/χρόνος/info του τελευταίου έγκυρου block
        var usBit = -1000L
        var usInfo = 0

        fun reset() {
            prevBit = 0
            bitReg = 0
            bitsSinceBlock = 0
            synced = false
            expectedBlock = 0
            fly = 0
            okFlags.fill(false)
            okCls.fill(3)
            good = 0
            groups = 0
            nbits = 0
            usPos = -1
            usBit = -1000
            usInfo = 0
        }

        fun accept(inf: Int, cls: Int, early: Boolean) {
            good++
            dbgGoodBlocks++
            if (fly > 0) fly-- // flywheel: καλό block −1 (και τα ERDC μετράνε)
            blocks[expectedBlock] = inf
            okFlags[expectedBlock] = true
            okCls[expectedBlock] = cls
            // dispatch: PS αρκούν B+D (A/PI, C/AF άσχετα με κείμενο)· τα RT/CT
            // απαιτούν επιπλέον blocks μέσα στην parseGroup.
            // 🔴 b119 — ΤΟ PS-FREEZE ROOT CAUSE (βρέθηκε 12/6 βράδυ, οδηγός το
            // gain-wiggle πείραμα Pantelis): το παλιό gate «good >= max» άφηνε
            // ΓΕΙΤΟΝΙΚΑ paths (±1 bit, αποκωδικοποιούν ΤΑ ΙΔΙΑ blocks = αιώνια
            // ισοπαλία στο good) να ταΐζουν parseGroup ΤΑΥΤΟΧΡΟΝΑ → το global
            // PS chain state έπαιρνε interleaved ακολουθίες → αλυσίδα 0→3
            // ποτέ πλήρης → PS νεκρό· το cumulative RT αδιαφορεί για τα διπλά
            // («RT βράχος»). Κάθε resetSync = νέα ισοπαλία = νεκρό ξανά· κάθε
            // transient (gain wiggle) έσπαγε την ισοπαλία = «μαγική» ανάσταση.
            // Fix: ΕΝΑΣ ρητός textOwner — πρώτος που κλείνει group τον διεκδικεί,
            // επανεκλογή κάθε 5s στο stats block (max good, ταυτότητα όχι σκορ).
            if (expectedBlock == 3 && okFlags[1]) {
                groups++
                dbgGroups++
                if (textOwner == null) textOwner = this
                if (textOwner === this) parseGroup(blocks, okFlags, okCls)
            }
            expectedBlock = NEXTPOS[expectedBlock]
            bitsSinceBlock = if (early) 1 else 0 // early slip: 1 bit ήδη μέσα
        }

        fun push(rawBit: Int) {
            val data = rawBit xor prevBit
            prevBit = rawBit
            bitReg = (bitReg shl 1) or data.toLong()
            nbits++
            val v = bitReg and 0x3FFFFFFL
            if (!synced) {
                // any-pair sync: 2 διαδοχικά έγκυρα blocks σε σωστή σειρά
                val pos = SYNPOS[syndrome(v)]
                if (pos != null) {
                    val info = ((v shr 10) and 0xFFFF).toInt()
                    if (usPos >= 0 && nbits - usBit == 26L && NEXTPOS[usPos] == pos) {
                        dbgAHits++
                        synced = true
                        fly = 0
                        okFlags.fill(false)
                        okCls.fill(3)
                        blocks[usPos] = usInfo
                        okFlags[usPos] = true
                        okCls[usPos] = 0
                        blocks[pos] = info
                        okFlags[pos] = true
                        okCls[pos] = 0
                        expectedBlock = NEXTPOS[pos]
                        bitsSinceBlock = 0
                    } else {
                        usPos = pos
                        usBit = nbits
                        usInfo = info
                    }
                }
                return
            }
            if (++bitsSinceBlock < 26) return
            val syn = syndrome(v)
            val expSyn = when (expectedBlock) {
                0 -> SYN_A; 1 -> SYN_B; 3 -> SYN_D; else -> SYN_C
            }
            val ok = if (expectedBlock == 2) (syn == SYN_C || syn == SYN_C2) else syn == expSyn
            var inf = if (ok) ((v shr 10) and 0xFFFF).toInt() else -1
            var cls = 0
            if (inf < 0 && bitsSinceBlock == 26) {
                // FEC bursts 1-5 μόνο στην ονομαστική ευθυγράμμιση· slips = exact
                var e = fecTable[syn xor expSyn]
                if (e == null && expectedBlock == 2) e = fecTable[syn xor SYN_C2]
                if (e != null) {
                    inf = (((v xor e.first) shr 10) and 0xFFFF).toInt()
                    cls = e.second
                    dbgFec++
                }
            }
            if (inf >= 0) {
                if (bitsSinceBlock == 27) dbgSlips++ // +1 slip πέτυχε
                accept(inf, cls, early = false)
                return
            }
            if (bitsSinceBlock == 26) {
                // bit-slip −1: το block έκλεισε 1 bit νωρίτερα
                val v2 = (bitReg shr 1) and 0x3FFFFFFL
                val s2 = syndrome(v2)
                val ok2 = if (expectedBlock == 2) (s2 == SYN_C || s2 == SYN_C2) else s2 == expSyn
                if (ok2) {
                    dbgSlips++
                    accept(((v2 shr 10) and 0xFFFF).toInt(), cls = 0, early = true)
                    return
                }
                return // 1 bit ακόμα: το +1 slip κρίνεται στο 27
            }
            // bitsSinceBlock == 27: αποτυχία και στις 3 θέσεις
            dbgBadBlocks++
            okFlags[expectedBlock] = false
            okCls[expectedBlock] = 3
            expectedBlock = NEXTPOS[expectedBlock]
            fly++
            if (fly >= FLY_MAX) {
                synced = false
                usPos = -1
                usBit = -1000
            }
            bitsSinceBlock = 1 // 1 bit του επόμενου καταναλώθηκε
        }
    }

    /** b95 — NWSY (SAA6588 σελ.8): restart sync για zap σε νέο σταθμό.
     *  Πετάει ΜΟΝΟ decoder sync + text — ο carrier loop ΔΕΝ αγγίζεται:
     *  in-spec σταθμοί = 57k ±6Hz (EN 50067), το τρέχον freqInt είναι
     *  καλύτερο prior από 4.6s FFC αναμονή. Το chip ακριβώς αυτό κάνει. */
    @Synchronized
    fun resetSync() {
        ps = ""
        rt = ""
        psBuf.fill(null)
        psSeqLen = 0
        psPrevAddr = -1
        psMsg = ""
        psPend = ""
        psPendOk = false
        psPendMs = 0L
        psMsgMs = 0L
        rtBuf.fill(null)
        rtFlag = -1
        ct = ""
        rtpAppGt = -1
        rtpToggle = -1
        rtpTitle = ""
        rtpArtist = ""
        for (p in paths) p.reset()
        textOwner = null // b119: νέα εκλογή — αλλιώς δείχνει νεκρό/παλιό path
        ffcApplied = false
        fcIdx = 0
        fdN = 0
    }

    @Synchronized
    fun reset() {
        ps = ""
        rt = ""
        psBuf.fill(null)
        psSeqLen = 0
        psPrevAddr = -1
        psMsg = ""
        psPend = ""
        psPendOk = false
        psPendMs = 0L
        psMsgMs = 0L
        rtBuf.fill(null)
        rtFlag = -1
        ct = ""
        rtpAppGt = -1
        rtpToggle = -1
        rtpTitle = ""
        rtpArtist = ""
        dbgFec = 0
        dbgSlips = 0
        mixPhase = 0
        triCnt = 0
        d1I = 0f; d2I = 0f; d3I = 0f; d4I = 0f
        d1Q = 0f; d2Q = 0f; d3Q = 0f; d4Q = 0f
        bbRingI.fill(0f)
        bbRingQ.fill(0f)
        bbIdx = 0
        w = 0f
        freqInt = 0f
        costasA = COSTAS_A_ACQ
        fdN = 0
        fcIdx = 0
        ffcApplied = false
        slotNco = 0.0
        lastSlot = 0L
        emaI2 = 0f
        emaQ2 = 0f
        dAcc = 0f
        dCount = 0
        ring.fill(0f)
        tick = 0
        for (p in paths) p.reset()
        textOwner = null // b119
        dbgAHits = 0
        dbgGoodBlocks = 0
        dbgBadBlocks = 0
        dbgGroups = 0
    }

    /** Καλείται ανά MPX sample @171k. Το phi (pilot) αγνοείται — δικό μας tracking. */
    fun process(mpx: Float, phi: Float) {
        // mix ×e^{-j2πn/3}: το 57k κατεβαίνει στο DC
        val bi = mpx * MIX_C[mixPhase]
        val bq = mpx * MIX_S[mixPhase]
        if (++mixPhase == 3) mixPhase = 0

        // [1,2,3,2,1]/9 + παίρνουμε 1 στα 3 → 57k (διπλό null στο 57k = fold του DC)
        var yi = 0f
        var yq = 0f
        val due = ++triCnt == 3
        if (due) {
            triCnt = 0
            yi = (bi + 2f * d1I + 3f * d2I + 2f * d3I + d4I) * (1f / 9f)
            yq = (bq + 2f * d1Q + 3f * d2Q + 2f * d3Q + d4Q) * (1f / 9f)
        }
        d4I = d3I; d3I = d2I; d2I = d1I; d1I = bi
        d4Q = d3Q; d3Q = d2Q; d2Q = d1Q; d1Q = bq
        if (!due) return

        // 53-tap LPF @57k — εδώ πεθαίνει το L−R splatter, ΠΡΙΝ δει τίποτα ο Costas
        bbRingI[bbIdx] = yi
        bbRingQ[bbIdx] = yq
        bbIdx = (bbIdx + 1) % bbTaps.size
        var fI = 0f
        var fQ = 0f
        var k = bbIdx
        for (c in bbTaps) {
            k = if (k == 0) bbTaps.size - 1 else k - 1
            fI += c * bbRingI[k]
            fQ += c * bbRingQ[k]
        }

        // Costas derotate σε καθαρό σήμα
        val cw = cos(w)
        val sw = sin(w)
        val di = fI * cw + fQ * sw
        val dq = fQ * cw - fI * sw
        emaI2 += 1e-4f * (di * di - emaI2)
        emaQ2 += 1e-4f * (dq * dq - emaQ2)
        val lq = emaI2 / (emaQ2 + 1e-12f)
        val e = (if (di >= 0f) 1f else -1f) * dq
        // 🔴 χωρίς lock ο θόρυβος κάνει τον integrator random walk ±180 Hz/s —
        // ΠΑΓΩΜΑ: τη συχνότητα την ορίζει μόνο ο FFC· ο B αναλαμβάνει μετά το lock
        // (b95: 1.3→1.1 — στο box lq οροφή ~1.3, ο integrator έμενε παγωμένος
        // ενώ το tracking ήταν πραγματικό: foff σταθερό ±0.2Hz στο log)
        if (lq >= 1.1f) freqInt = (freqInt + COSTAS_B * e).coerceIn(-FREQ_MAX, FREQ_MAX)
        w += freqInt + costasA * e
        if (w > PI_F) w -= TWO_PI else if (w < -PI_F) w += TWO_PI
        if (costasA == COSTAS_A_ACQ) {
            if (lq > LOCK_UP) costasA = COSTAS_A_TRK
        } else if (lq < LOCK_DOWN) costasA = COSTAS_A_ACQ

        // FFC: κάθε 8ο sample του band-limited (±2.4k) baseband — pick, ΟΧΙ boxcar
        // (καθαρό decimation αφού είναι ήδη φιλτραρισμένο) — τετράγωνο @7125 Hz
        if (++fdN == FFC_DEC) {
            fdN = 0
            fcBufI[fcIdx] = fI * fI - fQ * fQ
            fcBufQ[fcIdx] = 2f * fI * fQ
            if (++fcIdx == FFC_N) {
                fcIdx = 0
                if (lq < 1.2f) ffcFromFft() // b95: κάτω από το νέο LOCK_UP
            }
        }

        // ÷3 → 19k (το LPF έχει ήδη κόψει ό,τι θα δίπλωνε)
        dAcc += di
        if (++dCount < 3) return
        dCount = 0
        val x = dAcc * (1f / 3f)
        dAcc = 0f
        onTick19k(x)
    }

    private fun onTick19k(x: Float) {
        ring[ringIdx] = x
        ringIdx = (ringIdx + 1) and 31
        tick++

        // trailing biphase soft: matched filter στα τελευταία 16 samples
        // (παλιό half +, νέο half − — ίδια σύμβαση με το παλιό a−b boxcar)
        var soft = 0f
        for (j in 0 until 16) soft += mfW[j] * ring[(ringIdx + 16 + j) and 31]
        emaPeak += 0.001f * (abs(soft) - emaPeak)

        // NCO: 16 slots ανά bit, ρυθμός = (57000+foff)/48 bits/s από τον carrier.
        // Κάθε slot που περνάει ταΐζει το δικό του path — έτσι ο "σωστός" path
        // μένει ευθυγραμμισμένος με τον encoder για πάντα (μηδέν slip).
        val foffHz = freqInt * BB_RATE / (2.0 * Math.PI)
        slotNco += (1187.5 + foffHz / 48.0) * 16.0 / 19000.0
        val slot = slotNco.toLong()
        val bit = if (soft > 0f) 1 else 0
        while (lastSlot < slot) {
            lastSlot++
            paths[(lastSlot and 15L).toInt()].push(bit)
        }

        if (++dbgBits % (6000L * 16L) == 0L) {
            val hz = freqInt * BB_RATE / (2.0 * Math.PI)
            var best = 0
            for (p in 1 until 16) if (paths[p].good > paths[best].good) best = p
            val bp = paths[best]
            textOwner = bp // b119: επανεκλογή text owner — ταυτότητα, όχι σκορ
            val lock = emaI2 / (emaQ2 + 1e-12f)
            val gear = (if (costasA == COSTAS_A_TRK) "T" else "A") + if (ffcApplied) "+F" else ""
            // 🔴 ps/rt ΕΚΤΟΣ format template: corrupted RDS chars περιέχουν '%' →
            // UnknownFormatConversionException → πέθαινε ΟΛΟΣ ο FM pump (11/6 11:16)
            sigLock = lock
            val dg = bp.good - sigLastGood
            sigBlocksSec = if (dg in 0..400) dg / 5.05f else 0f // best-switch glitch → 0
            sigLastGood = bp.good
            val stats = "bits=${dbgBits / 16} foff=%.1fHz lock=%.2f gear=$gear peak=%.4f".format(hz, lock, emaPeak)
            Log.i("DvbTvRds", "$stats best=$best sync=${bp.synced} g=${bp.good} gr=${bp.groups} fec=$dbgFec slip=$dbgSlips ps='$psMsg' rt='${rt.take(24)}'")
        }
    }

    /** Coherent FFT (Hann) στο z² → peak ±450 Hz → offset· εφαρμογή ΜΟΝΟ με
     *  quality gate (peak ≥ FFC_GATE × median) — αλλιώς μην αγγίξεις το loop. */
    private fun ffcFromFft() {
        for (i in 0 until FFC_N) {
            fftRe[i] = fcBufI[i] * hann[i]
            fftIm[i] = fcBufQ[i] * hann[i]
        }
        fft(fftRe, fftIm)
        val frate = BB_RATE / FFC_DEC // 7125 Hz
        // b95: search ±900 Hz στο z² = offset έως ±450 (μαζί με το νέο FREQ_MAX)
        val kMax = (900.0 * FFC_N / frate).toInt()
        val mags = FloatArray(2 * kMax)
        var bk = 0
        var bm = -1f
        for (k in 1..kMax) {
            val m1 = fftRe[k] * fftRe[k] + fftIm[k] * fftIm[k]
            mags[k - 1] = m1
            if (m1 > bm) { bm = m1; bk = k }
            val k2 = FFC_N - k
            val m2 = fftRe[k2] * fftRe[k2] + fftIm[k2] * fftIm[k2]
            mags[kMax + k - 1] = m2
            if (m2 > bm) { bm = m2; bk = -k }
        }
        if (bk == 0) return
        mags.sort()
        val median = mags[mags.size / 2]
        if (bm < FFC_GATE * median) return // αβέβαιη εκτίμηση — μην κλωτσήσεις
        val f2 = bk * frate / FFC_N            // συχνότητα τόνου = 2×offset
        val wOff = Math.PI * f2 / BB_RATE      // 2π×(f2/2)/57k σε rad/sample
        freqInt = wOff.toFloat().coerceIn(-FREQ_MAX, FREQ_MAX)
        ffcApplied = true
    }

    /** In-place radix-2 FFT (iterative, bit-reversal) — μόνο για το FFC, 1/1.15s. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang.toFloat())
            val wi = sin(ang.toFloat())
            var base = 0
            while (base < n) {
                var curR = 1f
                var curI = 0f
                val half = len / 2
                for (k in 0 until half) {
                    val a = base + k
                    val b = a + half
                    val vR = re[b] * curR - im[b] * curI
                    val vI = re[b] * curI + im[b] * curR
                    re[b] = re[a] - vR; im[b] = im[a] - vI
                    re[a] += vR; im[a] += vI
                    val nR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nR
                }
                base += len
            }
            len = len shl 1
        }
    }

    private fun parseGroup(blocks: IntArray, okf: BooleanArray, okCls: IntArray) {
        val b2 = blocks[1]
        val gtype = (b2 shr 12) and 0xF
        val versionB = (b2 and 0x800) != 0
        when {
            gtype == 0 -> {
                // PS chars: B+D με κλάση ≤ERDB — τα ERDC κρατάνε μόνο την αλυσίδα
                if (okCls[1] > 1 || okCls[3] > 1) return
                val addr = b2 and 0x3
                psBuf[addr] = "" + rdsChar((blocks[3] shr 8) and 0xFF) + rdsChar(blocks[3] and 0xFF)
                // redsea sequential_length: μετράει μόνο συνεχόμενη σειρά 0→3.
                // Οι encoders αλλάζουν λέξη σε όριο κύκλου, άρα ένα σειριακό
                // πέρασμα δεν μπορεί να δρασκελίσει δύο λέξεις = όχι χίμαιρες.
                if (addr == 0) psSeqLen = 1
                else if (addr == psPrevAddr + 1 && psSeqLen == addr) psSeqLen = addr + 1
                else psSeqLen = 0 // strict: σπασμένη σειρά = νεκρό έως το επόμενο addr0.
                // Measured (fec_test proof3): χωρίς αυτό, η «γεμάτη» κατάσταση
                // ξαναδημοσίευε mixed buffer → P3923/TRAGIZDI/14YTE/S/6/26.
                // Με αυτό: 16/16 λέξεις σωστές στο 93.0 capture, μηδέν typo.
                psPrevAddr = addr
                // b116: καθαρότητα αλυσίδας — ERDA παντού = έμπιστη single-shot·
                // FEC-διορθωμένη = ύποπτη για miscorrection ('AKOON' live 91.5)
                val cls = maxOf(okCls[1], okCls[3])
                psChainCls = if (addr == 0) cls else maxOf(psChainCls, cls)
                if (psSeqLen == 4) {
                    val word = "${psBuf[0]}${psBuf[1]}${psBuf[2]}${psBuf[3]}".trim()
                    if (word.isNotEmpty()) psPublish(word, psChainCls == 0)
                }
            }
            gtype == 2 && !versionB -> {
                // 2A: chars στα C+D — όλα ≤ERDB· cumulative ανά addr (b95)
                if (!okf[2] || okCls[1] > 1 || okCls[2] > 1 || okCls[3] > 1) return
                val addr = b2 and 0xF
                val flag = (b2 shr 4) and 1
                if (flag != rtFlag) { // νέο μήνυμα — το παλιό μένει ορατό μέχρι το νέο
                    rtFlag = flag
                    rtBuf.fill(null)
                }
                rtBuf[addr] = "" + rdsChar((blocks[2] shr 8) and 0xFF) + rdsChar(blocks[2] and 0xFF) +
                        rdsChar((blocks[3] shr 8) and 0xFF) + rdsChar(blocks[3] and 0xFF)
                rtTryPublish()
            }
            gtype == 2 && versionB -> {
                if (okCls[1] > 1 || okCls[3] > 1) return // chars στο D
                val addr = b2 and 0xF
                val flag = (b2 shr 4) and 1
                if (flag != rtFlag) {
                    rtFlag = flag
                    rtBuf.fill(null)
                }
                rtBuf[addr] = "" + rdsChar((blocks[3] shr 8) and 0xFF) + rdsChar(blocks[3] and 0xFF)
                rtTryPublish()
            }
            gtype == 3 && !versionB -> {
                // ODA registration: B = app group code (5 LSB), D = AID
                if (okCls[1] > 1 || okCls[3] > 1) return
                val agc = b2 and 0x1F
                if (blocks[3] == 0x4BD7 && (agc and 1) == 0) rtpAppGt = agc shr 1
            }
            rtpAppGt > 0 && gtype == rtpAppGt && !versionB -> {
                // RT+ tags (EBU layout, validated με redsea στο synthetic):
                // B: b4=toggle b3=running b2..b0=ct1[5:3] | C: b15..b13=ct1[2:0],
                // b12..b7=start1, b6..b1=len1, b0=ct2[5] | D: b15..b11=ct2[4:0],
                // b10..b5=start2, b4..b0=len2
                if (!okf[2] || okCls[1] > 1 || okCls[2] > 1 || okCls[3] > 1) return
                val toggle = (b2 shr 4) and 1
                val running = (b2 shr 3) and 1
                if (toggle != rtpToggle) { // νέο item → purge (spec: clear memory)
                    rtpToggle = toggle
                    rtpTitle = ""
                    rtpArtist = ""
                }
                if (running == 0) { // δεν παίζει item — μην δείχνεις σκουπίδια
                    rtpTitle = ""
                    rtpArtist = ""
                    return
                }
                val c = blocks[2]
                val d = blocks[3]
                applyRtpTag(((b2 and 0x7) shl 3) or ((c shr 13) and 0x7),
                    (c shr 7) and 0x3F, (c shr 1) and 0x3F)
                applyRtpTag(((c and 0x1) shl 5) or ((d shr 11) and 0x1F),
                    (d shr 5) and 0x3F, d and 0x1F)
            }
            gtype == 4 && !versionB -> {
                // CT: AN243 — δεκτό ΜΟΝΟ από group με 0 διορθώσεις (ERDA παντού·
                // το ρολόι δεν επαναλαμβάνεται, δεν υπάρχει 2η επιβεβαίωση)
                if (!okf[2] || okCls[1] > 0 || okCls[2] > 0 || okCls[3] > 0) return
                val mjd = ((blocks[1] and 0x3) shl 15) or ((blocks[2] shr 1) and 0x7FFF)
                val hourUtc = ((blocks[2] and 0x1) shl 4) or ((blocks[3] shr 12) and 0xF)
                val minute = (blocks[3] shr 6) and 0x3F
                var offHalf = blocks[3] and 0x1F
                if ((blocks[3] and 0x20) != 0) offHalf = -offHalf
                if (mjd >= 15079 && hourUtc < 24 && minute < 60) {
                    var totMin = hourUtc * 60 + minute + offHalf * 30
                    var dayAdj = 0
                    if (totMin < 0) { totMin += 1440; dayAdj = -1 }
                    if (totMin >= 1440) { totMin -= 1440; dayAdj = 1 }
                    // MJD → ημερομηνία (EN 50067 annex)
                    val mjdL = mjd + dayAdj
                    val yp = ((mjdL - 15078.2) / 365.25).toInt()
                    val mp = ((mjdL - 14956.1 - (yp * 365.25).toInt()) / 30.6001).toInt()
                    val day = mjdL - 14956 - (yp * 365.25).toInt() - (mp * 30.6001).toInt()
                    val k = if (mp == 14 || mp == 15) 1 else 0
                    val year = 1900 + yp + k
                    val month = mp - 1 - k * 12
                    ct = "%02d:%02d %d/%d/%d".format(totMin / 60, totMin % 60, day, month, year)
                }
            }
        }
    }

    /** Πλήρης λέξη PS (καθαρή αλυσίδα 0→3, όλα ≤ERDB) → υποψήφιος.
     *  Ιστορικό (12/6): b92 «2 ίδιες σερί» πάγωνε το PS σε σταθμούς με ≤1
     *  αλυσίδα/step (το RT έρεε = blocks OK)· b114 σκέτο άμεσο publish έβγαλε
     *  ΧΙΜΑΙΡΕΣ ('KA20:23','KASTOR5' live 91.5) — ο encoder αλλάζει λέξη ΜΕΣΑ
     *  σε αλυσίδα χωρίς καμία ένδειξη (AN243 §10), μόνο η επανάληψη το πιάνει.
     *  b115 (psTick = ο μόνος publisher): ready = 2η αλυσίδα ίδια (ζωντανοί
     *  σταθμοί <1s) Ή timeout 1.2s (κολλημένοι: +1.2s αντί για ΠΟΤΕ)· συν
     *  DWELL 2s μεταξύ αλλαγών οθόνης (αίτημα Pantelis «θέλει delay», car-radio
     *  pace). Η χίμαιρα δεν επιβεβαιώνεται και θάβεται από την επόμενη λέξη. */
    private fun psPublish(word: String, clean: Boolean) {
        ps = word
        if (word == psPend) {
            psPendOk = true
        } else if (word != psMsg) {
            psPend = word
            psPendOk = false
            psPendClean = clean
            psPendMs = System.currentTimeMillis()
        } else {
            psPend = "" // ξαναήρθε το ήδη δημοσιευμένο — άκυρος ο υποψήφιος
        }
    }

    /** Καλείται περιοδικά από το RdsSvc loop (FmEngine) — δημοσιεύει με
     *  confirm-ή-timeout + dwell. b116: timeout κλιμακωτό κατά καθαρότητα
     *  (ERDA chain 1.2s · FEC-διορθωμένη 3.5s — προλαβαίνει να τη θάψει η
     *  επόμενη πραγματική λέξη στους ζωντανούς σταθμούς). */
    fun psTick(now: Long) {
        if (psPend.isEmpty() || psPend == psMsg) return
        val tmo = if (psPendClean) 1200 else 3500
        val ready = psPendOk || now - psPendMs > tmo
        if (ready && now - psMsgMs >= 2000) {
            psMsg = psPend
            psMsgMs = now
            psPend = ""
            psPendOk = false
        }
    }

    // b101 (εντολή Pantelis): το 2× confirm του RT ΚΑΤΑΡΓΗΘΗΚΕ — έκανε το RT
    // να αργεί λεπτά (ping-pong με την A/B εναλλαγή σε μέτριο σήμα). Δημοσίευση
    // ΑΜΕΣΩΣ με τη συμπλήρωση· φίλτρα = CRC + ERDB gate + A/B framing. Τυχόν
    // σπάνιο typo αυτοδιορθώνεται στο επόμενο πέρασμα (~6s, cumulative refresh).

    /** b95: cumulative completeness — δημοσίευση όταν ΟΛΑ τα segments 0..(\r ή
     *  15) είναι παρόντα (όχι απαραίτητα από ένα πέρασμα) ΚΑΙ συναρμολογηθεί
     *  ΙΔΙΟ 2 φορές (AN243 «update only when equivalent» — έπιασε το
     *  'KASTORIb' miscorrection στο offline proof, το confirm ΜΕΝΕΙ). */
    private fun rtTryPublish() {
        val sb = StringBuilder()
        var i = 0
        while (i < 16) {
            val seg = rtBuf[i] ?: return // τρύπα πριν το τέλος = όχι πλήρες ακόμα
            sb.append(seg)
            if (seg.contains('\r')) break
            i++
        }
        val cr = sb.indexOf("\r")
        val t = if (cr >= 0) sb.substring(0, cr).trimEnd() else sb.toString().trimEnd()
        if (t.isEmpty() || t == rt) return
        rt = t
    }

    /** Tag → κόψιμο από το confirmed RT. Μόνο αν το RT καλύπτει το range —
     *  τα tags «δεν αλλάζουν όσο ζει το RT» (spec), άρα ασφαλές να ξαναγίνει
     *  apply σε κάθε επανάληψη του app group. */
    private fun applyRtpTag(ctype: Int, start: Int, len: Int) {
        val r = rt
        if (r.isEmpty() || start >= r.length) return
        val v = r.substring(start, minOf(start + len + 1, r.length)).trim()
        if (v.isEmpty()) return
        when (ctype) {
            1 -> rtpTitle = v   // item.title
            4 -> rtpArtist = v  // item.artist
            // 31/32 stationname, 24 date_time κ.λπ. — στο log αν χρειαστεί
        }
    }

    private fun rdsChar(c: Int): Char = when {
        c == 0x0D -> '\r'
        c in 0x20..0x7E -> c.toChar()
        else -> ' '
    }

    private fun syndrome(vector: Long): Int {
        var reg = 0
        for (k in 25 downTo 0) {
            reg = (reg shl 1) or ((vector shr k) and 1L).toInt()
            if (reg and 0x400 != 0) reg = reg xor POLY
        }
        for (k in 10 downTo 1) {
            reg = reg shl 1
            if (reg and 0x400 != 0) reg = reg xor POLY
        }
        return reg and 0x3FF
    }
}
