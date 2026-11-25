package app.stremiolite.linux

import app.stremiolite.AiSearch
import app.stremiolite.AioRows
import app.stremiolite.RowSetup

/** "Check my AI setup": your key with Google, each AIOMetadata's answer, and the AI settings in your rows. */
object AiCheck {
    private fun label(a: app.stremiolite.Addon) = if (a.stickRows) "Stremonino's rows" else "Stremio's rows"

    fun screen(): Screen {
        lateinit var s: Screen
        val lines = linkedMapOf<String, String>()
        fun build() {
            val rows = mutableListOf<Row>()
            lines.forEach { (k, v) -> rows += Row(info(k, v)) }
            rows += Row("────────────────────")
            rows += Row("Look inside my rows' AI settings", "►", opens = {
                RowPicker.entry { _, l, pw ->
                    val (ready, desc) = AiSearch.describe(l.config, Prefs.geminiKey)
                    val r = desc.map { Row(it) }.toMutableList()
                    r += Row("────────────────────")
                    r += Row(if (ready) "These settings look right." else "Something here needs fixing.")
                    r += Row("Fix it: put my key in and switch AI on", "►", action = {
                        if (Prefs.geminiKey.isBlank()) { App.toast("Add your Gemini key first (Your keys)"); return@Row }
                        RowSetup.setAiKey(l.config, Prefs.geminiKey)
                        App.toast("Saving…")
                        App.async({ AioRows.save(l, pw) }) { res ->
                            App.toast(res.fold({ "Saved. AIOMetadata may keep an old empty answer for a while: ask something new." }) { e ->
                                "Couldn't save: " + (if (e is AioRows.Failed) e.message else Net.describe(e))
                            })
                        }
                    })
                    Screen("Your rows' AI settings", r)
                }
            })
            s.rows = rows
            App.refresh()
        }
        val key = Prefs.geminiKey
        lines["Your Gemini key"] = if (key.isBlank()) "not set (Settings › Your keys)" else "checking with Google…"
        val installs = AiSearch.installs(Home.all())
        if (installs.isEmpty()) lines["AIOMetadata"] = "none on this account"
        installs.forEach { lines[label(it)] = "asking…" }
        s = Screen("Check my AI setup", emptyList())
        build()
        if (key.isNotBlank()) App.async({ AiSearch.testKey(key) }) { r ->
            lines["Your Gemini key"] = r.fold({ it?.let { "✗ $it" } ?: "✓ works with Google" }) { "✗ " + Net.describe(it) }
            build()
        }
        installs.forEach { a ->
            App.async({ AiSearch.ask(listOf(a), "feel-good family films") }) { r ->
                lines[label(a)] = r.fold({ "✓ AI answered: ${it.size} titles" }) { "✗ " + (it.message ?: Net.describe(it)) }
                build()
            }
        }
        return s
    }
}
