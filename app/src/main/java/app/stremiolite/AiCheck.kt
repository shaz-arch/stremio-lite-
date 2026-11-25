package app.stremiolite

/** "Check my AI setup": your key with Google, each AIOMetadata's answer, and the AI settings in your rows. */
object AiCheck {
    fun screen(app: MainActivity): Screen {
        lateinit var s: Screen
        val lines = linkedMapOf<String, String>()
        var config: Pair<Boolean, List<String>>? = null
        var working: String? = null
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
                    r += Row("Fix it: put my key in, switch AI on" + (working?.let { " and use $it" } ?: ""), "►", action = {
                        val key = Settings.geminiKey(app)
                        if (key.isBlank()) { app.toast("Add your Gemini key first (Your keys)"); return@Row }
                        RowSetup.setAiKey(l.config, key, working)
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
            if (key.isNotBlank()) Net.async({ AiSearch.findModel(key) }) { r ->
                val (model, tried) = r.getOrElse { null to listOf("?" to Net.describe(it)) }
                working = model
                val busy = tried.count { it.second?.let { why -> AiSearch.isBusy(why) } == true }
                lines["Your Gemini key"] = when {
                    model != null -> "✓ works" + if (busy > 0) " ($busy busy model${if (busy > 1) "s" else ""} skipped)" else ""
                    tried.isNotEmpty() && tried.all { it.second?.let { why -> AiSearch.isBusy(why) } == true } -> "✓ accepted, but Google is busy: try in a while"
                    else -> "✗ " + (tried.lastOrNull()?.second ?: "no answer")
                }
                if (model != null) lines["Model answering now"] = model
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
