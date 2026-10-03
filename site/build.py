#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build the RideFlux website into site/dist (standard library only).

    python site/build.py            # build + validate
    python site/build.py --serve    # build, then serve dist/ on http://localhost:8000/

What it does
  * renders the landing page once per language (18) from site/src/i18n/<code>.json,
  * renders the privacy policy from the repository's PRIVACY.md (English + 繁體中文),
  * renders the release notes from site/src/changelog.json,
  * writes sitemap.xml, robots.txt, 404.html and copies site/assets,
  * fails the build when a translation is missing a key, has a blank value or drops
    inline markup, and when any internal link, image or #anchor points nowhere.

Environment: SITE_URL (default https://zero2005x.github.io/RideFlux) is used for canonical
and hreflang URLs; SITE_DIST overrides the output directory.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import html
import json
import os
import posixpath
import re
import shutil
import sys
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent
SRC = HERE / "src"
ASSETS = HERE / "assets"
DIST = Path(os.environ.get("SITE_DIST") or HERE / "dist")

# `or`, not a get() default: CI passes SITE_URL="" on pull requests, which must still fall back.
SITE_URL = (os.environ.get("SITE_URL") or "https://zero2005x.github.io/RideFlux").rstrip("/")
REPO_URL = "https://github.com/zero2005x/RideFlux"
PLAY_ID = "com.rideflux.app"
EFFECTIVE_RE = re.compile(r"Effective date / 生效日期:\s*(\S+)")


@dataclass(frozen=True)
class Lang:
    code: str        # BCP-47 tag, also the URL folder (English lives at the root)
    name: str        # endonym
    dir: str         # ltr | rtl
    script: str      # latin | cyrillic | cjk | indic | arabic - drives a few typography rules
    play_hl: str     # hl= parameter of the Play Store link
    badge: str       # Google's official badge file prefix; "en" where Google ships none
    og_locale: str
    shots: str       # screenshot set: only English and Traditional Chinese exist


LANGS = [
    Lang("en", "English", "ltr", "latin", "en", "en", "en_US", "en"),
    Lang("zh-TW", "繁體中文", "ltr", "cjk", "zh-TW", "zh-tw", "zh_TW", "zh-TW"),
    Lang("zh-CN", "简体中文", "ltr", "cjk", "zh-CN", "zh-cn", "zh_CN", "en"),
    Lang("hi", "हिन्दी", "ltr", "indic", "hi", "hi", "hi_IN", "en"),
    Lang("es", "Español", "ltr", "latin", "es", "es", "es_ES", "en"),
    Lang("ar", "العربية", "rtl", "arabic", "ar", "ar", "ar_AR", "en"),
    Lang("fr", "Français", "ltr", "latin", "fr", "fr", "fr_FR", "en"),
    Lang("pt", "Português", "ltr", "latin", "pt-BR", "pt-br", "pt_BR", "en"),
    Lang("ru", "Русский", "ltr", "cyrillic", "ru", "ru", "ru_RU", "en"),
    Lang("ur", "اردو", "rtl", "arabic", "ur", "ur", "ur_PK", "en"),
    Lang("de", "Deutsch", "ltr", "latin", "de", "de", "de_DE", "en"),
    Lang("ja", "日本語", "ltr", "cjk", "ja", "ja", "ja_JP", "en"),
    Lang("vi", "Tiếng Việt", "ltr", "latin", "vi", "vi", "vi_VN", "en"),
    Lang("ko", "한국어", "ltr", "cjk", "ko", "ko", "ko_KR", "en"),
    Lang("it", "Italiano", "ltr", "latin", "it", "it", "it_IT", "en"),
    Lang("uk", "Українська", "ltr", "cyrillic", "uk", "en", "uk_UA", "en"),   # Google ships no uk badge
    Lang("nl", "Nederlands", "ltr", "latin", "nl", "nl", "nl_NL", "en"),
    Lang("id", "Bahasa Indonesia", "ltr", "latin", "id", "id", "id_ID", "en"),
]
BY_CODE = {l.code: l for l in LANGS}
EN = BY_CODE["en"]

# ---------------------------------------------------------------- icons (24x24, stroke)

ICONS = {
    "gauge": '<path d="M3.5 17a8.5 8.5 0 0 1 17 0"/><path d="M12 17l3.6-5.4"/><circle cx="12" cy="17" r="1.4" fill="currentColor" stroke="none"/><path d="M5.6 11.2l1.3.9M12 8v1.5M18.4 11.2l-1.3.9"/>',
    "shield": '<path d="M12 3 5 6v5.5c0 4.3 2.9 7.6 7 9.5 4.1-1.9 7-5.2 7-9.5V6z"/><path d="M12 8.5v4.2"/><circle cx="12" cy="16" r="1" fill="currentColor" stroke="none"/>',
    "route": '<circle cx="6" cy="18" r="2.2"/><circle cx="18" cy="6" r="2.2"/><path d="M8.2 18H15a3.2 3.2 0 0 0 0-6.4H9a3.2 3.2 0 0 1 0-6.4h6.8"/>',
    "export": '<path d="M12 4v10.5"/><path d="M7.5 10.5 12 15l4.5-4.5"/><path d="M5 19.5h14"/>',
    "sliders": '<path d="M4 7h9M17 7h3M4 17h3M11 17h9"/><circle cx="15" cy="7" r="2"/><circle cx="9" cy="17" r="2"/>',
    "glasses": '<circle cx="7" cy="14.5" r="3.6"/><circle cx="17" cy="14.5" r="3.6"/><path d="M10.6 14.2h2.8"/><path d="M3.4 14 5.2 7.5M20.6 14 18.8 7.5"/>',
    "wheel": '<circle cx="12" cy="12" r="8.2"/><circle cx="12" cy="12" r="2.2"/><path d="M12 3.8v6M12 14.2v6M3.8 12h6M14.2 12h6"/>',
    "phone": '<rect x="7" y="2.8" width="10" height="18.4" rx="2.4"/><path d="M10.8 18h2.4"/>',
    "ring": '<circle cx="12" cy="14.2" r="6"/><path d="M8.2 4.2h7.6L14.4 8h-4.8z"/>',
    "wifi-off": '<path d="M3.5 3.5l17 17"/><path d="M8.6 16.1a5 5 0 0 1 3.4-1.4M5 12.4A10 10 0 0 1 8.4 10M12 7c3.2 0 6.1 1.2 8.3 3.3M15.5 12a5 5 0 0 1 1.6 1.1"/><circle cx="12" cy="19.2" r="1.1" fill="currentColor" stroke="none"/>',
    "eye-off": '<path d="M2.5 12S6 5.8 12 5.8 21.5 12 21.5 12 18 18.2 12 18.2 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="2.7"/><path d="M4 4l16 16"/>',
    "code": '<path d="M8.5 7 3.5 12l5 5M15.5 7l5 5-5 5M13.5 5l-3 14"/>',
    "globe": '<circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17M12 3.5c-3.2 3.2-3.2 13.8 0 17M12 3.5c3.2 3.2 3.2 13.8 0 17"/>',
}


def svg(name: str, extra: str = "") -> str:
    return (f'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" '
            f'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"{extra}>{ICONS[name]}</svg>')


ARROW_H = ('<svg viewBox="0 0 38 14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" '
           'stroke-linejoin="round" aria-hidden="true"><path d="M2 7h32M28 2l6 5-6 5"/></svg>')
ARROW_UP = ('<svg viewBox="0 0 14 30" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" '
            'stroke-linejoin="round" stroke-dasharray="1 5" aria-hidden="true"><path d="M7 28V5M2 10l5-6 5 6" '
            'stroke-dasharray="none"/></svg>')

# ---------------------------------------------------------------- i18n

INLINE_TOKENS = ("**", "`", "](")


def inline(s: str) -> str:
    """Plain text -> HTML with a tiny markup: **bold**, `code`, [text](https://url)."""
    out = html.escape(s, quote=False)
    out = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", out)
    out = re.sub(r"`(.+?)`", r"<code>\1</code>", out)
    out = re.sub(r"\[([^\]]+)\]\((https?://[^)\s]+)\)", r'<a href="\2" rel="noopener">\1</a>', out)
    return out


def load_i18n() -> dict[str, dict[str, str]]:
    en = json.loads((SRC / "i18n" / "en.json").read_text(encoding="utf-8"))
    problems: list[str] = []
    result: dict[str, dict[str, str]] = {}
    for lang in LANGS:
        path = SRC / "i18n" / f"{lang.code}.json"
        if not path.exists():
            problems.append(f"{lang.code}: {path.name} is missing")
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        missing = sorted(en.keys() - data.keys())
        extra = sorted(data.keys() - en.keys())
        if missing:
            problems.append(f"{lang.code}: missing keys {missing}")
        if extra:
            problems.append(f"{lang.code}: unknown keys {extra}")
        for key, value in data.items():
            if key not in en:
                continue
            if not isinstance(value, str) or not value.strip():
                problems.append(f"{lang.code}: blank value for {key}")
                continue
            if "{{" in value or "}}" in value or "<" in value:
                problems.append(f"{lang.code}: {key} contains template or HTML syntax")
            for token in INLINE_TOKENS:
                if value.count(token) != en[key].count(token):
                    problems.append(f"{lang.code}: {key} has {value.count(token)}x {token!r}, "
                                    f"English has {en[key].count(token)}x")
        if lang.code != "en" and not os.environ.get("SITE_ALLOW_UNTRANSLATED"):
            long_keys = [k for k, v in en.items() if len(v) >= 30]
            same = [k for k in long_keys if data.get(k) == en[k]]
            if len(same) > 0.2 * len(long_keys):
                problems.append(f"{lang.code}: {len(same)} of {len(long_keys)} long strings are identical to "
                                f"English - untranslated? (SITE_ALLOW_UNTRANSLATED=1 skips this check)")
        result[lang.code] = data
    if problems:
        raise SystemExit("i18n validation failed:\n  " + "\n  ".join(problems))
    return result


# ---------------------------------------------------------------- templating

TOKEN = re.compile(r"\{\{([tpv@]):?([\w.]+)\}\}")


def render(template: str, strings: dict[str, str], ctx: dict[str, object]) -> str:
    def sub(m: re.Match) -> str:
        kind, key = m.groups()
        if kind == "t":
            return inline(strings[key])
        if kind == "p":
            return html.escape(strings[key], quote=True)
        if kind == "v":
            return html.escape(str(ctx[key]), quote=True)
        return str(ctx[key])
    return TOKEN.sub(sub, template)


def rel(from_dir: str, to_dir: str) -> str:
    """Relative URL between two site folders ('' is the root, folders end with '/')."""
    r = posixpath.relpath("/" + to_dir.rstrip("/"), "/" + from_dir.rstrip("/"))
    if r == ".":
        return "./"
    return r + "/" if (to_dir == "" or to_dir.endswith("/")) else r


def landing_dir(lang: Lang) -> str:
    return "" if lang.code == "en" else f"{lang.code}/"


def privacy_dir(lang_code: str) -> str:
    return "zh-TW/privacy/" if lang_code == "zh-TW" else "privacy/"


CHANGELOG_DIR = "changelog/"


def play_url(lang: Lang) -> str:
    return f"https://play.google.com/store/apps/details?id={PLAY_ID}&hl={lang.play_hl}"


def badge_url(lang: Lang) -> str:
    return f"https://play.google.com/intl/en_us/badges/static/images/badges/{lang.badge}_badge_web_generic.png"


# ---------------------------------------------------------------- shared page parts

def lang_menu(here: Lang, page_dir: str, s: dict[str, str]) -> str:
    items = []
    for l in LANGS:
        cur = ' aria-current="true"' if l.code == here.code else ""
        items.append(f'<li><a href="{rel(page_dir, landing_dir(l))}" hreflang="{l.code}" lang="{l.code}" '
                     f'data-lang-choice="{l.code}"{cur}>{html.escape(l.name)}</a></li>')
    return (f'<details class="lang"><summary aria-label="{html.escape(s["nav.language"], quote=True)}">'
            f'{svg("globe")}<span>{html.escape(here.name)}</span></summary>'
            f'<ul class="popover">{"".join(items)}</ul></details>')


def lang_cloud(here: Lang, page_dir: str) -> str:
    out = []
    for l in LANGS:
        cur = ' aria-current="true"' if l.code == here.code else ""
        out.append(f'<a href="{rel(page_dir, landing_dir(l))}" hreflang="{l.code}" lang="{l.code}" '
                   f'data-lang-choice="{l.code}"{cur}>{html.escape(l.name)}</a>')
    return "".join(out)


def nav_links(home: str, s: dict[str, str]) -> str:
    pairs = [("features", "nav.features"), ("hud", "nav.hud"), ("wheels", "nav.wheels"),
             ("faq", "nav.faq"), ("new", "nav.new")]
    return "".join(f'<a href="{home}#{anchor}">{html.escape(s[key])}</a>' for anchor, key in pairs)


def page_context(lang: Lang, page_dir: str, *, title: str, description: str, canonical: str,
                 version: dict[str, str]) -> dict[str, object]:
    root = rel(page_dir, "")
    home = rel(page_dir, landing_dir(lang))
    og = f"{SITE_URL}/assets/og/{lang.shots}.jpg"
    return {
        "lang": lang.code, "dir": lang.dir, "script": lang.script, "root": root, "home": home,
        "title": title, "description": description, "canonical": canonical,
        "og_image": og, "og_locale": lang.og_locale,
        "play_url": play_url(lang), "repo_url": REPO_URL,
        "releases_url": f"{REPO_URL}/releases/latest", "issues_url": f"{REPO_URL}/issues",
        "docs_url": f"{REPO_URL}/tree/main/docs",
        "privacy_url": rel(page_dir, privacy_dir(lang.code)),
        "changelog_url": rel(page_dir, CHANGELOG_DIR),
        "css_v": version["css"], "js_v": version["js"],
        "html_attrs": "", "robots": "", "alternates": "", "jsonld": "",
    }


def full_page(lang: Lang, page_dir: str, s: dict[str, str], ctx: dict[str, object], content: str) -> str:
    ctx = dict(ctx)
    ctx["nav_links"] = nav_links(str(ctx["home"]), s)
    ctx["lang_menu"] = lang_menu(lang, page_dir, s)
    ctx["lang_cloud"] = lang_cloud(lang, page_dir)
    header = render((SRC / "partial-header.html").read_text(encoding="utf-8"), s, ctx)
    footer = render((SRC / "partial-footer.html").read_text(encoding="utf-8"), s, ctx)
    ctx.update(header=header, footer=footer, content=content)
    return render((SRC / "layout.html").read_text(encoding="utf-8"), s, ctx)


def alternates_for(pairs: list[tuple[str, str]], default_dir: str) -> str:
    lines = [f'<link rel="alternate" hreflang="{code}" href="{SITE_URL}/{d}">' for code, d in pairs]
    lines.append(f'<link rel="alternate" hreflang="x-default" href="{SITE_URL}/{default_dir}">')
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------- landing page

WHEEL_ROWS = [
    # brand, models (literal text or i18n key), status, note key
    ("Begode / Gotway / ExtremeBull", "A2", "verified", "wheels.begode.note"),
    ("Begode / Gotway / ExtremeBull", "@wheels.begode.other", "exp", None),
    ("KingSong", "—", "exp", None),
    ("Veteran", "Sherman, Abrams, Patton, Lynx, Oryx, Nosfet", "exp", None),
    ("Ninebot", "One, E+, S2, Mini · Z, ZT, KickScooter Z", "exp", None),
    ("Inmotion", "V5, V8, V10 · V9, V11, V12, V13, V14", "exp", None),
]
SHOTS = [("01-dashboard", "shot.dashboard"), ("02-live-charts", "shot.charts"), ("03-parameters", "shot.params"),
         ("04-trip-recording", "shot.trips"), ("05-trip-detail", "shot.detail"), ("06-hud", "shot.hud"),
         ("07-alert-settings", "shot.alerts")]
FEATURES = [("gauge", "f.dash"), ("shield", "f.alerts"), ("route", "f.trips"),
            ("export", "f.export"), ("sliders", "f.controls"), ("glasses", "f.hud")]
TRUST = [("wifi-off", "trust.net"), ("eye-off", "trust.ads"), ("code", "trust.oss"), ("globe", "trust.lang")]


def esc(s: str) -> str:
    return html.escape(s, quote=True)


def landing_blocks(lang: Lang, page_dir: str, s: dict[str, str], changelog: dict) -> dict[str, str]:
    t = lambda k: inline(s[k])  # noqa: E731

    trust = "".join(
        f'<div class="trust-item">{svg(icon)}<div><strong>{t(k + ".t")}</strong><span>{t(k + ".d")}</span></div></div>'
        for icon, k in TRUST)

    cards = "".join(
        f'<article class="card"><div class="icon-box">{svg(icon)}</div><h3>{t(k + ".t")}</h3><p>{t(k + ".d")}</p></article>'
        for icon, k in FEATURES)

    shots = "".join(
        f'<li><figure><div class="phone"><img src="{{root}}assets/shots/{lang.shots}/{stem}.webp" '
        f'alt="{esc(s[key])}" width="540" height="960" loading="lazy"></div>'
        f'<figcaption>{t(key)}</figcaption></figure></li>'
        for stem, key in SHOTS).replace("{root}", rel(page_dir, ""))

    hud_points = "".join(f"<li>{t(k)}</li>" for k in ("hud.b1", "hud.b2", "hud.b3", "hud.b4"))

    def node(icon: str, key: str, cls: str = "") -> str:
        return (f'<div class="node {cls}"><span class="icon-box">{svg(icon)}</span>'
                f'<span>{t(key)}</span></div>')

    def link(key: str) -> str:
        return f'<div class="link">{ARROW_H}<span>{t(key)}</span></div>'

    diagram = (
        '<div class="flow">'
        + node("wheel", "hud.d.wheel") + link("hud.d.l1")
        + node("phone", "hud.d.phone") + link("hud.d.l2")
        + node("glasses", "hud.d.glasses", "node-glasses")
        + "</div>"
        '<div class="flow-ring">'
        f'<div class="vlink">{ARROW_UP}<span>{t("hud.d.l3")}</span></div>'
        + node("ring", "hud.d.ring", "node-ring")
        + "</div>")

    rows = []
    for i, (brand, models, status, note_key) in enumerate(WHEEL_ROWS):
        models_html = t(models[1:]) if models.startswith("@") else esc(models)
        pill = ('<span class="pill pill-ok">' + t("status.verified") + "</span>" if status == "verified"
                else '<span class="pill pill-warn">' + t("status.exp") + "</span>")
        note = f"<small>{t(note_key)}</small>" if note_key else ""
        rows.append(f'<div class="tr" role="row"><span class="brand-cell" role="cell">{esc(brand)}</span>'
                    f'<span class="models" role="cell">{models_html}</span>'
                    f'<span class="status" role="cell">{pill}{note}</span></div>')

    priv_items = "".join(f"<li>{t(k)}</li>" for k in ("privacy.p1", "privacy.p2", "privacy.p3", "privacy.p4"))
    if lang.code in ("en", "zh-TW"):
        priv_note = ""
    else:
        priv_note = ('<p class="small-note"><a href="{en}">English</a> · <a href="{zh}">繁體中文</a></p>'
                     .format(en=rel(page_dir, privacy_dir("en")), zh=rel(page_dir, privacy_dir("zh-TW"))))

    faq = "".join(
        f'<details><summary>{t(f"faq.q{i}")}</summary><div class="answer"><p>{t(f"faq.a{i}")}</p></div></details>'
        for i in range(1, 8))

    return {
        "icon_code": svg("code"),
        "trust_items": trust, "feature_cards": cards, "shot_items": shots, "hud_points": hud_points,
        "hud_diagram": diagram, "wheel_rows": "".join(rows), "privacy_items": priv_items,
        "privacy_note": priv_note, "faq_items": faq,
        "news_card": release_card(changelog["releases"][0], teaser=True, page_dir=page_dir, s=s),
    }


def release_card(rel_: dict, *, teaser: bool, page_dir: str, s: dict[str, str]) -> str:
    notes = "".join(f"<li>{html.escape(n)}</li>" for n in rel_["notes"])
    foot = (f'<div class="release-foot"><a href="{esc(rel_["url"])}" rel="noopener">GitHub</a>'
            + (f'<a href="{rel(page_dir, CHANGELOG_DIR)}">{html.escape(s["new.all"])}</a>' if teaser else "")
            + "</div>")
    return (f'<article class="release"><div class="release-head"><h3>v{html.escape(rel_["version"])}</h3>'
            f'<time datetime="{rel_["date"]}">{rel_["date"]}</time></div>'
            f'<ul lang="en" dir="ltr">{notes}</ul>{foot}</article>')


def jsonld(lang: Lang, canonical: str, description: str) -> str:
    data = {
        "@context": "https://schema.org",
        "@type": "MobileApplication",
        "name": "RideFlux",
        "applicationCategory": "UtilitiesApplication",
        "operatingSystem": "Android 9+",
        "description": description,
        "url": canonical,
        "installUrl": play_url(lang),
        "image": f"{SITE_URL}/assets/og/{lang.shots}.jpg",
        "inLanguage": lang.code,
        "license": "https://www.gnu.org/licenses/gpl-3.0.html",
        "offers": {"@type": "Offer", "price": "0", "priceCurrency": "USD"},
        "author": {"@type": "Organization", "name": "RideFlux contributors", "url": REPO_URL},
        "sameAs": [REPO_URL],
    }
    body = json.dumps(data, ensure_ascii=False, indent=2).replace("</", "<\\/")
    return f'<script type="application/ld+json">\n{body}\n</script>\n'


def build_landing(lang: Lang, strings: dict[str, dict[str, str]], changelog: dict, version: dict[str, str]) -> tuple[str, str]:
    s = strings[lang.code]
    page_dir = landing_dir(lang)
    canonical = f"{SITE_URL}/{page_dir}"
    ctx = page_context(lang, page_dir, title=s["meta.title"], description=s["meta.description"],
                       canonical=canonical, version=version)
    ctx["badge_url"] = badge_url(lang)
    ctx["shots"] = lang.shots
    ctx["alternates"] = alternates_for([(l.code, landing_dir(l)) for l in LANGS], "")
    ctx["jsonld"] = jsonld(lang, canonical, s["meta.description"])
    if lang.code == "en":
        attrs = [' data-autolang="1"', f' data-supported="{",".join(l.code for l in LANGS)}"']
        attrs += [f' data-home-{l.code}="{landing_dir(l)}"' for l in LANGS if l.code != "en"]
        ctx["html_attrs"] = "".join(attrs)
    ctx.update(landing_blocks(lang, page_dir, s, changelog))
    content = render((SRC / "content-index.html").read_text(encoding="utf-8"), s, ctx)
    return page_dir, full_page(lang, page_dir, s, ctx, content)


# ---------------------------------------------------------------- prose pages

CJK = re.compile(r"[　-〿㐀-鿿＀-￯]")


def md_inline(text: str) -> str:
    codes: list[str] = []

    def stash(m: re.Match) -> str:
        codes.append(m.group(1))
        return f"\x00{len(codes) - 1}\x00"

    text = re.sub(r"`([^`]+)`", stash, text)
    text = re.sub(r"<(https?://[^>\s]+)>", lambda m: f"[{m.group(1)}]({m.group(1)})", text)
    text = html.escape(text, quote=False)
    text = re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)",
                  lambda m: f'<a href="{html.escape(m.group(2), quote=True)}" rel="noopener">{m.group(1)}</a>', text)
    text = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", text)
    return re.sub(r"\x00(\d+)\x00", lambda m: f"<code>{html.escape(codes[int(m.group(1))])}</code>", text)


