# Diligent baseline

Measurements of the Phase 0 build: VSCodroid renamed, before Diligent adds
anything. Later phases compare against these numbers, and Phase 6 starts by
measuring them again.

Fill in one row per device. Measure the `debug-apk` artifact from the Build
workflow on `main`, installed fresh (uninstall any earlier Diligent first).

## Phase 6 measurement (2026-10-27)

Measured from the latest local `debug-apk` build
(`app-debug.apk`, 314,982,467 bytes on disk) with `unzip -l` and Python's
`zipfile` (compressed size is what the phone downloads; unpacked is what the
APK holds before first-run extraction). MiB = 1,048,576 bytes.

### Download size

| Part of the APK | Download (MiB) | Unpacked (MiB) | Files |
| --- | ---: | ---: | ---: |
| Whole APK | 293.9 | 721.9 | 23,947 |
| `assets/` | 199.2 | 591.6 | 22,880 |
| `classes*.dex` (debug, not minified) | 71.0 | 71.1 | 16 |
| `lib/arm64-v8a/` (native programs) | 23.2 | 58.2 | 15 |
| `res/`, `resources.arsc` | 2.6 | 3.0 | 931 |

The debug build skips R8 (`isMinifyEnabled = false`), so its dex files are
much larger than a release build's. The release download will be smaller by
most of the 71 MiB of dex; measure the signed release APK the same way when
Phase 7 builds one.

Largest native programs: `libnode.so` 16.1 MiB, `libripgrep.so` 2.1 MiB,
`libgit.so` 1.8 MiB, `libgit-remote-curl.so` 1.1 MiB.

### Folders under `assets/`

| Folder | Download (MiB) | Unpacked (MiB) | Files |
| --- | ---: | ---: | ---: |
| `assets/vscode-reh/` (editor server) | 142.8 | 435.6 | 17,374 |
| of which `vscode-reh/extensions/` | 112.2 | 347.8 | 14,669 |
| of which `vscode-reh/out/` | 15.3 | 40.7 | 261 |
| of which `vscode-reh/node_modules/` | 15.1 | 46.6 | 2,431 |
| `assets/usr/` (programs and libraries) | 37.9 | 93.1 | 2,852 |
| `assets/extensions/` (bundled extensions) | 13.2 | 45.1 | 2,630 |
| `assets/nls/` (translations) | 5.2 | 17.6 | 13 |
| loose files (`server.js` and others) | 0.1 | 0.3 | 11 |

Bundled extensions: `ms-python.python` 6.6 MiB, `esbenp.prettier-vscode`
4.2 MiB, `bradlc.vscode-tailwindcss` 2.0 MiB, `dbaeumer.vscode-eslint`
0.3 MiB; the Diligent and VSCodroid extensions are under 0.1 MiB each.

### Folders under `assets/usr/`

| Folder | Download (MiB) | Unpacked (MiB) | Files |
| --- | ---: | ---: | ---: |
| `usr/lib/` loose libraries | 25.2 | 59.1 | 55 |
| of which `libicudata.so.78` | 13.1 | 31.6 | 1 |
| of which `libcrypto.so.3` | 2.4 | 5.0 | 1 |
| of which `libpython3.14.so` | 2.0 | 5.6 | 1 |
| `usr/lib/python3.14/` (standard library and pip) | 8.8 | 22.9 | 1,151 |
| `usr/lib/node_modules/` (npm) | 2.9 | 8.4 | 1,550 |
| `usr/lib/node-addons/` | 0.6 | 1.9 | 5 |
| `usr/lib/git-core/` | 0.0 | 0.1 | 15 |
| `usr/share/` (licenses, terminfo) | 0.2 | 0.7 | 76 |

The source tree holds none of these: `assets/usr/`, `assets/vscode-reh/` and
`assets/nls/` are built by the download scripts and ignored by git. The
committed part of `android/app/src/main/assets/` is 0.9 MiB (the Diligent and
VSCodroid extensions, 0.8 MiB, and the five `.js` helpers).

### Storage after setup and idle memory

