#!/usr/bin/env python3
"""Verify the bundled reader_rules.json against real article DOM.

The reader-mode agent loop adds per-site `include`/`strip` rules to
`app/src/main/assets/reader_rules.json`. This tool goes a step past the load-time
`ReaderBundledRulesTest` (which only checks that the JSON is well formed): it applies each rule's
actual CSS selectors to a saved copy of that site's real article HTML and confirms the `include`
selector resolves to a container with substantial article text and paragraph tags — i.e. that the
extractor's `walk()` (which emits only <p>/<h*>/<li>/<blockquote>/... ) would actually get a body.

It is intentionally a standalone dev script (like scripts/sign-reader-rules.py), not part of the
app build. Requires: pip install beautifulsoup4 soupsieve

Usage:
    # 1. Save each site's article HTML into a fixtures dir, named <host>.html, e.g.
    #      curl -A '<Googlebot UA>' https://www.bbc.com/news/articles/... > fixtures/bbc.com.html
    # 2. Run:
    #      python3 scripts/verify_reader_rules.py fixtures/

    # A host with no fixture is reported as "no fixture" (skipped, not failed) so you can verify
    # incrementally. Exit code is non-zero only if a rule that HAS a fixture matches no container —
    # that is the real "broken include selector" signal.

The `strip` selectors are APPLIED (not just counted) before measuring, exactly as the extractor does
it (`isStripped`: an element is dropped if it is, or is inside, a strip match). A strip selector that
swallows the body is otherwise invisible — e.g. PMC's `.pmc-paywall` is the body WRAPPER on
variety/rollingstone, not a paywall overlay, so stripping it emptied the reader.

Verdicts (textLen/<p> are measured AFTER strips, i.e. what the extractor actually sees):
    OK         include resolved to >800 chars of text with >=1 <p>       (extractor will get a body)
    THIN       matched but little text / no <p> in this fixture          (usually a client-rendered
                                                                          SSR fixture whose body
                                                                          hydrates later; refetch the
                                                                          rendered DOM to be sure)
    OVERSTRIP  include had a real body but the strip selectors ate most  (FAIL: a strip selector
               of it                                                      matches the body wrapper)
    MISS       include selector matched nothing                          (FAIL: fix the selector)
"""
import json
import os
import sys

try:
    from bs4 import BeautifulSoup
except ImportError:
    sys.exit("needs beautifulsoup4 + soupsieve: pip install beautifulsoup4 soupsieve")

RULES_PATH = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "reader_rules.json")


def host_of(rule):
    m = rule.get("match")
    return (m[0] if isinstance(m, list) else m) or "?"


def as_list(v):
    if v is None:
        return []
    return v if isinstance(v, list) else [v]


def fixture_for(host, fixtures_dir):
    # Accept <host>.html or a bare-domain alias (host without a leading www.).
    for name in (f"{host}.html", f"{host.replace('www.', '')}.html"):
        p = os.path.join(fixtures_dir, name)
        if os.path.exists(p):
            return p
    return None


def pick_include(soup, rule):
    """Mirror findArticleElement() in extension/src/extractor.core.js, exactly.

    The extractor does `document.querySelector(sel)` per include selector and accepts the result if
    it is visible and has ANY non-empty text. So it takes the FIRST match in document order — it does
    not look at later matches, and it does not require the match to be substantial. A verifier that
    scans for the first *big* match is more forgiving than the app and will pass rules whose real
    on-device behaviour is a near-empty reader (a 150-char decoy node earlier in the DOM).
    """
    for sel in as_list(rule.get("include")):
        try:
            cand = soup.select_one(sel)
        except Exception:
            continue  # extractor try/catches a bad selector and moves to the next one
        if cand is not None and cand.get_text(strip=True):
            return cand
    return None


def apply_strips(soup, rule):
    """Drop every strip match's subtree, as the extractor's isStripped() does. Returns hit count."""
    hits = 0
    for sel in as_list(rule.get("strip")):
        try:
            matches = soup.select(sel)
        except Exception:
            continue
        for el in matches:
            hits += 1
            el.decompose()
    return hits


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: verify_reader_rules.py <fixtures-dir>   (fixtures named <host>.html)")
    fixtures_dir = sys.argv[1]
    rules = json.load(open(RULES_PATH))["rules"]

    print(f"{'host':24} {'inc':4} {'textLen':8} {'<p>':4} {'strip':6} verdict")
    print("-" * 72)
    failures = 0
    verified = 0
    for rule in rules:
        host = host_of(rule)
        fx = fixture_for(host, fixtures_dir)
        if not fx:
            print(f"{host:24} {'-':4} {'-':8} {'-':4} {'-':6} no fixture (skipped)")
            continue
        raw = open(fx, encoding="utf-8", errors="replace").read()

        # Measure the include container before strips, then again on a fresh parse with the strips
        # actually applied — the gap is what the strip selectors cost.
        before = pick_include(BeautifulSoup(raw, "html.parser"), rule)
        if before is None:
            print(f"{host:24} {'NO':4} {'-':8} {'-':4} {'-':6} MISS *** include matched nothing")
            failures += 1
            continue
        text_before = len(before.get_text(strip=True))

        soup = BeautifulSoup(raw, "html.parser")
        strip_hits = apply_strips(soup, rule)
        el = pick_include(soup, rule)
        text_len = len(el.get_text(strip=True)) if el is not None else 0
        p_count = len(el.find_all("p")) if el is not None else 0

        # A strip selector that matches the body wrapper silently empties the reader.
        overstripped = text_before > 800 and text_len < max(800, text_before * 0.4)
        if overstripped:
            verdict = "OVERSTRIP *** strip ate the body " f"({text_before} -> {text_len} chars)"
            failures += 1
        else:
            verdict = "OK" if text_len > 800 and p_count >= 1 else "THIN"
            verified += 1
        print(f"{host:24} {'yes':4} {text_len:<8} {p_count:<4} {strip_hits:<6} {verdict}")

    print("-" * 72)
    print(f"verified {verified} rule(s) against fixtures; {failures} MISS/OVERSTRIP")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
