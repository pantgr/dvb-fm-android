package gr.pantelis.dvbtv

/**
 * Minimal DVB PSI parser για channel scan: PAT (PID 0x000, table 0x00) + SDT actual
 * (PID 0x011, table 0x42) + NIT actual (PID 0x010, table 0x40, LCN descriptor 0x83).
 * Feed raw TS bytes· όταν [complete], το [tvServices] δίνει SID + όνομα για το τρέχον mux.
 * ISO 13818-1 + EN 300 468. (Το LCN τελικά ΔΕΝ χρησιμοποιείται για αρίθμηση — βλ. SKILL.md.)
 */
class TsPsiParser {
    data class Service(val sid: Int, val name: String, val type: Int)

    private val patSids = HashSet<Int>()
    private val sdtServices = LinkedHashMap<Int, Service>()
    private val lcnBySid = HashMap<Int, Int>()
    private var patTracker = SectionTracker()
    private var sdtTracker = SectionTracker()
    private var nitTracker = SectionTracker()

    private val pat = SectionAssembler(0x00) { parsePat(it) }
    private val sdt = SectionAssembler(0x42) { parseSdt(it) }
    private val nit = SectionAssembler(0x40) { parseNit(it) }

    private val pending = ByteArray(188)
    private var pendingLen = 0

    val complete: Boolean
        get() = patTracker.done && sdtTracker.done && nitTracker.done

    fun reset() {
        patSids.clear(); sdtServices.clear(); lcnBySid.clear()
        patTracker = SectionTracker(); sdtTracker = SectionTracker(); nitTracker = SectionTracker()
        pat.reset(); sdt.reset(); nit.reset()
        pendingLen = 0
    }

    // ΟΛΑ τα services (TV + radio), όπως το Windows dvb_tv — κανένα type filter
    fun tvServices(): List<Service> =
        sdtServices.values.filter { it.sid in patSids && it.name.isNotBlank() }

    fun lcn(sid: Int): Int = lcnBySid[sid] ?: 0

    fun feed(data: ByteArray, len: Int) {
        var off = 0
        if (pendingLen > 0) {
            val take = minOf(188 - pendingLen, len)
            System.arraycopy(data, 0, pending, pendingLen, take)
            pendingLen += take
            off = take
            if (pendingLen < 188) return
            packet(pending, 0)
            pendingLen = 0
        }
        while (off < len && data[off] != 0x47.toByte()) off++ // resync
        while (off + 188 <= len) {
            if (data[off] != 0x47.toByte()) { off++; continue }
            packet(data, off)
            off += 188
        }
        if (off < len) {
            pendingLen = len - off
            System.arraycopy(data, off, pending, 0, pendingLen)
        }
    }

    private fun packet(b: ByteArray, off: Int) {
        val pid = ((b[off + 1].toInt() and 0x1F) shl 8) or (b[off + 2].toInt() and 0xFF)
        val assembler = when (pid) {
            0x0000 -> pat
            0x0010 -> nit
            0x0011 -> sdt
            else -> return
        }
        val pusi = (b[off + 1].toInt() and 0x40) != 0
        val afc = (b[off + 3].toInt() shr 4) and 0x3
        if (afc == 0 || afc == 2) return // no payload
        var p = off + 4
        if (afc == 3) p += 1 + (b[p].toInt() and 0xFF) // skip adaptation field
        if (p >= off + 188) return
        assembler.feed(b, p, off + 188, pusi)
    }

    // ---- table parsing ----

    private fun parsePat(s: ByteArray) {
        val secLen = ((s[1].toInt() and 0x0F) shl 8) or (s[2].toInt() and 0xFF)
        val loopEnd = 3 + secLen - 4 // μείον CRC
        var p = 8
        while (p + 4 <= loopEnd) {
            val prog = ((s[p].toInt() and 0xFF) shl 8) or (s[p + 1].toInt() and 0xFF)
            if (prog != 0) patSids.add(prog) // 0 = NIT
            p += 4
        }
        patTracker.mark(s[6].toInt() and 0xFF, s[7].toInt() and 0xFF)
    }

    private fun parseSdt(s: ByteArray) {
        val secLen = ((s[1].toInt() and 0x0F) shl 8) or (s[2].toInt() and 0xFF)
        val end = 3 + secLen - 4
        var p = 11
        while (p + 5 <= end) {
            val sid = ((s[p].toInt() and 0xFF) shl 8) or (s[p + 1].toInt() and 0xFF)
            val dloop = ((s[p + 3].toInt() and 0x0F) shl 8) or (s[p + 4].toInt() and 0xFF)
            var d = p + 5
            val dEnd = minOf(d + dloop, end)
            var name = ""
            var type = -1
            while (d + 2 <= dEnd) {
                val tag = s[d].toInt() and 0xFF
                val dl = s[d + 1].toInt() and 0xFF
                if (tag == 0x48 && d + 2 + dl <= dEnd) { // service_descriptor
                    type = s[d + 2].toInt() and 0xFF
                    val provLen = s[d + 3].toInt() and 0xFF
                    val nameLenPos = d + 4 + provLen
                    if (nameLenPos < d + 2 + dl) {
                        val nameLen = s[nameLenPos].toInt() and 0xFF
                        if (nameLenPos + 1 + nameLen <= d + 2 + dl)
                            name = DvbText.decode(s, nameLenPos + 1, nameLen)
                    }
                }
                d += 2 + dl
            }
            if (type >= 0) sdtServices[sid] = Service(sid, name, type)
            p = dEnd
        }
        sdtTracker.mark(s[6].toInt() and 0xFF, s[7].toInt() and 0xFF)
    }

    // NIT actual: network descriptors (skip) → TS loop → logical_channel_descriptor 0x83
    private fun parseNit(s: ByteArray) {
        val secLen = ((s[1].toInt() and 0x0F) shl 8) or (s[2].toInt() and 0xFF)
        val end = 3 + secLen - 4
        var p = 8
        if (p + 2 > end) return
        val ndl = ((s[p].toInt() and 0x0F) shl 8) or (s[p + 1].toInt() and 0xFF)
        p += 2 + ndl
        if (p + 2 > end) return
        p += 2 // transport_stream_loop_length
        while (p + 6 <= end) {
            val tdl = ((s[p + 4].toInt() and 0x0F) shl 8) or (s[p + 5].toInt() and 0xFF)
            var d = p + 6
            val dEnd = minOf(d + tdl, end)
            while (d + 2 <= dEnd) {
                val tag = s[d].toInt() and 0xFF
                val dl = s[d + 1].toInt() and 0xFF
                if (tag == 0x83 && d + 2 + dl <= dEnd) {
                    var e = d + 2
                    while (e + 4 <= d + 2 + dl) {
                        val sid = ((s[e].toInt() and 0xFF) shl 8) or (s[e + 1].toInt() and 0xFF)
                        val lcn = ((s[e + 2].toInt() and 0x03) shl 8) or (s[e + 3].toInt() and 0xFF)
                        if (lcn > 0) lcnBySid[sid] = lcn
                        e += 4
                    }
                }
                d += 2 + dl
            }
            p = dEnd
        }
        nitTracker.mark(s[6].toInt() and 0xFF, s[7].toInt() and 0xFF)
    }
}
