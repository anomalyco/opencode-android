#!/usr/bin/env python3
"""Lists the crashes and freezes Google Play collected for the app, and opens GitHub issues for them.

Usage:
  scripts/play-crashes.py                    lists the last 7 days' crash groups, most users first
  scripts/play-crashes.py --days 28          the same over a longer window
  scripts/play-crashes.py show <id>          one group: a sample stack trace, its device and Play Console links
  scripts/play-crashes.py issues             prints the GitHub issues it would open or comment on
  scripts/play-crashes.py issues --create    opens and comments on them with `gh`

Google Play (Android vitals) only has crashes from Play installs whose users share diagnostics.
It splits one bug into many groups, so this merges the groups that share a type, an exception and
the frame Play blames, and gives each merged group a short id.

`issues` covers the groups at least --min-users people hit (default 2) on the version with the most
reports or a newer one. Each issue is labelled `crash` and carries its id in a hidden marker, so a
group gets one issue. When the group shows up in a newer version, the issue gets a comment. Stack
traces are posted without exception messages, which can contain what the user typed.

It reads a Google service account key that has "View app quality information" in Play Console,
from $PLAY_VITALS_KEY or ~/.config/opencode-android/play-vitals-reader.json, or the key itself from
$PLAY_VITALS_SERVICE_ACCOUNT_JSON. It needs Python 3 and openssl, and `gh` for `issues`.
"""
import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "ai.opencode.mobilecode"
API = "https://playdeveloperreporting.googleapis.com/v1beta1/apps/" + PACKAGE
SCOPE = "https://www.googleapis.com/auth/playdeveloperreporting"
DEFAULT_KEY = Path.home() / ".config" / "opencode-android" / "play-vitals-reader.json"
LABEL = "crash"
MARKER = re.compile(r"<!-- play-crash:(\w+) version:(\d+) -->")
STACK_LINES = 40


def base64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def access_token():
    inline = os.environ.get("PLAY_VITALS_SERVICE_ACCOUNT_JSON")
    if inline:
        key = json.loads(inline)
    else:
        path = Path(os.environ.get("PLAY_VITALS_KEY") or DEFAULT_KEY)
        if not path.exists():
            sys.exit(f"No service account key at {path}. Set PLAY_VITALS_KEY to its path.")
        key = json.loads(path.read_text())
    now = int(time.time())
    header = base64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = base64url(json.dumps({
        "iss": key["client_email"],
        "scope": SCOPE,
        "aud": key["token_uri"],
        "iat": now,
        "exp": now + 3600,
    }).encode())
    unsigned = header + "." + claims
    with tempfile.NamedTemporaryFile("w", suffix=".pem") as pem:
        pem.write(key["private_key"])
        pem.flush()
        signed = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", pem.name],
            input=unsigned.encode(), capture_output=True, check=True,
        )
    form = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": unsigned + "." + base64url(signed.stdout),
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(key["token_uri"], data=form)) as response:
        return json.load(response)["access_token"]


def get(token, path, query):
    url = API + path + "?" + urllib.parse.urlencode(query)
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            # Google answers 429 and 503 now and then; the same request works a few seconds later.
            if error.code not in (429, 503) or attempt == 3:
                sys.exit(f"Google Play answered {error.code}: {error.read().decode()[:300]}")
            time.sleep(5)


def interval(days):
    end = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0)
    start = end - timedelta(days=days)
    query = {}
    for name, moment in (("startTime", start), ("endTime", end)):
        query[f"interval.{name}.year"] = moment.year
        query[f"interval.{name}.month"] = moment.month
        query[f"interval.{name}.day"] = moment.day
        query[f"interval.{name}.hours"] = moment.hour
    return query


def play_groups(token, days):
    groups = []
    query = interval(days)
    query["pageSize"] = 100
    while True:
        page = get(token, "/errorIssues:search", query)
        groups += page.get("errorIssues", [])
        if "nextPageToken" not in page:
            return groups
        query["pageToken"] = page["nextPageToken"]


def version_code(play_group, field):
    return int(play_group.get(field, {}).get("versionCode", 0))


def api_level(play_group, field):
    return int(play_group.get(field, {}).get("apiLevel", 0))


