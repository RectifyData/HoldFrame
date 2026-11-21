$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
New-Item -ItemType Directory -Force build/classes, dist | Out-Null
$core = Get-ChildItem 'core/src/main/java/dev/rectifydata/holdframe/core' -Filter '*.java' | Select-Object -ExpandProperty FullName
$ui = Get-ChildItem 'desktop/src/main/java/dev/rectifydata/holdframe/windows' -Filter '*.java' | Select-Object -ExpandProperty FullName
& javac --release 17 -encoding UTF-8 -d build/classes @core @ui
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
& jar --create --file dist/HoldFrame.jar --main-class dev.rectifydata.holdframe.windows.HoldFrame -C build/classes .
if ($LASTEXITCODE -ne 0) { throw 'JAR packaging failed' }
Write-Host "Built dist/HoldFrame.jar. Launch using java -jar dist/HoldFrame.jar" -ForegroundColor Green
