"""Fetch one short-lived, browser-scoped Remnawave session for the local A063 test.

No token or subscription URL is printed. The output is a private, replaceable
local file, not a deployed API credential and not a source of entitlement.
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import sys
import tempfile
from urllib.parse import urlsplit

import requests


def documents(path: pathlib.Path):
    value = path.read_text(encoding="utf-8")
    decoder = json.JSONDecoder()
    offset = 0
    while offset < len(value):
        while offset < len(value) and value[offset].isspace():
            offset += 1
        if offset < len(value):
            item, offset = decoder.raw_decode(value, offset)
            yield item


def panel_entry(path: pathlib.Path) -> dict:
    entries = [item["Remnawave Panel"] for item in documents(path) if "Remnawave Panel" in item]
    if len(entries) != 1:
        raise ValueError("panel credential entry unavailable")
    entry = entries[0]
    if not all(isinstance(entry.get(key), str) and entry[key] for key in ("Address", "Login", "Pass")):
        raise ValueError("panel credential entry incomplete")
    return entry


def body(response: requests.Response) -> dict:
    if response.status_code != 200:
        raise ValueError("panel authentication or lookup unavailable")
    result = response.json()
    if not isinstance(result, dict) or not isinstance(result.get("response"), dict):
        raise ValueError("unexpected panel response")
    return result["response"]


def fetch(entry: dict, expected_host: str, subscription_host: str, expected_username: str) -> dict:
    base = entry["Address"].rstrip("/")
    parsed = urlsplit(base)
    if parsed.scheme != "https" or parsed.hostname != expected_host or parsed.path or parsed.query or parsed.fragment or parsed.username:
        raise ValueError("unexpected panel origin")
    session = requests.Session()
    session.trust_env = False
    session.headers.update({
        "Accept": "application/json",
        "Content-Type": "application/json",
        "X-Remnawave-Client-Type": "browser",
    })
    auth = body(session.post(
        base + "/api/auth/login",
        json={"username": entry["Login"], "password": entry["Pass"]},
        timeout=15, allow_redirects=False,
    ))
    token = auth.get("accessToken")
    if not isinstance(token, str) or not token or len(token) > 8192:
        raise ValueError("panel browser session unavailable")
    session.headers["Authorization"] = "Bearer " + token
    matches = []
    for start in range(0, 2000, 100):
        payload = body(session.get(
            base + "/api/users", params={"start": start, "size": 100},
            timeout=15, allow_redirects=False,
        ))
        users = payload.get("users")
        if not isinstance(users, list):
            raise ValueError("unexpected panel user list")
        matches.extend(user for user in users if isinstance(user, dict) and user.get("username") == expected_username)
        if len(users) < 100:
            break
    if len(matches) != 1:
        raise ValueError("exact personal user not found")
    user = matches[0]
    if user.get("status") != "ACTIVE":
        raise ValueError("personal subscription inactive")
    user_id = user.get("id")
    subscription = user.get("subscriptionUrl")
    if not isinstance(user_id, int) or user_id < 1 or not isinstance(subscription, str):
        raise ValueError("personal user fields unavailable")
    link = urlsplit(subscription)
    if link.scheme != "https" or link.hostname != subscription_host or link.username or link.query or link.fragment:
        raise ValueError("unexpected subscription URL")
    if not link.path.startswith("/") or len(link.path) < 9:
        raise ValueError("unexpected subscription token")
    return {
        "panel_origin": base,
        "panel_browser_jwt": token,
        "user_id": user_id,
        "subscription_url": subscription,
    }


def write_private(destination: pathlib.Path, payload: dict) -> None:
    if destination.exists() or not destination.parent.is_dir():
        raise ValueError("private output exists or directory unavailable")
    fd, temporary = tempfile.mkstemp(prefix=".panel-session-", dir=destination.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            json.dump(payload, output, separators=(",", ":"))
            output.write("\n")
        os.replace(temporary, destination)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--credentials", type=pathlib.Path, required=True)
    parser.add_argument("--out", type=pathlib.Path, required=True)
    parser.add_argument("--expected-panel-host", required=True)
    parser.add_argument("--expected-subscription-host", required=True)
    parser.add_argument("--expected-username", required=True)
    args = parser.parse_args()
    try:
        write_private(args.out, fetch(panel_entry(args.credentials), args.expected_panel_host,
                                          args.expected_subscription_host, args.expected_username))
    except Exception:
        print("personal panel browser session unavailable", file=sys.stderr)
        return 1
    print("personal panel browser session saved in private storage")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
