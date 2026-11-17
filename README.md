# Speed Cards

An offline flashcard app for Android, built for language learning. It works like Anki: an adjustable
spaced-repetition scheduler (SM-2), plus cards you can link to each other, voice recordings with
text-to-speech, imports from Anki and spreadsheets, and backups to your own NAS. It ships with
required starter decks for **Spanish (Mexico)** and **Mandarin Chinese**.

Everything stays on the phone. There's no account, no cloud sync, and no analytics.

---

## Features

### Decks, notes and cards
- **Folders:** the app opens on a list of folders you can name, rename and create. Each holds top-level decks; open one to see its decks and study them together. Move a deck between folders from its Deck options. Decks not in a folder appear under "Unfiled decks", and only empty folders can be deleted.
- Decks with sub-decks (e.g. `Gatos › Razas`). Each deck has a scheduling preset and a language pair, such as `es-MX` → `en-US`.
- Collapse a deck on the home screen to hide its sub-decks. Its counts still include them, and the app remembers which decks are collapsed.
- Notes and cards are separate, as in Anki: one note can make several cards.
- **Built-in note types:**

  | Note type | Cards it makes |
  |---|---|
  | Basic | front → back |
  | Basic (and reversed) | front → back and back → front |
  | Cloze | `{{c1::…}}` fill-in-the-blank |
  | Vocab | recognition and recall, with example, notes and source fields |
  | Spanish Vocab | recognition, recall, and a fill-in-the-blank sentence |
  | Chinese Vocab | reading, recall and listening, with Simplified + Traditional, pinyin and measure word |
- **Card browser:** search, and filter by deck.
- **Card editor:**
  - Pin fields to keep them between cards ("sticky" fields).
  - "Save & next" and a live preview.
  - A warning when the word already exists.
  - Per-card controls: suspend/unsuspend, reset to new, clear the leech flag.
- **Leech detection:** a card forgotten 8 times is flagged, and you're asked to rewrite or split it.

### Reviewing and scheduling
- **Answer buttons:** Again / Hard / Medium / Easy, each showing the interval it would give. Again can be hidden per preset.
- **Adjustable SM-2 presets:**
  - learning and relearning steps (e.g. `10m 1h 1d`), graduating and easy intervals
  - starting and minimum ease, how much each answer changes ease
  - Hard and Easy multipliers, interval modifier, maximum interval
  - how much of the interval is kept after a lapse, fuzz, daily new and review limits, leech threshold
- **Presets come with a simulator:** type an answer pattern like `M M H E A M` and see the intervals it produces.
- **Starter presets:** Vocabulary, Sentences/grammar, and Cram before trip/exam.
- **During review:**
  - undo (up to 20 answers back)
  - hide a card until tomorrow, suspend it, or edit it
  - a "Related" panel listing linked cards
- **Spacing:** a card is never followed by its sibling card or a linked card when something else is due.
- **Reviews first:** a deck shows no new cards while it has reviews due today, or learning cards due now. New cards appear once those are done, in the same session. A new card's own short learning steps don't hold back the next new card.
- **Study all due cards** from the home screen goes through every deck in one session. Each top-level deck keeps its own daily limits.
- The study day rolls over at 4 am.

### Custom study (from a deck's ⋮ menu)
- **Cram** a whole deck or one tag. Practice only; schedules don't change.
- **Practise forgotten cards:** cards you answered Again today, in the last 7 days or in the last 30.
- **Study ahead** 1, 3 or 7 days. These are real reviews: answering early earns credit only for the time that actually passed.
- **Practise linked cards:** a card plus everything linked to it, 1–3 steps out.

### Linking cards
- **Typed links**, one-to-many or many-to-many:
  - symmetric: synonym, antonym, gender pair, false friend, same root, collocation, same measure word
  - one-way, with a reverse label: conjugation of ↔ has conjugation, plural of, example sentence for
