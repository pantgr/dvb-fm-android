package gr.pantelis.dvbtv

import java.nio.charset.Charset

/** Κοινά εργαλεία DVB SI (μοιράζονται TsPsiParser + EitParser). */

internal class SectionTracker {
    private val seen = HashSet<Int>()
    private var last = -1
    val done get() = last >= 0 && seen.size >= last + 1
    fun mark(secNum: Int, lastNum: Int) { seen.add(secNum); last = lastNum }
}

/** Μαζεύει PSI sections από TS payloads (pointer_field, multi-packet sections, stuffing). */
internal class SectionAssembler(private val tableIds: Set<Int>, private val onSection: (ByteArray) -> Unit) {
    constructor(tableId: Int, onSection: (ByteArray) -> Unit) : this(setOf(tableId), onSection)

    private val buf = ByteArray(4096)
    private var len = 0
    private var need = -1 // συνολικά bytes τρέχοντος section, -1 = δεν μαζεύουμε
    private var skip = 0  // bytes ξένου section που προσπερνάμε

    fun reset() { len = 0; need = -1; skip = 0 }

    fun feed(b: ByteArray, start: Int, end: Int, pusi: Boolean) {
        if (!pusi) {
            if (need >= 0 || skip > 0) collect(b, start, end)
            return
        }
        val pointer = b[start].toInt() and 0xFF
        val newStart = start + 1 + pointer
        if (newStart > end) { reset(); return }
        if (need >= 0 || skip > 0) collect(b, start + 1, newStart) // ουρά προηγούμενου section
        need = -1; len = 0; skip = 0
        collect(b, newStart, end)
    }

    private fun collect(b: ByteArray, startIn: Int, end: Int) {
        var p = startIn
        while (p < end) {
            if (skip > 0) {
                val t = minOf(skip, end - p); skip -= t; p += t
                continue
            }
            if (need < 0) {
                if ((b[p].toInt() and 0xFF) == 0xFF) return // stuffing μέχρι το τέλος του packet
                if (end - p < 3) return // header κομμένο σε όριο packet — τα tables επαναλαμβάνονται
                val tid = b[p].toInt() and 0xFF
                val secLen = ((b[p + 1].toInt() and 0x0F) shl 8) or (b[p + 2].toInt() and 0xFF)
                val total = secLen + 3
                if (tid !in tableIds || total > buf.size) { skip = total; continue }
                need = total; len = 0
            }
            val take = minOf(need - len, end - p)
            System.arraycopy(b, p, buf, len, take)
            len += take; p += take
            if (len == need) {
                onSection(buf.copyOf(need))
                need = -1; len = 0
            }
        }
    }
}

/** EN 300 468 Annex A: προαιρετικό charset selector byte μπροστά από το text. */
internal object DvbText {
    fun decode(b: ByteArray, off: Int, len: Int): String {
        if (len <= 0) return ""
        var start = off
        var n = len
        var cs = csOrLatin("ISO-8859-1")
        val first = b[off].toInt() and 0xFF
        if (first < 0x20) {
            when {
                first in 0x01..0x0B -> { cs = csOrLatin("ISO-8859-${first + 4}"); start++; n-- } // 0x03 -> 8859-7 (ελληνικά)
                first == 0x10 && len >= 3 -> {
                    val table = ((b[off + 1].toInt() and 0xFF) shl 8) or (b[off + 2].toInt() and 0xFF)
                    cs = csOrLatin("ISO-8859-$table"); start += 3; n -= 3
                }
                first == 0x15 -> { cs = Charsets.UTF_8; start++; n-- }
                else -> { start++; n-- }
            }
        }
        if (n <= 0) return ""
        return String(b, start, n, cs).filter { it.code !in 0x80..0x9F }.trim()
    }

    private fun csOrLatin(name: String): Charset =
        try { Charset.forName(name) } catch (e: Exception) { Charset.forName("ISO-8859-1") }
}