def join_lines(lines: list[str]) -> str:
    out = lines[0].strip()
    for ln in lines[1:]:
        ln = ln.strip()
        both_cjk = out and CJK.match(out[-1]) and CJK.match(ln[:1] or " ")
        out += ("" if both_cjk else " ") + ln
    return out


def md_to_html(md: str) -> str:
    lines = md.split("\n")
    out: list[str] = []
    i = 0
    while i < len(lines):
        ln = lines[i]
        if not ln.strip() or ln.strip() == "---":
            i += 1
            continue
        m = re.match(r"^(#{2,4}) (.*)$", ln)
        if m:
            level = len(m.group(1)) - 1            # ### -> h2, #### -> h3 (the page owns the h1)
            out.append(f"<h{level}>{md_inline(m.group(2))}</h{level}>")
            i += 1
        elif ln.lstrip().startswith("|"):
            rows = []
            while i < len(lines) and lines[i].lstrip().startswith("|"):
                rows.append([c.strip() for c in lines[i].strip().strip("|").split("|")])
                i += 1
            head, body = rows[0], [r for r in rows[2:]]
            th = "".join(f"<th>{md_inline(c)}</th>" for c in head)
            tr = "".join("<tr>" + "".join(f"<td>{md_inline(c)}</td>" for c in r) + "</tr>" for r in body)
            out.append(f"<table><thead><tr>{th}</tr></thead><tbody>{tr}</tbody></table>")
        elif re.match(r"^\s*[-*] ", ln):
            items: list[list[str]] = []
            while i < len(lines) and (re.match(r"^\s*[-*] ", lines[i]) or (lines[i].startswith("  ") and items)):
                if re.match(r"^\s*[-*] ", lines[i]):
                    items.append([re.sub(r"^\s*[-*] ", "", lines[i])])
                else:
                    items[-1].append(lines[i])
                i += 1
            out.append("<ul>" + "".join(f"<li>{md_inline(join_lines(it))}</li>" for it in items) + "</ul>")
        else:
            para = []
            while i < len(lines) and lines[i].strip() and not re.match(r"^(#{2,4} |\s*[-*] |\s*\|)", lines[i]) \
                    and lines[i].strip() != "---":
                para.append(lines[i])
                i += 1
            out.append(f"<p>{md_inline(join_lines(para))}</p>")
    return "\n".join(out)


