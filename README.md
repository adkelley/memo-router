# Memo Router

Transcribes Just Press Record voice memos with MLX Whisper and routes each one
into Apple Reminders or Apple Notes.

## Requirements

- macOS on Apple Silicon
- [Babashka](https://babashka.org) (`brew install borkdude/brew/babashka`)
- `ffmpeg` (`brew install ffmpeg`)
- MLX Whisper as a uv tool (`uv tool install mlx-whisper`)
- Just Press Record with iCloud sync turned on
- A Shortcut for adding shopping items (see [Shopping list](#shopping-list))

## Configuration

Settings live in `config.edn` in the project root. It is git-ignored because it
holds machine-specific paths, so create it yourself with this shape:

```clojure
{:memo-dir  "/Users/<you>/Library/Mobile Documents/iCloud~com~openplanetsoftware~just-press-record/Documents"
 :manifest  "/Users/<you>/Library/Application Support/memo-router/processed.txt"
 :settle-ms 60000
 :whisper   {:bin   "/Users/<you>/.local/bin/mlx_whisper"
             :model "mlx-community/whisper-large-v3-turbo"}
 :reminders {:account "iCloud"
             :list    "Inbox"
             :routes  {"Home" {:list "Home"}
                       "Work" {:account "Work" :list "Tasks"}}}
 :notes     {:account "iCloud"
             :folder  "Notes"
             :routes  {"Work" {:account "Work"}
                       "Acme" {:account "Acme Corp" :folder "Memos"}}}
 :shopping  {:shortcut "Add to Shopping List"}}
```

| Key | Meaning |
|---|---|
| `:memo-dir` | Just Press Record's iCloud folder. Memos sit in date folders, e.g. `2026-10-31/11-23-32.m4a`. |
| `:manifest` | Where Memo Router records processed memos. You only choose the path: the file and its folder are created on the first successful run, and each handled memo is appended as a path relative to `:memo-dir` (e.g. `2026-10-31/11-23-32.m4a`). Use `bb re-process` to have a memo processed again (see [Processing a memo again](#processing-a-memo-again)). |
| `:settle-ms` | Skip memos modified more recently than this, so files still syncing from iCloud are picked up on a later run. |
| `:whisper :bin` | Absolute path to the `mlx_whisper` command (`which mlx_whisper`). |
| `:whisper :model` | Hugging Face model used for transcription. Downloaded on first use into `~/.cache/huggingface/hub/`. |
| `:reminders :account` | Optional. Reminders account (e.g. `"iCloud"`) that holds `:list`. Omit it to search all accounts, which is fine unless the same list name exists in more than one. |
| `:reminders :list` | Optional. Reminders list for memos classified as reminders. Must match an existing list name exactly; omit it to use your default list. |
| `:reminders :routes` | Optional. Map of spoken name to destination, `{:list "..." :account "..."}`, for sending a reminder to another list (see [Routing reminders to a list](#routing-reminders-to-a-list)). `:list` is required. Without `:account`, the route uses the top-level `:account`. Add `:link :spotify` to put a Spotify search link in the reminder's notes (see [Spotify links](#spotify-links)). |
| `:notes :account` | Recommended. Notes account (e.g. `"iCloud"`) that notes are created in. Omit it to use Notes' default account, but that can change when accounts are added or the setting changes, so name it explicitly if you have work or client accounts. |
| `:notes :folder` | Optional. Folder within that account for everything else. Must match an existing folder name exactly; omit it to use the account's default folder. Folder names can repeat across accounts, which is why the lookup is scoped to one account. |
| `:notes :routes` | Optional. Map of spoken keyword to destination, `{:account "..." :folder "..."}`, for sending a note to another account (see [Routing notes to an account](#routing-notes-to-an-account)). `:account` is required. Without `:folder`, the note goes to that account's default folder, not the top-level `:folder`. |
| `:shopping :shortcut` | Name of the Shortcut that adds one item to your shopping list (see [Shopping list](#shopping-list)). Must match its name in the Shortcuts app exactly. |

Reminders and Notes are driven through `/usr/bin/osascript`, and shopping items
through `/usr/bin/shortcuts`. Both are part of macOS, so they need no
configuration. `:reminders`, `:notes` and `:shopping` each accept an optional
`:bin` that overrides that path; the tests use it to substitute a fake.

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

### Skipped memos

A memo with a blank or one-word transcript is skipped: nothing is created, and
it is marked processed so it isn't tried again. Whisper often invents a single
word, such as "You", from a recording of silence. The log shows these as
`:skipped`, and the audio stays in iCloud.

### Processing a memo again

`bb re-process` removes a memo from the manifest, so the next run handles it
again from the audio. It is most useful after a parsing fix or a change of
model. Failed memos do not need it, since they are retried on their own.

```bash
bb re-process 2026-10-31/11-23-32.m4a
```

The memo's path is its recording date and time. To find one, look it up by
that time in Just Press Record, or search the manifest for the date:

```bash
grep 2026-10-31 "/Users/<you>/Library/Application Support/memo-router/processed.txt"
```

It accepts `--config-path`, like `bb process`, and says so if the memo is not in
the manifest. It does not touch the original reminder or note, so if that still
exists, you will end up with two. Deleting a reminder or note never changes the
manifest either, so processing a memo again is always your choice.

### Routing notes to an account

Start a note memo with a keyword from `:notes :routes` to send it to that
account, e.g. "Acme note: call the auditor about Q3." The keyword is matched
at the start of the transcript, ignoring case, optionally followed by "note"
and `:`, `,` or `.`, and is left out of the note's title. When several
keywords match, the longest wins: with keys "Acme" and "Acme Corp", "Acme
Corp: …" uses the "Acme Corp" route. A note with no
keyword goes to the top-level `:account` and `:folder`. "Acme, remind me to …"
becomes an Acme note, because the reminder phrase must open the memo. To route
a reminder, see [Routing reminders to a list](#routing-reminders-to-a-list).

Account names must match exactly; list them with:

```bash
osascript -l JavaScript -e 'Application("Notes").accounts.name()'
```

Whisper may spell a keyword differently than you expect (e.g. "A.D.K."), so
add aliases as extra keys pointing to the same destination.

### Routing reminders to a list

Start a memo with a name from `:reminders :routes`, followed by "list", to send
it to that list:

> Home list, remind me to do the dishes tomorrow at 3.

This creates "Do the dishes" in the Home list, due tomorrow at 15:00. The name is
matched at the start of the transcript, ignoring case, and can be followed by
`:`, `,` or `.`. It is left out of the title, and the reminder's notes keep the
full transcript. Naming a list is enough, so "Home list: do the dishes" works
without "remind me to".

A reminder that names no list goes to the top-level `:list`. A name that isn't in
`:routes` isn't treated as a list, so "Garage list, remind me to sweep" becomes a
note until you add a route for it. When several names match, the longest wins.

A route's key is what you say, and its `:list` is the real list, so a short name
can stand in for a long one: `"Gifts" {:list "Gift Ideas"}`. Each route
looks in the top-level `:account` unless it sets its own. List your accounts and
their lists with:

```bash
osascript -l JavaScript -e 'Application("Reminders").accounts().map(a => a.name() + ": " + a.lists.name().join(", ")).join("\n")'
```

Whisper can mishear these phrases: "home list" sometimes comes through as
"homeless", which won't match.

### Spotify links

A route with `:link :spotify` adds a Spotify search link to each reminder's
notes, so one tap opens the results:

```clojure
"Spotify" {:list "Spotify" :link :spotify}
```

> Spotify list, remind me to look into the album Animals by Pink Floyd.

This creates "Look into the album Animals by Pink Floyd", with
`https://open.spotify.com/search/Animals%20Pink%20Floyd` in its notes. The search
leaves out a leading "look into", "look up", "listen to", "check out", "search
for" or "find", a kind word like "the album", "song", "artist" or "band", and
"by" or "from".

The link searches everything, with no artist, album or song filter. Spotify's
website understands a filter, but the iPhone app reads it as the search text, so
the link would search for "albums". Reminders also has a URL field, but it can't
be set from a script, so the link goes in the notes, where it can be tapped.

A song name containing "by" or "from", like "Stand by Me", loses that word from
the search.

### Shopping list

Ask to add items to your shopping list, and each one goes on the list
separately:

> Remind me to add pickles, tomatoes, and flour to my shopping list.

The memo has to be just that request. It can start with "Remind me to", "Don't
forget to" or simply "Add", and end with "my" or "the", then "shopping list" or
"grocery list". Anything else is handled as an ordinary reminder or note.

Items are added through a Shortcut, because Reminders sorts those into grocery
sections and leaves items added through `osascript` uncategorized. Create one
that takes text as its input and adds it to your grocery list with the Add New
Reminder action, then put its name in `:shopping :shortcut`. To check it:

```bash
echo "Tomatoes" | shortcuts run "Add to Shopping List"
```

Items are split on commas and "and", so "mac and cheese" becomes two items. If
an item fails, the memo stays unprocessed and the next run starts it again, so
items added before the failure appear twice.

### Due dates on reminders

A reminder that mentions a date or time gets a due date, and the phrase is left
out of its title: "Remind me to call Dana tomorrow at 3" becomes "Call Dana",
due tomorrow at 15:00. The reminder's notes keep the full transcript. Dates in
notes are left alone.

Phrases are read relative to when the memo was recorded, taken from its file
name, not when it was processed. A memo recorded on Monday night that is
processed on Tuesday still treats "tomorrow" as Tuesday.

| Say | Due |
|---|---|
| `today`, `tomorrow` | That day, all-day |
| `Friday`, `on Friday`, `this Friday`, `next Friday` | The next Friday after the memo's date, all-day. Said on a Friday, this is a week away. |
| `in 2 hours`, `in 30 minutes`, `in an hour` | That long after the memo was recorded. Numbers can be digits or words up to twelve. |
| `in 3 days` | That day, all-day |
| `morning`, `afternoon`, `evening`, `night` after a date, e.g. `tomorrow morning` | That day at 09:00, 14:00, 18:00 or 20:00 |
| `tonight`, `this afternoon`, `in the morning` | The same times, on the next day that time is still ahead |
| `at 3 pm`, `at 3:30 p.m.`, `at 4.55 pm` | That time on the date spoken. With no date, today if it is still ahead, otherwise tomorrow. |
| `at 3`, `at 3:30`, `at 3 o'clock` | See below |

For an hour with no am or pm, Memo Router finds the first day on which 3:00 or
15:00 is still ahead, and if both are, takes the one between 07:00 and 18:59. A
part of the day decides it instead, so "tomorrow evening at 8" is 20:00.

| Recorded | Said | Due |
|---|---|---|
| Monday 10:00 | at 3 | Monday 15:00 |
| Monday 10:00 | at 9 | Monday 21:00 |
| Monday 16:00 | at 3 | Tuesday 15:00 |
| Monday 10:00 | tomorrow at 9 | Tuesday 09:00 |

To avoid reading numbers as times, an hour with no am or pm counts only before
the end of a sentence, punctuation, or "to". "Remind me to look at 3 options"
gets no time, and neither does "tomorrow at 3 with Dana", which is all-day
tomorrow with "at 3" left in the title. Say "3 pm" or "3 o'clock" to be certain.

Say the part of the day before the time. "Tomorrow evening at 9" is 21:00, but
"tomorrow at 9 in the evening" is all-day tomorrow, with "at 9 in the evening"
left in the title. An hour that Whisper writes as a word is not read either, so
"tomorrow at three" is all-day tomorrow with "at three" left in the title.

Only the first date in a memo is used; a second one stays in the title.
Recurring phrases such as "every Friday" are not supported and are treated as
the next Friday.

### Priority on reminders

Say a priority anywhere in a reminder, and it is set on the reminder and left out
of the title. "Remind me to call Dana, high priority" becomes "Call Dana", marked
High.

| Say | Priority |
|---|---|
| `high priority`, `high-priority`, `urgent`, `urgently` | High |
| `medium priority` | Medium |
| `low priority` | Low |

It works alongside dates and lists, and it can open the memo: "Home list, high
priority, do the dishes tomorrow at 3" and "Urgent, remind me to call Dana" both
work. A note keeps its words, so "Urgent matters for the board" stays a note.

The word "urgent" counts wherever it appears in a reminder, so "Remind me to read
the urgent email" becomes "Read the email", marked High.

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

Install it once by filling in the template, from the project folder (`$PWD`
becomes `{{project}}`):

```bash
sed -e "s|{{home}}|$HOME|g" -e "s|{{project}}|$PWD|g" \
  launchd/memo-router.plist.template > ~/Library/LaunchAgents/local.memo-router.plist
plutil -lint ~/Library/LaunchAgents/local.memo-router.plist
```

Then manage it with the `agent:` tasks in `bb.edn` (`bb tasks` lists them):

| Task | Does |
|---|---|
| `bb agent:start` | Load the job; it runs now and then every 5 minutes |
| `bb agent:stop` | Unload it until `agent:start` or your next login |
| `bb agent:run` | Run it now instead of waiting for the next interval |
| `bb agent:status` | Show its state; `last exit code = 0` means the last run succeeded |
| `bb agent:log` | Follow the log (Ctrl-C stops following, not the job) |

The log records memo names, outcomes and errors; runs that find no new memos
write nothing.

After editing the template, fill it in again with the `sed` command above,
then reload it with `bb agent:stop` and `bb agent:start`.

To remove it, stop it and delete the plist:

```bash
bb agent:stop
rm ~/Library/LaunchAgents/local.memo-router.plist
```

Changes to `config.edn` or the code need no reload: each run starts a fresh
`bb` process that reads them.

The tasks wrap these `launchctl` commands, if you need them directly:

```bash
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/local.memo-router.plist
launchctl bootout gui/$(id -u)/local.memo-router
launchctl kickstart gui/$(id -u)/local.memo-router
launchctl print gui/$(id -u)/local.memo-router
tail -f ~/Library/Logs/memo-router.log
```
