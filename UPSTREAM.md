# Upstream: VSCodroid

Diligent is built on [VSCodroid](https://github.com/rmyndharis/VSCodroid) by
Yudhi Armyndharis and the VSCodroid Community, released under the MIT License
(see `LICENSE` and `NOTICE.md`).

VSCodroid provides the engine: the Code - OSS editor server running on Node
inside the app, the bundled tools (bash, Git, tmux, Node, npm, Python, OpenSSH),
the language packs, the foreground service and the Android bridge. Diligent
keeps that engine and adds a phone-first interface on top of it, with a
user-chosen Mobile or Desktop view. `AGENT_GUIDE.md` describes the plan.

This repository was cloned from VSCodroid with its full history, so updates
from VSCodroid merge like any other branch.

## Remotes

| Remote | Points to | Used for |
| --- | --- | --- |
| `origin` | This repository (Diligent) | Our work |
| `upstream` | `https://github.com/rmyndharis/VSCodroid.git` | VSCodroid's updates |

A fresh clone of Diligent has no `upstream` remote. Add it once:

```bash
git remote add upstream https://github.com/rmyndharis/VSCodroid.git
```

## Pulling VSCodroid's updates

```bash
git checkout main
git fetch upstream
git checkout -b upstream-merge-YYYY-MM-DD
git merge upstream/main
```

Then open a pull request, let the Build and Lint workflows run, install the
debug APK and run the two-minute smoke test in `AGENT_GUIDE.md` section 4
before merging.

## Keeping merges easy

Most of the rules in `AGENT_GUIDE.md` section 3 exist so these merges stay
clean:

- **Our code lives in new files** with the `diligent` prefix: Kotlin under
  `kotlin/com/vscodroid/diligent/`, extensions named `diligent.*`. A new file
  never conflicts.
- **VSCodroid's identifiers stay as they are:** the Kotlin `namespace`
  `com.vscodroid` and every Kotlin package, the `.vscodroid` data folders, the
  `vscodroid-bridge` channel and the `vscodroid.*` extension IDs. Only the
  `applicationId` (what Android and Google Play see) is Diligent's own.
- **Edits to VSCodroid's own files stay small**, for example one line that
  calls into a `diligent` file rather than a block of new code in place.
- **`patches/` is avoided.** Changes to the VS Code source are the hardest to
  carry across updates.

## Where merges are likely to conflict

These VSCodroid files carry small Diligent edits. Expect conflicts here and
resolve them by keeping both sides' intent:

| File | Diligent's edit |
| --- | --- |
| `android/app/build.gradle.kts` | `applicationId` |
| `scripts/deploy.sh`, `device-launch.sh`, `device-test.sh` | Default `PKG` is Diligent's debug ID |
| `android/app/src/androidTest/` (`AndroidManifest.xml`, `SafProviderGrantReceiver.java`) | The package the instrumented tests target |
| `CONTRIBUTING.md`, `docs/USER_GUIDE.md` | `adb` commands and storage paths name Diligent's ID |
| `android/app/src/main/res/values*/strings.xml` | The app's name |
| `assets/extensions/vscodroid.*/package.nls*.json`, `extension.js` | Visible text says Diligent (`Diligent: ...` commands); IDs unchanged |
| `android/app/src/test/.../setup/CommandReferenceTest.kt` | Checks the `Diligent: ` command prefix |
| `branding/product.json` | Editor names and links |
| `android/app/src/main/assets/server.js` | `nameShort` and `nameLong` in `productOverrides` |
| `scripts/verify-server-tree.py`, `scripts/test-server-bootstrap.js` | Expect the name Diligent |
| `LICENSE` | Diligent's copyright line under VSCodroid's |
| `android/gradle/libs.versions.toml`, `android/build.gradle.kts`, `android/app/build.gradle.kts` | Compose, Material 3 and `androidx.window` |
| `android/app/src/main/kotlin/com/vscodroid/setup/FirstRunSetup.kt` | `isOwnExtension()`: `diligent.diligent-*` extensions count as the app's own |
| `.gitignore`, `scripts/check-extension-updates.py` | `diligent.diligent-*` extensions are committed and need a version bump on change |

Add a row whenever a phase edits another VSCodroid file.

## Versions

VSCodroid's `versionCode` drives the data migrations in `FirstRunSetup.kt`.
Do not reset or offset it without reading those migrations first;
`AGENT_GUIDE.md` Phase 7 settles Diligent's version scheme.
| `android/app/src/main/kotlin/com/vscodroid/MainActivity.kt` | Holds a `DiligentController` (with a small `DiligentActivityHost` for opening folders), calls its `onLayoutReady()` after `setContentView`, tells it about the keyboard (`onImeVisibilityChanged`) and the open folder (`rememberWorkspaceFolder`), passes its bridge host to `AndroidBridge`, and one relay branch forwards `diligent*` commands. `recreateWebView` re-adds the WebView to `editorFrame`. Back asks `diligent.onBack()` first. `onConfigurationChanged` tells it the screen changed (big screen suggestion). `onCreate` and `onNewIntent` pass the intent to `diligent.onIntent` (the task notification's Stop, and opening Health) |
| `android/app/src/main/kotlin/com/vscodroid/bridge/AndroidBridge.kt` | `diligent` constructor parameter and the `diligentEditorReady`, `diligentSetView`, `diligentUpdateTasks`, `diligentKitProgress`, `diligentGetHealth` and `diligentInstallToolchain` methods (the last delegates to `installToolchain` with the token first) |
| `android/app/src/test/.../bridge/BridgeTokenUniformityTest.kt` | Watches the `diligent` collaborator |
| `docs/05-API_SPEC.md` | The Diligent relay branch and methods |
| `.github/workflows/lint.yml`, `.github/workflows/release.yml` | Run `scripts/test-diligent-bridge.js` and `scripts/test-diligent-core.js` |
| `android/app/src/main/res/layout/activity_main.xml` | The WebView sits in an `editorFrame` FrameLayout, so Mobile view's native screens can cover it without resizing it |
| `android/app/src/main/AndroidManifest.xml` | Declares `diligent.preview.PreviewActivity` (not exported), Mobile view's preview of the running app, and the `WAKE_LOCK` permission `diligent/TaskKeeper.kt` holds while a task runs |
| `android/app/src/main/kotlin/com/vscodroid/service/NodeService.kt` | Three one-line calls into `diligent/TaskKeeper.kt`: `attach` in `onCreate`, `detach` in `onDestroy`, and `decorate` in `createNotification`, which lists the running tasks with a Stop each on the running card |
