# Diligent changelog

What Diligent adds on top of VSCodroid. VSCodroid's own changes are in
`CHANGELOG.md`. Each phase of `AGENT_GUIDE.md` ends with a dated entry here.

## Phase 0: Clone, rename and build

### 2026-10-26

**Done**

- Cloned VSCodroid `main` (version 1.5.0, versionCode 16) with its full
  history; `upstream` points to VSCodroid and `origin` to
  `cod-itor/Diligent-OpenSource`. `UPSTREAM.md` explains how to pull
  VSCodroid's updates and lists every VSCodroid file Diligent edits.
- Added the build guide as `AGENT_GUIDE.md`.
- Application ID `com.ditarector.diligent` (debug:
  `com.ditarector.diligent.debug`), so Diligent installs next to VSCodroid.
  The namespace and Kotlin packages stay `com.vscodroid`. Device scripts,
  instrumented tests and the user-facing docs follow the new ID.
- The app is named Diligent in every language: launcher, notifications,
  dialogs and messages, plus the bundled extensions' commands
  (`Diligent: ...`), walkthrough and messages.
- The editor is named Diligent (`branding/product.json` and the
  `server.js` override), with Report Issue and license links to this
  repository.
- The About dialog credits VSCodroid in all fourteen languages; `LICENSE`
  keeps VSCodroid's copyright line and adds Diligent's.
- `docs/diligent/BASELINE.md` is ready for the human's measurements.

**Checked locally:** all 3,296 unit tests pass and the instrumented tests
compile (JDK 21); every `scripts/check-*.py` gate in `lint.yml` passes.
`scripts/test-auth-callback-provider.js` and `scripts/test-bridge-relay.js`
fail under Node 26 on the Mac, and fail the same way on the untouched
VSCodroid tree; CI runs them on Node 24.

**Still to do for Phase 0:** push, open the pull request from
`phase-0-diligent`, get Build and Lint green (the first build rebuilds the
editor server, up to two hours), install the `debug-apk` artifact, run the
smoke test and fill in `BASELINE.md`.

**Needs a decision or a later phase**

- **Icon:** there is no `branding-source/logo.png`, so the app keeps
  VSCodroid's icons. A Diligent icon is required before release.
- **Editor text set by a patch:** `patches/0011` writes "VSCodroid" into
  about ten editor messages ("Welcome to VSCodroid", "Please reload
  VSCodroid ..."). The guide asks before touching `patches/`.
  `branding/server/manifest.json` (the web app manifest) also still says
  VSCodroid.
- **Links that still point at VSCodroid (Phase 7):** the About dialog's
  privacy policy and Source Code links, the language pack `downloadUrl`s in
  `ToolchainRegistry.kt`, `REPO` in `scripts/fetch-vscode-oss.sh` (used by
  local and release builds), and the README.
