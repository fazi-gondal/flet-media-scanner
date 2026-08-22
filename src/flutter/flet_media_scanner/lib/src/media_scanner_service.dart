import 'dart:async';
import 'dart:convert';

import 'package:flet/flet.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class MediaScannerService extends FletService {
  static const MethodChannel _channel =
      MethodChannel('flet_media_scanner/scan');

  static const EventChannel _eventChannel =
      EventChannel('flet_media_scanner/changes');

  StreamSubscription<dynamic>? _changeSubscription;

  MediaScannerService({required super.control});

  @override
  void init() {
    super.init();
    control.addInvokeMethodListener(_onInvokeMethod);
    _startChangeObserver();
    debugPrint("MediaScannerService: initialized");
  }

  /// Subscribe to the Kotlin ContentObserver EventChannel.
  /// Each event is forwarded to Python as a "change" event on the control.
  void _startChangeObserver() {
    _changeSubscription = _eventChannel
        .receiveBroadcastStream()
        .listen(
          (dynamic event) {
            if (event is Map) {
              final payload = Map<String, dynamic>.from(event);
              debugPrint("MediaScannerService: change event: $payload");
              control.triggerEvent("change", payload);
            }
          },
          onError: (dynamic error) {
            debugPrint(
                "MediaScannerService: change observer error: $error");
          },
          cancelOnError: false,
        );
  }

  Future<dynamic> _onInvokeMethod(String methodName, dynamic args) async {
    final Map<String, dynamic> arguments =
        (args != null && args is Map) ? Map<String, dynamic>.from(args) : {};

    debugPrint(
        "MediaScannerService._onInvokeMethod: $methodName args=$arguments");

    return switch (methodName) {
      // ── Permissions ───────────────────────────────────────────────────────
      "check_permissions"   => await _invokeChannel("checkPermissions", {}),
      "request_permissions" => await _invokeChannel("requestPermissions", {}),
      // ── Generic query ─────────────────────────────────────────────────────
      "get_assets"          => await _invokeChannel("getAssets", arguments),
      // ── Album management ──────────────────────────────────────────────────
      "get_albums"          => await _invokeChannel("getAlbums", arguments),
      "delete_album"        => await _invokeChannel("deleteAlbum", arguments),
      // ── Thumbnail ─────────────────────────────────────────────────────────
      "get_thumbnail"       => await _invokeChannel("getThumbnail", arguments),
      // ── Video ─────────────────────────────────────────────────────────────
      "save_video"          => await _invokeChannel("saveVideo", arguments),
      "delete_video"        => await _invokeDeleteChannel(arguments),
      "list_videos"         => await _invokeChannel("listVideos", arguments),
      // ── Audio ─────────────────────────────────────────────────────────────
      "save_audio"          => await _invokeChannel("saveAudio", arguments),
      "list_audio"          => await _invokeChannel("listAudio", arguments),
      // ── Image ─────────────────────────────────────────────────────────────
      "save_image"          => await _invokeChannel("saveImage", arguments),
      "list_images"         => await _invokeChannel("listImages", arguments),
      // ── Delete (universal) ────────────────────────────────────────────────
      "delete_media"        => await _invokeDeleteChannel(arguments),
      "delete_assets"       => await _invokeChannel("deleteAssets", arguments),
      // ── Legacy scan ───────────────────────────────────────────────────────
      "scan_media"          => await _scanMedia(arguments),
      _                     => throw Exception(
          "MediaScannerService: unknown method '$methodName'"),
    };
  }

  Future<String> _invokeChannel(
      String kotlinMethod, Map<String, dynamic> args) async {
    try {
      final result = await _channel
          .invokeMethod<Map<dynamic, dynamic>>(kotlinMethod, _toKotlinArgs(args));
      final payload = Map<String, dynamic>.from(result ?? {});
      if (kotlinMethod.startsWith("save")) {
        control.triggerEvent("saved", payload);
      }
      return _encode(payload);
    } on PlatformException catch (e) {
      final payload = {
        "success": false,
        "error": "${e.code}: ${e.message}",
        ...args,
      };
      return _encode(payload);
    } catch (e, st) {
      debugPrint("MediaScannerService.$kotlinMethod error: $e\n$st");
      return _encode({"success": false, "error": e.toString()});
    }
  }

  Future<String> _invokeDeleteChannel(Map<String, dynamic> args) async {
    final contentUri =
        (args["contentUri"] ?? args["content_uri"]) as String? ?? "";
    if (contentUri.isEmpty) {
      return _encode({"success": false, "error": "contentUri is required"});
    }
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        "deleteMedia",
        {"contentUri": contentUri},
      );
      final payload = Map<String, dynamic>.from(result ?? {});
      control.triggerEvent("deleted", payload);
      return _encode(payload);
    } on PlatformException catch (e) {
      return _encode({
        "success": false,
        "content_uri": contentUri,
        "error": "${e.code}: ${e.message}",
      });
    } catch (e, st) {
      debugPrint("MediaScannerService.deleteMedia error: $e\n$st");
      return _encode({"success": false, "error": e.toString()});
    }
  }

  Future<String> _scanMedia(Map<String, dynamic> args) async {
    final String path = args["path"] as String? ?? "";
    debugPrint(
        "MediaScannerService._scanMedia: deprecated — use save_* methods. path='$path'");
    control.triggerEvent("scanned", {
      "path": path,
      "success": "false",
      "error": "scan_media is deprecated; use save_video/save_audio/save_image",
    });
    return "false";
  }

  Map<String, dynamic> _toKotlinArgs(Map<String, dynamic> args) {
    return {
      if (args.containsKey("path")) "path": args["path"],
      if (args.containsKey("file_name")) "fileName": args["file_name"],
      if (args.containsKey("fileName")) "fileName": args["fileName"],
      if (args.containsKey("album")) "album": args["album"],
      if (args.containsKey("mediaType")) "mediaType": args["mediaType"],
      if (args.containsKey("mimeType")) "mimeType": args["mimeType"],
      if (args.containsKey("limit")) "limit": args["limit"],
      if (args.containsKey("offset")) "offset": args["offset"],
      if (args.containsKey("sortBy")) "sortBy": args["sortBy"],
      if (args.containsKey("sortOrder")) "sortOrder": args["sortOrder"],
      if (args.containsKey("relativePath")) "relativePath": args["relativePath"],
      if (args.containsKey('contentUri')) 'contentUri': args['contentUri'],
      if (args.containsKey('content_uri')) 'contentUri': args['content_uri'],
      if (args.containsKey('contentUris')) 'contentUris': args['contentUris'],
      if (args.containsKey('width')) 'width': args['width'],
      if (args.containsKey('height')) 'height': args['height'],
    };
  }

  String _encode(Map<String, dynamic> payload) => jsonEncode(payload);

  @override
  void dispose() {
    _changeSubscription?.cancel();
    _changeSubscription = null;
    debugPrint("MediaScannerService: disposed");
    super.dispose();
  }
}
