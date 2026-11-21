package dev.rectifydata.holdframe.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.DataOutputStream;
import java.util.zip.GZIPOutputStream;

public final class CoreTest {
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Model.Project p = new Model.Project();
        check(p.totalFrames() == 24, "initial exposure");
        p.extend(0, 5000);
        check(p.drawings.size() == 1 && p.totalFrames() == 5000, "hold without copies");
        Model.Stroke stroke = new Model.Stroke(0xFF123456, 6, false);
        stroke.points.add(new Model.Point(2, 4));
        stroke.points.add(new Model.Point(20, 40));
        p.drawingAt(0).strokes.add(stroke);
        int other = p.addAfter(0, true);
        check(p.drawings.size() == 2 && p.drawingAt(other).strokes.size() == 1, "duplicate pose");
        p.drawingAt(other).strokes.get(0).points.clear();
        check(p.drawingAt(0).strokes.get(0).points.size() == 2, "copy independent");
        check(p.undo(other), "undo stroke");
        check(!p.undo(other), "no extra undo");
        p.extend(1, 36);
        check(p.indexAt(4999) == 0 && p.indexAt(5000) == 1, "exposure boundary");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ProjectIO.write(p, bytes);
        Model.Project read = ProjectIO.read(new ByteArrayInputStream(bytes.toByteArray()));
        check(read.totalFrames() == 5036 && read.drawingAt(0).strokes.size() == 1, "persistence");
        check(read.drawingAt(0).strokes.get(0).color == 0xFF123456, "saved color");
        read.remove(1);
        check(read.drawings.size() == 1, "orphan cleanup");
        boolean rejected = false;
        try { ProjectIO.read(new ByteArrayInputStream(new byte[] {1, 2, 3})); }
        catch (IOException e) { rejected = true; }
        check(rejected, "reject malformed data");
        p.extend(0, Integer.MAX_VALUE);
        check(p.timeline.get(0).duration == Model.MAX_DURATION, "clip max");
        p.extend(0, -20);
        check(p.timeline.get(0).duration == 1, "clip min");
        Model.Project many = new Model.Project();
        Model.Stroke one = new Model.Stroke(0xFF222222, 4, false);
        one.points.add(new Model.Point(10, 10));
        many.drawingAt(0).strokes.add(one);
        Model.ClipboardFrame clipboard = many.copyFrame(0);
        // Original edits after copying must not leak into the frozen clipboard.
        many.drawingAt(0).strokes.clear();
        for (int i = 0; i < 20_000; i++) many.pasteAfter(many.timeline.size() - 1, clipboard);
        check(many.timeline.size() == 20_001, "paste > old 10,000 exposure limit");
        check(many.drawings.size() == 2, "repeated clipboard exposures share source drawing");
        check(many.drawingAt(1).strokes.size() == 1, "clipboard is a snapshot");
        Model.Drawing isolated = many.ensureEditable(1);
        isolated.strokes.clear();
        check(many.drawingAt(2).strokes.size() == 1, "pasted poses independent when edited");
        check(many.drawings.size() == 3, "copy-on-write works");
        ByteArrayOutputStream manyBytes = new ByteArrayOutputStream();
        ProjectIO.write(many, manyBytes);
        Model.Project reloaded = ProjectIO.read(new ByteArrayInputStream(manyBytes.toByteArray()));
        check(reloaded.timeline.size() == 20_001 && reloaded.drawingAt(2).strokes.size() == 1,
            "many exposure save/open round-trip");
        check(manyBytes.size() < 10_000, "highly compressible exposure project stays small");
        reloaded.remove(1);
        check(reloaded.drawings.size() == 2, "orphaned edited pose removed");
        // Editable text is saved as text rather than rasterized frames.
        Model.TextItem title = new Model.TextItem("Hello, world!\nsecond line", "Dialog", "",
            1, 0xFF128AFE, 42, 45, 77);
        title.scaleX=1.5f;
        title.scaleY=.75f;
        many.drawingAt(0).texts.add(title);
        Model.ClipboardFrame textClipboard = many.copyFrame(0);
        int textClip = many.pasteAfter(0, textClipboard);
        Model.TextItem pasted = many.drawingAt(textClip).texts.get(0);
        check(pasted != title && pasted.value.equals(title.value), "editable text copied");
        many.ensureEditable(textClip).texts.get(0).value="Independent";
        check(many.drawingAt(0).texts.get(0).value.equals("Hello, world!\nsecond line"),
            "copy-on-write text isolation");
        ByteArrayOutputStream newBytes = new ByteArrayOutputStream();
        ProjectIO.write(many,newBytes);
        Model.Project textRoundTrip=ProjectIO.read(new ByteArrayInputStream(newBytes.toByteArray()));
        Model.TextItem restoredText=textRoundTrip.drawingAt(0).texts.get(0);
        check(restoredText.value.equals(title.value) && restoredText.fontFamily.equals(title.fontFamily)
            && restoredText.style==1 && restoredText.color==0xFF128AFE
            && restoredText.size==42f && restoredText.scaleX==1.5f && restoredText.scaleY==.75f,
            "text attributes round-trip");
        check(textRoundTrip.drawingAt(textClip).texts.get(0).value.equals("Independent"),
            "independent text edits persisted");
        // The legacy file format must still open, including existing .holdframe drawings.
        ByteArrayOutputStream legacy = new ByteArrayOutputStream();
        DataOutputStream v1 = new DataOutputStream(new GZIPOutputStream(legacy));
        v1.writeInt(0x48464D31); v1.writeInt(24); v1.writeInt(1);
        v1.writeUTF("original"); v1.writeInt(1);
        v1.writeInt(0xFF334455); v1.writeFloat(3f); v1.writeBoolean(false);
        v1.writeInt(1); v1.writeFloat(15f); v1.writeFloat(20f);
        v1.writeInt(1); v1.writeUTF("original"); v1.writeInt(50);
        v1.close();
        Model.Project legacyProject=ProjectIO.read(new ByteArrayInputStream(legacy.toByteArray()));
        check(legacyProject.totalFrames()==50 && legacyProject.drawingAt(0).texts.isEmpty(),
            "opens HFM1 stroke-only file");
        System.out.println("HoldFrame core tests passed (text, legacy projects, 20,000 reusable pastes)");
    }
}
