package gr.pantelis.dvbtv

import java.util.TreeMap

/**
 * EIT actual TS (PID 0x12): present/following (table 0x4E → now/next) +
 * schedule (tables 0x50–0x5F → πλήρες πρόγραμμα ανά SID, για EPG browsing).
 * Τρέχει συνέχεια δίπλα στο playback (ο TsRouter το ταΐζει).
 * DVB ώρες = UTC (MJD + BCD, EN 300 468 Annex C) → epoch seconds.
 */
class EitParser {
    data class Event(val name: String, val startUtcSec: Long, val durSec: Int, val desc: String = "") {
        val endUtcSec get() = startUtcSec + durSec
    }

    private val nowBySid = HashMap<Int, Event>()
    private val nextBySid = HashMap<Int, Event>()
    private val scheduleBySid = HashMap<Int, TreeMap<Long, Event>>()

    private val eit = SectionAssembler(setOf(0x4E) + (0x50..0x5F).toSet()) { parseEit(it) }
    private val pending = ByteArray(188)
    private var pendingLen = 0

    @Synchronized fun now(sid: Int): Event? = nowBySid[sid]
    @Synchronized fun next(sid: Int): Event? = nextBySid[sid]

    /** Τρέχουσα + μελλοντικές εκπομπές του SID, χρονολογικά (index 0 = τώρα). */
    @Synchronized
    fun events(sid: Int): List<Event> {
        val nowSec = System.currentTimeMillis() / 1000
        val out = ArrayList<Event>()
        nowBySid[sid]?.let { out.add(it) }
        nextBySid[sid]?.let { ev -> if (out.none { it.startUtcSec == ev.startUtcSec }) out.add(ev) }
        scheduleBySid[sid]?.values?.forEach { ev ->
            if (ev.endUtcSec > nowSec && out.none { it.startUtcSec == ev.startUtcSec }) out.add(ev)
        }
        return out.sortedBy { it.startUtcSec }
    }

    @Synchronized
    fun reset() {
        nowBySid.clear(); nextBySid.clear(); scheduleBySid.clear()
        eit.reset()
        pendingLen = 0
    }

    @Synchronized
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
        while (off < len && data[off] != 0x47.toByte()) off++
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
        if (pid != 0x0012) return
        val pusi = (b[off + 1].toInt() and 0x40) != 0
        val afc = (b[off + 3].toInt() shr 4) and 0x3
        if (afc == 0 || afc == 2) return
        var p = off + 4
        if (afc == 3) p += 1 + (b[p].toInt() and 0xFF)
        if (p >= off + 188) return
        eit.feed(b, p, off + 188, pusi)
    }

    private fun parseEit(s: ByteArray) {
        val tableId = s[0].toInt() and 0xFF
        val secLen = ((s[1].toInt() and 0x0F) shl 8) or (s[2].toInt() and 0xFF)
        val sid = ((s[3].toInt() and 0xFF) shl 8) or (s[4].toInt() and 0xFF)
        val secNum = s[6].toInt() and 0xFF // για 0x4E: 0 = present (τώρα), 1 = following (μετά)
        val end = 3 + secLen - 4
        var p = 14
        while (p + 12 <= end) {
            val mjd = ((s[p + 2].toInt() and 0xFF) shl 8) or (s[p + 3].toInt() and 0xFF)
            val hh = bcd(s[p + 4]); val mm = bcd(s[p + 5]); val ss = bcd(s[p + 6])
            val durSec = bcd(s[p + 7]) * 3600 + bcd(s[p + 8]) * 60 + bcd(s[p + 9])
            val dll = ((s[p + 10].toInt() and 0x0F) shl 8) or (s[p + 11].toInt() and 0xFF)
            var d = p + 12
            val dEnd = minOf(d + dll, end)
            var name = ""
            val desc = StringBuilder()
            while (d + 2 <= dEnd) {
                val tag = s[d].toInt() and 0xFF
                val dl = s[d + 1].toInt() and 0xFF
                if (tag == 0x4D && d + 2 + dl <= dEnd) { // short_event: lang(3)+nameLen+name+textLen+text
                    val nameLen = s[d + 5].toInt() and 0xFF
                    if (d + 6 + nameLen <= d + 2 + dl) {
                        name = DvbText.decode(s, d + 6, nameLen)
                        val textLenPos = d + 6 + nameLen
                        if (textLenPos < d + 2 + dl) {
                            val textLen = s[textLenPos].toInt() and 0xFF
                            if (textLenPos + 1 + textLen <= d + 2 + dl && textLen > 0)
                                desc.append(DvbText.decode(s, textLenPos + 1, textLen))
                        }
                    }
                }
                if (tag == 0x4E && d + 2 + dl <= dEnd) { // extended_event: num(1)+lang(3)+itemsLen+items+textLen+text
                    val itemsLen = s[d + 6].toInt() and 0xFF
                    val textLenPos = d + 7 + itemsLen
                    if (textLenPos < d + 2 + dl) {
                        val textLen = s[textLenPos].toInt() and 0xFF
                        if (textLenPos + 1 + textLen <= d + 2 + dl && textLen > 0) {
                            if (desc.isNotEmpty()) desc.append(" ")
                            desc.append(DvbText.decode(s, textLenPos + 1, textLen))
                        }
                    }
                }
                d += 2 + dl
            }
            if (mjd != 0xFFFF && name.isNotBlank()) {
                val startUtc = (mjd.toLong() - 40587L) * 86400L + hh * 3600L + mm * 60L + ss
                val ev = Event(name, startUtc, durSec, desc.toString())
                when {
                    tableId == 0x4E && secNum == 0 -> nowBySid[sid] = ev
                    tableId == 0x4E && secNum == 1 -> nextBySid[sid] = ev
                    tableId >= 0x50 -> scheduleBySid.getOrPut(sid) { TreeMap() }[ev.startUtcSec] = ev
                }
            }
            p = dEnd
        }
    }

    private fun bcd(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return (v shr 4) * 10 + (v and 0x0F)
    }
}
