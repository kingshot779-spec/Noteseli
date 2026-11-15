# ELIOS NOTES

An independent Android notes application built with Kotlin, Jetpack Compose and Room.
Local-first, no account, no cloud, no ads.

## Project state (database schema v4, app 0.2.0)

> **Build status:** nothing here has been compiled or run. This environment has no Android SDK,
> no Gradle and no network. Treat every item below as *code written, not verified* until the
> GitHub Actions build is green and the app has been tried on a device.

### Implemented (code written, not yet device-tested)
- Library: notes, search (titles + page text), favorites, pinning, archive, trash (restore / delete forever with confirmation / 30-day auto-purge), folders, sorting.
- **Data safety:** no `REPLACE` upserts on `notes` (it cascade-deleted pages and ink). One ordered save queue + one repository lock. Failed saves are retried 3 times and a banner is shown if they still fail; a retried snapshot can never overwrite a newer one. Unreadable (corrupt / newer-version) text or ink payloads are shown as empty but are **never overwritten or deleted** by autosave. Legacy v1 notes have their text copied into page 1 on first open so other pages can no longer replace it.
- Safe migrations 1 -> 2 -> 3 -> 4 (columns added only if missing).
- Pages: individual or continuous scroll, A4 portrait/landscape, templates, page colour, add / delete (confirmation names the page). A page **grows** when its text is taller than the page (on screen and in the PDF) instead of clipping it. Text lines sit on the 32-unit template grid.
- Ink engine: pen, pencil, marker, highlighter, pressure when reported, finger/stylus handling with palm rejection, partial and whole-stroke eraser (the eraser now sweeps the whole path between touch samples), undo/redo (200 steps), compact versioned payloads.
- PDF export (vector, system file picker, re-opened with PdfRenderer to verify the page count; a broken file is deleted).
- **Backup / restore** (menu in the library): one JSON file via the system file picker (includes archived and trashed notes). The backup is re-read after writing and deleted if it does not verify. Restore *merges* in one transaction: existing notes are never overwritten, and a bad file changes nothing. The file is **not encrypted**.
- Arabic (default) and English. Dark theme follows the system.

### Not implemented yet
Lasso, zoom, rich-text formatting, images/media, PDF import/annotation/merge/split, audio, handwriting recognition, tags, note lock & encryption, in-app language switch on Android < 13, insert-page-at-position, text flowing across pages, pen tilt / S-Pen button.

### Known limits
- Changing orientation applies to empty pages only (so ink is never cut off).
- Continuous-scroll mode shows pages without gaps, but a stroke cannot cross a page boundary.
- Black ink on the dark page colour is invisible; pick another colour.
- Room schema JSON (`app/schemas`) has never been generated; the CI run uploads it as an artifact - commit it.
- Library versions (AGP 8.13.2, Kotlin 2.2.21, KSP 2.3.10, Compose BOM 2026.09.00 ...) could not be checked offline.

## Build
Open `ELIOS_NOTES` in a current Android Studio, let Gradle sync, run the `app` configuration.
The archive has no Gradle wrapper JAR; generate one with the version in `gradle/wrapper/gradle-wrapper.properties`.
Library versions in `build.gradle.kts` could not be verified offline and should be checked on first sync.

Unit tests (pure JVM): `./gradlew :app:testDebugUnitTest` (ink codec round-trip, eraser geometry).

Settings: minSdk 23, compile/target SDK 36, Java/Kotlin 17.

## Principles
- Local-first storage; no AI features; independent identity and assets.
- A feature counts as complete only after it has been implemented **and tested**.

## Getting an APK without installing anything
Push this folder to a GitHub repository. The workflow in `.github/workflows/build-apk.yml`
sets up JDK 17, Android SDK 36 and Gradle 8.13, runs the JVM unit tests, builds and verifies
`app/build/outputs/apk/debug/app-debug.apk`, then publishes it under Actions -> Artifacts as
`ELIOS_NOTES-debug-apk`. Compile errors, if any, appear in that run's log.
