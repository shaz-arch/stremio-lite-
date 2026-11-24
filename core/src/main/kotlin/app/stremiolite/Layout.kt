package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject

/**
 * Your own arrangement of the Index, on top of the automatic one: which folder a row sits in,
 * its name, and the order of rows and folders. Only changes are stored, so rows you haven't
 * touched (and new rows an add-on adds later) are still filed automatically.
 * Saved with the shared settings, so every device shows the same Index.
 */
object Layout {
    /** The front page (Browse on Home), as a folder path. */
    const val FRONT = "@front"

    class Data(
        /** Row key → folder path ("Genres > Horror"); "" = the front page. */
        val folder: MutableMap<String, String> = LinkedHashMap(),
        /** Row key → its name in the Index. */
        val name: MutableMap<String, String> = LinkedHashMap(),
        /** Folder path ("" = the top of the Index, [FRONT] = the front page) → items in order ("f:Name" or "r:rowKey"). */
        val order: MutableMap<String, MutableList<String>> = LinkedHashMap(),
        /** Folder paths you've hidden (with everything inside them). */
        val hidden: MutableSet<String> = LinkedHashSet(),
    ) {
        val isEmpty get() = folder.isEmpty() && name.isEmpty() && order.isEmpty() && hidden.isEmpty()
    }

    fun parse(s: String?): Data {
        val o = runCatching { JSONObject(s ?: return Data()) }.getOrNull() ?: return Data()
        val d = Data()
        o.optJSONObject("folder")?.let { f -> f.keys().forEach { d.folder[it] = f.optString(it) } }
        o.optJSONObject("name")?.let { f -> f.keys().forEach { d.name[it] = f.optString(it) } }
        o.optJSONObject("order")?.let { f ->
            f.keys().forEach { k -> f.optJSONArray(k)?.let { a -> d.order[k] = (0 until a.length()).map { a.optString(it) }.toMutableList() } }
        }
        o.optJSONArray("hidden")?.let { a -> (0 until a.length()).forEach { d.hidden += a.optString(it) } }
        return d
    }

    fun json(d: Data): String {
        val o = JSONObject()
        o.put("folder", JSONObject(d.folder as Map<*, *>))
        o.put("name", JSONObject(d.name as Map<*, *>))
        val ord = JSONObject()
        d.order.forEach { (k, v) -> ord.put(k, JSONArray(v)) }
        o.put("order", ord)
        o.put("hidden", JSONArray(d.hidden.toList()))
        return o.toString()
    }

    fun rowItem(key: String) = "r:$key"
    fun folderItem(name: String) = "f:$name"

    /** A folder in the Index: its sub-folders and rows, already in your order. */
    class Node(val name: String, val path: String, val folders: List<Node>, val rows: List<Folders.Entry>) {
        val count: Int get() = rows.size + folders.sumOf { it.count }
        /** Items as stored in the order list, in the order shown. */
        val items: List<String> get() = folders.map { folderItem(it.name) } + rows.map { rowItem(Folders.rowKey(it.addon, it.catalog)) }
    }

    fun parts(path: String?) = path.orEmpty().split(" > ").map { it.trim() }.filter { it.isNotEmpty() }
    fun join(parts: List<String>) = parts.joinToString(" > ")

    /** Applies your folders and names to the automatic entries. */
    fun apply(entries: List<Folders.Entry>, d: Data): List<Folders.Entry> {
        if (d.isEmpty) return entries
        return entries.map { e ->
            val key = Folders.rowKey(e.addon, e.catalog)
            val f = d.folder[key]
            val n = d.name[key]
            if (f == null && n == null) e
            else Folders.Entry(e.addon, e.catalog, if (f == null) e.folder else f.ifEmpty { null }, n ?: e.name)
        }
    }

