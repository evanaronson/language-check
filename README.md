# Linguize

Linguize is the gut check that your Spanish was Spanish enough. Or that your Catalan was Catalan enough.

Even when you're fluent or able to communicate in your target language, you often want to make sure before you hit send: does this make sense, is anything wrong, would a native speaker say it this way?

You could do Google Translate, but it's built for the opposite job: translating *into* the language you're learning. Paste your own text in and translate it back, and you'll find out whether it's roughly understandable. You won't find out what you got wrong. A good translator reads right past your mistakes and awkward phrasing, because that's what makes it a good translator.

Checking your writing isn't a translation task. Linguize does just that one job. Select what you wrote in any app, tap **Linguize** in the selection menu, and a card floats over the app with:

- **Fixes:** the actual mistakes, each highlighted, with the smallest change and a short reason.
- **More natural:** how a native speaker would say it, only when it's clearly better.
- **Meaning:** what your text says, translated into your own language, so you can confirm it says what you meant.

Replace what you want, in-line, and you're back where you were.

## Install

**[Download the latest APK](https://github.com/evanaronson/linguize/releases/latest/download/linguize.apk)**. Every push to `main` builds a new one; older builds are under [Releases](https://github.com/evanaronson/linguize/releases).

1. Open the link on your phone and open the downloaded file. When Android asks, allow your browser to install unknown apps.
2. Open **Linguize**, pick Google Gemini or OpenAI, paste that provider's API key ([Gemini](https://aistudio.google.com/apikey), [OpenAI](https://platform.openai.com/api-keys)) and tap Save. Keys are stored encrypted on the phone and are never in the code.
3. Try a sentence in **Try it** on the same screen. Then select text you've written in any app and look for **Linguize** in the selection menu (on Samsung it can be behind **⋮** in the menu).

That's all most apps need. Later builds install over the previous one because they're all signed with the same key.

### Optional: apps without the menu entry (e.g. Telegram, the Claude app…)

Some apps don't show other apps' entries in their selection menu. For those, Linguize can add an accessibility button instead: while typing, tap the button and the same card floats over the app, then accepted changes are written back into the text field. It reads the focused text field only when you tap the button.

Android makes this a few taps because the app wasn't installed from the Play Store:

1. **Settings → Accessibility → Installed apps → Linguize** and try to turn it on. Android blocks it with a "Restricted setting" message the first time; that's expected.
2. **Settings → Apps → Linguize → ⋮ (top right) → Allow restricted settings**, and confirm.
3. Back in **Settings → Accessibility → Installed apps → Linguize**: turn it on, along with its shortcut. A floating Linguize button appears (on Samsung you can choose between the floating button and the navigation-bar button).

The **Apps without the menu** section of Linguize's settings has the same steps and a button straight to Accessibility settings.

## Code layout

Everything is under `app/src/main/java/com/evanaronson/linguize/`. Dependencies point one way: the core knows nothing else, and the screens sit on top.

| Package | Role |
|---|---|
| `core/` | Pure Kotlin, no Android or network, unit-tested. The check's options (`Language`, `Punctuation`, `Judgments`), the model's answer as the schema defines it (`Verdict`), and the edit engine: `Alignment` and `Edits` turn the answer into `Edit`s anchored to the original text (positions come from aligning the texts, never from the model; each punctuation mark is its own fix; rewordings are found against the corrected text, then mapped back), `Revision` tracks accepting and undoing with the rules written out at its top, and `interpret()` produces a `CheckResult`. `Selection` splits selected text from the whitespace around it. |
| `llm/` | Talking to models. `Prompt` is the contract: the instructions and schema in `assets/`, the user-message format, and reading answers. `GeminiClient` and `OpenAIClient` implement `ProviderClient` over plain REST; adding a provider means one more client. `CheckFailure` is every way a check can fail. |
| `data/` | What the phone remembers: `Settings`, `ApiKeys` (encrypted with an Android Keystore key) and `SelectionMenu` (which menu entries are on, kept as the enabled state of their activity-aliases). |
| root | `Checker` runs a check with the chosen provider, model and options; `App` creates the long-lived objects. |
| `ui/` | `card/`: the result card and its `CheckViewModel`, which owns a check and the accepted changes; `CardActions.of()` gives the card the same behaviour everywhere. `settings/`: the settings screen (one file per section), its view model and `SettingsActivity`, the launcher screen. `components/` and `theme/`: shared controls and the brand theme. |
| `menu/` | `CheckActivity`, opened from a selection-menu entry. |
| `accessibility/` | The accessibility button: `LinguizeAccessibilityService` reads the focused field and writes accepted changes back; `OverlayWindow` shows the card above other apps. |

The prompt and schema live in `app/src/main/assets/` so the eval script uses exactly what the app sends, and a unit test checks every example in the prompt against the schema.

## Tuning the judgments

The prompt is where most of the product lives. To test a prompt change against real sentences, including how often it nags about text that's already fine, and to measure latency:

```sh
GEMINI_API_KEY=... python3 eval/run.py
OPENAI_API_KEY=... python3 eval/run.py --provider openai
```

Add cases to `eval/cases.jsonl` when the app gets something wrong.

## Brand

The wordmark is **lingu·ize**: the raised dot (the Catalan *punt volat*, as in col·legi) marks where *-ize* snaps onto a word, as in catalan·ize and castilian·ize. The launcher icon is "·ize", cut straight from the wordmark. It's ink on paper, and cobalt (`#3340F0`, lighter `#7C84FF` in dark mode) is the one colour, used on the wordmark's dot and as the app's accent.

`brand/` holds the SVGs. Every letter is drawn in code from one geometric kit in `brand/source/`; `python3 brand/source/build.py` rebuilds them. The app's copies are vector drawables: `wordmark.xml`, `ic_launcher_foreground.xml` and `ic_launcher_monochrome.xml`.

## Build locally

You need JDK 17+ and the Android SDK. `./gradlew testDebugUnitTest` runs the tests; `./gradlew assembleRelease` writes the APK to `app/build/outputs/apk/release/`. A local build has version 1, so it won't install over a release build without uninstalling first.

The signing key in `app/signing/` is a throwaway key for sideloading, committed so that CI builds keep installing over each other. Because it's public, anyone could sign an APK that installs over Linguize as an update, so only install APKs from this repo's releases.
