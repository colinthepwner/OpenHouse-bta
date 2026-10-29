#!/bin/sh
# Builds the OpenHouse mod: build/libs/openhouse-<version>.jar (needs JDK 21).
set -e
cd "$(dirname "$0")"
./gradlew build --console=plain
ls build/libs/openhouse-*.jar | grep -v sources
