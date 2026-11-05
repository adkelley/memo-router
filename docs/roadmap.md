# Roadmap

Where Memo Router is and what comes next. Items move from **Next** to **Done**
as they land; **Later** holds ideas that are agreed but not scheduled.

## Done

Each stage works on its own and has tests that mock its external tool.

- `inbox` – find settled, unprocessed `.m4a` memos as paths relative to the
  iCloud folder, oldest first
- `manifest` – read the processed set and append memos once handled
- `whisper` – transcribe a memo with `mlx_whisper` into a trimmed string
- `classify` – keyword rules turn a transcript into
  `{:type :reminder|:note :title … :body …}`, plus `:due` on a reminder with a
  date or time and `:destination` on a reminder naming a list or a note with a
  spoken keyword, or into `{:type :shopping :items … :body …}` for a shopping
  request
- `shopping` – add each item to the shopping list through a Shortcut, so
  Reminders sorts it into a grocery section
- Blank or one-word transcripts are skipped, since Whisper invents words like
  "You" from silence
- `due` – read dates and times from a reminder's transcript, relative to the
  memo's recording time, and remove the phrase from its title
- `reminders` – create a reminder in a named account and list through
  `osascript`, timed or all-day when it has a due date, or the list chosen by a
  spoken prefix from `:reminders :routes`
- `notes` – create a note as escaped HTML in a named account and folder, or
  the account chosen by a spoken keyword from `:notes :routes`
- `config` – load `config.edn` and report every problem before a run starts
- `core` – `bb process` wires the stages together, with `--dry-run`, and a
  failed memo is logged and retried on the next run while the others continue
- `bb re-process <memo>` removes a memo from the manifest, so the next run
  handles it again
- launchd job from a template, managed with the `bb agent:*` tasks
- Test runner (`bb test`), formatter (`bb fmt`) and linter (`bb lint`) tasks
- GitHub Actions CI: format check, lint and tests on every push

## Next

### 1. Errata map

Fix what Whisper regularly mishears, using a map in `config.edn`, e.g.
"homeless" → "home list" or "foreclosure" → "for Clojure". A pure function
scrubs the transcript after `whisper` and before `classify`, so every feature
sees the corrected text.

- Match whole words, ignoring case
- A plain replacement changes every occurrence, so "homeless shelter" would
  become "home list shelter". Decide whether that matters, or whether an entry
  can apply only at the start of a memo
- Decide whether `:body` keeps Whisper's text or the corrected text

## Later

### Classification

- Replace the keyword rules with an API behind the same `classify` function
  and return shape: OpenAI Decisions API, TypeSafe Jev, or an LLM such as
  Claude
- With an LLM, generate a short summary heading for notes (e.g. "Group wiki
  memos by project") instead of the first sentence. Spoken memos often come
  through as one long sentence, so today's `note-title` is capped at a few
  words and cut off with "…", which is the start of the memo rather than a
  summary
- With an LLM, also correct transcription errors in `:title` and `:body` from
  context (e.g. "foreclosure" → "for Clojure"), instead of maintaining word
  lists
- With an LLM, read due dates from context, filling the same `:due` slot as
  `due/extract-due`. An LLM could also fill the `:destination` slot for notes
- With an LLM, split shopping items from context, so "mac and cheese" stays
  whole, and accept looser requests

### Due dates

- A smarter `next <weekday>`: said on a Monday, "next Friday" could mean the
  Friday of next week rather than this one
- Spelled-out hours ("at three"), if transcripts turn out to need them
- Recurring reminders ("every Friday"), which need EventKit (see below)

### Reminders through EventKit

- Explore a small Swift helper that creates reminders with EventKit, called
  from the `reminders` namespace in place of `osascript`. `EKRecurrenceRule`
  can repeat a reminder on several weekdays ("every Sunday and Wednesday"),
  which the Reminders scripting dictionary has no way to set
- Check first that a command-line helper can get Reminders access when run by
  launchd
- Test whether an item saved through EventKit is sorted into a Groceries
  section. EventKit has no public API for sections, so the shopping list may
  need to stay on Shortcuts
- EventKit does not cover Notes, which stays on `osascript`

### Robustness

- Skip Whisper's longer inventions, such as "Thank you.", if they appear
- Validate `:notes :routes` and `:reminders :routes` in `config/problems`, so a
  bad route is reported at startup instead of as a failed memo
- Switch `core` from `println` to `clojure.tools.logging`: memo names and
  routing decisions, never transcript text
- Startup check that `:reminders :list` and `:notes :folder` exist, using the
  same `osascript` name listings used while debugging
- Clearer script errors than `Can't get object (-1728)`, e.g. "No Reminders
  list named Inbox"
- `clojure.spec` for the config map if the hand-written checks grow
- iCloud placeholders: find `.name.m4a.icloud` files and fetch them with
  `brctl download` instead of silently skipping them
- `whisper`: throw `ex-info` (with `:file` and `:out-dir`) when `mlx_whisper`
  exits 0 but writes no transcript

### Tooling

- Optional `bb agent:install` task that fills in the plist template, replacing
  the `sed` command in the README

### Links on reminders

- A link template on a reminder route, using any site's search URL, so "Spotify
  list, remind me to look into Pink Floyd" adds
  `https://open.spotify.com/search/Pink%20Floyd` to the reminder's notes. A Books
  route could use `https://www.amazon.com/s?k={query}&i=stripbooks` or
  `https://www.goodreads.com/search?q={query}`. This needs no API keys.
  Reminders scripting has no URL field, so the link goes in the body. Check that
  it is tappable there
- Getting "Pink Floyd" out of "look into Pink Floyd" is the hard part: a few
  stripped phrases to start with, or an LLM later
- Direct links, as a later step: Spotify's Web API for artists and albums, using
  a client ID and secret (the user has the Premium it requires), and possibly
  Open Library for books

### Transcription

- `:initial-prompt` in the `:whisper` config. A sentence-style prompt fixed
  "for Clojure" on turbo, but it does not scale across topics, so it is
  deferred in favour of LLM cleanup

### Documentation

- Docstring tidy-ups: `memo-files` continuation indent; `add-note!` should
  mention `:account`
