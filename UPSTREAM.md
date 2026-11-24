# Upstream

- Repository: https://github.com/igawa6/peanuts-native
- Tag: `v0.1.7-1` (commit `719d4f7b6ffdf7c5ecb6b7b4dc1d488411ea5b10`)
- The hooks patch in `patches/` is a diff against that tag.

To move to a newer release, change the tag in `apply.sh` and regenerate the patch
with the newer tree: `git diff` of the five files the patch touches (the
manifest, `android/app/build.gradle`, `MainActivity.java`, `Prefs.java` and
`dev/peanuts/ui/LibrarySettings.java`).
