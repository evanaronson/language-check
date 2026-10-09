# History

Status: proposal, not built. Branch `feature/history`.

## Decisions (9 Oct)

| Question | Decision | Consequence |
|---|---|---|
| Who reads it, where | On the phone first; a server later | Rows are upload-ready; no client-side encryption the server couldn't undo |
| Feeds back into checks? | No. A writer profile is premature | Checks stay stateless; history is write-only until insights |
| Full text or corrections only | Full text, keep everything | `text`, `finalText`, `meaning` and raw verdicts are stored |
| Second platform | Not a consideration for this feature | Framework SQLite; data classes stay Android-free anyway |
| Categories | None at check time. Patterns are found later by a model reading the raw edits and their reasons | No taxonomy, no `category` field; raw verdicts and `why` are kept |
| Raw data access | Should be available | Export in v1 |
| Where history lives | Home-first: the launcher screen becomes Home with Recent; Settings behind a gear; no button on the card | See UX |
| Other users | Possibly friends, not public | On by default, plainly worded; consent moment designed before friends install |

Linguize remembers every check and what came of it, on the phone, so that later it can tell you the kinds of mistakes you tend to make. This document is the analysis and the spec for the recording half. The insight half ("you drop accents on verb endings") is a later feature that can't exist until there is history to read.

## The problem

Every check is thrown away the moment the card closes. The model sees the same mistakes from the same person week after week and nothing accumulates. The future feature needs months of data, so the recording has to start now, and it has to be recorded in a shape the future feature can use, or we'll be re-running the model over old text to recover what we could have kept.

Two things make this harder than "write a row":

1. **It's private.** The texts are messages to a partner, friends and colleagues. Today the app holds nothing but settings and encrypted keys. After this, it holds a diary. That changes what the app is, and the UX has to say so plainly.
2. **It has to outlive its first storage.** The data should live on the phone now and be able to move to a server later without a rewrite, and the insight feature should be able to read it without a migration.

## Goals

- Every check is recorded from the moment it starts, including ones that fail, with what was suggested and what the writer did about each suggestion.
- The record is enough to answer, later: *which kinds of mistakes, in which language, how often, and did the writer take the fix or not.*
- Recording never slows the card down or changes how it behaves.
- The writer can see that it's happening, turn it off, and delete it.
- The same data model works unchanged as the payload of a future sync.

## Non-goals (this version)

- **Insights.** No "you tend to…" screens. There's nothing to say yet.
- **Sync, server, accounts.** The model is ready for it; none of it is built.
- **Search** across history. Lists and delete only.
- **Encrypting history at rest** beyond the app sandbox. See *Privacy* for why, and when to revisit.
- **Retention policies** (auto-delete after N days). Clear-all and delete-one are enough for one writer.

## How it fits the app

