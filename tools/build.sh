#!/system/bin/sh
# 一键构建「文件分割合并」APK：AXML -> javac -> D8 -> 打包 -> V1+V2 签名
# 全部离线（JDK17 与 build-tools 依赖均在本机，不下载任何东西）
set -e
ROOT=/storage/emulated/0/DSH/zip2apk
APP=$ROOT/app
PD=/data/data/com.dsharnessmobile.shell/files
JDK=$PD/opt/jdk17/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk/bin
ANDROID_JAR=/storage/emulated/0/DSH/xhs-mod/libs/android.jar
R8=/storage/emulated/0/DSH/xhs-mod/tools/r8-8.9.35.jar
APKSIGNER=/storage/emulated/0/DSH/xhs-mod/libs/apksigner.jar
export LD_LIBRARY_PATH=$PD/usr/lib
PY=python3

cd "$APP"
mkdir -p out/classes out/dex out/apk apk

echo "[1/6] AndroidManifest.xml -> 二进制 AXML"
$PY "$ROOT/tools/axml.py" AndroidManifest.xml out/AndroidManifest.bin

echo "[2/6] javac"
rm -rf out/classes; mkdir -p out/classes
$JDK/javac -nowarn -source 8 -target 8 -cp "$ANDROID_JAR" -d out/classes \
    $(find src -name "*.java")
echo "      classes: $(find out/classes -name '*.class' | wc -l)"

echo "[3/6] D8 -> classes.dex"
rm -rf out/dex; mkdir -p out/dex
$JDK/java -Xmx768m -cp "$R8" com.android.tools.r8.D8 --release --min-api 24 \
    --lib "$ANDROID_JAR" --output out/dex \
    $(find out/classes -name "*.class") 2>&1 | tail -1
echo "      dex: $(stat -c%s out/dex/classes.dex) bytes"

echo "[4/6] 组装 APK（图标 + resources.arsc）"
cp out/AndroidManifest.bin out/apk/AndroidManifest.xml
cp out/dex/classes.dex out/apk/classes.dex
rm -rf out/apk/res; mkdir -p out/apk/res
cp -r res/. out/apk/res/
$PY "$ROOT/tools/resgen.py" "$APP"
$PY "$ROOT/tools/pack.py" "$APP"

echo "[5/6] 生成签名密钥（首次）"
if [ ! -f apk/zipsplit.jks ]; then
  $JDK/keytool -genkeypair -keystore apk/zipsplit.jks -alias zipsplit \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass 123456 -keypass 123456 -dname "CN=zipsplit,O=dsh" 2>&1 | tail -1
fi

echo "[6/6] 签名（V1+V2）"
rm -f out/zipsplit.apk
$JDK/java -jar "$APKSIGNER" sign --ks apk/zipsplit.jks \
    --ks-pass pass:123456 --key-pass pass:123456 \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out out/zipsplit.apk out/zipsplit-unsigned.apk 2>&1 | tail -1

echo ""
echo "✅ 构建完成: $APP/out/zipsplit.apk  ($(stat -c%s out/zipsplit.apk) bytes)"
echo "   安装: su -c 'cp $APP/out/zipsplit.apk /data/local/tmp/ && pm install -r -t /data/local/tmp/zipsplit.apk'"
