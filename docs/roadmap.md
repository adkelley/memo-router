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
  `{:type :reminder|:note :title … :body …}`
- `due` – read dates and times from a reminder's transcript, relative to the
  memo's recording time, and remove the phrase from its title
- `reminders` – create a reminder in a named list through `osascript`, timed
  or all-day when it has a due date
- `notes` – create a note as escaped HTML in a named account and folder
- Test runner (`bb test`) and formatter task (`bb fmt`)

## Next

### 1. `core/-main`

Wire the stages together:

    new memos → transcribe → (skip if blank) → classify
              → add reminder or note → mark processed

- **CLI** with a babashka.cli spec on `-main`:
  - `--config` – path to `config.edn` (default `config.edn`); launchd passes
    an absolute path
  - `--dry-run` – transcribe and classify, log what would happen, but create
    nothing and leave the manifest untouched
- **Config loading** with `clojure.edn/read-string`, failing early with a clear
  `ex-info` when a required key is missing or blank, or `:memo-dir` does not
  exist (note `(fs/exists? "")` is true)
- **Errors per memo** – a failure (e.g. `-1728`) is logged and the memo is left
  unprocessed for the next run; the other memos still run
- **Blank transcripts** – skip creating anything, but mark the memo processed
  so silence is not retried forever
- **Logging** with `clojure.tools.logging`: memo names and routing decisions,
  never transcript text

### 2. `bb reprocess <memo>`

Mark a memo as unprocessed so the next run handles it again, e.g. after
deleting a reminder by mistake or switching to a better model.

    bb reprocess 2026-10-31/11-23-32.m4a

- `manifest/unmark-processed!` – rewrite the manifest without that line;
  return `true` if a line was removed, `false` otherwise (typo, or no manifest
  yet), and never create an empty manifest
- `core/reprocess` – load config like `-main` (including `--config`), call
  `unmark-processed!`, and log whether the memo was found
- `bb.edn` task using `exec`, with the memo as a positional argument
  (`:args->opts [:memo]`) so no `--memo` flag is needed
- Deleting or completing a reminder or note never changes the manifest on its
  own: memo-router only writes to Reminders and Notes, so reprocessing is
  always an explicit choice

### 3. launchd job

- `~/Library/LaunchAgents/com.alexkelley.memo-router.plist` running
  `bb process --config <absolute path>` every 300 seconds
- `PATH` including `~/.local/bin` and `/opt/homebrew/bin`, so `mlx_whisper`
  can find `ffmpeg`
- stdout and stderr to `~/Library/Logs/memo-router.log`
- Run once by hand first to approve the Automation prompts for Reminders and
  Notes
- Optionally a `bb install` task that writes the plist and runs `launchctl`

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
- Route reminders to lists by spoken prefix, as notes are routed to accounts

### Due dates

- A smarter `next <weekday>`: said on a Monday, "next Friday" could mean the
  Friday of next week rather than this one
- Spelled-out hours ("at three"), if transcripts turn out to need them
- Recurring reminders ("every Friday")

### Robustness

- Startup check that `:reminders :list` and `:notes :folder` exist, using the
  same `osascript` name listings used while debugging
- Clearer script errors than `Can't get object (-1728)`, e.g. "No Reminders
  list named Inbox"
- `clojure.spec` for the config map if the hand-written checks grow
- iCloud placeholders: find `.name.m4a.icloud` files and fetch them with
  `brctl download` instead of silently skipping them
- `whisper`: throw `ex-info` (with `:file` and `:out-dir`) when `mlx_whisper`
  exits 0 but writes no transcript
- Optional `:account` for Reminders, matching Notes, if lists ever repeat
  across accounts

### Transcription

- `:initial-prompt` in the `:whisper` config. A sentence-style prompt fixed
  "for Clojure" on turbo, but it does not scale across topics, so it is
  deferred in favour of LLM cleanup

### Documentation

- README section on the processed manifest: created on first run, one line
  per memo, and how to reprocess
- Docstring tidy-ups: `memo-files` continuation indent; `add-note!` should
  mention `:account`
