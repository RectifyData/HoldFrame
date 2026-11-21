package dev.rectifydata.holdframe.core;
import java.io.*;
public final class TextTransformProjectTest {
    private static void eq(float e,float a,String label){if(Math.abs(e-a)>.001f)throw new AssertionError(label+": "+a+" expected "+e);}
    public static void main(String[] args)throws Exception{
        Model.Project p=new Model.Project();
        Model.TextItem item=new Model.TextItem("Editable","sans-serif","",0,0xFF123456,40,80,90);
        p.drawingAt(0).texts.add(item);
        TextTransformMath.Result scaled=TextTransformMath.resize(item.x,item.y,300,100,1,1,3,680,290);
        item.x=scaled.x;item.y=scaled.y;item.scaleX=scaled.scaleX;item.scaleY=scaled.scaleY;
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        ProjectIO.write(p,out);
        Model.Project loaded=ProjectIO.read(new ByteArrayInputStream(out.toByteArray()));
        Model.TextItem restored=loaded.drawingAt(0).texts.get(0);
        eq(item.x,restored.x,"X");eq(item.scaleX,restored.scaleX,"scaleX");
        Model.ClipboardSequence clipboard=loaded.copyRange(0,0);
        loaded.pasteSequenceAfter(0,clipboard);loaded.pasteSequenceAfter(0,clipboard);
        loaded.ensureEditable(1).texts.get(0).x+=30;
        eq(item.x,loaded.drawingAt(0).texts.get(0).x,"copy-on-write");
        eq(item.x,loaded.drawingAt(2).texts.get(0).x,"other paste");
        System.out.println("TextTransformProjectTest passed");
    }
}