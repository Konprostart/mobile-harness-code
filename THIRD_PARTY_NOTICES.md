# Third-Party Notices — Mobile Harness Code

This file lists third-party components incorporated into or distributed with
Mobile Harness Code (an independent fork of Mobile Harness), together with their
licenses and upstream sources. It is provided in good faith; the authoritative
license text for each component resides at its upstream source or in the files
referenced below. Where a component is distributed as a runtime bundle rather
than as source, the corresponding notice is preserved in
[`app/src/main/assets/licenses`](app/src/main/assets/licenses).

## Source components built into the app

| Component | License | Upstream source | Notice |
| :--- | :--- | :--- | :--- |
| PRoot | GPL-2.0 | https://github.com/termux/proot | `app/src/main/assets/licenses/proot-GPL-2.0.txt` |
| talloc | LGPL-3.0-or-later | https://talloc.samba.org/ | `app/src/main/assets/licenses/talloc-LGPL-3.0-or-later.txt` |
| libandroid-shmem | BSD-3-Clause | https://github.com/termux/libandroid-shmem | `app/src/main/assets/licenses/libandroid-shmem-BSD-3-Clause.txt` |
| claude-code-android helper | MIT | (bundled in runtime; © 2026 ferrumclaudepilgrim) | `app/src/main/assets/licenses/claude-code-android-MIT.txt` |
| Apache Commons Compress | Apache-2.0 | https://commons.apache.org/proper/commons-compress/ | — |
| zstd-jni (luben) | BSD-2-Clause | https://github.com/luben/zstd-jni | — |
| AndroidX (core, activity, lifecycle) | Apache-2.0 | https://developer.android.com/jetpack/androidx | — |
| Jetpack Compose & Material 3 | Apache-2.0 | https://developer.android.com/jetpack/compose | — |
| Kotlin stdlib & kotlinx-coroutines | Apache-2.0 | https://kotlinlang.org/ | — |
| JUnit (test scope) | EPL-1.0 | https://junit.org/ | — |
| org.json (test scope) | JSON License | https://www.json.org/ | — |
| AndroidHarness (skills/plugin/MCP logic) | MIT | https://github.com/Sanuu7/AndroidHarness | `app/src/main/assets/licenses/androidharness-MIT.txt` |
| Signiffia (Key Vault & signing components) | Apache-2.0 | https://github.com/Konprostart/Signiffia | `app/src/main/assets/licenses/signiffia-Apache-2.0.txt` |
| Android apksig | Apache-2.0 | https://android.googlesource.com/platform/tools/apksig | — |
| BouncyCastle (bcprov/bcpkix/bcutil) | Bouncy Castle Licence (MIT-style) | https://www.bouncycastle.org/ | — |

## Runtime bundles (downloaded at setup or bundled for offline builds)

These are self-contained ARM64 user-space archives. Their contents remain
governed by their respective upstream licenses and are verified by SHA-256
before extraction.

| Bundle | Key contents | Representative licenses |
| :--- | :--- | :--- |
| Ubuntu 20.04 ARM64 rootfs | base system, coreutils, shell tooling | GPL/LGPL/MIT/BSD and other per-package licenses |
| Node.js / npm | JavaScript runtime & package manager | MIT |
| Git | version control | GPL-2.0 |
| OpenSSL | TLS library | Apache-2.0 |
| zstd | compression | BSD-3-Clause (dual with GPL-2.0) |
| Python 3 | language runtime, pip, venv | PSF License |
| OpenJDK 17 (Temurin) | Java runtime | GPL-2.0 with Classpath Exception |
| Gradle | build tool | Apache-2.0 |
| Android SDK / Build Tools | Android toolchain | Apache-2.0 / Google terms |
| Claude Code CLI | Anthropic coding agent | Anthropic Commercial Terms (downloaded from Anthropic) |
| DeepSeek Harness (DSH) | coding agent | Its own upstream license |
| Antigravity CLI (agy) | Google coding agent | Google Terms (downloaded from Google) |

## Trademarks

"Ubuntu", "Android", "Kotlin", "Node.js", "Git", "Claude", "Claude Code",
"Antigravity", "Google", and other product names are trademarks of their
respective owners. Mobile Harness Code is not affiliated with, endorsed by, or
sponsored by any of those owners, nor by the original Mobile Harness project.
