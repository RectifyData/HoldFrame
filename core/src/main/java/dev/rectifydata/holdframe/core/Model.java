package dev.rectifydata.holdframe.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Platform-neutral 2D animation: one source drawing, arbitrary exposure duration. */
public final class Model {
    private Model() {}
    public static final int WIDTH = 800;
    public static final int HEIGHT = 450;
    public static final int DEFAULT_FPS = 24;
    public static final int MAX_DURATION = 240_000;
    // Exposures are tiny references, not bitmap frames. Bound only by practical memory/file size.
    public static final int MAX_EXPOSURES = 1_000_000;

    public static final class Point {
        public final float x, y;
        public Point(float x, float y) { this.x = x; this.y = y; }
    }
    public static final class Stroke {
        public int color;
        public float width;
        public boolean eraser;
        public final List<Point> points = new ArrayList<>();
        /** Optional normalized widths, one per point; empty on legacy strokes. */
        public final List<Float> pressures = new ArrayList<>();
        public Stroke(int color, float width, boolean eraser) {
            this.color = color; this.width = width; this.eraser = eraser;
        }
        public Stroke copy() {
            Stroke s = new Stroke(color, width, eraser);
            s.points.addAll(points); // immutable Point values
            s.pressures.addAll(pressures);
            return s;
        }
    }
    /** Editable text object. Fonts are referenced by family or a local user-selected file;
     * font binaries are never embedded in, or redistributed with, projects. */
    public static final class TextItem {
        public String value;
        public String fontFamily;
        public String fontPath;
        public int style;
        public int color;
        public float size, x, y, scaleX = 1f, scaleY = 1f;
        public TextItem(String value, String fontFamily, String fontPath, int style,
                        int color, float size, float x, float y) {
            this.value=value; this.fontFamily=fontFamily; this.fontPath=fontPath;
            this.style=style; this.color=color; this.size=size; this.x=x; this.y=y;
        }
        public TextItem copy() {
            TextItem item=new TextItem(value,fontFamily,fontPath,style,color,size,x,y);
            item.scaleX=scaleX; item.scaleY=scaleY;
            return item;
        }
        public void assign(TextItem item) {
            value=item.value; fontFamily=item.fontFamily; fontPath=item.fontPath;
            style=item.style; color=item.color; size=item.size;
            x=item.x; y=item.y; scaleX=item.scaleX; scaleY=item.scaleY;
        }
    }
    /** Raster asset instance; each imported asset is stored once in the portable project. */
    public static final class ImageItem {
        public String assetId;
        public float x, y, width, height;
        public ImageItem(String assetId, float x, float y, float width, float height) {
            this.assetId=assetId; this.x=x; this.y=y; this.width=width; this.height=height;
        }
        public ImageItem copy() { return new ImageItem(assetId,x,y,width,height); }
        public void assign(ImageItem value) {
            assetId=value.assetId; x=value.x; y=value.y; width=value.width; height=value.height;
        }
    }
    public static final class Drawing {
        public final String id;
        public final List<Stroke> strokes = new ArrayList<>();
        public final List<TextItem> texts = new ArrayList<>();
        public final List<ImageItem> images = new ArrayList<>();
        public Drawing(String id) { this.id = id; }
        public Drawing copy(String newId) {
            Drawing d = new Drawing(newId);
            for (Stroke s : strokes) d.strokes.add(s.copy());
            for (TextItem t : texts) d.texts.add(t.copy());
            for (ImageItem image : images) d.images.add(image.copy());
            return d;
        }
    }
    public static final class Exposure {
        public String drawingId;
        public int duration;
        public Exposure(String drawingId, int duration) {
            this.drawingId = drawingId; this.duration = duration;
        }
    }
    /** Timeline audio is a lightweight reference to an existing file, never embedded raw audio. */
    public static final class AudioClip {
        public final String id;
        public String sourcePath;
        public int startFrame, durationFrames;
        public long sourceOffsetMillis;
        public float volume = 1f;
        public boolean muted;
        public AudioClip(String id, String sourcePath, int startFrame, int durationFrames) {
            this.id = id; this.sourcePath = sourcePath;
            this.startFrame = startFrame; this.durationFrames = durationFrames;
        }
        public int endFrame() { return (int)Math.min(Integer.MAX_VALUE,(long)startFrame+durationFrames); }
    }
    /** Timeline clipboard captures an ordered, editable sequence of exposure clips.
     * A copied drawing is snapshotted once even if it appears in multiple clips.
     * Pasting multiple times only adds references; editing one pose detaches it.
     */
    public static final class ClipboardSequence {
        private static final class Entry {
            final String sourceId;
            final int duration;
            Entry(String sourceId,int duration) { this.sourceId=sourceId; this.duration=duration; }
        }
        private final List<Entry> entries = new ArrayList<>();
        private final Map<String,Drawing> originals = new LinkedHashMap<>();
        private final Map<String,byte[]> assets = new LinkedHashMap<>();
        private Project cachedProject;
        private final Map<String,String> cachedIds = new LinkedHashMap<>();
        public int size() { return entries.size(); }
        public long duration() {
            long total=0;
            for (Entry e:entries) total+=e.duration;
            return total;
        }
    }
    /** Single drawing clipboard kept for compatibility with existing callers. */
    public static final class ClipboardFrame {
        private final ClipboardSequence sequence;
        public final int duration;
        private ClipboardFrame(ClipboardSequence sequence) {
            this.sequence=sequence;
            this.duration=sequence.entries.get(0).duration;
        }
    }
    public static final class Project {
        public int fps = DEFAULT_FPS;
        public final Map<String, Drawing> drawings = new LinkedHashMap<>();
        public final List<Exposure> timeline = new ArrayList<>();
        public final List<AudioClip> audioClips = new ArrayList<>();
        /** Portable PNG/JPEG asset bytes, keyed by SHA-256, deduplicated across poses. */
        public final Map<String,byte[]> imageAssets = new LinkedHashMap<>();
        public Project() { this(true); }
        Project(boolean withDrawing) {
            if (withDrawing) {
                String id = UUID.randomUUID().toString();
                drawings.put(id, new Drawing(id));
                timeline.add(new Exposure(id, fps));
            }
        }
        private long[] prefix;
        private void invalidateIndex() { prefix = null; }
        private void index() {
            if (prefix != null && prefix.length == timeline.size() + 1) return;
            prefix = new long[timeline.size() + 1];
            for (int i = 0; i < timeline.size(); i++) {
                prefix[i+1] = prefix[i] + timeline.get(i).duration;
            }
        }
        public int totalFrames() {
            index();
            return (int)Math.min(Integer.MAX_VALUE,prefix[prefix.length-1]);
        }
        public int clipStart(int clipIndex) {
            if (clipIndex < 0 || clipIndex > timeline.size()) throw new IndexOutOfBoundsException();
            index();
            return (int)Math.min(Integer.MAX_VALUE,prefix[clipIndex]);
        }
        public int indexAt(int frame) {
            if (timeline.isEmpty()) throw new IllegalStateException("No clips");
            index();
            long target = Math.max(0,frame);
            int found = Arrays.binarySearch(prefix,target);
            if (found >= 0) return Math.min(found,timeline.size()-1);
            return Math.min(timeline.size()-1,Math.max(0,-found-2));
        }
        public Drawing drawingAt(int clip) {
            Drawing d = drawings.get(timeline.get(clip).drawingId);
            if (d == null) throw new IllegalStateException("Missing drawing");
            return d;
        }
        /** Adds an independent drawing or an editable copy after the selected clip. */
        public int addAfter(int index, boolean duplicate) {
            if (timeline.size() >= MAX_EXPOSURES) throw new IllegalStateException("Exposure count limit reached");
            Drawing previous = drawingAt(index);
            String id = UUID.randomUUID().toString();
            drawings.put(id, duplicate ? previous.copy(id) : new Drawing(id));
            timeline.add(index + 1, new Exposure(id, fps));
            invalidateIndex();
            return index + 1;
        }
        /** Capture every clip in the selected inclusive range in timeline order. */
        public ClipboardSequence copyRange(int first,int last) {
            if (first<0 || last<first || last>=timeline.size())
                throw new IndexOutOfBoundsException("Invalid pose range");
            ClipboardSequence copied=new ClipboardSequence();
            for (int i=first;i<=last;i++) {
                Exposure exposure=timeline.get(i);
                copied.entries.add(new ClipboardSequence.Entry(exposure.drawingId,exposure.duration));
                if (!copied.originals.containsKey(exposure.drawingId)) {
                    Drawing pose=drawingAt(i).copy(exposure.drawingId);
                    copied.originals.put(exposure.drawingId,pose);
                    for (ImageItem image:pose.images) {
                        byte[] data=imageAssets.get(image.assetId);
                        if (data==null) throw new IllegalStateException("Missing image asset");
                        copied.assets.putIfAbsent(image.assetId,data.clone());
                    }
                }
            }
            return copied;
        }
        public ClipboardFrame copyFrame(int index) {
            return new ClipboardFrame(copyRange(index,index));
        }
        public int pasteAfter(int index,ClipboardFrame clipboard) {
            if (clipboard==null) throw new IllegalArgumentException("Nothing copied");
            return pasteSequenceAfter(index,clipboard.sequence);
        }
        /** Inserts the entire copied sequence in one operation; returns its last clip index.
         * Pasting the same sequence repeatedly shares drawing snapshots and image bytes.
         * Audio is a separate timeline track and is deliberately not copied.
         */
        public int pasteSequenceAfter(int index,ClipboardSequence clipboard) {
            if (clipboard==null || clipboard.entries.isEmpty())
                throw new IllegalArgumentException("Nothing copied");
            if (index<0 || index>=timeline.size()) throw new IndexOutOfBoundsException();
            if (clipboard.entries.size()>(long)MAX_EXPOSURES-timeline.size())
                throw new IllegalStateException("Exposure count limit reached");
            int missing=0;
            long bytes=0;
            for (byte[] data:imageAssets.values()) bytes+=data.length;
            for (Map.Entry<String,byte[]> a:clipboard.assets.entrySet()) {
                byte[] existing=imageAssets.get(a.getKey());
                if (existing!=null && !Arrays.equals(existing,a.getValue()))
                    throw new IllegalArgumentException("Conflicting image asset ID");
                if (existing==null) { missing++; bytes+=a.getValue().length; }
            }
            if (missing+imageAssets.size()>256 || bytes>128_000_000L)
                throw new IllegalStateException("Image asset limit reached");
            if (clipboard.cachedProject!=this) {
                clipboard.cachedProject=this;
                clipboard.cachedIds.clear();
            }
            int newDrawings=0;
            for (String originalId:clipboard.originals.keySet()) {
                String cached=clipboard.cachedIds.get(originalId);
                if (cached==null || !drawings.containsKey(cached)) newDrawings++;
            }
            if (newDrawings>(long)MAX_EXPOSURES-drawings.size())
                throw new IllegalStateException("Drawing count limit reached");
            for (Map.Entry<String,byte[]> a:clipboard.assets.entrySet())
                imageAssets.putIfAbsent(a.getKey(),a.getValue().clone());
            for (Map.Entry<String,Drawing> entry:clipboard.originals.entrySet()) {
                String id=clipboard.cachedIds.get(entry.getKey());
                if (id!=null && drawings.containsKey(id)) continue;
                id=UUID.randomUUID().toString();
                drawings.put(id,entry.getValue().copy(id));
                clipboard.cachedIds.put(entry.getKey(),id);
            }
            List<Exposure> inserted=new ArrayList<>(clipboard.entries.size());
            for (ClipboardSequence.Entry e:clipboard.entries)
                inserted.add(new Exposure(clipboard.cachedIds.get(e.sourceId),e.duration));
            timeline.addAll(index+1,inserted);
            invalidateIndex();
            return index+inserted.size();
        }
        /**
         * Split the held exposure at a global, zero-based frame boundary.
         * The second half shares the source drawing until edited (copy-on-write).
         * Audio is tied to absolute time and remains unchanged.
         *
         * @return index of the new second exposure
         * @throws IllegalArgumentException if the split is at a clip boundary
         */
        public int splitAt(int globalFrame) {
            if (globalFrame<=0 || globalFrame>=totalFrames())
                throw new IllegalArgumentException("Place the playhead inside a pose to split.");
            if (timeline.size()>=MAX_EXPOSURES)
                throw new IllegalStateException("Maximum number of poses reached.");
            int at=indexAt(globalFrame);
            int clipStart=clipStart(at);
            Exposure original=timeline.get(at);
            int left=globalFrame-clipStart;
            if(left<=0 || left>=original.duration)
                throw new IllegalArgumentException("Place the playhead inside a pose to split.");
            int right=original.duration-left;
            original.duration=left;
            timeline.add(at+1,new Exposure(original.drawingId,right));
            invalidateIndex();
            return at+1;
        }

