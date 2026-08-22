from dataclasses import dataclass
import json
import os
from typing import Any, Optional

import flet as ft


@dataclass
class SaveResult:
    success: bool = False
    content_uri: str = ""
    display_name: str = ""
    mime_type: str = ""
    relative_path: str = ""
    source_path: str = ""
    size: int = 0
    error: str = ""


@dataclass
class MediaAsset:
    """
    A single media item returned by :meth:`MediaScanner.get_assets`.

    ``width``, ``height`` are 0 for audio files.
    ``duration`` (milliseconds) is 0 for images.
    """
    content_uri: str = ""
    display_name: str = ""
    mime_type: str = ""
    media_type: str = ""   # "video" | "audio" | "image"
    relative_path: str = ""
    size: int = 0
    date_added: int = 0
    date_modified: int = 0
    width: int = 0
    height: int = 0
    duration: int = 0      # milliseconds


@dataclass
class AlbumInfo:
    """
    Represents a single album (sub-folder) in the MediaStore.

    ``cover_uri`` is the ``content://`` URI of the most recently added item
    in the album — useful as a thumbnail source for album grid views.
    Requires Android 10+ (API 29); on older devices ``get_albums()`` returns
    an empty list.
    """
    name: str = ""           # e.g. "MyApp"
    relative_path: str = "" # e.g. "Movies/MyApp"
    count: int = 0
    cover_uri: str = ""
    media_type: str = ""    # "video" | "audio" | "image"


@dataclass
class PermissionStatus:
    """
    Holds the runtime permission status for each media type.

    Each field is one of:
    - ``"granted"``        — permission has been granted
    - ``"denied"``         — permission was denied (can still request again)
    - ``"denied_forever"`` — user ticked "don't ask again" (open Settings instead)
    - ``"unknown"``        — could not determine status (e.g. no Activity attached)
    """
    images: str = "unknown"
    video: str = "unknown"
    audio: str = "unknown"

    @property
    def all_granted(self) -> bool:
        """True if images, video, and audio are all granted."""
        return all(v == "granted" for v in (self.images, self.video, self.audio))

    @property
    def any_denied_forever(self) -> bool:
        """True if any permission is permanently denied (user must open Settings)."""
        return any(v == "denied_forever" for v in (self.images, self.video, self.audio))


