# HoldFrame — Java desktop editor (Windows + Linux)

The **same Java desktop application** runs on Windows and Linux. This is the `windows`
branch because it also supplies the native Windows installer; no Windows-only code is
required to run the desktop UI.

## Animation without redundant frames

HoldFrame treats each drawing as one editable vector pose. **Drag a pose's right-hand
timeline edge to extend its exposure** just like stretching a still-image clip in a
video editor. A 5-minute hold still references exactly one drawing, not 7,200 images.

### New drawing controls

- **Onion skin** (enabled by default): previous pose appears red; next pose appears blue.
  Show/hide either independently using the dockable tool palette.
- **Lasso / Move**: draw a freehand loop around strokes to select them. Drag inside
  the selection to move it, or drag the lower-right square to scale it. Ctrl+A
  selects every stroke in the current pose for whole-pose repositioning.
- **Ctrl+C** copies the selected drawing or a **Shift-selected contiguous range of drawings**, including each hold duration, as an immutable snapshot.
- **Ctrl+V** repeatedly inserts the entire copied pose sequence in order, advancing the
  playhead to the first newly inserted pose. Repeated pastes share the same stored drawing until any
  individual pasted pose is edited (copy-on-write). There is no 10,000-pose cap;
  a 1,000,000-exposure safety bound prevents accidental runaway memory use.
- **Range selection:** click a timeline pose to set an anchor, Shift-click another pose
  or Shift-drag across the timeline (edge auto-scroll works) to select all poses in between.
  Ctrl+Shift+A selects the whole pose timeline. The range is highlighted, and a pasted
  sequence is selected so you can immediately paste it again.
- **Ctrl+Z / Ctrl+Y** undo and redo pen, eraser, and transform edits.
- Pen, true transparent eraser, brush size, colors, per-pose exposure timing, playback,
  project open/save, and an always-visible floating editing palette.
- Playback is based on elapsed clock time rather than accumulating timer drift.
- Editing data is stored in one versioned, compact, compressed `.holdframe` file.
- **Timeline auto-scroll:** while resizing or scrubbing, hold the pointer near either edge of the visible timeline to scroll in that direction, even when the mouse stops moving. Resizing the last drawing can extend beyond the old end without manually dragging the scrollbar. Timeline mouse wheel also pans horizontally.
- **Native file picker + dockable toolbar:** no layered overlay covering the Windows file dialog, duplicated timeline areas, or oversized floating brush slider. The brush toolbar can still float independently if dragged.
- **Playhead follow:** playback and Previous/Next Frame navigate the timeline without requiring manual horizontal scrolling; mouse scrubbing keeps manual control.
- **GUI polish:** the timeline and canvas paint their full backgrounds (no unpainted grey strips), the scrollbar stays visible instead of shifting the track during edits, and Ctrl+A activates the matching Lasso button. The timeline no longer needlessly resets the drawing cache on every exposure-width adjustment.

### Run on Windows

Build with JDK 17+ using `./build.ps1` and launch `dist/HoldFrame.jar`, or run
`launch.bat`. The GitHub Actions workflow produces an interactive NSIS installer with
visible progress and a checked-by-default launch option. The installer has a bundled
Java runtime and does not install silently.

### Run on Linux

Install OpenJDK 17+, then run:

```bash
./build.sh
./launch.sh
```

The Linux workflow creates a standalone application image with its own runtime and a
portable JAR. Building from source needs only a JDK; no Gradle or external libraries.

### Test

```bash
mkdir -p build/classes
javac --release 17 -d build/classes core/src/main/java/dev/rectifydata/holdframe/core/*.java \
  core/src/test/java/dev/rectifydata/holdframe/core/*.java \
  desktop/src/main/java/dev/rectifydata/holdframe/windows/*.java \
  desktop/src/test/java/dev/rectifydata/holdframe/windows/*.java
java -cp build/classes dev.rectifydata.holdframe.core.CoreTest
java -cp build/classes dev.rectifydata.holdframe.windows.LassoTest
xvfb-run -a java -cp build/classes dev.rectifydata.holdframe.windows.DesktopFeatureTest
xvfb-run -a java -cp build/classes dev.rectifydata.holdframe.windows.TimelineAutoScrollTest
```

