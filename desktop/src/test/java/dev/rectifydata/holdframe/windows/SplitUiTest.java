package dev.rectifydata.holdframe.windows;

import dev.rectifydata.holdframe.core.Model;
import javax.swing.SwingUtilities;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/** Real UI split regressions for pose/audio at playhead. */
public final class SplitUiTest {
    private static void check(boolean v,String label){if(!v)throw new AssertionError(label);}
    private static void call(Editor e,String name)throws Exception{
        Method m=Editor.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(e);
    }
    public static void main(String[] args)throws Exception{
        AtomicReference<Throwable> error=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{
            Editor editor=new Editor();
            try{
                editor.setVisible(true);
                editor.seek(6);
                call(editor,"splitAtPlayhead");
                check(editor.project().timeline.size()==2,"split exposures");
                check(editor.project().timeline.get(0).duration==6,"left exposure");
                check(editor.project().timeline.get(1).duration==18,"right exposure");
                check(editor.project().totalFrames()==24,"total frames unchanged");
                Model.AudioClip audio=editor.project().addAudio("example.wav",0,24);
                audio.sourceOffsetMillis=100;
                editor.selectAudio(audio.id);
                editor.seek(10);
                call(editor,"splitSelectedAudio");
                check(editor.project().audioClips.size()==2,"two audio clips");
                Model.AudioClip after=editor.project().audioClips.get(1);
                check(audio.durationFrames==10&&after.startFrame==10&&after.durationFrames==14,"split timing");
                check(after.sourceOffsetMillis==517,"audio source offset");
                check(editor.project().totalFrames()==24,"audio does not move pose timeline");
            }catch(Throwable ex){error.set(ex);}finally{editor.dispose();}
        });
        if(error.get()!=null)throw new AssertionError("SplitUiTest failed",error.get());
        System.out.println("SplitUiTest passed");
    }
}
