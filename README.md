# PS2 IMG Manager (for Neutrino UDPBD)

An Android app to **create, open, edit (add/delete), and save** a `.img`
**virtual USB drive** — the multi-game, browsable format Neutrino's
`-bsd=udpbd` backing store expects (as opposed to OPL's separate, native
UDPBD mode, which streams a single raw ISO9660 disc image directly). This
`.img` is an **exFAT filesystem wrapped in an MBR partition table**: drop
several games in as regular files/folders, and the console browses it the
same way it would a real USB drive.

No FAT32 splitting and no batch-renaming features, per spec — this tool
focuses purely on building/editing the drive's contents.

## What it does

- **New Image** — start an empty virtual drive.
- **Open .img** — parses an existing exFAT+MBR image back into a browsable
  file tree, without needing to fully unpack it to disk. Also reads
  standards-conforming exFAT images made by other tools, not just ones this
  app wrote.
- **Add Files** — pick one or more files (e.g. your `.iso`/`.bin` game dumps)
  from your device/cloud storage and add them into the current folder.
- **New Folder** — add a virtual subfolder (e.g. `DVD/`, `CD/`, or however
  you want to organize your library — exFAT has no fixed-folder requirement).
- **Delete Selected** — check items in the list and remove them.
- **Save As .img** — writes a fresh exFAT+MBR image to wherever you choose
  (local storage, SD card, etc.), ready to serve from Neutrino's UDPBD
  backing store.

Editing works by rebuilding the whole image from the in-memory file tree
rather than patching bytes in place — this is far more reliable than
in-place filesystem surgery.

## Why exFAT (not FAT32)

exFAT is what Neutrino's UDPBD backing store defaults to, and unlike FAT32
it has **no 4 GiB per-file size cap** — important since real PS2 dual-layer
dumps can exceed that. (This is a different thing from OPL's own
"UDPBD, Access: IMG" mode, which streams one raw ISO9660 disc image and
isn't a filesystem at all — that mode is intentionally not what this tool
targets.)

## How the image is built

Every file and folder is written as one contiguous run of clusters
("NoFatChain" allocation in exFAT terms), so the FAT itself only needs real
linked-list entries for the allocation bitmap, the up-case table, and the
root directory (none of which have anywhere else to record their length).
Everything else's size/location is read straight from its directory entry,
the same way any exFAT implementation is expected to handle contiguous
files.

This on-disk layout and every checksum/hash algorithm (boot sector
checksum, directory entry-set checksum, filename hash, up-case table
checksum) were validated against an independent, from-scratch Python parser
that re-derives and cross-checks every field — including forcing both a
multi-cluster root directory and a multi-cluster subdirectory to make sure
chain-following works, not just the common single-cluster case — before the
logic was ported to Kotlin. **It has not been verified by actually mounting
the output on a real OS or PS2**, since neither is available in the
build/dev environment — that verification (and specifically Neutrino/OPL
compatibility) is the main thing to confirm on a real device before relying
on this.

## Building

This is a standard Android Studio (Gradle) project.

1. Install [Android Studio](https://developer.android.com/studio) (needs
   internet access to download the Android SDK/Gradle — I built this
   project offline, so it hasn't been compiled or run yet).
2. Open the `PS2ImgManager/` folder as a project.
3. Let Gradle sync (downloads dependencies automatically).
4. Run on a device/emulator with **Run ▶**, or **Build > Generate Signed
   Bundle/APK** to produce an installable APK.

Minimum SDK: Android 7.0 (API 24). Target/compile SDK: Android 14 (API 34).

## Building via GitHub Actions (no local Android Studio needed)

A workflow is included at `.github/workflows/android-build.yml`. It builds
a debug APK on every push/PR and on manual trigger, using
`gradle assembleDebug` (Gradle is installed by the workflow itself, so no
committed Gradle wrapper jar is needed).

Setup:

1. Create a new GitHub repo and push the **contents of this `PS2ImgManager/`
   folder** to its root (i.e. `app/`, `build.gradle.kts`, `.github/`, etc.
   should sit directly at the repo root — not nested inside another folder).
2. Push to `main` (or `master`), or trigger manually from the **Actions**
   tab (**Run workflow**).
3. When the run finishes, open it and download the `ps2-img-manager-debug-apk`
   artifact from the **Artifacts** section — that's your installable APK.

To build a **release** (signed) APK instead, you'd add a signing config to
`app/build.gradle.kts` sourced from repo secrets and a second job/step
running `gradle assembleRelease` — say the word if you want that added.

## Project layout

```
PS2ImgManager/
  app/src/main/java/com/ps2imgmanager/
    MainActivity.kt          UI actions (New/Open/Add/Delete/Save)
    EntryAdapter.kt           RecyclerView list of the current image tree
    image/
      ImageEntry.kt            Virtual file/folder tree node
      FileSource.kt            Lazy byte sources (picked file, or region/extents of an opened image)
    exfat/
      ExfatWriter.kt           Builds an exFAT filesystem (wrapped in an MBR) from the tree
      ExfatReader.kt           Parses an existing exFAT+MBR image into the tree
```

## Known limitations / testing notes

Since I don't have a way to compile/run an APK, mount exFAT, or run any
exFAT validation tools in this environment, please test on a real device
and let me know if anything needs fixing. Likely candidates, roughly in
order of risk:

- **Not yet verified against a real PS2/Neutrino/OPL setup.** The exFAT
  bytes were validated for internal self-consistency (checksums, chain
  integrity, no overlapping cluster claims) but never mounted on an actual
  OS or console.
- **No config-change handling** — rotating the screen mid-edit resets the
  in-memory tree (a pre-existing gap, not new to this rewrite).
- **128 KiB fixed cluster size** — reasonable for GB-scale game files, but
  not adaptive to very small or very large total drive sizes the way a real
  formatter would be.
- **No 2nd FAT / no volume label entry** — both are optional per the exFAT
  spec and this keeps the writer simpler, but a stricter checker might flag
  their absence.
