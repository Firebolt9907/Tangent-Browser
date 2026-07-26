#!/usr/bin/env python3
"""Fetch live article fixtures for the reader-mode rules, ready for verify_reader_rules.py.

`verify_reader_rules.py` needs a directory of `<host>.html` files holding a REAL article page per
rule. Producing those by hand is the slow part of a rules sweep: most publishers' homepages expose no
article links to curl, so each host needs its own RSS/Atom feed, and the feed's *channel* link is
usually an index page rather than an article. This script encodes the feed URL and the URL-picking
rules that were worked out per host, so a full rot sweep is one command instead of an afternoon.

Usage:
    python3 scripts/fetch_reader_fixtures.py <fixtures-dir>              # every known host
    python3 scripts/fetch_reader_fixtures.py <fixtures-dir> npr.org ...  # just these
    python3 scripts/fetch_reader_fixtures.py --list                      # show known hosts

Then:
    python3 scripts/verify_reader_rules.py <fixtures-dir>

Fixtures are named `<host>.html` where <host> is the rule's FIRST match entry — that is what the
verifier keys on. Hosts whose fetch yields an obvious non-article (bot wall, consent shell, index
page, 404) are reported and skipped rather than written, so a stale fixture can't mask a MISS.

Not exhaustive: it covers the hosts that have a working curl channel. Publishers that bot-wall every
channel (Reuters, Bloomberg, WSJ, the US local-news chains, Zendesk help centers) are listed in
BLOCKED with the reason, so nobody re-derives that the hard way.
"""
import os
import re
import subprocess
import sys

MOBILE_UA = ("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
             "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36")

