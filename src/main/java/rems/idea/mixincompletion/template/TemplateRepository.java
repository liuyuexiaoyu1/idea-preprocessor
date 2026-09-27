package rems.idea.mixincompletion.template;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.io.HttpRequests;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The template repository, kept as a copy on disk.
 *
 * <p>The wizard asks for the template list every time it opens, and the repository it comes from holds a
 * directory per Minecraft version. Fetching that on every opening would put a download between the reader and
 * the dialog, so it is downloaded once into the plugin's own directory and read from there afterwards.
 *
 * <p>Kept as a directory rather than as a zip: what the wizard wants is to read individual files out of it by
 * path, and a zip would mean either decompressing on every read or holding all of it in memory to answer one
 * question at a time.
 *
 * <p>Which branch it comes from is asked of the repository rather than assumed. A repository's default branch
 * is its own - this one keeps its templates on {@code dev} - and naming the wrong one is answered with a page
 * that says the branch does not exist, which arrives here as a download that fails and a template list that is
 * empty. The branches are tried in the order that puts the likely one first, and all of them are named in the
 * failure, so what went wrong is readable from the log.
 */
final class TemplateRepository {
    private static final Logger LOG = Logger.getInstance(TemplateRepository.class);

    /** Where the copy lives. Under the plugin's own system directory, so it is not the project's business. */
    private static final String CACHE_DIR = "preprocessor-templates";

    /** The branches a repository may keep its templates on, most likely first. */
    private static final List<String> BRANCHES = List.of("dev", "main", "master");

    /** A file only a template repository has, which is what makes a directory a copy of one. */
    private static final String MARKER = "settings.json";

    /** Where the copy that ships with the plugin is, and the list of what is in it. */
    private static final String BUNDLED = "/preprocessor-template";
    private static final String BUNDLED_INDEX = BUNDLED + "/file-list.txt";
    private static final String BUNDLED_REPOSITORY = "liuyuexiaoyu1/fabric-mod-template";

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 60000;

    private TemplateRepository() {}

    /**
     * The root of the template repository, from the cache, the network, or the copy in the plugin.
     *
     * <p>Three sources in that order. The cache is the fast answer and the usual one. The network is the one
     * that keeps the version list current. The copy inside the plugin is what makes the wizard work on a
     * machine that cannot reach the repository at all - behind a proxy that is not configured, or offline -
     * where the alternative is an empty template list and nothing that says why. A wizard that cannot make a
     * project is worse than one a version list behind.
     *
     * <p>A cached copy is only usable when it holds the version list. A directory left behind by a download
     * that was cut off part way through holds files and no list, and treating it as the copy would give the
     * wizard an empty template list for as long as it stayed there.
     *
     * @return the directory holding the repository, or null when there was nothing to read at all
     */
    static @Nullable VirtualFile root(String repository) {
        Path directory = cacheDirectory(repository);
        VirtualFile copy = read(directory);

        if (copy != null) {
            return copy;
        }

        VirtualFile fetched = download(repository, directory);

        return fetched != null ? fetched : bundled(repository, directory);
    }