def privacy_sections() -> dict[str, tuple[str, str]]:
    """PRIVACY.md -> {lang code: (effective date, markdown body)}."""
    text = (REPO_ROOT / "PRIVACY.md").read_text(encoding="utf-8")
    m = EFFECTIVE_RE.search(text)
    if not m:
        raise SystemExit("PRIVACY.md: effective date line not found")
    date = m.group(1)
    parts = re.split(r"^## (English|繁體中文)\s*$", text, flags=re.M)
    # parts = [preamble, 'English', body, '繁體中文', body]
    bodies = {parts[i]: parts[i + 1] for i in range(1, len(parts) - 1, 2)}
    if set(bodies) != {"English", "繁體中文"}:
        raise SystemExit("PRIVACY.md: expected '## English' and '## 繁體中文' sections")
    return {"en": (date, bodies["English"]), "zh-TW": (date, bodies["繁體中文"])}


PRIVACY_LABELS = {"en": ("Effective date", "Back"), "zh-TW": ("生效日期", "返回")}


def prose_page(lang: Lang, page_dir: str, s: dict[str, str], version: dict[str, str], *, title: str,
               description: str, body_html: str, alternates: str = "") -> str:
    canonical = f"{SITE_URL}/{page_dir}"
    ctx = page_context(lang, page_dir, title=title, description=description, canonical=canonical, version=version)
    ctx["alternates"] = alternates
    return full_page(lang, page_dir, s, ctx, f'<div class="container page"><article class="prose">{body_html}</article></div>')