# host -> RSS/Atom feed. The host key must equal the rule's match[0].
FEEDS = {
    "apnews.com": "https://feedx.net/rss/ap.xml",
    "npr.org": "https://feeds.npr.org/1001/rss.xml",
    "foxnews.com": "https://moxie.foxnews.com/google-publisher/latest.xml",
    "cbsnews.com": "https://www.cbsnews.com/latest/rss/main",
    "cnbc.com": "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=100003114",
    "abcnews.go.com": "https://feeds.abcnews.com/abcnews/topstories",
    "cbc.ca": "https://www.cbc.ca/webfeed/rss/rss-world",
    "aljazeera.com": "https://www.aljazeera.com/xml/rss/all.xml",
    "arstechnica.com": "https://arstechnica.com/feed/",
    "techcrunch.com": "https://techcrunch.com/feed/",
    "wired.com": "https://www.wired.com/feed/rss",
    "gizmodo.com": "https://gizmodo.com/feed",
    "thehill.com": "https://thehill.com/news/feed/",
    "newyorker.com": "https://www.newyorker.com/feed/everything",
    "salon.com": "https://www.salon.com/feed/",
    "propublica.org": "https://www.propublica.org/feeds/propublica/main",
    "businessinsider.com": "https://www.businessinsider.com/rss",
    "time.com": "https://time.com/feed/",
    "fortune.com": "https://fortune.com/feed/fortune-feeds/?id=3230629",
    "benzinga.com": "https://www.benzinga.com/feed",
    "github.blog": "https://github.blog/feed/",
    "freecodecamp.org": "https://www.freecodecamp.org/news/rss/",
    "substack.com": "https://on.substack.com/feed",
    "eater.com": "https://www.eater.com/rss/index.xml",
    "screenrant.com": "https://screenrant.com/feed/",
    "gq.com": "https://www.gq.com/feed/rss",
    "techradar.com": "https://www.techradar.com/feeds.xml",
    "popsci.com": "https://www.popsci.com/feed/",
    "kotaku.com": "https://kotaku.com/rss",
    "jalopnik.com": "https://www.jalopnik.com/feed/",
    "avclub.com": "https://www.avclub.com/rss",
    "pcworld.com": "https://www.pcworld.com/feed",
    "variety.com": "https://variety.com/feed/",
    "indiewire.com": "https://www.indiewire.com/feed/",
    "9to5mac.com": "https://9to5mac.com/feed/",
    "theregister.com": "https://www.theregister.com/headlines.atom",
    "hackaday.com": "https://hackaday.com/feed/",
    "skysports.com": "https://www.skysports.com/rss/12040",
    "eurogamer.net": "https://www.eurogamer.net/feed",
    "decrypt.co": "https://decrypt.co/feed",
    "cbssports.com": "https://www.cbssports.com/rss/headlines/",
    "sciencedaily.com": "https://www.sciencedaily.com/rss/top/science.xml",
    "sciencealert.com": "https://www.sciencealert.com/feed",
    "techspot.com": "https://www.techspot.com/backend.xml",
    "thewrap.com": "https://www.thewrap.com/feed/",
    "smashingmagazine.com": "https://www.smashingmagazine.com/feed/",
    "css-tricks.com": "https://css-tricks.com/feed/",
    "infoq.com": "https://feed.infoq.com/",
    "opensource.com": "https://opensource.com/feed",
    "motor1.com": "https://www.motor1.com/rss/articles/all/",
    "indiatoday.in": "https://www.indiatoday.in/rss/1206578",
    "channelnewsasia.com": "https://www.channelnewsasia.com/api/v1/rss-outbound-feed?_format=xml",
    "timesofisrael.com": "https://www.timesofisrael.com/feed/",
    "jpost.com": "https://www.jpost.com/rss/rssfeedsfrontpage.aspx",
    "elmundo.es": "https://e00-elmundo.uecdn.es/elmundo/rss/portada.xml",
    "corriere.it": "https://xml2.corriereobjects.it/rss/homepage.xml",
    "clarin.com": "https://www.clarin.com/rss/lo-ultimo/",
    "spiegel.de": "https://www.spiegel.de/schlagzeilen/index.rss",
    "elpais.com": "https://feeds.elpais.com/mrss-s/pages/ep/site/elpais.com/portada",
    "irishtimes.com": "https://www.irishtimes.com/arc/outboundfeeds/feed-irish-news/",
    "globalnews.ca": "https://globalnews.ca/feed/",
    "abc.net.au": "https://www.abc.net.au/news/feed/51120/rss.xml",
    "smh.com.au": "https://www.smh.com.au/rss/feed.xml",
    "hindustantimes.com": "https://www.hindustantimes.com/feeds/rss/india-news/rssfeed.xml",
    "indianexpress.com": "https://indianexpress.com/feed/",
    "thehindu.com": "https://www.thehindu.com/news/national/feeder/default.rss",
    "straitstimes.com": "https://www.straitstimes.com/news/singapore/rss.xml",
    "bearblog.dev": "https://herman.bearblog.dev/feed/",
    "krebsonsecurity.com": "https://krebsonsecurity.com/feed/",
    "bleepingcomputer.com": "https://www.bleepingcomputer.com/feed/",
    "ign.com": "https://feeds.ign.com/ign/all",
    "sfgate.com": "https://www.sfgate.com/rss/feed/Bay-Area-News-429.php",
    "seattletimes.com": "https://www.seattletimes.com/feed/",
    "boston.com": "https://www.boston.com/feed/",
    "quantamagazine.org": "https://www.quantamagazine.org/feed/",
    "theintercept.com": "https://theintercept.com/feed/?rss",
    "thenation.com": "https://www.thenation.com/feed/?post_type=article",
    "404media.co": "https://www.404media.co/rss/",
    "nautil.us": "https://nautil.us/feed/",
    "blog.cloudflare.com": "https://blog.cloudflare.com/rss/",
    "engineering.fb.com": "https://engineering.fb.com/feed/",
    "stripe.com": "https://stripe.com/blog/feed.rss",
    "aws.amazon.com": "https://aws.amazon.com/blogs/aws/feed/",
    "dropbox.tech": "https://dropbox.tech/feed",
    "engineering.atspotify.com": "https://engineering.atspotify.com/feed/",
    "martinfowler.com": "https://martinfowler.com/feed.atom",
    "cnet.com": "https://www.cnet.com/rss/news/",
    "apartmenttherapy.com": "https://www.apartmenttherapy.com/main.rss",
    "mentalfloss.com": "https://www.mentalfloss.com/rss.xml",
    "thepointsguy.com": "https://thepointsguy.com/feed/",
    "smittenkitchen.com": "https://smittenkitchen.com/feed/",
    "kingarthurbaking.com": "https://www.kingarthurbaking.com/blog/feed",
    "sciencenews.org": "https://www.sciencenews.org/feed",
    "eos.org": "https://eos.org/feed",
    "sifted.eu": "https://sifted.eu/feed",
    "voanews.com": "https://www.voanews.com/api/zq$omekvi_",
    "thediplomat.com": "https://thediplomat.com/feed/",
    "scotusblog.com": "https://www.scotusblog.com/feed/",
    "pewresearch.org": "https://www.pewresearch.org/feed/",
    "buzzfeed.com": "https://www.buzzfeed.com/index.xml",
    "vice.com": "https://www.vice.com/en/feed/",
    "outsideonline.com": "https://www.outsideonline.com/feed/",
    "huffpost.com": "https://www.huffpost.com/section/us-news/feed",
    "mirror.co.uk": "https://www.mirror.co.uk/news/?service=rss",
    "express.co.uk": "https://www.express.co.uk/posts/rss/78/news",
    "thesun.co.uk": "https://www.thesun.co.uk/feed/",
    "wordpress.com": "https://wordpress.com/blog/feed/",
    "nationalpost.com": "https://nationalpost.com/feed/",
    "rte.ie": "https://www.rte.ie/feeds/rss/?index=/news/",
    "sbs.com.au": "https://www.sbs.com.au/news/feed",
    "koreaherald.com": "https://www.koreaherald.com/rss/newsAll",
    "chinadaily.com.cn": "https://www.chinadaily.com.cn/rss/china_rss.xml",
    "theglobeandmail.com": "https://www.theglobeandmail.com/arc/outboundfeeds/rss/category/canada/",
}