Not measured yet, needs a device. Use the commands under "How to measure"
below, on a fresh install of the same APK.

### Python pack decision

Python's share of the base download, from the table above:

| Python part | Download (MiB) |
| --- | ---: |
| `usr/lib/python3.14/` (standard library, pip 1.9 MiB of it) | 8.83 |
| `usr/lib/libpython3.14.so` | 1.98 |
| Libraries only Python uses (`libffi`, `libexpat`, `libgdbm`, `libbz2`; likely Python only, not traced) | 0.15 |
| Python core subtotal | 10.96 |
| `ms-python.python` extension | 6.6 |
| Python with its extension | 17.6 |

- Core only: 10.96 of 293.9 MiB = 3.7% of this debug download.
- With the extension: 17.6 of 293.9 MiB = 6.0%.
- Against a release build with the 71 MiB of dex shrunk to about 10 MiB
  (an estimate, not measured): 17.6 of about 233 MiB = 7.6%.

The guide's bar is 15%. Python is under half of it in every reading.

**Recommendation: do not move Python into a `toolchain_python` pack.** The
saving is at most 17.6 MiB, and Python is wired into many places that a pack
would all have to change: `scripts/download-python.sh`,
`scripts/build-wheelhouse.py`, `wheelhouse.json`,
`scripts/patch-python-platform.py`, `scripts/download-extensions.sh` (the
`ms-python` extension), `scripts/check-binary-lists.py`,
`scripts/check-bundled-extensions.py`, `android/app/build.gradle.kts` (35
mentions), `setup/FirstRunSetup.kt` (96 mentions: extraction, symlinks, pip
config, bytecode, reclaim), `util/Environment.kt`, `util/StorageManager.kt`,
`setup/ToolchainManager.kt`, the Diligent run detector and recipes
(`run/RunDetector.kt`, `run/RunConfig.kt`, `kit/KitRecipe.kt`), the
`build.yml`, `lint.yml` and `release.yml` workflows, and about 15 unit tests
under `test/kotlin/com/vscodroid/setup/` (`PythonReclaimTest`,
`SupersededPythonTest`, `PipConfigTest`, `PythonBytecodeEnvTest` and
others). The `python-script` recipe would also stop working offline on a new
install. Revisit only if Python grows past 15% (for example a bundled
scientific wheelhouse).

The bigger levers, if the download has to shrink: R8 on the release build
(about 60 MiB), `libicudata.so.78` (13.1 MiB, full ICU data; a smaller ICU
data file would cut most of it), and the built-in extensions under
`vscode-reh/extensions/` (112.2 MiB).

## Results

| Measure | Device and Android version | Value | Date |
| --- | --- | --- | --- |
| Download size (APK) | | | |
| Storage after first setup | | | |
| Cold start to editor ready (median of 10 launches) | | | |
| Idle memory | | | |

## How to measure

**Download size.** The size of the APK file:

```bash
ls -l app-debug.apk
```

**Storage after first setup.** Open the app once, let first-run setup finish
and the editor appear, then read Settings, Apps, Diligent, Storage and add App
size and User data. Or:

```bash
adb shell dumpsys diskstats | grep -A3 com.ditarector.diligent.debug
```

**Cold start to editor ready.** Force-stop the app, start it, and time it
until the editor is usable (the file explorer and an editor tab respond to a
tap). Repeat 10 times and write down the middle value (the median):

```bash
adb shell am force-stop com.ditarector.diligent.debug
adb shell am start -W -n com.ditarector.diligent.debug/com.vscodroid.SplashActivity
```

`am start -W` reports when the first screen is drawn, which is earlier than
"editor ready"; use a stopwatch for the full time and note the `TotalTime`
beside it.

**Idle memory.** With the editor open and nothing running for one minute:

```bash
adb shell dumpsys meminfo com.ditarector.diligent.debug | grep -A4 'App Summary'
```

Write down `TOTAL PSS`. The editor server runs as a child process, so also
add the Node processes:

```bash
adb shell run-as com.ditarector.diligent.debug ps -A -o RSS,NAME | grep -i node
```
