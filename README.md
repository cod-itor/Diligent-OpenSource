<p align="center">
  <img src="docs/logo/diligent.svg" alt="Diligent logo" width="120" height="120">
</p>

<h1 align="center">Diligent</h1>

<p align="center">
  <strong>Write and run real code on your Android phone, with an interface made for a phone.</strong><br>
  The real VS Code engine underneath. A touch-first app on top.
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/cod-itor/Diligent-OpenSource?style=flat-square" alt="License"></a>
  <a href="https://github.com/cod-itor/Diligent-OpenSource/issues"><img src="https://img.shields.io/github/issues/cod-itor/Diligent-OpenSource?style=flat-square" alt="Issues"></a>
  <a href="https://github.com/rmyndharis/VSCodroid"><img src="https://img.shields.io/badge/built_on-VSCodroid-0a7d73?style=flat-square" alt="Built on VSCodroid"></a>
</p>

<p align="center">
  <a href="#what-diligent-is">What it is</a> •
  <a href="#two-views-you-choose">Two views</a> •
  <a href="#features">Features</a> •
  <a href="#privacy-and-safety">Privacy and safety</a> •
  <a href="#status">Status</a> •
  <a href="#building-from-source">Build</a> •
  <a href="#credits">Credits</a> •
  <a href="#license">License</a>
</p>

---

