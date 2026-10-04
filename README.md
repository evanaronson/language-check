# Linguize

A small Android utility for checking text you've written in a language you're learning. You select the text in any app, tap **Check** in the selection menu, and a card floats over the app with two independent judgments:

1. **Correctness:** the minimum fix needed, or "No fixes".
2. **Naturalness:** one clearly better phrasing, or "Sounds natural".

Copy or Replace, and you're back where you were.

## Install

Every push to `main` builds a signed APK and publishes it as a GitHub release. On the phone:

1. Open the latest release in this repo and download `language-check-N.apk`.
2. Open it and allow your browser to install unknown apps when Android asks.
3. Open **Linguize**, pick Google Gemini or OpenAI, paste that provider's API key ([Gemini](https://aistudio.google.com/apikey), [OpenAI](https://platform.openai.com/api-keys)), tap Save, and try a sentence. Keys are stored encrypted on the phone; none are in the code.

Later builds install over the previous one because they're all signed with the same key.

## How it works

Possible results: **Looks good**; fixes and/or a more natural rewording, each change highlighted separately; **Can't tell what this means**; **Not <language>** when a language is pinned in settings; or an error with Retry.

Fixes and rewordings are both worked out against the original text, so they're independent: tap a highlight to see why and **Replace** just that change, or **Replace all** for a whole section. Accepted changes disappear from the list; **Undo** reverts the last action, and closing the card in any way (Done, back, tapping outside) hands the text with accepted changes back to the app. A rewording that covers a fixed word takes precedence over that fix.

The selection menu can show several entries: **Linguize** (detects the language), **Catalanize** and **Castilianize**. Pick which ones appear in settings; tapping one checks the text as that language. Each entry is an `activity-alias` of `CheckActivity` in the manifest, switched on and off by `SelectionMenu`; adding a language means a `Language`, a `MenuEntry`, an alias and a label.

Settings also has a punctuation level (Strict, Moderate or Casual), which judgments to make (fix, naturalize or both), and a model picker that lists the models your key can use, live from the provider. Picking a model runs a short test check and shows how long it took, or the provider's error.

## Code layout

Packages under `app/src/main/java/com/evanaronson/languagecheck/`, each depending only on the ones above it:

| Package | Responsibility |
|---|---|
| `review/` | The core, with no Android or network code. `Verdict` is the model's answer as the schema defines it; `Edits` and `Alignment` turn it into `Edit`s anchored to the original text (positions come from aligning the texts, never from the model; every punctuation mark is its own fix; rewordings are found against the corrected text, so fixes never repeat in them, then mapped back onto the original); `Revision` tracks which edits are accepted; `interpret()` produces a `CheckResult`. Also the check options: `Language`, `Punctuation`, `Judgments`. |
| `llm/` | Talking to models. `Prompt` is the contract: instructions and schema from `assets/`, the user-message format, and reading answers. `GeminiClient` and `OpenAIClient` implement `ProviderClient` (check, list models) over plain REST; adding a provider means one more client. |
| `settings/` | `Settings` (choices) and `ApiKeys` (keys encrypted with an Android Keystore key). |
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
