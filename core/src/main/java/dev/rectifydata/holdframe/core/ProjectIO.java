package dev.rectifydata.holdframe.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Cross-platform .holdframe: versioned and compressed vector drawings + exposures. */
public final class ProjectIO {
    private static final int MAGIC_V1 = 0x48464D31; // HFM1 (legacy stroke-only)
    private static final int MAGIC_V2 = 0x48464D32; // HFM2 (editable text)
    private static final int MAGIC_V3 = 0x48464D33; // HFM3 (audio references)
    private static final int MAGIC_V4 = 0x48464D34; // HFM4 (image assets)
    private ProjectIO() {}
    /** The supplied output stream remains open. */
    public static void write(Model.Project project, OutputStream stream) throws IOException {
        write(project, stream, null);
    }
    public static void write(Model.Project project, OutputStream stream, Path projectDirectory) throws IOException {
        project.validate();
        GZIPOutputStream gzip = new GZIPOutputStream(new BufferedOutputStream(stream));
        DataOutputStream out = new DataOutputStream(gzip);
        out.writeInt(MAGIC_V4);
        out.writeInt(project.fps);
        out.writeInt(project.drawings.size());
        for (Model.Drawing drawing : project.drawings.values()) {
            out.writeUTF(drawing.id);
            out.writeInt(drawing.strokes.size());
            for (Model.Stroke stroke : drawing.strokes) {
                out.writeInt(stroke.color);
                out.writeFloat(stroke.width);
                out.writeBoolean(stroke.eraser);
                out.writeInt(stroke.points.size());
                for (Model.Point point : stroke.points) {
                    out.writeFloat(point.x);
                    out.writeFloat(point.y);
                }
            }
            out.writeInt(drawing.texts.size());
            for (Model.TextItem item : drawing.texts) {
                out.writeUTF(item.value);
                out.writeUTF(item.fontFamily);
                out.writeUTF(item.fontPath);
                out.writeInt(item.style);
                out.writeInt(item.color);
                out.writeFloat(item.size);
                out.writeFloat(item.x);
                out.writeFloat(item.y);
                out.writeFloat(item.scaleX);
                out.writeFloat(item.scaleY);
            }
            out.writeInt(drawing.images.size());
            for (Model.ImageItem image : drawing.images) {
                out.writeUTF(image.assetId);
                out.writeFloat(image.x); out.writeFloat(image.y);
                out.writeFloat(image.width); out.writeFloat(image.height);
            }
        }
        out.writeInt(project.timeline.size());
        for (Model.Exposure clip : project.timeline) {
            out.writeUTF(clip.drawingId);
            out.writeInt(clip.duration);
        }
        out.writeInt(project.audioClips.size());
        for (Model.AudioClip clip : project.audioClips) {
            out.writeUTF(clip.id);
            out.writeUTF(relativePath(clip.sourcePath, projectDirectory));
            out.writeInt(clip.startFrame);
            out.writeInt(clip.durationFrames);
            out.writeLong(clip.sourceOffsetMillis);
            out.writeFloat(clip.volume);
            out.writeBoolean(clip.muted);
        }
        out.writeInt(project.imageAssets.size());
        for (java.util.Map.Entry<String,byte[]> asset : project.imageAssets.entrySet()) {
            out.writeUTF(asset.getKey());
            out.writeInt(asset.getValue().length);
            out.write(asset.getValue());
        }
        out.flush();
        gzip.finish();
        gzip.flush();
    }
    /** The supplied input stream remains open. Rejects malformed or oversized projects. */
    public static Model.Project read(InputStream stream) throws IOException {
        return read(stream, null);
    }
    public static Model.Project read(InputStream stream, Path projectDirectory) throws IOException {
        DataInputStream in = new DataInputStream(new GZIPInputStream(new BufferedInputStream(stream)));
        int magic = in.readInt();
        if (magic != MAGIC_V1 && magic != MAGIC_V2 && magic != MAGIC_V3 && magic != MAGIC_V4)
            throw new IOException("Not a supported HoldFrame project");
        Model.Project result = new Model.Project(false);
        result.fps = in.readInt();
        int drawings = count(in.readInt(), 1, Model.MAX_EXPOSURES, "drawings");
        long totalPoints = 0;
        for (int i = 0; i < drawings; i++) {
            String id = in.readUTF();
            if (result.drawings.containsKey(id)) throw new IOException("Duplicate drawing");
            Model.Drawing d = new Model.Drawing(id);
            int strokes = count(in.readInt(), 0, 100_000, "strokes");
            for (int j = 0; j < strokes; j++) {
                Model.Stroke s = new Model.Stroke(in.readInt(), in.readFloat(), in.readBoolean());
                int points = count(in.readInt(), 1, 100_000, "points");
                totalPoints += points;
                if (totalPoints > 500_000) throw new IOException("Project point limit exceeded");
                for (int k = 0; k < points; k++)
                    s.points.add(new Model.Point(in.readFloat(), in.readFloat()));
                d.strokes.add(s);
            }
            if (magic == MAGIC_V2 || magic == MAGIC_V3 || magic == MAGIC_V4) {
                int textCount = count(in.readInt(), 0, 20_000, "text elements");
                for (int j = 0; j < textCount; j++) {
                    Model.TextItem t=new Model.TextItem(in.readUTF(),in.readUTF(),in.readUTF(),
                        in.readInt(),in.readInt(),in.readFloat(),in.readFloat(),in.readFloat());
                    t.scaleX=in.readFloat(); t.scaleY=in.readFloat();
                    d.texts.add(t);
                }
            }
            if (magic == MAGIC_V4) {
                int imageCount=count(in.readInt(),0,10_000,"image placements");
                for (int j=0;j<imageCount;j++)
                    d.images.add(new Model.ImageItem(in.readUTF(),in.readFloat(),in.readFloat(),
                        in.readFloat(),in.readFloat()));
            }
            result.drawings.put(id, d);
        }
        int clips = count(in.readInt(), 1, Model.MAX_EXPOSURES, "exposures");
        for (int i = 0; i < clips; i++)
            result.timeline.add(new Model.Exposure(in.readUTF(), in.readInt()));
        if (magic == MAGIC_V3 || magic == MAGIC_V4) {
            int count = count(in.readInt(), 0, 128, "audio clips");
            for (int i = 0; i < count; i++) {
                String id=in.readUTF();
                String path=resolvePath(in.readUTF(),projectDirectory);
                Model.AudioClip clip=new Model.AudioClip(id,path,in.readInt(),in.readInt());
                clip.sourceOffsetMillis=in.readLong();
                clip.volume=in.readFloat();
                clip.muted=in.readBoolean();
                result.audioClips.add(clip);
            }
        }
        if (magic == MAGIC_V4) {
            int assets=count(in.readInt(),0,256,"image assets");
            long total=0;
            for (int i=0;i<assets;i++) {
                String id=in.readUTF();
                int length=count(in.readInt(),8,16_000_000,"image asset bytes");
                total+=length;
                if (total>128_000_000 || result.imageAssets.containsKey(id))
                    throw new IOException("Invalid or duplicate image asset");
                byte[] data=new byte[length];
                in.readFully(data);
                result.imageAssets.put(id,data);
            }
        }
        try { result.validate(); }
        catch (RuntimeException e) { throw new IOException("Invalid project: " + e.getMessage(), e); }
        if (in.read() != -1) throw new IOException("Unexpected trailing project data");
        return result;
    }
    private static String relativePath(String input,Path base) {
        if (base == null) return input;
        try {
            Path path=Path.of(input);
            if (!path.isAbsolute()) return input;
            Path relative=base.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
            return relative.toString().length() <= 8192 ? relative.toString() : input;
        } catch (RuntimeException ex) { return input; }
    }
    private static String resolvePath(String input,Path base) throws IOException {
        if (base == null) return input;
        try {
            Path path=Path.of(input);
            return (path.isAbsolute() ? path : base.resolve(path)).toAbsolutePath().normalize().toString();
        } catch (InvalidPathException ex) { throw new IOException("Invalid audio source path",ex); }
    }
    private static int count(int value, int min, int max, String kind) throws IOException {
        if (value < min || value > max) throw new IOException("Invalid " + kind + " count");
        return value;
    }
}
