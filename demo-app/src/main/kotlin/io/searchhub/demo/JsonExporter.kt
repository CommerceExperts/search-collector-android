package io.searchhub.demo

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.searchhub.collector.model.SearchCollectorEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

private val json = Json { prettyPrint = true }

suspend fun exportEvents(context: Context, events: List<SearchCollectorEvent>): String =
    withContext(Dispatchers.IO) {
        val fileName = "searchhub-events-${System.currentTimeMillis()}.json"
        val jsonContent = json.encodeToString(events)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert returned null")
            try {
                (resolver.openOutputStream(uri) ?: error("openOutputStream returned null for $uri"))
                    .use { it.write(jsonContent.toByteArray()) }
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            "Downloads/$fileName"
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { it.write(jsonContent.toByteArray()) }
            file.absolutePath
        }
    }
