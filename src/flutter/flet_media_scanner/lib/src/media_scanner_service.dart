import 'dart:convert';

import 'package:flet/flet.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class MediaScannerService extends FletService {
  static const MethodChannel _channel =
      MethodChannel('flet_media_scanner/scan');

  MediaScannerService({required super.control});

  @override
  void init() {
    super.init();
    control.addInvokeMethodListener(_onInvokeMethod);
    debugPrint("MediaScannerService: initialized");
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
      // ── Legacy scan ───────────────────────────────────────────────────────
      "scan_media"          => await _scanMedia(arguments),
      _                     => throw Exception(
          "MediaScannerService: unknown method '$methodName'"),
    };
  }

  /// Generic: calls any Kotlin method that takes named args and returns a Map.
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

  /// Delete: accepts both content_uri (Python) and contentUri (Kotlin).
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

  /// Deprecated — use save_video/save_audio/save_image instead.
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
      if (args.containsKey("contentUri")) "contentUri": args["contentUri"],
      if (args.containsKey("content_uri")) "contentUri": args["content_uri"],
    };
  }

  String _encode(Map<String, dynamic> payload) => jsonEncode(payload);

  @override
  void dispose() {
    debugPrint("MediaScannerService: disposed");
    super.dispose();
  }
}
