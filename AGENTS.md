# AGENTS.md

## Project
Memo Router is a Clojure project that transcribes audio memos with MLX Whisper
and routes the results into Apple Reminders and Apple Notes. It talks to all
three by shelling out: `mlx_whisper` for transcription and `osascript` for
Reminders and Notes.

## Runtime
- Babashka (`bb`) only; the project is configured by `bb.edn` and has no `deps.edn`
- Babashka starts fast, which suits running from a launchd job every few minutes

## Run
- The user edits in Zed, which has no nREPL integration; use a terminal REPL alongside it
- Start a REPL with `rlwrap bb repl` (history and line editing; `brew install rlwrap`)
- After saving a file, reload it in the REPL with `(require 'memo-router.core :reload)`
- Rebel Readline does not run in Babashka; do not add it to `bb.edn`
- Run the entry point with `bb -m memo-router.core` (or a `bb.edn` task once defined)

## Code Layout
- Main namespace: `memo-router.core` (`src/memo_router/core.clj`)

## Environment
- macOS on Apple Silicon only (MLX requires it)
- MLX Whisper is installed as a uv tool with `uv tool install mlx-whisper`, which provides the `mlx_whisper` CLI in `~/.local/bin`
- The first `osascript` call to Reminders or Notes triggers a macOS Automation permission prompt for the calling process (the terminal when run by hand, `bb` when run from launchd); grant it in System Settings > Privacy & Security > Automation
- Never print, log, or commit API keys or memo contents beyond what debugging needs

## Configuration
- Settings live in `config.edn` in the project root (memo directory, processed manifest, `mlx_whisper` path and model, Reminders list, Notes folder)
- Read it with `clojure.edn/read-string`, never `clojure.core/read-string`
- Load the config once in `-main` and pass the relevant part down as an argument; do not read it from a global inside functions
- Use absolute paths in config, since launchd runs with a minimal environment
- Keep secrets out of `config.edn`; read API keys from environment variables

## Logging
- Use `clojure.tools.logging` (backed by Timbre in Babashka) instead of `println`
- Output goes to stdout, which launchd redirects to the log file
- Log memo file names and routing decisions, not transcript text

## Dependencies
- Prefer libraries built into Babashka; they need no `:deps` entry in `bb.edn`
- Shelling out: `babashka.process`
- Files and paths: `babashka.fs`
- JSON (e.g. reading `mlx_whisper --output-format json`): `cheshire`
- HTTP: `babashka.http-client`
- Logging: `clojure.tools.logging` / `taoensso.timbre`
- Transcription: the `mlx_whisper` CLI
- Reminders and Notes: AppleScript or JXA run through `osascript`
- Add a `:deps` entry to `bb.edn` only for a library Babashka does not include, and confirm it runs in Babashka

## Editing Guidance
- The user is the primary coder. By default, the agent guides, plans, explains, and reviews rather than implementing changes.
- Help the user develop idiomatic Clojure: explain design tradeoffs, encourage small functions and clear data flow, and review correctness, validation, and appropriate tests.
- Offer focused examples and incremental next steps so the user can write and understand the code. Make code changes only when the user explicitly requests implementation; direct requests to update documentation authorize those documentation edits.
- Keep this repo simple and educational
- Prefer small functions with clear names
- Keep each external tool behind its own namespace (e.g. `whisper`, `reminders`, `notes`) so routing logic stays pure data in, data out
- Pass arguments to `osascript` as argv (`on run argv`) rather than splicing user text into script source, to avoid quoting bugs and injection
- Check exit codes and surface stderr in `ex-info` data when a shell-out fails
- Mock shell-outs in tests; do not create real reminders or notes, or run long transcriptions, during routine verification
- Do not replace working code with large abstractions unless requested

## Commits
- Follow the commit message approach described in `docs/commit.md`
- Prefer commit messages in the form `<symbol> <scope> - <summary>`

## Verification
- After changes, verify the namespace loads with:
  `bb -e "(require 'memo-router.core)"`
- If relevant, exercise the changed function from the REPL
