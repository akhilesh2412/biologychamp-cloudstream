# BiologyChamp — CloudStream 3 Plugin

A CloudStream 3 (reCloudStream) provider that reads the BiologyChamp study
index and plays its lecture links — no scraping, no hard-coded video URLs.

**Source:** `https://new.biologychamp.co.in/study/study01.json`

## What it does

- **Home page** — one tab per section from the index: Alpha, Alpha Plus,
  Delta, Sigma, Prime, Others, BPSC 73rd, BPSC 72nd, BPSC TRE,
  Bihar Daroga & BSSC, AEDO, Books & Notes, UPSC & Other PCS,
  Free Courses — plus YouTube playlist categories (see below):
  Web Development, Python, Programming Languages, DSA & Algorithms,
  Mobile Development, Databases, DevOps & Tools, System Design,
  AI & Machine Learning, Computer Science, Cyber Security.
- **YouTube playlists by category** — 54 full YouTube playlists as
  courses (2,624 videos), grouped into 11 category tabs: Web Development
  (17), Python (8), Programming Languages (7), Mobile Development (5),
  DevOps & Tools (5), DSA & Algorithms (4), System Design (2),
  Computer Science (2), Databases (2), Cyber Security (1),
  AI & Machine Learning (1). There is no single "YouTube Courses" tab.
  Each course shows its playlist thumbnail; every video is an episode
  with its own thumbnail and plays through CloudStream's bundled
  YouTube extractor. The list lives in `youtube_courses.json` in this
  repo (each course carries a `category`), so adding a playlist is a
  data change, not a rebuild — but a new category needs a matching tab
  in the provider code.
- **Search** — searches batch names (and section names) across the whole
  index, including sections added after this plugin was built.
- **Batch page** — opens the course's own `api` JSON and lists its
  lectures as episodes, grouped by subject (season = subject), in
  `orderIdx` order. Each episode shows the lecture's poster when the
  lecture has one, otherwise the course image — lectures usually ship
  without their own poster, so without this fallback the episode
  thumbnails stayed blank. The description shows
  video / PDF counts and the price from the index.
- **Playback** — `loadLinks` hands direct HLS (`.m3u8`) and MP4 / direct
  download links to the player via CloudStream's bundled extractors,
  with the direct link as fallback so a lecture never ends up with
  zero sources.

## Verified data format

Index (`study01.json`):

```json
{
  "sections": [
    {
      "name": "Alpha",
      "items": [
        {
          "id": "alpha-batch-2",
          "image": "https://…/05e2153f….jpeg",
          "isPopular": false,
          "name": "Alpha Batch 2.0",
          "originalPrice": 0,
          "pdfCount": 75,
          "startDate": "N/A",
          "videoCount": 518,
          "api": "https://ac.lrnt.ccwu.cc/data/batch/alpha-batch-2.json"
        }
      ]
    }
  ]
}
```

Batch (`/study/batches/<id>.json`, verified on `eduteria-51272.json`):

```json
{
  "id": "eduteria-51272",
  "name": "Titan Plus (For 73rd BPSC) - Batch 3",
  "image": "https://…/03-Titon%2B.png",
  "videoCount": 5,
  "pdfCount": 0,
  "lectures": [
    {
      "title": "Ancient History Syllabus Discussion",
      "link": "https://…/file_library/videos/download/…",
      "subject": "Ancient History",
      "chapter": "Pooja Mam",
      "orderIdx": 0,
      "poster": "https://…/Ancient%20History%2073rd%20BPSC%20%2815%29.png"
    }
  ]
}
```

Some `link` values are HLS `.m3u8` URLs, others are direct download
URLs. There is no per-lecture id and no duration field.

Some courses point at a different API instead of a batch JSON
(`…/api/course/subjects?courseId=<id>`), and at least one batch host
returned HTTP 403 to the fetcher. `load()` therefore also has a generic
fallback: it walks any JSON tree and collects every object carrying a
video link + a title, grouped by the nearest `subject` / `chapter`.
A course with no playable lectures still opens and says so in its
description instead of crashing.

The index is cached in memory for 10 minutes so the home page, tabs
and search don't re-download it on every tap.

## Install (in the CloudStream app)

1. Push this repo to GitHub (or fork it). The workflow below builds it.
2. In CloudStream: **Settings → Extensions → Add Repository**, paste:
   `https://raw.githubusercontent.com/Akhilesh2412/programming-courses/builds/repo.json`
3. Install **BiologyChamp** from the repository list.

> The `builds` branch URL is generated automatically by the workflow,
> so forks and renames just work.

## Build

This project follows the standard CloudStream layout, so it builds
with the included GitHub workflow.

1. Push to `main` (or `master`). The `.github/workflows/build.yml`
   workflow runs `./gradlew make makePluginsJson` and publishes to
   the `builds` branch:
   - `*.cs3` — one file per module
   - `plugins.json` — plugin list
   - `repo.json` — repository manifest the app actually fetches
2. Repository URL for the app ("Add Repository"):
   `https://raw.githubusercontent.com/Akhilesh2412/programming-courses/builds/repo.json`

The workflow first builds the CloudStream gradle plugin from source
(`recloudstream/gradle` @ `32895ae`) and publishes it to `mavenLocal`,
because the plugin JAR is not currently available on JitPack /
GitHub Packages.

Local build (needs JDK 17 + Android SDK 35):

```bash
./gradlew make makePluginsJson
# .cs3 file: BiologyChamp/build/BiologyChamp.cs3
# plugin list: build/plugins.json
```

## Project layout

```
.
├── build.gradle.kts                 # root Gradle config (AGP 8.7.3, Kotlin 2.4.0)
├── settings.gradle.kts              # auto-includes every module folder
├── repo.json                        # repository manifest (URL rewritten by CI)
├── youtube_courses.json             # YouTube playlists: 54 courses in 11 categories, 2624 videos
├── BiologyChamp/
│   ├── build.gradle.kts             # version = 4 (must stay a bare integer)
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── kotlin/com/biologychamp/
│           ├── BiologyChamp.kt      # MainAPI: home, search, load, loadLinks
│           └── BiologyChampPlugin.kt
└── .github/workflows/build.yml      # builds .cs3 + plugins.json → builds branch
```

Bump `version` in `BiologyChamp/build.gradle.kts` (integer only) on
meaningful changes so the app offers an update.

## Notes / limits

- PDFs and test-series-only courses have no playable video — they
  open with 0 episodes and an explanation in the description.
- If a batch host blocks the request (403 / Cloudflare), that batch
  can't load until the host allows it — the plugin can't bypass that.
- Only use this plugin with content you have the right to access and
  distribute. The plugin stores no video URLs; it reads them at
  runtime from the operator's own JSON API.
