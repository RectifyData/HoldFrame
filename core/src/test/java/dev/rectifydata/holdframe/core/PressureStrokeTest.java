package dev.rectifydata.holdframe.core;
import java.io.*;
public final class PressureStrokeTest {
  public static void main(String[] args)throws Exception {
    Model.Project p=new Model.Project();
    Model.Stroke s=new Model.Stroke(0xff000000,12,false);
    s.points.add(new Model.Point(10,20));s.points.add(new Model.Point(40,60));
    s.pressures.add(.25f);s.pressures.add(1.75f);
    p.drawingAt(0).strokes.add(s);p.validate();
    Model.Stroke clone=s.copy();
    if(clone.pressures.size()!=2||clone.pressures.get(1)!=1.75f)throw new AssertionError("Copy loses pressure");
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();ProjectIO.write(p,bytes);
    Model.Project back=ProjectIO.read(new ByteArrayInputStream(bytes.toByteArray()));
    Model.Stroke round=back.drawingAt(0).strokes.get(0);
    if(round.pressures.size()!=2||round.pressures.get(0)!=.25f||round.pressures.get(1)!=1.75f)
      throw new AssertionError("HFM5 pressure lost");
    round.pressures.clear();round.pressures.add(1f);
    try{back.validate();throw new AssertionError("Invalid pressure count accepted");}
    catch(IllegalArgumentException expected) { }
    System.out.println("PressureStrokeTest passed");
  }
}
