package gr.pantelis.dvbtv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ChannelStore {
    data class Channel(val name: String, val sid: Int, val freqHz: Long, val lcn: Int = 0, val snr: Int = 0)

    private fun file(ctx: Context) = File(ctx.filesDir, "channels.json")

    fun save(ctx: Context, channels: List<Channel>) {
        val arr = JSONArray()
        for (c in channels)
            arr.put(JSONObject()
                .put("name", c.name).put("sid", c.sid).put("freqHz", c.freqHz)
                .put("lcn", c.lcn).put("snr", c.snr))
        file(ctx).writeText(arr.toString())
    }

    fun load(ctx: Context): List<Channel> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Channel(o.getString("name"), o.getInt("sid"), o.getLong("freqHz"),
                    o.optInt("lcn", 0), o.optInt("snr", 0))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
