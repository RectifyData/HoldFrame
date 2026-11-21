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
