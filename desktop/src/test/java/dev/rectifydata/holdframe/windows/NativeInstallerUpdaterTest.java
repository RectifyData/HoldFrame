package dev.rectifydata.holdframe.windows;
public final class NativeInstallerUpdaterTest {
  private static void ok(boolean b,String reason){if(!b)throw new AssertionError(reason);}
  public static void main(String[] args) {
    String w="{\"tag_name\":\"windows-v0.12.9\",\"assets\":[{\"name\":\"HoldFrame-Setup.exe\",\"browser_download_url\":\"https://github.com/RectifyData/HoldFrame/releases/download/windows-v0.12.9/HoldFrame-Setup.exe\"}]}";
    String l="{\"tag_name\":\"linux-v0.12.8\",\"assets\":[{\"name\":\"HoldFrame-Linux-amd64.deb\",\"browser_download_url\":\"https://github.com/RectifyData/HoldFrame/releases/download/linux-v0.12.8/HoldFrame-Linux-amd64.deb\"}]}";
    String json="["+w+","+l+"]";
    var windows=NativeInstallerUpdater.parse(json,"0.12.1","windows");
    ok(windows!=null&&windows.version.equals("0.12.9")&&windows.asset.equals("HoldFrame-Setup.exe"),"Windows installer");
    var linux=NativeInstallerUpdater.parse(json,"0.12.1","linux");
    ok(linux!=null&&linux.version.equals("0.12.8")&&linux.asset.equals("HoldFrame-Linux-amd64.deb"),"Linux package");
    ok(NativeInstallerUpdater.parse(json,"0.12.9","windows")==null,"already updated");
    ok(NativeInstallerUpdater.parse(json.replace("https://github.com/RectifyData/HoldFrame","https://evil.example"),"0.12.1","windows")==null,"reject other hosts");
    System.out.println("NativeInstallerUpdaterTest passed");
  }
}
