# Releasing

Four packages come out of one tag: a Windows installer, a Debian package, a Fedora package and an
Android APK. `.github/workflows/release.yml` builds them; nothing is built by hand.

## Cutting a release

```bash
git tag v1.2.3
git push origin v1.2.3
```

That is the whole of it. The workflow runs the tests first, packages each platform on its own runner,
then opens a **draft** release with everything attached and a `SHA256SUMS.txt` beside it. It is a draft
on purpose — read it, check the files are the sizes you expect, and publish it yourself.

To build the packages without releasing anything, run the workflow by hand from the Actions tab and give
it a version. The artifacts are attached to the run for two weeks and no release is created.

## Updating, and why the draft matters

Spiceity checks GitHub once at launch and offers what it finds. It asks `/releases/latest`, which
**ignores drafts and pre-releases** — so nothing is offered to anybody until you publish the draft the
workflow opened. That is the intended safety catch: a release exists for you to look at before it
exists for everyone.

What each platform does with an update:

| Installed as | What happens |
| --- | --- |
| Windows installer | Downloads the `.exe`, checks it, runs it, and closes so its files can be replaced |
| `.deb` / `.rpm` | Downloads the package and hands it to the package manager through a `pkexec` prompt |
| Android | Downloads the APK and hands it to Android's package installer, which asks again |
| Unzipped folder, or Gradle | Told there is an update, and sent to the release page |

That last row is the one worth knowing. A copy running from the zip was never installed by anything, so
running an installer over it would put a second copy in Program Files and leave the folder you are
actually running untouched. Detection errs that way deliberately: being told to update by hand is a
smaller problem than two copies.

**Nothing is installed that was not verified.** The download is checked against `SHA256SUMS.txt` from
the same release, and a release without one is refused with a link to the page instead. If you publish
a release by hand, publish the checksums with it or the updater will not touch it.

The check can be switched off in Settings → Updates, and it is one request to GitHub either way.

## Why a job per platform

`jpackage` cannot cross-build. It reads the JDK it is running on and produces a package for that machine
and no other, so a Windows installer needs a Windows runner and a `.deb` needs a Linux one. There is no
way to make one runner produce all four.

The packages are large — around 270 MB each — because each carries its own Java runtime and its own copy
of Chromium for the SoundCloud sign-in window. That is deliberate: nothing has to be installed first.

## The three shapes of the version

The tag is the source, but it reaches three places that disagree about what a version may look like.

| Where | From `v1.2.3` | Rule it has to satisfy |
| --- | --- | --- |
| Gradle, and the release page | `1.2.3` | anything |
| `.msi`, `.deb`, `.rpm` | `1.2.3` | rpm refuses a hyphen; msi wants three numeric parts |
| Android `versionCode` | `10203` | an integer that increases with every release |

So `v1.2.3-beta.1` is a perfectly good tag: the release says `1.2.3-beta.1`, the packages are built as
`1.2.3`, and Android gets `10203`. The suffix is dropped rather than failing the build twenty minutes in,
on two platforms out of three, at the very last step.

`versionCode` packs the parts as `major * 10000 + minor * 100 + patch`, which keeps the ordering the same
as the version people read and leaves room for ninety-nine minors and patches.

## Signing the APK

Without secrets the workflow still builds a release APK, signs it with the **debug** key, and says so in
the log. That is installable and fine for testing, but it is not yours: an APK signed with the debug key
cannot be replaced later by one signed properly, so anybody who installed it has to uninstall first.

To sign properly, make a keystore once:

```bash
keytool -genkeypair -v -keystore spiceity.jks -keyalg RSA -keysize 4096 \
  -validity 10000 -alias spiceity
base64 -w0 spiceity.jks          # the value for the first secret below
```

Then add four repository secrets under Settings → Secrets and variables → Actions:

| Secret | What it is |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | the keystore file, base64 encoded |
| `ANDROID_KEYSTORE_PASSWORD` | the store password |
| `ANDROID_KEY_ALIAS` | `spiceity`, or whatever alias you chose |
| `ANDROID_KEY_PASSWORD` | the key password |

Keep the `.jks` somewhere safe and backed up. Losing it means never being able to update the app for
anyone who installed it.

The release job refuses to publish an APK that is not signed at all, which is the failure worth catching:
an unsigned APK cannot be installed by anybody and nothing about the file says so.

## macOS

There is no `.dmg`. macOS refuses a bundle version whose major is 0, which fails the build at
configuration time for *every* platform while this project is still 0.x, and nothing here is built or
tested on a Mac. Add `TargetFormat.Dmg` back with a macOS-specific `packageVersion` when there is a Mac
to test on.

## Changing the icon

The `.ico` and `.png` the packagers use are drawn by `AppIcon` and committed. After changing the drawing,
regenerate them:

```bash
./gradlew :desktop:test --tests "*GenerateIconFiles*" -Dspiceity.writeIcons=true
```

A test compares the committed files against the code so the two cannot drift — but only on a machine that
has Segoe UI Black, because the letter falls back to the platform's own sans without it and the same code
then draws different pixels. Regenerate on Windows.