Core model, file format, and optimizations originate in `main`. The `android` branch
holds a separate low-RAM native Android implementation. Both use the same data format.

**Limitations:** The editor uses a single drawing layer per pose. MP4 export requires
FFmpeg (selected from the GUI when not on PATH). HoldFrame still stores editable
strokes/exposures instead of duplicate frame images, while video exports are
normal encoded video files. Extremely complex projects can use substantial RAM.

### Editable text and four-corner transforming (v0.3.0)

Select **Text**, then click the drawing canvas to insert type. The text dialog lets you
edit multiple lines, choose **any font family installed on your computer**, load a
local **TTF/OTF/TTC/OTC or Type 1 font file** (when supported by Java), select point
size, bold/italic and text color. Click existing text with the Text tool to reopen
its editor; alternatively double-click it in Lasso mode.

Choose **Lasso / Move** and draw around text or strokes, or press **Ctrl+A** to select
everything in the pose. Four square handles appear (top-left, top-right, bottom-left,
bottom-right): drag any corner to resize, or drag inside the outlined box to move.
Text remains editable after scaling and repositioning. Undo/redo includes stroke
widths and text transforms.

Projects use **HoldFrame HFM2** to retain editable text, colors, fonts, styling,
position and transforms. Previous **HFM1** projects still open without conversion
prompts. Font files are **never copied into project files or app downloads**:
projects store a local path and family fallback. To preserve the exact appearance
on a second computer, install or provide the same font there. Text will remain
editable even if its source font is unavailable. Java font support varies for
certain font-file subtypes (WOFF/WOFF2 are not supported).

### Imported images and audio tracks (v0.4.0)

- **+ Image** opens a native picker. PNG, JPEG, BMP and GIF stills are imported,
  rendered underneath editable pen/text strokes, and selectable using Lasso. Move or
  resize them with the existing four handles. Image bytes are embedded once in the
  compressed project and reused by every copied pose. Animated GIFs use their first frame.
- **+ Audio** imports WAV/AIFF/AU directly, or MP3/OGG when FFmpeg/ffprobe are
  installed. Sound is placed on a separate track at the playhead. Drag the audio clip
  to move it; drag its right edge to shorten/extend its playback range. Select a sound
  to change mute or volume. Play synchronizes audio with the drawing timeline.
- The **HFM4** format preserves old projects (HFM1–HFM3 can still be opened). Audio
  files are referenced on disk rather than embedded to keep `.holdframe` compact.
  Moving your project between computers requires taking the audio files along too.
- **Multi-pose copy/paste:** select a contiguous pose sequence on the timeline with
  Shift-click or Shift-drag, then Ctrl+C and Ctrl+V. Repeated pastes reuse image
  assets and shared drawing snapshots; editing one pose detaches it via copy-on-write.
  Audio clips are deliberately not part of a copied pose sequence.

Audio playback on the desktop needs a functioning audio output device. Linux GUI
regressions use Xvfb; hardware audio output and the native Windows build still need
on-device verification.

### Exporting your animation (v0.5.0)

Use **Export…** on the desktop toolbar, **File → Export animation…**, or **Ctrl+E**.
Choose an output format and destination; the export runs on a background worker
with visible progress and a Cancel button. Imported images, strokes, erased areas,
and editable text are rendered at the original **800×450** canvas size.

