// BiologyChamp — single-provider CloudStream plugin.
// Version must stay a bare integer: the CloudStream gradle plugin reads
// `project.version` with .toIntOrNull() and falls back to -1 otherwise.
version = 4

dependencies {
    // jsoup 1.18.3 is compiled against jspecify annotations (provided
    // scope), so they must be present on the compile classpath.
    implementation("org.jspecify:jspecify:1.0.0")
}

cloudstream {
    description = "BiologyChamp study batches from https://new.biologychamp.co.in/study/study01.json — Alpha, Delta, Sigma, Prime, BPSC, TRE, Railway and more. Browse by section, search any batch, open a batch to see its lectures grouped by subject, and play direct HLS / MP4 lecture links. Also includes 54 YouTube playlists organised into category tabs (Web Development, Python, Programming Languages, DSA & Algorithms, Mobile Development, Databases, DevOps & Tools, System Design, AI & Machine Learning, Computer Science, Cyber Security) whose videos play through CloudStream's YouTube extractor."
    authors = listOf("Akhilesh2412")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("Others")
    language = "en"

    // No resources are required (no custom fragment/settings UI).
    requiresResources = false

    iconUrl = "https://lwfiles.mycourse.app/62a6cd5e1e9e2fbf212d608d-public/05e2153f1e10cdbe245ce6ae493910e9.jpeg"
}
