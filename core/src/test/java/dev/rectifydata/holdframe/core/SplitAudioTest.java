package dev.rectifydata.holdframe.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/** Split an audio clip with exact playhead boundaries and no media duplication. */
public final class SplitAudioTest {
    private static void ok(boolean b,String why){if(!b)throw new AssertionError(why);}
    public static void main(String[] args) throws Exception {
        Model.Project p=new Model.Project();
        Model.AudioClip first=p.addAudio("sounds/test.wav",0,24);
        first.volume=.45f;first.muted=true;first.sourceOffsetMillis=200;
        Model.AudioClip second=p.splitAudioClip(first.id,7);
        ok(p.audioClips.size()==2,"count");
        ok(first.durationFrames==7&&second.startFrame==7&&second.durationFrames==17,"timing");
        ok(second.sourceOffsetMillis==492,"source offset");
        ok(second.volume==first.volume&&second.muted==first.muted,"properties");
        ok(first.sourcePath.equals(second.sourcePath)&&!first.id.equals(second.id),"shared source unique clip");
        ok(p.totalFrames()==24,"animation duration unchanged");
        boolean rejected=false;
        try{p.splitAudioClip(first.id,0);}catch(IllegalArgumentException e){rejected=true;}
        ok(rejected&&p.audioClips.size()==2,"reject outside audio clip");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        ProjectIO.write(p,bytes);
        Model.Project saved=ProjectIO.read(new ByteArrayInputStream(bytes.toByteArray()));
        ok(saved.audioClips.size()==2&&saved.audioClips.get(1).sourceOffsetMillis==492,"save/reopen");
        saved.validate();
        System.out.println("SplitAudioTest passed");
    }
}
