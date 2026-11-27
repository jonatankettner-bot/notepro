package com.gever.notepro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/** The brush families NotePro uses. Kept as single instances so a stroke's family can be identified. */
// Brush families and their stored codes live in Brushes.kt (Families).

data class Folder(val id: String, var name: String, var color: Int, var parentId: String?, var emoji: String = "")

data class NoteMeta(
    val id: String,
    var title: String,
    var folderId: String?,
    val created: Long,
    var modified: Long,
    var opened: Long,
    var favorite: Boolean,
    var paper: String,
    var paperColor: Int,
    var pages: Int = 1,
    var bookmarks: List<Int> = emptyList(),
)

/**
 * Local storage, all under the app's private files dir:
 *   library.json            folders
 *   notes/<id>/meta.json    title, folder, dates, paper
 *   notes/<id>/ink.bin      strokes (our own small binary format, see writeStrokes)
 *   notes/<id>/thumb.png    first-page thumbnail for the library
 */
class NoteStore(ctx: Context) {
    private val root = ctx.filesDir
    private val notesDir = File(root, "notes").apply { mkdirs() }
    private val libFile = File(root, "library.json")

    // ---------------- folders ----------------
    fun folders(): MutableList<Folder> {
        if (!libFile.exists()) return mutableListOf()
        return try {
            val arr = JSONObject(libFile.readText()).optJSONArray("folders") ?: JSONArray()
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Folder(o.getString("id"), o.getString("name"), o.getInt("color"),
                    o.optString("parent").ifEmpty { null }, o.optString("emoji"))
            }
        } catch (_: Exception) { mutableListOf() }
    }

    fun saveFolders(list: List<Folder>) {
        val arr = JSONArray()
        for (f in list) arr.put(JSONObject().put("id", f.id).put("name", f.name).put("color", f.color)
            .put("parent", f.parentId ?: "").put("emoji", f.emoji))
        atomicWrite(libFile, JSONObject().put("folders", arr).toString().toByteArray())
    }

    // ---------------- notes ----------------
    fun notes(): List<NoteMeta> = notesDir.listFiles()?.mapNotNull { dir -> readMeta(dir) } ?: emptyList()

    fun note(id: String): NoteMeta? = readMeta(File(notesDir, id))

    private fun readMeta(dir: File): NoteMeta? = try {
        val o = JSONObject(File(dir, "meta.json").readText())
        NoteMeta(o.getString("id"), o.getString("title"), o.optString("folder").ifEmpty { null },
            o.getLong("created"), o.getLong("modified"), o.optLong("opened", o.getLong("modified")),
            o.optBoolean("favorite"), o.optString("paper", "LINED"), o.optInt("paperColor", -1),
            o.optInt("pages", 1),
            o.optJSONArray("bookmarks")?.let { a -> List(a.length()) { a.getInt(it) } } ?: emptyList())
    } catch (_: Exception) { null }

    fun saveMeta(m: NoteMeta) {
        val dir = File(notesDir, m.id).apply { mkdirs() }
        val o = JSONObject().put("id", m.id).put("title", m.title).put("folder", m.folderId ?: "")
            .put("created", m.created).put("modified", m.modified).put("opened", m.opened)
            .put("favorite", m.favorite).put("paper", m.paper).put("paperColor", m.paperColor)
            .put("pages", m.pages).put("bookmarks", JSONArray(m.bookmarks))
        atomicWrite(File(dir, "meta.json"), o.toString().toByteArray())
    }

    fun create(title: String, folderId: String?, paper: String = "LINED", paperColor: Int = -1): NoteMeta {
        val now = System.currentTimeMillis()
        val m = NoteMeta(UUID.randomUUID().toString(), title, folderId, now, now, now, false, paper, paperColor)
        saveMeta(m); return m
    }

    fun delete(id: String) { File(notesDir, id).deleteRecursively() }

    fun duplicate(src: NoteMeta, newTitle: String): NoteMeta {
        val now = System.currentTimeMillis()
        val m = src.copy(id = UUID.randomUUID().toString(), title = newTitle, created = now, modified = now, opened = now, favorite = false)
        File(notesDir, src.id).copyRecursively(File(notesDir, m.id), overwrite = true)
        saveMeta(m); return m
    }

    fun thumbnail(id: String): Bitmap? {
        val f = File(File(notesDir, id), "thumb.png")
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    fun saveThumbnail(id: String, bmp: Bitmap) {
        val f = File(File(notesDir, id).apply { mkdirs() }, "thumb.png")
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(f)
    }

    // ---------------- strokes ----------------
    fun loadStrokes(id: String): List<Stroke> {
        val f = File(File(notesDir, id), "ink.bin")
        if (!f.exists()) return emptyList()
        return try { DataInputStream(f.inputStream().buffered()).use { readStrokes(it) } } catch (_: Exception) { emptyList() }
    }

    fun saveStrokes(id: String, strokes: List<Stroke>) {
        val f = File(File(notesDir, id).apply { mkdirs() }, "ink.bin")
        val tmp = File(f.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { writeStrokes(it, strokes) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun writeStrokes(out: DataOutputStream, strokes: List<Stroke>) {
        out.writeInt(MAGIC); out.writeInt(1); out.writeInt(strokes.size)
        val p = StrokeInput()
        for (st in strokes) {
            val b = st.brush
            out.writeByte(Families.code(b)); out.writeInt(b.colorIntArgb); out.writeFloat(b.size); out.writeFloat(b.epsilon)
            val inputs = st.inputs
            out.writeInt(inputs.size)
            for (i in 0 until inputs.size) {
                inputs.populate(i, p)
                out.writeByte(toolCode(p.toolType)); out.writeFloat(p.x); out.writeFloat(p.y); out.writeLong(p.elapsedTimeMillis)
                out.writeFloat(p.strokeUnitLengthCm); out.writeFloat(p.pressure); out.writeFloat(p.tiltRadians); out.writeFloat(p.orientationRadians)
            }
        }
    }

    private fun readStrokes(inp: DataInputStream): List<Stroke> {
        if (inp.readInt() != MAGIC) return emptyList()
        inp.readInt() // version
        val count = inp.readInt()
        val list = ArrayList<Stroke>(count)
        val p = StrokeInput()
        repeat(count) {
            val fam = Families.of(inp.readByte().toInt())
            val color = inp.readInt(); val size = inp.readFloat(); val eps = inp.readFloat()
            val n = inp.readInt()
            val batch = MutableStrokeInputBatch()
            repeat(n) {
                val tool = toolOf(inp.readByte().toInt())
                val x = inp.readFloat(); val y = inp.readFloat(); val t = inp.readLong()
                val unit = inp.readFloat(); val pr = inp.readFloat(); val tilt = inp.readFloat(); val ori = inp.readFloat()
                p.update(x, y, t, tool, unit, pr, tilt, ori)
                batch.add(p)
            }
            if (n > 0) list.add(Stroke(Brush.createWithColorIntArgb(fam, color, size, eps), batch.toImmutable()))
        }
        return list
    }

    private fun toolCode(t: InputToolType) = when (t) {
        InputToolType.STYLUS -> 1; InputToolType.TOUCH -> 2; InputToolType.MOUSE -> 3; else -> 0
    }
    private fun toolOf(c: Int) = when (c) {
        1 -> InputToolType.STYLUS; 2 -> InputToolType.TOUCH; 3 -> InputToolType.MOUSE; else -> InputToolType.UNKNOWN
    }

    private fun atomicWrite(f: File, bytes: ByteArray) {
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    companion object { private const val MAGIC = 0x4E50494E } // "NPIN"
}
