# RideFlux website

The source of <https://zero2005x.github.io/RideFlux/> — a static, 18-language landing page for the Play Store app, plus the privacy policy and release notes. No framework, no web fonts, no analytics, no cookies; the only third-party request is Google's official "Get it on Google Play" badge image.

## Build and preview

Python 3.9+ and nothing else (the standard library is enough):

```bash
python site/build.py            # build + validate into site/dist/
python site/build.py --serve    # …then serve http://localhost:8000/
```

`site/dist/` is git-ignored. The build **fails** (non-zero exit) when:

- a language file is missing a key, has an unknown key, or a blank value;
- a translation drops or adds inline markup (`**bold**`, `` `code` ``, `[text](url)`) compared with English;
- more than 20 % of a language's long strings are still identical to English (an untranslated copy);
- any internal link, image or `#anchor` points at something that does not exist.

`SITE_URL` (default `https://zero2005x.github.io/RideFlux`) sets the canonical / hreflang / sitemap URLs; `SITE_DIST` changes the output folder.

## Layout

```
site/
├── build.py                 # the generator + validators
├── src/
│   ├── layout.html          # <head>, header, footer shell shared by every page
│   ├── partial-header.html · partial-footer.html
│   ├── content-index.html   # the landing page; {{t:key}} = translated text, {{@block}} = HTML built by build.py
│   ├── style.css · site.js  # one stylesheet, one tiny script (language redirect + popovers)
│   ├── changelog.json       # release notes (English)
│   └── i18n/<code>.json     # 18 files; en.json is the source of truth
├── assets/                  # committed: shots/, og/, icons/ (generated, see below)
└── tools/optimize_images.py # regenerates assets/ from docs/play-store (needs Pillow)
```

Output URLs: English at `/`, every other language at `/<code>/` (`/zh-TW/`, `/ja/`, …), the privacy policy at `/privacy/` and `/zh-TW/privacy/`, release notes at `/changelog/`.

## Everyday tasks

**Add a release to the release notes** — add an object at the top of `releases` in `src/changelog.json` (`version`, `date`, `url`, `notes`). The landing page shows the first entry; `/changelog/` lists all of them.

**Change wording** — edit `src/i18n/en.json`, then the same key in the other 17 files. The build tells you which key is missing where.

**Add a language** — add `src/i18n/<code>.json` with every key of `en.json`, and one `Lang(...)` line in `LANGS` in `build.py` (endonym, `ltr`/`rtl`, script family, the Play Store `hl` value, and Google's badge file prefix). Google ships no badge for some languages; use `"en"` as the prefix then.

**Update the screenshots** — regenerate the Play Store screenshots (see `docs/play-store/README.md`), then:

```bash
pip install Pillow
python site/tools/optimize_images.py
```

Only English and Traditional Chinese screenshots exist; the other 16 languages show the English set.

**Privacy policy** — it is rendered from the repository's `PRIVACY.md` on every build (English and 繁體中文 sections), so edit that file, not the HTML.

## Deployment

`.github/workflows/pages.yml` builds on every pull request that touches `site/` or `PRIVACY.md`, and deploys to GitHub Pages on every push to `main` that does.

One-time setting: **Settings → Pages → Build and deployment → Source: GitHub Actions**. Until that is set the deploy job cannot publish. A custom domain can be added in the same screen; the workflow reads the real base URL from `actions/configure-pages`, so canonical links follow it automatically.

## Translations

Every translation except English was drafted with an AI assistant and has **not** been reviewed by a native speaker. Corrections are welcome as pull requests; the build keeps the files structurally consistent, but it cannot judge tone or terminology.
