"""Check BeReal's public JP Play listing; only --create-issue writes to GitHub."""

import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import sys
from urllib.request import Request, urlopen

PLAY_URL = "https://play.google.com/store/apps/details?id=com.bereal.ft&hl=en&gl=JP"
BASELINE = Path(__file__).resolve().parents[1] / ".github/bereal-google-play.json"
JST = timezone(timedelta(hours=9), "JST")


def validate_snapshot(snapshot):
    version, updated = snapshot["version"], snapshot["updated"]
    if not isinstance(version, str) or not re.fullmatch(r"[0-9]+(?:\.[0-9]+)+(?:[-+][0-9A-Za-z.-]+)?", version):
        raise ValueError("Google Play did not expose a valid app version")
    if type(updated) is not int or updated <= 0:
        raise ValueError("Google Play did not expose a valid update timestamp")
    datetime.fromtimestamp(updated, JST)
    return {"version": version, "updated": updated}


def parse_listing(html):
    # Play's embedded ds:5 metadata, also used by google-play-scraper's Detail spec.
    matches = list(re.finditer(r"AF_initDataCallback\(\{\s*key:\s*['\"]ds:5['\"],.*?\bdata\s*:", html, re.S))
    if len(matches) != 1:
        raise ValueError("Google Play app metadata is missing or ambiguous")
    data, _ = json.JSONDecoder().raw_decode(html[matches[0].end():].lstrip())
    details = data[1][2]
    return validate_snapshot({"version": details[140][0][0][0], "updated": details[145][0][1][0]})


def fetch_listing():
    request = Request(PLAY_URL, headers={"User-Agent": "Mozilla/5.0"})
    with urlopen(request, timeout=30) as response:
        return parse_listing(response.read().decode("utf-8"))


def github_api(route, token, payload=None):
    request = Request(
        "https://api.github.com/" + route,
        data=json.dumps(payload).encode("utf-8") if payload is not None else None,
        headers={"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json",
                 "Content-Type": "application/json", "User-Agent": "BeFuck-update-check",
                 "X-GitHub-Api-Version": "2026-03-10"},
    )
    with urlopen(request, timeout=30) as response:
        return json.load(response)


def ensure_issue(current, baseline, repository, token, run_url):
    marker = f"<!-- bereal-google-play:{current['version']}:{current['updated']} -->"
    route = f"repos/{repository}/issues"
    page = 1
    while True:
        issues = github_api(f"{route}?state=all&per_page=100&page={page}", token)
        if not isinstance(issues, list):
            raise ValueError("GitHub returned an invalid issue list")
        for issue in issues:
            if "pull_request" not in issue and marker in (issue.get("body") or ""):
                return "Already reported: " + issue["html_url"]
        if len(issues) < 100:
            break
        page += 1
    updated = datetime.fromtimestamp(current["updated"], JST).strftime("%Y-%m-%d %H:%M:%S JST")
    body = (f"{marker}\n\n[Google PlayのBeReal]({PLAY_URL})で更新を検知しました。\n\n"
            f"- バージョン：`{current['version']}`\n- ストア更新日時：{updated}\n"
            f"- 監視開始時のバージョン：`{baseline['version']}`\n\n"
            f"BeFuckの対応状況を確認してください。\n\n[検知したCI実行]({run_url})\n")
    issue = github_api(route, token, {
        "title": f"BeReal {current['version']} のGoogle Play更新を検知 ({updated[:10]})",
        "body": body,
    })
    return "Created: " + issue["html_url"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--create-issue", action="store_true")
    args = parser.parse_args()
    baseline = validate_snapshot(json.loads(BASELINE.read_text(encoding="utf-8")))
    current = fetch_listing()
    print("Google Play: " + json.dumps(current))
    if current == baseline or current["updated"] < baseline["updated"]:
        print("No new update since the monitoring baseline.")
        return
    if not args.create_issue:
        print("Update detected (read-only; pass --create-issue to report it).")
        return
    repository = os.environ["GITHUB_REPOSITORY"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid GITHUB_REPOSITORY")
    run_id = os.environ["GITHUB_RUN_ID"]
    if not run_id.isdecimal():
        raise ValueError("Invalid GITHUB_RUN_ID")
    print(ensure_issue(current, baseline, repository, os.environ["GH_TOKEN"],
                       f"https://github.com/{repository}/actions/runs/{run_id}"))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, IndexError, TypeError, OSError) as error:
        print(f"Update check failed: {error}", file=sys.stderr)
        sys.exit(1)
