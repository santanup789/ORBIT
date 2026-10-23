package app.orbit.finder.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class FriendStore(context: Context) {
    private val prefs = context.getSharedPreferences("orbit_friends", Context.MODE_PRIVATE)
    fun load(): List<Friend> = runCatching {
        val a = JSONArray(prefs.getString("items", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            Friend(o.getString("id"), o.optString("pk"), o.getString("name"),
                runCatching { DeviceIcon.valueOf(o.optString("icon")) }.getOrDefault(DeviceIcon.PHONE),
                runCatching { TrustState.valueOf(o.optString("trust")) }.getOrDefault(TrustState.PENDING),
                o.optLong("seen"), firebaseUid = o.optString("uid"), gpsPublicKey = o.optString("gpsKey"))
        }}
    }.getOrDefault(emptyList())

    fun save(items: List<Friend>) {
        val a = JSONArray()
        items.forEach { f -> a.put(JSONObject().apply {
            put("id", f.id); put("pk", f.publicKey); put("name", f.name); put("icon", f.icon.name)
            put("trust", f.trust.name); put("seen", f.lastSeenMs)
            put("uid", f.firebaseUid); put("gpsKey", f.gpsPublicKey)
        }) }
        prefs.edit().putString("items", a.toString()).apply()
    }
}