> [!NOTE]
> Diligent is in early development and has no release yet. Phases 1 to 4 are built and pass
> their automated tests, but they have not been tried on a phone yet, so expect rough edges.
> To try it, install the `debug-apk` artifact of the **Build** workflow, or build it from
> source (see [Building from source](#building-from-source)).

## What Diligent is

[VSCodroid](https://github.com/rmyndharis/VSCodroid) proved the hard part: the real VS Code
editor, Node.js, Python, Git and a terminal all run on an Android phone, offline. What a phone
still needs is an interface that fits it. On a 6-inch touch screen the desktop VS Code layout
is cramped and hard to use.

Diligent keeps VSCodroid's working engine and puts a phone-shaped app on top of it: big buttons
within thumb reach, a bottom tab bar, and a clean editor with no desktop menus or panels.

**Who it is for**

- Developers with a laptop who want to keep coding away from the desk.
- Students whose phone is their only computer.
- Anyone who wants a real development environment in their pocket, not a toy editor and not a
  cloud IDE.

## Two views, you choose

| View | What you see | Best for |
| --- | --- | --- |
| **Mobile view** | Diligent's own touch-first screens around the editor: Projects, Files, Editor, Terminal and More | Phones, one-handed use, beginners |
| **Desktop view** | The full VS Code interface, exactly as on a computer | Tablets with a keyboard, Samsung DeX, an external monitor |

- You pick on first launch and can switch at any time, without losing open files, unsaved text
  or running terminals.
- The app never forces a view. On a big screen it may **suggest** Desktop view; you decide.

## Features

From VSCodroid, unchanged:

- **Real VS Code**: the editor, Command Palette, extensions from [Open VSX](https://open-vsx.org).
- **Integrated terminal**: bash with a real PTY (tmux, line editing, the Node and Python REPLs).
- **Batteries included**: Node.js, Python 3, Git, npm, SSH and common tools, offline.
- **On-demand languages**: Ruby, Java and C/C++.
- **Extra Key Row**: Ctrl, Alt, Tab, Esc, F1 to F12, symbols and a cursor trackpad.

Added by Diligent:

**Mobile view**

- A native top bar, bottom tabs (Projects, Files, Editor, Terminal, More) and an action bar
  under the editor: Run or Stop, Preview, Save, Find, Commands, Undo, plus Accept and Next for
  AI suggestions.
- **Projects**: recent projects with their language, New project, Open folder and Clone from
  Git, with live progress and a Stop that never leaves a half-cloned folder behind.
- **Files**: a native file tree to create, rename, move and delete files and folders.
- **Run and Preview**: Diligent finds how to start a project (`npm run dev`, Flask, FastAPI and
  more), runs it in the background with a live log, and previews it inside the app.
- **Settings**: switch between Mobile and Desktop view at any time. Desktop view gets a
  "Mobile view" button to come back, and a big screen or Samsung DeX may suggest Desktop view.

**Reliability**

- A supervisor restarts a crashed dev server by itself and says why it stopped: low memory,
  Android's background process limit, a busy port, or Android closing it. It gives up after
  five failures in ten minutes.
- A notification lists running tasks with Stop, and the phone stays awake only while one runs.
- **Health** (More, then Health): checks battery optimisation, Android's background process
  limit, notifications, storage and process count, each with a Fix button.
- **Snapshots**: the project is saved to a private Git ref every ten minutes, without touching
  your branch, staging area or files. Undo opens the list and restores one.
- Auto-save one second after you stop typing (you can turn it off in Settings).

**Setup center**

- "What do you want to build?": nine ready templates (web page, React with Vite, Next.js,
  Express API, Python, Python for data, Java, C/C++, Ruby) with live progress.
- The same in the terminal: `kit list`, `kit add nextjs my-app`, `kit prepare nextjs` (so it works offline later), `kit explain` (plain words for an error), `kit doctor`, `kit remove`.
- Space, internet and tools are checked before anything starts, with a plain message saying
  what is missing.

**Laptop, AI and sharing**

- **Run on laptop** (More, then Laptop): pair a Mac or Linux laptop over SSH, then run a
  profile there. Diligent syncs the project with Git, starts it, and previews it on the phone.
- **AI agents** (More, then AI): Claude Code and Antigravity CLI sessions that keep running in
  the background, with Attach, Stop and "Undo changes since this session started", plus
  Copilot Chat.
- **AI suggestions while typing**: from Ollama on your laptop or your own API key
  (OpenAI-compatible endpoints such as Codestral, Anthropic, Gemini).
- **Share to other devices** (More, then Share): open the editor or a preview on another device
  over Tailscale, with a link and a QR code.

Coming next: pairing and sync with Diligent's desktop and iPhone companions, any-phone and
offline improvements, and a beginner mode.

## Privacy and safety

- **AI suggestions are off** until you pick a provider. Before the first request Diligent
  names the service and host that will receive your code. They run only on Wi-Fi by default,
  and never read `.env` files, keys (`*.pem`, `*.key`, `id_*`) or anything listed in
  `.diligentignore`. API keys are kept in the editor's secret storage, never in settings files.
- **Sharing is off** by default and never starts by itself. It listens only on the phone's
  Tailscale address (never on Wi-Fi or every address), shows a "Sharing on" notification with
  Stop, and turns itself off after 60 minutes without traffic.
- **AI agents**: Diligent never installs them for you and never reads or uploads their sign-in
  files. A snapshot is taken before each session so its changes can be undone.
- **Run on laptop** uses your own SSH key; Diligent stores no passwords.

## Status

Diligent is built in phases, described in [AGENT_GUIDE.md](AGENT_GUIDE.md). What is done is
recorded in [CHANGELOG-DILIGENT.md](CHANGELOG-DILIGENT.md). "Built" means the phase is written
and passes its automated tests; each phase's on-device checks in the guide still need a real
phone.

| Phase | What | State |
| --- | --- | --- |
| 0 | Clone VSCodroid, rename to Diligent, build | Done |
| 1 | The view switch and Mobile view | Built |
| 2 | Reliability | Built |
| 3 | Setup center | Built |
| 4 | Run on laptop, AI agents, AI suggestions, sharing | Built |
| 5 | Devices, pairing and sync | Planned |
| 6 | Any phone, offline and beginner mode | Planned |
| 7 | Release | Planned |

## Requirements

| Requirement  | Minimum                 |
| ------------ | ----------------------- |
| Android      | 11 (API 30)             |
| Architecture | arm64-v8a               |
| WebView      | Chrome 125+             |
| RAM          | 4 GB recommended        |
| Storage      | about 740 MB free       |

Diligent's application ID is `com.ditarector.diligent`, so it installs next to VSCodroid
without replacing it.

## Building from source

You need Android Studio (API 36), the Android NDK r27 or newer, CMake, Python 3 and Git. You do
not build VS Code yourself: the editor server is built once per VS Code version by a workflow in
this repository and fetched as a verified tarball.

```bash
git clone https://github.com/cod-itor/Diligent-OpenSource.git
cd Diligent-OpenSource

# Build the Android app
cd android && ./gradlew assembleDebug

# Install on a connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

> [!WARNING]
> The steps above build the Android shell only. A runnable app also needs the editor server,
> the Node runtime and the bundled tools, which the `scripts/download-*.sh` and
> `scripts/build-*.sh` scripts provide. [CONTRIBUTING.md](CONTRIBUTING.md) lists the order to
> run them in. The easiest way to get a working APK is the `debug-apk` artifact of the
> **Build** workflow on GitHub Actions.

## Contributing

Bug reports, ideas and pull requests are welcome. Please read
[CONTRIBUTING.md](CONTRIBUTING.md) first, and see [UPSTREAM.md](UPSTREAM.md) for how Diligent
stays in step with VSCodroid.

- [Report a bug or request a feature](https://github.com/cod-itor/Diligent-OpenSource/issues)
- [Documentation](docs/)

For a security problem, please do **not** open a public issue; see [SECURITY.md](SECURITY.md).

## Credits

**Diligent is built on [VSCodroid](https://github.com/rmyndharis/VSCodroid), created by
[Yudhi Armyndharis](https://github.com/rmyndharis) and the VSCodroid community.**

Everything that makes a real editor run on a phone comes from their work: the VS Code server
on Android, the bundled Node.js, Python and Git, the terminal, the Extra Key Row, storage
access and much more. Diligent adds a phone-first interface on top and would not exist without
it. The About screen in the app credits VSCodroid too.

If VSCodroid's work helps you, consider supporting its author:

<a href="https://buymeacoffee.com/rmyndharis"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Yudhi Armyndharis a coffee" height="45"/></a>

VSCodroid itself is available on
[Google Play](https://play.google.com/store/apps/details?id=com.vscodroid).

## Legal

Diligent is **not affiliated with or endorsed by Microsoft Corporation**, and is an independent
project, not an official VSCodroid release.

The bundled editor server is built from the MIT-licensed
[Code - OSS](https://github.com/microsoft/vscode) source, with Android patches and branding
applied before the build.

- "Visual Studio Code" and "VS Code" are trademarks of Microsoft.
- Extensions come from the [Open VSX](https://open-vsx.org) registry, not the Microsoft
  Marketplace.
- See [NOTICE.md](NOTICE.md) for third-party attribution.

## License

Diligent's source, like VSCodroid's, is under the **MIT License**; [LICENSE](LICENSE) keeps
VSCodroid's copyright line beside Diligent's. That licence does not extend to the third-party
components the app downloads or bundles; each carries its own terms, listed in
[NOTICE.md](NOTICE.md).

---

<p align="center">
  Diligent, by Dita Rector, on the shoulders of VSCodroid by Yudhi Armyndharis and the VSCodroid community.
</p>
