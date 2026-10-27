# Diligent for Android: Build Guide for an AI Coding Agent

**Build order:** this app is built **first**. Then `02-DESKTOP-APP.md`, then `03-IPHONE-APP.md`.

**What this file is:** step-by-step instructions for an AI coding agent (Claude Code, Antigravity) that builds **Diligent**, a phone-first coding app for Android, on top of the open-source app [VSCodroid](https://github.com/rmyndharis/VSCodroid). The agent does **one phase at a time**.

---

## 0. Read this first: what Diligent is for

**Diligent lets people write and run real code on an Android phone, with an interface made for a phone.**

VSCodroid already proved the hard part: the real VS Code editor, Node.js, Python, Git and a terminal all run on the phone, offline. What it lacks is an interface that fits a phone. On a 6-inch touch screen, the desktop VS Code layout is cramped and hard to use. Diligent keeps VSCodroid's working engine and puts a phone-shaped app on top of it.

**The most important feature: the user chooses the view**

| View | What the user sees | Best for |
| --- | --- | --- |
| **Mobile view** | Diligent's own touch-first app: big buttons in thumb reach, a bottom tab bar, a clean editor with no desktop menus or panels | Phones, one-handed use, beginners |
| **Desktop view** | The full VS Code interface, exactly as on a computer | Tablets with a keyboard, Samsung DeX, an external monitor, power users |

- The user picks on first launch and can switch at any time, in under a second, without losing open files, unsaved text or running terminals.
- The phone never forces a view. When it sees DeX or a monitor, it **suggests** Desktop view and lets the user decide.

**The interface is the product.** Mobile view is what makes Diligent different from every other coding app. It will be redesigned and improved many times, so build it to be easy to change: one theme file, one file per screen, small reusable components.

**Who it is for**

1. The owner (first user): an Android phone, an iPhone and a Mac.
2. Developers with a laptop who want to keep coding away from the desk.
3. Students whose phone is their only computer.

**Principles every phase follows**

- **Phone first:** every feature must be usable with one thumb in Mobile view.
- **Reuse the engine:** do not rebuild what VSCodroid already does; build our layer on top.
- **Never lose work:** auto-save, snapshots, clear warnings.
- **Works offline:** everything except AI and downloads works without internet.
- **The user's data stays on the user's devices:** no cloud storage, no servers of ours.

**Diligent's family of apps:** this Android app is the full IDE. A desktop companion (`02-DESKTOP-APP.md`) and an iPhone editor (`03-IPHONE-APP.md`) join later. All three talk through one shared protocol, **diligent-link v1** (Appendix A).

---

## 1. How to use this guide (for the human)

1. Do Phase 0 with the agent; it creates the `Diligent` repository.
2. Save this file in the repository root as `AGENT_GUIDE.md` and commit it.
3. Start the agent in the repository and say:
   > Read AGENT_GUIDE.md, starting with section 0. Do Phase N only. Follow every rule in section 3. Stop when Phase N's "Done when" list passes, then report what you changed, with screenshots of any new screens, and how to test it.
4. Install the APK that GitHub builds, try it on your phone, then merge.
5. Only then start the next phase.

## 2. Names and placeholders

| Placeholder | Meaning | Value or example |
| --- | --- | --- |
| `<APP_NAME>` | The app's name | **Diligent** |
| `<APP_ID>` | Android application ID, unique on Google Play | `com.yourname.diligent` (the human chooses) |
| `<GITHUB_USER>` | Your GitHub username | `yourname` |
| `<APPS_REPO>` | Second repository (shared library, desktop, iPhone), created in Phase 5 | `Diligent-Apps` |

Internal code uses the prefix `diligent`: extensions `diligent.*`, Kotlin code under `kotlin/com/vscodroid/diligent/`, project files in `.diligent/`, app data in `~/.diligent/`.

## 3. Rules for the agent (every phase)

1. **Start from section 0.** Every decision must serve the main purpose: a phone-first app with a user-chosen Mobile or Desktop view.
2. **Do only the current phase.** Do not start features from a later phase.
3. **Big phases go in small pull requests:** one pull request per Part (A, B, C...).
4. **Read before you change.** Read `CONTRIBUTING.md` and every file a task names. VSCodroid's code has long comments explaining why it works the way it does; keep that behavior unless the task says otherwise.
5. **Find code by searching for the quoted text,** not by line number. Line numbers in this guide are hints and drift as the code changes.
6. **Choose the lightest layer.** Prefer a JavaScript extension, then Kotlin. Edit VS Code itself (`patches/`) only when a task says so.
7. **Keep our code separate** in new files with the `diligent` prefix, so updates from VSCodroid still merge cleanly.
8. **Build the interface to be redesigned:** colors, type sizes, spacing and shapes come only from `DiligentTheme`; one file per screen; reusable components. No hard-coded colors or sizes in screens.
9. **Keep the license and credit.** VSCodroid is MIT licensed: `LICENSE`, `NOTICE.md`, `licenses/`, `docs/LEGAL_NOTICES.md` and the in-app license screens stay. This is a legal requirement of using its code.
10. **Never download programs at runtime from our own servers.** Languages and tools ship inside the APK or as Google Play asset packs. Downloading npm or pip packages is fine.
11. **Never invent cryptography.** Use the libraries this guide names. If one does not fit, stop and ask.
12. **No fancy dashes.** The repository bans the em dash, en dash and horizontal bar characters. Use commas, colons or the ASCII hyphen.
13. **Test on arm64 only:** a real phone or an arm64 emulator.
14. **Stop and ask** when a task needs a password, a signing key, a decision, or a test on a device you cannot reach. Never guess credentials.
15. **Write it down:** a dated entry in `CHANGELOG-DILIGENT.md` at the end of each phase.

## 4. How we test

VSCodroid's code already works, so testing focuses on **what Diligent adds**.

1. **The new feature:** the phase's "Done when" list, on a real phone.
2. **A two-minute smoke test** that the base still works after our changes: the app opens, the editor edits and saves a file, the terminal runs `node --version`, the Extensions view finds an extension.
3. **The automatic checks GitHub runs** on every pull request. They must pass, or the build is red:
   - `cd android && ./gradlew testDebugUnitTest` and `./gradlew lint`;
   - the `python3 scripts/check-*.py` steps in `.github/workflows/lint.yml`, which also catch Diligent mistakes: hard-coded text in Kotlin (`check-translatable-strings.py`), banned dashes (`check-plain-punctuation.py`), undocumented bridge methods (`check-bridge-api-spec.py`).
4. **Screenshots:** every pull request that adds or changes a screen includes phone screenshots (`adb exec-out screencap -p > screen.png`), so the human can review the design.

## 5. What VSCodroid already provides (do not rebuild it)

- The Code - OSS 1.139.1 editor server (the open-source core of VS Code), running on Node inside the app and shown in a WebView. Base: VSCodroid `main`, version 1.5.0 (versionCode 16) when this guide was written.
- Bundled tools: bash, Git, tmux, make, the OpenSSH client and `ssh-keygen`, Node 24, npm, Python 3 with prebuilt numpy, pandas and psutil.
- On-demand language packs for Ruby, Java 17 and C/C++: Google Play asset packs on Play installs, downloads from GitHub Releases on sideloaded installs.
- A foreground service that restarts the editor server up to 5 times with backoff and recognizes kills by Android (exit code 137).
- A process monitor that counts the app's processes.
- An Android bridge already exposing storage breakdown, cache clearing, free space, toolchain list/install/removal, SSH key generation and listing, crash reports, clipboard, folder picker and the extra key row.
- The extra key row, cursor trackpad, Claude Code support, GitHub Copilot Chat and the Open VSX extension store.

## 6. Repository map

**Two repositories in total:**

1. **`Diligent`**: a clone of VSCodroid with our work on top (this app). Created in Phase 0.
2. **`<APPS_REPO>`**: created in Phase 5, holding `sync-core/` (shared Kotlin Multiplatform library for diligent-link), `desktop/` and `ios/`. `Diligent` includes `sync-core` as a Git submodule at `android/diligent-sync-core`.

**Key files** (search for the quoted text to find the exact spot)

| Path | What it is | When you touch it |
| --- | --- | --- |
| `android/app/build.gradle.kts` | Application ID, `versionCode` and `versionName`, dependencies | Phase 0, Phase 1, releases |
| `android/app/src/main/AndroidManifest.xml` | Activities, services, permissions | New screens, services, permissions |
| `.../kotlin/com/vscodroid/MainActivity.kt` | Hosts the WebView; `injectBridgeRelay` connects web code to Kotlin; the About link (search `github.com/rmyndharis/VSCodroid`) | Phase 1, carefully |
| `.../SplashActivity.kt`, `.../setup/FirstRunSetup.kt` | First-run setup; default editor settings (search `"editor.minimap.enabled" to`); `.bashrc` creation (search `createBashrc`) | Phase 1, Phase 2, Phase 3 |
| `.../service/NodeService.kt`, `.../service/ProcessManager.kt` | Editor server lifecycle; launch arguments (search `"--host=127.0.0.1"`) | Phase 2, Phase 4 |
| `.../bridge/AndroidBridge.kt` | Methods the web side can call | When a feature needs Android |
| `.../keyboard/` | Extra key row (`KeyPageConfig.kt` lists the keys), trackpad | Phase 1, Phase 4 |
| `.../setup/ToolchainRegistry.kt`, `ToolchainManager.kt` | Language packs | Phase 3, Phase 6, Phase 7 |
| `android/app/src/main/assets/server.js` | Starts the editor server; access token handling | Phase 4 |
| `android/app/src/main/assets/process-monitor.js` | Process count snapshot | Phase 2 |
| `android/app/src/main/assets/extensions/` | Bundled extensions (`vscodroid.*`); ours go here as `diligent.*` | Every phase |
| `android/app/src/main/res/values/strings.xml` | Every user-facing string in Kotlin | Every phase |
| `branding/product.json` | Editor names, applied when CI builds the editor server | Phase 0 |
| `patches/` | VS Code source patches | Avoid |
| `.github/workflows/` | `build.yml` (debug APK), `codeoss-build.yml` (editor server, cached), `lint.yml`, `release.yml` | Phase 0, Phase 7 |
| `docs/05-API_SPEC.md`, `docs/USER_GUIDE.md` | Bridge contract, user guide | When behavior changes |

## 7. Facts that prevent wasted hours

- **Two kinds of extension code.** Code that calls the Android bridge runs in the web extension host (a `browser` entry in `package.json`) and talks over the `BroadcastChannel('vscodroid-bridge')` relay that `MainActivity.injectBridgeRelay` opens. Code that runs processes (`child_process`, files, sockets) runs in the Node extension host (a `main` entry). Commands are shared across hosts: the Node side calls `vscode.commands.executeCommand('diligent.bridge.<name>', ...)` and the web side does the bridge call.
- **A new bridge method has three parts:** the method in `AndroidBridge.kt`, a branch in the relay in `MainActivity.injectBridgeRelay`, and an entry in `docs/05-API_SPEC.md`.
- **User-facing text in Kotlin is always a string resource.** Extensions put their text in `package.nls.json`.
- **Android will not run a file from app storage by its path.** Start tools by name (`node`, `python3`, `bash script.sh`).
- **The debug build's package is `<APP_ID>.debug`**, so it installs next to the release build and next to VSCodroid.
- **Bundled extensions are plain JavaScript (CommonJS) with `// @ts-check`,** no bundler and no npm dependencies, like VSCodroid's own. Folder pattern: `publisher.name-version`, for example `diligent.diligent-core-0.1.0`.
- **The editor's access token** lives in `<server-data-dir>/data/token` (see the comment above `serverArgs` in `server.js`). Opening the editor with `?tkn=<token>` once sets a cookie for later requests.

## 8. Technology list

| Area | Technology | Detail | Used for | Phase |
| --- | --- | --- | --- | --- |
| Language | Kotlin | As pinned in the repo | Android screens, services, `sync-core` | All |
| Language | JavaScript with `// @ts-check` | No bundler | `diligent.*` extensions | All |
| Language | Bash 3.2 | | Scripts, the `kit` command | 0, 3 |
| Android | Android SDK | compileSdk 37, targetSdk 36, minSdk 30 (Android 11) | Building | 0 |
| Android | Gradle wrapper, Android Gradle Plugin 9.3.1 | `./gradlew` | Building | 0 |
| Android | JDK 17, NDK, CMake | NDK 27.2.12479018 in CI | Building | 0 |
| Android | Jetpack Compose, Material 3 | New dependency | Mobile view screens | 1 |
| Android | `androidx.window` | New dependency | Screen size for the view suggestion | 1 |
| Android | Play Asset Delivery | Already present | Language packs | 3, 6 |
| Android | Foreground service (`specialUse`) | Already present | Keeping tasks alive | 2 |
| Android | `NsdManager` (DNS-SD) | Android SDK | Finding nearby devices | 5 |
| Android | Google Nearby Connections | `play-services-nearby`, new | Phone to phone over Bluetooth or Wi-Fi Direct | 5 (Part F) |
| Shared | Kotlin Multiplatform | `sync-core` in `<APPS_REPO>` | diligent-link v1 | 5 |
| Crypto | BouncyCastle | New dependency | Device certificate, SRP-6a pairing | 5 |
| Editor | Code - OSS 1.139.1, VS Code extension API | `engines.vscode` `^1.96.0` | Editor, Desktop view, extensions | All |
| On device | Node 24, npm, Python 3.14, Git, tmux, OpenSSH | Bundled | Running projects, snapshots, sessions | All |
| Network | Tailscale | User's own app, optional | Devices on other networks | 4, 5 |
| AI | Claude Code, Antigravity CLI (`agy`), Copilot Chat | User's own accounts | Agents and chat | 4 |
| AI | Ollama, OpenAI-compatible APIs, Anthropic, Gemini, Mistral Codestral | User's own laptop or key | Suggestions while typing | 4 |
| CI | GitHub Actions | Existing workflows | Builds and checks | 0 |
| Release | GitHub Releases, Google Play Console | Play: one-time 25 USD | Distribution | 7 |

## 9. Phase overview

| Phase | Goal | Needs |
| --- | --- | --- |
| 0 | Clone VSCodroid into `Diligent`, rename, build on GitHub | Nothing |
| 1 | **The view switch and Mobile view (the core of Diligent)** | 0 |
| 2 | Reliability: tasks that survive, snapshots, health check | 1 |
| 3 | Setup center: install languages and start projects in one tap (`kit`) | 2 |
| 4 | Run on laptop, AI agents, AI suggestions, sharing | 3 |
| 5 | Devices, code pairing and sync | 4 |
| 6 | Any phone, offline, beginner mode | 5 |
| 7 | Release | 6 |

The desktop app can start once Phase 5 is done, because it only needs `sync-core`. The iPhone app comes last.

---

## Phase 0: Clone, rename and build

**Goal:** a `Diligent` repository on your GitHub, built from VSCodroid's code, showing the name Diligent, built by GitHub Actions and installed on your phone. No new features.

**The human does first**

- [ ] Create an **empty** repository named `Diligent` on GitHub (no README, no license). **Public** is recommended: GitHub Actions is free and unlimited for public repositories, while private ones get 2,000 free minutes a month and the first build is long.
- [ ] Choose `<APP_ID>` and write it into section 2.
- [ ] Optional: put a 1024 x 1024 PNG logo at `branding-source/logo.png`.

**Tasks**

1. **Clone and push.** Keep VSCodroid's history; it makes future updates easy to merge.
   ```bash
   git clone https://github.com/rmyndharis/VSCodroid.git Diligent
   cd Diligent
   git remote rename origin upstream
   git remote add origin https://github.com/<GITHUB_USER>/Diligent.git
   git push -u origin main
   ```
2. **Upstream notes.** Create `UPSTREAM.md` explaining that Diligent is built on VSCodroid and how to pull its updates (`git fetch upstream && git merge upstream/main`). Create `CHANGELOG-DILIGENT.md`.
3. **Application ID.** In `android/app/build.gradle.kts`, set `applicationId = "<APP_ID>"`. Leave `namespace = "com.vscodroid"` and every Kotlin package unchanged: they are internal, users never see them, and renaming them would make every update from VSCodroid conflict.
4. **Find runtime uses of the old ID.** Run `grep -rn "com\.vscodroid" --include=*.kt --include=*.xml --include=*.kts --include=*.sh --include=*.py --include=*.js --include=*.yml .` and sort each hit:
   - Kotlin package or namespace: leave it.
   - The app's ID used at runtime (provider authorities, intents, `run-as`, `pm clear`, `PKG` in `scripts/deploy.sh`): use `${applicationId}` in XML, `BuildConfig.APPLICATION_ID` or `context.packageName` in Kotlin, a variable in scripts.
   - Documentation: update only where users read it.
5. **Name: Diligent.** Set `app_name` to `Diligent` in `res/values/strings.xml` and every `res/values-*/strings.xml`. Replace other user-visible "VSCodroid" text. Keep technical identifiers: `.vscodroid` folder names, the `vscodroid-bridge` channel, `vscodroid.*` extension IDs.
6. **Editor name.** In `branding/product.json`, under `set`, change only `nameShort` and `nameLong` to `Diligent`, `reportIssueUrl` to `https://github.com/<GITHUB_USER>/Diligent/issues/new`, and `licenseUrl` and `serverLicenseUrl` to the repository's LICENSE. Do not change `applicationName`, `dataFolderName`, `serverApplicationName`, `serverDataFolderName`, `sharedDataFolderName` or `urlProtocol`. This change makes CI rebuild the editor server, which can take up to two hours the first time.
7. **Icon.** If `branding-source/logo.png` exists, read `scripts/generate-branding-icons.py` and use it. If not, keep the current icons and note in `CHANGELOG-DILIGENT.md` that a Diligent icon is required before release.
8. **Credit.** Keep the existing copyright line in `LICENSE` and add a line for Diligent below it. In the About dialog, add: "Diligent is built on VSCodroid by Yudhi Armyndharis and the VSCodroid Community (MIT License)."
9. **Baseline.** Create `docs/diligent/BASELINE.md` with a table the human fills in: download size, storage after first setup, cold start to editor ready (median of 10 launches), idle memory.
10. **Build.** Open a pull request from `phase-0-diligent`. Wait for the Build and Lint workflows; fix anything they flag.

**Done when**

- [ ] `github.com/<GITHUB_USER>/Diligent` holds the code, with `upstream` pointing to VSCodroid.
- [ ] Build and Lint are green on `main`.
- [ ] The `debug-apk` artifact installs next to VSCodroid and shows "Diligent" under its icon, in the editor title and in the About dialog, with the VSCodroid credit.
- [ ] The smoke test (section 4) passes.

**Do not:** rename Kotlin packages, data folders or extension IDs; touch `patches/`; remove the license or credit.

---

## Phase 1: The view switch and Mobile view (the core of Diligent)

**Goal:** the user chooses between **Mobile view** (Diligent's touch-first app) and **Desktop view** (full VS Code), and Mobile view is pleasant to use one-handed on a phone. This is the most important phase; take the time to make it feel good.

**Read first:** section 0 again; `MainActivity.kt` (layout, WebView, `injectBridgeRelay`, back handling); `keyboard/KeyPageConfig.kt`; the default settings map in `FirstRunSetup.kt`.

### Part A: foundations

1. **Dependencies:** Jetpack Compose (through the Compose BOM), Material 3 and `androidx.window`, in `android/gradle/libs.versions.toml` and `android/app/build.gradle.kts`, with the Kotlin Compose compiler plugin matching the Kotlin version the Android Gradle Plugin uses. New screens are Compose inside the existing activities.
2. **Theme:** `diligent/ui/theme/DiligentTheme.kt` with color tokens (light and dark, following the system), typography (Material 3 scale), an 8 dp spacing scale and shapes. Screens use only these tokens.
3. **Structure:** `diligent/ui/screens/` (one file per screen), `diligent/ui/components/` (shared pieces such as the action bar, project card, file row), `diligent/ui/navigation/`. Every screen and component has a Compose `@Preview`, so the design can be improved quickly later.
4. **Kotlin to editor messages:** Kotlin runs `evaluateJavascript` to post `{cmd, args}` on `BroadcastChannel('diligent-events')`. A new web extension, `diligent.diligent-bridge`, runs `vscode.commands.executeCommand(cmd, ...args)` for commands on an allowlist only, because any web extension can post on the channel. The same extension registers `diligent.bridge.*` commands for calls the other way.

### Part B: the view switch

1. **View setting** (stored per device): `mobile` or `desktop`, plus an option "Suggest Desktop view on big screens" (on by default).
2. **First launch:** after VSCodroid's first-run setup, show "How do you want to code?" with two large cards:
   - **Mobile view**, recommended for phones: touch-first, simple, one-handed.
   - **Desktop view**: the full VS Code interface, best with a keyboard, a tablet, DeX or a monitor.

   Below them: "You can switch at any time."
3. **Switching from Mobile view:** a "Desktop view" item in the top bar menu and in Settings.
4. **Switching from Desktop view:** a status bar item "Mobile view" and a command "Diligent: Switch to Mobile view", both contributed by `diligent-bridge`.
5. **Switching never reloads.** Both views use the same WebView and editor: the switch changes the native screens around it and the editor's layout settings. Open files, unsaved text, terminals and running tasks stay exactly as they were. Target: under one second.
6. **Editor layout per view.** When entering Mobile view, `diligent-bridge` saves the user's current values in `globalState`, then sets `workbench.activityBar.location` = `hidden`, `workbench.statusBar.visible` = `false`, `workbench.editor.showTabs` = `single`, `breadcrumbs.enabled` = `false`, `editor.fontSize` = `15`, and runs `workbench.action.closeSidebar`, `workbench.action.closePanel` and `workbench.action.closeAuxiliaryBar`. Entering Desktop view restores the saved values. If an element cannot be hidden with settings or commands, stop and ask before touching `patches/`.
7. **Big screen suggestion** (`diligent/ViewAdvisor.kt`): when the window becomes at least 840 dp wide (`WindowMetricsCalculator`), Samsung DeX turns on (compare the `Configuration` fields `semDesktopModeEnabled` and `SEM_DESKTOP_MODE_ENABLED` by reflection; any exception means "not DeX"), or the app moves to an external display, show a small prompt "Switch to Desktop view?" with Switch and Not now. Never switch without the user. When the big screen goes away, offer to switch back.

### Part C: Mobile view screens

**Layout:** a top bar (project name, run status, menu) that hides while typing, and a bottom tab bar with five tabs. Main actions sit in the bottom half of the screen. Touch targets are at least 48 dp. Portrait and landscape both work.

1. **Projects (home):** recent projects as cards (name, language, last opened), plus three big actions: New project (opens Setup in Phase 3; until then, an empty folder), **Clone from Git** (URL field, runs `git clone` into `~/projects`), and Open folder.
2. **Files:** a native file tree of the open project: open, create, rename, move, delete (asks first). Tapping a file sends `vscode.open` and switches to Editor.
3. **Editor:** the WebView in the Mobile view layout. Above the keyboard, the existing extra key row. Below the editor, an action bar: Run or Stop, Preview, Save, Search, Command palette, Undo.
4. **Terminal:** the same WebView with the integrated terminal maximized (`workbench.action.terminal.focus`, then maximize the panel). No native terminal.
5. **More:** Run, Setup, AI, Devices, Health, Settings. Items for later phases show "Coming soon" until their phase is built.
6. **Run (basic):** project run profiles in `.diligent/run.json`:
   ```json
   { "profiles": [ { "name": "dev", "command": "npm run dev", "port": 3000, "where": "phone", "env": {} } ] }
   ```
   When missing, detect one (a `dev` or `start` script in `package.json`; Next.js or Vite, port 3000 or 5173; `main.py` or `app.py`) and write it only after the user accepts. Start and Stop run the command in a tmux session `diligent-<project>-<profile>` with its log in `~/.diligent/logs/`. The screen shows status and the last 200 log lines. Turbopack does not run on Android, so a Next.js `dev` script runs as `npm run dev -- --webpack`, with a one-time note saying why.
7. **Preview:** `PreviewActivity`, a WebView on `http://localhost:<port>`, with Reload and Open in browser.
8. **Tablets in Mobile view:** from 600 dp wide, editor and preview side by side.
9. **Back button:** in native tabs, Back goes to the previous tab; in Editor and Terminal, keep the existing behavior.

**Done when**

- [ ] First launch asks for a view; the choice is remembered.
- [ ] Switching both ways takes under a second and keeps an unsaved edit and a running terminal command intact.
- [ ] Connecting to DeX or a monitor shows the suggestion; nothing switches without the user.
- [ ] In Mobile view on a phone: clone a GitHub repository, open a file from Files, edit and save it, run a Next.js project from Run, see it in Preview, all one-handed.
- [ ] Every screen uses `DiligentTheme` only and has a `@Preview`; the pull request includes screenshots of every screen in light and dark.
- [ ] The smoke test and GitHub checks pass.

**Do not:** build a native code editor or native terminal; change how Desktop view looks beyond restoring the user's settings.

---

## Phase 2: Reliability

**Goal:** what the user starts keeps running, comes back by itself when Android kills it, and their work is never lost. This upgrades the Phase 1 Run screen; it adds no new tabs.

**Read first:** `service/NodeService.kt`, `service/ProcessManager.kt`, `assets/process-monitor.js`, `bridge/AndroidBridge.kt`, `docs/05-API_SPEC.md`, and `assets/extensions/vscodroid.vscodroid-process-monitor-1.4.0/` as the model bundled extension.

**New pieces**

| Piece | Where | Runs in |
| --- | --- | --- |
| `diligent.diligent-core` | `assets/extensions/diligent.diligent-core-0.1.0/` | Node extension host: supervisor, snapshots |
| Health screen | `diligent/ui/screens/HealthScreen.kt` | Android |
| Task notification and wake lock | `diligent/TaskKeeper.kt` | Android |

**Tasks**

1. **Supervisor.** Wrap every run profile so the log ends with `DILIGENT_EXIT:<code>` when the command ends.
   - Every 10 seconds: is the tmux session alive, and does the port accept a TCP connection?
   - On failure, restart after 1 s, then 5 s, then 30 s; stop after 5 restarts within 10 minutes.
   - Name the cause: exit 137 with low `MemAvailable` in `/proc/meminfo` = low memory; process count near 32 = Android's background process limit; `EADDRINUSE` = busy port; vanished session = killed by Android. Otherwise show the exit code and the last 20 log lines.
   - After an editor server restart, re-attach to running sessions instead of starting duplicates.
2. **Process budget.** Read the snapshot `process-monitor.js` writes (its path is in that file) and warn at 24 processes. Do not kill language servers; that file's comments explain why it fails.
3. **Task notification and wake lock.** New bridge method `diligentUpdateTasks(authToken, json)`. `TaskKeeper` lists running tasks in the existing foreground notification with a Stop action, and holds a partial wake lock only while a task runs, renewed hourly, released when none run.
4. **Health screen** (More, then Health): each check shows pass, fail or "cannot check", with a Fix button.
   - Battery: `PowerManager.isIgnoringBatteryOptimizations`. Fix opens `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`. Do not add the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission; Google Play restricts it.
   - Background process limit, Android 14+: try `Settings.Global` key `settings_enable_monitor_phantom_procs`; `false` passes. If it cannot be read, show the steps: Developer options, "Disable child process restrictions". Verify on a real Android 14+ phone which of the two happens.
   - Background process limit, Android 12 and 13: these commands with copy buttons, run over Wireless debugging: `adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent"`, then `adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"`, then (12L and 13 only) `adb shell settings put global settings_enable_monitor_phantom_procs false`.
   - Notifications allowed (Android 13+), free storage at least 1 GB, process count below 24.
   - Opened from More, from the notification after a kill, and once after first-run setup.
5. **Snapshots** on ref `refs/diligent/snapshots`, without touching the user's branch, index or files:
   ```bash
   export GIT_INDEX_FILE=.git/diligent-index
   git add -A && tree=$(git write-tree)
   commit=$(git commit-tree "$tree" -p refs/diligent/snapshots -m "diligent: <reason>")
   git update-ref refs/diligent/snapshots "$commit"
   ```
   Omit `-p` for the first snapshot. If the project is not a Git repository, ask once before `git init`. Respect `.gitignore`. Take one every 10 minutes while files are saved, and expose `diligent.snapshot.take(reason)` for later phases. The Undo action from Phase 1 opens a snapshot list (time and reason); restoring takes a "before restore" snapshot, then runs `git checkout <commit> -- .`. Keep the newest 100.
6. **Auto-save default:** in the settings map in `FirstRunSetup.kt`, `files.autoSave` = `afterDelay` and `files.autoSaveDelay` = `1000`, for new installs.
7. **Tests:** pure logic (detection, cause naming, backoff, snapshot commands) tested by `node scripts/test-diligent-core.js`, following `scripts/test-process-monitor.js`, added to `lint.yml`; JUnit tests for the health decisions.

**Done when**

- [ ] `pkill -9 -f next` brings a running Next.js project back within 30 seconds, with a message naming the cause.
- [ ] Five failures in ten minutes stop it, with an explanation and a link to Health.
- [ ] A dev server survives two hours with the screen off, or recovers by itself.
- [ ] Edit, snapshot, break the file, Undo: the content is back and the user's Git branch is untouched.
- [ ] The smoke test and GitHub checks pass.

---

## Phase 3: Setup center (`kit`)

**Goal:** a new user installs what they need and starts a working project with one tap in Mobile view, or one command in the terminal, without researching how to make it work on Android.

**Read first:** `setup/ToolchainManager.kt`, `setup/ToolchainRegistry.kt`, and the `.bashrc` creation in `FirstRunSetup.kt` (search `createBashrc`).

1. **Recipes** are JSON in `assets/extensions/diligent.diligent-kit-0.1.2/recipes/<id>.json`: `id`, `title`, `summary`, `needs` (`toolchains`, `minFreeMB`, `internet`), `tested` (versions last tested) and ordered `steps`. Step types: `toolchain` (install a pack through the existing bridge method `installToolchain`), `run` (a shell command in the project folder), `write` (a file from a template), `profile` (write `.diligent/run.json`), `test` (start the profile, wait for the port or an expected output line, stop it).
2. **First recipes**

   | ID | Creates | Internet |
   | --- | --- | --- |
   | `web-basic` | HTML, CSS, JS, served by `python3 -m http.server` | No |
   | `react-vite` | React app with Vite 8 | Once |
   | `nextjs` | Next.js app, run with `--webpack` | Once |
   | `node-api` | Express API | Once |
   | `python-script` | Python script with a virtual environment | No |
   | `python-data` | Python with numpy and pandas, plus a notebook | No, if bundled packages cover it |
   | `java` | Hello World with the Java pack | Pack download |
   | `c-cpp` | Hello World with the C/C++ pack | Pack download |
   | `ruby` | Hello World with the Ruby pack | Pack download |
3. **One engine, two front ends.** The engine lives in `diligent.diligent-kit` (Node extension host) and listens on a Unix socket at `~/.diligent/kit.sock`; the terminal command and the Setup screen both use it.
4. **The `kit` command:** add `kit() { node "$HOME/.diligent/kit/kit.js" "$@"; }` where `createBashrc` writes `.bashrc`. Commands: `kit list`, `kit add <recipe> [name]` (one line per step with `[ok]` or `[fail]`, then "Ready" and how to run it), `kit remove <pack>`, `kit doctor` (tool versions, packs, storage, health checks through new bridge method `diligentGetHealth`), `kit help`.
5. **Setup screen** (Mobile view, from New project and More): "What do you want to build?" recipe cards (title, summary, size, internet badge), a confirm sheet (what gets installed, space needed), live progress (new bridge method `diligentKitProgress`), and a result with Open project and Run.
6. **Fail early and clearly:** check `needs` before the first step and say exactly what is missing.

**Done when**

- [ ] New project, then "Next.js app", ends with a running app in Preview, with live progress on the way.
- [ ] `kit add nextjs my-app` in the terminal does the same.
- [ ] `kit add web-basic` and `kit add python-script` work in flight mode.
- [ ] `kit doctor` prints every check; the smoke test and GitHub checks pass.

---

## Phase 4: Run on laptop, AI agents, AI suggestions and sharing

**Goal:** heavy work runs on the user's laptop with one tap, AI agents keep running in the background, AI suggestions appear while typing, and the editor and previews open on other devices over Tailscale.

**Read first:** the SSH key methods in `AndroidBridge.kt`, the SSH and Claude Code sections of `docs/USER_GUIDE.md`, the server arguments in `ProcessManager.kt`, and the token comment above `serverArgs` in `assets/server.js`.

### Part A: run on laptop (SSH)

This works with any laptop. Once the desktop app is paired (Phase 5), "Run on laptop" uses diligent-link instead and needs no SSH setup.

1. **Laptop screen** (More, then Laptop): what the laptop needs (SSH on: macOS System Settings, General, Sharing, Remote Login; Linux `openssh-server`), its address (Wi-Fi IP or Tailscale name), the username. Create or reuse the phone's ed25519 key through the bridge; show the public key with Copy and the laptop command `mkdir -p ~/.ssh && echo '<key>' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys`. Test with `ssh -o BatchMode=yes -o ConnectTimeout=8 -o StrictHostKeyChecking=accept-new <user>@<host> 'uname -sm; command -v node git'`. Save machines in `~/.diligent/machines.json`. macOS and Linux now; Windows later.
2. **Run on laptop:** a profile with `"where": "laptop:<name>"` takes a snapshot; syncs by Git (bare `~/diligent-sync/<project>.git` and work folder `~/diligent-work/<project>` on the laptop; push `refs/diligent/snapshots` to `refs/heads/diligent-sync` with `--force`; check out with `-f`); runs the optional `prepare` command when the lock file changed; starts `ssh -tt -L <localPort>:localhost:<port> <user>@<host> "cd ~/diligent-work/<project> && <command>"` in a local tmux session under the supervisor; opens Preview on `http://localhost:<localPort>`. Unreachable laptop: "Can't reach <name>. Is it awake and on the same network or Tailscale?"
3. **Offload hint:** after two low-memory kills, or a step over 3 minutes, with a laptop paired, offer "Run on <name> instead".

### Part B: AI agent sessions

1. **AI screen** (More, then AI): Claude Code (`claude`), Antigravity CLI (`agy`), Copilot Chat (opens VS Code's chat view).
2. **Installing:** follow VSCodroid's user guide for Claude Code; Diligent does not redistribute the Claude Code CLI. For `agy`, show Google's official install command for the user to run; never run installers automatically. Test whether `agy` runs in the terminal and record the result.
3. **Sessions** run in tmux as `diligent-ai-<agent>-<project>` after a snapshot, with Attach, Stop, and "Undo changes since this session started".
4. **Status:** installed or not, version. For sign-in, only a status command the tool documents. Never read, copy or upload credential files.

### Part C: sharing to other devices (Tailscale)

1. **Tailscale address:** the IPv4 address in `100.64.0.0/10`; if none, "Install Tailscale and sign in" with a Play link.
2. **Share editor:** `diligent/ShareProxy.kt` binds only to the Tailscale address and forwards to `127.0.0.1:<editor port>`. Show `http://<tailscale-ip>:<port>/?tkn=<token>` (token from `<server-data-dir>/data/token`) as text and QR code. If `server.js` refuses another host, adjust it narrowly for the Tailscale address only.
3. **Share preview:** the same for a running profile's port.
4. **Safety:** off by default, never at boot, a "Sharing on" notification with Stop, auto-off after 60 minutes without traffic.

### Part D: AI suggestions while typing

1. **Extension** `diligent.diligent-ai` (Node extension host) registers a `vscode.InlineCompletionItemProvider` for all languages. It works in both views.
2. **Where the AI runs** (setting `diligent.ai.provider`, default `off`):

   | Option | How | Notes |
   | --- | --- | --- |
   | `laptop` | Ollama on the user's laptop: `POST /api/generate` with `prompt` (code before the cursor) and `suffix` (code after) for fill-in-the-middle models | Best through the desktop app's AI relay (Phase 5, Part G). Without it, the user types a URL; warn that Ollama only listens to its own computer by default, and that opening it (`OLLAMA_HOST=0.0.0.0`) exposes it to everyone on that Wi-Fi |
   | `apikey` | The user's own key: an OpenAI-compatible endpoint (covers Mistral Codestral's FIM, OpenAI and others), Anthropic or Gemini | Keys in VS Code `SecretStorage` (`context.secrets`), never in settings files |
   | `phone` | A small model on the phone | Later; out of scope |
   | `off` | No suggestions | Default |
3. **Phone rules (required)**
   - **Accept and Next** in the extra key row and the Mobile view action bar: Accept runs `editor.action.inlineSuggest.commit`, Next runs `editor.action.inlineSuggest.showNext`. If the key row can only send keys, send Tab while a suggestion is visible, and Alt+] for Next.
   - **Ask only after typing pauses for 500 ms;** cancel the request in flight when the user types again.
   - **"Suggestions only on Wi-Fi"**, on by default; new bridge method `diligentNetworkType` returns `wifi`, `cellular` or `none`.
   - **Privacy:** on first use, show which service receives the code (name and host); a status item names the active provider; a per-project off switch (`diligent.ai.enabled`); never send content from `.env*`, `*.pem`, `*.key`, `id_*` or files in `.diligentignore`.
4. **Context:** up to 2,000 characters before the cursor and 500 after, configurable.

**Done when**

- [ ] Your Mac pairs over SSH; a `nextjs` project runs there from the phone with its preview on the phone; a sleeping Mac gives the clear message.
- [ ] A Claude Code session survives 10 minutes of app switching and an editor reload; Undo restores the files from before it.
- [ ] Safari on an iPhone opens the shared editor over Tailscale; a device on the same Wi-Fi without Tailscale cannot connect.
- [ ] With a provider set, suggestions appear only after a pause; Accept and Next work; nothing is sent on mobile data with "only on Wi-Fi" on; a `.env` file never produces a request.
- [ ] The smoke test and GitHub checks pass.

**Do not:** bind anything to `0.0.0.0` or the Wi-Fi address in Part C; store passwords; run installers for the user; send code anywhere before the user picks a provider.

---

## Phase 5: Devices, code pairing and sync

**Goal:** Diligent finds the user's other devices, pairs with them using a code shown on the other device, and syncs project files device to device when the user taps Sync. Nothing is stored in any cloud.

**Read first:** Appendix A completely, `scripts/check-local-network-permission.py`, and the Phase 2 snapshot hook.

### Part A: the shared library `sync-core`

1. **The human creates** an empty public repository `<APPS_REPO>` on GitHub.
2. **Create** `sync-core/` there: a Kotlin Multiplatform library with a `jvm` target (Android and desktop) and `iosArm64` plus `iosSimulatorArm64` targets (iPhone). Copy Appendix A into `sync-core/SPEC.md`; from then on that file is the source of truth for all three apps.
3. **Ports and adapters:** `commonMain` holds pure logic (message framing, pairing state machine, device registry, manifest comparison, conflict rules, sync session, remote run messages). Platform work sits behind interfaces each app implements: `Transport`, `Discovery`, `Identity`, `Srp`, `FileStore`, `Snapshotter`, `Clock`.
4. **Tests in `commonTest`:** framing, the full comparison table in Appendix A, conflict naming, pairing expiry, single use and lockout.
5. **Use it from Diligent:** a Git submodule at `android/diligent-sync-core`, a composite build (`includeBuild`) in `android/settings.gradle.kts`, and `submodules: true` on the checkout steps in the workflows. GitHub's Linux runners cannot compile the iPhone targets, so set `kotlin.native.ignoreDisabledTargets=true`. If the Android app cannot consume the `jvm` target, add an `android` target to `sync-core`.

### Part B: Android adapters

1. **Identity:** the device certificate (ECDSA P-256, self-signed, 20 years) made with BouncyCastle on first launch; private key wrapped with an Android Keystore key; device ID as Appendix A says.
2. **Discovery:** `NsdManager` registers and browses `_diligent._tcp`.
3. **Transport:** TLS 1.3 (`SSLSocket`, `SSLServerSocket`) with a `TrustManager` that accepts any certificate only during pairing and only pinned fingerprints afterwards.
4. **SRP:** BouncyCastle `SRP6Client` and `SRP6Server`, RFC 5054 3072-bit group, SHA-512.
5. **Permissions:** `INTERNET`, `ACCESS_NETWORK_STATE`; handle local network access on newer Android versions the way VSCodroid's check script describes.
6. **Reachability:** Diligent listens for paired devices while the app is open or a task is running. A setting "Stay reachable in the background" keeps listening through the existing foreground service notification. When neither is true, other devices see it as offline.

### Part C: Devices screen (More, then Devices)

```
My devices (paired before)
  Toto's Laptop   Online, Wi-Fi    Last sync: 5 min ago   [Sync]
  Galaxy Tab      Offline          Last sync: yesterday
Nearby now (same network, not paired)
  MacBook-Air                                            [Connect]
[+ Add by QR code or IP address]
```

1. **My devices:** status, connection used, last sync; tap for Sync, Rename, Forget, and "Approval: Always allow / Ask each time".
2. **Nearby now:** unpaired devices advertising `_diligent._tcp`.
3. **Add by QR code or IP address:** the QR code holds address, port and device ID.
4. **Pairing** follows Appendix A: Connect sends a request; the other device shows a 6-digit code and who is asking; the user types it here. Show clear results: success, wrong code (tries left), expired, locked.
5. **Visibility:** "Visible to nearby devices: On / Off / Only while this screen is open", default "Only while this screen is open".
6. **Identity change:** a paired device with a different certificate is blocked with "This device's identity changed. Pair again only if you reinstalled it."

### Part D: Sync

1. **Sync button** in the project menu and on the Devices screen:
   1. Pick a reachable paired device.
   2. Save all open files (`workbench.action.files.saveAll`).
   3. Both sides build manifests; show the plan ("3 files newer here, 1 newer on Laptop, 1 changed on both").
   4. On confirm, both sides snapshot, then transfer as Appendix A describes.
   5. Conflicts open a side-by-side compare screen: keep mine, keep theirs, or keep both.
   6. If `package.json` or a lock file changed, offer `npm install` (or the matching tool).
2. **A new project** arriving from another device asks: "Receive project <name> into ~/projects/<name>?"
3. **Automatic sync (optional, off by default):** per project and device, after saves (30 s quiet) or every 10 minutes; only when reachable; paused while a conflict waits.
4. **Ignore rules** exactly as Appendix A.7 lists.

### Part E: Connections

1. **Setting:** Automatic (default), Wi-Fi or hotspot only, Tailscale only, Phone to phone.
2. **Automatic order:** same network, then phone to phone (Part F, when built and both are Android), then Tailscale (when a `100.64.0.0/10` address exists and the peer's Tailscale address is known), then Bluetooth through Part F.
3. **Show the connection used** ("Connected via Wi-Fi") and a clear reason when nothing works: different networks, a network that blocks devices from seeing each other (common on public Wi-Fi; suggest a phone hotspot), or the other app not reachable.
4. **Tailscale is never required.**

### Part F: Phone to phone (separate pull request, later)

1. Google Nearby Connections (`play-services-nearby`), strategy `P2P_POINT_TO_POINT`, which picks Bluetooth, Bluetooth Low Energy or Wi-Fi Direct by itself.
2. The same TLS session runs over the Nearby byte stream with `SSLEngine`, so pinning works as on Wi-Fi.
3. Request the permissions Nearby Connections lists for each Android version; hide the option without Google Play services.
4. Warn before syncing more than 20 MB over Bluetooth.

### Part G: Remote run and AI relay (both directions)

1. **Host:** accept `run.*` messages (Appendix A.8) from paired devices (for example an iPhone), run them through the supervisor, stream logs, open the preview proxy; respect the approval setting.
2. **Client:** a profile with `"where": "device:<deviceId>"` runs on a paired desktop app through `run.*` messages instead of SSH; logs go to Run; `run.preview` opens in Preview. Keep the SSH path for laptops without the desktop app.
3. **AI relay client:** with provider `laptop` and a paired desktop that answers `ai.info`, request `ai.endpoint` (Appendix A.9) instead of a typed URL.

Items 2 and 3 can only be tested once the desktop app reaches its Phases 3 and 4.

**Done when**

- [ ] Two Android phones on the same Wi-Fi pair with a code and list each other under My devices.
- [ ] Five wrong codes lock pairing; an expired code is refused; a reinstalled device triggers the identity warning.
- [ ] Sync shows the plan, transfers both ways, produces a conflict when both changed a file, and Undo restores the snapshot.
- [ ] Sync works over a phone hotspot with mobile data off, and over Tailscale across two networks.
- [ ] Ignored folders never transfer; `.env` only when allowed.
- [ ] `sync-core` tests, the smoke test and GitHub checks pass.

**Do not:** invent cryptography; store anything on a server; sync without the user's tap unless automatic sync is on for that project and device.

---

## Phase 6: Any phone, offline and beginner mode

**Goal:** Diligent works well on a 4 GB phone, works without internet for the basics, and a beginner reaches a running web page in five minutes without help.

**Read first:** `setup/ToolchainManager.kt`, `setup/ToolchainRegistry.kt`, `android/toolchain_ruby/` (model pack), `scripts/package-toolchains.sh`, `scripts/check-bundle-size.py`, and the storage methods in `AndroidBridge.kt`.

### Part A: lighter, and offline

1. **Measure first** into `docs/diligent/BASELINE.md`: download size, folder sizes under `assets/` and `assets/usr/`, storage after setup, idle memory.
2. **Python as an on-demand pack, only if it pays:** if Python and its packages are at least 15% of the base download, move them into `toolchain_python`, built like `toolchain_ruby` (Gradle module, `ToolchainRegistry` entry, `package-toolchains.sh`, `release.yml`, `ToolchainRegistryTest`); recipes needing Python gain a `toolchain` step. If Python is tied into too many places, stop and ask.
3. **Low-memory mode:** on by default at 4 GB RAM or less, switchable. Sets `files.watcherExclude` for `**/node_modules/**` and `**/.git/objects/**`, `git.autorefresh` = `false`, `extensions.autoCheckUpdates` = `false`, a lower `typescript.tsserver.maxTsServerMemory`; disables the tablet split view; warns before a run with under 500 MB free memory.
4. **Storage screen** (More, then Storage): existing bridge methods plus per-project `node_modules`, npm and pip caches, snapshots beyond the newest 100, logs older than 7 days. Every Delete shows the size and asks first. Never delete source files.
5. **Offline kit:** `kit prepare <recipe>` runs a recipe once in a temporary folder to fill the npm cache, so a later `kit add` works offline with `--prefer-offline`. Setup marks recipes ready offline.

### Part B: beginner mode

1. **Guided start,** once after the view choice and from New project: what to make (website, React app, Python, "I'm new to coding"), a name, confirm. Runs the recipe, opens the project, shows a "Next step" card.
2. **Beginner mode:** on when the user picks "I'm new to coding"; short tips on each screen; advanced items move under More, then Advanced.
3. **Plain-language errors:** catalog `assets/extensions/diligent.diligent-kit-0.1.2/errors.json` (pattern, title, plain explanation, optional fix), applied to run logs, plus "Explain this error" for selected text:

   | Pattern | Plain explanation | Fix |
   | --- | --- | --- |
   | `EADDRINUSE` | Another program already uses this port | Stop the other task |
   | `ENOENT` with `package.json` | You are not inside a project folder | Open the project |
   | `command not found: node` or `python3` | That language is not installed | Run the matching recipe |
   | `ENOTFOUND`, `ECONNREFUSED` with a registry address | No internet connection | Connect, or use an offline recipe |
   | `EACCES` or `Permission denied` when a package runs a program | Android blocks programs a package downloaded | Explain; suggest an alternative such as Vite 8 |
   | `ModuleNotFoundError` | A Python package is missing | `pip install` in the project's environment |
   | `SyntaxError` with file and line | A typing mistake | Open the file at that line |
   | Exit code 137 or `signal 9` | Android stopped the program | Open Health |
   | `ENOSPC` | The phone is out of storage | Open Storage |
   | `JavaScript heap out of memory` | Not enough memory | Low-memory mode, or run on laptop |
   | Turbopack error | Turbopack does not run on Android | Run with `--webpack` |
   | `Please tell me who you are` | Git needs your name and email | Ask for both, run `git config` |
4. **Share a project:** export a zip without `node_modules` through the Android share sheet; import a zip into `~/projects`.

**Done when**

- [ ] On a 4 GB phone with Android 11 or 12: setup completes, `web-basic` and `python-script` run, and a two-hour soak passes.
- [ ] Sizes are measured against the baseline; the Python pack decision is recorded with numbers.
- [ ] In flight mode, `web-basic`, `python-script` and a prepared `nextjs` recipe create and run.
- [ ] Five beginners each reach a running web page within five minutes, unaided.
- [ ] Every error entry has a test in `scripts/test-diligent-kit.js`; the smoke test and GitHub checks pass.

---

## Phase 7: Release

**Goal:** a signed Diligent anyone can download from your GitHub Releases page, then on Google Play after closed testing.

**Read first:** `.github/workflows/release.yml`, the release sections of `CONTRIBUTING.md`, `docs/PRIVACY_POLICY.md`, `docs/LEGAL_NOTICES.md`, `NOTICE.md`, the `downloadUrl` entries in `setup/ToolchainRegistry.kt`, and the versionCode migrations in `setup/FirstRunSetup.kt`.

**The human does first**

- [ ] Create a signing key on the Mac and back it up: `keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias diligent`. Losing it means Diligent can never be updated.
- [ ] Add the secrets `release.yml` expects to the repository's GitHub settings (the agent lists the names). Never paste a secret into a chat.
- [ ] Check that "Diligent" is free to use as an app name where you will publish it (other companies and apps use the word).
- [ ] Create a Google Play Console account (one-time 25 USD) and complete identity verification.
- [ ] Find at least 12 testers with Android phones.

**Tasks**

1. **Secrets list:** read `release.yml` and `build.gradle.kts`; tell the human the exact names; wait until they confirm.
2. **Final branding:** the Diligent icon; no user-visible "VSCodroid" left except the credit, including the bundled `vscodroid-welcome` extension's text and the store text.
3. **Links:** the About link goes to `github.com/<GITHUB_USER>/Diligent`, with the VSCodroid credit link beside it. `downloadUrl` entries in `ToolchainRegistry.kt` point to `https://github.com/<GITHUB_USER>/Diligent/releases/latest/download/toolchain_<name>.zip` after confirming `release.yml` uploads those zips. Also check `release.yml` and the scripts for any other hard-coded `rmyndharis/VSCodroid` that Diligent's releases must not depend on.
4. **Version scheme:** `FirstRunSetup.kt` runs migrations by comparing versionCodes, so do not reset or offset versionCode casually. Read the migrations, propose a scheme for Diligent releases and VSCodroid updates, and wait for approval.
5. **Copilot decision:** summarize GitHub's terms for the bundled Copilot CLI (`NOTICE.md`). If the human removes it, remove it the way `download-extensions.sh` adds it and update `NOTICE.md`.
6. **Sign-in link conflict:** test Claude Code and GitHub sign-in with VSCodroid also installed. If `vscodroid://` callbacks open a chooser, change `urlProtocol` in `branding/product.json` and the matching intent filter together, then test again.
7. **Legal documents:** update `docs/PRIVACY_POLICY.md` (no data collected unless crash reports are opted in; pairing and sync stay on the user's devices; AI suggestions go only to the provider the user picks) and `docs/LEGAL_NOTICES.md` (Diligent's notices, BouncyCastle, Nearby Connections), with their dates. Publish the privacy policy with the existing GitHub Pages workflow.
8. **GitHub release:** run the release workflow (signed APK, app bundle, language pack zips, checksums); publish `vX.Y.Z` with notes from `CHANGELOG-DILIGENT.md`.
9. **Play preparation:** ready-to-paste text for the store listing (Mobile view screenshots first), the Data safety form, the content rating, the foreground service `specialUse` explanation (keeping user-started dev servers and AI agents running), and each permission's reason.
10. **Closed test and production:** upload the bundle to closed testing; 12 or more testers opted in for 14 days in a row; fix crashes until crash-free sessions reach 99.5%; then the human applies for production access.

**Done when**

- [ ] The signed APK installs on a phone that never had Diligent, and a Java pack downloads from Diligent's release.
- [ ] The closed test passes with 12+ testers for 14 days and at least 99.5% crash-free sessions.
- [ ] The privacy policy is online; production access is requested.

**Do not:** commit a keystore, password or secret; ask for secrets in a chat; ship to production before the closed test meets its bar.

---

## Checklist before finishing any phase

- [ ] Only this phase's tasks were done, in pull requests of one Part each.
- [ ] The phase's "Done when" list passes on a real phone.
- [ ] The two-minute smoke test passes.
- [ ] GitHub's Build and Lint checks are green.
- [ ] New screens use `DiligentTheme` only, and the pull request has screenshots in light and dark.
- [ ] The license, notices and VSCodroid credit are still there.
- [ ] `CHANGELOG-DILIGENT.md` has a dated entry; the report lists anything that needs a decision.

**When something goes wrong**

- **White screen after install:** the build skipped the editor or tool assets; check the CI steps that fetch the server and package assets.
- **First CI build takes very long:** it builds the editor server, cached afterwards; changes to `branding/` or `patches/` rebuild it.
- **Nothing runs in the emulator:** it is x86_64; use arm64.
- **A tool runs in the terminal but not from an extension:** start it by name, not by path.
- **Devices do not see each other:** same network (not guest Wi-Fi)? visibility setting? a network that isolates devices? Try a phone hotspot.

---

## Appendix A: diligent-link v1 (shared by all three apps)

Copy this appendix into `<APPS_REPO>/sync-core/SPEC.md` in Phase 5. After that, `SPEC.md` is the source of truth; changes need a new protocol version.

### A.1 Identity

- Each device creates, once, a self-signed X.509 certificate with an ECDSA P-256 key, valid 20 years. The private key lives in the platform's secure storage (Android Keystore wrapping, iOS Keychain, desktop OS keychain or an encrypted file).
- **Device ID** = SHA-256 of the certificate (DER), base32 without padding, first 32 characters, shown in groups of 4 (`ABCD-EFGH-...`).
- Each device has a user-editable display name and a platform: `android`, `ios`, `windows`, `macos` or `linux`.

### A.2 Discovery

- DNS-SD service type `_diligent._tcp` on the local network.
- TXT record: `v=1`, `name`, `platform`, `id` (device ID), `pair=1` when pairing requests are accepted (else `0`).
- Default port 47321; if busy, any free port, advertised in DNS-SD.
- Manual fallback: an IP address and port, or a QR code containing `diligent://<ip>:<port>/<device ID>`.

### A.3 Reachability

- Android: listening while the app is open, while a task runs, or always when "Stay reachable in the background" is on.
- Desktop: listening while the app runs (it lives in the tray).
- iPhone: listening only while the app is open (iOS suspends apps in the background).

### A.4 Transport

- TCP with TLS 1.3, both sides presenting their certificate (mutual TLS).
- Before pairing, any certificate is accepted, but only pairing messages are allowed.
- After pairing, only the pinned fingerprint of that peer is accepted. A different certificate for a known device ID is refused and reported as an identity change.
- Tailscale uses the same protocol to the peer's `100.64.0.0/10` address.
- Android phone to phone (Nearby Connections) runs the same TLS session over the Nearby byte stream.

### A.5 Framing

- Every message is a frame: 4-byte big-endian length, then UTF-8 JSON with at least `type` and `id`.
- File data: a JSON header frame `{ "type": "file.data", "id", "path", "offset", "length" }` immediately followed by `length` raw bytes.
- Unknown types are answered with `{ "type": "error", "code": "unsupported" }`.

### A.6 Pairing (6-digit code, SRP-6a)

1. Requester sends `pair.request` `{ name, platform, deviceId }`.
2. The target shows "<name> wants to connect. Code: 482 913". The code is 6 digits from a cryptographically secure random generator, valid 120 seconds, single use.
3. SRP-6a, RFC 5054 3072-bit group, SHA-512, username `diligent`, password = the 6 digits: target sends `pair.challenge` `{ salt, B }`; requester sends `pair.proof` `{ A, M1 }`; target verifies and sends `pair.confirm` `{ M2 }`; requester verifies `M2`.
4. Both derive the session key `K`. Each sends `pair.bind` `{ deviceId, name, platform, mac }` where `mac` = HMAC-SHA512(K, own certificate fingerprint followed by the peer's fingerprint). Each verifies the other's `mac`, which binds the TLS identities to the code and defeats a man in the middle.
5. Both store the pairing record and pin the peer's fingerprint.
6. Limits: 5 failed proofs lock pairing on the target for 5 minutes; a new request cancels an older pending code.
7. Interoperability: JVM uses BouncyCastle SRP6; iOS uses a maintained Swift SRP-6a library. A cross-platform test with fixed inputs must pass before release; if the implementations disagree, stop and ask.

**Pairing record:** `{ deviceId, name, platform, fingerprint, pairedAt, approval: "always" | "ask", lastSeen, lastSync, lastAddresses: { lan, tailscale } }`. Forget deletes the record and, when reachable, sends `pair.forget` to the peer.

### A.7 Sync (manual by default)

- **Project identity:** `.diligent/project.json` holds `{ "projectId": "<uuid>", "name" }` and is synced.
- **Manifest entry:** `{ path, size, mtimeMs, sha256, executable }` for every file not ignored; paths use `/`, relative to the project root.
- **Never synced by default:** `.git`, `node_modules`, `.venv`, `venv`, `__pycache__`, `dist`, `build`, `.next`, `.gradle`, `target`, `.diligent/logs`, `.idea/workspace.xml`, `.idea/shelf/`, `.env*`, plus anything in `.gitignore` and `.diligentignore`. `.env*` syncs only when allowed for that project on both devices. Other editor settings (`.vscode/`, the rest of `.idea/`) sync unless the user excludes them.
- **Unsaved changes:** apps that edit files save them before building a manifest. Apps that cannot see another editor's unsaved changes (the desktop app) remind the user to save first.
- **Baseline:** after each successful sync, both devices store the agreed manifest for that pair, in app data, never inside the project.
- **Comparison** (local L, remote R, baseline B):

  | Situation | Action |
  | --- | --- |
  | L changed since B, R unchanged | Send L |
  | R changed since B, L unchanged | Receive R |
  | Both changed and contents differ | Conflict |
  | Both changed to identical contents | Nothing |
  | New on both sides with different contents | Conflict |
  | Deleted on one side, unchanged on the other | Delete on the other side (after its snapshot) |
  | Deleted on one side, changed on the other | Conflict (keep the changed file) |
  | No baseline yet (first sync) | Same path with different contents is a conflict; otherwise copy missing files both ways |

- **Messages:** `sync.hello` `{ projectId }`, `sync.manifest` `{ entries }`, `sync.plan` `{ send, receive, delete, conflicts }`, `sync.confirm`, `file.request` `{ path }`, `file.data`, `sync.done` `{ manifest }`, `sync.cancel`.
- **Safety:** both sides snapshot before applying anything. Received files go to a temporary file, are checked against `sha256`, then renamed into place. A conflict keeps the local file and saves the remote one as `<name> (from <Device>).<ext>`; the apps then show a compare screen.
- **Approval:** if the responder's record says `ask`, it shows "<Device> wants to sync <project>. Allow?" before sending its manifest.
- **Automatic mode** repeats the same flow after saves or on a schedule, only when reachable, and stops while a conflict is unresolved.

### A.8 Remote run

- `run.start` `{ projectId, profile }` (the host offers a sync first if its copy is older), `run.stop`, `run.status`, `run.log` (streamed lines), `run.exit` `{ code }`.
- **Preview proxy:** the host opens a TCP proxy on the interface the session uses (LAN or Tailscale address), on a random port, forwarding to `127.0.0.1:<profile port>`, accepting connections only from the requester's current IP, closed when the run stops. The host replies `run.preview` `{ host, port }`.
- Profiles use the same `.diligent/run.json` format on every platform.

### A.9 AI relay (desktop only)

- `ai.info` returns whether Ollama is reachable on the desktop and which models exist.
- The desktop opens the same kind of IP-restricted proxy to its local Ollama port for the requesting device and replies `ai.endpoint` `{ host, port }`; it closes when the requester disconnects.

---

## Appendix B: Sources

- [VSCodroid on GitHub](https://github.com/rmyndharis/VSCodroid): `CONTRIBUTING.md`, `docs/05-API_SPEC.md`, `docs/USER_GUIDE.md`, `NOTICE.md`
- [Google Play: Device and Network Abuse policy](https://support.google.com/googleplay/android-developer/answer/9888379?hl=en)
- [Google Play: testing requirements for new personal accounts](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en-GB)
- [Andronix: Android 12 and the phantom process killer](https://docs.andronix.app/android-12/andronix-on-android-12-and-beyond)
- [Vercel Community: Turbopack fails on Termux](https://community.vercel.com/t/better-wasm-fallback-support-for-turbopack/43856)
- [Antigravity CLI 1.2.15 release notes](https://www.havoptic.com/r/antigravity-cli-1.2.15)
- [Capawesome: battery optimization and Play policy](https://capawesome.io/plugins/android-battery-optimization/)
