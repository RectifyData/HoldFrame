package dev.rectifydata.holdframe.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Exact split timing, copy-on-write, audio continuity and save/reopen regressions. */
public final class SplitExposureTest {
    private static void eq(int actual,int expected,String message) {
        if(actual!=expected)throw new AssertionError(message+": expected "+expected+", got "+actual);
    }
    private static void check(boolean yes,String label) {if(!yes)throw new AssertionError(label);}
    public static void main(String[] args) throws Exception {
        Model.Project project=new Model.Project();
        Model.Drawing first=project.drawingAt(0);
        first.texts.add(new Model.TextItem("FIRST","SansSerif","",0,0xff000000,35f,20f,20f));
        project.addAudio("example.wav",0,24);
        eq(project.totalFrames(),24,"initial duration");
        int following=project.splitAt(7);
        eq(following,1,"new index");
        eq(project.timeline.size(),2,"clip count");
        eq(project.timeline.get(0).duration,7,"first duration");
        eq(project.timeline.get(1).duration,17,"second duration");
        eq(project.clipStart(1),7,"second starts at split");
        eq(project.totalFrames(),24,"total duration preserved");
        check(project.drawingAt(0)==project.drawingAt(1),"split shares drawing before editing");
        check(project.audioClips.size()==1 && project.audioClips.get(0).startFrame==0 &&
            project.audioClips.get(0).durationFrames==24,"audio untouched by split");
        Model.Drawing detached=project.ensureEditable(1);
        detached.texts.get(0).value="SECOND";
        check(project.drawingAt(0)!=detached &&
            project.drawingAt(0).texts.get(0).value.equals("FIRST"),"text edit detaches the split");
        int third=project.splitAt(20);
        eq(third,2,"subsequent split");
        eq(project.timeline.get(1).duration,13,"middle duration");
        eq(project.timeline.get(2).duration,4,"tail duration");
        for(int boundary:new int[]{-1,0,7,20,24,25}) {
            int count=project.timeline.size(),frames=project.totalFrames();
            boolean rejected=false;
            try{project.splitAt(boundary);}catch(IllegalArgumentException e){rejected=true;}
            check(rejected,"reject split at boundary "+boundary);
            eq(project.timeline.size(),count,"no change on error");
            eq(project.totalFrames(),frames,"duration unchanged on error");
        }
        ByteArrayOutputStream stream=new ByteArrayOutputStream();
        ProjectIO.write(project,stream);
        Model.Project opened=ProjectIO.read(new ByteArrayInputStream(stream.toByteArray()));
        eq(opened.timeline.size(),3,"saved split count");
        eq(opened.clipStart(2),20,"saved split timing");
        eq(opened.totalFrames(),24,"saved duration");
        check(opened.drawingAt(0).texts.get(0).value.equals("FIRST"),"original text retained");
        check(opened.drawingAt(1).texts.get(0).value.equals("SECOND"),"independent text retained");
        opened.validate();
        System.out.println("SplitExposureTest passed");
    }
}