def build_privacy(strings, version) -> list[tuple[str, str]]:
    sections = privacy_sections()
    pages = []
    for code in ("en", "zh-TW"):
        lang = BY_CODE[code]
        s = strings[code]
        date, md = sections[code]
        eff, back = PRIVACY_LABELS[code]
        page_dir = privacy_dir(code)
        toggle = ""
        for c in ("en", "zh-TW"):
            cur = ' aria-current="true"' if c == code else ""
            toggle += (f'<a href="{rel(page_dir, privacy_dir(c))}" hreflang="{c}" lang="{c}"{cur}>'
                       f'{html.escape(BY_CODE[c].name)}</a>')
        body = (f'<a class="back" href="{rel(page_dir, landing_dir(lang))}">← RideFlux</a>'
                f'<h1>{html.escape(s["page.privacy.title"])}</h1>'
                f'<p class="meta">{eff}: <time datetime="{date}">{date}</time> · <code>{PLAY_ID}</code></p>'
                f'<div class="toggle">{toggle}</div>' + md_to_html(md))
        pages.append((page_dir, prose_page(
            lang, page_dir, s, version, title=f'{s["page.privacy.title"]} — RideFlux',
            description=s["privacy.lead"], body_html=body,
            alternates=alternates_for([("en", privacy_dir("en")), ("zh-TW", privacy_dir("zh-TW"))], privacy_dir("en")))))
    return pages


