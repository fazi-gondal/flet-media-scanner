package com.example.flet_media_scanner

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import java.util.Locale

class FletMediaScannerPlugin :
    FlutterPlugin,
    MethodCallHandler,
    ActivityAware,
    PluginRegistry.RequestPermissionsResultListener,
    EventChannel.StreamHandler {

    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private var activity: Activity? = null
    private var pendingPermResult: Result? = null

    // Change observer
    private lateinit var eventChannel: EventChannel
    private var eventSink: EventChannel.EventSink? = null
    private var imageObserver: android.database.ContentObserver? = null
    private var videoObserver: android.database.ContentObserver? = null
    private var audioObserver: android.database.ContentObserver? = null

    companion object {
        private const val TAG = "FletMediaScanner"
        private const val CHANNEL = "flet_media_scanner/scan"
        private const val EVENT_CHANNEL = "flet_media_scanner/changes"
        private const val PERM_REQUEST_CODE = 9427

        private val MIME_BY_EXTENSION = mapOf(
            "mp4"  to "video/mp4",
            "mkv"  to "video/x-matroska",
            "webm" to "video/webm",
            "avi"  to "video/x-msvideo",
            "mov"  to "video/quicktime",
            "3gp"  to "video/3gpp",
            "ts"   to "video/mp2t",
            "flv"  to "video/x-flv",
            "mp3"  to "audio/mpeg",
            "m4a"  to "audio/mp4",
            "aac"  to "audio/aac",
            "flac" to "audio/flac",
            "opus" to "audio/opus",
            "wav"  to "audio/wav",
            "ogg"  to "audio/ogg",
            "wma"  to "audio/x-ms-wma",
            "aiff" to "audio/aiff",
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

    // ─────────────────────────── FlutterPlugin ────────────────────────────────

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, CHANNEL)
        channel.setMethodCallHandler(this)
        eventChannel = EventChannel(binding.binaryMessenger, EVENT_CHANNEL)
        eventChannel.setStreamHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        unregisterObservers()
    }

    // ─────────────────────────── ActivityAware ────────────────────────────────

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() { activity = null }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivity() { activity = null }

    // ─────────────── RequestPermissionsResultListener ─────────────────────────

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode != PERM_REQUEST_CODE) return false
        val pending = pendingPermResult ?: return false
        pendingPermResult = null
        pending.success(mapOf("success" to true, "permissions" to buildPermissionMap()))
        return true
    }

    // ──────────────────── EventChannel.StreamHandler (Change observer) ────────────────────

    /**
     * Called by Flutter when a Dart listener subscribes to the EventChannel.
     * Registers ContentObservers on all three MediaStore collections.
     */
    override fun onListen(arguments: Any?, sink: EventChannel.EventSink?) {
        eventSink = sink
        registerObservers()
        Log.d(TAG, "EventChannel: listening for MediaStore changes")
    }

    /**
     * Called by Flutter when the Dart listener cancels the subscription.
     * Unregisters all ContentObservers to avoid leaks.
     */
    override fun onCancel(arguments: Any?) {
        unregisterObservers()
        eventSink = null
        Log.d(TAG, "EventChannel: cancelled")
    }

    private fun registerObservers() {
        val resolver = context.contentResolver
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        imageObserver = makeObserver(handler, "image")
        videoObserver = makeObserver(handler, "video")
        audioObserver = makeObserver(handler, "audio")

        resolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, imageObserver!!)
        resolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, videoObserver!!)
        resolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, audioObserver!!)
    }

    private fun unregisterObservers() {
        val resolver = context.contentResolver
        imageObserver?.let { resolver.unregisterContentObserver(it) ; imageObserver = null }
        videoObserver?.let { resolver.unregisterContentObserver(it) ; videoObserver = null }
        audioObserver?.let { resolver.unregisterContentObserver(it) ; audioObserver = null }
    }

    private fun makeObserver(
        handler: android.os.Handler,
        collection: String,
    ): android.database.ContentObserver {
        return object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = sendChange(collection, null)
            override fun onChange(selfChange: Boolean, uri: Uri?) = sendChange(collection, uri)
        }
    }

    /**
     * Post a change event to the Dart EventChannel sink.
     * Always dispatched on the main looper so the sink call is thread-safe.
     */
    private fun sendChange(collection: String, uri: Uri?) {
        val payload = mapOf(
            "collection" to collection,
            "uri"        to (uri?.toString() ?: ""),
        )
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            eventSink?.success(payload)
        }
    }

    // ─────────────────────────── MethodCallHandler ────────────────────────────

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "checkPermissions"   -> checkPermissions(result)
            "requestPermissions" -> requestPermissions(result)
            "getAssets"          -> getAssets(call, result)
            "getAlbums"          -> getAlbums(call, result)
            "deleteAlbum"        -> deleteAlbum(call, result)
            "getThumbnail"       -> getThumbnail(call, result)
            "saveVideo"          -> saveVideo(call, result)
            "deleteVideo"        -> deleteMedia(call, result)
            "listVideos"         -> listVideos(call, result)
            "saveAudio"          -> saveAudio(call, result)
            "listAudio"          -> listAudio(call, result)
            "saveImage"          -> saveImage(call, result)
            "listImages"         -> listImages(call, result)
            "deleteMedia"        -> deleteMedia(call, result)
            "deleteAssets"       -> deleteAssets(call, result)
            else                 -> result.notImplemented()
        }
    }

    // ─────────────────────────── Permissions ──────────────────────────────────

    private fun checkPermissions(result: Result) {
        result.success(mapOf("success" to true, "permissions" to buildPermissionMap()))
    }

    private fun requestPermissions(result: Result) {
        val act = activity
        if (act == null) {
            result.error("NO_ACTIVITY", "No Activity attached — cannot request permissions", null)
            return
        }
        val perms = requiredPermissions()
        val allGranted = perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            result.success(mapOf("success" to true, "permissions" to buildPermissionMap()))
            return
        }
        pendingPermResult = result
        ActivityCompat.requestPermissions(act, perms.toTypedArray(), PERM_REQUEST_CODE)
    }

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun buildPermissionMap(): Map<String, String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) mapOf(
            "images" to getPermStatus(Manifest.permission.READ_MEDIA_IMAGES),
            "video"  to getPermStatus(Manifest.permission.READ_MEDIA_VIDEO),
            "audio"  to getPermStatus(Manifest.permission.READ_MEDIA_AUDIO),
        )
        else {
            val s = getPermStatus(Manifest.permission.READ_EXTERNAL_STORAGE)
            mapOf("images" to s, "video" to s, "audio" to s)
        }

    private fun getPermStatus(permission: String): String =
        if (ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED) "granted" else "denied"

    // ─────────────────────────── Thumbnail ─────────────────────────────────

    /**
     * Generate a JPEG thumbnail for an image asset and return it as base64.
     *
     * Arguments:
     *   contentUri  String  — the content:// URI of the image
     *   width       Int     — desired width  (default 200, ignored on API < 29)
     *   height      Int     — desired height (default 200, ignored on API < 29)
     *
     * Returns { success, base64, mime_type, width, height }.
     * Video thumbnails are not supported yet (planned for a future release).
     */
    @Suppress("DEPRECATION")
    private fun getThumbnail(call: MethodCall, result: Result) {
        val uriStr = call.argument<String>("contentUri")
            ?: call.argument<String>("content_uri")
        val width  = call.argument<Int>("width")  ?: 200
        val height = call.argument<Int>("height") ?: 200

        if (uriStr.isNullOrBlank()) {
            result.error("INVALID_ARGUMENT", "contentUri is required", null)
            return
        }

        try {
            val uri = Uri.parse(uriStr)
            val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+: modern loadThumbnail — respects requested size
                context.contentResolver.loadThumbnail(
                    uri, Size(width, height), null
                )
            } else {
                // API < 29: legacy MINI thumbnail (~512×384) — size params ignored
                val id = ContentUris.parseId(uri)
                MediaStore.Images.Thumbnails.getThumbnail(
                    context.contentResolver,
                    id,
                    MediaStore.Images.Thumbnails.MINI_KIND,
                    null,
                )
            }

            if (bitmap == null) {
                result.error("THUMBNAIL_ERROR", "Thumbnail not available for: $uriStr", null)
                return
            }

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

            result.success(mapOf(
                "success"   to true,
                "base64"    to base64,
                "mime_type" to "image/jpeg",
                "width"     to bitmap.width,
                "height"    to bitmap.height,
            ))
        } catch (e: Exception) {
            Log.e(TAG, "getThumbnail: ${e.message}", e)
            result.error("THUMBNAIL_ERROR", e.message, e.toString())
        }
    }

    // ─────────────────────────── Album management ─────────────────────────────

    /**
     * Group all MediaStore items by RELATIVE_PATH and return one AlbumInfo per folder.
     *
     * Arguments:
     *   mediaType  "all" | "video" | "audio" | "image"  (default "all")
     */
    private fun getAlbums(call: MethodCall, result: Result) {
        val mediaType = (call.argument<String>("mediaType") ?: "all").lowercase(Locale.ROOT)

        data class Spec(val uri: Uri, val type: String)
        val specs = when (mediaType) {
            "video" -> listOf(Spec(videoCollection(), "video"))
            "audio" -> listOf(Spec(audioCollection(), "audio"))
            "image" -> listOf(Spec(imageCollection(), "image"))
            else    -> listOf(
                Spec(videoCollection(), "video"),
                Spec(audioCollection(), "audio"),
                Spec(imageCollection(), "image"),
            )
        }

        // key = relative_path (trimmed), value = mutable album row
        val albumMap = linkedMapOf<String, MutableMap<String, Any?>>()

        try {
            for (spec in specs) {
                collectAlbums(spec.uri, spec.type, albumMap)
            }

            val albums = albumMap.values
                .sortedByDescending { (it["count"] as? Int) ?: 0 }

            result.success(mapOf("success" to true, "albums" to albums))
        } catch (e: Exception) {
            Log.e(TAG, "getAlbums: ${e.message}", e)
            result.error("QUERY_ERROR", e.message, e.toString())
        }
    }

    /**
     * Scan one collection and populate [albumMap] with grouped album data.
     * The album [cover_uri] is the most-recently-added item in each folder.
     * Requires API 29+ for RELATIVE_PATH; no-ops on older devices.
     */
    private fun collectAlbums(
        collection: Uri,
        mediaType: String,
        albumMap: LinkedHashMap<String, MutableMap<String, Any?>>,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
        )

        context.contentResolver.query(
            collection,
            projection,
            null, null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idIdx   = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val pathIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val rawPath = cursor.getString(pathIdx) ?: continue
                val trimmed = rawPath.trimEnd('/')
                val albumName = trimmed.substringAfterLast('/')
                if (albumName.isBlank()) continue   // skip root-level files

                val existing = albumMap[trimmed]
                if (existing == null) {
                    val id  = cursor.getLong(idIdx)
                    val uri = ContentUris.withAppendedId(collection, id)
                    albumMap[trimmed] = mutableMapOf(
                        "name"          to albumName,
                        "relative_path" to trimmed,
                        "count"         to 1,
                        "cover_uri"     to uri.toString(),
                        "media_type"    to mediaType,
                    )
                } else {
                    existing["count"] = (existing["count"] as Int) + 1
                    // cover_uri stays as the first (most-recent) item seen
                }
            }
        }
    }

    /**
     * Delete every MediaStore item whose RELATIVE_PATH starts with [relativePath].
     *
     * Arguments:
     *   relativePath  e.g. "Movies/MyApp" (as returned by get_albums)
     *   mediaType     "all" | "video" | "audio" | "image"  (default "all")
     *
     * Returns { success, deleted_count, relative_path }.
     */
    private fun deleteAlbum(call: MethodCall, result: Result) {
        val relativePath = call.argument<String>("relativePath")
            ?.trim()?.trim('/')
            ?.takeIf { it.isNotBlank() }
        val mediaType = (call.argument<String>("mediaType") ?: "all").lowercase(Locale.ROOT)

        if (relativePath == null) {
            result.error("INVALID_ARGUMENT", "relativePath is required", null)
            return
        }

        data class Spec(val uri: Uri)
        val specs = when (mediaType) {
            "video" -> listOf(Spec(videoCollection()))
            "audio" -> listOf(Spec(audioCollection()))
            "image" -> listOf(Spec(imageCollection()))
            else    -> listOf(Spec(videoCollection()), Spec(audioCollection()), Spec(imageCollection()))
        }

        try {
            var total = 0
            val resolver = context.contentResolver

            for (spec in specs) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // API 29+: batch delete via WHERE clause
                    total += resolver.delete(
                        spec.uri,
                        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                        arrayOf("$relativePath/"),
                    )
                } else {
                    // API < 29: iterate and delete individually
                    val ids = mutableListOf<Long>()
                    resolver.query(
                        spec.uri,
                        arrayOf(MediaStore.MediaColumns._ID),
                        null, null, null,
                    )?.use { cursor ->
                        val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        while (cursor.moveToNext()) {
                            ids += cursor.getLong(idIdx)
                        }
                    }
                    for (id in ids) {
                        val uri = ContentUris.withAppendedId(spec.uri, id)
                        try { total += resolver.delete(uri, null, null) }
                        catch (e: Exception) { Log.w(TAG, "deleteAlbum: could not delete $uri: ${e.message}") }
                    }
                }
            }

            result.success(mapOf(
                "success"       to true,
                "deleted_count" to total,
                "relative_path" to relativePath,
            ))
        } catch (e: Exception) {
            Log.e(TAG, "deleteAlbum: ${e.message}", e)
            result.error("DELETE_ERROR", e.message, e.toString())
        }
    }

    // ─────────────────────────── get_assets (generic query) ───────────────────

    /**
     * Generic unified query across one or more MediaStore collections.
     *
     * Arguments (all optional except none required):
     *   mediaType  "all" | "video" | "audio" | "image"  (default "all")
     *   album      String | null — filter by relative path prefix
     *   mimeType   String | null — filter by exact MIME type
     *   limit      Int           (default 50, -1 = no limit)
     *   offset     Int           (default 0)
     *   sortBy     "date_added" | "date_modified" | "display_name" | "size" | "duration"
     *   sortOrder  "asc" | "desc"  (default "desc")
     */
    private fun getAssets(call: MethodCall, result: Result) {
        val mediaType  = (call.argument<String>("mediaType") ?: "all").lowercase(Locale.ROOT)
        val album      = call.argument<String>("album")?.trim()?.trim('/')?.takeIf { it.isNotBlank() }
        val mimeFilter = call.argument<String>("mimeType")?.trim()?.takeIf { it.isNotBlank() }
        val limit      = call.argument<Int>("limit") ?: 50
        val offset     = call.argument<Int>("offset") ?: 0
        val sortByRaw  = (call.argument<String>("sortBy") ?: "date_added").lowercase(Locale.ROOT)
        val sortOrder  = if ((call.argument<String>("sortOrder") ?: "desc")
                .lowercase(Locale.ROOT) == "asc") "ASC" else "DESC"

        val sortColumn = when (sortByRaw) {
            "date_modified" -> MediaStore.MediaColumns.DATE_MODIFIED
            "display_name"  -> MediaStore.MediaColumns.DISPLAY_NAME
            "size"          -> MediaStore.MediaColumns.SIZE
            "duration"      -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                                   MediaStore.MediaColumns.DURATION
                               else MediaStore.MediaColumns.DATE_ADDED
            else            -> MediaStore.MediaColumns.DATE_ADDED
        }
        val orderBy = "$sortColumn $sortOrder"

        // Determine which collections to query
        data class CollectionSpec(val uri: Uri, val type: String, val defaultMime: String)
        val specs = when (mediaType) {
            "video" -> listOf(CollectionSpec(videoCollection(), "video", "video/mp4"))
            "audio" -> listOf(CollectionSpec(audioCollection(), "audio", "audio/mpeg"))
            "image" -> listOf(CollectionSpec(imageCollection(), "image", "image/jpeg"))
            else    -> listOf(
                CollectionSpec(videoCollection(), "video", "video/mp4"),
                CollectionSpec(audioCollection(), "audio", "audio/mpeg"),
                CollectionSpec(imageCollection(), "image", "image/jpeg"),
            )
        }

        try {
            val allItems = mutableListOf<Map<String, Any?>>()

            for (spec in specs) {
                val dirPrefix = when (spec.type) {
                    "video" -> Environment.DIRECTORY_MOVIES
                    "audio" -> Environment.DIRECTORY_MUSIC
                    else    -> Environment.DIRECTORY_PICTURES
                }
                val relPath = if (album != null) "$dirPrefix/$album" else dirPrefix

                allItems += queryCollection(
                    spec.uri, spec.type, spec.defaultMime,
                    relPath, mimeFilter, orderBy
                )
            }

            // Sort merged results if querying multiple collections
            if (specs.size > 1) {
                allItems.sortWith { a, b ->
                    val aVal = a[sortByRaw] ?: a["date_added"]
                    val bVal = b[sortByRaw] ?: b["date_added"]
                    val cmp = when {
                        aVal is Long && bVal is Long -> aVal.compareTo(bVal)
                        aVal is String && bVal is String -> aVal.compareTo(bVal, ignoreCase = true)
                        else -> 0
                    }
                    if (sortOrder == "ASC") cmp else -cmp
                }
            }

            val total = allItems.size
            val paged = if (limit < 0) allItems.drop(offset)
                        else allItems.drop(offset).take(limit)

            result.success(mapOf(
                "success" to true,
                "assets"  to paged,
                "total"   to total,
                "limit"   to limit,
                "offset"  to offset,
            ))
        } catch (e: Exception) {
            Log.e(TAG, "getAssets: ${e.message}", e)
            result.error("QUERY_ERROR", e.message, e.toString())
        }
    }

    /**
     * Query a single MediaStore collection and return a list of asset maps
     * with extended metadata (width, height, duration).
     */
    private fun queryCollection(
        collection: Uri,
        mediaType: String,
        defaultMime: String,
        relativePathPrefix: String,
        mimeFilter: String?,
        orderBy: String,
    ): List<Map<String, Any?>> {
        val resolver = context.contentResolver

        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.DURATION)
            }
        }

        // Build WHERE clause
        val selections = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selections += "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            args += "$relativePathPrefix%"
        }
        if (mimeFilter != null) {
            selections += "${MediaStore.MediaColumns.MIME_TYPE} = ?"
            args += mimeFilter
        }

        val selection = if (selections.isEmpty()) null else selections.joinToString(" AND ")
        val selectionArgs = if (args.isEmpty()) null else args.toTypedArray()

        val items = mutableListOf<Map<String, Any?>>()
        resolver.query(collection, projection.toTypedArray(), selection, selectionArgs, orderBy)
            ?.use { cursor ->
                val idIdx       = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIdx     = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIdx     = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeIdx     = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val addedIdx    = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val modifiedIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val widthIdx    = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val heightIdx   = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                val pathIdx     = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
                val durationIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    cursor.getColumnIndex(MediaStore.MediaColumns.DURATION) else -1

                while (cursor.moveToNext()) {
                    val id          = cursor.getLong(idIdx)
                    val uri         = ContentUris.withAppendedId(collection, id)
                    val displayName = cursor.getString(nameIdx) ?: continue
                    val mime        = cursor.getString(mimeIdx) ?: getMimeType(displayName, defaultMime)
                    val relPath     = if (pathIdx >= 0) cursor.getString(pathIdx) ?: "" else relativePathPrefix
                    val width       = if (widthIdx >= 0) cursor.getInt(widthIdx) else 0
                    val height      = if (heightIdx >= 0) cursor.getInt(heightIdx) else 0
                    val duration    = if (durationIdx >= 0) cursor.getLong(durationIdx) else 0L

                    items += mapOf(
                        "content_uri"   to uri.toString(),
                        "display_name"  to displayName,
                        "mime_type"     to mime,
                        "media_type"    to mediaType,
                        "relative_path" to relPath.trimEnd('/'),
                        "size"          to cursor.getLong(sizeIdx),
                        "date_added"    to cursor.getLong(addedIdx),
                        "date_modified" to cursor.getLong(modifiedIdx),
                        "width"         to width,
                        "height"        to height,
                        "duration"      to duration,
                    )
                }
            }
        return items
    }

    // ─────────── Collection URI helpers ───────────────────────────────────────

    private fun videoCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    private fun audioCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    private fun imageCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    // ─────────────────────────────── Shared save/list helpers ─────────────────

    private fun saveToMediaStore(
        collection: Uri, displayName: String, mimeType: String,
        relativePath: String, isPendingColumn: String,
        source: File, path: String, result: Result, tag: String,
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
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(source).use { it.copyTo(out) }
            } ?: throw IllegalStateException("Unable to open MediaStore output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply {
                    put(isPendingColumn, 0)
                    put(MediaStore.MediaColumns.SIZE, source.length())
                }, null, null)
            }
            result.success(mapOf(
                "success"       to true,
                "content_uri"   to uri.toString(),
                "display_name"  to displayName,
                "mime_type"     to mimeType,
                "relative_path" to relativePath,
                "source_path"   to path,
                "size"          to source.length(),
            ))
        } catch (e: Exception) {
            Log.e(TAG, "$tag: ${e.message}", e)
            uri?.let { try { resolver.delete(it, null, null) } catch (_: Exception) {} }
            result.error("SAVE_ERROR", e.message, e.toString())
        }
    }

    private fun listFromMediaStore(
        collection: Uri, relativePathPrefix: String,
        defaultMime: String, listKey: String, result: Result, tag: String,
    ) {
        val items = queryCollection(collection, listKey.trimEnd('s'),
            defaultMime, relativePathPrefix, null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC")
        result.success(mapOf("success" to true, listKey to items))
    }

    // ─────────────────────────────────── Video ────────────────────────────────

    private fun saveVideo(call: MethodCall, result: Result) {
        val path = call.argument<String>("path")
        val requestedFileName = call.argument<String>("fileName")
        val album = call.argument<String>("album")?.trim()?.trim('/')?.takeIf { it.isNotBlank() } ?: "MyApp"
        if (path.isNullOrBlank()) { result.error("INVALID_ARGUMENT", "path required", null); return }
        val source = File(path)
        if (!source.isFile) { result.error("FILE_NOT_FOUND", "not found: $path", null); return }
        val displayName = requestedFileName?.trim()?.takeIf { it.isNotBlank() } ?: source.name
        saveToMediaStore(videoCollection(), displayName, getMimeType(displayName, "video/mp4"),
            "${Environment.DIRECTORY_MOVIES}/$album", MediaStore.Video.Media.IS_PENDING,
            source, path, result, "saveVideo")
    }

    private fun listVideos(call: MethodCall, result: Result) {
        val album = call.argument<String>("album")?.trim()?.trim('/')?.takeIf { it.isNotBlank() } ?: "MyApp"
        listFromMediaStore(videoCollection(), "${Environment.DIRECTORY_MOVIES}/$album",
            "video/mp4", "videos", result, "listVideos")
    }

    // ─────────────────────────────────── Audio ────────────────────────────────

    private fun saveAudio(call: MethodCall, result: Result) {
        val path = call.argument<String>("path")
        val requestedFileName = call.argument<String>("fileName")
        val album = call.argument<String>("album")?.trim()?.trim('/')?.takeIf { it.isNotBlank() } ?: "MyApp"
        if (path.isNullOrBlank()) { result.error("INVALID_ARGUMENT", "path required", null); return }
        val source = File(path)
        if (!source.isFile) { result.error("FILE_NOT_FOUND", "not found: $path", null); return }
        val displayName = requestedFileName?.trim()?.takeIf { it.isNotBlank() } ?: source.name
        saveToMediaStore(audioCollection(), displayName, getMimeType(displayName, "audio/mpeg"),
            "${Environment.DIRECTORY_MUSIC}/$album", MediaStore.Audio.Media.IS_PENDING,
            source, path, result, "saveAudio")
    }

    private fun listAudio(call: MethodCall, result: Result) {
        val album = call.argument<String>("album")?.trim()?.trim('/')?.takeIf { it.isNotBlank() } ?: "MyApp"
        listFromMediaStore(audioCollection(), "${Environment.DIRECTORY_MUSIC}/$album",
            "audio/mpeg", "audio", result, "listAudio")
    }

    // ─────────────────────────────────── Image ────────────────────────────────

    private fun saveImage(call: MethodCall, result: Result) {
        val path = call.argument<String>("path")
        val requestedFileName = call.argument<String>("fileName")
        val album = call.argument<String>("album")?.trim()?.trim('/')?.takeIf { it.isNotBlank() } ?: "MyApp"
        if (path.isNullOrBlank()) { result.error("INVALID_ARGUMENT", "path required", null); return }
        val source = File(path)
        if (!source.isFile) { result.error("FILE_NOT_FOUND", "not found: $path", null); return }
        val displayName = requestedFileName?.trim()?.takeIf { it.isNotBlank() } ?: source.name
        saveToMediaStore(imageCollection(), displayName, getMimeType(displayName, "image/jpeg"),
            "${Environment.DIRECTORY_PICTURES}/$album", MediaStore.Images.Media.IS_PENDING,
            source, path, result, "saveImage")
    }

    private fun listImages(call: MethodCall, result: Result) {
        val album = call.argument<String>("album")?.trim()?.trim('/')?.takeIf { it.isNotBlank() } ?: "MyApp"
        listFromMediaStore(imageCollection(), "${Environment.DIRECTORY_PICTURES}/$album",
            "image/jpeg", "images", result, "listImages")
    }

    // ─────────────────────────── Delete (universal) ───────────────────────────

    private fun deleteMedia(call: MethodCall, result: Result) {
        val contentUri = call.argument<String>("contentUri") ?: call.argument<String>("content_uri")
        if (contentUri.isNullOrBlank()) {
            result.error("INVALID_ARGUMENT", "contentUri required", null); return
        }
        try {
            val uri = Uri.parse(contentUri)
            val rows = context.contentResolver.delete(uri, null, null)
            result.success(mapOf("success" to (rows > 0), "content_uri" to contentUri, "deleted_rows" to rows))
        } catch (e: Exception) {
            Log.e(TAG, "deleteMedia: ${e.message}", e)
            result.error("DELETE_ERROR", e.message, e.toString())
        }
    }

    /**
     * Batch-delete a list of content:// URIs in a single call.
     *
     * Arguments:
     *   contentUris  List<String>  — URIs as returned by save_*/get_assets
     *
     * Returns { success, results: List<Boolean>, deleted_count, total }.
     */
    private fun deleteAssets(call: MethodCall, result: Result) {
        @Suppress("UNCHECKED_CAST")
        val uris = call.argument<List<String>>("contentUris") ?: emptyList()

        if (uris.isEmpty()) {
            result.success(mapOf("success" to true, "results" to emptyList<Boolean>(),
                "deleted_count" to 0, "total" to 0))
            return
        }

        val resolver = context.contentResolver
        val results = mutableListOf<Boolean>()

        for (uriStr in uris) {
            val deleted = try {
                resolver.delete(Uri.parse(uriStr), null, null) > 0
            } catch (e: Exception) {
                Log.w(TAG, "deleteAssets: failed for $uriStr — ${e.message}")
                false
            }
            results += deleted
        }

        result.success(mapOf(
            "success"       to true,
            "results"       to results,
            "deleted_count" to results.count { it },
            "total"         to uris.size,
        ))
    }
}
