package com.example.flet_media_scanner

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import java.util.Locale

class FletMediaScannerPlugin : FlutterPlugin, MethodCallHandler {
    private lateinit var channel: MethodChannel
    private lateinit var context: Context

    companion object {
        private const val TAG = "FletMediaScanner"
        private const val CHANNEL = "flet_media_scanner/scan"

        /** Explicit MIME map — URLConnection misses MKV, Opus, FLAC, AVIF, HEIC on many OSes. */
        private val MIME_BY_EXTENSION = mapOf(
            // ── Video ──────────────────────────────────────────────────────────
            "mp4"  to "video/mp4",
            "mkv"  to "video/x-matroska",
            "webm" to "video/webm",
            "avi"  to "video/x-msvideo",
            "mov"  to "video/quicktime",
            "3gp"  to "video/3gpp",
            "ts"   to "video/mp2t",
            "flv"  to "video/x-flv",
            // ── Audio ──────────────────────────────────────────────────────────
            "mp3"  to "audio/mpeg",
            "m4a"  to "audio/mp4",
            "aac"  to "audio/aac",
            "flac" to "audio/flac",
            "opus" to "audio/opus",
            "wav"  to "audio/wav",
            "ogg"  to "audio/ogg",
            "wma"  to "audio/x-ms-wma",
            "aiff" to "audio/aiff",
            // ── Image ──────────────────────────────────────────────────────────
            "jpg"  to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png"  to "image/png",
            "gif"  to "image/gif",
            "webp" to "image/webp",
            "bmp"  to "image/bmp",
            "heic" to "image/heic",
            "heif" to "image/heif",
            "avif" to "image/avif",
            "svg"  to "image/svg+xml",
            "tiff" to "image/tiff",
            "tif"  to "image/tiff",
        )

        fun getMimeType(filename: String, fallback: String = "application/octet-stream"): String {
            val ext = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return MIME_BY_EXTENSION[ext]
                ?: URLConnection.guessContentTypeFromName(filename)
                ?: fallback
        }

        fun isAudio(mimeType: String) = mimeType.startsWith("audio/")
        fun isImage(mimeType: String) = mimeType.startsWith("image/")
        fun isVideo(mimeType: String) = mimeType.startsWith("video/")
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, CHANNEL)
        channel.setMethodCallHandler(this)
        Log.d(TAG, "onAttachedToEngine: channel registered")
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        Log.d(TAG, "onDetachedFromEngine")
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "saveVideo"   -> saveVideo(call, result)
            "deleteVideo" -> deleteMedia(call, result)   // reused for all media types
            "listVideos"  -> listVideos(call, result)
            "saveAudio"   -> saveAudio(call, result)
            "listAudio"   -> listAudio(call, result)
            "saveImage"   -> saveImage(call, result)
            "listImages"  -> listImages(call, result)
            "deleteMedia" -> deleteMedia(call, result)
            else          -> result.notImplemented()
        }
    }

    // ─────────────────────────────── Shared helper ────────────────────────────

    /**
     * Generic copy-into-MediaStore routine shared by video / audio / image.
     *
     * @param collection  the MediaStore URI to insert into
     * @param displayName file name shown in the gallery / files app
     * @param mimeType    MIME type of the file
     * @param relativePath  e.g. "Movies/MyApp" or "Music/MyApp" or "Pictures/MyApp"
     * @param isPendingColumn  e.g. MediaStore.Video.Media.IS_PENDING (API 29+)
     * @param source      the source file to copy
     * @param result      the Flutter result callback
     * @param tag         log tag prefix for this call
     */
    private fun saveToMediaStore(
        collection: Uri,
        displayName: String,
        mimeType: String,
        relativePath: String,
        isPendingColumn: String,
        source: File,
        path: String,
        result: Result,
        tag: String,
    ) {
        val resolver = context.contentResolver
        var uri: Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.MediaColumns.DATE_MODIFIED, source.lastModified() / 1000)
                put(MediaStore.MediaColumns.SIZE, source.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(isPendingColumn, 1)
                }
            }

            uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore insert returned null")

            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Unable to open MediaStore output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(isPendingColumn, 0)
                        put(MediaStore.MediaColumns.SIZE, source.length())
                    },
                    null, null
                )
            }

            result.success(
                mapOf(
                    "success"       to true,
                    "content_uri"   to uri.toString(),
                    "display_name"  to displayName,
                    "mime_type"     to mimeType,
                    "relative_path" to relativePath,
                    "source_path"   to path,
                    "size"          to source.length(),
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "$tag: exception: ${e.message}", e)
            uri?.let {
                try { resolver.delete(it, null, null) }
                catch (de: Exception) { Log.w(TAG, "$tag: failed to clean up: $de") }
            }
            result.error("SAVE_ERROR", e.message, e.toString())
        }
    }

    /** Generic MediaStore list query shared by video / audio / image. */
    private fun listFromMediaStore(
        collection: Uri,
        relativePathPrefix: String,
        defaultMime: String,
        listKey: String,
        result: Result,
        tag: String,
    ) {
        val resolver = context.contentResolver
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.MediaColumns.RELATIVE_PATH)
        }

        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" else null
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            arrayOf("$relativePathPrefix%") else null

        val items = mutableListOf<Map<String, Any?>>()
        try {
            resolver.query(
                collection,
                projection.toTypedArray(),
                selection,
                selectionArgs,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIdx       = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIdx     = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIdx     = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeIdx     = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val addedIdx    = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val modifiedIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val pathIdx     = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1

                while (cursor.moveToNext()) {
                    val id          = cursor.getLong(idIdx)
                    val uri         = ContentUris.withAppendedId(collection, id)
                    val displayName = cursor.getString(nameIdx) ?: continue
                    val relativePath = if (pathIdx >= 0)
                        cursor.getString(pathIdx) ?: "" else relativePathPrefix

                    items.add(
                        mapOf(
                            "content_uri"   to uri.toString(),
                            "display_name"  to displayName,
                            "mime_type"     to (cursor.getString(mimeIdx) ?: getMimeType(displayName, defaultMime)),
                            "relative_path" to relativePath.trimEnd('/'),
                            "size"          to cursor.getLong(sizeIdx),
                            "date_added"    to cursor.getLong(addedIdx),
                            "date_modified" to cursor.getLong(modifiedIdx),
                        )
                    )
                }
            }
            result.success(mapOf("success" to true, listKey to items))
        } catch (e: Exception) {
            Log.e(TAG, "$tag: exception: ${e.message}", e)
            result.error("LIST_ERROR", e.message, e.toString())
        }
    }

    // ─────────────────────────────────── Video ────────────────────────────────

    private fun saveVideo(call: MethodCall, result: Result) {
        val path = call.argument<String>("path")
        val requestedFileName = call.argument<String>("fileName")
        val album = call.argument<String>("album")?.trim()?.trim('/')?.takeIf { it.isNotBlank() } ?: "MyApp"

        if (path.isNullOrBlank()) { result.error("INVALID_ARGUMENT", "path must not be null or empty", null); return }
        val source = File(path)
        if (!source.isFile) { result.error("FILE_NOT_FOUND", "source file does not exist: $path", null); return }

        val displayName = requestedFileName?.trim()?.takeIf { it.isNotBlank() } ?: source.name
        val mimeType = getMimeType(displayName, "video/mp4")
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        saveToMediaStore(
            collection, displayName, mimeType,
            "${Environment.DIRECTORY_MOVIES}/$album",
            MediaStore.Video.Media.IS_PENDING,
            source, path, result, "saveVideo"
        )
    }

    private fun listVideos(call: MethodCall, result: Result) {
        val album = call.argument<String>("album")?.trim()?.trim('/')?.takeIf { it.isNotBlank() } ?: "MyApp"
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        listFromMediaStore(
            collection,
            "${Environment.DIRECTORY_MOVIES}/$album",
            "video/mp4", "videos", result, "listVideos"
        )
    }

    // ─────────────────────────────────── Audio ────────────────────────────────

    private fun saveAudio(call: MethodCall, result: Result) {
        val path = call.argument<String>("path")
        val requestedFileName = call.argument<String>("fileName")
        val album = call.argument<String>("album")?.trim()?.trim('/')?.takeIf { it.isNotBlank() } ?: "MyApp"

        if (path.isNullOrBlank()) { result.error("INVALID_ARGUMENT", "path must not be null or empty", null); return }
        val source = File(path)
        if (!source.isFile) { result.error("FILE_NOT_FOUND", "source file does not exist: $path", null); return }

        val displayName = requestedFileName?.trim()?.takeIf { it.isNotBlank() } ?: source.name
        val mimeType = getMimeType(displayName, "audio/mpeg")
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        saveToMediaStore(
            collection, displayName, mimeType,
            "${Environment.DIRECTORY_MUSIC}/$album",
            MediaStore.Audio.Media.IS_PENDING,
            source, path, result, "saveAudio"
        )
    }

    private fun listAudio(call: MethodCall, result: Result) {
        val album = call.argument<String>("album")?.trim()?.trim('/')?.takeIf { it.isNotBlank() } ?: "MyApp"
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        listFromMediaStore(
            collection,
            "${Environment.DIRECTORY_MUSIC}/$album",
            "audio/mpeg", "audio", result, "listAudio"
        )
    }

    // ─────────────────────────────────── Image ────────────────────────────────

    private fun saveImage(call: MethodCall, result: Result) {
        val path = call.argument<String>("path")
        val requestedFileName = call.argument<String>("fileName")
        val album = call.argument<String>("album")?.trim()?.trim('/')?.takeIf { it.isNotBlank() } ?: "MyApp"

        if (path.isNullOrBlank()) { result.error("INVALID_ARGUMENT", "path must not be null or empty", null); return }
        val source = File(path)
        if (!source.isFile) { result.error("FILE_NOT_FOUND", "source file does not exist: $path", null); return }

        val displayName = requestedFileName?.trim()?.takeIf { it.isNotBlank() } ?: source.name
        val mimeType = getMimeType(displayName, "image/jpeg")
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        saveToMediaStore(
            collection, displayName, mimeType,
            "${Environment.DIRECTORY_PICTURES}/$album",
            MediaStore.Images.Media.IS_PENDING,
            source, path, result, "saveImage"
        )
    }

    private fun listImages(call: MethodCall, result: Result) {
        val album = call.argument<String>("album")?.trim()?.trim('/')?.takeIf { it.isNotBlank() } ?: "MyApp"
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        listFromMediaStore(
            collection,
            "${Environment.DIRECTORY_PICTURES}/$album",
            "image/jpeg", "images", result, "listImages"
        )
    }

    // ─────────────────────────────────── Delete (universal) ───────────────────

    /** Works for any content:// URI — video, audio, or image. */
    private fun deleteMedia(call: MethodCall, result: Result) {
        val contentUri = call.argument<String>("contentUri")
            ?: call.argument<String>("content_uri")
        if (contentUri.isNullOrBlank()) {
            result.error("INVALID_ARGUMENT", "contentUri must not be null or empty", null)
            return
        }
        try {
            val uri = Uri.parse(contentUri)
            val deletedRows = context.contentResolver.delete(uri, null, null)
            result.success(
                mapOf(
                    "success"      to (deletedRows > 0),
                    "content_uri"  to contentUri,
                    "deleted_rows" to deletedRows,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "deleteMedia: exception: ${e.message}", e)
            result.error("DELETE_ERROR", e.message, e.toString())
        }
    }
}
