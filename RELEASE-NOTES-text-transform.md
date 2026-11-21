# HoldFrame — Text transform parity (Android + Java desktop)

Features:
- Desktop: Text tool click-to-select, drag to move, four highlighted handles to resize independently on both axes. Double-click text to edit it. New text starts selected. Existing Lasso multi-object transforms remain.
- Android: Draw / Media / floating Tools → Move / resize text. Tap a text element, drag to move or drag one of four corner handles to resize. An optional hardware keyboard Ctrl+T switches transform mode.
- Both use the same editable HFM4 text representation. Existing frames, fonts, colors, undo/redo behavior and exposure-timeline project data are retained.
- Android text previews update without redrawing the entire low RAM bitmap on each drag frame.

Build and release:
- Android 12 minimum (API 31); test APK signing, ZIP structure, manifest, alignment and Android 12 emulator installation/launch.
- Java 17+ cross-platform runnable JAR for Windows and Linux.
- One GitHub release with exactly **two** individually downloadable assets: Android APK and executable Java JAR. No nested ZIP and no native installers.
- Regression coverage: text resize from all four corners, anchored scale, save/reopen, undo/redo, sequence copy-on-write, desktop direct manipulation.

Development status: automated tests gate the prerelease. Real Android phone touchscreen validation remains outstanding.
