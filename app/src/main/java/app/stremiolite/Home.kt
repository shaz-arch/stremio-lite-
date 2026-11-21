package app.stremiolite

/**
 * Step 2 home: who's signed in, the add-ons on the account (with a live connection check
 * for each), plus the step 1 demo menus and device check. Step 3 replaces the demo menus
 * with real catalogue rows.
 */
object Home {
    private var addons: List<Addon>? = null
    private var loading = false
    private var loadError: String? = null

    /** Connection result per add-on (by transport URL), shown on the list and details screens. */
    private val checks = mutableMapOf<String, String>()

    fun forget() {
        addons = null
        loadError = null
        checks.clear()
    }

    fun screen(app: MainActivity): Screen {
        val who = Row(info("Signed in as", Account.email(app).ifEmpty { "…" }))
        val addonsRow = Row("Your add-ons", "loading…", opens = { addonList(app) })
        lateinit var screen: Screen

        fun showState() {
            addonsRow.detail = when {
                addons != null -> "${addons!!.size}  ►"
                loading -> "loading…"
                else -> "failed: ${loadError ?: "?"}"
            }
            app.refresh(screen)
        }

        fun load() {
            val key = Account.authKey(app) ?: return
            loading = true
            showState()
            Net.async({
                val email = runCatching { Stremio.userEmail(key) }.getOrDefault("")
                email to Stremio.addons(key)
            }) { r ->
                loading = false
                r.onSuccess { (email, list) ->
                    addons = list
                    loadError = null
                    checks.clear()
                    if (email.isNotEmpty()) {
                        Account.saveEmail(app, email)
                        who.label = info("Signed in as", email)
                    }
                }.onFailure { e ->
                    loadError = if (e is Stremio.ApiError) e.message else Net.describe(e)
                }
                showState()
            }
        }

        screen = Screen(
            title = "Stremio Lite",
            tag = "step 2",
            rows = listOf(
                who,
                addonsRow,
                Row("Reload add-ons from my account", action = { if (!loading) load() }),
                Row("────────────────────"),
                Row("Demo menus (sample data)", opens = { DemoMenus.home(app) }),
                Row("Device check", opens = { DemoMenus.deviceCheck(app) }),
                Row("About", opens = { DemoMenus.about() }),
                Row("Sign out", opens = { signOut(app) }),
            ),
        )
        screen.position = 1
        screen.onShow = {
            if (addons == null && !loading) load() else showState()
        }
        return screen
    }

    private fun addonList(app: MainActivity): Screen {
        val list = addons
        if (list == null) {
            return Screen("Your add-ons", listOf(Row(if (loading) "Still loading, go back and try again in a moment." else "Couldn't load: ${loadError ?: "?"}")))
        }
        val summary = Row("")
        lateinit var screen: Screen
        val rows = list.map { a -> a to Row(a.name, "", opens = { addonDetails(app, a) }) }

        fun update() {
            rows.forEach { (a, row) -> row.detail = "v${a.version}  " + (checks[a.transportUrl] ?: "checking…") }
            val done = list.count { checks.containsKey(it.transportUrl) }
            val ok = list.count { checks[it.transportUrl]?.startsWith("OK") == true }
            val skipped = list.count { checks[it.transportUrl]?.startsWith("built") == true }
            val failed = done - ok - skipped
            summary.label = if (done < list.size) "Checking ${list.size} add-ons… $done done"
            else "${list.size} add-ons: $ok connect" +
                (if (failed > 0) ", $failed FAILED" else "") +
                (if (skipped > 0) ", $skipped built into Stremio" else "")
            app.refresh(screen)
        }

        fun checkAll() {
            checks.clear()
            update()
            for (a in list) {
                if (a.isLocal) {
                    checks[a.transportUrl] = "built into Stremio (skipped)"
                    continue
                }
                Net.async({
                    val t0 = System.currentTimeMillis()
                    Net.get(a.transportUrl, timeoutMs = 10_000)
                    System.currentTimeMillis() - t0
                }) { r ->
                    checks[a.transportUrl] = r.fold({ ms -> "OK ${ms} ms" }, { e -> "FAILED: ${Net.describe(e)}" })
                    update()
                }
            }
            update()
        }

        screen = Screen(
            "Your add-ons",
            listOf(summary, Row("────────────────────")) +
                rows.map { it.second } +
                listOf(Row("────────────────────"), Row("Check connections again", action = { checkAll() })),
        )
        screen.position = 2
        screen.onShow = { if (checks.isEmpty()) checkAll() else update() }
        return screen
    }

    private fun addonDetails(app: MainActivity, a: Addon): Screen {
        val searchOnly = a.catalogs.count { it.required.isNotEmpty() }
        val head = listOf(
            Row(info("Version", a.version)),
            Row(info("Server", a.host)),
            Row(info("Connection", checks[a.transportUrl] ?: "not checked yet")),
            Row(info("Types", a.types.joinToString(", ").ifEmpty { "-" })),
            Row(info("Provides", a.resources.joinToString(", ").ifEmpty { "-" })),
            Row(info("Catalogue rows", "${a.catalogs.size}" + if (searchOnly > 0) " ($searchOnly search-only)" else "")),
        )
        val cats = if (a.catalogs.isEmpty()) emptyList() else
            listOf(Row("──── Catalogue rows (open in step 3) ────")) +
                a.catalogs.map { c ->
                    Row(c.name, c.type + if (c.required.isNotEmpty()) " · search only" else "")
                }
        return Screen(a.name, head + cats)
    }

    private fun signOut(app: MainActivity) = Screen(
        "Sign out?",
        listOf(
            Row("No, go back", action = { app.back() }),
            Row("Yes, sign out of this TV", action = {
                Account.clear(app)
                forget()
                app.resetTo(SignIn.screen(app))
            }),
        ),
    )
}

/** "Label ........ value" on one line, so values sit next to their labels. */
fun info(label: String, value: String) = "${label.padEnd(22)} $value"