- You can create your own link types with a name, an optional reverse label and a colour.
- Type `[[` in any field to open the link picker, or use **Add link**.
- **Link graph:** pan and zoom around a card's neighbourhood, colour-coded by link type. Tap a card to re-centre on it or edit it.

### Audio
- **Recording:** record into any field, then trim the start and end. Audio is stored in Anki's `[sound:…]` format.
- **Playback:** autoplay on the question and answer, a replay button, and speed from 0.5× to 1.5×.
- **Text-to-speech fallback:** when there's no recording, each field is read in its own language from the deck settings.
- **Read front aloud** (per note, in the card editor): turn it off to keep the question side silent. Its audio plays after you flip the card instead. Listening cards still play. Chinese starter notes, whose fronts are characters, start with it off.
- **Mute:** the speaker button on the home screen silences all card audio, front and back, until you turn it back on. Also available in the study screen's Audio settings.
- **Shadowing:** record yourself and hear the reference followed by your take (A/B). Keep takes and play them back later from **My recordings**.
- **Audio & media screen:** storage use, and cleanup of files no card uses.

### Adding cards
- **In the app:** the add screen.
- **Quick capture without leaving the app you're in:**
  - text-selection menu → *Speed Cards*
  - Share → Speed Cards
  - a **Quick Settings tile** ("Add card")
  - the **launcher shortcut** (long-press the icon → Quick add)
  - the widget's **+** button
- **The quick-add sheet:**
  - Picks words out of a selected sentence.
  - Offers to paste what you just copied.
  - Has a **mic** button for pronunciation.
  - Detects the language and picks the matching deck.
  - **Suggests a meaning** with on-device translation (ML Kit).
  - **Save to Inbox** lets you finish the card later.
- **Imports from a computer:**
  - **Anki `.apkg`**, legacy format. Note types, decks, media and (optionally) review progress come over.
  - **CSV / TSV**, with a column-matching wizard, tags, and an `id` column so re-importing updates instead of duplicating.
  - **Zip of a CSV + audio files**, so audio gets attached.
  - **`.sctxt`**, a plain-text format that can include links. See `SctxtParser.kt` for the syntax.
- **Getting files onto the phone:** "Open with" or share from another app, the file picker, or the **NAS `import/` folder**. Imported files are moved to `import/done/`.
- **Every import** ends with a report and an **Undo** button, which removes the notes that import created.

### Backup and restore
- **Back up now** writes a snapshot (`speedcards-YYYYMMDD-HHmm.scbackup`) into the folder you chose with the system folder picker. That can be a NAS share through an SMB documents-provider app or your NAS vendor's app.
- A backup counts as successful only when the file arrives intact: it's written as `.part`, size-checked, then renamed.
- New media files are copied into `media/`.
- **Retention:** 7 daily, 4 weekly and 6 monthly backups.
- **Daily backup reminder** at a time you choose. It's skipped if you've already backed up today or changed nothing, and has Back up now, Snooze 1h and Skip today buttons.
- **Status:** "Last backup" shows on the home screen and in Backup & restore, amber after 3 days and red after 7.
- **Export full backup:** one file that includes all media.
- **Restore** from a NAS backup or any file. It warns first ("This replaces your current data. Back up first?"), then restarts the app to apply.

### Habits and progress
- Streak on the home screen, plus a **daily study reminder**. It's silent when nothing is due.
- **Home-screen widget (2×2):** cards due, a 12-week review heatmap, and **Study** and **+** buttons.
- **Statistics** (all decks or one deck):
  - streak, reviews and time today, time over the last 7 days, words learned, 30-day retention
  - a 6-month review heatmap and a 30-day due forecast
  - card states, interval and ease charts, retention by deck, and a leech list

