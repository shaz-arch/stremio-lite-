package app.stremiolite

import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Settings › Backup: this device's own settings (your keys, the kids lock, drives, player…), locked
 * with a passphrase only you know (AES-GCM; the key is made from the passphrase with PBKDF2), and kept
 * on your account, so a reset or new device gets them back. Without the passphrase it can't be read,
 * by anyone (us included); a forgotten passphrase means making a new backup.
 */
object Backup {
    const val ID = "stremiolite:backup"
    private const val ROUNDS = 40_000
    const val MIN_PASS = 8

    class Saved(val at: Long, val device: String, val blob: String)

    /** Locks [plain] with [pass]. */
    fun seal(plain: String, pass: String): String {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(pass, salt), GCMParameterSpec(128, iv))
        val data = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return JSONObject().put("v", 1).put("salt", B64.encode(salt)).put("iv", B64.encode(iv)).put("data", B64.encode(data)).toString()
    }

    /** Unlocks a backup; null if the passphrase is wrong (or it's damaged). */
    fun open(blob: String, pass: String): String? = runCatching {
        val o = JSONObject(blob)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(pass, B64.decode(o.getString("salt"))), GCMParameterSpec(128, B64.decode(o.getString("iv"))))
        String(c.doFinal(B64.decode(o.getString("data"))), Charsets.UTF_8)
    }.getOrNull()

    private fun key(pass: String, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val k = f.generateSecret(PBEKeySpec(pass.toCharArray(), salt, ROUNDS, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    /** The backup kept for [kind] ("android" or "linux") on the account record. */
    fun read(raw: String?, kind: String): Saved? {
        val o = raw?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { runCatching { JSONObject(it.optString("name")) }.getOrNull() } ?: return null
        val b = o.optJSONObject(kind) ?: return null
        return Saved(b.optLong("at"), b.optString("device"), b.optString("blob").ifEmpty { return null })
    }

    fun record(base: String?, kind: String, saved: Saved?): JSONObject {
        val item = base?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Sync.newItem(ID, "other", "{}")
        val o = runCatching { JSONObject(item.optString("name")) }.getOrNull() ?: JSONObject()
        if (saved == null) o.remove(kind) else o.put(kind, JSONObject().put("at", saved.at).put("device", saved.device).put("blob", saved.blob))
        return item.put("name", o.toString()).put("removed", true).put("temp", false).put("_mtime", Sync.iso(System.currentTimeMillis()))
    }

    /** Android's settings files as JSON, keeping each value's type ({"k":{"t":"s","v":…}}). */
    fun prefsToJson(all: Map<String, *>, skip: Set<String> = emptySet()): JSONObject {
        val o = JSONObject()
        for ((k, v) in all) {
            if (k in skip) continue
            val e = when (v) {
                is String -> JSONObject().put("t", "s").put("v", v)
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is Set<*> -> JSONObject().put("t", "ss").put("v", org.json.JSONArray(v.map { it.toString() }))
                else -> null
            } ?: continue
            o.put(k, e)
        }
        return o
    }

    /** What isn't backed up: sync bookkeeping (so the account's newer settings still win). */
    val SKIP = setOf("syncedAt", "pendingSync")
}
