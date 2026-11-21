package dev.rectifydata.holdframe.core;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/** Save/open and copied-pose regression for transformed editable text. */
public final class TextTransformProjectTest {
    private static void eq(float e,float a,String n){if(Math.abs(e-a)>.001f)throw new AssertionError(n+": "+a+" expected "+e);}
    public static void main(String[] args)throws Exception{
        Model.Project project=new Model.Project();
        Model.Drawing drawing=project.drawingAt(0);
        drawing.texts.add(new Model.TextItem("Hello\nHoldFrame","sans-serif","",0,0xFF123456,40,80,90));
        Model.TextItem item=drawing.texts.get(0);
        TextTransformMath.Result moved=TextTransformMath.resize(item.x,item.y,300,100,item.scaleX,item.scaleY,3,680,290);
        item.x=moved.x;item.y=moved.y;item.scaleX=moved.scaleX;item.scaleY=moved.scaleY;
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        ProjectIO.write(project,out);
        Model.Project reloaded=ProjectIO.read(new ByteArrayInputStream(out.toByteArray()));
        Model.TextItem restored=reloaded.drawingAt(0).texts.get(0);
        eq(item.x,restored.x,"position X");eq(item.y,restored.y,"position Y");
        eq(item.scaleX,restored.scaleX,"horizontal scale");eq(item.scaleY,restored.scaleY,"vertical scale");
        Model.ClipboardSequence clipboard=reloaded.copyRange(0,0);
        reloaded.pasteSequenceAfter(0,clipboard);
        reloaded.pasteSequenceAfter(0,clipboard);
        Model.Drawing editable=reloaded.ensureEditable(1);
        editable.texts.get(0).x+=100;
        eq(item.x,reloaded.drawingAt(0).texts.get(0).x,"first original untouched");
        eq(item.x,reloaded.drawingAt(2).texts.get(0).x,"pasted pose unchanged");
        System.out.println("TextTransformProjectTest passed");
    }
}
