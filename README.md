# Korabooks — your Calibre-Web library, offline on Android

**English** · [Français](README.fr.md)

Korabooks mirrors a [Calibre-Web](https://github.com/janeczku/calibre-web) library
over its OPDS feed into a local database on the phone, and reads it from there.
Browsing, searching, filtering and sorting ten thousand books never touch the
server: the catalogue is walked once, and everything after that is a SQLite
query. Books are downloaded on demand.

> **Alpha.** Version 0.1.0 is a first build, shared for testing. Expect rough
> edges, and expect the local database to be rebuilt from scratch by a future
> version.

## What it does

- **Mirrors an OPDS catalogue** — one address, one login. A full sync walks the
  whole library; "Nouveautés" reads only what the server added since last time
  and stops at the first page it already knows.
- **Books, series, authors, genres** — four ways into the same library. A
  Calibre library is mostly standalone books, so books come first; the genre tab
  rebuilds the tree implied by Calibre's dotted tags.
- **Reads EPUB and PDF** offline, and keeps reading progress per book.
- **Adjustable display density**, everywhere it means anything.

## What it is not

Korabooks is a fork of [Kora](https://github.com/MKDevTests/Kora), itself a fork
of [Komelia](https://github.com/Snd-R/Komelia). Kora is a Komga client for manga;
Korabooks is the same engine pointed at a book library instead. Manga-specific
features (OCR, upscaling, AniList) are still in the tree but are not built into
the APK and have no callers here.

It talks to Calibre-Web through **OPDS only**, so it sees exactly what OPDS
publishes: title, author, series, language, tags. Calibre custom columns are not
part of that feed — if you keep genres in one, publish them as tags.

## Install

Grab the APK from [Releases](https://github.com/MKDevTests/Korabooks/releases),
allow installation from unknown sources, and open it. Android 8 or later,
arm64 only.

Then: **Paramètres → Catalogue**, enter the address of your Calibre-Web OPDS
feed (`http://192.168.1.10:8083/opds`) and your login, and press
"Tout resynchroniser". A library of ten thousand books takes about twenty
minutes, and is browsable while it runs.

## Build

```bash
./scripts/build-kora-debug.sh      # debug APK, installs on the connected device
./scripts/build-kora-release.sh    # signed release APK
```

Requires the Android SDK and a JDK 17. `git clone --recursive`, or
`git submodule update --init` afterwards — several dependencies are submodules.

## Licence

Apache 2.0, inherited from Komelia. See [LICENSE](LICENSE).
