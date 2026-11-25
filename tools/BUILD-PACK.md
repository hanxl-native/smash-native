# Build the game files on a PC

The app plays from two files: the **`.nup`** (the game) and the **pack** (the code
and shader data the runtime needs). The phone can build both, but Smash's 35,012
shaders take hours there; a PC does them in one go and leaves the phone free.

This folder has a script that makes both from your own dump:

```
tools/build-pack.sh  game.xci update.nsp dlc/*.nsp
        │
        ├─ out/SmashUltimate.nup   the game, update 13.0.5 and the DLC, merged
        └─ out/pack.zip            what the app's PACK card imports
```

## What you need

- Windows with **WSL2** (Ubuntu), Linux, or macOS.
- **Python 3.12+** — the packer's packages install into `.venv` inside the
  Peanuts Native checkout on the first run.
- **The .NET 10 runtime** — the shader translator runs on it.
- **cmake** — only if your game is an `.nsp`/`.xci` (it builds the importer that
  merges the files). A `.nup` or a dump folder does not need it.
- **`prod.keys`** — only for `.nsp`/`.xci`. Put it in this `tools/` folder or set
  `PROD_KEYS` to its path.
- About **10 GB** free, and your game files.

### Install them (Ubuntu / WSL2)

```sh
sudo apt update && sudo apt install -y python3 python3-venv cmake build-essential git curl
curl -sSL https://dot.net/v1/dotnet-install.sh | bash -s -- --channel 10.0 --runtime dotnet
export DOTNET_ROOT="$HOME/.dotnet"     # add it to ~/.bashrc to keep it
```

On Windows, put the files where WSL can read them (`/mnt/c/...`).

## Run it

```sh
cd tools
./build-pack.sh "/mnt/c/Users/you/Roms/Smash.xci" \
                "/mnt/c/Users/you/Roms/update.nsp" \
                /mnt/c/Users/you/Roms/dlc/*.nsp
```

From Windows, `build-pack.cmd` does the same through WSL:

```bat
tools\build-pack.cmd "C:\Users\you\Roms\Smash.xci" "C:\Users\you\Roms\update.nsp" C:\Users\you\Roms\dlc\*.nsp
```

The first run also clones **Peanuts Native `v0.1.7-1`** (about 700 MB) next to the
repository, which is the runtime the app was built with. Leave it there: later
runs reuse it and its shader cache.

## How long it takes

| | First run | Later runs |
|---|---|---|
| **PC** (8+ threads) | ~1.5–2 h for Smash (the shaders), 15–40 min for Darksiders | minutes |
| Phone, in the app | 3–6 h for Smash | — |

The PC job can run unattended; the phone would be hot and busy for hours.

## On the phone

1. Copy `SmashUltimate.nup` and `pack.zip` to the phone (USB, SD, cloud).
2. Open the app → **PACK** → import `pack.zip`; it shows **Ready**.
3. **GAME FILE** → pick `SmashUltimate.nup` (move into the app, the original
   stays if you copy).
4. **PLAY**.

No `prod.keys` and no firmware are needed on the phone this way: the `.nup` is
already merged, and the pack carries the shaders.

## Notes

- The pack is built for the app's runtime (`v0.1.7-1`); the script clones exactly
  that tag, because another version's shader library may not load.
- The `.nup` already holds update 13.0.5 and the DLC. The app's **UPDATE** and
  **DLC** cards are for building on the phone instead, without a PC.
- Both files are your own game's data: keep them private, do not upload them.
