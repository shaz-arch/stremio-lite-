package app.stremiolite

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Plain HTTP on a small pool of background threads; results come back on the main thread. */
object Net {
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        HttpsURLConnection.setDefaultSSLSocketFactory(Trust.socketFactory(context.applicationContext))
        Http.userAgent = "Stremonino/${BuildConfigLite.VERSION} (Android TV)"
    }

    /** Run [work] in the background, then [done] on the main thread. */
    fun <T> async(work: () -> T, done: (Result<T>) -> Unit) {
        pool.execute {
            // Lower priority than the screen, so loading never makes scrolling stutter.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val result = runCatching(work)
            main.post { done(result) }
        }
    }

    fun later(delayMs: Long, block: Runnable) = main.postDelayed(block, delayMs)
    fun cancel(block: Runnable) = main.removeCallbacks(block)

    /** GET; throws [Http.HttpError] for 4xx/5xx unless [allowErrors]. */
    fun get(url: String, timeoutMs: Int = 15_000, allowErrors: Boolean = false): String = Http.get(url, timeoutMs, allowErrors)

    /** POST a JSON body. API errors come back as JSON, so error codes are allowed through. */
    fun postJson(url: String, body: String, timeoutMs: Int = 15_000): String = Http.postJson(url, body, timeoutMs)

    fun getBytes(url: String): ByteArray = Http.getBytes(url)

    /** A short, plain-English reason for a failed request. */
    fun describe(e: Throwable): String {
        var t: Throwable? = e
        while (t != null) {
            when (t) {
                is CertificateException -> return "certificate not trusted"
                is SocketTimeoutException -> return "timed out"
                is UnknownHostException -> return "address not found"
                is Http.HttpError -> return "server said ${t.code}"
                is java.net.ConnectException -> return "connection refused"
                is org.json.JSONException -> return "unexpected reply"
            }
            t = t.cause
        }
        return when (e) {
            is SSLException -> "secure connection failed"
            // Our own errors (e.g. Stremio's reply) carry a readable message; class names are scrambled in release builds.
            else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }
    }
}

/**
 * Android 5.1 has an out-of-date list of trusted certificate authorities, so sites using
 * newer ones (Let's Encrypt among them) fail. This checks the stick's own list first, and
 * only if that fails checks Mozilla's current list bundled in the app (res/raw/extra_roots.pem).
 */
object Trust {
    /** How many connections were rescued by the bundled list (shown on Device check). */
    @Volatile
    var rescued = 0

    fun socketFactory(context: Context): javax.net.ssl.SSLSocketFactory {
        val system = trustManagerFor(null)
        val combined = object : X509TrustManager {
            private val bundled: X509TrustManager by lazy { trustManagerFor(loadBundled(context)) }

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                system.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    system.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    bundled.checkServerTrusted(chain, authType) // throws if this fails too
                    rescued++
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
        }
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf(combined), null)
        return ssl.socketFactory
    }

    private fun trustManagerFor(keyStore: KeyStore?): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(keyStore)
        return f.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    private fun loadBundled(context: Context): KeyStore {
        val pem = context.resources.openRawResource(R.raw.extra_roots).use {
            it.readBytes().toString(Charsets.US_ASCII)
        }
        val factory = CertificateFactory.getInstance("X.509")
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null, null)
        Regex("-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", RegexOption.DOT_MATCHES_ALL)
            .findAll(pem)
            .forEachIndexed { i, m ->
                try {
                    val der = Base64.decode(m.groupValues[1], Base64.DEFAULT)
                    store.setCertificateEntry("root$i", factory.generateCertificate(ByteArrayInputStream(der)))
                } catch (e: Exception) {
                    // A root this old Android can't parse; skip it.
                }
            }
        return store
    }
}
