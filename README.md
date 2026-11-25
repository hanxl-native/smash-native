# Smash Ultimate Native

![screenshot](docs/smash-ui.png)

**Super Smash Bros. Ultimate** on an Android phone: 60 fps, update 13.0.5 and the
52 DLC packs, from one app with one button.

This is the app: the screen, the game setup, the build flow and the icon are this
repository's. The runtime underneath is [Peanuts Native](https://github.com/igawa6/peanuts-native),
which does the hard part of running the game's code and graphics (see
[Credits](#credits)).

## What this app adds

| | |
|---|---|
| **One screen** | The game file, the update, the DLC, the GPU driver, the picture scale, the saves, the console's keys and firmware. No emulator menus. |
| **Builds the `.nup` in the app** | Pick the game's own `.nsp`/`.xci`, the update and any number of DLC packs; `BUILD GAME FILE` merges them into one `.nup` with the importer that ships in the APK. |
| **The right update, checked** | The update card reads the file's CNMT and refuses anything but **13.0.5 (v2031616)**, the version this app and its shader pack are made for. |
| **Your keys, your firmware** | The APK carries no `prod.keys` and no console font. `KEYS` takes your own `prod.keys`; `FIRMWARE` takes your firmware folder or `.zip` and reads the shared fonts from it. Stand-in fonts (Noto, Droid) keep the game working before that. |
| **Turnip, picked for you** | Mesa Turnip is selected automatically when it is installed; the phone's own Adreno driver loses the device at some scene changes. The GPU driver card installs a driver zip or goes back to the system one. |
| **Saves** | Export and import as a zip. |
| **An icon of its own** | SMASH / ULTIMATE, drawn for this project (no Nintendo art). |

## How it is put together

The app is an **overlay** on Peanuts Native `v0.1.7-1`. Two pieces:

- `patches/0001-smash-hooks.patch` — turns the upstream launcher into this app:
  the single screen becomes the launcher, the build gets the app's name and its
  assets folder, and the firmware picker is made public.
- `overlay/` — the screen (`SmashLauncherActivity`), the icons and the
  redistributable fonts.

`apply.sh` clones the release, copies the overlay in and applies the patch. The
app's own code is about a thousand lines of Java in this repository; the runtime
is upstream's.

## Requirements

- Android 13+ and 8 GB of RAM or more (a Snapdragon 8 Gen 2/3 class phone).
- **Your own copy of the game** (a dump of the card you own) and your console's
  `prod.keys` to build from `.nsp`/`.xci`.
- No game data is included in this repository or in its releases.

## Build

```sh
./apply.sh                 # clones upstream at v0.1.7-1, copies the overlay, applies the patch
cd peanuts-native/android/app
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk \
  ./gradlew apk -PnxApp=peanuts -PnxAppId=smash -PnxLabel="Smash Ultimate Native" \
  -PnxGames=01006A800016E000 -PnxPackEngine=fake
# android/app/out/nxnative-android-peanuts.apk
```

Needs `git`, JDK 17 and the Android SDK (build-tools, NDK 28.2). The first build
compiles the native host and takes a few minutes.

### The game data pack

The `.nup` is the game; the **pack** is the code and shader data the runtime needs
next to it (`files/packs/01006A800016E000/`). It is built from the game with
Peanuts Native's **Prepare** (on a computer or on the phone), and it is not
distributed here.

The app's **PACK** card imports a `.zip` of that folder — `pack.tsv` at the root,
so zip the folder's contents, not the folder itself — and extracts it into
`files/packs/01006A800016E000/`; the card shows **Ready** once it is in place. A
build of your own can also carry `assets/smash/pack.zip`, which the app copies on
first start.

For the whole thing on a PC — the `.nup` merged from your game, update and DLC,
and `pack.zip`, in one command — see [`tools/BUILD-PACK.md`](tools/BUILD-PACK.md).

## Tested on

Samsung Galaxy S24+ (Snapdragon 8 Gen 3, Adreno 750), Android 16: 60 fps with Mesa
Turnip, from the intro movie to a four-player match, Spanish text and saves
working. The phone's own Adreno driver runs the menus but loses the device at some
scene changes, which is why the app prefers Turnip.

## Support

This app is free and will stay free. If it is useful to you, you can buy me a
coffee:

- Ko-fi: https://ko-fi.com/hanxlnative
- PayPal: https://www.paypal.com/donate/?business=nmherrerab%40gmail.com&currency_code=USD

## Credits

- [Peanuts Native](https://github.com/igawa6/peanuts-native) by seedds and its
  contributors: the runtime this app is built on (MIT; its Android host is
  GPL-3.0-or-later, see [NOTICE](NOTICE)).
- Mesa Turnip, Noto Sans (SIL OFL) and Droid Sans Fallback (Apache 2.0) for the
  pieces shipped with the app.
- Not affiliated with Nintendo or Sora Ltd. The game's names and cover art belong
  to their owners; this app ships neither.

## License

This repository's own files are MIT, see [LICENSE](LICENSE). An APK built with
them also contains Peanuts Native's Android host, so the APK as a whole is
GPL-3.0-or-later; see [NOTICE](NOTICE).
