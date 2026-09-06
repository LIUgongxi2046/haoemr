#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
project_dir=$(cd "$script_dir/.." && pwd)
version="0.1.0-SNAPSHOT"
release_dir="$project_dir/release/haoemr-$version-synthetic"
archive="$project_dir/release/haoemr-$version-synthetic.tar.gz"

cd "$project_dir"
"$script_dir/with-java21.sh" ./gradlew bootJar --no-daemon --no-configuration-cache
npm --prefix web run build
"$script_dir/security-scan.sh"

rm -rf "$release_dir"
mkdir -p "$release_dir/backend" "$release_dir/web" "$release_dir/deploy" "$release_dir/samples/data"
cp build/libs/haoemr-*.jar "$release_dir/backend/haoemr.jar"
cp -R web/dist/. "$release_dir/web/"
cp deploy/compose.synthetic.yml deploy/backend.Dockerfile deploy/web-synthetic.Dockerfile deploy/README.md "$release_dir/deploy/"
cp samples/data/synthetic-clinical-golden-v1.json "$release_dir/samples/data/"
cp gradlew gradlew.bat settings.gradle.kts build.gradle.kts "$release_dir/"
cp -R gradle contracts src "$release_dir/"
cp web/package.json web/package-lock.json web/index.html web/tsconfig.json web/vite.config.ts "$release_dir/web/"
cp -R web/src "$release_dir/web/"
cp README.md "$release_dir/"

(
  cd "$release_dir"
  find . -type f ! -name SHA256SUMS -print0 | LC_ALL=C sort -z | xargs -0 shasum -a 256 > SHA256SUMS
)
tar -C "$project_dir/release" -czf "$archive" "$(basename "$release_dir")"
shasum -a 256 "$archive"
