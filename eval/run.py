#!/usr/bin/env python3
"""Runs the app's exact prompt and schema against eval/cases.jsonl and reports
judgment mismatches and latency.

    GEMINI_API_KEY=... python3 eval/run.py [--model gemini-3.5-flash-lite]

Each case may set "status", "fix" (a correction is expected or not) and
"natural" (a more natural alternative is expected or not). Omitted fields
aren't checked. "natural": false cases are the important ones: they catch the
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


def check(model, key, system, schema, text):
    body = {
        "systemInstruction": {"parts": [{"text": system}]},
        "contents": [{"role": "user", "parts": [{"text": f"Text: {text}"}]}],
        "generationConfig": {
            "responseMimeType": "application/json",
            "responseJsonSchema": schema,
            "maxOutputTokens": 512,
            "thinkingConfig": {"thinkingLevel": "MINIMAL"},
        },
    }
    request = urllib.request.Request(
        f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        data=json.dumps(body).encode(),
        headers={"x-goog-api-key": key, "Content-Type": "application/json"},
    )
    start = time.monotonic()
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.load(response)
    elapsed = time.monotonic() - start
    parts = payload["candidates"][0]["content"]["parts"]
    answer = "".join(p.get("text", "") for p in parts if not p.get("thought"))
    return json.loads(answer), elapsed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="gemini-3.5-flash-lite")
    args = parser.parse_args()
    key = os.environ["GEMINI_API_KEY"]
    system = (ASSETS / "check_prompt.md").read_text()
    schema = json.loads((ASSETS / "check_schema.json").read_text())
    cases = [json.loads(line) for line in (ROOT / "eval/cases.jsonl").read_text().splitlines() if line.strip()]

    times, failures = [], 0
    for case in cases:
        try:
            verdict, elapsed = check(args.model, key, system, schema, case["text"])
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
        if verdict.get("natural"):
            print(f"        natural: {verdict['natural']}")
        for problem in problems:
            print(f"        !! {problem}")

    if times:
        times.sort()
        p90 = times[min(len(times) - 1, int(len(times) * 0.9))]
        print(f"\n{len(cases) - failures}/{len(cases)} as expected · "
              f"median {statistics.median(times):.2f}s · p90 {p90:.2f}s · max {times[-1]:.2f}s")


if __name__ == "__main__":
    main()
