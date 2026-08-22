# flet-media-scanner

A [Flet](https://flet.dev) extension for Android **MediaStore** integration — save, list, and delete media files in the device Gallery / Music / Pictures without requiring broad storage permissions.

Supports **video**, **audio**, and **image** files. MIME type is resolved automatically from the file extension.

## Requirements

- **Platform**: Android only (no-op on other platforms)
- **Android**: API 23+ (minSdk 23)
- **Flet**: >= 0.86.0

## Supported Formats

### 🎬 Video
| Extension | MIME Type              | Destination          |
|-----------|------------------------|----------------------|
| `.mp4`    | `video/mp4`            | `Movies/<album>/`    |
| `.mkv`    | `video/x-matroska`     | `Movies/<album>/`    |
| `.webm`   | `video/webm`           | `Movies/<album>/`    |
| `.avi`    | `video/x-msvideo`      | `Movies/<album>/`    |
| `.mov`    | `video/quicktime`      | `Movies/<album>/`    |
| `.3gp`    | `video/3gpp`           | `Movies/<album>/`    |
| `.ts`     | `video/mp2t`           | `Movies/<album>/`    |
| `.flv`    | `video/x-flv`          | `Movies/<album>/`    |

### 🎵 Audio
| Extension | MIME Type              | Destination          |
|-----------|------------------------|----------------------|
| `.mp3`    | `audio/mpeg`           | `Music/<album>/`     |
| `.m4a`    | `audio/mp4`            | `Music/<album>/`     |
| `.aac`    | `audio/aac`            | `Music/<album>/`     |
| `.flac`   | `audio/flac`           | `Music/<album>/`     |
| `.opus`   | `audio/opus`           | `Music/<album>/`     |
| `.wav`    | `audio/wav`            | `Music/<album>/`     |
| `.ogg`    | `audio/ogg`            | `Music/<album>/`     |
| `.wma`    | `audio/x-ms-wma`       | `Music/<album>/`     |

### 🖼 Image
| Extension      | MIME Type              | Destination            |
|----------------|------------------------|------------------------|
| `.jpg` / `.jpeg` | `image/jpeg`         | `Pictures/<album>/`    |
| `.png`         | `image/png`            | `Pictures/<album>/`    |
| `.gif`         | `image/gif`            | `Pictures/<album>/`    |
| `.webp`        | `image/webp`           | `Pictures/<album>/`    |
| `.bmp`         | `image/bmp`            | `Pictures/<album>/`    |
| `.heic`        | `image/heic`           | `Pictures/<album>/`    |
| `.heif`        | `image/heif`           | `Pictures/<album>/`    |
| `.avif`        | `image/avif`           | `Pictures/<album>/`    |
| `.tiff`        | `image/tiff`           | `Pictures/<album>/`    |

## Installation

```bash
pip install flet-media-scanner
```

## Setup

Register the extension in your `pyproject.toml`:

```toml
[tool.flet.extensions]
flet_media_scanner = "flet_media_scanner.Extension"
```

## Usage

```python
import os
import flet as ft
from flet_media_scanner import MediaScanner, SaveResult

async def main(page: ft.Page):
    scanner = MediaScanner()
    storage = ft.StoragePaths()
    page.services.extend([scanner, storage])
    page.update()

    # Use StoragePaths to get the app cache directory (cross-device safe)
    cache_dir = await storage.get_application_cache_directory()

    # ── Video ──────────────────────────────────────────────────────────────
    video_path = os.path.join(cache_dir, "video.mp4")
    result: SaveResult = await scanner.save_video(
        video_path,
        file_name="my_video.mp4",
        album="MyApp",
    )
    if result.success:
        print(f"Video saved: {result.content_uri}")

    videos = await scanner.list_videos(album="MyApp")
    for v in videos:
        print(v["display_name"], v["content_uri"])

    # ── Audio ──────────────────────────────────────────────────────────────
    audio_path = os.path.join(cache_dir, "song.mp3")
    result = await scanner.save_audio(
        audio_path,
        file_name="song.mp3",
        album="MyApp",
    )
    if result.success:
        print(f"Audio saved: {result.content_uri}")

    tracks = await scanner.list_audio(album="MyApp")
    for t in tracks:
        print(t["display_name"], t["mime_type"])

    # ── Image ──────────────────────────────────────────────────────────────
    image_path = os.path.join(cache_dir, "photo.jpg")
    result = await scanner.save_image(
        image_path,
        file_name="photo.jpg",
        album="MyApp",
    )
    if result.success:
        print(f"Image saved: {result.content_uri}")

    images = await scanner.list_images(album="MyApp")
    for img in images:
        print(img["display_name"], img["size"])

    # ── Delete (works for any media type) ──────────────────────────────────
    deleted = await scanner.delete_media("content://media/external/video/media/123")
    print("Deleted:", deleted)

ft.run(main)
```

## API

### `MediaScanner` (Service)

Add to `page.services` before calling any methods.

---

#### Video

##### `await save_video(file_path, file_name=None, album="MyApp") → SaveResult`

Copies a private app video into `Movies/<album>` via Android MediaStore.

##### `await list_videos(album="MyApp") → list[dict]`

Returns all videos saved to the album.

---

#### Audio

##### `await save_audio(file_path, file_name=None, album="MyApp") → SaveResult`

Copies a private app audio file into `Music/<album>` via Android MediaStore.

##### `await list_audio(album="MyApp") → list[dict]`

Returns all audio files saved to the album.

---

#### Image

##### `await save_image(file_path, file_name=None, album="MyApp") → SaveResult`

Copies a private app image into `Pictures/<album>` via Android MediaStore.

##### `await list_images(album="MyApp") → list[dict]`

Returns all images saved to the album.

---

#### Delete

##### `await delete_media(content_uri) → bool`

Deletes any MediaStore item (video, audio, or image) by its `content://` URI.

##### `await delete_video(content_uri) → bool`

Alias for `delete_media()`. Kept for backwards compatibility.

---

#### Common parameters for `save_*`

| Parameter   | Type         | Description                                        |
|-------------|--------------|----------------------------------------------------|
| `file_path` | `str`        | Absolute path to the source file                   |
| `file_name` | `str \| None` | Display name (defaults to the source file basename) |
| `album`     | `str`        | Subfolder inside `Movies/`, `Music/`, or `Pictures/` |

---

### `SaveResult` (dataclass)

| Field           | Type   | Description                              |
|-----------------|--------|------------------------------------------|
| `success`       | `bool` | Whether the operation succeeded           |
| `content_uri`   | `str`  | MediaStore `content://` URI               |
| `display_name`  | `str`  | File name as shown in the gallery/app     |
| `mime_type`     | `str`  | e.g. `video/mp4`, `audio/mpeg`, `image/jpeg` |
| `relative_path` | `str`  | e.g. `Movies/MyApp/`                      |
| `source_path`   | `str`  | Original source file path                 |
| `size`          | `int`  | File size in bytes                        |
| `error`         | `str`  | Error message if `success=False`          |

---

### List item fields (`list_videos`, `list_audio`, `list_images`)

Each dict contains: `content_uri`, `display_name`, `mime_type`, `relative_path`, `size`, `date_added`, `date_modified`.

## License

MIT
