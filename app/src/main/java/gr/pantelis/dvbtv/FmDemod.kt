package gr.pantelis.dvbtv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * FM demodulator κατά το rtl_fm/SoftFM recipe:
 * capture 960 kS/s, tuner fs/4 = 240 kHz δίπλα (DC spike dodge), rotate ×j^n,
 * boxcar ÷4 → 240k, quadrature discriminator → MPX.
 * MPX: mono L+R (0-15k) + pilot 19k + DSB L−R στο 38k + RDS στο 57k.
 * Pilot PLL (τέχνη του SoftFM) → sin(2φ) αποδιαμορφώνει το L−R → STEREO.
 * Έξοδος: 48 kHz stereo PCM16, de-emphasis 50 µs ανά κανάλι.
 * Hook για RDS: ο decoder παίρνει (mpx, φ) ανά sample @240k — carrier 3φ, clock φ/16.
 */
class FmDemod {
    companion object {
        // 🔑 Το κολπάκι του redsea: MPX rate = 171k = 3×57k ΑΚΡΙΒΩΣ.
        // Carrier RDS = fs/3, pilot = fs/9, bit = 144 samples — όλα ακέραια, μηδέν drift.
        const val IN_RATE = 1_026_000      // 6 × 171k, στο καλό εύρος του RTL resampler
        const val TUNE_OFFSET = IN_RATE / 4 // fs/4 = 256.5 kHz (DC spike dodge)
        // 🔴 Ο discriminator θέλει το ΠΛΗΡΕΣ Carson band (±128k) → τρέχει στα 342k
        // (Nyquist ±171k). Το φίλτρο μπαίνει ΜΕΤΑ, στο MPX (που χωράει σε 60k) → ÷2 → 171k.
        private const val DECIM_IQ = 3      // 1026k -> 342k (boxcar, πριν τον discriminator)
        private const val DISC_RATE = IN_RATE / DECIM_IQ
        private const val MID_RATE = DISC_RATE / 2 // 171k MPX (pilot=fs/9, RDS=fs/3)
        private const val DECIM2 = 4        // 171k -> 42750 Hz audio (το Android το αναπαράγει)
        private const val OUT_RATE = MID_RATE / DECIM2
        // b111-118: 9000→6300→5200→4200→3150 (eye-tube calibration Pantelis): οι
        // FM compressors κρατάνε modulation density ~100% (loudness war) → το
        // demod RMS βγαίνει μόνιμα καυτό vs πηγές με κανονικό crest factor· συν
        // το stereo L=M+S (έως 2× mono πλάτος) που μπορούσε να ψαλιδίζει.
        private const val AUDIO_GAIN = 3150f
        // 🔴 v24 sim εύρημα #1 (stereo_sim sweep, 12/6): το SoftFM 1.17 ήταν ΛΑΘΟΣ
        // για το chain μας — sd=mpx·sin(2φ) ανακτά 0.5×sub, άρα το S θέλει ×2.0
        // ΑΚΡΙΒΩΣ για να ταιριάξει το M. Με 1.17 το separation είχε μαθηματικό
        // ταβάνι 11.6 dB (μετρήθηκε 11.7)· με 2.0 μετρήθηκε 62 dB σε CNR 60.
        private const val STEREO_GAIN = 2.0f
        // pilot gate με hysteresis (chip practice: TA7343 3mV / TDA1591 2dB /
        // LA1888 1.4:1) — ON το παλιό threshold, OFF χαμηλότερα. Validity gate
        // ΜΟΝΟ· τον βαθμό στέρεο τον ορίζει το blend (TDA1591 SNC pattern).
        private const val PILOT_ON = 0.06f   // pilot ~0.12-0.175 rad όταν υπάρχει
        private const val PILOT_OFF = 0.04f
        // Blend ramp πάνω στο RSSI (rfDb − gainDb, antenna-referred dBFS):
        // b=0 κάτω από SIG_MONO, b=1 πάνω από SIG_FULL (TDA1591: IF meter →
        // separation 45→0 dB· LA1888: mono<25dBµ, full 40dB@80dBµ). Αρχικές
        // τιμές — calibration από το live log (FM: blend ... sig=).
        private const val SIG_FULL = -25f
        private const val SIG_MONO = -40f
        // Ασύμμετρη δυναμική LA1888NM: προς mono τ≈1ms (burst), προς stereo
        // αργά ~150ms (7µA linear ramp στο chip) — εδώ EMA στα output instants.
        private const val BLEND_DOWN_A = 1f / (0.003f * OUT_RATE)
        private const val BLEND_UP_A = 1f / (0.150f * OUT_RATE)

        // pilot PLL: 2ης τάξης, bandwidth ~50-100 Hz @240k
        private const val PLL_FREQ = (2.0 * Math.PI * 19000.0 / MID_RATE).toFloat()
        private const val PLL_ALPHA = 0.002f // στενό loop = λιγότερο phase jitter (×3 στο 57k για RDS)
        private const val PLL_BETA = 1e-6f
        // pilot = 19k ±2 Hz standard· clamp ±50 Hz ώστε ο θόρυβος (χωρίς pilot)
        // να μην παρασύρει τον integrator εκτός capture range (windup)
        private const val PLL_INT_MAX = (2.0 * Math.PI * 50.0 / MID_RATE).toFloat()
        private const val TWO_PI = (2.0 * Math.PI).toFloat()

        // de-emphasis single pole: a = dt/(tau+dt), tau=50µs, dt=1/48000
        private val DEEMPH_A = ((1.0 / OUT_RATE) / (50e-6 + 1.0 / OUT_RATE)).toFloat()
    }

