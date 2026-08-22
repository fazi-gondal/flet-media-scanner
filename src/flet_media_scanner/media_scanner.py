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


@ft.control("MediaScanner")
class MediaScanner(ft.Service):
    """
    Android media service for publishing files through MediaStore.

    Supports:
    - **Video**: MP4, MKV, WebM, AVI, MOV, 3GP, TS, FLV
    - **Audio**: MP3, M4A, AAC, FLAC, Opus, WAV, OGG, WMA
    - **Image**: JPEG, PNG, GIF, WebP, BMP, HEIC, HEIF, AVIF, TIFF

    MIME type is resolved automatically from the file extension.
    New downloads should use the appropriate save_*() method.
    scan_media() is kept only for legacy files that already exist in
    public storage and need Gallery indexing.
    """

    on_saved: Optional[ft.EventHandler[Any]] = None
    on_scanned: Optional[ft.EventHandler[Any]] = None

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
        Scan a legacy public media file so it appears in Gallery/Photos.

        New downloads should use save_video() / save_audio() / save_image() instead;
        MediaStore inserts do not need this scan.
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
            success = result == "true"
            print(f"[MediaScanner] scan_media: {file_path} -> result={result} success={success}")
            return success
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
