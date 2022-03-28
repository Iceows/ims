
xcopy /Y "C:\GitRepo\iceows\ims\app\build\outputs\apk\debug\app-debug.apk" .
java.exe -jar "ApkSigner.jar" sign  --key platform.pk8 --cert platform.x509.pem  --v4-signing-enabled false --out "PhhIMS.apk" "app-debug.apk"

adb root
adb remount rw, /system
adb shell mkdir /system/priv-app/PhhIMS
adb push PhhIMS.apk /system/priv-app/PhhIMS
adb shell chmod 644 /system/priv-app/PhhIMS/PhhIMS.apk 

adb reboot
