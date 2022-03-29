
xcopy /Y "C:\GitRepo\iceows\ims\app\build\outputs\apk\debug\app-debug.apk" .
java.exe -jar "ApkSigner.jar" sign  --key platform.pk8 --cert platform.x509.pem  --v4-signing-enabled false --out "PhhIMS.apk" "app-debug.apk"

rem adb root
rem adb remount rw, /system
rem adb shell mkdir /system/priv-app/PhhIMS
rem adb push PhhIMS.apk /system/priv-app/PhhIMS
rem adb shell chmod 644 /system/priv-app/PhhIMS/PhhIMS.apk 

adb install -r PhhIMS.apk 
 
rem adb reboot
