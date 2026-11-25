package app.stremiolite

/** "Check my AI setup": your key with Google, each AIOMetadata's answer, and the AI settings in your rows. */
object AiCheck {
    fun screen(app: MainActivity): Screen {
        lateinit var s: Screen
        val lines = linkedMapOf<String, String>()
        var config: Pair<Boolean, List<String>>? = null
        fun build() {
            val rows = mutableListOf<Row>()
            lines.forEach { (k, v) -> rows += Row(info(k, v)) }
            rows += Row("────────────────────")
            rows += Row("Look inside my rows' AI settings", "►", opens = {
                RowPicker.entry(app) { _, l, pw ->
                    config = AiSearch.describe(l.config, Settings.geminiKey(app))
                    val (ready, desc) = config!!
                    val r = mutableListOf<Row>()
                    desc.forEach { r += Row(it) }
                    r += Row("────────────────────")
                    r += Row(if (ready) "These settings look right." else "Something here needs fixing.")
                    r += Row("Fix it: put my key in and switch AI on", "►", action = {
                        val key = Settings.geminiKey(app)
                        if (key.isBlank()) { app.toast("Add your Gemini key first (Your keys)"); return@Row }
                        RowSetup.setAiKey(l.config, key)
                        app.toast("Saving…")
                        Net.async({ AioRows.save(l, pw) }) { res ->
                            app.toast(res.fold({ "Saved. AIOMetadata may keep an old empty answer for a while: ask something new." }) { e ->
                                "Couldn't save: " + (if (e is AioRows.Failed) e.message else Net.describe(e))
                            })
                        }
                    })
                    Screen("Your rows' AI settings", r)
                }
            })
            s.rows = rows
            app.refresh(s)
        }
        fun run() {
            val key = Settings.geminiKey(app)
            lines.clear()
            lines["Your Gemini key"] = if (key.isBlank()) "not set (Settings › Your keys)" else "checking with Google…"
            val installs = AiSearch.installs(Home.allAddons())
            if (installs.isEmpty()) lines["AIOMetadata"] = "none on this account"
            installs.forEach { lines[(if (it.stickRows) "Stremonino's rows" else "Stremio's rows")] = "asking…" }
            build()
            if (key.isNotBlank()) Net.async({ AiSearch.testKey(key) }) { r ->
                lines["Your Gemini key"] = r.fold({ it?.let { "✗ $it" } ?: "✓ works with Google" }) { "✗ " + Net.describe(it) }
                build()
            }
            installs.forEach { a ->
                Net.async({ AiSearch.ask(listOf(a), "feel-good family films") }) { r ->
                    lines[(if (a.stickRows) "Stremonino's rows" else "Stremio's rows")] = r.fold({ "✓ AI answered: ${it.size} titles" }) { "✗ " + (it.message ?: Net.describe(it)) }
                    build()
                }
            }
        }
        s = Screen("Check my AI setup", emptyList())
        var started = false
        s.onShow = { if (!started) { started = true; run() } else build() }
        return s
    }
}
