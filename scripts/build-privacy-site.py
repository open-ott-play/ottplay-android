#!/usr/bin/env python3
"""Render the checked-in privacy document as a script-free public page."""
import argparse
from html import escape
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def inline(text):
    text = escape(text)
    text = re.sub(r"\[([^\]]+)\]\((https://[^\s)]+|mailto:[^\s)]+)\)", r'<a href="\2">\1</a>', text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"`([^`]+)`", r"<code>\1</code>", text)
    return text

def section_id(title):
    return re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")


def render_document(content, title, description, page, headings):
    """Shared chrome for the public policy and playback-review pages."""
    base = "../" if page == "review" else "./"
    contents = "\n".join(
        f'<a href="#{escape(anchor)}">{escape(label)}</a>'
        for anchor, label in headings
    )
    privacy_current = ' aria-current="page"' if page == "privacy" else ""
    review_current = ' aria-current="page"' if page == "review" else ""
    return f'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#0d1418">
<title>{escape(title)}</title>
<meta name="description" content="{escape(description, quote=True)}">
<link rel="stylesheet" href="{base}style.css">
</head>
<body id="top">
<a class="skip-link" href="#main">Skip to content</a>
<header class="site-header">
  <div class="header-inner">
    <a class="brand" href="{base}" aria-label="OTT-play Native documentation home">
      <span class="brand-mark" aria-hidden="true"></span>
      <span><strong>OTT-play</strong><small>Native for Android</small></span>
    </a>
    <nav class="site-nav" aria-label="Main navigation">
      <a href="{base}"{privacy_current}>Privacy policy</a>
      <a href="{base}review/"{review_current}>Playback review</a>
    </nav>
  </div>
</header>
<main id="main" class="reading-layout" tabindex="-1">
  <aside class="desktop-contents">
    <p class="nav-label">On this page</p>
    <nav class="section-links" aria-label="Page sections">{contents}</nav>
    <a class="contact-link" href="mailto:alvit.work@gmail.com">Contact support <span aria-hidden="true">↗</span></a>
  </aside>
  <div class="reading-column">
    <p class="page-kicker">OTT-play Native <span aria-hidden="true">/</span> {'Privacy policy' if page == 'privacy' else 'Playback review'}</p>
    <details class="mobile-contents">
      <summary>On this page</summary>
      <nav class="section-links" aria-label="Page sections">{contents}</nav>
    </details>
    <article class="document" id="document-content">
{content}
    </article>
    <footer class="page-footer"><span>Published by alvit</span><a href="#top">Back to top <span aria-hidden="true">↑</span></a></footer>
  </div>
</main>
</body>
</html>
'''


def build():
    markdown = (ROOT / "docs/privacy-policy.md").read_text()
    assert "alvit.work@gmail.com" in markdown and "play.ott.foss.nativeapp" in markdown
    assert not re.search(r"\b(TODO|TBD|PLACEHOLDER)\b", markdown)
    blocks, headings = [], []
    for paragraph in markdown.strip().split("\n\n"):
        if paragraph.startswith("# "):
            blocks.append("<h1>" + inline(paragraph[2:]) + "</h1>")
        elif paragraph.startswith("## "):
            title = paragraph[3:]
            anchor = section_id(title)
            headings.append((anchor, title))
            blocks.append(f'<h2 id="{anchor}">' + inline(title) + "</h2>")
        elif paragraph.startswith("- "):
            blocks.append("<ul>" + "".join("<li>" + inline(line[2:]) + "</li>" for line in paragraph.splitlines()) + "</ul>")
        else:
            blocks.append("<p>" + inline(paragraph.replace("\n", " ")) + "</p>")
    assert len({anchor for anchor, _ in headings}) == len(headings), "Duplicate section anchor"
    return render_document(
        "\n".join(blocks),
        "Privacy Policy — OTT-play Native for Android",
        "How OTT-play Native handles source credentials, playback, programme guides and local data. Published by alvit.",
        "privacy", headings,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check the generated page without changing files")
    args = parser.parse_args()
    destination = ROOT / "docs/site/index.html"
    page = build()
    if args.check:
        if not destination.is_file() or destination.read_text() != page:
            raise SystemExit("Privacy page is stale: run python3 scripts/build-privacy-site.py")
        print("Privacy page matches the policy and generator.")
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(page)
    print(f"Rendered {destination.relative_to(ROOT)} ({len(page.encode())} bytes)")


if __name__ == "__main__":
    main()