# Hosts with no feed but a stable evergreen article/doc URL.
DIRECT = {
    "developer.mozilla.org": "https://developer.mozilla.org/en-US/docs/Web/CSS/flex",
    "docs.python.org": "https://docs.python.org/3/tutorial/introduction.html",
    "readthedocs.io": "https://requests.readthedocs.io/en/latest/user/quickstart/",
    "gitbook.com": "https://docs.gitbook.com/getting-started/quickstart",
    "github.com": "https://docs.github.com/en/get-started/quickstart/hello-world",
    "plos.org": "https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0292797",
    "arxiv.org": "https://arxiv.org/html/2410.00075v1",
    "wikipedia.org": "https://en.wikipedia.org/wiki/Euler%27s_identity",
    "wikihow.com": "https://www.wikihow.com/Tie-a-Tie",
    "ncbi.nlm.nih.gov": "https://pmc.ncbi.nlm.nih.gov/articles/PMC7096066/",
    "who.int": "https://www.who.int/news-room/fact-sheets/detail/diabetes",
    "un.org": "https://www.un.org/en/climatechange/what-is-climate-change",
    "nasa.gov": "https://www.nasa.gov/news-release/nasa-sets-coverage-for-agencys-spacex-crew-11-launch-docking/",
    "gov.uk": "https://www.gov.uk/vehicle-tax",
    "frontiersin.org": ("https://www.frontiersin.org/journals/psychology/articles/"
                        "10.3389/fpsyg.2023.1145278/full"),
    "bankrate.com": "https://www.bankrate.com/banking/savings/what-is-a-savings-account/",
    "stackoverflow.com": ("https://stackoverflow.com/questions/11227809/"
                          "why-is-processing-a-sorted-array-faster-than-processing-an-unsorted-array"),
    "reactnative.dev": "https://reactnative.dev/docs/getting-started",
    "vitejs.dev": "https://vite.dev/guide/",
    "fastapi.tiangolo.com": "https://fastapi.tiangolo.com/tutorial/first-steps/",
    "kubernetes.io": "https://kubernetes.io/docs/concepts/overview/",
    "docs.astro.build": "https://docs.astro.build/en/guides/routing/",
    "rust-lang.org": "https://doc.rust-lang.org/book/ch03-01-variables-and-mutability.html",
    "docs.gitlab.com": "https://docs.gitlab.com/ee/user/project/repository/",
    "go.dev": "https://go.dev/doc/tutorial/getting-started",
    "docs.djangoproject.com": "https://docs.djangoproject.com/en/5.0/intro/tutorial01/",
    "docs.aws.amazon.com": "https://docs.aws.amazon.com/AmazonS3/latest/userguide/Welcome.html",
    "support.apple.com": "https://support.apple.com/en-us/102651",
    "support.google.com": "https://support.google.com/chrome/answer/95464",
    # Telegraph pages are user-created and vanish; their own API doc page is stable and is rendered
    # with the same `.tl_article_content` template as any post.
    "telegra.ph": "https://telegra.ph/api",
    "dev.to": None,  # resolved at runtime via the API, see fetch_one
}

