# Linguize

Linguize is the gut check that your Spanish was Spanish enough. Or that your Catalan was Catalan enough.

Even when you're fluent or able to communicate in your target language, you often want to make sure before you hit send: does this make sense, is anything wrong, would a native speaker say it this way?

You could do Google Translate, but it's built for the opposite job: translating *into* the language you're learning. Paste your own text in and translate it back, and you'll find out whether it's roughly understandable. You won't find out what you got wrong. A good translator reads right past your mistakes and awkward phrasing, because that's what makes it a good translator.

Checking your writing isn't a translation task. Linguize does just that one job. Select what you wrote in any app, tap **Linguize** in the selection menu, and a card floats over the app with:

- **Fixes:** the actual mistakes, each highlighted, with the smallest change and a short reason.
- **More natural:** how a native speaker would say it, only when it's clearly better.
- **Meaning:** what your text says, translated into your own language, so you can confirm it says what you meant.

Replace what you want, in-libe, and you're back where you were.

## Install

**[Download the latest APK](https://github.com/evanaronson/linguize/releases/latest/download/linguize.apk)**. Every push to `main` builds a new one; older builds are under [Releases](https://github.com/evanaronson/linguize/releases).

1. Open the link on your phone and open the downloaded file. When Android asks, allow your browser to install unknown apps.
2. Open **Linguize**, pick Google Gemini or OpenAI, paste that provider's API key ([Gemini](https://aistudio.google.com/apikey), [OpenAI](https://platform.openai.com/api-keys)) and tap Save. Keys are stored encrypted on the phone and are never in the code.
3. Try a sentence in **Try it** on the same screen. Then select text you've written in any app and look for **Linguize** in the selection menu (on Samsung it can be behind **⋮** in the menu).

That's all most apps need. Later builds install over the previous one because they're all signed with the same key.

### Optional: apps without the menu entry (i.e. Telegram, the Claude app…)

Some apps don't show other apps' entries in their selection menu. For those, Linguize can add an accessibility button instead: while typing, tap the button and the same card floats over the app, then accepted changes are written back into the text field. It reads the focused text field only when you tap the button.

Android makes this a few taps because the app wasn't installed from the Play Store:

1. **Settings → Accessibility → Installed apps → Linguize** and try to turn it on. Android blocks it with a "Restricted setting" message the first time; that's expected.
2. **Settings → Apps → Linguize → ⋮ (top right) → Allow restricted settings**, and confirm.
3. Back in **Settings → Accessibility → Installed apps → Linguize**: turn it on, along with its shortcut. A floating Linguize button appears (on Samsung you can choose between the floating button and the navigation-bar button).

The **Apps without the menu** section of Linguize's settings has the same steps and a button straight to Accessibility settings.

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

## Brand

The wordmark is **lingu·ize**: the raised dot (the Catalan *punt volat*, as in col·legi) marks where *-ize* snaps onto a word, as in catalan·ize and castilian·ize. The launcher icon is "·ize", cut straight from the wordmark. It's ink on paper, and cobalt (`#3340F0`, lighter `#7C84FF` in dark mode) is the one colour, used on the wordmark's dot and as the app's accent.

`brand/` holds the SVGs. Every letter is drawn in code from one geometric kit in `brand/source/` (`python3 brand/source/build.py` rebuilds them into `brand/A/`). The app's copies are vector drawables: `wordmark.xml`, `ic_launcher_foreground.xml` and `ic_launcher_monochrome.xml`.

## Build locally

You need JDK 17+ and the Android SDK. Run `./gradlew assembleRelease`; the APK is written to `app/build/outputs/apk/release/`.

The signing key in `app/signing/` is a throwaway key for sideloading, committed so that CI builds keep installing over each other. Because it's public, anyone could sign an APK that installs over Linguize as an update, so only install APKs from this repo's releases.
