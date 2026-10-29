#!/bin/sh
# Builds openhouse-helper.jar (needs JDK 8 or newer).
set -e
cd "$(dirname "$0")"
RTC_VER=0.20.0
RTC_SHA=7c2b8349fd1a628b7ced59106ab4dd3314dd0a0e0944ea0e740f84ac9c4ff349
RTC=build-libs/webrtc-java-$RTC_VER.jar
if [ ! -f "$RTC" ]; then
  mkdir -p build-libs
  curl -fsSL -o "$RTC.part" "https://repo1.maven.org/maven2/dev/onvoid/webrtc/webrtc-java/$RTC_VER/webrtc-java-$RTC_VER.jar"
  echo "$RTC_SHA  $RTC.part" | sha256sum -c - >/dev/null
  mv "$RTC.part" "$RTC"
fi
rm -rf out && mkdir -p out
javac --release 8 -nowarn -encoding UTF-8 -cp "$RTC" -d out $(find src -name '*.java')
printf 'Main-Class: openhouse.Main\n' > out/MANIFEST.MF
(cd out && jar cfm ../openhouse-helper.jar MANIFEST.MF openhouse)
echo built openhouse-helper.jar