# Everything below refuses every channel tried. Reason is recorded so the loop stops re-deriving it.
BLOCKED = {
    "reuters.com": "401 to direct + Googlebot; absent from Wayback; arc sitemap articles 401",
    "bloomberg.com": "403 everywhere, not in Wayback",
    "wsj.com": "401; validated once via Wayback, heuristic handles it",
    "nytimes.com": "bot-walls curl (774B); canonical-selector rule, no fixture possible",
    "washingtonpost.com": "000 to curl; use Wayback raw + --compressed if ever needed",
    "medium.com": "403 to mobile AND Googlebot, JS-rendered, empty Wayback CDX (4 attempts)",
    "zendesk.com": "help centers expose no .article-body to curl; theme varies per instance (4 attempts)",
    "denverpost.com/mercurynews.com/baltimoresun.com/miamiherald.com":
        "MediaNews/Alden/Tribune/McClatchy feeds return 0 bytes to mobile and Googlebot",
    "axios.com/politico.com/france24.com": "sub-6KB walls even from feed URLs",
    "learn.microsoft.com/developer.apple.com": "body hydrates client-side for every UA",
    "mdpi.com/bmj.com/cell.com/jamanetwork.com/pnas.org/science.org":
        "publisher CDNs answer curl with a ~5KB challenge page; frontiersin, PLOS and PMC are the "
        "open-access journals that do work",
}



# A fixture with fewer long paragraphs than this is a wall, a consent shell, or a client-rendered
# page — never a usable article fixture. Page SIZE is deliberately not a gate: telegra.ph serves a
# perfectly good 7KB article, while some 20KB responses are pure consent shell.
MIN_LONG_P = 3


def curl(url, out=None, ua=MOBILE_UA):
    cmd = ["curl", "-sSL", "-A", ua, "--max-time", "30", url]
    if out:
        cmd += ["-o", out]
    res = subprocess.run(cmd, capture_output=True)
    return res.stdout.decode("utf-8", "replace") if not out else ""


# Live blogs, video pages and photo galleries are real URLs with real bytes, but their body is a
# feed of updates or a player — an include that is correct for articles legitimately MISSes on them.
NON_ARTICLE_URL = re.compile(r"/(video|videos|live|liveblog|live-blog|gallery|podcast|watch)(/|$)"
                             r"|-live(-|$)|/live-updates", re.I)


