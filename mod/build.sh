#!/bin/sh
set -e
cd "$(dirname "$0")"
./gradlew build --console=plain
ls build/libs/openhouse-*.jar | grep -v sources
