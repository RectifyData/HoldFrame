package dev.rectifydata.holdframe.windows;

import java.nio.file.Path;

public final class ShortcutSupportTest {
    private static void check(boolean v,String msg){if(!v)throw new AssertionError(msg);}
    public static void main(String[] args){
        Path home=Path.of("/tmp/holdframe install");
        check(ShortcutSupport.stableJar(home,false).endsWith(".local/share/holdframe/HoldFrame.jar"),"Linux path");
        check(ShortcutSupport.stableJar(home,true).endsWith("AppData/Local/Programs/HoldFrame/HoldFrame.jar"),"Windows path");
        String file=ShortcutSupport.desktopFile(home.resolve("HoldFrame.jar"),"applications-graphics");
        check(file.contains("Exec=java -jar \"/tmp/holdframe install/HoldFrame.jar\""),"quoted launcher");
        check(file.contains("Terminal=false"),"no console");
        check(ShortcutSupport.ps("O'Brien").equals("'O''Brien'"),"PowerShell quoting");
        boolean error=false;
        try{ShortcutSupport.ps("foo\nbar");}catch(IllegalArgumentException e){error=true;}
        check(error,"newline rejection");
        System.out.println("ShortcutSupportTest passed");
    }
}
