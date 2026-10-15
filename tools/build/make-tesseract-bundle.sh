#!/usr/bin/env bash
# Builds the Tesseract OCR bundle the Windows installer ships:
#     apps/app/backend/proxyma-desktop/src/assembly/tesseract/tesseract-w64.zip
#
# Run by hand on the Windows PC, and only when the bundle should change (a new Tesseract or a new
# language). The zip is COMMITTED, like the JRE and embeddable-Python zips beside it, so a release
# build needs neither this script nor the network.
#
# WHY IT EXISTS. pytesseract, which the desktop packages have always shipped, is only a driver: it
# shells out to tesseract.exe. Nothing shipped the program, so the offline OCR engine said
# "available" and failed on every scanned document (TesseractNotFoundError). See OcrService.
#
# WHAT GOES IN. The UB Mannheim build is the standard Windows Tesseract, but its installer carries
# 239 MB of training tools and the GTK stack they need. Proxyma needs tesseract.exe and the 26 DLLs
# it actually loads - found by walking its import tables, the list below - plus language data:
#   eng  - from the installer
#   vie  - tessdata_fast, pinned to a commit. Fast models, because they are a twentieth of the size
#          of the best ones and OCR here feeds search, not typesetting.
# osd.traineddata is left out: OcrService never asks for orientation detection.
#
# Needs: curl, 7-Zip at its default path, sha256sum. Git Bash has all but 7-Zip.
set -euo pipefail

UB_VERSION="5.4.0.20240606"
UB_URL="https://github.com/UB-Mannheim/tesseract/releases/download/v${UB_VERSION}/tesseract-ocr-w64-setup-${UB_VERSION}.exe"
UB_SHA256="c885fff6998e0608ba4bb8ab51436e1c6775c2bafc2559a19b423e18678b60c9"
ENG_SHA256="7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
VIE_COMMIT="923915d4ced2a7235221788285785a29c4a42d4a"
VIE_URL="https://github.com/tesseract-ocr/tessdata_fast/raw/${VIE_COMMIT}/vie.traineddata"
VIE_SHA256="79df64caf7bcfb2a27df5042ecb6121e196eada34da774956995747636d5bfa1"
SEVENZIP="/c/Program Files/7-Zip/7z.exe"

FILES=(
  tesseract.exe
  libLerc.dll libarchive-13.dll libb2-1.dll libbz2-1.dll libcrypto-3-x64.dll libdeflate.dll
  libexpat-1.dll libgcc_s_seh-1.dll libgif-7.dll libiconv-2.dll libjbig-0.dll libjpeg-8.dll
  libleptonica-6.dll liblz4.dll liblzma-5.dll libopenjp2-7.dll libpng16-16.dll libsharpyuv-0.dll
  libstdc++-6.dll libtesseract-5.dll libtiff-6.dll libwebp-7.dll libwebpmux-3.dll
  libwinpthread-1.dll libzstd.dll zlib1.dll
)

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$REPO/apps/app/backend/proxyma-desktop/src/assembly/tesseract/tesseract-w64.zip"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

check() {  # file expected-sha256
  local got; got="$(sha256sum "$1" | cut -d' ' -f1)"
  [ "$got" = "$2" ] || { echo "checksum mismatch for $1: $got" >&2; exit 1; }
}

[ -x "$SEVENZIP" ] || { echo "7-Zip not found at $SEVENZIP" >&2; exit 1; }

echo "==> Downloading Tesseract $UB_VERSION"
curl -fsSL -o "$WORK/setup.exe" "$UB_URL"
check "$WORK/setup.exe" "$UB_SHA256"
curl -fsSL -o "$WORK/vie.traineddata" "$VIE_URL"
check "$WORK/vie.traineddata" "$VIE_SHA256"

echo "==> Extracting"
"$SEVENZIP" x -y -o"$WORK/ext" "$WORK/setup.exe" >/dev/null

B="$WORK/bundle/tesseract"
mkdir -p "$B/tessdata"
for f in "${FILES[@]}"; do cp "$WORK/ext/$f" "$B/"; done
check "$WORK/ext/tessdata/eng.traineddata" "$ENG_SHA256"
cp "$WORK/ext/tessdata/eng.traineddata" "$B/tessdata/"
cp "$WORK/vie.traineddata" "$B/tessdata/"
cp "$WORK/ext/doc/LICENSE" "$B/LICENSE.txt"
cp "$WORK/ext/doc/AUTHORS" "$B/AUTHORS.txt"
cat > "$B/README.txt" <<EOF
Tesseract OCR ${UB_VERSION}, as built for Windows by UB Mannheim
(https://github.com/UB-Mannheim/tesseract), reduced to the program, the libraries it loads and
two languages: eng (from that build) and vie (tesseract-ocr/tessdata_fast @ ${VIE_COMMIT}).
Tesseract is licensed under the Apache License 2.0 - see LICENSE.txt.
Bundled by Proxyma for its offline OCR engine; made by tools/build/make-tesseract-bundle.sh.
EOF

echo "==> Checking it runs"
"$B/tesseract.exe" --list-langs | tr -d '\r' | grep -qx vie || { echo "vie not listed" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
(cd "$WORK/bundle" && "$SEVENZIP" a -tzip -mx=9 "$(cygpath -w "$OUT")" tesseract >/dev/null)
echo "==> $OUT ($(du -h "$OUT" | cut -f1))"
