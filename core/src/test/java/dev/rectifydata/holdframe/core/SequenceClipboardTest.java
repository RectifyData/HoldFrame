package dev.rectifydata.holdframe.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Multiple-clip clipboard: range order, durations, snapshot, COW, asset reuse. */
public final class SequenceClipboardTest {
    private static void check(boolean ok,String why) { if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        Model.Project p=new Model.Project();
        String id="01".repeat(32);
        byte[] png=new byte[]{1,2,3,4,5,6,7,8,9};
        p.imageAssets.put(id,png);
        p.drawingAt(0).images.add(new Model.ImageItem(id,10,20,60,40));
        Model.Stroke stroke=new Model.Stroke(0xff332211,3,false);
        stroke.points.add(new Model.Point(3,5));
        p.drawingAt(0).strokes.add(stroke);
        p.extend(0,120);
        int second=p.addAfter(0,false);
        p.extend(second,48);
        p.drawingAt(second).texts.add(new Model.TextItem("Hi","Dialog","",0,0xff000000,20,50,60));
        int third=p.addAfter(second,true);
        p.extend(third,6);
        check(p.totalFrames()==174,"initial durations");
        Model.ClipboardSequence copy=p.copyRange(0,2);
        check(copy.size()==3 && copy.duration()==174,"range copy keeps durations");
        p.ensureEditable(0).images.get(0).x=444; // mutate source after copying
        p.ensureEditable(second).texts.get(0).value="Changed";
        int last=p.pasteSequenceAfter(2,copy);
        check(last==5,"group pasted in correct order");
        check(p.timeline.get(3).duration==120 && p.timeline.get(4).duration==48 &&
              p.timeline.get(5).duration==6,"durations unchanged");
        check(p.drawingAt(3).images.get(0).x==10,"clipboard snapshot frozen");
        check("Hi".equals(p.drawingAt(4).texts.get(0).value),"text snapshot frozen");
        check(p.drawingAt(3).images.size()==1 && p.imageAssets.size()==1,"image bytes deduplicated");
        int next=p.pasteSequenceAfter(last,copy);
        check(next==8 && p.timeline.size()==9,"repeated full-sequence paste");
        check(p.timeline.get(3).drawingId.equals(p.timeline.get(6).drawingId),"repeat paste shares source drawing");
        int count=p.drawings.size();
        p.ensureEditable(6).images.get(0).y=999;
        check(p.drawings.size()==count+1,"copy-on-write detaches only edited pose");
        check(p.drawingAt(3).images.get(0).y==20,"edit does not bleed to earlier paste");
        ByteArrayOutputStream data=new ByteArrayOutputStream();
        ProjectIO.write(p,data);
        Model.Project loaded=ProjectIO.read(new ByteArrayInputStream(data.toByteArray()));
        check(loaded.timeline.size()==9 && loaded.totalFrames()==522,"sequence roundtrip");
        check(loaded.imageAssets.size()==1 && Arrays.equals(loaded.imageAssets.get(id),png),
              "image payload saved only once");
        // Pasting into another project after opening is still safe and preserves image assets.
        Model.Project other=new Model.Project();
        int secondEnd=other.pasteSequenceAfter(0,copy);
        check(secondEnd==3 && other.imageAssets.containsKey(id),"cross-project paste copies assets");
        check(other.drawingAt(1).images.size()==1,"cross-project pose imported");
        other.ensureEditable(1).images.get(0).x=999;
        check(p.drawingAt(3).images.get(0).x==10,"cross-project edits independent");
        boolean failed=false;
        try { p.pasteSequenceAfter(-1,copy); } catch (IndexOutOfBoundsException ex) { failed=true; }
        check(failed,"bad insertion rejected");
        check(p.copyFrame(1).duration==48,"single copy API unchanged");
        System.out.println("SequenceClipboardTest passed");
    }
}