def build_changelog(strings, changelog, version) -> tuple[str, str]:
    lang, s = EN, strings["en"]
    page_dir = CHANGELOG_DIR
    cards = "".join(release_card(r, teaser=False, page_dir=page_dir, s=s) for r in changelog["releases"])
    body = (f'<a class="back" href="{rel(page_dir, "")}">← RideFlux</a>'
            f'<h1>{html.escape(s["page.changelog.title"])}</h1>'
            f'<p class="meta">{html.escape(s["page.changelog.note"])} '
            f'<a href="{REPO_URL}/releases" rel="noopener">GitHub Releases</a></p>{cards}')
    return page_dir, prose_page(lang, page_dir, s, version, title=f'{s["page.changelog.title"]} — RideFlux',
                                description=s["page.changelog.title"] + " — RideFlux", body_html=body)


def build_404(strings, version) -> str:
    lang, s = EN, strings["en"]
    ctx = page_context(lang, "", title=f'{s["page.404.title"]} — RideFlux', description=s["page.404.body"],
                       canonical=f"{SITE_URL}/", version=version)
    # GitHub Pages serves 404.html from any depth, so every URL on it must be absolute.
    ctx.update(root=SITE_URL + "/", home=SITE_URL + "/", privacy_url=f"{SITE_URL}/privacy/",
               changelog_url=f"{SITE_URL}/changelog/", robots='<meta name="robots" content="noindex">\n')
    body = (f'<div class="container notfound"><h1>404</h1><p>{html.escape(s["page.404.body"])}</p>'
            f'<p><a class="btn btn-primary" href="{SITE_URL}/">{html.escape(s["page.404.home"])}</a></p></div>')
    full = dict(ctx)
    # Language links also need absolute targets.
    s2 = strings["en"]
    full["nav_links"] = nav_links(SITE_URL + "/", s2)
    full["lang_menu"] = lang_menu(lang, "", s2).replace('href="', f'href="{SITE_URL}/').replace(f'href="{SITE_URL}/./', f'href="{SITE_URL}/')
    full["lang_cloud"] = lang_cloud(lang, "").replace('href="', f'href="{SITE_URL}/').replace(f'href="{SITE_URL}/./', f'href="{SITE_URL}/')
    header = render((SRC / "partial-header.html").read_text(encoding="utf-8"), s2, full)
    footer = render((SRC / "partial-footer.html").read_text(encoding="utf-8"), s2, full)
    full.update(header=header, footer=footer, content=body)
    return render((SRC / "layout.html").read_text(encoding="utf-8"), s2, full)


