# Language Check

A small Android utility. You select Catalan or Spanish text you've written in any app, tap **Check** in the selection menu, and a card floats over the app with two independent judgments:

1. **Correctness:** the minimum fix needed, or "No fixes".
2. **Naturalness:** one clearly better phrasing, or "Sounds natural".

Copy or Replace, and you're back where you were.

## Install

Every push to `main` builds a signed APK and publishes it as a GitHub release. On the phone:

1. Open the latest release in this repo and download `language-check-N.apk`.
2. Open it and allow your browser to install unknown apps when Android asks.
3. Open **Language Check**, paste a [Gemini API key](https://aistudio.google.com/apikey), tap Save, and try a sentence.

Later builds install over the previous one because they're all signed with the same key.

## How it works

| Piece | Where |
|---|---|
| Selection-menu entry (`ACTION_PROCESS_TEXT`) and the floating card | `CheckActivity.kt`, `ui/ResultCard.kt` |
| Instructions and output schema sent to the model | `app/src/main/assets/check_prompt.md`, `check_schema.json` |
| Gemini call (plain REST, `gemini-3.5-flash-lite`, minimal thinking) | `check/GeminiChecker.kt` |
| Turning the verdict into card states, plus the word diff behind "1 fix" and highlights | `check/CheckResult.kt`, `check/WordDiff.kt` |
| API key, encrypted with an Android Keystore key | `KeyStorage.kt` |

Possible results: **Looks good** (no fixes, sounds natural); a fix and/or a more natural alternative; **Can't tell what this means**; **Not Catalan or Spanish**; or an error with Retry.

Adding OpenAI means adding another `Checker` implementation; the prompt and schema are provider-neutral.

## Tuning the judgments

The prompt is where most of the product lives. To test a prompt change against real sentences, including how often it nags about text that's already fine, and to measure latency:

```sh
GEMINI_API_KEY=... python3 eval/run.py
```

Add cases to `eval/cases.jsonl` when the app gets something wrong.

## Build locally

You need JDK 17+ and the Android SDK. Run `./gradlew assembleRelease`; the APK is written to `app/build/outputs/apk/release/`.

The signing key in `app/signing/` is a throwaway key for sideloading, committed so that CI builds keep installing over each other. Keep the repo private.
