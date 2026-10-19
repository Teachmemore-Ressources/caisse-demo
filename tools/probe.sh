#!/bin/bash
echo "== outils"; for t in fakeroot objcopy ldd rpm dpkg-deb; do printf '%s: ' "$t"; command -v $t || echo "ABSENT"; done
echo "== gradle courant"; curl -s https://services.gradle.org/versions/current | grep -E '"version"|"downloadUrl"'
m=https://repo.maven.apache.org/maven2
for a in org/jetbrains/compose/org.jetbrains.compose.gradle.plugin org/jetbrains/kotlin/kotlin-gradle-plugin org/xerial/sqlite-jdbc org/jetbrains/kotlinx/kotlinx-serialization-json org/jetbrains/kotlinx/kotlinx-coroutines-swing; do
  printf '%s -> ' "$a"; curl -s "$m/$a/maven-metadata.xml" | grep -E '<release>' | head -1
done