The app already has one place that knows everything about a check: `CheckViewModel` owns the text, the language, the settled answers, the revision (every suggested edit and what's accepted), and the moment the card closes. Recording hooks in there and nowhere else. The three hosts (selection menu, accessibility button, settings tester) change by one argument.

```
CheckViewModel ──events──▶ HistoryRecorder ──rows──▶ HistoryStore (SQLite)
                                 │                        │
                          pure Kotlin, tested       data/ package
```

- `HistoryRecorder` turns what happens in the view model into records. It's pure Kotlin with no Android in it, so the mapping (which suggestion ended up accepted, undone, ignored, retired, copied) is unit-tested like the edit engine.
- `HistoryStore` is an interface with one implementation on the framework's SQLite. The recorder and the settings screen talk to the interface. If storage changes (SQLDelight for an iOS port, a server), the recorder doesn't.
- `App` gets an application-wide coroutine scope. The close-of-card write happens as the view model is being cleared, when `viewModelScope` is already cancelled, so it needs a scope that outlives the screen.

### What the recorder sees

| Event | From | Recorded as |
|---|---|---|
| `check(text, language, origin)` | any host | a new **session**, written immediately with `closedAt = null` |
| verdict arrives, or fails | `run()` | an **attempt** appended to the session (raw verdict, settled answers, failure) |
| `accept`, `acceptAll`, `undo` | card | remembered in memory (`everAccepted`) to tell *undone* from *ignored* later |
| Copy of a section's preview | card | remembered in memory: that kind's remaining suggestions were taken by copying |
| close: `dismiss()`, a new `check()`, or `onCleared()` | any host | the session is **closed**: final text, outcome, and one **suggestion** row per edit with its decision |

Closing is the one subtle part. Today the hosts read `workingText` and close in three different ways; the recorder doesn't care, because all three paths end in `dismiss()` or `onCleared()` on the view model. Nothing is written on every tap; one write at open, one per attempt, one transaction at close.

### Decisions, defined

Each suggestion ends the session with exactly one decision:

| Decision | Meaning |
|---|---|
| `accepted` | applied in the final text (and, if a fix, not replaced by an accepted rewording) |
| `undone` | accepted at some point, not applied at the end |
| `retired` | a fix overtaken by an accepted rewording (the rewording includes it) |
| `copied` | the writer copied that section's preview; the suggestion left the app in the copy |
| `ignored` | still on offer when the card closed |
| `superseded` | from an earlier attempt; a re-check (answered assumption, retry, new settings) replaced it |

Session outcome is one of `applied` (changes went back to the app), `copied` (nothing applied, text copied), `none` (closed with nothing), `failed` (last attempt failed), `abandoned` (never closed: the process died; marked on next start).

These are the facts the insight feature needs: *this writer makes agreement mistakes in Catalan and takes the fix 90% of the time; the model keeps suggesting "quedar" and the writer ignores it.*

## Data model

Two tables. Rows are immutable once the session closes, except for soft deletion. Everything is designed so a row can be uploaded as-is later.

**Every row has:** `id` (client-generated UUID, never an autoincrement), `createdAt` / `updatedAt` (epoch ms, UTC), `deletedAt` (soft delete; the row stays until a sync could carry the deletion), `deviceId` (a random UUID made once per install), `schema` (integer, the shape of this row).

### `sessions`, one per time the card opens

| Column | Notes |
|---|---|
| `startedAt`, `closedAt` | `closedAt` null while open |
| `origin` | `menu` · `button` · `tester` |
| `hostApp` | package name of the app the text came from; null for the tester. Optional, see questions |
| `requestedLanguage` | `Catalan` · `Spanish` · null for auto |
| `language` | what the model judged the text as. **New verdict field**, see *Prompt changes* |
| `text` | what was checked |
| `textHash` | short hash, for "same message checked again" without comparing texts |
| `finalText` | what went back to the app; null when nothing was applied |
| `outcome` | see above |
| `failure` | `CheckFailure.Reason`, when `outcome = failed` |
| `punctuation`, `judgments` | the settings in force; they change what gets suggested |
| `provider`, `model`, `promptHash`, `appVersion` | so verdicts from different models and prompt versions aren't compared as if equal |
| `attempts` | JSON: `[{at, settled: [{about, answer}], verdict (raw), failure?}]`. The audit trail; never queried, kept so anything can be re-derived |
| `meaning` | from the last successful attempt |

### `suggestions`, one per edit the writer saw

| Column | Notes |
|---|---|
| `sessionId` | FK |
| `attempt` | index into the session's attempts |
| `kind` | `fix` · `natural` |
| `start`, `end`, `fromText`, `toText`, `why` | as the card showed it, positions in the session's `text` |
| `decision`, `decidedAt` | see above |

Why not an event log? An append-only log of every tap is the purest shape for sync and would capture undo trajectories exactly, but it needs projection code from day one to show anything, and the insight feature would be a projection too. Two tables that a human can read in a SQLite browser, with the raw verdicts kept as JSON for anything we didn't think of, is the smaller debt. The `attempts` JSON is the escape hatch: if a future question needs something the columns don't have, it's in there.

Why not one JSON document per session? It would be simplest to write, but `suggestions` is the table the insight feature aggregates over (by language, decision, date, text), and that wants real columns and an index, not `json_extract`.

### Sync-readiness, concretely

What's done now so a server later is additive:

- UUIDs and `deviceId`: rows from two phones merge without collisions.
- `updatedAt` and soft delete: "everything changed since last sync" is one query, and deletions travel.
- `schema` and `promptHash` versions on the rows: the server never has to guess how to read an old row.
- The row classes are `@Serializable` Kotlin: the wire format is the storage format. Versioned, so that coupling is a feature, not a trap.
- Nothing in a row depends on local state (no int ids, no references to settings).

What isn't done: a `syncState` table, an uploader, auth, conflict rules. When it comes: last-write-wins by `updatedAt`; with one writer per device that's never wrong in practice.

## Prompt changes (prerequisite)

One field the verdict doesn't have today, cheap in tokens:

1. **`language`**: the language the model judged the text as, as a name ("Catalan", "Spanish", "French", …), always filled, including when `status` is `wrong_language` (then it's the language it actually found). Without this, auto-detected checks can't be grouped by language. Side benefit for the card: "Not Catalan — this looks like Spanish."
The eval script and its cases get the new field; the prompt's examples get `language` values.

No mistake categories are assigned at check time. The insight feature will hand a model the raw edits (from, to, why) across the whole history and let it find the patterns; at this scale that's one or two calls, it's consistent across all of history, and the structure (if any) is chosen with data in hand.

## UX

### Where history lives

The first instinct is a small history button on the card, next to the language picker and the settings gear, opening the list inside the card. The argument for it is real: the card is where the writer is 95% of the time, and a past check should look the way it looked the first time. The second half of that is right and the first half is a trap.

**The card is a moment, not a place.** It lives for a few seconds between "did I write this right" and send, over someone else's app, usually with the keyboard up. Everything on it competes with the two controls that matter (Replace, Done). History is reflective: you read it when you're not writing to anyone. Putting a diary behind a button on the thing that has to feel like spellcheck adds a third job to a surface that's good because it has one.

Two smaller problems: that header (picker + gear) only exists on the accessibility overlay. The selection-menu card, which is most checks, has no header, so a history button there means adding chrome to the fastest path. And there's no action to take on a past check from inside the card except reading it.

**What the instinct gets right:** a past check should be rendered with the card's own content (`ReviewContent`, the highlights, the reasons, what was taken), not as a settings-style form. The tester already proves the card's content works inside a full-screen page. So the detail view reuses the component, wherever it lives.

**History should reach the card as data, never as navigation.** The in-the-moment uses of history don't need a button:

- *Instant re-open.* Checking the same text again within a few minutes (closed by accident, app lost the card) reuses the stored verdict: no model call, no wait. Free, invisible, and the first thing that makes the recording pay.
- *"You've had this fix before."* A fix on the card can carry one quiet line ("4th time this month"). That's the insight feature arriving in-situ. Later; noted here so the data supports it (it does: `fromText`, `toText`, `decision`).

### The launcher screen becomes Home

Today the launcher screen is Settings with a tester at the bottom. The tester and history are the same thing, checks, at different times: "check something now" above "what you checked before". So:

**Home**
> lingu·ize                                   ⚙
> [ text box ]  Linguize ▾
> (the result card, when a check is running)
> Recent
> ▸ 9 Oct · WhatsApp · "Hola bebé, estoy en casa…" · 3 fixes · 2 taken
> ▸ 9 Oct · Telegram · "Com estàs amb la pluja?" · looks good
> …

- Tap a row: the check opens below it or on a page, rendered with the card's content, read-only, with Copy. Delete from there.
- Insights, later, are a strip between the text box and the list. They don't need a new screen either.
- The gear (top right) opens **Settings**: the existing Checking, Model and Apps-without-the-menu sections, plus a History section (toggle, count, Clear, Export). Browsing lives on Home; controls live in Settings.
- Two screens, a `BackHandler` and a `screen` state in the activity. No navigation library until there's a third.
- The overlay's gear keeps opening the same activity.

Design debt this removes: the app's most interesting content was heading for a settings section, and the launcher screen was going to need splitting anyway when insights arrived. Doing it now is one move instead of two, and it's mostly moving sections that already exist.

### If a compact entry is ever wanted

The glyph is Material's `history`: a clock with a counter-clockwise arrow. It's the one shape browsers and Google's apps all use for "what I did before", so it reads without a label; a plain clock means scheduling. The recommended design doesn't need it, because Home *is* history. If it's wanted on the overlay header later, it sits between the picker and the gear and opens Home.

### Settings · History

> **History**
> ☑ Remember what I check
> Kept on this phone only, so Linguize can later show you the mistakes you tend to make. Turning this off keeps what's already here.
> 312 checks since 9 Oct · **Clear** · **Export**

- *Remember* is on by default. Off stops recording; it doesn't delete.
- *Clear* asks once, then deletes everything.
- *Export* writes a JSON Lines file (one session per line, suggestions nested) and offers it through the share sheet. It's the sync payload in disguise, and the way to look at the raw data in a notebook.

### Privacy, stated plainly

- Nothing leaves the phone. The data extraction rules already exclude the app from backups and device transfer, so history doesn't land in Google's cloud.
- The tester's text is recorded like any other check, tagged `origin = tester`, and the insight feature excludes it by default. "Everything you check" is simple to explain; exceptions aren't.
- Failed checks are recorded (half the reliability story) and hidden from Recent by default.
- Read-only selections are recorded; their suggestions can only end as `copied` or `ignored`.
- Column encryption with the existing Keystore key is possible and costs: no SQL over text, slower lists, more code, and a server couldn't read it. The sandbox plus backup exclusion is the right level while the app is personal or among friends. Before friends install: the History section's wording is the consent, and recording should be shown once (a line on Home the first time), not assumed.

## Requirements

### Must have (v1)

- [ ] `language` in the verdict schema, prompt, examples and eval.
- [ ] `HistoryStore` interface; SQLite implementation with `sessions` and `suggestions`; DB created lazily; all I/O off the main thread.
- [ ] `HistoryRecorder` (pure Kotlin) with tests for every decision and outcome in the tables above, including: undo after accept, rewording retiring a fix, Copy, re-check superseding, a failed attempt, close with nothing.
- [ ] `CheckViewModel` reports to the recorder; `check()` takes an `origin`; the three hosts pass it.
- [ ] Open sessions found on process start are marked `abandoned`.
- [ ] Launcher screen split into Home (text box, result card, Recent list) and Settings (gear); `BackHandler`, no nav library.
- [ ] Recent list on Home, newest first; tap opens the check rendered with the card's content; delete one.
- [ ] Settings · History section: toggle, count, Clear, Export (JSON Lines via the share sheet).
- [ ] Same text checked again within 10 minutes reuses the stored verdict instead of calling the model.
- [ ] Card latency unchanged: no history work on the path between tapping Linguize and the request leaving.
- [ ] `App` gains an application scope; the close write uses it.

Acceptance, the ones worth spelling out:

- Given history is on, when a check starts, then a session row exists before the model answers, even if the app is killed a second later.
- Given a fix was accepted and a rewording covering it was then accepted, when the card closes, then the fix is `retired` and the rewording `accepted`, and "changes applied" on the card still reads 1.
- Given the writer taps Replace then Undo then closes, then the suggestion is `undone`, not `ignored`.
- Given history is off, when a check runs, then no row is written, and the count in settings doesn't change.
- Given 2,000 sessions, when settings opens, then it opens as fast as today (the count is one indexed query, observed as a Flow).

### Should have (v1.5)

- [ ] `hostApp` recorded (menu: `callingPackage`; button: the field's package) and shown in Recent.
- [ ] Recent hides failed checks and the tester by default, with a way to show them.

### Later (designed for, not built)

- Insights: a model reads the kept edits and their reasons and names the patterns, shown as a strip on Home; "you've had this fix before" on the card.
- Sync: `syncState` table, uploader, opt-in separate from this one.
- Encrypt text columns before a public release.

## Tech-debt ledger

Taken on, deliberately:

- **Hand-written SQL on the framework's SQLite** instead of Room. No new dependency and no code generation (a KSP version can't be verified against this toolchain without CI round-trips), at the cost of writing two tables' worth of SQL and Flows by hand. A test checks every record field has a column. Room or SQLDelight remain an easy swap behind `HistoryStore`.
- **An application coroutine scope.** One new concept; it exists for the close write and nothing else should grow on it.
- **`origin` on `check()`.** One more parameter on the view model's entry point.

Avoided:

- No event-sourcing machinery, no DI framework, no navigation library, no encryption library, no sync scaffolding, no analytics.
- No change to the card's behaviour or to `core/`. The edit engine doesn't know history exists.
- No second copy of the data model for the wire: the row classes are the payload.


## Open questions

- Home-first (above) or the original idea (a settings section plus a history button on the card)? The spec argues for Home-first; it's the founder's call.
- Whether the raw verdict in `attempts` needs a size cap (a 3,000-character text twice over plus lists is ~15 KB; fine, but confirm).

## Phasing

0. Prompt: `language`, examples, eval. Ship on its own; it's visible nowhere and de-risks the rest.
1. Record: store, recorder, view-model hook; Home/Settings split with the Recent list and the History section; export. Ship. Let it run.
2. Host app in Recent; filters; instant re-open polish.
3. Insights: a separate spec, written once there are a few hundred fixes to look at.