    /**
     * Unpacks the copy that ships with the plugin into the cache directory.
     *
     * <p>Kept as a list of files beside the files themselves rather than as an archive: a jar cannot be listed
     * from inside, and a list that can be read is a list that can be checked - the test that guards this
     * checks that every version the list of versions names has a properties file of its own.
     *
     * <p>Only for the repository this plugin is written for. A repository a reader added themselves is theirs,
     * and a copy of somebody else's templates is not what they asked for.
     */
    static @Nullable VirtualFile bundled(String repository, Path directory) {
        if (!BUNDLED_REPOSITORY.equals(repository)) {
            return null;
        }

        try {
            String index = resourceText(BUNDLED_INDEX);

            if (index == null) {
                LOG.warn("preprocessor template: the plugin holds no copy of " + repository);

                return null;
            }

            FileUtil.delete(directory);
            Files.createDirectories(directory);

            for (String line : index.split("\n")) {
                String relative = line.strip();

                if (relative.isEmpty()) {
                    continue;
                }

                byte[] contents = resource(BUNDLED + "/" + relative);

                if (contents == null) {
                    continue;
                }

                Path target = directory.resolve(relative).normalize();

                if (!target.startsWith(directory.normalize())) {
                    continue;
                }

                Files.createDirectories(target.getParent());
                Files.write(target, contents, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }

            VirtualFile root = read(directory);

            LOG.info("preprocessor template: " + repository
                    + (root == null ? " could not be unpacked from the plugin" : " taken from the plugin's own copy"));

            return root;
        } catch (IOException failure) {
            LOG.warn("preprocessor template: could not unpack the copy of " + repository, failure);

            return null;
        }
    }

    /** A file from the plugin's own resources, as the bytes it is: one of them is a jar. */
    private static byte @Nullable [] resource(String path) throws IOException {
        try (InputStream in = TemplateRepository.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static @Nullable String resourceText(String path) throws IOException {
        byte[] bytes = resource(path);

        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /** Removes the copy, so the next call fetches it again. */
    static void invalidate(String repository) {
        try {
            FileUtil.delete(cacheDirectory(repository));
        } catch (IOException failure) {
            LOG.warn("preprocessor template: could not clear the copy of " + repository, failure);
        }
    }

    /**
     * The Minecraft versions the repository supports, in the order it lists them.
     *
     * <p>Read rather than derived from the directories: the order is the repository's own, and it is the one
     * a version range has to be taken out of. A range like "1.20.1 to 1.21.5" means the versions this list
     * holds between those two, not everything a version comparison would accept between their numbers.
     */
    static List<String> versions(VirtualFile root) {
        VirtualFile settings = root.findChild(MARKER);

        if (settings == null || !settings.isValid()) {
            return List.of();
        }

        try {
            JsonElement parsed = JsonParser.parseString(VfsUtilCore.loadText(settings));
            JsonElement list = parsed.getAsJsonObject().get("versions");

            if (list == null || !list.isJsonArray()) {
                return List.of();
            }

            List<String> versions = new ArrayList<>();

            for (JsonElement entry : list.getAsJsonArray()) {
                if (entry.isJsonPrimitive()) {
                    versions.add(entry.getAsString());
                }
            }

            return versions;
        } catch (IOException | RuntimeException failure) {
            LOG.warn("preprocessor template: could not read the version list", failure);

            return List.of();
        }
    }

    /** Writes a file into the copy, replacing it, so the next read sees the new contents. */
    static void replace(VirtualFile root, String relativePath, String contents) {
        try {
            VirtualFile file = root.findFileByRelativePath(relativePath);

            if (file == null || !file.isValid()) {
                return;
            }

            file.setBinaryContent(contents.getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            LOG.warn("preprocessor template: could not write " + relativePath, failure);
        }
    }

    /**
     * The versions between two ends of a range, taken from a list the repository defines.
     *
     * <p>Ends that name nothing the repository has are ignored rather than refused: the repository's versions
     * change over time, and a range typed against an older one should still produce the versions that do
     * exist instead of producing nothing.
     */
    static List<String> range(List<String> supported, String from, String to) {
        if (supported.isEmpty()) {
            return supported;
        }

        int start = from == null || from.isBlank() ? 0 : supported.indexOf(from);
        int end = to == null || to.isBlank() ? supported.size() - 1 : supported.indexOf(to);

        if (start < 0) {
            start = 0;
        }

        if (end < 0) {
            end = supported.size() - 1;
        }

        if (end < start) {
            int swap = start;
            start = end;
            end = swap;
        }

        return List.copyOf(supported.subList(start, end + 1));
    }

    /** Where the archives of a repository are, in the order they are tried. */
    static List<String> archiveUrls(String repository) {
        List<String> urls = new ArrayList<>();

        for (String branch : BRANCHES) {
            urls.add("https://codeload.github.com/" + repository + "/zip/refs/heads/" + branch);
        }

        return urls;
    }

    private static Path cacheDirectory(String repository) {
        String name = repository.replace('/', '-');

        return Path.of(PathManager.getSystemPath(), CACHE_DIR, name);
    }

    /** The copy of a repository in a directory, when there is one. */
    private static @Nullable VirtualFile read(Path directory) {
        if (!Files.isRegularFile(directory.resolve(MARKER))) {
            return null;
        }

        return LocalFileSystem.getInstance().refreshAndFindFileByPath(
                FileUtil.toSystemIndependentName(directory.toString()));
    }

    private static @Nullable VirtualFile download(String repository, Path directory) {
        IOException failure = null;

        for (String url : archiveUrls(repository)) {
            try {
                fetch(url, directory);

                // The archive's wrapping directory is dropped as it is unpacked, so what it held is what the
                // cache directory holds. Asking for it one level down asks about a directory that was never
                // written, and answers "no template repository" for a copy that is sitting right there.
                VirtualFile root = read(directory);

                if (root != null) {
                    LOG.info("preprocessor template: " + repository + " fetched from " + url);

                    return root;
                }

                failure = new IOException(url + " held no template repository");
            } catch (IOException problem) {
                failure = problem;
            }
        }

        if (failure != null) {
            LOG.warn("preprocessor template: could not fetch " + repository + ", tried "
                    + String.join(", ", archiveUrls(repository)), failure);
        }

        return null;
    }

    /**
     * Fetches one archive and unpacks it into the cache directory.
     *
     * <p>Asked through the platform rather than through a connection of this plugin's own, so the proxy an
     * IDE is configured with is the one used. A machine that reaches the network only through a proxy is a
     * machine where a connection made by hand hangs or is refused, and the template list is then empty for a
     * reason nothing on screen explains.
     */
    private static void fetch(String url, Path directory) throws IOException {
        Path archive = Files.createTempFile("preprocessor-template", ".zip");

        try {
            HttpRequests.request(url)
                    .connectTimeout(CONNECT_TIMEOUT_MS)
                    .readTimeout(READ_TIMEOUT_MS)
                    .connect(request -> {
                        try (InputStream in = request.getConnection().getInputStream()) {
                            Files.copy(in, archive, StandardCopyOption.REPLACE_EXISTING);
                        }

                        return null;
                    });

            FileUtil.delete(directory);
            Files.createDirectories(directory);

            unpack(archive, directory);
        } finally {
            try {
                Files.deleteIfExists(archive);
            } catch (IOException ignored) {
                // A leftover temporary file is not worth reporting.
            }
        }
    }

    /** Unpacks an archive into a directory, dropping the wrapping directory GitHub puts everything under. */
    private static void unpack(Path archive, Path target) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                int slash = name.indexOf('/');

                if (slash < 0) {
                    continue;
                }

                String relative = name.substring(slash + 1);

                if (relative.isEmpty()) {
                    continue;
                }

                Path destination = target.resolve(relative);

                // Everything stays under the directory being written to. An entry naming its way out of the
                // archive is not a template file, whatever the archive says.
                if (!destination.normalize().startsWith(target.normalize())) {
                    continue;
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
