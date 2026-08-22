# flet-media-scanner

[![PyPI version](https://img.shields.io/pypi/v/flet-media-scanner)](https://pypi.org/project/flet-media-scanner/)
[![PyPI downloads](https://img.shields.io/pypi/dm/flet-media-scanner)](https://pypi.org/project/flet-media-scanner/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://developer.android.com)

A [Flet](https://flet.dev) extension for Android **MediaStore** integration — save, list, query, delete, move, and rename **videos**, **audio**, and **images** in the device Gallery / Music / Pictures without requiring broad storage permissions.

Built entirely with **native Kotlin** using Android's `MediaStore` APIs directly — no third-party Flutter packages required.

> **Author:** [Fazi Gondal](https://github.com/fazi-gondal) · [nextinpk@gmail.com](mailto:nextinpk@gmail.com)

---

## Requirements

| Requirement | Version |
|-------------|---------|
| **Platform** | Android only (no-op on other platforms) |
| **Android API** | 23+ (minSdk 23) |
| **Flet** | >= 0.86.0 |
| **Python** | >= 3.12 |

---

## Architecture

```
Python API  →  Dart bridge  →  Kotlin (native MediaStore)
                                      ↕ EventChannel
                               ContentObserver (on_change)
```

Unlike typical Flet extensions that wrap Flutter packages, this extension bypasses the Flutter package ecosystem entirely and calls Android's `MediaStore` APIs directly from Kotlin — giving you a lighter, faster, and fully self-contained plugin.

---

## Supported Formats

### 🎬 Video → `Movies/<album>/`
| Extension | MIME Type |
|-----------|-----------|
| `.mp4` | `video/mp4` |
| `.mkv` | `video/x-matroska` |
| `.webm` | `video/webm` |
| `.avi` | `video/x-msvideo` |
| `.mov` | `video/quicktime` |
| `.3gp` | `video/3gpp` |
| `.ts` | `video/mp2t` |
| `.flv` | `video/x-flv` |

### 🎵 Audio → `Music/<album>/`
| Extension | MIME Type |
|-----------|-----------|
| `.mp3` | `audio/mpeg` |
| `.m4a` | `audio/mp4` |
| `.aac` | `audio/aac` |
| `.flac` | `audio/flac` |
| `.opus` | `audio/opus` |
| `.wav` | `audio/wav` |
| `.ogg` | `audio/ogg` |
| `.wma` | `audio/x-ms-wma` |
| `.aiff` | `audio/aiff` |

### 🖼 Image → `Pictures/<album>/`
| Extension | MIME Type |
|-----------|-----------|
| `.jpg` / `.jpeg` | `image/jpeg` |
| `.png` | `image/png` |
| `.gif` | `image/gif` |
| `.webp` | `image/webp` |
| `.bmp` | `image/bmp` |
| `.heic` | `image/heic` |
| `.heif` | `image/heif` |
| `.avif` | `image/avif` |
| `.svg` | `image/svg+xml` |
| `.tiff` / `.tif` | `image/tiff` |

> MIME type is resolved automatically from the file extension — no manual configuration needed.

---

## Installation

```bash
pip install flet-media-scanner
```

---

## Setup

Register the extension in your app's `pyproject.toml`:

```toml
[tool.flet.extensions]
flet_media_scanner = "flet_media_scanner.Extension"
```

---

## Quick Start

```python
import os
import flet as ft
from flet_media_scanner import MediaScanner, SaveResult

async def main(page: ft.Page):
    scanner = MediaScanner()
    storage = ft.StoragePaths()
    page.services.extend([scanner, storage])
    page.update()

    # Use ft.StoragePaths to resolve the correct cache directory (cross-device safe)
    cache_dir = await storage.get_application_cache_directory()

    # ── Permissions ────────────────────────────────────────────────────────────
    status = await scanner.check_permissions()
    if not status.all_granted:
        status = await scanner.request_permissions()

    # ── Save Video ─────────────────────────────────────────────────────────────
    result: SaveResult = await scanner.save_video(
        os.path.join(cache_dir, "video.mp4"),
        file_name="my_video.mp4",
        album="MyApp",
    )
    if result.success:
        print(f"Saved → {result.content_uri}")

    # ── Save Audio ─────────────────────────────────────────────────────────────
    result = await scanner.save_audio(
        os.path.join(cache_dir, "song.mp3"),
        file_name="song.mp3",
        album="MyApp",
    )

    # ── Save Image ─────────────────────────────────────────────────────────────
    result = await scanner.save_image(
        os.path.join(cache_dir, "photo.jpg"),
        file_name="photo.jpg",
        album="MyApp",
    )

    # ── Generic query (all types) ───────────────────────────────────────────────
    assets, total = await scanner.get_assets(
        media_type="all",   # "video" | "audio" | "image" | "all"
        limit=50,
        offset=0,
        sort_by="date_added",
        sort_order="desc",
    )
    for a in assets:
        print(a.display_name, a.mime_type, a.size)

    # ── Albums ─────────────────────────────────────────────────────────────────
    albums = await scanner.get_albums()
    for album in albums:
        print(album.name, album.count, album.cover_uri)

    # ── Thumbnail ──────────────────────────────────────────────────────────────
    b64 = await scanner.get_thumbnail(assets[0].content_uri, width=200, height=200)
    if b64:
        page.add(ft.Image(src_base64=b64))

    # ── Rename ─────────────────────────────────────────────────────────────────
    ok = await scanner.rename_asset(assets[0].content_uri, "renamed.jpg")

    # ── Move ───────────────────────────────────────────────────────────────────
    ok = await scanner.move_asset(assets[0].content_uri, new_relative_path="Pictures/Archive")

    # ── Batch delete ───────────────────────────────────────────────────────────
    uris = [a.content_uri for a in assets[:5]]
    results = await scanner.delete_assets(uris)   # list[bool]

    # ── Change observer ────────────────────────────────────────────────────────
    import json
    def on_change(e):
        data = json.loads(e.data)
        print(f"MediaStore changed: {data['collection']} — {data.get('uri', '')}")

    scanner.on_change = on_change

ft.run(main)
```

---

## API Reference

### `MediaScanner` (Service)

Add to `page.services` before calling any methods.

---

### 🔐 Permissions

| Method | Description |
|--------|-------------|
| `await check_permissions() → PermissionStatus` | Check current runtime permission status (no dialog shown) |
| `await request_permissions() → PermissionStatus` | Request media permissions at runtime (shows system dialog if needed) |

#### `PermissionStatus` dataclass

| Field | Type | Values |
|-------|------|--------|
| `images` | `str` | `"granted"` · `"denied"` |
| `video` | `str` | `"granted"` · `"denied"` |
| `audio` | `str` | `"granted"` · `"denied"` |
| `all_granted` | `bool` | `True` if all three are granted |
| `any_denied_forever` | `bool` | `True` if any permission is permanently denied |

```python
status = await scanner.check_permissions()
if status.all_granted:
    ...
elif status.any_denied_forever:
    # Direct user to Settings
    ...
else:
    status = await scanner.request_permissions()
```

> Android version logic:
> - **API ≤ 32**: uses `READ_EXTERNAL_STORAGE`
> - **API 33+** (Android 13): uses `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`

---

### 🔍 Generic Query

#### `await get_assets(...) → tuple[list[MediaAsset], int]`

Unified paginated query across video, audio, and/or image collections.

```python
assets, total = await scanner.get_assets(
    media_type="all",         # "video" | "audio" | "image" | "all"
    album=None,               # None = all albums; "MyApp" = specific album
    mime_type=None,           # e.g. "video/mp4", "image/jpeg" — exact match
    limit=50,                 # max items to return (-1 = no limit)
    offset=0,                 # pagination offset
    sort_by="date_added",     # "date_added" | "date_modified" | "display_name" | "size" | "duration"
    sort_order="desc",        # "asc" | "desc"
)
# Returns (list[MediaAsset], total_count_before_pagination)
```

#### `MediaAsset` dataclass

| Field | Type | Description |
|-------|------|-------------|
| `content_uri` | `str` | `content://` URI (use for all subsequent operations) |
| `display_name` | `str` | Filename |
| `mime_type` | `str` | e.g. `video/mp4` |
| `media_type` | `str` | `"video"` · `"audio"` · `"image"` |
| `relative_path` | `str` | e.g. `Movies/MyApp` |
| `size` | `int` | Bytes |
| `date_added` | `int` | Unix timestamp (seconds) |
| `date_modified` | `int` | Unix timestamp (seconds) |
| `width` | `int` | Pixels (0 for audio) |
| `height` | `int` | Pixels (0 for audio) |
| `duration` | `int` | Milliseconds (0 for images) |

---

### 📁 Album Management

| Method | Description |
|--------|-------------|
| `await get_albums(media_type="all") → list[AlbumInfo]` | List all albums, sorted by item count (largest first) |
| `await delete_album(relative_path, media_type="all") → int` | Delete all items in an album; returns deleted count |

#### `AlbumInfo` dataclass

| Field | Type | Description |
|-------|------|-------------|
| `name` | `str` | Album folder name, e.g. `"MyApp"` |
| `relative_path` | `str` | Full path, e.g. `"Movies/MyApp"` |
| `count` | `int` | Number of items in the album |
| `cover_uri` | `str` | `content://` URI of most recently added item (use as cover art) |
| `media_type` | `str` | `"video"` · `"audio"` · `"image"` |

```python
# List video albums
albums = await scanner.get_albums(media_type="video")
for album in albums:
    print(f"{album.name}: {album.count} items")

# Delete all images from an album
deleted = await scanner.delete_album("Pictures/Temp", media_type="image")
print(f"Deleted {deleted} items")
```

> **Note:** `get_albums` requires Android 10+ (API 29). Returns `[]` on older devices.

---

### 🖼 Thumbnail

#### `await get_thumbnail(content_uri, width=200, height=200) → str`

Returns a **base64-encoded JPEG** thumbnail for an image asset.

```python
b64 = await scanner.get_thumbnail(asset.content_uri, width=300, height=300)
if b64:
    page.add(ft.Image(src_base64=b64))
```

| Detail | Value |
|--------|-------|
| Output | Base64 JPEG string (use with `ft.Image(src_base64=...)`) |
| Android 10+ | `ContentResolver.loadThumbnail()` — respects `width`/`height` |
| Android < 10 | `MediaStore.Images.Thumbnails.MINI_KIND` (~512×384, size ignored) |
| Supports | Images only. Video thumbnails planned for a future release. |

---

### 👀 Change Observer

Fired whenever the Android MediaStore reports a change on the device.

```python
import json

def handle_change(e):
    data = json.loads(e.data)
    collection = data["collection"]   # "image" | "video" | "audio"
    uri = data.get("uri", "")        # content:// URI of changed item (may be empty)
    print(f"MediaStore changed: {collection} — {uri}")

scanner.on_change = handle_change
```

> The observer starts automatically on app launch and watches all three MediaStore collections. It fires for **any** device MediaStore change (not just your app's), so debounce or filter as needed.

---

### ✏️ Rename / Move

| Method | Description |
|--------|-------------|
| `await rename_asset(content_uri, new_name) → bool` | Rename a file in-place (changes `DISPLAY_NAME` only) |
| `await move_asset(content_uri, new_relative_path, new_name=None) → bool` | Move to a different folder, optionally renaming |

```python
# Rename (all Android versions)
ok = await scanner.rename_asset(asset.content_uri, "holiday_2024.mp4")

# Move to an archive folder (Android 10+ / API 29+)
ok = await scanner.move_asset(asset.content_uri, new_relative_path="Movies/Archive")

# Move + rename simultaneously
ok = await scanner.move_asset(
    asset.content_uri,
    new_relative_path="Pictures/Edited",
    new_name="cropped_photo.jpg",
)
```

> `move_asset` requires Android 10+ (API 29). `rename_asset` works on all versions. The `content://` URI remains the same after both operations.

---

### 🗑 Delete

| Method | Description |
|--------|-------------|
| `await delete_media(content_uri) → bool` | Delete any MediaStore item by its `content://` URI |
| `await delete_video(content_uri) → bool` | Alias for `delete_media()` |
| `await delete_assets(content_uris) → list[bool]` | Batch delete multiple items; one `bool` per URI |
| `await delete_album(relative_path, media_type="all") → int` | Delete all items in an album |

```python
# Single delete
deleted = await scanner.delete_media("content://media/external/images/media/42")

# Batch delete
uris = [a.content_uri for a in assets]
results = await scanner.delete_assets(uris)
print(f"Deleted {sum(results)}/{len(results)} items")
```

---

### 🎬 Video

| Method | Description |
|--------|-------------|
| `await save_video(file_path, file_name=None, album="MyApp") → SaveResult` | Copy an app-private video into `Movies/<album>` |
| `await list_videos(album="MyApp") → list[dict]` | List all videos in the album |

### 🎵 Audio

| Method | Description |
|--------|-------------|
| `await save_audio(file_path, file_name=None, album="MyApp") → SaveResult` | Copy an app-private audio file into `Music/<album>` |
| `await list_audio(album="MyApp") → list[dict]` | List all audio files in the album |

### 🖼 Image

| Method | Description |
|--------|-------------|
| `await save_image(file_path, file_name=None, album="MyApp") → SaveResult` | Copy an app-private image into `Pictures/<album>` |
| `await list_images(album="MyApp") → list[dict]` | List all images in the album |

#### Common `save_*` parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `file_path` | `str` | — | Absolute path to the source file (use `ft.StoragePaths` to resolve) |
| `file_name` | `str \| None` | `None` | Display name in Gallery (defaults to source basename) |
| `album` | `str` | `"MyApp"` | Sub-folder inside `Movies/`, `Music/`, or `Pictures/` |

---

### `SaveResult` (dataclass)

Returned by all `save_*` methods.

| Field | Type | Description |
|-------|------|-------------|
| `success` | `bool` | `True` if the file was saved successfully |
| `content_uri` | `str` | Android MediaStore `content://` URI |
| `display_name` | `str` | Filename as shown in the Gallery/Files app |
| `mime_type` | `str` | e.g. `video/mp4`, `audio/mpeg`, `image/jpeg` |
| `relative_path` | `str` | e.g. `Movies/MyApp/` |
| `source_path` | `str` | Original source file path |
| `size` | `int` | File size in bytes |
| `error` | `str` | Error message if `success=False` |

---

### Events

| Event | When fired | `e.data` shape |
|-------|-----------|----------------|
| `on_saved` | After a successful `save_*` call | SaveResult fields as JSON |
| `on_change` | MediaStore item added/removed/modified | `{"collection": "image"\|"video"\|"audio", "uri": "..."}` |

---

## Changelog

| Version | Changes |
|---------|---------|
| **1.7.0** | Move/Rename — `rename_asset()` (all versions) + `move_asset()` (API 29+) |
| **1.6.0** | Batch delete — `delete_assets(list[str])` → `list[bool]` |
| **1.5.0** | Change observer — `on_change` event via `EventChannel` + `ContentObserver` |
| **1.4.0** | Thumbnail API — `get_thumbnail()` returns base64 JPEG via `loadThumbnail` (API 29+) |
| **1.3.0** | Album management — `get_albums()` + `delete_album()` + `AlbumInfo` dataclass |
| **1.2.0** | Generic query — `get_assets()` with filters, pagination, sorting + `MediaAsset` dataclass |
| **1.1.0** | Permission API — `check_permissions()` + `request_permissions()` (Android 6–14+) |
| **1.0.1** | Updated README with badges, author info, architecture overview |
| **1.0.0** | Stable release — full native Kotlin, no Flutter package dependencies |
| **0.86.5** | Cleaned up `pubspec.yaml`, removed unused Flutter deps |
| **0.86.4** | Updated README to use `ft.StoragePaths` for cross-device safe paths |
| **0.86.3** | Added audio (MP3, M4A, AAC, FLAC, Opus, WAV) and image support |
| **0.86.2** | Added MKV and WebM video container support |
| **0.86.1** | Development status → Production/Stable |

---

## License

MIT © [Fazi Gondal](https://github.com/fazi-gondal)
