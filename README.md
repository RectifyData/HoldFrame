# HoldFrame — Android branch

Native Android drawing and exposure-timeline editor, sharing the platform-neutral
Java model and compressed `.holdframe` files from `main`.

Designed for devices with limited RAM: only the current pose uses a persistent
render bitmap (600×338 in Low RAM mode or 800×450 normally). Animated holds are timeline references, not image duplicates.

## Features

- Draw, erase, duplicate, delete, and extend drawings like video-editor clips.
- **Copy/Paste poses** from the toolbar or floating palette; keyboard Ctrl+C/V works
  with a connected keyboard. **Copy range** accepts a first and last pose number;
  the entire ordered sequence is pasted with its original exposure durations.
  Repeated pastes share frozen pose data until one is edited (copy-on-write).
- **Onion skin previews:** enable through the floating Tools menu. The previous
  drawing is red and the next is blue. Disabled by default to conserve memory.
  Ghosts use transient vector layers so no extra long-lived frame bitmaps are stored.
- Undo/redo is isolated per pose and does not alter copied sibling poses.
- Time-based playback and a low-overhead exposure timeline.
- Editable autosave and standard Android document open/save.

## Build

GitHub Actions builds a debug APK using Android SDK 35 / Gradle 8.9 and publishes a
**prerelease**, never treating an unverified preview as tested for all devices.
The shared model tests run before APK compilation.

The `windows` branch contains the desktop Swing editor for Windows and Linux.
Desktop adds lasso move/scale; it is not a feature of the Android app in this branch.


## HFM4 images, sound, and pose sequences

- **+ Image** imports PNG/JPEG/GIF/BMP using Android's document picker. Original
  compressed bytes stay in the project only once, even across multiple poses.
  The canvas decodes a downsampled preview on demand to conserve RAM. The floating
  Tools menu offers numeric image placement/resize.
- **+ Audio** imports into app-private storage (60 MB limit), then plays compressed
  sound through Android MediaPlayer when the playhead enters the audio clip.
  Only one simultaneous sound is active in this low-RAM version. External audio
  files are not embedded in `.holdframe` projects; imported audio remains on
  the original device unless exported separately.
- **Copy range** copies contiguous poses chosen by number, with optional Android
  hardware-keyboard Ctrl+C/V for one pose or the selected range. The selected
  range is highlighted on the compact timeline.
- Desktop/Android HFM4 files include vector strokes, editable text, raster image
  assets and external audio references. HFM1–HFM3 remain readable.

**Build status:** Android APK compilation and device testing are still unverified
in this environment. Do not replace a stable daily-driver app solely with this
source-level preview.

## HFM2 text compatibility

The shared engine now reads editable text objects saved by the desktop app, and
the Android canvas renders them using Android system fonts or a user-imported local
font. The toolbar has **+ Text**, and the floating menu offers editing existing text
and importing local TTF/OTF fonts. Imported fonts stay in the app's private storage;
font binaries are never embedded in projects or included in source releases.
Older HFM1 projects remain readable. This Android change is source-level only
until it passes an Android SDK build and device test.

## Android 12+ and Low RAM Settings (v0.6.0 preview)

- **Minimum Android version: 12 (API 31)**; compile/target SDK 35. Native
  platform Android 12 SplashScreen API is used for launch. Android 11 or older
  cannot install the APK.
- Open **Settings → Memory** (or floating **Tools → Settings / Low RAM mode**).
  Choose **Automatic**, **Always on**, or **Always off**. The choice persists.
- **Automatic** detects physical RAM using ActivityManager.MemoryInfo and enables
  Low RAM mode when reported total RAM is **at or below 6 GiB**; failure to read
  physical RAM errs on the conservative side. Users may override this at any time.
- In Low RAM mode, only a scaled raster cache is allocated (~0.77 MiB instead
  of ~1.37 MiB); the saved 800×450 canvas/vector geometry remains unchanged.
  Imported JPEG previews decode at most 768 px and use RGB565 (PNG transparency
  is preserved). Onion previews default off, and when switched on render only the
  previous ghost. Undo histories retain 12 strokes/pose instead of 60. Playback
  redraw and autosave are throttled. Imported image limits are reduced.
- Android trims memory by dropping decoded-image, font and stage raster caches
  without dropping project data. The raster is lazily rebuilt.

**Testing:** `RamProfileTest` is a pure-Java regression test for automatic RAM
thresholds, manual overrides and resource budgets. The GitHub Actions Android
workflow compiles the APK on SDK 35 and uploads a debug-signed experimental build.


## Feature tabs and hardware keymaps (v0.7)

File, Draw, Animate, and Media tabs replace the crowded scrolling toolbar, while the drawing canvas and timeline stay visible. The floating pen/tools button remains available.

Automatic timeline following reveals the current frame during playback, frame stepping, and seek. Dragging an exposure's edge toward the left or right edge of the screen scrolls continuously while held; audio-lane swipes remain available for manual scrolling.

Physical keyboard: Ctrl+1–4 chooses a tab; Ctrl+S/O/N saves/opens/creates a project; Ctrl+C/V copies/pastes; Ctrl+D duplicates; Ctrl+Z/Y undo/redo; Space plays; and Left/Right steps a frame. File → More → Keyboard keymaps chooses the Standard or Animation preset (J add pose, K duplicate, L play, brackets frame-step). The choice persists.


## Canvas zoom and updates (Android 12+)

Pinch with two fingers to zoom (50–800%); move two fingers to pan without
drawing. In **Draw → More** or the floating **Tools** menu, choose Zoom +,
Zoom -, Fit canvas or toggle one-finger Pan mode. Canvas zoom affects only
the viewport, not the quality or coordinates of saved project drawings.
Ctrl+plus, Ctrl+minus and Ctrl+0 work with supported hardware keyboards.

**File → More → Check for updates** queries published GitHub releases,
including prereleases. Updates are checked no more than once per day by
default, and can be disabled in **File → More → Update preferences**.
Installing an update is always user-directed; no silent installations.
Preview APKs are debug-signed and CI signing keys can rotate between runs:
back up projects before changing installations, and never uninstall without
backing up app-private data. Requires Android 12 (API 31) or later.


## Transforming editable text (Android 12+)

Choose **Draw → More → Move / resize text** or the floating **Tools** menu.
Tap a text element to select it, drag to move it, then drag any of its four
blue corner handles to resize width and height. Select **Finish text transform**
to go back to drawing, or **Edit selected text** to change the text/font.
New text opens with handles ready. Undo/redo includes text transforms, while
low RAM mode updates just the selected text during dragging. Two-finger pinch
zoom and pan remain available, and text remains editable across HFM4 saves.


### v0.11 splitting and text deletion

Open **Animate → More → Split at playhead** (hardware keyboard Ctrl+B) to
divide a held pose into two exposures at the current frame. Animation duration,
image assets and audio track timing remain unchanged; editing either half
detaches its shared drawing automatically. Splitting is unavailable exactly at
pose boundaries.

In **Media → More**, choose **Edit selected text** or **Delete selected text**.
Tap text in **Draw → More → Move / resize text** mode to select it, move it
or scale it with four corner handles. Text deletion participates in undo/redo.
The low-RAM action bar remains compact: Split stays in Animate's More menu.
