#!/usr/bin/env bash
# services.gradle.org redirects the distribution to GitHub Releases, which
# answers 504 from GitHub-hosted runners. Leave a checksum-verified zip where
# the wrapper looks, so ./gradlew does not download it again.
set -euo pipefail

NAME="gradle-9.3.1-bin"
HASH="23ovyewtku6u96viwx3xl3oks"
SHA256="b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06"

if [ -n "${GRADLE_USER_HOME:-}" ]; then
  ROOT="${GRADLE_USER_HOME}"
elif [ -n "${USERPROFILE:-}" ] && command -v cygpath >/dev/null 2>&1; then
  # Git Bash HOME is not always the profile gradlew.bat uses.
  ROOT="$(cygpath -u "${USERPROFILE}")/.gradle"
else
  ROOT="${HOME}/.gradle"
fi

DEST="${ROOT}/wrapper/dists/${NAME}/${HASH}"
ZIP="${DEST}/${NAME}.zip"

mkdir -p "${DEST}"

checksum_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print tolower($1)}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print tolower($1)}'
  else
    openssl dgst -sha256 "$1" | awk '{print tolower($NF)}'
  fi
}

checksum_ok() {
  [ -f "${ZIP}" ] || return 1
  [ "$(checksum_of "${ZIP}")" = "${SHA256}" ]
}

if checksum_ok; then
  echo "Gradle distribution already cached"
  exit 0
fi

rm -f "${ZIP}" "${ZIP}.part" "${ZIP}.lck"

urls=(
  "https://mirrors.cloud.tencent.com/gradle/${NAME}.zip"
  "https://mirrors.huaweicloud.com/gradle/${NAME}.zip"
  "https://repo.huaweicloud.com/gradle/${NAME}.zip"
  "https://mirror.nju.edu.cn/gradle/${NAME}.zip"
  "https://services.gradle.org/distributions/${NAME}.zip"
)

for url in "${urls[@]}"; do
  echo "Downloading Gradle from ${url}"
  if curl -fL --retry 2 --retry-delay 2 --connect-timeout 20 --max-time 300 -o "${ZIP}" "${url}" && checksum_ok; then
    rm -f "${ZIP}.lck"
    echo "Gradle distribution ready"
    exit 0
  fi
  rm -f "${ZIP}"
  echo "Failed: ${url}" >&2
done

echo "Could not download Gradle ${NAME}" >&2
exit 1