def article_urls_from_feed(feed_xml):
    """Yield candidate article links from a feed, best first.

    Deliberately NOT "the first <link>": in most feeds that is the channel link (an index page).
    Only <item>/<entry> bodies are considered, live/video URLs are pushed to the back, and several
    candidates are returned because a publisher's newest item is often a live blog.
    """
    good, deprioritised = [], []
    for item in re.findall(r"<(?:item|entry)[^>]*>(.*?)</(?:item|entry)>", feed_xml, re.S):
        m = (re.search(r"<link>\s*(?:<!\[CDATA\[)?\s*(https?://[^\]\s<]+)", item)
             or re.search(r'<link[^>]*href="(https?://[^"]+)"', item))
        if not m:
            continue
        url = m.group(1)
        # Depth >= 4 means there is at least one path segment past the host, which is enough to
        # exclude the bare channel link. Requiring more drops legitimately flat permalinks
        # (kotaku.com/<slug>, <blog>.bearblog.dev/<slug>).
        if len(url.rstrip("/").split("/")) < 4 or "/feed" in url or url.endswith(".xml"):
            continue
        (deprioritised if NON_ARTICLE_URL.search(url) else good).append(url)
    return good + deprioritised


def looks_like_article(path):
    try:
        raw = open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return False, "unreadable"
    title = re.search(r"<title[^>]*>(.*?)</title>", raw, re.S | re.I)
    if title and re.search(r"page not found|404|access denied|are you a robot", title.group(1), re.I):
        return False, f"error page: {title.group(1).strip()[:40]}"
    long_p = len([1 for m in re.finditer(r"<p[ >](.*?)</p>", raw, re.S)
                  if len(re.sub(r"<[^>]+>", "", m.group(1)).strip()) > 80])
    # Paragraph count is the real signal. Byte size only matters when there is almost no prose:
    # a lean page (telegra.ph is ~7KB) is fine, an 8KB page with no paragraphs is a wall.
    if long_p < MIN_LONG_P:
        return False, f"only {long_p} long <p> in {len(raw)}B (wall or client-rendered)"
    return True, f"{len(raw)}B, {long_p} long <p>"


MAX_CANDIDATES = 4


def fetch_one(host, outdir):
    if host == "dev.to":
        import json
        try:
            candidates = [json.loads(curl("https://dev.to/api/articles?per_page=1"))[0]["url"]]
        except Exception:
            return False, "dev.to API returned nothing"
    elif host in FEEDS:
        candidates = article_urls_from_feed(curl(FEEDS[host]))[:MAX_CANDIDATES]
        if not candidates:
            return False, "feed exposed no article <item> link"
    else:
        url = DIRECT.get(host)
        if not url:
            return False, "no feed or direct URL configured"
        candidates = [url]

    path = os.path.join(outdir, f"{host}.html")
    tmp = path + ".part"
    why = "no candidate fetched"
    for url in candidates:
        curl(url, tmp)
        ok, why = looks_like_article(tmp)
        if ok:
            os.replace(tmp, path)
            return True, why
        # Try the next feed item: the newest post is often a live blog or a video.
    if os.path.exists(tmp):
        os.remove(tmp)
    return False, f"{why} <- {candidates[-1][:60]}"


def main():
    args = [a for a in sys.argv[1:]]
    if "--list" in args:
        print("feed-backed hosts:")
        for h in sorted(FEEDS):
            print(f"  {h}")
        print("direct-URL hosts:")
        for h in sorted(DIRECT):
            print(f"  {h}")
        print("known-blocked (do not retry without a new channel):")
        for h, why in BLOCKED.items():
            print(f"  {h}: {why}")
        return
    if not args:
        sys.exit(__doc__.strip().splitlines()[0] + "\n\nusage: fetch_reader_fixtures.py <dir> [host ...]")
    outdir, hosts = args[0], args[1:]
    os.makedirs(outdir, exist_ok=True)
    targets = hosts or sorted(set(FEEDS) | set(DIRECT))

    ok_n = skip_n = 0
    for host in targets:
        ok, why = fetch_one(host, outdir)
        print(f"{host:26} {'ok  ' if ok else 'SKIP'} {why}")
        ok_n, skip_n = ok_n + int(ok), skip_n + int(not ok)
    print(f"\n{ok_n} fixture(s) written to {outdir}, {skip_n} skipped")
    print("next: python3 scripts/verify_reader_rules.py " + outdir)


if __name__ == "__main__":
    main()
