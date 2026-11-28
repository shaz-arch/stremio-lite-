package app.stremiolite.linux

import app.stremiolite.Async
import app.stremiolite.Platform
import app.stremiolite.Storage
import org.json.JSONObject

/** The Linux app's side of [Platform]: the main loop's threads, and JSON files in the profile's folder. */
object LinuxPlatform {
    fun install() {
        Platform.async = object : Async {
            override fun <T> background(work: () -> T, done: (Result<T>) -> Unit) = App.async(work, done)
            override fun later(delayMs: Long, block: () -> Unit) = App.later(delayMs, block)
        }
        Platform.storage = object : Storage {
            private fun read(store: String): JSONObject = Store.readJson("$store.json")
            override fun get(store: String, key: String): String? = read(store).let { if (it.has(key)) it.optString(key) else null }
            override fun keys(store: String): List<String> = read(store).keys().asSequence().toList()
            override fun edit(store: String, changes: Map<String, String?>) {
                val o = read(store)
                for ((k, v) in changes) if (v == null) o.remove(k) else o.put(k, v)
                Store.writeJson("$store.json", o)
            }
        }
    }
}