    /** true όταν το pilot 19k είναι παρόν (gate με hysteresis ON 0.06/OFF 0.04). */
    @Volatile
    var stereoDetected = false
        private set

    /** debug: τρέχον επίπεδο pilot (αναμενόμενο ~0.18 rad όταν υπάρχει). */
    @Volatile
    var pilotLevelDbg = 0f
        private set

    /** RSSI driver του blend (γράφει το FmEngine: rfDb − gainDb). */
    @Volatile
    var signalDb = -99f

    /** τρέχον blend factor 0..1 (UI/log). */
    @Volatile
    var blendDbg = 0f
        private set

    private var track: AudioTrack? = null
    private var deemphL = 0f
    private var deemphR = 0f
    private val pcm = ShortArray(8192)
    private var pcmLen = 0

    // RawHub era: το front-end (rotate/decim/discriminator/MPX FIR) ζει στο
    // RawHub — εδώ μπαίνει ΩΜΟ MPX @171k μέσω processMpx(). Ο FmDemod είναι
    // πλέον καθαρά το AUDIO microservice (PLL + audio FIR + deemph + track).

    // pilot PLL state
    private var phi = 0f
    private var freqInt = 0f
    private var pilotLevel = 0f

    // blend state (output rate)
    private var blend = 0f

    // decimating FIR (windowed sinc, Hamming): cutoff 15 kHz @171k, 57 taps —
    // στο 171k το 25-tap ήταν πολύ κοντό: pilot/L−R δίπλωναν στον ήχο (hiss)
    private val taps = FloatArray(57).also { t ->
        val fc = 15000.0 / MID_RATE
        val m = t.size - 1
        var sum = 0.0
        for (i in t.indices) {
            val x = i - m / 2.0
            val sinc = if (x == 0.0) 2 * fc else sin(2 * Math.PI * fc * x) / (Math.PI * x)
            val w = 0.54 - 0.46 * cos(2 * Math.PI * i / m)
            t[i] = (sinc * w).toFloat()
            sum += t[i]
        }
        for (i in t.indices) t[i] = (t[i] / sum).toFloat()
    }
    private val delayM = FloatArray(taps.size)
    private val delayS = FloatArray(taps.size)
    private var dIdx = 0
    private var phase = 0

