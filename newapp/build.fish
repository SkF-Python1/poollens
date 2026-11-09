#!/usr/bin/env fish
# Build PoolLens from plain Java sources: aapt2 -> javac -> d8 -> zip -> align -> sign.
# No Gradle, no network. Everything resolves against the local Android SDK.

set ROOT (dirname (status filename))
set SRC   $ROOT/src
set RES   $ROOT/res
set MANI  $ROOT/AndroidManifest.xml

set SDK   /home/atiya/.local/android-build/android-sdk
set BT    $SDK/build-tools/34.0.0
set AJAR  $SDK/platforms/android-34/android.jar
set JDK   /home/atiya/.local/android-build/jdk-17.0.12+7

set OUT   $ROOT/build
set GEN   $OUT/gen
set CLS   $OUT/classes
set DEX   $OUT/dex
set APK   $OUT/poollens-unsigned.apk
set ALIGNED $OUT/poollens-aligned.apk
set SIGNED  $ROOT/poollens.apk

set KS    /mnt/data/Code/8BP/.tools/fstool.keystore
set KSPW  fstool123

rm -rf $OUT
mkdir -p $GEN $CLS $DEX

echo "==> [1/6] compiling resources (aapt2)"
$BT/aapt2 compile --dir $RES -o $OUT/res.zip
or begin; echo "!! aapt2 compile failed"; exit 1; end

echo "==> [2/6] linking resources"
$BT/aapt2 link \
    -o $OUT/base.apk \
    -I $AJAR \
    --manifest $MANI \
    --java $GEN \
    --min-sdk-version 26 \
    --target-sdk-version 34 \
    $OUT/res.zip
or begin; echo "!! aapt2 link failed"; exit 1; end

echo "==> [3/6] compiling java"
find $SRC $GEN -name '*.java' > $OUT/sources.txt
$JDK/bin/javac \
    -source 11 -target 11 \
    -classpath $AJAR \
    -d $CLS \
    -nowarn \
    @$OUT/sources.txt
or begin; echo "!! javac failed"; exit 1; end

echo "==> [4/6] dexing"
find $CLS -name '*.class' > $OUT/classes.txt
$BT/d8 --release --min-api 26 --lib $AJAR --output $DEX @$OUT/classes.txt
or begin; echo "!! d8 failed"; exit 1; end

echo "==> [5/6] packaging"
python3 $ROOT/package.py $OUT/base.apk $DEX/classes.dex $APK
or begin; echo "!! packaging failed"; exit 1; end

echo "==> [6/6] aligning + signing"
$BT/zipalign -f -p 4 $APK $ALIGNED
or begin; echo "!! zipalign failed"; exit 1; end

$BT/apksigner sign \
    --ks $KS --ks-pass pass:$KSPW --key-pass pass:$KSPW \
    --out $SIGNED $ALIGNED
or begin; echo "!! apksigner failed"; exit 1; end

$BT/apksigner verify --print-certs $SIGNED | head -3

echo ""
echo "OK -> $SIGNED"
ls -la $SIGNED
