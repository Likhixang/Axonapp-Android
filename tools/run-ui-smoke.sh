#!/usr/bin/env bash
set -euo pipefail
mkdir -p ci-ux/screenshots
adb install -r ci-apks/app/app-debug.apk
adb install -r ci-apks/test/app-debug-androidTest.apk
adb logcat -c
set +e
adb shell am instrument -w -r -e class cc.khixang.axonhub.ui.IosUxSmokeTest cc.khixang.axonhub.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee ci-ux/instrumentation.txt
instrument_status=${PIPESTATUS[0]}
set -e
adb logcat -d > ci-ux/logcat.txt
adb pull /sdcard/Android/data/cc.khixang.axonhub/files/screenshots/. ci-ux/screenshots/ || true
python3 - ci-ux/instrumentation.txt "$instrument_status" <<'PY'
import pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text()
if sys.argv[2] != '0' or not re.search(r'OK \(\d+ tests?\)', text) or any(token in text for token in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed')):
    raise SystemExit('Android instrumentation did not pass; see instrumentation.txt and logcat.txt')
shots = list(pathlib.Path('ci-ux/screenshots').glob('*.png'))
if not shots:
    raise SystemExit('Instrumentation passed but no UX screenshots were captured')
print(f'Android device UX smoke passed; {len(shots)} real screenshots captured')
PY