- **MP4 video + audio**: 24 FPS by default (uses the project's actual FPS), H.264
  video and AAC audio. Multiple overlapping audio clips use their source offsets,
  durations, mute flags, and volume levels. Requires FFmpeg; the app can locate
  a local FFmpeg executable and remembers it, or you can install it on PATH.
- **GIF**: looped animation with one GIF image for each *pose exposure*, using
  that exposure's duration. No audio and no duplicate image for every video frame.
- **PNG sequence ZIP**: one PNG per pose plus `timing.csv` containing original
  frame positions and durations. This format saves disk space for long holds and
  remains useful for sprite/game-engine import.
- **Sprite sheet PNG**: up to 32 poses laid out in rows of four to cap memory use.
  Holds are not encoded in the PNG itself. Use the sequence ZIP for exact timing.
- **Current pose PNG**: exports the selected drawing as an ordinary 800×450 still.

CLI exports work **headlessly**, without opening the GUI:

```bash
java -jar dist/HoldFrame.jar --export project.holdframe --format gif --output animation.gif
java -jar dist/HoldFrame.jar --export project.holdframe --format mp4 --output movie.mp4
java -jar dist/HoldFrame.jar --export project.holdframe --format pngzip --output sequence.zip
java -jar dist/HoldFrame.jar --export project.holdframe --format sheet --output poses.png
java -jar dist/HoldFrame.jar --export project.holdframe --format png --pose 0 --output pose.png
```

Use `--force` to overwrite an existing export and `--ffmpeg /path/to/ffmpeg` to
select an FFmpeg executable from the CLI. MP4 export streams RGB pixels into
FFmpeg directly, with a one-hour video-export guardrail; no folder of temporary
rendered frames is written. GIF and ZIP are also written to a temporary file and
replace the destination only after successful completion.

The Android source shares the HFM4 model but does **not** yet offer these desktop
export formats; the Android APK still requires an SDK build and device testing.

## Feature tabs and keyboard shortcuts

Controls are separated into **Project**, **Draw**, **Animate**, and **Media** tabs; the drawing canvas and timeline stay visible. The Draw tab contains the pen, eraser, lasso, text, color, brush size and onion-skin settings.

The Windows/Linux editor supports persistent, **customizable keyboard keymaps** under Project → Keymaps or Settings → Keyboard keymaps. Every command can be assigned a Java KeyStroke expression such as `control S`, `alt F7`, `SPACE`, `LEFT`, or `control shift A`; duplicate/invalid bindings are rejected. Reset defaults restores the original assignments.

Default shortcuts: Ctrl+S save, Ctrl+O open, Ctrl+Shift+N new, Ctrl+E export, Ctrl+N add pose, Ctrl+D duplicate, Ctrl+C/V copy/paste, Ctrl+Z/Y undo/redo, Ctrl+A select objects, Ctrl+Shift+A select timeline, Ctrl+1–4 change tabs, P/E/L/T select drawing tools, Space play/pause and Left/Right step frames.


## Zoom and updates (Windows and Linux)

Canvas zoom: **Ctrl + mouse wheel**, **View → Zoom in/out**, or customize
the Ctrl+plus, Ctrl+minus and Ctrl+0 shortcuts under **Settings → Keymaps**.
Pan with the middle mouse button; Fit canvas returns to normal zoom.
Zoom only changes canvas presentation and doesn't rasterize the source.

Use **File → Check for updates** to query GitHub releases, including
prereleases. Daily checks are on by default, and can be disabled under
**Settings → Check for updates automatically**. Updates are user-directed;
the app opens the official release page and never installs silently.
The downloadable JAR requires Java 17 or later and runs on Windows/Linux.


## Transforming editable text (desktop)

Choose the **Text** tool, then click any existing text. Drag to reposition it,
or drag any of the four highlighted corner handles to resize its width and
height independently. Double-click to edit the words/font/color. Newly added
text is automatically selected. Transforms support Ctrl+Z/Ctrl+Y and are saved
as editable font/text objects in the project (not flattened to image pixels).
The existing Lasso tool can still transform text alongside images and strokes.