    fun start() {
        val minBuf = AudioTrack.getMinBufferSize(
            OUT_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(OUT_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
            maxOf(minBuf, 65536),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track?.play()
    }

    /** Ωμό MPX sample @171k από το RawHub ring (καλείται στο audio thread). */
    fun processMpx(mpx: Float) {
        val t = track ?: return

        // ---- pilot PLL (19k) — ΞΑΝΑΜΠΗΚΕ στο build-77 ----
        // Το b73 το αφαίρεσε «για CPU» και ο ρυθμός decode έπεσε 5× (389→75
        // blocks/min)· συσχέτιση ισχυρή, μηχανισμός ασαφής — όμως το b72
        // (PLL ON + mono) ήταν το χρυσό config.
        val s1 = sin(phi)
        val c1 = cos(phi)
        val err = mpx * c1
        freqInt = (freqInt + PLL_BETA * err).coerceIn(-PLL_INT_MAX, PLL_INT_MAX)
        phi += PLL_FREQ + freqInt + PLL_ALPHA * err
        if (phi > TWO_PI) phi -= TWO_PI
        pilotLevel += 0.0005f * (mpx * s1 * 2f - pilotLevel)
        // gate με hysteresis (chip practice) — validity μόνο, όχι ο βαθμός
        if (stereoDetected) {
            if (pilotLevel < PILOT_OFF) stereoDetected = false
        } else {
            if (pilotLevel > PILOT_ON) stereoDetected = true
        }
        pilotLevelDbg = pilotLevel

        // v24 STEREO: L−R demod ανά sample @171k (sin(2φ)=2·s1·c1 — δωρεάν από
        // το PLL), φιλτράρεται στο δικό του delay line όπως το M.
        delayM[dIdx] = mpx
        delayS[dIdx] = mpx * 2f * s1 * c1
        dIdx = (dIdx + 1) % taps.size

        if (++phase == DECIM2) {
            phase = 0
            var m = 0f
            var sb = 0f
            var k = dIdx
            for (c in taps) {
                k = if (k == 0) taps.size - 1 else k - 1
                m += c * delayM[k]
                sb += c * delayS[k]
            }
            // blend (TDA1591 SNC pattern): RSSI ramp × pilot gate, ασύμμετρη
            // δυναμική (γρήγορα προς mono, αργά προς stereo — LA1888NM)
            val tgt = if (stereoDetected)
                ((signalDb - SIG_MONO) / (SIG_FULL - SIG_MONO)).coerceIn(0f, 1f) else 0f
            blend += (if (tgt < blend) BLEND_DOWN_A else BLEND_UP_A) * (tgt - blend)
            blendDbg = blend
            val s = blend * STEREO_GAIN * sb
            var l = m + s
            var r = m - s
            deemphL += DEEMPH_A * (l - deemphL)
            deemphR += DEEMPH_A * (r - deemphR)
            l = deemphL
            r = deemphR
            pcm[pcmLen++] = (l * AUDIO_GAIN).toInt().coerceIn(-32767, 32767).toShort()
            pcm[pcmLen++] = (r * AUDIO_GAIN).toInt().coerceIn(-32767, 32767).toShort()
            if (pcmLen == pcm.size) {
                t.write(pcm, 0, pcmLen)
                pcmLen = 0
            }
        }
    }

    /** Άδειασμα μισογεμάτου PCM buffer (τέλος chunk στο audio thread). */
    fun flushPcm() {
        val t = track ?: return
        if (pcmLen > 4096) {
            t.write(pcm, 0, pcmLen)
            pcmLen = 0
        }
    }

    /** Μετά από retune: καθαρό ξεκίνημα για το PLL (όχι κουβαλημένο windup). */
    fun resetPll() {
        freqInt = 0f
        pilotLevel = 0f
        stereoDetected = false
        blend = 0f
        blendDbg = 0f
    }

    fun stop() {
        try {
            track?.stop()
            track?.release()
        } catch (_: Exception) {}
        track = null
    }
}