# ---------------------------------------------------------------- sitemap & robots

def sitemap(lastmod: str) -> str:
    alts = "".join(
        f'<xhtml:link rel="alternate" hreflang="{l.code}" href="{SITE_URL}/{landing_dir(l)}"/>' for l in LANGS)
    alts += f'<xhtml:link rel="alternate" hreflang="x-default" href="{SITE_URL}/"/>'
    urls = [f"<url><loc>{SITE_URL}/{landing_dir(l)}</loc><lastmod>{lastmod}</lastmod>{alts}</url>" for l in LANGS]
    for d in (privacy_dir("en"), privacy_dir("zh-TW"), CHANGELOG_DIR):
        urls.append(f"<url><loc>{SITE_URL}/{d}</loc><lastmod>{lastmod}</lastmod></url>")
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">\n'
            + "\n".join(urls) + "\n</urlset>\n")


# ---------------------------------------------------------------- link check

class PageScan(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.ids: set[str] = set()
        self.refs: list[tuple[str, str]] = []     # (attribute, url)

    def handle_starttag(self, tag, attrs):
        d = dict(attrs)
        if d.get("id"):
            self.ids.add(d["id"])
        for attr in ("href", "src"):
            if d.get(attr):
                self.refs.append((attr, d[attr]))


def resolve(dist: Path, page: Path, url: str) -> Path | None:
    """Map a link found on `page` to a file in dist, or None for links we cannot check offline."""
    parts = urlsplit(url)
    if parts.scheme in ("http", "https"):
        if not url.startswith(SITE_URL):
            return None                                   # somebody else's site
        base = urlsplit(SITE_URL).path
        return dist / unquote(parts.path)[len(base):].lstrip("/")
    if parts.scheme or url.startswith("//"):
        return None                                       # mailto:, data:, protocol-relative
    return page if not parts.path else page.parent / unquote(parts.path)


def check_links(dist: Path) -> list[str]:
    pages: dict[Path, PageScan] = {}
    for p in dist.rglob("*.html"):
        scan = PageScan()
        scan.feed(p.read_text(encoding="utf-8"))
        pages[p] = scan
    errors = []
    for page, scan in pages.items():
        where = page.relative_to(dist)
        for attr, url in scan.refs:
            target = resolve(dist, page, url)
            if target is None:
                continue
            if target.is_dir():
                target = target / "index.html"
            if not target.exists():
                errors.append(f"{where}: {attr}={url!r} points at a missing file")
                continue
            fragment = urlsplit(url).fragment
            if fragment and target in pages and fragment not in pages[target].ids:
                errors.append(f"{where}: {url!r} points at a missing #{fragment}")
    return errors


# ---------------------------------------------------------------- main

def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def digest(path: Path) -> str:
    return hashlib.sha1(path.read_bytes()).hexdigest()[:8]


def build() -> Path:
    strings = load_i18n()
    changelog = json.loads((SRC / "changelog.json").read_text(encoding="utf-8"))
    version = {"css": digest(SRC / "style.css"), "js": digest(SRC / "site.js")}

    if DIST.exists():
        shutil.rmtree(DIST)
    DIST.mkdir(parents=True)
    shutil.copytree(ASSETS, DIST / "assets")
    shutil.copy(SRC / "style.css", DIST / "assets" / "style.css")
    shutil.copy(SRC / "site.js", DIST / "assets" / "site.js")
    write(DIST / ".nojekyll", "")

    for lang in LANGS:
        page_dir, text = build_landing(lang, strings, changelog, version)
        write(DIST / page_dir / "index.html", text)
    for page_dir, text in build_privacy(strings, version):
        write(DIST / page_dir / "index.html", text)
    page_dir, text = build_changelog(strings, changelog, version)
    write(DIST / page_dir / "index.html", text)
    write(DIST / "404.html", build_404(strings, version))
    write(DIST / "sitemap.xml", sitemap(datetime.date.today().isoformat()))
    write(DIST / "robots.txt", f"User-agent: *\nAllow: /\n\nSitemap: {SITE_URL}/sitemap.xml\n")

    errors = check_links(DIST)
    if errors:
        raise SystemExit("link check failed:\n  " + "\n  ".join(errors))
    n_html = len(list(DIST.rglob("*.html")))
    print(f"built {n_html} pages, {len(LANGS)} languages -> {DIST}")
    return DIST


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--serve", action="store_true", help="serve dist/ on http://localhost:8000/ after building")
    ap.add_argument("--port", type=int, default=8000)
    args = ap.parse_args()
    dist = build()
    if args.serve:
        import functools
        import http.server
        handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(dist))
        print(f"serving http://localhost:{args.port}/  (Ctrl+C to stop)")
        http.server.ThreadingHTTPServer(("127.0.0.1", args.port), handler).serve_forever()


if __name__ == "__main__":
    main()