### Starter decks (required)
- **Spanish folder** (Mexican Spanish): Supervivencia / Viaje, Tiro con arco, Gimnasio / Pesas, Gatos. 200 notes and 69 links.
- **Chinese folder:** 旅行 Travel survival, 射箭 Archery, 健身 Gym & weightlifting, 猫 Cats. 203 notes and 77 links.
- Each topic is its own top-level deck, filed into the **Spanish** or **Chinese** folder. Installs from before folders, which kept the topics under a `Spanish (Mexico)` or `中文 Chinese` parent deck, are moved to this layout on launch with progress kept. A starter deck left without a folder is put back in its folder at the next launch.
- Installed on first launch. They can't be deleted, and they're reinstalled if they go missing (e.g. after restoring an older backup).
- Newer bundled versions add notes without overwriting your edits or progress.
- **Chinese display (Settings):** choose Simplified or Traditional as the main script, and turn tone-coloured pinyin on or off.

### Privacy
- **No accounts or servers.** The database, media and backups stay on the phone and in the folder you choose.
- **ML Kit** (translation and language detection) runs on the device. Its usage telemetry is disabled: the reporting backend is removed in `AndroidManifest.xml`.
- **Network use:** translation models are downloaded the first time you translate a language pair.

---

## Later

Planned, not built yet:

- **Bulk paste:** paste a word list (`word — meaning` per line), check a preview, and create the notes in one go.
- **Typed-answer cards** with an accent- and case-insensitive check and a character-by-character diff.
- **Conjugation and declension generator:** fill in a small table from a verb or noun and create linked child notes in one step.
- **Listening note type polish:** a dedicated listening-first note type for any language, beyond the Chinese listening card.
- **FSRS** as an alternative scheduler alongside SM-2.
- **Newer Anki package format** (`collection.anki21b`, zstd-compressed). Today these are detected and you're asked to re-export with "Support older Anki versions".
- **Direct SMB backup**, as a fallback if the system folder picker proves unreliable with a particular NAS.
- **Native review of the starter decks:** a few archery and gym terms and slang notes are flagged for checking. See `tools/seed/review/*.csv`.

---

## Building and running

**Requirements:**
- Android Studio, whose bundled JDK 21 is used by Gradle (see `org.gradle.java.home` in `gradle.properties`)
- Android SDK 36
- a device or emulator on Android 8.0 (API 26) or newer

```sh
./gradlew :app:assembleDebug     # APK at app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew test                   # scheduler, data and app unit tests (JVM + Robolectric)
```

`local.properties` (not committed) must point `sdk.dir` at your Android SDK.

### Project layout

| Module | What's in it |
|---|---|
| `core/scheduler` | Pure-Kotlin SM-2 scheduler, preset simulator, interval formatting. No Android dependencies. |
| `core/data` | Room database, repositories (study queue, notes, links, media, stats), template renderer (cloze, `{{hanzi:…}}`, `{{pinyin:…}}`), importers (CSV, `.sctxt`, `.apkg`), backup/restore, starter-deck installer |
| `app` | Jetpack Compose UI, audio (ExoPlayer, recorder, TTS), backup and reminder workers (WorkManager), widget, Quick Settings tile, quick-add activity |
| `app/src/main/assets/seed` | The bundled starter decks (`spanish_mx.sctxt`, `chinese.sctxt`) |
| `tools/seed` | `validate.py`, which checks the starter decks, and the review CSVs |

### Checking the starter decks

```sh
python3 -m venv .venv && .venv/bin/pip install -r tools/seed/requirements.txt
# es_MX.dic / es_MX.aff from https://github.com/LibreOffice/dictionaries (folder "es")
.venv/bin/python tools/seed/validate.py --es-dict path/to/es_MX --csv tools/seed/review
```

The script checks:
- required fields, duplicates, link targets and cloze markup
- pinyin against `pypinyin`
- Simplified/Traditional pairs against OpenCC
- Spanish spelling against the `es_MX` Hunspell dictionary

Errors fail the run. Warnings need a human look.

To ship deck corrections, edit the `.sctxt` file and raise `seed-version` on its first line.
