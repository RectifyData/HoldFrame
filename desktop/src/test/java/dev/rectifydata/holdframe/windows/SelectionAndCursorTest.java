package dev.rectifydata.holdframe.windows;
import dev.rectifydata.holdframe.core.Model;
import java.awt.Container;
import java.awt.event.KeyEvent;
import java.awt.Cursor;
import javax.swing.SwingUtilities;
import java.lang.reflect.Method;
public final class SelectionAndCursorTest {
  private static void ok(boolean x,String why){if(!x)throw new AssertionError(why);}
  static void invoke(Editor e,String name) throws Exception {
    Method method=Editor.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(e);
  }
  static CanvasView find(Container c){
    for(java.awt.Component item:c.getComponents()){
      if(item instanceof CanvasView)return (CanvasView)item;
      if(item instanceof Container){CanvasView cv=find((Container)item);if(cv!=null)return cv;}
    }return null;
  }
  public static void main(String[] args)throws Exception{
    final Throwable[] error=new Throwable[1];
    SwingUtilities.invokeAndWait(()->{
      Editor editor=new Editor();
      try {
        editor.setVisible(true);
        CanvasView view=find(editor.getContentPane());
        ok(view!=null,"canvas missing");
        ok(view.getCursor().getType()==Cursor.CUSTOM_CURSOR,"black brush cursor unavailable");
        Model.Project project=editor.project();
        Model.AudioClip audio=project.addAudio("missing.wav",0,24);
        editor.selectAudio(audio.id);
        invoke(editor,"deleteSelection");
        ok(project.audioClips.isEmpty(),"Delete did not remove selected audio clip");
        int next=project.addAfter(0,false);
        ok(project.timeline.size()==2,"clip setup");
        editor.selectTimelinePose(next,false);
        invoke(editor,"deleteSelection");
        ok(project.timeline.size()==1,"Delete did not remove selected pose clip");
        invoke(editor,"deleteSelection");
        ok(project.timeline.size()==1,"Delete removed unselected pose");
        ok(editor.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
          .get(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0))!=null,"Esc action missing");
        System.out.println("SelectionAndCursorTest passed");
      }catch(Throwable t){error[0]=t;}finally{editor.dispose();}
    });
    if(error[0]!=null)throw new AssertionError(error[0]);
  }
}
