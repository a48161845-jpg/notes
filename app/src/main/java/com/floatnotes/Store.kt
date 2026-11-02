package com.floatnotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Note(val id: Long, val text: String, val image: String?, val ts: Long)

/** Хранит заметки в notes.json, картинки — в filesDir/images. Всё остаётся на устройстве. */
class Store(private val ctx: Context) {

    private val file = File(ctx.filesDir, "notes.json")
    private val imgDir = File(ctx.filesDir, "images").apply { mkdirs() }

    fun all(): MutableList<Note> {
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            val out = mutableListOf<Note>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Note(
                        o.getLong("id"),
                        o.optString("text", ""),
                        if (o.isNull("image")) null else o.optString("image"),
                        o.optLong("ts", 0L)
                    )
                )
            }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun write(list: List<Note>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("text", it.text)
                put("image", it.image ?: JSONObject.NULL)
                put("ts", it.ts)
            })
        }
        file.writeText(arr.toString())
    }

    fun add(text: String, image: String?): Note {
        val now = System.currentTimeMillis()
        val note = Note(now, text, image, now)
        val list = all()
        list.add(note)
        write(list)
        return note
    }

    fun delete(id: Long) {
        val list = all()
        val gone = list.firstOrNull { it.id == id }
        list.removeAll { it.id == id }
        write(list)
        gone?.image?.let { File(imgDir, it).delete() }
    }

    fun imageFile(name: String) = File(imgDir, name)

    /** Копирует картинку по Uri в своё хранилище, уменьшая до 1600 px по длинной стороне. */
    fun importImage(uri: Uri): String? {
        return try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            val longSide = maxOf(bmp.width, bmp.height)
            if (longSide > 1600) {
                val k = 1600f / longSide
                bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
            }
            val png = bmp.hasAlpha()
            val name = UUID.randomUUID().toString() + if (png) ".png" else ".jpg"
            File(imgDir, name).outputStream().use {
                bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, it)
            }
            name
        } catch (e: Exception) {
            null
        }
    }

    fun thumb(name: String, maxPx: Int): Bitmap? {
        val f = File(imgDir, name)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
