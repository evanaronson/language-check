# Linguize

**A gut check for what you just wrote in a language you're learning.**

## The problem

When you write in a language you're learning, especially at an intermediate level, you often want a quick check before you hit send: does this make sense, is anything wrong, would a native speaker say it this way?

There's no good tool for that. The instinctive move is Google Translate, but Translate is built for the opposite job: turning text *into* the language you're learning. Here the text is already in that language, and it's the part you're unsure about. You can paste it in and translate it back into your own language, and that tells you whether it's roughly understandable, which is better than nothing. But it won't tell you what you got wrong. A good translator reads past your mistakes and awkward phrasing, because understanding imperfect input is exactly what makes it good at translating. Checking your writing isn't a translation task. It's a different job, so it needs a different tool.

## What Linguize does

Linguize does that one job. Select what you wrote in any app, tap **Linguize** in the selection menu, and a card floats over the app with:

1. **Fixes:** the actual mistakes, each highlighted separately, with the minimum change and a short reason. Or "No fixes".
2. **More natural:** how a native speaker would say it, only when it's clearly better. Or "Sounds natural".
3. **Meaning:** what your text says, translated into your own language, so you can confirm it says what you meant (the part Google Translate was good for).

Replace the changes you want, one at a time or all at once, and you're back where you were. There's no chat and nothing to read beyond short reasons: it's a check, not a lesson.

## Install

**[Download the latest APK](https://github.com/evanaronson/language-check/releases/latest/download/linguize.apk)** (open it while signed in to GitHub, since the repo is private). Every push to `main` builds a new one; older builds are under [Releases](https://github.com/evanaronson/language-check/releases).

1. Open the link on your phone and open the downloaded file. When Android asks, allow your browser to install unknown apps.
2. Open **Linguize**, pick Google Gemini or OpenAI, paste that provider's API key ([Gemini](https://aistudio.google.com/apikey), [OpenAI](https://platform.openai.com/api-keys)) and tap Save. Keys are stored encrypted on the phone and are never in the code.
3. Try a sentence in **Try it** on the same screen. Then select text you've written in any app and look for **Linguize** in the selection menu (on Samsung it can be behind **⋮** in the menu).

That's all most apps need. Later builds install over the previous one because they're all signed with the same key.

### Optional: apps without the menu entry (Telegram, the Claude app…)

Some apps don't show other apps' entries in their selection menu. For those, Linguize can add an accessibility button instead: while typing, tap the button and the same card floats over the app, then accepted changes are written back into the text field. It reads the focused text field only when you tap the button.

Android makes this a few taps because the app wasn't installed from the Play Store:

1. **Settings → Accessibility → Installed apps → Linguize** and try to turn it on. Android blocks it with a "Restricted setting" message the first time; that's expected.
2. **Settings → Apps → Linguize → ⋮ (top right) → Allow restricted settings**, and confirm.
3. Back in **Settings → Accessibility → Installed apps → Linguize**: turn it on, along with its shortcut. A floating Linguize button appears (on Samsung you can choose between the floating button and the navigation-bar button).

The **Apps without the menu** section of Linguize's settings has the same steps and a button straight to Accessibility settings.

## How it works

Possible results: **Looks good**; fixes and/or a more natural rewording, each change highlighted separately; **Can't tell what this means**; **Not <language>** when a language is pinned in settings; or an error with Retry.

Fixes and rewordings are both worked out against the original text, so they're independent: tap a highlight to see why and **Replace** just that change, or **Replace all** for a whole section. Accepted changes disappear from the list; **Undo** reverts the last action, and closing the card in any way (Done, back, tapping outside) hands the text with accepted changes back to the app. Where a fix and a rewording touch (share any of the original's characters), accepting either one retires the other; changes that only sit next to each other are independent. Undo brings back what the last action accepted and anything it retired. The rules are written out in `review/Revision.kt`.

The selection menu can show several entries: **Linguize** (detects the language), **Catalanize** and **Castilianize**. Pick which ones appear in settings; tapping one checks the text as that language. Each entry is an `activity-alias` of `CheckActivity` in the manifest, switched on and off by `SelectionMenu`; adding a language means a `Language`, a `MenuEntry`, an alias and a label.

A quiet "ⓘ Meaning ›" line at the top of the card (or "ⓘ Meaning · N assumptions ›") leads to a page showing how the text was understood (back returns to the suggestions). **Meaning** is a plain translation of what you wrote into your own language, which is the app's UI language (English for now), so you can confirm it says what you meant. Meaning, assumptions and reasons are all written in that language.

Below it, where the text is genuinely ambiguous (who did something, when, who's speaking), **Assumptions** lists what was assumed. Each is presented as right; "Not right" offers alternatives, and picking one checks again with that answer, keeping accepted changes that didn't move. Answers last until the card closes.

The accessibility button (see Install) shows the same card in an overlay, with a "Linguize ▾" picker above it for checking as a specific language; accepted changes are written back into the field. The service (`accessibility/`) reads the focused field only when the button is tapped.

Settings also has a punctuation level (Strict, Moderate or Casual), which judgments to make (fix, naturalize or both), and a model picker that lists the models your key can use, live from the provider. Picking a model runs a short test check and shows how long it took, or the provider's error.

## Code layout

Packages under `app/src/main/java/com/evanaronson/languagecheck/`, each depending only on the ones above it:

| Package | Responsibility |
|---|---|
| `review/` | The core, with no Android or network code. `Verdict` is the model's answer as the schema defines it; `Edits` and `Alignment` turn it into `Edit`s anchored to the original text (positions come from aligning the texts, never from the model; every punctuation mark is its own fix; rewordings are found against the corrected text, so fixes never repeat in them, then mapped back onto the original); `Revision` tracks which edits are accepted; `interpret()` produces a `CheckResult`. Also the check options: `Language`, `Punctuation`, `Judgments`. |
| `llm/` | Talking to models. `Prompt` is the contract: instructions and schema from `assets/`, the user-message format, and reading answers. `GeminiClient` and `OpenAIClient` implement `ProviderClient` (check, list models) over plain REST; adding a provider means one more client. |
| `settings/` | `Settings` (choices) and `ApiKeys` (keys encrypted with an Android Keystore key). |
| `accessibility/` | The accessibility button: reads the focused field, shows the card in an overlay window, writes accepted changes back. `FieldText` handles checking a selection and putting the result back in place. |
| root | `CheckService` runs a check with the chosen provider, model and options; `SelectionMenu` turns the menu entries on and off; `App` creates the long-lived objects; `CheckActivity` opens from a menu entry; `MainActivity` hosts settings. |
| `ui/` | `card/`: the result card and its `CheckViewModel`, which owns the check and the accepted changes. `settings/`: the settings screen and its `SettingsViewModel`. `components/`: shared controls. |

The prompt and schema live in `app/src/main/assets/` so the eval script uses exactly what the app sends.

## Tuning the judgments

The prompt is where most of the product lives. To test a prompt change against real sentences, including how often it nags about text that's already fine, and to measure latency:

```sh
GEMINI_API_KEY=... python3 eval/run.py
OPENAI_API_KEY=... python3 eval/run.py --provider openai
```

Add cases to `eval/cases.jsonl` when the app gets something wrong.

## Build locally

You need JDK 17+ and the Android SDK. Run `./gradlew assembleRelease`; the APK is written to `app/build/outputs/apk/release/`.

The signing key in `app/signing/` is a throwaway key for sideloading, committed so that CI builds keep installing over each other. Keep the repo private.
