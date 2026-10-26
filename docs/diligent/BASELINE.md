# Diligent baseline

Measurements of the Phase 0 build: VSCodroid renamed, before Diligent adds
anything. Later phases compare against these numbers, and Phase 6 starts by
measuring them again.

Fill in one row per device. Measure the `debug-apk` artifact from the Build
workflow on `main`, installed fresh (uninstall any earlier Diligent first).

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
