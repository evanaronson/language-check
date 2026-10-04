#!/usr/bin/env python3
"""Runs the app's exact prompt and schema against eval/cases.jsonl and reports
judgment mismatches and latency.

    GEMINI_API_KEY=... python3 eval/run.py
    OPENAI_API_KEY=... python3 eval/run.py --provider openai

Each case may set "language" (as the app's language setting names it; default
auto), "punctuation" (strict, moderate or casual; default moderate), "checks"
(both, fix or naturalize; default both), "status", "fix" (a correction is expected or not) and "natural" (a more
natural alternative is expected or not). Omitted fields aren't checked. "natural": false cases are the important ones: they catch the
app nagging about language that is already fine.
"""
import argparse
import json
import os
import pathlib
import statistics
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets"


def check_gemini(model, key, system, schema, message):
    body = {
        "systemInstruction": {"parts": [{"text": system}]},
        "contents": [{"role": "user", "parts": [{"text": message}]}],
        "generationConfig": {
            "responseMimeType": "application/json",
            "responseJsonSchema": schema,
            "maxOutputTokens": 4096,
            "thinkingConfig": {"thinkingLevel": "MINIMAL"},
        },
    }
    request = urllib.request.Request(
        f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        data=json.dumps(body).encode(),
        headers={"x-goog-api-key": key, "Content-Type": "application/json"},
    )
    payload, elapsed = post(request)
    parts = payload["candidates"][0]["content"]["parts"]
    answer = "".join(p.get("text", "") for p in parts if not p.get("thought"))
    return json.loads(answer), elapsed


def check_openai(model, key, system, schema, message):
    # Mirrors OpenAIChecker: strict mode rejects Gemini's propertyOrdering.
    schema = {k: v for k, v in schema.items() if k != "propertyOrdering"}
    body = {
        "model": model,
        "instructions": system,
        "input": message,
        "store": False,
        "max_output_tokens": 4096,
        "reasoning": {"effort": "none"},
        "text": {"format": {"type": "json_schema", "name": "check", "strict": True, "schema": schema}},
    }
    request = urllib.request.Request(
        "https://api.openai.com/v1/responses",
        data=json.dumps(body).encode(),
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
    )
    payload, elapsed = post(request)
    answer = "".join(
        c.get("text", "")
        for item in payload["output"] if item.get("type") == "message"
        for c in item["content"] if c.get("type") == "output_text"
    )
    return json.loads(answer), elapsed


def post(request):
    start = time.monotonic()
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.load(response)
    return payload, time.monotonic() - start


PROVIDERS = {
    "gemini": (check_gemini, "gemini-3.5-flash-lite", "GEMINI_API_KEY"),
    "openai": (check_openai, "gpt-6-sol", "OPENAI_API_KEY"),
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--provider", choices=PROVIDERS, default="gemini")
    parser.add_argument("--model", help="override the provider's default model")
    args = parser.parse_args()
    check, default_model, key_var = PROVIDERS[args.provider]
    model = args.model or default_model
    key = os.environ[key_var]
    system = (ASSETS / "check_prompt.md").read_text()
    schema = json.loads((ASSETS / "check_schema.json").read_text())
    cases = [json.loads(line) for line in (ROOT / "eval/cases.jsonl").read_text().splitlines() if line.strip()]

    times, failures = [], 0
    for case in cases:
        try:
            message = (f"Language: {case.get('language', 'auto')}\n"
                       f"Punctuation: {case.get('punctuation', 'moderate')}\n"
                       f"Checks: {case.get('checks', 'both')}\nText: {case['text']}")
            verdict, elapsed = check(model, key, system, schema, message)
        except (urllib.error.URLError, KeyError, json.JSONDecodeError) as error:
            failures += 1
            print(f"ERROR  {case['text']}\n       {error}")
            continue
        times.append(elapsed)
        problems = []
        if "status" in case and verdict["status"] != case["status"]:
            problems.append(f"status {verdict['status']} != {case['status']}")
        if "fix" in case and verdict["has_errors"] != case["fix"]:
            problems.append(f"fix {verdict['has_errors']} != {case['fix']}")
        if "natural" in case and verdict["more_natural"] != case["natural"]:
            problems.append(f"natural {verdict['more_natural']} != {case['natural']}")
        failures += bool(problems)
        mark = "MISS " if problems else "ok   "
        print(f"{mark}{elapsed:4.1f}s  {case['text']}")
        if verdict.get("corrected"):
            print(f"            fix: {verdict['corrected']}")
            for change in verdict.get("fixes", []):
                print(f"                 {change['from'] or '+'} -> {change['to']}  [{change['context']}]  ({change['why']})")
        if verdict.get("natural"):
            print(f"        natural: {verdict['natural']}")
            for change in verdict.get("natural_changes", []):
                print(f"                 {change['from'] or '+'} -> {change['to']}  [{change['context']}]  ({change['why']})")
        for problem in problems:
            print(f"        !! {problem}")

    if times:
        times.sort()
        p90 = times[min(len(times) - 1, int(len(times) * 0.9))]
        print(f"\n{len(cases) - failures}/{len(cases)} as expected · "
              f"median {statistics.median(times):.2f}s · p90 {p90:.2f}s · max {times[-1]:.2f}s")


if __name__ == "__main__":
    main()
