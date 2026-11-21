## v0.5.0 — Exports, without changing how projects store animation

- Added desktop **Export…** to the toolbar, File menu and Ctrl+E.
- Added MP4/H.264 video export with mixed, offset, trimmed and volume-adjusted audio tracks through FFmpeg. The app can locate a custom FFmpeg executable.
- Added looping GIF animation with proper held-pose durations, lossless PNG pose-sequence ZIP with exact CSV timing, PNG sprite sheets and still PNG export.
- Exports run in the background with a progress meter and cancel control; unfinished output files are removed on failure/cancellation.
- Added a headless cross-platform CLI through the same executable JAR.
- Fixed FFmpeg filter chain assembly for audio tracks and improved encoding failure reporting.
- Verified multi-track audio, animated GIF frame duration metadata, image assets, text, project save/open, export cancellation and the export GUI/shortcuts.

## v0.4.0 — Audio, images and pose-sequence clipboard

- Import raster stills (PNG/JPEG/BMP/GIF first frame) with move and four-corner scaling.
- Embed each compressed image asset once in HFM4 projects; existing HFM1–HFM3 projects still load.
- Add independent sound clips with timeline offset, movement, duration resizing, volume and mute.
- Play WAV/AIFF/AU through Java Sound; MP3/OGG can use installed FFmpeg/ffprobe.
- Shift-select adjacent poses, copy them as a group, and paste the full ordered sequence repeatedly.
- Preserve exact source pose durations and immutable clipboard snapshots; detach pasted drawings on edit.
- Retain automatic timeline edge-scrolling during Shift-drag range selection.
- Regression coverage for image transforms, waveform-independent audio timing, file serialization,
  keyboard shortcuts, timeline range gestures and repeated sequence paste.
- Audio is linked to original files rather than embedded; bring audio files when sharing projects.

# Changelog

## v0.2.2 — Timeline drag and native dialog repairs

- **Fixed timeline edge-follow:** resizing or scrubbing a pose near the viewport edges scrolls left or right automatically, including when the pointer is stationary. Previously, a view-size mismatch reset horizontal scroll to zero, forcing manual scrolling.
- **Fixed playhead follow:** immediately synchronize viewport dimensions after exposure changes, so Next/Previous frame and playback can reveal the active frame without fighting the scrollbar.
- **Mouse-wheel navigation:** wheel over the timeline pans horizontally; scrolling no longer needs grabbing the scrollbar.
- **Fixed overlapping GUI and file dialogs:** removed the drawing toolbar's layered overlay, made it a proper detachable Swing toolbar, and changed Open/Save As to native modal file pickers.
- **Fixed oversized brush slider** inside the dockable tool palette.
- **Fixed delayed scroll after repeated pastes:** the viewport follows added/pasted poses synchronously on Swing's event thread.
- Added real-timer tests for hands-free bidirectional dragging, UI layout, wheel panning, and a native file-picker smoke test.
- No changes to the shared project file format or Android app in this desktop bug-fix release.

# HoldFrame desktop changelog

## 0.2.1 — Timeline follow and GUI corrections

- Automatically pan the timeline left or right when resizing an exposure or scrubbing near the viewport edge, even with a stationary mouse. No need to grab the scrollbar.
- Keep the playhead in view during playback and Frame Previous/Next navigation; manual timeline drags are not overridden by automatic seeking.
- Maintain scrolling room beyond the last exposure and stop edge-scrolling immediately when dragging ends or the app loses focus.
- Prevent timeline vertical jumping by keeping the horizontal scrollbar visible, and eliminate unpainted grey patches on the canvas and timeline.
- Reduce drawing flicker while resizing exposures by preserving the cached artwork when the selected drawing hasn't changed.
- Sync the Lasso toolbar button with Ctrl+A selection, simplify the status label, and add automated bidirectional autoscroll/GUI regression tests.


## 0.2.0 — Onion skins and reusable poses

- Show neighboring drawings as red/blue onion-skin guides, with independent visibility switches.
- Use the lasso to move part of a drawing or scale it from a corner. Ctrl+A selects an entire pose.
- Copy a drawing with Ctrl+C and paste it as many times as needed with Ctrl+V. Identical pasted drawings share their stored source until edited, keeping project files small.
- Undo and redo now include lasso transformations as well as brush strokes.
- Fix the timeline bug that prevented dragging the final drawing beyond the existing animation end.
- Smooth out playback timing and speed up large timelines by indexing clip positions.
- Make the same Java JAR run on Windows and Linux, with a Linux app-image build and an interactive Windows installer workflow.
- Add automated tests for long exposures, 20,000 pastes, drawing tools, onion previews, lasso transforms and timeline resizing.

The desktop editor still uses a single editable vector drawing per pose. Audio tracks and video rendering are not part of this version.

## v0.3.0 — Editable type and four-corner transforms

- Added a proper Text tool: place and edit typed text, including multiline captions.
  Use installed system fonts or import a font file locally; adjust family, size,
  style and color without turning letters into painted pixels.
- Added four resize handles to selected strokes and text, one at every corner.
  Drag inside the bounds to move a selection. Undo/redo restores geometry and
  transformed stroke widths.
- Text survives copy/paste, onion skins and project saving. The compact HFM2 project
  format remains able to open old HFM1 animation files.
- Font binaries are not bundled, embedded in projects or automatically redistributed;
  a missing font uses the saved family name or a system fallback.
- Kept the Linux/Windows JAR portable without third-party runtime dependencies.
