#!/usr/bin/env python3
"""Print the one-tap Obtainium install link for Tangent Browser.

Obtainium accepts a whole app config in a deep link (`obtainium://app/<url-encoded json>`), but
custom schemes are not clickable in GitHub Markdown or most messengers — so we wrap it in
Obtainium's own https redirect (`apps.obtainium.imranr.dev/redirect?r=...`), which bounces to the
app. The JSON shape here mirrors Obtainium's own "share app" code (lib/pages/apps.dart).

Regenerate and paste into README.md / docs/OBTAINIUM.md whenever the repo URL or app id changes:

    python3 scripts/obtainium_link.py [repo-url]
"""

import json
import sys
import urllib.parse

DEFAULT_REPO = "https://github.com/Firebolt9907/TangentBrowser"

APP_ID = "com.firebolt9907.tangentbrowser"
APP_NAME = "Tangent Browser"


def build_link(repo_url: str) -> str:
    author = repo_url.rstrip("/").split("/")[-2]
    config = {
        "id": APP_ID,
        "url": repo_url,
        "author": author,
        "name": APP_NAME,
        "preferredApkIndex": 0,
        # Nested JSON *string*, not an object — that is how Obtainium serializes it.
        "additionalSettings": json.dumps(
            {
                "includePrereleases": False,
                "fallbackToOlderReleases": True,
                "autoApkFilterByArch": True,
            },
            separators=(",", ":"),
        ),
        "overrideSource": "GitHub",
    }
    encoded = urllib.parse.quote(json.dumps(config, separators=(",", ":")), safe="")
    return f"https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/{encoded}"


if __name__ == "__main__":
    print(build_link(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_REPO))