        /** Copy-on-write: editing a pasted pose never mutates any other pasted pose. */
        public Drawing ensureEditable(int clipIndex) {
            Exposure clip = timeline.get(clipIndex);
            String id = clip.drawingId;
            for (int i = 0; i < timeline.size(); i++) {
                if (i != clipIndex && id.equals(timeline.get(i).drawingId)) {
                    String newId = UUID.randomUUID().toString();
                    Drawing detached = drawingAt(clipIndex).copy(newId);
                    drawings.put(newId, detached);
                    clip.drawingId = newId;
                    return detached;
                }
            }
            return drawingAt(clipIndex);
        }
        /**
         * Split an existing audio track clip without copying any audio bytes.
         * The second piece starts at the same source position reached by the
         * first piece, preserving playback pitch, timing, volume and mute state.
         */
        public AudioClip splitAudioClip(String clipId,int globalFrame) {
            if(audioClips.size()>=128)throw new IllegalStateException("Maximum audio clips reached");
            for(int i=0;i<audioClips.size();i++) {
                AudioClip old=audioClips.get(i);
                if(!old.id.equals(clipId))continue;
                int before=globalFrame-old.startFrame;
                if(before<=0 || before>=old.durationFrames)
                    throw new IllegalArgumentException("Place the playhead inside the audio clip to split.");
                int trailing=old.durationFrames-before;
                long offset=old.sourceOffsetMillis+Math.round(before*1000d/fps);
                if(offset>86_400_000L)throw new IllegalArgumentException("Audio source offset out of range.");
                AudioClip next=new AudioClip(UUID.randomUUID().toString(),old.sourcePath,globalFrame,trailing);
                next.sourceOffsetMillis=offset;
                next.volume=old.volume;
                next.muted=old.muted;
                old.durationFrames=before;
                audioClips.add(i+1,next);
                return next;
            }
            throw new IllegalArgumentException("Audio clip not found.");
        }
        public AudioClip addAudio(String source, int startFrame, int durationFrames) {
            if (audioClips.size() >= 128) throw new IllegalStateException("Maximum audio track clips reached");
            AudioClip clip=new AudioClip(UUID.randomUUID().toString(),source,startFrame,durationFrames);
            audioClips.add(clip);
            extendToCover(clip.endFrame());
            return clip;
        }
        public void extendToCover(int frameCount) {
            if (frameCount <= totalFrames()) return;
            int last=timeline.size()-1;
            long needed=(long)timeline.get(last).duration+frameCount-totalFrames();
            if (needed > MAX_DURATION) throw new IllegalArgumentException("Animation duration limit reached");
            extend(last,(int)needed);
        }
        public void extend(int index, int duration) {
            timeline.get(index).duration = Math.max(1, Math.min(MAX_DURATION, duration));
            invalidateIndex();
        }
        public void remove(int index) {
            if (timeline.size() == 1) throw new IllegalStateException("One clip required");
            String id = timeline.remove(index).drawingId;
            invalidateIndex();
            boolean stillUsed = false;
            for (Exposure e : timeline) {
                if (id.equals(e.drawingId)) { stillUsed = true; break; }
            }
            if (!stillUsed) drawings.remove(id);
        }
        public boolean undo(int index) {
            List<Stroke> strokes = drawingAt(index).strokes;
            if (strokes.isEmpty()) return false;
            strokes.remove(strokes.size() - 1);
            return true;
        }
        public void validate() {
            if (fps < 1 || fps > 120 || timeline.size() < 1 || timeline.size() > MAX_EXPOSURES
                    || drawings.size() < 1 || drawings.size() > MAX_EXPOSURES)
                throw new IllegalArgumentException("Invalid project dimensions");
            if (imageAssets.size() > 256) throw new IllegalArgumentException("Too many image assets");
            long imageBytes = 0;
            for (Map.Entry<String,byte[]> asset : imageAssets.entrySet()) {
                if (asset.getKey() == null || !asset.getKey().matches("[a-f0-9]{64}") ||
                    asset.getValue() == null || asset.getValue().length < 8 ||
                    asset.getValue().length > 16_000_000)
                    throw new IllegalArgumentException("Invalid image asset");
                imageBytes += asset.getValue().length;
                if (imageBytes > 128_000_000) throw new IllegalArgumentException("Image assets too large");
            }
            if (audioClips.size() > 128) throw new IllegalArgumentException("Too many audio clips");
            for (AudioClip a : audioClips) {
                if (a.id == null || a.id.isBlank() || a.id.length() > 120 ||
                        a.sourcePath == null || a.sourcePath.isBlank() || a.sourcePath.length() > 8192 ||
                        a.startFrame < 0 || a.durationFrames <= 0 ||
                        a.sourceOffsetMillis < 0 || a.sourceOffsetMillis > 86_400_000L ||
                        a.endFrame() > Integer.MAX_VALUE - 1 ||
                        !Float.isFinite(a.volume) || a.volume < 0 || a.volume > 1)
                    throw new IllegalArgumentException("Invalid audio clip");
            }
            long points = 0;
            for (Map.Entry<String, Drawing> item : drawings.entrySet()) {
                String id = item.getKey();
                Drawing d = item.getValue();
                if (id == null || !id.equals(d.id) || id.length() == 0 || id.length() > 100)
                    throw new IllegalArgumentException("Invalid drawing ID");
                if (d.images.size() > 10_000) throw new IllegalArgumentException("Too many images in pose");
                for (ImageItem image : d.images) {
                    if (image.assetId == null || !imageAssets.containsKey(image.assetId) ||
                        !Float.isFinite(image.x) || !Float.isFinite(image.y) ||
                        !Float.isFinite(image.width) || !Float.isFinite(image.height) ||
                        Math.abs(image.x) > 100_000 || Math.abs(image.y) > 100_000 ||
                        image.width < 0.01f || image.height < 0.01f ||
                        image.width > 100_000 || image.height > 100_000)
                        throw new IllegalArgumentException("Invalid image placement");
                }
                if (d.texts.size() > 20_000) throw new IllegalArgumentException("Too many text elements");
                for (TextItem t : d.texts) {
                    if (t.value == null || t.value.length() > 10000 || t.fontFamily == null
                            || t.fontFamily.length() > 256 || t.fontPath == null
                            || t.fontPath.length() > 4096 || t.style < 0 || t.style > 3
                            || !(t.size >= 1f && t.size <= 4096f) || !Float.isFinite(t.size)
                            || !Float.isFinite(t.x) || !Float.isFinite(t.y)
                            || Math.abs(t.x) > 100000 || Math.abs(t.y) > 100000
                            || !Float.isFinite(t.scaleX) || !Float.isFinite(t.scaleY)
                            || t.scaleX < .01f || t.scaleX > 100f
                            || t.scaleY < .01f || t.scaleY > 100f)
                        throw new IllegalArgumentException("Invalid text element");
                }
                if (d.strokes.size() > 100_000) throw new IllegalArgumentException("Too many strokes");
                for (Stroke s : d.strokes) {
                    if (!(s.width > 0 && s.width <= 200) || !Float.isFinite(s.width)
                            || s.points.size() < 1 || s.points.size() > 100_000)
                        throw new IllegalArgumentException("Invalid stroke");
                    if(!s.pressures.isEmpty() && s.pressures.size()!=s.points.size())
                        throw new IllegalArgumentException("Stroke pressure count mismatch");
                    for(float pressure:s.pressures) if(!Float.isFinite(pressure)||pressure<0.1f||pressure>2f)
                        throw new IllegalArgumentException("Invalid stroke pressure");
                    points += s.points.size();
                    if (points > 500_000) throw new IllegalArgumentException("Too many points");
                    for (Point p : s.points) {
                        if (!Float.isFinite(p.x) || !Float.isFinite(p.y)
                                || Math.abs(p.x) > 100_000 || Math.abs(p.y) > 100_000)
                            throw new IllegalArgumentException("Invalid point");
                    }
                }
            }
            for (Exposure e : timeline) {
                if (e.duration < 1 || e.duration > MAX_DURATION || !drawings.containsKey(e.drawingId))
                    throw new IllegalArgumentException("Broken exposure");
            }
        }
    }
}