@ft.control("MediaScanner")
class MediaScanner(ft.Service):
    """
    Android media service for publishing files through MediaStore.

    Supports:
    - **Video**: MP4, MKV, WebM, AVI, MOV, 3GP, TS, FLV
    - **Audio**: MP3, M4A, AAC, FLAC, Opus, WAV, OGG, WMA
    - **Image**: JPEG, PNG, GIF, WebP, BMP, HEIC, HEIF, AVIF, TIFF

    MIME type is resolved automatically from the file extension.

    Usage::

        scanner = MediaScanner()
        page.services.append(scanner)
        page.update()

        # 1. Request permissions (Android 13+ requires this for listing all media)
        status = await scanner.request_permissions()
        if not status.all_granted:
            print("Some permissions were denied")

        # 2. Save a file
        result = await scanner.save_video(path, album="MyApp")
    """

    on_saved: Optional[ft.EventHandler[Any]] = None
    on_scanned: Optional[ft.EventHandler[Any]] = None

    # ─────────────────────────────── Permissions ──────────────────────────────

    async def check_permissions(self) -> PermissionStatus:
        """
        Return the current runtime permission status without prompting the user.

        On Android 13+ this checks ``READ_MEDIA_IMAGES``, ``READ_MEDIA_VIDEO``,
        and ``READ_MEDIA_AUDIO`` independently.
        On Android ≤12 it checks ``READ_EXTERNAL_STORAGE`` (covers all three).
        Returns immediately — no dialog is shown.
        """
        try:
            result = await self._invoke_method("check_permissions", {}, timeout=10.0)
            return self._parse_permission_status(result)
        except Exception as e:
            print(f"[MediaScanner] check_permissions error: {e}")
            return PermissionStatus()

    async def request_permissions(self) -> PermissionStatus:
        """
        Show the Android runtime permission dialog and return the result.

        On Android 13+ requests ``READ_MEDIA_IMAGES``, ``READ_MEDIA_VIDEO``,
        and ``READ_MEDIA_AUDIO``. On Android ≤12 requests
        ``READ_EXTERNAL_STORAGE``.

        If all permissions are already granted, returns immediately with
        ``all_granted=True`` without showing any dialog.

        If the user has permanently denied a permission (``denied_forever``),
        direct them to **Settings → App → Permissions** instead.
        """
        try:
            result = await self._invoke_method("request_permissions", {}, timeout=60.0)
            return self._parse_permission_status(result)
        except Exception as e:
            print(f"[MediaScanner] request_permissions error: {e}")
            return PermissionStatus()

    @staticmethod
    def _parse_permission_status(raw: str | None) -> PermissionStatus:
        try:
            payload = json.loads(raw) if raw else {}
            perms = payload.get("permissions") or {}
            return PermissionStatus(
                images=str(perms.get("images") or "unknown"),
                video=str(perms.get("video") or "unknown"),
                audio=str(perms.get("audio") or "unknown"),
            )
        except Exception:
            return PermissionStatus()

    # ─────────────────────────────── Generic query ────────────────────────────

    async def get_assets(
        self,
        media_type: str = "all",
        album: str | None = None,
        mime_type: str | None = None,
        limit: int = 50,
        offset: int = 0,
        sort_by: str = "date_added",
        sort_order: str = "desc",
    ) -> tuple[list[MediaAsset], int]:
        """
        Query the device MediaStore and return a paginated list of assets.

        Parameters
        ----------
        media_type : str
            ``"all"`` (default), ``"video"``, ``"audio"``, or ``"image"``.
        album : str | None
            Filter to a specific album sub-folder, e.g. ``"MyApp"``.
            ``None`` returns assets from all albums.
        mime_type : str | None
            Exact MIME filter, e.g. ``"video/mp4"`` or ``"image/jpeg"``.
        limit : int
            Maximum number of items to return (default 50, ``-1`` = no limit).
        offset : int
            Number of items to skip (for pagination).
        sort_by : str
            ``"date_added"`` (default), ``"date_modified"``, ``"display_name"``,
            ``"size"``, or ``"duration"``.
        sort_order : str
            ``"desc"`` (default) or ``"asc"``.

        Returns
        -------
        tuple[list[MediaAsset], int]
            ``(assets, total_count)`` where *total_count* is the number of
            matching items **before** pagination.
        """
        try:
            args: dict = {
                "mediaType": media_type,
                "limit": limit,
                "offset": offset,
                "sortBy": sort_by,
                "sortOrder": sort_order,
            }
            if album is not None:
                args["album"] = album
            if mime_type is not None:
                args["mimeType"] = mime_type

            raw = await self._invoke_method("get_assets", args, timeout=30.0)
            payload = json.loads(raw) if raw else {}
            total = int(payload.get("total") or 0)
            assets = [
                MediaAsset(
                    content_uri=str(a.get("content_uri") or ""),
                    display_name=str(a.get("display_name") or ""),
                    mime_type=str(a.get("mime_type") or ""),
                    media_type=str(a.get("media_type") or ""),
                    relative_path=str(a.get("relative_path") or ""),
                    size=int(a.get("size") or 0),
                    date_added=int(a.get("date_added") or 0),
                    date_modified=int(a.get("date_modified") or 0),
                    width=int(a.get("width") or 0),
                    height=int(a.get("height") or 0),
                    duration=int(a.get("duration") or 0),
                )
                for a in (payload.get("assets") or [])
            ]
            return assets, total
        except Exception as e:
            print(f"[MediaScanner] get_assets error: {e}")
            return [], 0

    # ───────────────────────────────── Albums ──────────────────────────────────

    async def get_albums(
        self,
        media_type: str = "all",
    ) -> list[AlbumInfo]:
        """
        Return all media albums found in the MediaStore, grouped by folder.

        Each album corresponds to a ``RELATIVE_PATH`` sub-folder such as
        ``Movies/MyApp`` or ``Pictures/Camera``.

        Parameters
        ----------
        media_type : str
            ``"all"`` (default), ``"video"``, ``"audio"``, or ``"image"``.

        Returns
        -------
        list[AlbumInfo]
            Albums sorted by item count (largest first).
            Returns an empty list on Android < 10 (API 29).
        """
        try:
            raw = await self._invoke_method(
                "get_albums",
                {"mediaType": media_type},
                timeout=20.0,
            )
            payload = json.loads(raw) if raw else {}
            return [
                AlbumInfo(
                    name=str(a.get("name") or ""),
                    relative_path=str(a.get("relative_path") or ""),
                    count=int(a.get("count") or 0),
                    cover_uri=str(a.get("cover_uri") or ""),
                    media_type=str(a.get("media_type") or ""),
                )
                for a in (payload.get("albums") or [])
            ]
        except Exception as e:
            print(f"[MediaScanner] get_albums error: {e}")
            return []

    async def delete_album(
        self,
        relative_path: str,
        media_type: str = "all",
    ) -> int:
        """
        Delete all media items inside an album folder.

        Pass the ``relative_path`` as returned by :meth:`get_albums`
        (e.g. ``"Movies/MyApp"`` or ``"Pictures/Camera"``).

        Parameters
        ----------
        relative_path : str
            The MediaStore relative path of the album to wipe.
        media_type : str
            ``"all"`` (default), ``"video"``, ``"audio"``, or ``"image"``.
            Restricts which MediaStore collection(s) are searched.

        Returns
        -------
        int
            Number of deleted items (0 if album was already empty or not found).
        """
        if not relative_path:
            return 0
        try:
            raw = await self._invoke_method(
                "delete_album",
                {
                    "relativePath": relative_path.strip("/"),
                    "mediaType": media_type,
                },
                timeout=30.0,
            )
            payload = json.loads(raw) if raw else {}
            return int(payload.get("deleted_count") or 0)
        except Exception as e:
            print(f"[MediaScanner] delete_album error: {e}")
            return 0

    # ─────────────────────────────────── Video ────────────────────────────────

    async def save_video(
        self,
        file_path: str,
        file_name: str | None = None,
        album: str = "MyApp",
    ) -> SaveResult:
        """
        Copy an app-private video file into Android MediaStore (Movies/<album>).

        Supported: MP4, MKV (.mkv), WebM (.webm), AVI, MOV, 3GP, TS, FLV.
        MIME type is resolved automatically from the file extension.

        On Android 10+ this publishes without requiring broad storage permissions.
        """
        return await self._save_media("save_video", file_path, file_name, album)

    async def list_videos(self, album: str = "MyApp") -> list[dict]:
        """List videos previously saved to the MediaStore album (Movies/<album>)."""
        return await self._list_media("list_videos", "videos", album)

    # ─────────────────────────────────── Audio ────────────────────────────────

    async def save_audio(
        self,
        file_path: str,
        file_name: str | None = None,
        album: str = "MyApp",
    ) -> SaveResult:
        """
        Copy an app-private audio file into Android MediaStore (Music/<album>).

        Supported: MP3, M4A, AAC (.aac), FLAC, Opus (.opus), WAV, OGG, WMA.
        MIME type is resolved automatically from the file extension.
        """
        return await self._save_media("save_audio", file_path, file_name, album)

    async def list_audio(self, album: str = "MyApp") -> list[dict]:
        """List audio files previously saved to the MediaStore album (Music/<album>)."""
        return await self._list_media("list_audio", "audio", album)

    # ─────────────────────────────────── Image ────────────────────────────────

    async def save_image(
        self,
        file_path: str,
        file_name: str | None = None,
        album: str = "MyApp",
    ) -> SaveResult:
        """
        Copy an app-private image file into Android MediaStore (Pictures/<album>).

        Supported: JPEG, PNG, GIF, WebP, BMP, HEIC, HEIF, AVIF, TIFF, SVG.
        MIME type is resolved automatically from the file extension.
        """
        return await self._save_media("save_image", file_path, file_name, album)

    async def list_images(self, album: str = "MyApp") -> list[dict]:
        """List images previously saved to the MediaStore album (Pictures/<album>)."""
        return await self._list_media("list_images", "images", album)

    # ─────────────────────────────────── Delete ───────────────────────────────

    async def delete_media(self, content_uri: str) -> bool:
        """
        Delete any MediaStore item (video, audio, or image) by its content:// URI.
        """
        if not content_uri:
            return False
        try:
            result = await self._invoke_method(
                "delete_media",
                {"contentUri": content_uri},
                timeout=15.0,
            )
            payload = json.loads(result) if result else {}
            return bool(payload.get("success"))
        except Exception as e:
            print(f"[MediaScanner] delete_media error: {content_uri}: {e}")
            return False

    async def delete_video(self, content_uri: str) -> bool:
        """Delete a MediaStore video item by its content:// URI. Alias for delete_media()."""
        return await self.delete_media(content_uri)

    # ─────────────────────────────────── Legacy ───────────────────────────────

    async def scan_media(self, file_path: str) -> bool:
        """
        .. deprecated::
            Use ``save_video()``, ``save_audio()``, or ``save_image()`` instead.
            MediaStore inserts do not need an explicit scan.
        """
        if not file_path or not os.path.exists(file_path):
            print(f"[MediaScanner] scan_media: file does not exist: {file_path}")
            return False
        try:
            result = await self._invoke_method(
                "scan_media",
                {"path": file_path},
                timeout=15.0,
            )
            return result == "true"
        except Exception as e:
            print(f"[MediaScanner] scan_media error: {file_path}: {e}")
            return False

    # ─────────────────────────────────── Internals ────────────────────────────

    async def _save_media(
        self,
        method: str,
        file_path: str,
        file_name: str | None,
        album: str,
    ) -> SaveResult:
        if not file_path or not os.path.exists(file_path):
            return SaveResult(error=f"file does not exist: {file_path}")
        try:
            result = await self._invoke_method(
                method,
                {
                    "path": file_path,
                    "fileName": file_name or os.path.basename(file_path),
                    "album": album,
                },
                timeout=60.0,
            )
            payload = json.loads(result) if result else {}
            return SaveResult(
                success=bool(payload.get("success")),
                content_uri=str(payload.get("content_uri") or ""),
                display_name=str(payload.get("display_name") or ""),
                mime_type=str(payload.get("mime_type") or ""),
                relative_path=str(payload.get("relative_path") or ""),
                source_path=str(payload.get("source_path") or file_path),
                size=int(payload.get("size") or 0),
                error=str(payload.get("error") or ""),
            )
        except Exception as e:
            return SaveResult(error=str(e), source_path=file_path)

    async def _list_media(self, method: str, list_key: str, album: str) -> list[dict]:
        try:
            result = await self._invoke_method(
                method,
                {"album": album},
                timeout=15.0,
            )
            payload = json.loads(result) if result else {}
            items = payload.get(list_key) or []
            return items if isinstance(items, list) else []
        except Exception as e:
            print(f"[MediaScanner] {method} error: {e}")
            return []
