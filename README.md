# Memo Router

Transcribes Just Press Record voice memos with MLX Whisper and routes each one
into Apple Reminders or Apple Notes.

## Requirements

- macOS on Apple Silicon
- [Babashka](https://babashka.org) (`brew install borkdude/brew/babashka`)
- `ffmpeg` (`brew install ffmpeg`)
- MLX Whisper as a uv tool (`uv tool install mlx-whisper`)
- Just Press Record with iCloud sync turned on

## Configuration

Settings live in `config.edn` in the project root. It is git-ignored because it
holds machine-specific paths, so create it yourself with this shape:

```clojure
{:memo-dir  "/Users/<you>/Library/Mobile Documents/iCloud~com~openplanetsoftware~just-press-record/Documents"
 :manifest  "/Users/<you>/Library/Application Support/memo-router/processed.txt"
 :settle-ms 60000
 :whisper   {:bin   "/Users/<you>/.local/bin/mlx_whisper"
             :model "mlx-community/whisper-large-v3-turbo"}
 :reminders {:list "Inbox"}
 :notes     {:account "iCloud"
             :folder  "Notes"
             :routes  {"Work" {:account "Work"}
                       "Acme" {:account "Acme Corp" :folder "Memos"}}}}
```

| Key | Meaning |
|---|---|
| `:memo-dir` | Just Press Record's iCloud folder. Memos sit in date folders, e.g. `2026-10-31/11-23-32.m4a`. |
| `:manifest` | Where Memo Router records processed memos. You only choose the path: the file and its folder are created on the first successful run, and each handled memo is appended as a path relative to `:memo-dir` (e.g. `2026-10-31/11-23-32.m4a`). Remove a line to have that memo processed again. |
| `:settle-ms` | Skip memos modified more recently than this, so files still syncing from iCloud are picked up on a later run. |
| `:whisper :bin` | Absolute path to the `mlx_whisper` command (`which mlx_whisper`). |
| `:whisper :model` | Hugging Face model used for transcription. Downloaded on first use into `~/.cache/huggingface/hub/`. |
| `:reminders :list` | Optional. Reminders list for memos classified as reminders. Must match an existing list name exactly; omit it to use your default list. |
| `:notes :account` | Recommended. Notes account (e.g. `"iCloud"`) that notes are created in. Omit it to use Notes' default account, but that can change when accounts are added or the setting changes, so name it explicitly if you have work or client accounts. |
| `:notes :folder` | Optional. Folder within that account for everything else. Must match an existing folder name exactly; omit it to use the account's default folder. Folder names can repeat across accounts, which is why the lookup is scoped to one account. |
| `:notes :routes` | Optional. Map of spoken keyword to destination, `{:account "..." :folder "..."}`, for sending a note to another account (see [Routing notes to an account](#routing-notes-to-an-account)). `:account` is required. Without `:folder`, the note goes to that account's default folder, not the top-level `:folder`. |

Reminders and Notes are driven through `/usr/bin/osascript`, which is part of
macOS, so it needs no configuration. Both `:reminders` and `:notes` accept an
optional `:bin` that overrides that path; the tests use it to substitute a fake.

Use absolute paths throughout: launchd runs with a minimal environment, so `~`
and `$HOME` are not expanded. Keep API keys out of this file and set them as
environment variables.

## Usage

```bash
bb process
```

| Option | Meaning |
|---|---|
| `--config-path <path>` | Config file to read. Defaults to `config.edn` in the current directory. The launchd job passes an absolute path so it never depends on where it runs from. |
| `--dry-run` | Transcribe and classify new memos and log what would happen, but create no reminders or notes and leave the manifest untouched. Use it to check a new setup or config change against real memos. |

```bash
bb process --dry-run
bb process --config-path "/Users/<you>/code/memo-router/config.edn"
```

Run `bb process --dry-run` before the first real run: it exercises the whole
pipeline on your memos without changing anything, so nothing needs undoing if
the routing is not what you expected.

For development, start a REPL with `rlwrap bb repl` and reload a namespace
after saving with `(require 'memo-router.core :reload)`.

### Routing notes to an account

Start a note memo with a keyword from `:notes :routes` to send it to that
account, e.g. "Acme note: call the auditor about Q3." The keyword is matched
at the start of the transcript, ignoring case, optionally followed by "note"
and `:`, `,` or `.`, and is left out of the note's title. When several
keywords match, the longest wins: with keys "Acme" and "Acme Corp", "Acme
Corp: …" uses the "Acme Corp" route. A note with no
keyword goes to the top-level `:account` and `:folder`. Reminders are not
routed: "Acme, remind me to …" becomes an Acme note, because the reminder
phrase must open the memo.

Account names must match exactly; list them with:

```bash
osascript -l JavaScript -e 'Application("Notes").accounts.name()'
```

Whisper may spell a keyword differently than you expect (e.g. "A.D.K."), so
add aliases as extra keys pointing to the same destination.

## Running automatically

[`launchd/memo-router.plist.template`](launchd/memo-router.plist.template)
describes a per-user LaunchAgent that runs `bb process` every 5 minutes, and
once when it is loaded; the comments in the file explain each setting.
launchd does not expand `~`, so the template's `{{home}}` and `{{project}}`
placeholders are replaced with absolute paths when you install it. The
filled-in copy holds your username, so it lives only in
`~/Library/LaunchAgents/` and `launchd/*.plist` is git-ignored.

Before installing, run `bb process` once by hand so macOS asks for the
Automation permissions (Reminders and Notes) while you are at the keyboard.
The first scheduled run may ask again for `bb` itself, and possibly for
access to iCloud Drive; allow these too, or the job will fail in the log.

Install and start it:

```bash
sed -e "s|{{home}}|$HOME|g" -e "s|{{project}}|$PWD|g" \
  launchd/memo-router.plist.template > ~/Library/LaunchAgents/local.memo-router.plist
plutil -lint ~/Library/LaunchAgents/local.memo-router.plist
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/local.memo-router.plist
```

Run these from the project folder, since `$PWD` becomes `{{project}}`.

Check on it (`last exit code = 0` means the last run succeeded) and read the log:

```bash
launchctl print gui/$(id -u)/local.memo-router | grep -E "state|last exit code"
tail -f ~/Library/Logs/memo-router.log
```

Run it now instead of waiting for the next interval:

```bash
launchctl kickstart gui/$(id -u)/local.memo-router
```

After editing the template, fill it in again with the `sed` command above,
then reload it:

```bash
launchctl bootout gui/$(id -u)/local.memo-router
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/local.memo-router.plist
```

Stop and remove it:

```bash
launchctl bootout gui/$(id -u)/local.memo-router
rm ~/Library/LaunchAgents/local.memo-router.plist
```

Changes to `config.edn` or the code need no reload: each run starts a fresh
`bb` process that reads them.
