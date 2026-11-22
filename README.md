# HoldFrame — shared animation engine

This `main` branch contains the **pure-Java animation core**, not a platform GUI.

HoldFrame stores independent 800×450 vector drawings and timeline exposures. Each
exposure points to a drawing and carries a hold duration in animation frames. A
long-held still image does not generate any duplicate PNG files or a video.

## Branches

- `main`: reusable Java core and versioned compressed `.holdframe` project format.
- `windows`: Swing desktop adaptation, **runs on both Windows and Linux**. Has onion
  skins, lasso move/scale, frame shortcuts, and visible Windows installer workflow.
- `android`: native low-memory Android app, with on-demand onion previews and
  reusable pose copy/paste. Android builds publish experimental prereleases.

## Core behavior

- Extend or shorten exposures directly; time is kept as a duration, not pixels.
- Copy one pose or an **ordered sequence of poses**, preserving each exposure's
  exact hold duration. Repeated pastes reference shared frozen drawings until an
  individual pose is edited (copy-on-write); image assets are stored once.
- **HFM4** supports original vector strokes, editable text, compressed imported
  raster images and separate nonembedded audio-file timeline references. Opens
  older HFM1–HFM3 projects, and is portable between desktop and Android.
- Up to one million exposure references per project (a safety bound, not one
  million image files). Real limits depend on device memory.
- Fast indexed frame lookup for playback through projects with many exposures.
- GZIP-compressed, validated, platform-independent `.holdframe` files.
- Range copying preserves order and durations. Importing a sequence into a second
  project includes its image assets; referenced audio is not duplicated.

## Test with OpenJDK 17+

```bash
mkdir -p out/core
javac --release 17 -d out/core core/src/main/java/dev/rectifydata/holdframe/core/*.java core/src/test/java/dev/rectifydata/holdframe/core/*.java
java -cp out/core dev.rectifydata.holdframe.core.CoreTest
java -cp out/core dev.rectifydata.holdframe.core.MediaCoreTest
java -cp out/core dev.rectifydata.holdframe.core.SequenceClipboardTest
```

Build and UI instructions are in the respective platform branches. Shared core
changes should be made on `main` first and then brought into both app branches.


## v0.11 — precise split and selection, desktop shortcuts

**Split at Playhead:** On Android, use Animate → More → Split at playhead or
Ctrl+B with a hardware keyboard. On Windows/Linux, use Animate → Split or
Ctrl+B (remappable). A split retains the same total duration and the same
absolute audio timing; the two halves use copy-on-write drawings so changing
one doesn't modify the other.

**Lasso scope (desktop):** strokes that pass through the lasso boundary are
split at the vector polygon intersections. Dragging/resizing/deleting the
selected part leaves the outside pieces alone. Edit → Delete selected objects
and Edit → Edit selected object support scoped edits, with undo/redo. Android
supports editing/removing its selected text objects through Media → More.

**Launch and shortcuts:** The Java 17+ JAR is directly executable using
\`java -jar HoldFrame-Desktop.jar\`. Under Settings → Create application
shortcuts, users choose Desktop or Start Menu/Applications. The optional
Windows NSIS installer source includes a visible wizard, selectable
shortcuts, and optional launch on completion. No silent installation.

## Update, selection, and brush preferences

- Android: **File → More → Check for updates** now downloads a compatible HoldFrame APK from GitHub Releases inside the app, displays progress and asks before handing installation to Android's visible Package Installer. Android still enforces matching signing keys.
- Windows/Linux: press **Esc** to clear canvas/timeline/audio selection. Use **KMBS (Keep My Brush Size)** beside the brush slider to retain its last size across sessions; disable KMBS to start with the default brush size next time.
- Android: press **Esc** on a hardware keyboard to deselect text and timeline ranges. **File → More → Keep My Brush Size (KMBS)** controls whether the selected brush width is restored on restart.

Updates never use silent installation; projects should be backed up before replacing debug-signed builds.

### Installer and compilation pipeline

A push to `main` runs Android SDK 35 compilation and Android 12 emulator checks alongside the Java 17 desktop build. Once both pass, the two verified build outputs are published as one GitHub prerelease. Windows-specific CI also creates a visible NSIS installer and publishes that setup alongside its executable JAR as a separate prerelease. The Android updater asks before downloading and hands off installation to the system Package Installer; Android may require explicit approval of HoldFrame as an install source. Debug-signing changes may prevent installation over earlier APKs.