def merged_groups(play):
    merged = {}
    for item in play:
        kind = item.get("type", "")
        exception = item.get("location", "")
        # Play prefixes the frame it blames with its file: "TranscriptRowGrowth.kt - ai.opencode…".
        frame = item.get("cause", "").split(" - ")[-1]
        key = "|".join((kind, exception, frame))
        group = merged.get(key)
        if group is None:
            group = {
                "id": hashlib.sha1(key.encode()).hexdigest()[:10],
                "kind": "Freeze" if kind == "APPLICATION_NOT_RESPONDING" else "Crash",
                "exception": exception,
                "frame": frame,
                "reports": 0,
                "users": 0,
                "first_version": version_code(item, "firstAppVersion"),
                "last_version": 0,
                "first_api": api_level(item, "firstOsVersion"),
                "last_api": 0,
                "last_seen": "",
                "play": [],
            }
            merged[key] = group
        group["reports"] += int(item.get("errorReportCount", 0))
        group["users"] += int(item.get("distinctUsers", 0))
        group["first_version"] = min(group["first_version"], version_code(item, "firstAppVersion"))
        group["last_version"] = max(group["last_version"], version_code(item, "lastAppVersion"))
        group["first_api"] = min(group["first_api"], api_level(item, "firstOsVersion"))
        group["last_api"] = max(group["last_api"], api_level(item, "lastOsVersion"))
        group["last_seen"] = max(group["last_seen"], item.get("lastErrorReportTime", "")[:10])
        group["play"].append(item)
    groups = list(merged.values())
    for group in groups:
        group["play"].sort(key=lambda item: int(item.get("errorReportCount", 0)), reverse=True)
    groups.sort(key=lambda group: (group["users"], group["reports"]), reverse=True)
    return groups


def title(group):
    exception = group["exception"].split(".")[-1]
    frame = group["frame"]
    # A native frame reads "[base.apk] n74.s"; a Java one is a full class and method name.
    if not frame.startswith("["):
        frame = ".".join(frame.split(".")[-2:])
    return f"{group['kind']}: {exception} in {frame}"


def span(first, last):
    return str(first) if first == last else f"{first}–{last}"


def sample_report(token, group, days):
    query = interval(days)
    query["pageSize"] = 1
    query["filter"] = "errorIssueId = " + group["play"][0]["name"].split("/")[-1]
    reports = get(token, "/errorReports:search", query).get("errorReports", [])
    return reports[0] if reports else None


def stack(report):
    lines = []
    for line in report.get("reportText", "").splitlines():
        exception = re.match(r"(Exception|Caused by:) ([\w.$]+)", line)
        if exception:
            lines.append(f"{exception.group(1)} {exception.group(2)}")
        elif re.match(r"\s+(at |#\d+ )|\"[^\"]*\" tid=|backtrace:", line):
            lines.append(line.rstrip())
    if len(lines) > STACK_LINES:
        lines = lines[:STACK_LINES] + ["  …"]
    return "\n".join(lines)


def sample_line(report):
    device = report.get("deviceModel", {}).get("marketingName", "an unknown device")
    api = report.get("osVersion", {}).get("apiLevel", "?")
    version = report.get("appVersion", {}).get("versionCode", "?")
    line = f"{device}, Android API {api}, version code {version}"
    commit = re.search(r'revision: "(\w+)"', report.get("vcsInformation", ""))
    if commit:
        line += f", commit {commit.group(1)[:9]}"
    return line


def issue_body(group, report, days):
    lines = [
        f"Google Play reported this {group['kind'].lower()} (Android vitals).",
        "",
        f"- **Exception:** `{group['exception']}`",
        f"- **Where:** `{group['frame']}`",
        f"- **Last {days} days:** {group['reports']} reports from about {group['users']} users",
        f"- **Version codes:** {span(group['first_version'], group['last_version'])}",
        f"- **Android API levels:** {span(group['first_api'], group['last_api'])}",
        f"- **Last seen:** {group['last_seen']}",
    ]
    if report:
        lines += [f"- **Sample:** {sample_line(report)}", "", "```", stack(report), "```"]
    lines += ["", "Play Console (needs access):"]
    for item in group["play"][:3]:
        lines.append(f"- {item.get('issueUri', '')}")
    lines += ["", f"<!-- play-crash:{group['id']} version:{group['last_version']} -->"]
    return "\n".join(lines)


