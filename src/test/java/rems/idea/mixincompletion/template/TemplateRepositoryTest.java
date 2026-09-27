package rems.idea.mixincompletion.template;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.io.HttpRequests;
import org.junit.Assume;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * How a version range is taken out of the versions a repository lists.
 *
 * <p>The range is applied to the list as the repository orders it rather than by comparing version numbers.
 * That ordering is the repository's own - it is the order a project's subprojects are written in, and the
 * order the build reads them back - and a range typed against it has to mean the same thing.
 */
public class TemplateRepositoryTest extends BasePlatformTestCase {

    private static final List<String> SUPPORTED = List.of(
            "1.20.1", "1.20.2", "1.20.4", "1.21.1", "1.21.4", "1.21.8", "26.3");

    /** Both ends left out takes the lot, which is what an unstated range means. */
    public void testWholeList() {
        assertEquals(SUPPORTED, TemplateRepository.range(SUPPORTED, "", ""));
        assertEquals(SUPPORTED, TemplateRepository.range(SUPPORTED, null, null));
    }

    /** Both ends given takes what lies between them, inclusive. */
    public void testBothEnds() {
        assertEquals(List.of("1.20.2", "1.20.4", "1.21.1"),
                TemplateRepository.range(SUPPORTED, "1.20.2", "1.21.1"));
    }

    /** One end left out runs to that side of the list. */
    public void testOneEnd() {
        assertEquals(List.of("1.21.4", "1.21.8", "26.3"),
                TemplateRepository.range(SUPPORTED, "1.21.4", ""));

        assertEquals(List.of("1.20.1", "1.20.2", "1.20.4"),
                TemplateRepository.range(SUPPORTED, "", "1.20.4"));
    }

    /** A single version is a range whose ends are the same. */
    public void testSingleVersion() {
        assertEquals(List.of("1.21.1"), TemplateRepository.range(SUPPORTED, "1.21.1", "1.21.1"));
    }

    /**
     * Ends the wrong way round still produce a range.
     *
     * <p>The two ends are free text, and which of them names the older version is not something the form
     * asks. Reading them in the order they were typed would produce nothing at all, which reads as a
     * template that does not work rather than as two fields filled in the other way round.
     */
    public void testEndsGivenTheWrongWayRound() {
        assertEquals(List.of("1.20.2", "1.20.4", "1.21.1"),
                TemplateRepository.range(SUPPORTED, "1.21.1", "1.20.2"));
    }

    /**
     * An end the repository does not have is ignored rather than refused.
     *
     * <p>The versions a repository supports change, so a range typed against an older one may name something
     * that is no longer there. Answering with the versions that are still there is more use than answering
     * with nothing, which would look like the template had failed.
     */
    public void testUnknownEnd() {
        assertEquals(List.of("1.20.1", "1.20.2", "1.20.4"),
                TemplateRepository.range(SUPPORTED, "1.19.4", "1.20.4"));

        assertEquals(List.of("1.21.8", "26.3"),
                TemplateRepository.range(SUPPORTED, "1.21.8", "26.9"));
    }

    /** A repository that lists nothing produces nothing, whatever was asked for. */
    public void testEmptyList() {
        assertEquals(List.of(), TemplateRepository.range(List.of(), "1.20.1", "1.21.1"));
    }

    /** The answer is a copy: the list it was taken from is not the project's to edit. */
    public void testAnswerIsIndependentOfTheSourceList() {
        List<String> answer = TemplateRepository.range(SUPPORTED, "1.20.1", "1.20.4");

        assertEquals(List.of("1.20.1", "1.20.2", "1.20.4"), answer);
        assertEquals("the list it came from is unchanged", 7, SUPPORTED.size());
    }

    /**
     * The branches are tried in order, and the one this repository keeps its templates on comes first.
     *
     * <p>A repository's default branch is its own. This one keeps them on {@code dev}, and asking for
     * {@code main} is answered with a page saying the branch does not exist - which arrives as a download that
     * fails and a template list that is empty, with nothing on screen to say which branch was wanted.
     */
    public void testTheBranchesTried() {
        assertEquals(List.of(
                        "https://codeload.github.com/owner/repo/zip/refs/heads/dev",
                        "https://codeload.github.com/owner/repo/zip/refs/heads/main",
                        "https://codeload.github.com/owner/repo/zip/refs/heads/master"),
                TemplateRepository.archiveUrls("owner/repo"));
    }

    /**
     * The repository itself, fetched.
     *
     * <p>What the wizard needs is a directory holding a version list; whether one can be got is a question
     * only the network answers, and every other part of this feature is downstream of it. Skipped where the
     * repository cannot be reached, since a test that fails for the network's reasons says nothing.
     */
    public void testTheTemplateRepositoryIsFetched() {
        String archive = TemplateRepository.archiveUrls(MultiVersionTemplateProvider.REPOSITORY).get(0);

        Assume.assumeTrue("the template repository is not reachable from here", reachable(archive));

        // The copy lives under the IDE's system directory, which a test is not allowed to read until it says
        // so. Only the test needs this: in an IDE the directory is the plugin's own.
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), PathManager.getSystemPath());

        VirtualFile root = TemplateRepository.root(MultiVersionTemplateProvider.REPOSITORY);

        assertNotNull("the repository was fetched", root);
        assertNotNull("it holds the version list the wizard reads", root.findChild("settings.json"));

        List<String> versions = TemplateRepository.versions(root);

        assertTrue("it lists versions: " + versions, versions.size() > 1);
    }

    /**
     * The plugin's own copy of the repository is complete enough to make a project from.
     *
     * <p>It is what the wizard falls back on when the repository cannot be reached, which is the case a
     * reader meets as an empty template list. Every version the list names has to have a properties file of
     * its own - that is what a version subproject is built from - and the main project has to name one of
     * them, or the build refuses to be configured at all.
     */
    public void testThePluginsOwnCopyIsWhole() throws Exception {
        Path directory = Files.createTempDirectory("bundled");

        VfsRootAccess.allowRootAccess(getTestRootDisposable(), directory.toString());

        VirtualFile root = TemplateRepository.bundled(MultiVersionTemplateProvider.REPOSITORY, directory);

        assertNotNull("the plugin holds a copy of the repository", root);

        List<String> versions = TemplateRepository.versions(root);

        assertTrue("it lists versions: " + versions, versions.size() > 1);

        for (String version : versions) {
            assertTrue("it holds " + version + "'s properties",
                    Files.isRegularFile(directory.resolve("versions").resolve(version).resolve("gradle.properties")));
        }

        assertTrue("it holds the scripts a project is built with",
                Files.isRegularFile(directory.resolve("build.gradle"))
                        && Files.isRegularFile(directory.resolve("common.gradle"))
                        && Files.isRegularFile(directory.resolve("settings.gradle"))
                        && Files.isRegularFile(directory.resolve("gradle.properties")));

        String main = Files.readString(directory.resolve("versions").resolve("mainProject")).strip();

        assertTrue("the main project is one of the versions: " + main, versions.contains(main));
    }

    /** A repository somebody else names is not answered out of this plugin's copy of a different one. */
    public void testAnotherRepositoryHasNoCopyHere() throws Exception {
        Path directory = Files.createTempDirectory("bundled");

        assertNull(TemplateRepository.bundled("somebody/else", directory));
        assertFalse("and nothing is written for it", Files.exists(directory.resolve("settings.json")));
    }

    private static boolean reachable(String url) {
        try {
            return HttpRequests.head(url).connectTimeout(4000).readTimeout(4000).tryConnect() > 0;
        } catch (IOException failure) {
            return false;
        }
    }
}
