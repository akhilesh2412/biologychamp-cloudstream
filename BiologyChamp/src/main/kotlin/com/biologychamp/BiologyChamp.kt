package com.biologychamp

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * BiologyChamp — CloudStream 3 provider for
 * https://new.biologychamp.co.in/study/study01.json
 *
 * Verified index schema (2026-11-17):
 *   { "sections": [ { "name": "...", "items": [ Course ] } ] }
 *   Course = { "id", "image", "isPopular", "name", "originalPrice",
 *              "pdfCount", "startDate", "videoCount", "api" }
 *   `api` is the per-course JSON URL. Two shapes were seen in the wild:
 *     1. https://new.biologychamp.co.in/study/batches/<id>.json
 *     2. https://ac.lrnt.ccwu.cc/data/batch/<id>.json
 *     3. https://eduteria.teamleakage.in/api/course/subjects?courseId=<id>
 *
 * Verified batch schema (eduteria-51272.json, "Titan Plus (For 73rd BPSC) - Batch 3"):
 *   { "id", "name", "image", "videoCount", "pdfCount", "isPopular",
 *     "originalPrice", "startDate",
 *     "lectures": [ { "title", "link", "subject", "chapter",
 *                     "orderIdx", "poster" } ] }
 *   `link` is either a direct CloudFront download URL or an HLS `.m3u8` URL.
 *   There is no per-lecture id and no duration field.
 *
 * The other batch shapes could not be fetched from every host (one returned
 * HTTP 403 to the fetcher), so [load] also has a generic recursive fallback:
 * it walks any JSON tree and collects every object that carries a video
 * link + a title, grouping by the nearest `subject` / `chapter` / `name`
 * it saw on the way down. A course whose `api` returns no playable
 * lectures still opens — it just shows 0 episodes and explains why in
 * its description, instead of crashing the app.
 *
 * Only content the operator has the right to distribute should be served
 * through this index. The plugin itself stores no video URLs; it reads
 * them at runtime from the operator's own JSON API.
 *
 * YouTube courses (added 2026-11-18): 54 programming playlists from
 * youtube_courses.json, a file kept in this repo and fetched at runtime
 * from raw.githubusercontent.com. Each playlist carries a `category`
 * (Web Development, Python, Programming Languages, DSA & Algorithms,
 * Mobile Development, Databases, DevOps & Tools, System Design,
 * AI & Machine Learning, Computer Science, Cyber Security) and shows
 * up under its own category tab on the home page — there is no single
 * "YouTube Courses" tab. A playlist's videos become episodes (title +
 * thumbnail), played through CloudStream's bundled YouTube extractor.
 * Nothing old was removed — the BiologyChamp batches work exactly
 * as before.
 *
 * v6 (2026-11-18): on play, a YouTube episode first tries a fresh
 * 1080p direct link from the y2mate converter (fetched at that moment,
 * because such links expire within minutes and are blocked when opened
 * directly), then falls back to the bundled YouTube extractor — so a
 * video still plays even if the converter is blocked.
 */
class BiologyChamp : MainAPI() {