def gh(*args):
    done = subprocess.run(["gh", *args], cwd=ROOT, capture_output=True, text=True)
    if done.returncode != 0:
        sys.exit(f"gh {' '.join(args[:2])} failed: {done.stderr.strip()}")
    return done.stdout


def known_issues():
    """Maps a group id to its issue number and the newest version code noted on it."""
    known = {}
    listed = gh("issue", "list", "--label", LABEL, "--state", "all", "--limit", "1000", "--json", "number,body,comments")
    for issue in json.loads(listed):
        texts = [issue["body"]] + [comment["body"] for comment in issue["comments"]]
        for text in texts:
            for group_id, version in MARKER.findall(text):
                entry = known.setdefault(group_id, {"number": issue["number"], "version": 0})
                entry["version"] = max(entry["version"], int(version))
    return known


def list_groups(groups):
    print(f"{'id':10}  {'users':>5}  {'reports':>7}  {'versions':>9}  {'last seen':10}  what")
    for group in groups:
        versions = span(group["first_version"], group["last_version"])
        print(f"{group['id']:10}  {group['users']:5}  {group['reports']:7}  {versions:>9}  {group['last_seen']:10}  {title(group)}")


def show(token, groups, group_id, days):
    for group in groups:
        if group["id"] == group_id:
            print(issue_body(group, sample_report(token, group, days), days))
            return
    sys.exit(f"No crash group {group_id} in the last {days} days.")


def issues(token, groups, days, min_users, create):
    reports_by_version = {}
    for group in groups:
        for item in group["play"]:
            code = version_code(item, "lastAppVersion")
            reports_by_version[code] = reports_by_version.get(code, 0) + int(item.get("errorReportCount", 0))
    current = max(reports_by_version, key=reports_by_version.get)
    known = known_issues()
    if create and LABEL not in gh("label", "list", "--limit", "200", "--json", "name", "--jq", ".[].name").split():
        gh("label", "create", LABEL, "--description", "Crash or freeze reported by Google Play", "--color", "B60205")
    for group in groups:
        if group["users"] < min_users or group["last_version"] < current:
            continue
        counts = f"{group['reports']} reports, about {group['users']} users, version codes {span(group['first_version'], group['last_version'])}"
        issue = known.get(group["id"])
        if issue is None:
            print(f"{'Opening' if create else 'Would open'}: {title(group)} ({counts})")
            if create:
                body = issue_body(group, sample_report(token, group, days), days)
                print("  " + gh("issue", "create", "--title", title(group), "--label", LABEL, "--body", body).strip())
        elif group["last_version"] > issue["version"]:
            print(f"{'Commenting on' if create else 'Would comment on'} #{issue['number']}: {title(group)} ({counts})")
            if create:
                comment = (
                    f"Still happening in version code {group['last_version']}: {group['reports']} reports from about "
                    f"{group['users']} users in the last {days} days.\n\n"
                    f"<!-- play-crash:{group['id']} version:{group['last_version']} -->"
                )
                gh("issue", "comment", str(issue["number"]), "--body", comment)
        else:
            print(f"Known, #{issue['number']}: {title(group)}")


def main():
    parser = argparse.ArgumentParser(description="Crashes and freezes Google Play collected for the app.")
    parser.add_argument("action", nargs="?", choices=["list", "show", "issues"], default="list")
    parser.add_argument("id", nargs="?", help="group id (for show)")
    parser.add_argument("--days", type=int, default=7, help="how far back to look (default 7)")
    parser.add_argument("--min-users", type=int, default=2, help="fewest users a group needs to get an issue (default 2)")
    parser.add_argument("--create", action="store_true", help="with issues: open and comment instead of only printing")
    args = parser.parse_args()
    if args.action == "show" and not args.id:
        parser.error("show needs a group id")

    token = access_token()
    groups = merged_groups(play_groups(token, args.days))
    if args.action == "list":
        list_groups(groups)
    elif args.action == "show":
        show(token, groups, args.id, args.days)
    else:
        issues(token, groups, args.days, args.min_users, args.create)


if __name__ == "__main__":
    main()
