package rems.idea.mixincompletion.template;

import junit.framework.TestCase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The conversion run against real projects that build against one Minecraft version.
 *
 * <p>A synthetic project proves the transformations do what they were written to do; it cannot prove they
 * survive a build script somebody else wrote. These are two projects of the shape the conversion is for, and
 * what is checked is what a conversion can get wrong without anything saying so: a property a generated script
 * reads and nothing defines, and a file it reaches for that is not in the directory the path resolves against.
 * Either one produces a project that builds fifteen versions and fails on the sixteenth.
 *
 * <p>Skipped when the checkouts are not there, which is the case on any machine but this one. The projects are
 * cloned by hand, not fetched here: a test that reaches the network fails for reasons that are not its own.
 */
public class RealProjectConversionTest extends TestCase {

    /** Where the real checkouts and the template were put. */
    private static final Path WORKSPACE = Path.of("G:\\Downloads\\mv-test");

    /** A property read out of a generated script. */
    private static final Pattern PROPERTY_READ =
            Pattern.compile("project\\.([A-Za-z_][A-Za-z0-9_]*)(?![.\\w])");

    /** A commented line, which reads like a reference and is not one. */
    private static final Pattern COMMENT = Pattern.compile("(?m)^\\s*(?://|\\*|/\\*).*$");

    private static final Pattern FILE_CALL = Pattern.compile("file\\(\\s*[\"'](src/[^\"']+)[\"']\\s*\\)");

    public void testFabricCarpetConverts() throws IOException {
        convert("fabric-carpet");
    }

    public void testVector3Converts() throws IOException {
        convert("vector3");
    }

    /** A project that splits its client and server code, and keeps its Java release in a variable. */
    public void testSvcExtraConverts() throws IOException {
        convert("svc-extra");
    }

    /**
     * Converts one of them over the template's whole range and checks what the result needs to be buildable.
     *
     * @param name the checkout
     */
    private void convert(String name) throws IOException {
        Path source = WORKSPACE.resolve(name);
        Path template = WORKSPACE.resolve("template");

        assumeAvailable(source, template);

        List<String> versions = versionsOf(template);
        Path project = Files.createTempDirectory(name);
        copy(source, project);

        assertFalse("the template lists versions", versions.isEmpty());

        List<String> notes = new ArrayList<>();

        MultiVersionLayout.convert(project, template, versions, "bc74432", notes);

        Set<String> shared = MultiVersionLayout.properties(project.resolve("gradle.properties")).keySet();
        Set<String> perVersion = new LinkedHashSet<>();

        for (String version : versions) {
            Path file = project.resolve("versions").resolve(version).resolve("gradle.properties");

            assertTrue(version + " has a properties file", Files.isRegularFile(file));
            perVersion.addAll(MultiVersionLayout.properties(file).keySet());
        }

        String scripts = COMMENT.matcher(Files.readString(project.resolve("common.gradle"), StandardCharsets.UTF_8)
                + Files.readString(project.resolve("build.gradle"), StandardCharsets.UTF_8)).replaceAll("");
        Matcher reads = PROPERTY_READ.matcher(scripts);

        while (reads.find()) {
            String property = reads.group(1);

            assertTrue(name + " reads " + property + " and nothing defines it",
                    shared.contains(property) || perVersion.contains(property) || KNOWN.contains(property));
        }

        Matcher files = FILE_CALL.matcher(scripts);

        while (files.find()) {
            String relative = files.group(1);

            for (String version : versions) {
                assertTrue(name + ": " + version + " is missing " + relative,
                        Files.isRegularFile(project.resolve("versions").resolve(version).resolve(relative)));
            }
        }

        String main = Files.readString(project.resolve("versions/mainProject")).trim();

        assertTrue(name + " names a main project in the list: " + main, versions.contains(main));
        assertFalse(name + " has no notes at all", notes.isEmpty());
    }

    /**
     * Properties a build script gets from somewhere other than a properties file.
     *
     * <p>{@code mcVersion} is the one that matters: it is put on every version's project by the loop at the
     * end of the node list, which is what the preprocessor's own {@code createNode} calls are for.
     */
    private static final Set<String> KNOWN = Set.of(
            "mcVersion", "name", "version", "group", "path", "projectDir", "buildDir", "rootDir", "rootProject",
            "parent", "ext", "tasks", "repositories", "dependencies", "configurations", "plugins", "logger",
            "file");

    private static void assumeAvailable(Path source, Path template) {
        org.junit.Assume.assumeTrue("the checkout of " + source + " is not present",
                Files.isDirectory(source) && Files.isRegularFile(template.resolve("settings.json")));
    }

    private static List<String> versionsOf(Path template) throws IOException {
        String json = Files.readString(template.resolve("settings.json"), StandardCharsets.UTF_8);
        List<String> versions = new ArrayList<>();
        Matcher matcher = Pattern.compile("\n\\s*\"([0-9][^\"]*)\"").matcher(json);

        while (matcher.find()) {
            versions.add(matcher.group(1));
        }

        return versions;
    }

    /** The checked-in tree only: a build output or a cached wrapper is not the project. */
    private static void copy(Path source, Path target) throws IOException {
        Set<String> skipped = Set.of(".git", "build", ".gradle", "run", "libs", "versions");

        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path relative = source.relativize(path);

                if (relative.getNameCount() > 0 && skipped.contains(relative.getName(0).toString())) {
                    continue;
                }

                Path destination = target.resolve(relative);

                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
