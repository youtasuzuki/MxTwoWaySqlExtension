echo off
echo --------------------------------------------------------------
echo If the TwoWaySql log level is set to `debug` or `trace`,
echo  changes to the copied SQL file will take effect immediately.
echo --------------------------------------------------------------
xcopy ..\sql ..\..\deployment\model\resources\sql /s /d /y
pause
