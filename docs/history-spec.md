# History

Status: proposal, not built. Branch `feature/history`.

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
CheckViewModel ──events──▶ HistoryRecorder ──rows──▶ HistoryStore (Room)
                                 │                        │
                          pure Kotlin, tested       data/ package
```

- `HistoryRecorder` turns what happens in the view model into records. It's pure Kotlin with no Android in it, so the mapping (which suggestion ended up accepted, undone, ignored, retired, copied) is unit-tested like the edit engine.
- `HistoryStore` is an interface with one Room implementation. The recorder and the settings screen talk to the interface. If storage changes (SQLDelight for an iOS port, a server), the recorder doesn't.
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
| `category` | from the taxonomy below. **New verdict field** |
| `start`, `end`, `fromText`, `toText`, `why` | as the card showed it, positions in the session's `text` |
| `decision`, `decidedAt` | see above |

Why not an event log? An append-only log of every tap is the purest shape for sync and would capture undo trajectories exactly, but it needs projection code from day one to show anything, and the insight feature would be a projection too. Two tables that a human can read in a SQLite browser, with the raw verdicts kept as JSON for anything we didn't think of, is the smaller debt. The `attempts` JSON is the escape hatch: if a future question needs something the columns don't have, it's in there.

Why not one JSON document per session? It would be simplest to write, but `suggestions` is the table the insight feature aggregates over (by category, language, decision, date), and that wants real columns and an index, not `json_extract`.

### Sync-readiness, concretely

What's done now so a server later is additive:

- UUIDs and `deviceId`: rows from two phones merge without collisions.
- `updatedAt` and soft delete: "everything changed since last sync" is one query, and deletions travel.
- `schema`, `promptHash`, `taxonomy` versions on the rows: the server never has to guess how to read an old row.
- The row classes are `@Serializable` Kotlin: the wire format is the storage format. Versioned, so that coupling is a feature, not a trap.
- Nothing in a row depends on local state (no int ids, no references to settings).

What isn't done: a `syncState` table, an uploader, auth, conflict rules. When it comes: last-write-wins by `updatedAt`; with one writer per device that's never wrong in practice.

## Prompt changes (prerequisite)

Two fields the verdict doesn't have today, both cheap in tokens, both much harder to add later than now:

1. **`language`**: the language the model judged the text as, as a name ("Catalan", "Spanish", "French", …), always filled, including when `status` is `wrong_language` (then it's the language it actually found). Without this, auto-detected checks can't be grouped by language. Side benefit for the card: "Not Catalan — this looks like Spanish."
2. **`category`** on every fix and every rewording. A fixed, coarse taxonomy, because the insight feature groups by it and a taxonomy is painful to change once months of rows use it:

| id | Covers | Example |
|---|---|---|
| `accent` | diacritics | estas → estàs |
| `spelling` | other orthography | pícnic, ll/l·l |
| `agreement` | gender, number | unes → uns, bo → bons |
| `verb` | tense, mood, person, conjugation | me ha traído → he traído |
| `function_word` | articles, prepositions, pronouns (ho, hi, en) | a → en |
| `word_choice` | calques, anglicisms, false friends | algunas → unas, bodega → tienda |
| `word_order` | | |
| `punctuation` | commas, ¿ ¡, full stops | |
| `capitalization` | | neo → Neo |
| `typo` | duplicated or dropped letters and words | de de → de |
| `register` | formality, tu/vostè | |
| `other` | anything else | |

Punctuation and capitalization are kept so the insight feature can *downweight* them: they depend on the Punctuation setting and aren't what the writer wants to hear about. Rewordings use the same list (mostly `word_choice`, `word_order`, `register`, `other`).

Stored with `taxonomy = 1` on the row. If the list changes, old rows keep their version and the insight feature maps or ignores them.

The eval script and its cases get the new fields; the prompt's examples get `language` and `category` values.

## UX

The feature's visible surface should be as small as the app's: one quiet place that says what's kept, with the controls next to it. No onboarding, no banner on the card.

### Settings · History (v1)

A fifth section on the settings screen:

> **History**
> ☑ Remember what I check
> Kept on this phone only, so Linguize can later show you the mistakes you tend to make. Turning this off keeps what's already here.
> 312 checks since 9 Oct · **Clear** · **Export**

- *Remember* is on by default (see questions). Off stops recording; it doesn't delete.
- *Clear* asks once, then deletes everything (hard delete locally; soft-delete semantics only matter once sync exists).
- *Export* writes a JSON Lines file (one session per line, suggestions nested) and offers it through the share sheet. It's the cheapest way to see that the recording is right, and it's the sync payload in disguise.

### History list (v1.5)

The "312 checks" line becomes tappable and opens a page, the same drill-in pattern the card already uses for Meaning and Assumptions: a list of checks, newest first (date, the app it came from, the first line of text, "3 fixes · 1 rewording · 2 taken"), tap for the detail (the text with its highlights and what was done with each), swipe or button to delete one.

This is the first second screen in the app. Do it with a `BackHandler` and a `screen` state in `SettingsActivity`, not a navigation library. A library is worth it at the third screen, not the second.

### Design debt to see coming

When insights arrive, they're the app's most interesting screen and they don't belong inside settings. The launcher screen will want to become *Home* (insights, then Try it) with Settings secondary. Nothing in v1 should fight that: the History section is a settings section about a setting, and the list is reachable from it, but the list's layout (a list of checks with a detail page) is what the Home screen's "recent" will reuse. Don't build the list as a settings-styled form.

The card itself doesn't change. The one temptation is a "saved" indicator on the card; resist it. Recording is spellcheck-grade invisible, like the rest of the app.

### Privacy, stated plainly

- Nothing leaves the phone. The data extraction rules already exclude the app from backups and device transfer, so history doesn't land in Google's cloud.
- The tester's sample text is recorded like any other check, tagged `origin = tester`, and the insight feature excludes it by default. Recording it keeps "what gets recorded" simple to explain: everything you check.
- Failed checks are recorded (they're half the reliability story) and hidden from the list by default.
- Read-only selections are recorded; their suggestions can only end as `copied` or `ignored`.
- Column encryption with the existing Keystore key is possible (same code as `ApiKeys`) and costs: no SQL over text, slower lists, more code. The sandbox plus backup exclusion is the right level for a sideloaded personal app. Revisit before the app is public.

## Requirements

### Must have (v1)

- [ ] `language` and `category` in the verdict schema, prompt, examples and eval.
- [ ] `HistoryStore` interface; Room implementation with `sessions` and `suggestions`; DB created lazily; all I/O off the main thread.
- [ ] `HistoryRecorder` (pure Kotlin) with tests for every decision and outcome in the tables above, including: undo after accept, rewording retiring a fix, Copy, re-check superseding, a failed attempt, close with nothing.
- [ ] `CheckViewModel` reports to the recorder; `check()` takes an `origin`; the three hosts pass it.
- [ ] Open sessions found on process start are marked `abandoned`.
- [ ] Settings · History section: toggle, count, Clear.
- [ ] Card latency unchanged: no history work on the path between tapping Linguize and the request leaving.
- [ ] `App` gains an application scope; the close write uses it.

Acceptance, the ones worth spelling out:

- Given history is on, when a check starts, then a session row exists before the model answers, even if the app is killed a second later.
- Given a fix was accepted and a rewording covering it was then accepted, when the card closes, then the fix is `retired` and the rewording `accepted`, and "changes applied" on the card still reads 1.
- Given the writer taps Replace then Undo then closes, then the suggestion is `undone`, not `ignored`.
- Given history is off, when a check runs, then no row is written, and the count in settings doesn't change.
- Given 2,000 sessions, when settings opens, then it opens as fast as today (the count is one indexed query, observed as a Flow).

### Should have (v1.5)

- [ ] Export (JSON Lines via the share sheet).
- [ ] History list and detail page; delete one.
- [ ] `hostApp` recorded (menu: `callingPackage`; button: the field's package).

### Later (designed for, not built)

- Insights: aggregate `suggestions` by `category × language × decision × week`.
- Sync: `syncState` table, uploader, opt-in separate from this one.
- Encrypt text columns before a public release.

## Tech-debt ledger

Taken on, deliberately:

- **Room + KSP.** The first dependency with code generation in the build. It's the standard, it gives migrations, compile-checked queries and Flows. Risk: KSP must match Kotlin 2.4.20 under AGP 9's built-in Kotlin; the first task is a CI spike. Fallback if it fights: `androidx.sqlite` with hand-written SQL (two tables, ~150 lines) or SQLDelight (which also covers an iOS port).
- **An application coroutine scope.** One new concept; it exists for the close write and nothing else should grow on it.
- **`origin` on `check()`.** One more parameter on the view model's entry point.

Avoided:

- No event-sourcing machinery, no DI framework, no navigation library, no encryption library, no sync scaffolding, no analytics.
- No change to the card's behaviour or to `core/`. The edit engine doesn't know history exists.
- No second copy of the data model for the wire: the row classes are the payload.

Debt that the design cannot remove and that is worth naming: the taxonomy. Twelve ids chosen now shape what the insight feature can say in six months. They're coarse on purpose; it's easier to split a category than to merge two.

## Open questions

Decisions for the founder:

1. **On by default, or opt-in?** Recommendation: on, with the section visible, while the app is personal. Flip to opt-in (or a one-time line in settings) before anyone else installs it.
2. **Is the taxonomy right?** The 12 categories above are the one decision that gets expensive to change.
3. **Record `hostApp`?** Cheap, useful for debugging write-back problems and for filtering the list; it's also the one piece of metadata a reader could find surprising ("it knows I was in WhatsApp"). Recommendation: record it.
4. **Export in v1?** It's the only way to verify the recording without the list. Recommendation: yes if the share-sheet plumbing (a `FileProvider`) stays under an hour; otherwise v1.5.

For engineering, non-blocking:

- Room/KSP under this build chain (spike first).
- Whether `attempts` JSON should cap the raw verdict size (a 3,000-character text twice over plus lists is ~15 KB; fine, but confirm).

## Phasing

0. Prompt: `language`, `category`, examples, eval. Ship on its own; it's visible nowhere and de-risks the rest.
1. Record: store, recorder, view-model hook, settings section. Ship. Let it run.
2. See: export, list, detail, delete-one.
3. Insights: a separate spec, written once there are a few hundred fixes to look at.