    override var name = "BiologyChamp"
    override var mainUrl = "https://new.biologychamp.co.in"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Others)

    // Sections verified in study01.json on 2026-11-17. Search covers every
    // section (including any added later); the home page tabs are the
    // sections known at build time. "Futurekul Railway" and
    // "Futurekul All Exams" were removed 2026-11-18 at the operator's
    // request — only professional technical courses are listed.
    override val mainPage = mainPageOf(
        "Alpha" to "Alpha",
        "Alpha Plus" to "Alpha Plus",
        "Delta" to "Delta",
        "Sigma" to "Sigma",
        "Prime" to "Prime",
        "Others" to "Others",
        "BPSC 73rd" to "BPSC 73rd",
        "BPSC 72nd" to "BPSC 72nd",
        "BPSC TRE" to "BPSC TRE",
        "Bihar Daroga & BSSC" to "Bihar Daroga & BSSC",
        "AEDO" to "AEDO",
        "Books & Notes" to "Books & Notes",
        "UPSC & Other PCS" to "UPSC & Other PCS",
        "Free Courses" to "Free Courses",
        // YouTube playlist categories (from youtube_courses.json).
        // There is deliberately no single "YouTube Courses" tab.
        "Web Development" to "Web Development",
        "Python" to "Python",
        "Programming Languages" to "Programming Languages",
        "DSA & Algorithms" to "DSA & Algorithms",
        "Mobile Development" to "Mobile Development",
        "Databases" to "Databases",
        "DevOps & Tools" to "DevOps & Tools",
        "System Design" to "System Design",
        "AI & Machine Learning" to "AI & Machine Learning",
        "Computer Science" to "Computer Science",
        "Cyber Security" to "Cyber Security",
    )

    companion object {
        private const val INDEX_URL = "https://new.biologychamp.co.in/study/study01.json"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val JSON_HEADERS = mapOf(
            "User-Agent" to UA,
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "https://new.biologychamp.co.in/",
        )

        // Index is one small JSON file; cache it for 10 minutes so the home
        // page, search and every tab do not re-download it.
        private var cachedIndex: JSONObject? = null
        private var cachedIndexAt = 0L
        private const val INDEX_TTL_MS = 10 * 60 * 1000L

        // YouTube courses live in youtube_courses.json in this repo, so
        // the list can be refreshed without rebuilding the plugin.
        // Every course carries a `category`; each category is its own
        // home-page tab (see mainPage). A course whose category matches
        // no tab is not listed anywhere, so new categories need a tab.
        private const val YOUTUBE_COURSES_URL =
            "https://raw.githubusercontent.com/Akhilesh2412/programming-courses/main/youtube_courses.json"
        private val YOUTUBE_CATEGORIES = setOf(
            "Web Development", "Python", "Programming Languages",
            "DSA & Algorithms", "Mobile Development", "Databases",
            "DevOps & Tools", "System Design", "AI & Machine Learning",
            "Computer Science", "Cyber Security",
        )

        // Sections the operator does not want listed (removed 2026-11-18).
        private val EXCLUDED_SECTIONS = setOf("Futurekul Railway", "Futurekul All Exams")

        private var cachedYoutube: JSONObject? = null
        private var cachedYoutubeAt = 0L
    }

    // ------------------------------------------------------------------
    // Index
    // ------------------------------------------------------------------

    private data class Course(
        val id: String,
        val name: String,
        val image: String?,
        val api: String,
        val section: String,
        val videoCount: Int,
        val pdfCount: Int,
        val price: Int,
    )

    private data class Lecture(
        val title: String,
        val link: String,
        val subject: String?,
        val chapter: String?,
        val poster: String?,
        val order: Int,
    )

    private data class YoutubeVideo(
        val id: String,
        val title: String,
        val thumbnail: String?,
    )

    private data class YoutubeCourse(
        val id: String,
        val title: String,
        val channel: String,
        val category: String,
        val description: String,
        val thumbnail: String?,
        val videos: List<YoutubeVideo>,
    )

    private suspend fun fetchIndex(): JSONObject {
        val now = System.currentTimeMillis()
        cachedIndex?.let { if (now - cachedIndexAt < INDEX_TTL_MS) return it }
        val text = app.get(INDEX_URL, headers = JSON_HEADERS).text
        val json = JSONObject(text)
        cachedIndex = json
        cachedIndexAt = now
        return json
    }

    private fun parseIndex(json: JSONObject): List<Course> {
        val out = ArrayList<Course>()
        val sections = json.optJSONArray("sections") ?: return out
        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            val sectionName = section.optString("name").trim()
            val items = section.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue
                val api = item.optString("api").trim()
                val courseName = item.optString("name").trim()
                if (api.isBlank() || courseName.isBlank()) continue
                out += Course(
                    id = item.optString("id"),
                    name = courseName,
                    image = item.optString("image").takeIf { it.isNotBlank() },
                    api = api,
                    section = sectionName,
                    videoCount = item.optInt("videoCount"),
                    pdfCount = item.optInt("pdfCount"),
                    price = item.optInt("originalPrice"),
                )
            }
        }
        return out
    }

    private suspend fun fetchYoutubeCourses(): JSONObject {
        val now = System.currentTimeMillis()
        cachedYoutube?.let { if (now - cachedYoutubeAt < INDEX_TTL_MS) return it }
        val text = app.get(YOUTUBE_COURSES_URL, headers = JSON_HEADERS).text
        val json = JSONObject(text)
        cachedYoutube = json
        cachedYoutubeAt = now
        return json
    }

    private fun parseYoutubeCourses(json: JSONObject): List<YoutubeCourse> {
        val out = ArrayList<YoutubeCourse>()
        val courses = json.optJSONArray("courses") ?: return out
        for (i in 0 until courses.length()) {
            val obj = courses.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            val title = obj.optString("title").trim()
            if (id.isBlank() || title.isBlank()) continue
            val videos = ArrayList<YoutubeVideo>()
            val arr = obj.optJSONArray("videos")
            if (arr != null) {
                for (j in 0 until arr.length()) {
                    val v = arr.optJSONObject(j) ?: continue
                    val vid = v.optString("id").trim()
                    val vtitle = v.optString("title").trim()
                    if (vid.isBlank() || vtitle.isBlank()) continue
                    videos += YoutubeVideo(
                        id = vid,
                        title = vtitle,
                        thumbnail = v.optString("thumbnail").takeIf { it.isNotBlank() },
                    )
                }
            }
            out += YoutubeCourse(
                id = id,
                title = title,
                channel = obj.optString("channel").trim(),
                category = obj.optString("category").trim().ifBlank { "Other" },
                description = obj.optString("description").trim(),
                thumbnail = obj.optString("thumbnail").takeIf { it.isNotBlank() },
                videos = videos,
            )
        }
        return out
    }

    private fun YoutubeCourse.toSearchResponse(): SearchResponse =
        newTvSeriesSearchResponse(
            title,
            "https://www.youtube.com/playlist?list=$id",
            TvType.Others,
        ) {
            posterUrl = thumbnail
        }

    private fun Course.toSearchResponse(): SearchResponse =
        newTvSeriesSearchResponse(name, api, TvType.Others) {
            posterUrl = image
        }

    // ------------------------------------------------------------------
    // MainAPI
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // The whole catalogue is one JSON file — there is no pagination.
        if (page > 1) return newHomePageResponse(request, emptyList())
        if (YOUTUBE_CATEGORIES.any { it.equals(request.data, ignoreCase = true) }) {
            return try {
                val courses = parseYoutubeCourses(fetchYoutubeCourses())
                    .filter { it.category.equals(request.data, ignoreCase = true) }
                newHomePageResponse(request, courses.map { it.toSearchResponse() })
            } catch (_: Throwable) {
                newHomePageResponse(request, emptyList())
            }
        }
        return try {
            val courses = parseIndex(fetchIndex())
                .filter { it.section.equals(request.data, ignoreCase = true) }
            newHomePageResponse(request, courses.map { it.toSearchResponse() })
        } catch (_: Throwable) {
            newHomePageResponse(request, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val out = ArrayList<SearchResponse>()
        try {
            out += parseIndex(fetchIndex())
                .filter { it.section !in EXCLUDED_SECTIONS }
                .filter {
                    it.name.contains(q, ignoreCase = true) ||
                        it.section.contains(q, ignoreCase = true)
                }
                .map { it.toSearchResponse() }
        } catch (_: Throwable) {
        }
        try {
            out += parseYoutubeCourses(fetchYoutubeCourses())
                .filter {
                    it.title.contains(q, ignoreCase = true) ||
                        it.channel.contains(q, ignoreCase = true)
                }
                .map { it.toSearchResponse() }
        } catch (_: Throwable) {
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        // YouTube playlist course: the url is the playlist page URL.
        if (url.contains("youtube.com/playlist", ignoreCase = true)) {
            val playlistId = url.substringAfter("list=", "").substringBefore("&").trim()
            return loadYoutubeCourse(url, playlistId)
        }
        val text = app.get(url, headers = JSON_HEADERS).text
        val json = JSONObject(text)

        val courseName = json.optString("name").trim().ifBlank { "BiologyChamp Course" }
        // Lectures usually ship without their own poster, so keep the
        // course image handy — it becomes every episode's thumbnail
        // (see lecturesToEpisodes). If the batch JSON has no image
        // either, fall back to the same course's image in the index.
        val poster = json.optString("image").takeIf { it.isNotBlank() }
            ?: try {
                parseIndex(fetchIndex()).firstOrNull { it.api == url }?.image
            } catch (_: Throwable) {
                null
            }
        val videoCount = json.optInt("videoCount")
        val pdfCount = json.optInt("pdfCount")
        val price = json.optInt("originalPrice")

        val lectures = parseLectures(json)
        val episodes = lecturesToEpisodes(url, lectures, poster)

        val subjectList = lectures.mapNotNull { it.subject?.trim() }
            .filter { it.isNotBlank() }.distinct().take(10)
        val plot = buildString {
            if (videoCount > 0) append("🎥 $videoCount videos")
            if (pdfCount > 0) {
                if (isNotEmpty()) append("  •  ")
                append("📄 $pdfCount PDFs")
            }
            if (lectures.isNotEmpty()) {
                if (isNotEmpty()) append("  •  ")
                append("${lectures.size} lectures loaded")
            }
            if (price > 0) append("\n💰 ₹$price")
            if (subjectList.isNotEmpty()) append("\nSubjects: ${subjectList.joinToString(", ")}")
            if (episodes.isEmpty()) {
                append(
                    "\nNo playable lectures were found in this course JSON. " +
                        "Some courses only list test series / PDFs, or their " +
                        "lecture API uses a different format."
                )
            }
        }.trim()

        return newTvSeriesLoadResponse(courseName, url, TvType.Others, episodes) {
            posterUrl = poster
            this.plot = plot.ifBlank { null }
            tags = subjectList.ifEmpty { null }
        }
    }

    /** A YouTube playlist from youtube_courses.json: videos become episodes. */
    private suspend fun loadYoutubeCourse(url: String, playlistId: String): LoadResponse {
        val course = try {
            parseYoutubeCourses(fetchYoutubeCourses()).firstOrNull { it.id == playlistId }
        } catch (_: Throwable) {
            null
        }
        if (course == null) {
            return newTvSeriesLoadResponse("YouTube Course", url, TvType.Others, emptyList()) {
                plot = "This YouTube playlist could not be loaded. " +
                    "Check that youtube_courses.json is up to date."
            }
        }
        val episodes = course.videos.mapIndexed { idx, video ->
            newEpisode("https://www.youtube.com/watch?v=${video.id}") {
                name = video.title
                season = 1
                episode = idx + 1
                posterUrl = video.thumbnail
            }
        }
        val plot = buildString {
            append("🎥 ${course.videos.size} videos")
            if (course.channel.isNotBlank()) append("  •  📺 ${course.channel}")
            if (course.description.isNotBlank()) append("\n${course.description}")
        }.trim()
        return newTvSeriesLoadResponse(course.title, url, TvType.Others, episodes) {
            posterUrl = course.thumbnail
            this.plot = plot.ifBlank { null }
        }
    }

    // ------------------------------------------------------------------
    // Lectures
    // ------------------------------------------------------------------

    /** Fast path for the verified schema, generic walk for anything else. */
    private fun parseLectures(json: JSONObject): List<Lecture> {
        val direct = json.optJSONArray("lectures")
        if (direct != null && direct.length() > 0) {
            val out = ArrayList<Lecture>()
            for (i in 0 until direct.length()) {
                val obj = direct.optJSONObject(i) ?: continue
                val link = obj.optString("link").trim()
                val title = obj.optString("title").trim()
                    .ifBlank { obj.optString("name").trim() }
                if (link.isBlank() || title.isBlank()) continue
                if (link.endsWith(".pdf", ignoreCase = true)) continue
                out += Lecture(
                    title = title,
                    link = link,
                    subject = obj.optString("subject").takeIf { it.isNotBlank() },
                    chapter = obj.optString("chapter").takeIf { it.isNotBlank() },
                    poster = obj.optString("poster").takeIf { it.isNotBlank() },
                    order = obj.optInt("orderIdx", i),
                )
            }
            if (out.isNotEmpty()) return out.sortedBy { it.order }
        }
        // Fallback: walk the whole tree (subjects / chapters / topics …).
        val found = ArrayList<Lecture>()
        collectLectures(json, subject = null, chapter = null, out = found)
        return found.distinctBy { it.link }.sortedBy { it.order }
    }

    private fun collectLectures(node: Any?, subject: String?, chapter: String?, out: MutableList<Lecture>) {
        when (node) {
            is JSONObject -> {
                val nodeSubject = node.optString("subject").takeIf { it.isNotBlank() } ?: subject
                val nodeChapter = node.optString("chapter").takeIf { it.isNotBlank() } ?: chapter
                // A container (subject / chapter / topic) lends its name to
                // the lectures nested under it.
                val containerName = node.optString("name").takeIf { it.isNotBlank() }
                val hasChildren = node.has("lectures") || node.has("videos") ||
                    node.has("chapters") || node.has("subjects") ||
                    node.has("topics") || node.has("items") || node.has("data")
                val childSubject = if (hasChildren && containerName != null) containerName else nodeSubject

                val link = listOf("link", "videoUrl", "video_url", "streamUrl", "playbackUrl", "fileUrl", "url", "source")
                    .firstNotNullOfOrNull { key -> node.optString(key).trim().takeIf { it.isNotBlank() } }
                val title = node.optString("title").trim()
                    .ifBlank { node.optString("name").trim() }
                if (link != null && title.isNotBlank() && isVideoLink(link)) {
                    out += Lecture(
                        title = title,
                        link = link,
                        subject = nodeSubject,
                        chapter = nodeChapter,
                        poster = node.optString("poster").takeIf { it.isNotBlank() }
                            ?: node.optString("thumbnail").takeIf { it.isNotBlank() }
                            ?: node.optString("image").takeIf { it.isNotBlank() },
                        order = node.optInt("orderIdx", out.size),
                    )
                    return
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    collectLectures(node.opt(keys.next()), childSubject, nodeChapter, out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collectLectures(node.opt(i), subject, chapter, out)
            }
        }
    }

    private fun isVideoLink(link: String): Boolean {
        val lower = link.lowercase()
        if (!lower.startsWith("http")) return false
        if (lower.endsWith(".pdf")) return false
        return lower.contains(".m3u8") || lower.contains(".mp4") ||
            lower.contains(".mkv") || lower.contains(".webm") ||
            lower.contains("cloudfront.net") || lower.contains("/videos/") ||
            lower.contains("download")
    }

    private fun lecturesToEpisodes(courseUrl: String, lectures: List<Lecture>, coursePoster: String? = null): List<Episode> {
        val subjects = lectures.mapNotNull { it.subject?.trim() }
            .filter { it.isNotBlank() }.distinct()
        val perSubjectCounter = HashMap<String, Int>()
        return lectures.map { lecture ->
            val subjectKey = lecture.subject?.trim().orEmpty()
            val season = if (subjectKey.isBlank()) 1 else subjects.indexOf(subjectKey) + 1
            val epNo = (perSubjectCounter[subjectKey] ?: 0) + 1
            perSubjectCounter[subjectKey] = epNo
            val displayName = when {
                lecture.chapter.isNullOrBlank() -> lecture.title
                lecture.chapter.equals(lecture.subject, ignoreCase = true) -> lecture.title
                else -> "${lecture.chapter}: ${lecture.title}"
            }
            newEpisode(lecture.link) {
                name = displayName
                this.season = season
                episode = epNo
                // Most lectures carry no poster of their own, which is
                // why episode thumbnails showed blank. Fall back to the
                // course image so every episode has a thumbnail.
                posterUrl = lecture.poster?.takeIf { it.isNotBlank() } ?: coursePoster
            }
        }
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // y2mate 1080p direct link (added 2026-11-18, v6)
    // ------------------------------------------------------------------
    // A download site's 1080p link cannot be stored: it is a short-lived
    // tunnel URL (dl12.yt-dl.click) that expires within minutes and is
    // blocked when opened directly, so it is fetched fresh here, at the
    // moment the user presses play, and only for YouTube videos. The
    // converter needs a per-video key first, then returns the direct
    // URL for the 1080p MP4. If the converter is blocked or slow, this
    // returns null and playback falls back to the bundled YouTube
    // extractor below — a video never ends up with zero sources
    // because of this step.
    private suspend fun fetchY2mate1080p(videoId: String): String? {
        return try {
            val keyText = app.get(
                "https://cnv.cx/v2/sanity/key?id=$videoId",
                headers = mapOf(
                    "User-Agent" to UA,
                    "Content-Type" to "application/json",
                ),
            ).text
            val key = JSONObject(keyText).optString("key").trim()
            if (key.isBlank()) return null
            val body = app.post(
                "https://cnv.cx/v2/converter",
                headers = mapOf(
                    "User-Agent" to UA,
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Accept" to "*/*",
                    "key" to key,
                ),
                data = mapOf(
                    "link" to "https://youtu.be/$videoId",
                    "format" to "mp4",
                    "audioBitrate" to "128",
                    "videoQuality" to "1080",
                    "filenameStyle" to "pretty",
                    "vCodec" to "h264",
                ),
            ).text
            JSONObject(body).optString("url").trim().takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun youtubeVideoId(url: String): String? {
        // Episodes are stored as watch?v=ID; also accept youtu.be/ID.
        val watch = Regex("[?&]v=([A-Za-z0-9_-]{6,})").find(url)
            ?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        if (watch != null) return watch
        return Regex("youtu\\.be/([A-Za-z0-9_-]{6,})").find(url)
            ?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val videoUrl = data.trim()
        if (videoUrl.isBlank()) return false
        // A YouTube watch page is not a playable file, so there is no
        // direct-link fallback for it — the bundled YouTube extractor
        // (via loadExtractor below) is a source, and for YouTube videos
        // a fresh 1080p link from the y2mate converter is tried first
        // (see fetchY2mate1080p). Download links are never stored: they
        // expire within minutes, so they are fetched at play time.
        val isYoutube = videoUrl.contains("youtube.com", ignoreCase = true) ||
            videoUrl.contains("youtu.be", ignoreCase = true)
        return try {
            val lower = videoUrl.lowercase()
            val type = when {
                lower.contains(".m3u8") -> ExtractorLinkType.M3U8
                lower.contains(".mpd") -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
            var added = false
            // YouTube: try a fresh 1080p direct link first. Best effort
            // only — any failure falls through to the extractor below.
            if (isYoutube) {
                val videoId = youtubeVideoId(videoUrl)
                if (!videoId.isNullOrBlank()) {
                    val direct1080p = fetchY2mate1080p(videoId)
                    if (!direct1080p.isNullOrBlank()) {
                        callback(
                            newExtractorLink(
                                name, "Y2Mate 1080p", direct1080p,
                                ExtractorLinkType.VIDEO,
                            ) {
                                referer = "https://www.y2mate.in.net/"
                                quality = Qualities.P1080.value
                            }
                        )
                        added = true
                    }
                }
            }
            // Direct HLS / MP4 links from the course JSON play as-is.
            // loadExtractor first gives bundled extractors a chance on
            // any wrapped / redirecting host; the direct link is the
            // fallback so a lecture never ends up with zero sources.
            try {
                if (loadExtractor(videoUrl, videoUrl, subtitleCallback, callback)) {
                    added = true
                }
            } catch (_: Throwable) {
                // Extractor failed; `added` keeps whatever the y2mate
                // step (or the fallback below) produced.
            }
            if (!added && !isYoutube) {
                callback(
                    newExtractorLink(name, "BiologyChamp", videoUrl, type) {
                        referer = mainUrl
                        quality = Qualities.Unknown.value
                    }
                )
                added = true
            }
            added
        } catch (_: Throwable) {
            false
        }
    }
}
