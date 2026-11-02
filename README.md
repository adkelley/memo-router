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
 :notes     {:account "iCloud" :folder "Notes"}}
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
| `--config-path <path>` | Config file to read. Defaults to `config.edn` in the current directory; launchd should pass an absolute path, since its working directory is not the project. |
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
