package dev.sandipchitale.jbdependencygitrepo;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MavenSourceUrlResolver {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Given a Gradle cache JAR class path like:
     * ~/.gradle/caches/modules-2/files-2.1/com.fasterxml/classmate/1.7.0/<hash>/classmate-1.7.0.jar!/com/fasterxml/classmate/AnnotationInclusion.class
     * returns a best-effort Git web URL to the corresponding .java file at the correct tag/branch.
     */
    public static String resolveSourceUrl(String gradleJarClassPath) {
        Objects.requireNonNull(gradleJarClassPath, "path");

        ParsedPath p = parseGradleJarClassPath(gradleJarClassPath);
        if (p == null) {
            p = parseMavenJarClassPath(gradleJarClassPath);
        }
        if (p == null) {
            throw new IllegalArgumentException("Unrecognized Gradle/Maven cache path format: " + gradleJarClassPath);
        }

        // Fetch POM and parse SCM
        PomScm scm = fetchPomScm(p.groupId, p.artifactId, p.version);
        if (scm == null || (isBlank(scm.url) && isBlank(scm.connection))) {
            throw new IllegalStateException("Cannot obtain SCM info from POM for " + p.gav());
        }

        String repoWeb = normalizeScmToWebUrl(notBlankOr(scm.url, scm.connection));
        if (repoWeb == null) {
            throw new IllegalStateException("Unsupported SCM URL for " + p.gav() + ": " + scm);
        }

        // Determine whether the entry is a compiled class or a generic file/folder
        String entryRelPath = p.classEntry.startsWith("/") ? p.classEntry.substring(1) : p.classEntry;
        boolean isClass = entryRelPath.endsWith(".class");
        String targetRelPath = isClass ? toJavaSourceRelPath(entryRelPath) : entryRelPath; // do not convert if not .class

        // Try host-specific resolution using APIs (GitHub)
        GitHubRepo gh = parseGitHubRepo(repoWeb);
        if (gh != null) {
            // Prefer monorepo-friendly ref names and validate existence via GitHub API
            String ref = githubPickRef(repoWeb, p.artifactId, p.version, scm.tag);
            if (isBlank(ref)) {
                // fallback to default branch if available
                ref = defaultBranchOrNull(gh);
            }
            if (!isBlank(ref)) {
                // Find actual path of the entry within the repo for this ref (handles modules/non-standard roots)
                GitHubPathResult pathResult = githubFindPath(gh, ref, targetRelPath, p.artifactId, isClass);
                if (pathResult != null && !isBlank(pathResult.path)) {
                    String kind = ("dir".equals(pathResult.type)) ? "tree" : "blob";
                    return String.format("https://github.com/%s/%s/%s/%s/%s", gh.owner, gh.repo, kind, ref, pathResult.path);
                }
            }
            // If GitHub API did not resolve, continue to heuristic fallback below
        }

        // Heuristic fallback: try multiple refs and roots combinations
        List<String> refCandidates = new ArrayList<>();
        if (!isBlank(scm.tag)) refCandidates.add(scm.tag);
        // Prefer artifactId-version for multi-repo projects like Jackson modules
        refCandidates.add(p.artifactId + "-" + p.version);
        refCandidates.add("v" + p.version);
        refCandidates.add(p.version);
        // Deduplicate while preserving order
        List<String> refs = new ArrayList<>();
        for (String r : refCandidates) { if (!isBlank(r) && !refs.contains(r)) refs.add(r); }

        // Guess a possible module directory from the path (last segment of package/module)
        String moduleGuess = null;
        int lastSlash = targetRelPath.lastIndexOf('/');
        if (lastSlash > 0) {
            String pkgPath = targetRelPath.substring(0, lastSlash);
            int lastPkgSlash = pkgPath.lastIndexOf('/');
            if (lastPkgSlash >= 0) moduleGuess = pkgPath.substring(lastPkgSlash + 1);
        }

        String[] roots;
        if (isClass) {
            roots = moduleGuess == null
                    ? new String[] { p.artifactId + "/src/main/java", "src/main/java" }
                    : new String[] { p.artifactId + "/src/main/java", moduleGuess + "/src/main/java", "src/main/java" };
        } else {
            roots = moduleGuess == null
                    ? new String[] { p.artifactId + "/src/main/resources", "src/main/resources", p.artifactId, "" }
                    : new String[] { p.artifactId + "/src/main/resources", moduleGuess + "/src/main/resources", "src/main/resources", p.artifactId, "" };
        }

        for (String r : refs) {
            for (String root : roots) {
                String base = isBlank(root) ? targetRelPath : (root + "/" + targetRelPath);
                // Try blob first (file), then tree (dir)
                String candidateBlob = repoWeb + "/blob/" + r + "/" + base;
                if (httpExists(candidateBlob)) return candidateBlob;
                String candidateTree = repoWeb + "/tree/" + r + "/" + base;
                if (httpExists(candidateTree)) return candidateTree;
            }
        }
        // Fall back to last resort: first ref + a reasonable root
        String lastResortRef = refs.isEmpty() ? ("v" + p.version) : refs.get(0);
        String defaultRoot = isClass ? "src/main/java" : "src/main/resources";
        return repoWeb + "/blob/" + lastResortRef + "/" + defaultRoot + "/" + targetRelPath;
    }

    // --- Helpers ---

    private static String toJavaSourceRelPath(String classEntry) {
        // strip leading "/" if any
        String e = classEntry.startsWith("/") ? classEntry.substring(1) : classEntry;
        if (!e.endsWith(".class")) {
            throw new IllegalArgumentException("Entry does not end with .class: " + classEntry);
        }
        // handle inner classes: take the part before first '$'
        int dollar = e.indexOf('$');
        if (dollar >= 0) {
            e = e.substring(0, dollar) + ".class"; // keep .class, then convert
        }
        String withoutExt = e.substring(0, e.length() - ".class".length());
        return withoutExt + ".java";
    }

    private static String normalizeScmToWebUrl(String scmUrlOrConn) {
        String s = scmUrlOrConn.trim();
        // Examples we might see:
        // - https://github.com/FasterXML/java-classmate
        // - scm:git:https://github.com/FasterXML/java-classmate.git
        // - scm:git:git@github.com:FasterXML/java-classmate.git
        // - git@github.com:FasterXML/java-classmate.git
        // - https://gitlab.com/group/repo
        // - https://github.com/apache/commons-lang

        // Strip optional scm:git: prefix
        if (s.startsWith("scm:")) {
            // keep last segment after last ':'
            int idx = s.lastIndexOf(':');
            if (idx > 0) s = s.substring(idx + 1);
        }

        // SSH to HTTPS for GitHub/GitLab/Bitbucket
        if (s.startsWith("git@github.com:")) {
            s = "https://github.com/" + s.substring("git@github.com:".length());
        } else if (s.startsWith("git@gitlab.com:")) {
            s = "https://gitlab.com/" + s.substring("git@gitlab.com:".length());
        } else if (s.startsWith("git@bitbucket.org:")) {
            s = "https://bitbucket.org/" + s.substring("git@bitbucket.org:".length());
        }

        // Drop trailing .git
        if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);

        // Basic sanity check: must look like a web URL we can append /blob/{ref}/...
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return s;
        }
        return null;
    }

    private static PomScm fetchPomScm(String groupId, String artifactId, String version) {
        // Follow parent POMs up the chain until we find a non-blank <scm>.
        // Limit depth to avoid infinite loops.
        int depth = 0;
        String g = groupId, a = artifactId, v = version;
        while (depth < 6 && !isBlank(g) && !isBlank(a) && !isBlank(v)) {
            depth++;
            Document doc = fetchPomDocument(g, a, v);
            if (doc == null) return null;

            String scmUrl = textContent(doc, "scm", "url");
            String scmConn = firstNonBlank(textContent(doc, "scm", "connection"), textContent(doc, "scm", "developerConnection"));
            String scmTag = textContent(doc, "scm", "tag");
            if (!isBlank(scmUrl) || !isBlank(scmConn) || !isBlank(scmTag)) {
                return new PomScm(scmUrl, scmConn, scmTag);
            }

            // Try parent coordinates
            String pg = textContent(doc, "parent", "groupId");
            String pa = textContent(doc, "parent", "artifactId");
            String pv = textContent(doc, "parent", "version");
            if (isBlank(pg) || isBlank(pa) || isBlank(pv)) {
                break; // no parent to follow
            }
            g = pg; a = pa; v = pv;
        }
        return null;
    }

    private static Document fetchPomDocument(String groupId, String artifactId, String version) {
        try {
            String groupPath = groupId.replace('.', '/');
            String pomUrl = "https://repo1.maven.org/maven2/" + groupPath + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".pom";
            HttpRequest req = HttpRequest.newBuilder(URI.create(pomUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "MavenSourceUrlResolver/1.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setNamespaceAware(false);
                dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                DocumentBuilder db = dbf.newDocumentBuilder();
                return db.parse(new java.io.ByteArrayInputStream(resp.body()));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String textContent(Document doc, String parent, String child) {
        NodeList parents = doc.getElementsByTagName(parent);
        if (parents.getLength() == 0) return null;
        NodeList kids = parents.item(0).getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i).getNodeName().equals(child)) {
                String t = kids.item(i).getTextContent();
                return isBlank(t) ? null : t.trim();
            }
        }
        return null;
    }

    private static String notBlankOr(String a, String b) {
        return !isBlank(a) ? a : (!isBlank(b) ? b : null);
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (!isBlank(v)) return v;
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static ParsedPath parseGradleJarClassPath(String path) {
        // Expect something like:  .../modules-2/files-2.1/{groupId path}/{artifactId}/{version}/{hash}/{artifactId}-{version}.jar!/{classEntry}
        int bang = path.indexOf('!');
        if (bang < 0) return null;
        String jarPath = path.substring(0, bang);
        String classEntry = path.substring(bang + 1);
        // Normalize separators
        String norm = jarPath.replace('\\', '/');
        String[] parts = norm.split("/");

        // Find marker "files-2.1" and then expect group path, artifactId, version ahead
        int idx = -1;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("files-2.1")) { idx = i; break; }
        }
        if (idx < 0 || idx + 3 >= parts.length) return null;

        List<String> groupSegs = new ArrayList<>();
        int i = idx + 1;
        // group segments continue until artifactId (which is followed by version)
        // We detect version by checking that parts[i+1] looks like a version in the path
        // Here, we assume format: .../{group...}/{artifactId}/{version}/...
        while (i + 2 < parts.length) {
            String maybeArtifact = parts[i];
            String maybeVersion = parts[i + 1];
            if (looksLikeVersion(maybeVersion)) {
                // found artifactId at i, version at i+1
                String artifactId = maybeArtifact;
                String version = maybeVersion;
                String groupId = String.join(".", groupSegs);
                return new ParsedPath(groupId, artifactId, version, classEntry.startsWith("/") ? classEntry.substring(1) : classEntry);
            } else {
                groupSegs.add(maybeArtifact);
                i++;
            }
        }
        return null;
    }

    private static ParsedPath parseMavenJarClassPath(String path) {
        // Expect something like:  .../.m2/repository/{groupId path}/{artifactId}/{version}/{artifactId}-{version}(-classifier).jar!/{entry}
        int bang = path.indexOf('!');
        if (bang < 0) return null;
        String jarPath = path.substring(0, bang);
        String classEntry = path.substring(bang + 1);
        String norm = jarPath.replace('\\', '/');
        String[] parts = norm.split("/");

        // Find marker "repository"
        int idx = -1;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("repository")) { idx = i; break; }
        }
        if (idx < 0 || idx + 4 >= parts.length) return null;

        // Scan for pattern: .../{artifactId}/{version}/{artifactId}-{version}*.jar
        for (int i = idx + 1; i + 2 < parts.length; i++) {
            String maybeArtifact = parts[i];
            String maybeVersion = parts[i + 1];
            String jarFile = parts[i + 2];
            if (looksLikeVersion(maybeVersion)
                    && jarFile.endsWith(".jar")
                    && jarFile.startsWith(maybeArtifact + "-" + maybeVersion)) {
                // group segments are between repository and artifactId
                List<String> groupSegs = new ArrayList<>();
                for (int g = idx + 1; g < i; g++) groupSegs.add(parts[g]);
                String groupId = String.join(".", groupSegs);
                return new ParsedPath(groupId, maybeArtifact, maybeVersion, classEntry.startsWith("/") ? classEntry.substring(1) : classEntry);
            }
        }
        return null;
    }

    private static boolean looksLikeVersion(String s) {
        // Simple heuristic: digits and dots and hyphens, maybe qualifiers
        return s.matches("[0-9].*");
    }

    // --- GitHub helpers ---
    private static class GitHubRepo {
        final String owner; final String repo;
        GitHubRepo(String owner, String repo) { this.owner = owner; this.repo = repo; }
    }

    private static GitHubRepo parseGitHubRepo(String repoWebUrl) {
        try {
            URI u = URI.create(repoWebUrl);
            String host = u.getHost();
            if (host == null || !host.equalsIgnoreCase("github.com")) return null;
            String path = u.getPath();
            if (path == null) return null;
            String[] segs = path.split("/"); // leading slash -> [ , owner, repo, ...]
            if (segs.length >= 3 && !segs[1].isEmpty() && !segs[2].isEmpty()) {
                String repo = segs[2];
                if (repo.endsWith(".git")) repo = repo.substring(0, repo.length() - 4);
                return new GitHubRepo(segs[1], repo);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static HttpRequest.Builder ghReq(URI uri) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "MavenSourceUrlResolver/1.0");
        String token = System.getenv("GITHUB_TOKEN");
        if (!isBlank(token)) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    private static String githubPickRef(String repoWeb, String artifactId, String version, String scmTag) {
        GitHubRepo gh = parseGitHubRepo(repoWeb);
        if (gh == null) return null;
        List<String> candidates = new ArrayList<>();
        if (!isBlank(scmTag)) candidates.add(scmTag);
        // Prefer artifactId-version first for repos that tag per-module (e.g., Jackson modules)
        candidates.add(artifactId + "-" + version);
        candidates.add("v" + version);
        candidates.add(version);
        List<String> refs = new ArrayList<>();
        for (String r : candidates) if (!isBlank(r) && !refs.contains(r)) refs.add(r);
        for (String r : refs) {
            if (githubRefExists(gh, r)) return r;
        }
        return null;
    }

    private static boolean githubRefExists(GitHubRepo gh, String ref) {
        try {
            String url = String.format("https://api.github.com/repos/%s/%s/git/ref/tags/%s", gh.owner, gh.repo, urlEnc(ref));
            HttpRequest req = ghReq(URI.create(url)).GET().build();
            HttpResponse<Void> resp = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() == 200) return true;
            // try generic refs (could be a branch)
            String url2 = String.format("https://api.github.com/repos/%s/%s/git/refs/%s", gh.owner, gh.repo, urlEnc(ref));
            req = ghReq(URI.create(url2)).GET().build();
            resp = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static String defaultBranchOrNull(GitHubRepo gh) {
        try {
            String url = String.format("https://api.github.com/repos/%s/%s", gh.owner, gh.repo);
            HttpRequest req = ghReq(URI.create(url)).GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                String body = resp.body();
                String key = "\"default_branch\":\"";
                int i = body.indexOf(key);
                if (i >= 0) {
                    int start = i + key.length();
                    int end = body.indexOf('"', start);
                    if (end > start) return body.substring(start, end);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String githubFindJavaPath(GitHubRepo gh, String ref, String javaRelPath, String artifactId) {
        // Quick check of common roots using the Contents API
        String[] roots = new String[] {
                artifactId + "/src/main/java",
                "src/main/java",
                artifactId + "/src/main/kotlin",
                "src/main/kotlin",
                artifactId + "/src",
                "src"
        };
        for (String root : roots) {
            String candidate = root + "/" + javaRelPath;
            if (githubContentExists(gh, ref, candidate)) return candidate;
        }
        // Fallback to recursive tree scanning
        try {
            String url = String.format("https://api.github.com/repos/%s/%s/git/trees/%s?recursive=1", gh.owner, gh.repo, urlEnc(ref));
            HttpRequest req = ghReq(URI.create(url)).GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            String body = resp.body();
            String needle = "/" + javaRelPath.replace('\\', '/');
            String marker = "\"path\":\"";
            String best = null; int bestScore = Integer.MIN_VALUE;
            int idx = 0;
            while ((idx = body.indexOf(marker, idx)) >= 0) {
                int start = idx + marker.length();
                int end = body.indexOf('"', start);
                if (end < 0) break;
                String path = body.substring(start, end);
                if (path.endsWith(needle)) {
                    int score = 0;
                    if (!isBlank(artifactId) && path.startsWith(artifactId + "/")) score += 5;
                    if (path.contains("/src/main/")) score += 3;
                    if (path.contains("/java/")) score += 2;
                    // prefer shorter extra prefixes
                    int extra = path.split("/").length - javaRelPath.split("/").length;
                    score -= Math.max(0, extra);
                    if (score > bestScore) { bestScore = score; best = path; }
                }
                idx = end + 1;
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean githubContentExists(GitHubRepo gh, String ref, String path) {
        // Do not URL-encode path segments with '/' — GitHub expects plain slashes in the path.
        String url = String.format("https://api.github.com/repos/%s/%s/contents/%s?ref=%s", gh.owner, gh.repo, path, urlEnc(ref));
        try {
            HttpRequest req = ghReq(URI.create(url)).GET().build();
            HttpResponse<Void> resp = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static String githubContentType(GitHubRepo gh, String ref, String path) {
        // Returns "file" or "dir" if exists, otherwise null
        String url = String.format("https://api.github.com/repos/%s/%s/contents/%s?ref=%s", gh.owner, gh.repo, path, urlEnc(ref));
        try {
            HttpRequest req = ghReq(URI.create(url)).GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            String body = resp.body().trim();
            if (body.startsWith("[")) return "dir"; // directory listing
            // try to detect type field
            int i = body.indexOf("\"type\":\"");
            if (i >= 0) {
                int start = i + "\"type\":\"".length();
                int end = body.indexOf('"', start);
                if (end > start) {
                    String t = body.substring(start, end);
                    if ("dir".equals(t) || "file".equals(t)) return t;
                }
            }
            // default assume file
            return "file";
        } catch (Exception e) {
            return null;
        }
    }

    private static GitHubPathResult githubFindPath(GitHubRepo gh, String ref, String relPath, String artifactId, boolean isClass) {
        if (isClass) {
            String p = githubFindJavaPath(gh, ref, relPath, artifactId);
            if (!isBlank(p)) return new GitHubPathResult(p, "file");
            return null;
        }
        // Guess a possible module directory from the path (last segment)
        String moduleGuess = null;
        int lastSlash = relPath.lastIndexOf('/');
        if (lastSlash > 0) {
            String pkgPath = relPath.substring(0, lastSlash);
            int lastPkgSlash = pkgPath.lastIndexOf('/');
            if (lastPkgSlash >= 0) moduleGuess = pkgPath.substring(lastPkgSlash + 1);
        }
        String[] roots = moduleGuess == null
                ? new String[] { artifactId + "/src/main/resources", "src/main/resources", artifactId, "" }
                : new String[] { artifactId + "/src/main/resources", moduleGuess + "/src/main/resources", "src/main/resources", artifactId, "" };
        for (String root : roots) {
            String path = isBlank(root) ? relPath : (root + "/" + relPath);
            String type = githubContentType(gh, ref, path);
            if (type != null) {
                return new GitHubPathResult(path, type);
            }
        }
        return null;
    }

    private static boolean httpExists(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "MavenSourceUrlResolver/1.0")
                    .GET().build();
            HttpResponse<Void> resp = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            int sc = resp.statusCode();
            return sc >= 200 && sc < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private static String urlEnc(String s) {
        try { return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }

    // --- Data classes ---
    private static class GitHubPathResult {
        final String path; // path within repo
        final String type; // "file" or "dir"
        GitHubPathResult(String path, String type) { this.path = path; this.type = type; }
    }

    private static class ParsedPath {
        final String groupId;
        final String artifactId;
        final String version;
        final String classEntry; // e.g., com/fasterxml/classmate/AnnotationInclusion.class or any entry
        ParsedPath(String groupId, String artifactId, String version, String classEntry) {
            this.groupId = groupId; this.artifactId = artifactId; this.version = version; this.classEntry = classEntry;
        }
        String gav() { return groupId + ":" + artifactId + ":" + version; }
    }

    private static class PomScm {
        final String url; // <scm><url>
        final String connection; // <scm><connection>
        final String tag; // <scm><tag>
        PomScm(String url, String connection, String tag) { this.url = url; this.connection = connection; this.tag = tag; }
        @Override public String toString() { return "PomScm{" + url + ", " + connection + ", tag=" + tag + '}'; }
    }

    // --- Demo ---
    public static void main(String[] args) {
        String example = System.getProperty("example", System.getenv().getOrDefault("EXAMPLE",
                System.getProperty("user.home") + "/.gradle/caches/modules-2/files-2.1/com.fasterxml/classmate/1.7.0/xxxx/classmate-1.7.0.jar!/com/fasterxml/classmate/AnnotationInclusion.class"));
        System.out.println(resolveSourceUrl(example));
    }
}