    /** Sorts [items] by the stored order for [path]; anything not listed keeps its place after them. */
    private fun <T> sorted(d: Data, path: String, items: List<T>, id: (T) -> String): List<T> {
        val order = d.order[path] ?: return items
        val pos = order.withIndex().associate { it.value to it.index }
        return items.withIndex().sortedWith(compareBy({ pos[id(it.value)] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    }

    /** The front page rows, in order. */
    fun front(entries: List<Folders.Entry>, d: Data): List<Folders.Entry> =
        sorted(d, FRONT, entries.filter { it.folder == null }) { rowItem(Folders.rowKey(it.addon, it.catalog)) }

    /** The top of the Index: every folder with its sub-folders and rows, in order. */
    fun tree(entries: List<Folders.Entry>, d: Data, withHidden: Boolean = false): Node =
        node("", "", entries.filter { it.folder != null }, 0, d, withHidden)

    fun isHidden(d: Data, path: String) = d.hidden.any { path == it || path.startsWith("$it > ") }

    private fun node(name: String, path: String, list: List<Folders.Entry>, depth: Int, d: Data, withHidden: Boolean): Node {
        val groups = LinkedHashMap<String, MutableList<Folders.Entry>>()
        val direct = mutableListOf<Folders.Entry>()
        list.forEach { e ->
            val p = parts(e.folder)
            if (p.size > depth) groups.getOrPut(p[depth]) { mutableListOf() } += e else direct += e
        }
        // "More" sits last unless you've placed it.
        groups.remove("More")?.let { groups["More"] = it }
        val subs = groups.map { (n, l) -> node(n, join(parts(path) + n), l, depth + 1, d, withHidden) }
            .filter { withHidden || it.path !in d.hidden }
        val items: List<Any> = subs + direct
        val ordered = sorted(d, path, items) { if (it is Node) folderItem(it.name) else rowItem(Folders.rowKey((it as Folders.Entry).addon, it.catalog)) }
        return Node(name, path, ordered.filterIsInstance<Node>(), ordered.filterIsInstance<Folders.Entry>())
    }

    /** Finds the folder at [path] ("" = the top). */
    fun find(root: Node, path: String): Node? {
        var n = root
        for (p in parts(path)) n = n.folders.firstOrNull { it.name == p } ?: return null
        return n
    }

    /** Every folder path, for "Move to…" lists. */
    fun allPaths(root: Node): List<String> = root.folders.flatMap { listOf(it.path) + allPaths(it) }

    // ---- changes ----

    /**
     * Puts a row in [path] ("" = the front page), at the end of that folder. [entries] is the Index
     * before the move: the folders' current order is kept, so moving a row never reshuffles them.
     */
    fun moveRow(d: Data, entries: List<Folders.Entry>, key: String, path: String) {
        val before = tree(entries, d, withHidden = true)
        fun freeze(n: Node) {
            if (d.order[n.path] == null) d.order[n.path] = n.folders.map { folderItem(it.name) }.toMutableList()
            n.folders.forEach { freeze(it) }
        }
        freeze(before)
        val dest = if (path.isEmpty()) FRONT else path
        if (path.isEmpty() && d.order[FRONT] == null) d.order[FRONT] = front(entries, d).map { rowItem(Folders.rowKey(it.addon, it.catalog)) }.toMutableList()
        find(before, path)?.takeIf { path.isNotEmpty() }?.let { n -> if (d.order[path].isNullOrEmpty() || d.order[path]!!.none { it.startsWith("r:") }) d.order[path] = n.items.toMutableList() }
        d.folder[key] = path
        d.order.values.forEach { it.remove(rowItem(key)) }
        val list = d.order.getOrPut(dest) { mutableListOf() }
        list.add(rowItem(key))
        // A brand-new folder goes at the end of its parent.
        val parts = parts(path)
        for (i in parts.indices) {
            val parent = join(parts.take(i))
            val l = d.order.getOrPut(parent) { mutableListOf() }
            if (folderItem(parts[i]) !in l) l.add(folderItem(parts[i]))
        }
    }

    fun rename(d: Data, key: String, name: String?) {
        if (name.isNullOrBlank()) d.name.remove(key) else d.name[key] = name.trim()
    }

    /** Moves [item] within [path], whose items are shown as [current]: by -1/+1, or to the top/bottom. */
    fun shift(d: Data, path: String, current: List<String>, item: String, by: Int): Boolean {
        val list = current.toMutableList()
        val i = list.indexOf(item)
        if (i < 0) return false
        val to = when (by) {
            Int.MIN_VALUE -> 0
            Int.MAX_VALUE -> list.lastIndex
            else -> i + by
        }
        if (to !in list.indices || to == i) return false
        list.removeAt(i)
        list.add(to, item)
        d.order[path] = list
        return true
    }

    /** Renames the folder at [path] (moving every row inside it); returns the new path. */
    fun renameFolder(d: Data, entries: List<Folders.Entry>, path: String, newName: String): String {
        val old = parts(path)
        val new = old.dropLast(1) + newName.trim()
        val newPath = join(new)
        val parent = join(old.dropLast(1))
        apply(entries, d).forEach { e ->
            val p = parts(e.folder)
            if (p.size >= old.size && p.take(old.size) == old) d.folder[Folders.rowKey(e.addon, e.catalog)] = join(new + p.drop(old.size))
        }
        // Keep its place among its neighbours, and the order inside it and its sub-folders.
        d.order[parent]?.let { l -> val i = l.indexOf(folderItem(old.last())); if (i >= 0) l[i] = folderItem(newName.trim()) }
        d.hidden.filter { it == path || it.startsWith("$path > ") }.forEach { h -> d.hidden -= h; d.hidden += newPath + h.removePrefix(path) }
        d.order.keys.filter { it == path || it.startsWith("$path > ") }.forEach { k ->
            d.order.remove(k)?.let { d.order[newPath + k.removePrefix(path)] = it }
        }
        return newPath
    }

    /** Moves every row of the folder at [path] into [to] (an empty folder disappears). */
    fun emptyFolder(d: Data, entries: List<Folders.Entry>, path: String, to: String) {
        val old = parts(path)
        apply(entries, d).forEach { e ->
            val p = parts(e.folder)
            if (p.size >= old.size && p.take(old.size) == old) d.folder[Folders.rowKey(e.addon, e.catalog)] = to
        }
    }

    fun hideFolder(d: Data, path: String, hide: Boolean) { if (hide) d.hidden += path else d.hidden -= path }

    /** Back to the automatic Index. */
    fun reset(d: Data) { d.folder.clear(); d.name.clear(); d.order.clear(); d.hidden.clear() }
}
