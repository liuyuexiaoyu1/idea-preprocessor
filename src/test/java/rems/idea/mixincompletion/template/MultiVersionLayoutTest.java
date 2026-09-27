package rems.idea.mixincompletion.template;

import junit.framework.TestCase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the conversion writes, and what it must not.
 *
 * <p>These are the parts that cannot be got wrong by eye. The version code is what the markers in the source
 * compare against, so a code that disagrees with them leaves every condition in the project unevaluated. The
 * properties decide what the build resolves, and a property read as absent is not an error anywhere - it is an
 * empty version in an artifact. Both were wrong in a real checkout before these tests existed.
 */
public class MultiVersionLayoutTest extends TestCase {

    /** A version becomes the number the preprocessor's own syntax uses for it. */
    public void testVersionCodes() {
        assertEquals("1_21_01", MultiVersionLayout.codeOf("1.21.1"));
        assertEquals("1_20_01", MultiVersionLayout.codeOf("1.20.1"));
        assertEquals("1_19_04", MultiVersionLayout.codeOf("1.19.4"));
        assertEquals("1_21_11", MultiVersionLayout.codeOf("1.21.11"));
        assertEquals("0_13_05", MultiVersionLayout.codeOf("0.13.5"));
        assertEquals("26_03_00", MultiVersionLayout.codeOf("26.3"));
        assertEquals("26_01_02", MultiVersionLayout.codeOf("26.1.2"));
        assertEquals("0_00_00", MultiVersionLayout.codeOf("latest"));
    }

    /** One node per version, and a link between each pair in the order they were given. */
    public void testNodeBlock() {
        String block = MultiVersionLayout.preprocessBlock(List.of("1.20.1", "1.21.1", "26.3"));

        assertTrue(block, block.contains("def mc1201 = createNode('1.20.1', 1_20_01, '')"));
        assertTrue(block, block.contains("def mc263 = createNode('26.3', 26_03_00, '')"));
        assertTrue(block, block.contains("mc1211.link(mc1201, null)"));
        assertTrue(block, block.contains("mc263.link(mc1211, null)"));
        assertFalse("the first has nothing before it", block.contains("mc1201.link("));
        assertTrue(block, block.contains("findProject(node.project).ext.mcVersion = node.mcVersion"));
        assertEquals("the block is opened once", 1, countOf(block, "preprocess {"));
    }

    /** A single version is a chain of one, with no links at all. */
    public void testSingleVersionNodeBlock() {
        String block = MultiVersionLayout.preprocessBlock(List.of("1.21.1"));

        assertTrue(block, block.contains("def mc1211 = createNode('1.21.1', 1_21_01, '')"));
        assertFalse("nothing to link to", block.contains(".link("));
    }

    /**
     * A properties file written on Windows is read like any other.
     *
     * <p>A pattern anchored at both ends of a line does not match a line that ends in a carriage return, and
     * every property in a real checkout did. Nothing reports it: the file is read, no property is found, and
     * every value the build script asks for is empty.
     */
    public void testPropertiesAreReadFromACarriageReturnFile() throws IOException {
        Path file = Files.createTempFile("layout", ".properties");
        Files.writeString(file, "minecraft_version=26.3\r\nloader_version=0.19.5\r\nmod_version=1.1.0\r\n",
                StandardCharsets.UTF_8);

        Map<String, String> properties = MultiVersionLayout.properties(file);
        Files.deleteIfExists(file);

        assertEquals("26.3", properties.get("minecraft_version"));
        assertEquals("0.19.5", properties.get("loader_version"));
        assertEquals("1.1.0", properties.get("mod_version"));
    }

    /** A property name may hold a hyphen, which release tooling uses. */
    public void testHyphenatedPropertyNamesAreRead() throws IOException {
        Path file = Files.createTempFile("layout", ".properties");
        Files.writeString(file, "release-extra-branch-name=1.21.10\r\n", StandardCharsets.UTF_8);

        Map<String, String> properties = MultiVersionLayout.properties(file);
        Files.deleteIfExists(file);

        assertEquals("1.21.10", properties.get("release-extra-branch-name"));
    }

    /**
     * The mod names itself, and everything that describes it follows from that.
     *
     * <p>Nothing in a build script has to agree with the metadata file except the metadata file, so it is the
     * one that is read: an id written elsewhere and left behind is an artifact the game will not load. The
     * version and the group only ever live in the properties, under whichever of two names a project chose.
     */
    public void testIdentityComesFromMetadataAndAliases() throws IOException {
        Path project = Files.createTempDirectory("project");
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(project.resolve("src/main/resources/fabric.mod.json"),
                "{\n  \"id\": \"vector3\",\n  \"name\": \"Vector3\"\n}\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("gradle.properties"),
                "version=1.1.0\ngroup=ml.mypals.vectorthree\n", StandardCharsets.UTF_8);

        Map<String, String> identity = MultiVersionLayout.identity(project,
                MultiVersionLayout.properties(project.resolve("gradle.properties")));

        assertEquals("vector3", identity.get("mod_id"));
        assertEquals("Vector3", identity.get("mod_name"));
        assertEquals("1.1.0", identity.get("mod_version"));
        assertEquals("ml.mypals.vectorthree", identity.get("maven_group"));
        assertEquals("the directory is what is left when nothing names it",
                project.getFileName().toString(), identity.get("archives_base_name"));
    }

    /** A project that names nothing but its directory still converts. */
    public void testIdentityFallsBackToTheDirectoryName() throws IOException {
        Path project = Files.createTempDirectory("carpet-igny-addition").resolve("carpet-igny-addition");
        Files.createDirectories(project);

        Map<String, String> identity = MultiVersionLayout.identity(project, Map.of());

        assertEquals("carpet-igny-addition", identity.get("mod_id"));
        assertEquals("carpet-igny-addition", identity.get("archives_base_name"));
    }

    /**
     * A dependency is pinned per version, the loader is not.
     *
     * <p>A pin like {@code 0.161.0+26.3} does not exist for another game version, so writing it at the root
     * gives fifteen versions a dependency that cannot resolve. The loader serves all of them from one version
     * and belongs at the root, which is where both the template and a working project keep it.
     */
    public void testDependenciesAreVersionScopedAndTheLoaderIsNot() {
        assertTrue(MultiVersionLayout.isVersionScoped("fabric_api_version"));
        assertTrue(MultiVersionLayout.isVersionScoped("flashback_version"));
        assertTrue(MultiVersionLayout.isVersionScoped("carpet_core_version"));
        assertTrue(MultiVersionLayout.isVersionScoped("litematica_dependency"));
        assertTrue(MultiVersionLayout.isVersionScoped("minecraft_version"));

        assertFalse(MultiVersionLayout.isVersionScoped("loader_version"));
        assertFalse(MultiVersionLayout.isVersionScoped("loom_version"));
        assertFalse("the mod's own version is shared", MultiVersionLayout.isVersionScoped("mod_version"));
        assertFalse(MultiVersionLayout.isVersionScoped("org.gradle.jvmargs"));
    }

    /** The shared properties carry the project's name and not the template's. */
    public void testRootPropertiesAreTheProjects() throws IOException {
        Path template = templateWith(List.of("26.3"));
        Path project = Files.createTempDirectory("project");
        Files.writeString(project.resolve("gradle.properties"),
                "minecraft_version=26.3\nloader_version=0.19.5\nmod_version=1.1.0\n"
                        + "maven_group=ml.mypals.vectorthree\narchives_base_name=vector3\n"
                        + "fabric_api_version=0.161.0+26.3\n", StandardCharsets.UTF_8);

        Map<String, String> existing = MultiVersionLayout.properties(project.resolve("gradle.properties"));
        String written = MultiVersionLayout.rootProperties(template, existing,
                MultiVersionLayout.identity(project, existing));

        assertTrue(written, written.contains("mod_id="));
        assertTrue(written, written.contains("mod_version=1.1.0"));
        assertTrue(written, written.contains("maven_group=ml.mypals.vectorthree"));
        assertTrue(written, written.contains("archives_base_name=vector3"));
        assertTrue("the loader is shared", written.contains("loader_version=0.19.5"));
        assertFalse("the template's own name is not kept", written.contains("template_mod"));
        assertFalse("a dependency pin is not shared", written.contains("fabric_api_version"));
        assertFalse("the game version belongs to a version", written.contains("minecraft_version"));
    }

    /** The version the project already builds gets the dependency pins it already has. */
    public void testVersionPropertiesCarryThatVersionsDependencies() throws IOException {
        Path template = templateWith(List.of("26.3", "1.21.1"));
        Map<String, String> existing = Map.of(
                "minecraft_version", "26.3",
                "fabric_api_version", "0.161.0+26.3",
                "loader_version", "0.19.5");

        String current = MultiVersionLayout.versionProperties(template, "26.3", existing, new ArrayList<>());
        String other = MultiVersionLayout.versionProperties(template, "1.21.1", existing, new ArrayList<>());

        assertTrue(current, current.contains("minecraft_version=26.3"));
        assertTrue(current, current.contains("fabric_api_version=0.161.0+26.3"));
        assertFalse("the loader is shared", current.contains("loader_version"));
        assertTrue(other, other.contains("minecraft_version=1.21.1"));
        assertFalse("a pin for another version is not this version's", other.contains("fabric_api_version=0.161"));
    }

    /**
     * A plugins block is the one place a property reference does not resolve on its own.
     *
     * <p>It is read before the script it is in, so a reference written with single quotes is not a reference
     * at all, it is the literal text - and Gradle goes looking for a plugin of that name. The value is written
     * out instead, and a reference that cannot be resolved is quoted the way it has to be to work.
     */
    public void testLoomVersionIsResolvedInThePluginsBlock() {
        String build = "plugins {\n\tid 'net.fabricmc.fabric-loom' version \"${loom_version}\"\n}\n";

        assertEquals("'1.18.2'", MultiVersionLayout.loomVersionToken(build, Map.of("loom_version", "1.18.2")));
        assertEquals("\"${loom_version}\"", MultiVersionLayout.loomVersionToken(build, Map.of()));
        assertEquals("'1.17-SNAPSHOT'",
                MultiVersionLayout.loomVersionToken("plugins {\n\tid 'fabric-loom' version '1.17-SNAPSHOT'\n}\n",
                        Map.of()));

        String block = MultiVersionLayout.pluginsBlock(build, "bc74432", Map.of("loom_version", "1.18.2"),
                new ArrayList<>());

        assertTrue(block, block.contains("id 'net.fabricmc.fabric-loom' version '1.18.2' apply false"));
        assertTrue("the other loom is declared so a version can use it",
                block.contains("id 'net.fabricmc.fabric-loom-remap' version '1.18.2' apply false"));
        assertTrue(block, block.contains("id 'com.replaymod.preprocess' version 'bc74432'"));
    }

    /**
     * The shared script keeps the project's own script and changes only what a version decides.
     *
     * <p>What it must not do is take the template's script, which names another mod, applies plugins this
     * project does not use and reaches for a header file it does not have.
     */
    public void testCommonScriptParameterizesTheProjectsOwnScript() throws IOException {
        Path project = Files.createTempDirectory("project");
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(project.resolve("src/main/resources/fabric.mod.json"),
                "{\"id\":\"demo\",\"name\":\"Demo\"}\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("LICENSE"), "MIT\n", StandardCharsets.UTF_8);

        String script = MultiVersionLayout.commonScript(FABRIC_SCRIPT, project, new ArrayList<>());

        assertFalse("the plugins block is declared once, at the root", script.contains("plugins {"));
        assertTrue(script, script.contains("final int mcVersion = project.mcVersion"));
        assertTrue(script, script.contains("apply plugin: unobfuscated ? 'net.fabricmc.fabric-loom'"));
        assertTrue(script, script.contains("JAVA_COMPATIBILITY = JavaVersion.VERSION_25"));
        assertTrue("the release follows the version",
                script.contains("it.options.release = JAVA_COMPATIBILITY.ordinal() + 1"));
        assertTrue(script, script.contains("sourceCompatibility = JAVA_COMPATIBILITY"));
        assertTrue("a dependency goes through the remapper below 26.x",
                script.contains("autoImplementation \"net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}\""));
        assertTrue(script, script.contains("def autoImplementation = { Object... args ->"));
        assertTrue("the loader stays the loader",
                script.contains("minecraft \"com.mojang:minecraft:${project.minecraft_version}\""));
        assertTrue("a file of the build is reached through the root",
                script.contains("from(rootProject.file('LICENSE'))"));
        assertTrue("the access widener is a file of the version",
                script.contains("file(\"src/main/resources/demo.accesswidener\")"));
    }

    /** A project with no Java declaration of its own is not given one. */
    public void testCommonScriptLeavesTheJavaLadderOutWhenNothingNeedsIt() throws IOException {
        Path project = Files.createTempDirectory("project");
        String script = MultiVersionLayout.commonScript(
                "plugins {\n\tid 'net.fabricmc.fabric-loom' version '1.17-SNAPSHOT'\n}\n\ngroup = project.maven_group\n",
                project, new ArrayList<>());

        assertFalse(script, script.contains("JAVA_COMPATIBILITY"));
    }

    /** A conversion writes the whole layout, keeps what it replaces, and copies what a version has to have. */
    public void testConvertWritesTheLayout() throws IOException {
        Path template = templateWith(List.of("26.3", "1.21.1"));
        Path project = temporaryProject();
        List<String> notes = new ArrayList<>();

        MultiVersionLayout.convert(project, template, List.of("1.21.1", "26.3"), "bc74432", notes);

        assertTrue(Files.readString(project.resolve("settings.json")).contains("\"1.21.1\""));
        assertTrue(Files.isRegularFile(project.resolve("settings.gradle")));
        assertTrue(Files.isRegularFile(project.resolve("common.gradle")));
        assertTrue(Files.isRegularFile(project.resolve("build.gradle")));
        assertTrue(Files.isRegularFile(project.resolve("versions/1.21.1/gradle.properties")));
        assertTrue(Files.isRegularFile(project.resolve("versions/26.3/gradle.properties")));

        for (String name : List.of("build.gradle", "settings.gradle", "gradle.properties")) {
            assertTrue(name + " is kept", Files.isRegularFile(project.resolve(name + ".single-version")));
        }

        assertEquals("the version the project builds is the one the sources hang on", "26.3",
                Files.readString(project.resolve("versions/mainProject")).trim());
        assertTrue("a path in a build script resolves against the version directory", Files.isRegularFile(
                project.resolve("versions/1.21.1/src/main/resources/demo.accesswidener")));
        assertTrue("the settings script keeps the project's own name",
                Files.readString(project.resolve("settings.gradle")).contains("rootProject.name = 'demo'"));
        assertFalse("the template's own mod name is nowhere in it",
                Files.readString(project.resolve("gradle.properties")).contains("template_mod"));

        assertFalse(notes.isEmpty());
    }

    /** A conversion of nothing is refused rather than half-written. */
    public void testConvertRefusesAnEmptyRange() throws IOException {
        Path project = temporaryProject();

        try {
            MultiVersionLayout.convert(project, templateWith(List.of("26.3")), List.of(), "bc74432",
                    new ArrayList<>());

            fail("an empty version list has no layout to write");
        } catch (IOException expected) {
            assertFalse("nothing is written", Files.exists(project.resolve("settings.json")));
        }
    }

    /** What the main project is when the version the project builds is outside the range. */
    public void testMainProjectIsTheFirstWhenTheBuiltVersionIsOutside() {
        assertEquals("1.20.1", MultiVersionLayout.mainProject(List.of("1.20.1", "1.21.1"),
                Map.of("minecraft_version", "26.3")));
        assertEquals("26.3", MultiVersionLayout.mainProject(List.of("1.20.1", "26.3"),
                Map.of("minecraft_version", "26.3")));
    }

    /** The template's own pin, for a conversion that cannot reach jitpack. */
    public void testPinnedPreprocessorIsReadFromTheTemplate() throws IOException {
        assertEquals("bc74432", MultiVersionLayout.pinnedPreprocessor(templateWith(List.of("26.3"))));
    }

    /**
     * A project that is already multi-version is left alone.
     *
     * <p>Converting it again would write over the files the first conversion kept, and those are the only copy
     * of what a single-version project's build scripts said: no version list can reconstruct them.
     */
    public void testAnAlreadyConvertedProjectIsRefused() throws IOException {
        Path project = temporaryProject();
        String build = Files.readString(project.resolve("build.gradle"));
        Files.writeString(project.resolve("settings.json"), "{\n  \"versions\": [\n    \"26.3\"\n  ]\n}\n");

        assertTrue(MultiVersionLayout.isMultiVersion(project));

        try {
            MultiVersionLayout.convert(project, templateWith(List.of("26.3")), List.of("26.3"), "bc74432",
                    new ArrayList<>());

            fail("a project that is already multi-version has nothing to convert");
        } catch (IOException expected) {
            assertEquals("the build script is untouched", build,
                    Files.readString(project.resolve("build.gradle")));
            assertFalse("nothing was kept over the originals",
                    Files.exists(project.resolve("build.gradle" + MultiVersionLayout.BACKUP_SUFFIX)));
        }
    }

    /** Either half of the layout on its own is enough to call a project converted. */
    public void testIsMultiVersion() throws IOException {
        Path project = Files.createTempDirectory("project");

        assertFalse("a plain project is not", MultiVersionLayout.isMultiVersion(project));

        Files.createDirectories(project.resolve("versions"));
        Files.writeString(project.resolve("versions/mainProject"), "26.3\n");

        assertTrue("the main project file is the layout's own", MultiVersionLayout.isMultiVersion(project));

        Files.delete(project.resolve("versions/mainProject"));
        Files.writeString(project.resolve("settings.json"), "{}\n");

        assertTrue("so is the version list", MultiVersionLayout.isMultiVersion(project));
    }

    /**
     * A project that keeps the Java release in a variable of its own is followed too.
     *
     * <p>Writing the release in a variable and setting the compile options from it is one decision written
     * twice. Rewriting only the literal leaves every use of the variable at 25, which is right for 26.x and
     * wrong for every version before it - and wrong quietly, because the literal is gone from the line the
     * project would look at.
     */
    public void testJavaVersionVariableFollowsTheVersion() throws IOException {
        Path project = Files.createTempDirectory("project");
        String script = MultiVersionLayout.commonScript("plugins {\n\tid 'net.fabricmc.fabric-loom' version '1.17.3'\n}\n"
                + "\ndef targetJavaVersion = 25\n"
                + "tasks.withType(JavaCompile).configureEach {\n"
                + "\tit.options.release.set(targetJavaVersion)\n"
                + "}\n"
                + "\njava {\n\tdef javaVersion = JavaVersion.toVersion(targetJavaVersion)\n}\n", project,
                new ArrayList<>());

        assertTrue(script, script.contains("targetJavaVersion = JAVA_COMPATIBILITY.ordinal() + 1"));
        assertTrue("the ladder is written even though no literal release is left",
                script.contains("JAVA_COMPATIBILITY = JavaVersion.VERSION_25"));
        assertTrue("what the variable is used for is not touched",
                script.contains("it.options.release.set(targetJavaVersion)"));
    }

    /** A project that splits its client and server code is told what that means for it. */
    public void testSplitSourceSetsAreReported() throws IOException {
        Path project = Files.createTempDirectory("project");
        List<String> notes = new ArrayList<>();

        MultiVersionLayout.commonScript("plugins {\n\tid 'net.fabricmc.fabric-loom' version '1.17.3'\n}\n"
                + "\nloom {\n\tsplitEnvironmentSourceSets()\n}\n", project, notes);

        assertTrue(notes.toString(), notes.stream().anyMatch(note -> note.contains("splitEnvironmentSourceSets")));
    }

    /** A build script of the shape both Fabric's template and every project grown from it has. */
    private static final String FABRIC_SCRIPT =
            "plugins {\n"
                    + "\tid 'net.fabricmc.fabric-loom' version \"${loom_version}\"\n"
                    + "\tid 'maven-publish'\n"
                    + "}\n"
                    + "\n"
                    + "base {\n"
                    + "\tarchivesName = project.archives_base_name\n"
                    + "}\n"
                    + "\n"
                    + "group = project.maven_group\n"
                    + "\n"
                    + "loom {\n"
                    + "\taccessWidenerPath = file(\"src/main/resources/demo.accesswidener\")\n"
                    + "}\n"
                    + "\n"
                    + "dependencies {\n"
                    + "\t// To change the versions see the gradle.properties file\n"
                    + "\tminecraft \"com.mojang:minecraft:${project.minecraft_version}\"\n"
                    + "\timplementation \"net.fabricmc:fabric-loader:${project.loader_version}\"\n"
                    + "\t// comment: implementation \"net.fabricmc.fabric-api:fabric-api:${project.fabric_version}\"\n"
                    + "\timplementation \"net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}\"\n"
                    + "}\n"
                    + "\n"
                    + "tasks.withType(JavaCompile).configureEach {\n"
                    + "\tit.options.release = 25\n"
                    + "}\n"
                    + "\n"
                    + "java {\n"
                    + "\tsourceCompatibility = JavaVersion.VERSION_25\n"
                    + "\ttargetCompatibility = JavaVersion.VERSION_25\n"
                    + "}\n"
                    + "\n"
                    + "jar {\n"
                    + "\tfrom(\"LICENSE\") {\n"
                    + "\t\trename { \"${it}_${project.archives_base_name}\"}\n"
                    + "\t}\n"
                    + "}\n";

    /** A template repository's shape, small enough to write here. */
    private static Path templateWith(List<String> versions) throws IOException {
        Path template = Files.createTempDirectory("template");

        Files.writeString(template.resolve("settings.gradle"),
                "def settings = new groovy.json.JsonSlurper().parseText(file('settings.json').text)\n"
                        + "for (String version : settings.versions) {\n\tinclude(\":$version\")\n}\n",
                StandardCharsets.UTF_8);
        Files.writeString(template.resolve("build.gradle"),
                "plugins {\n\tid 'maven-publish'\n\tid 'com.replaymod.preprocess' version 'bc74432'\n}\n"
                        + "\npreprocess {\n\tstrictExtraMappings = false\n\n"
                        + "\tdef mcX = createNode('1.14.4', 1_14_04, '')\n"
                        + "\tfor (final def node in getNodes()) {\n"
                        + "\t\tfindProject(node.project).ext.mcVersion = node.mcVersion\n\t}\n}\n"
                        + "\ntasks.register('buildAndGather') {\n}\n",
                StandardCharsets.UTF_8);
        Files.writeString(template.resolve("gradle.properties"),
                "org.gradle.jvmargs=-Xmx6G\n\nloader_version=0.19.5\n\n"
                        + "mod_id=template_mod\nmod_name=TemplateMod\nmod_version=1.0.0\n"
                        + "maven_group=me.fallenbreath\narchives_base_name=template_mod\n",
                StandardCharsets.UTF_8);

        for (String version : versions) {
            Files.createDirectories(template.resolve("versions").resolve(version));
            Files.writeString(template.resolve("versions").resolve(version).resolve("gradle.properties"),
                    "minecraft_version=" + version + "\nparchment_version=\n"
                            + "minecraft_dependency=>=" + version + "\n", StandardCharsets.UTF_8);
        }

        return template;
    }

    /** A project of the shape the conversion is for: one build script, sources at the root. */
    private static Path temporaryProject() throws IOException {
        Path project = Files.createTempDirectory("demo");
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(project.resolve("src/main/resources/fabric.mod.json"),
                "{\"id\":\"demo\",\"name\":\"Demo\"}\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("src/main/resources/demo.accesswidener"),
                "accessWidener v2 named\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("LICENSE"), "MIT\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("build.gradle"), FABRIC_SCRIPT, StandardCharsets.UTF_8);
        Files.writeString(project.resolve("settings.gradle"),
                "pluginManagement {\n\trepositories {\n\t\tmavenCentral()\n\t}\n}\n\nrootProject.name = 'demo'\n",
                StandardCharsets.UTF_8);
        Files.writeString(project.resolve("gradle.properties"),
                "minecraft_version=26.3\nloader_version=0.19.5\nloom_version=1.17-SNAPSHOT\n"
                        + "mod_version=1.1.0\nmaven_group=com.example\narchives_base_name=demo\n"
                        + "fabric_api_version=0.161.0+26.3\n", StandardCharsets.UTF_8);

        return project;
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int at = text.indexOf(needle);

        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }

        return count;
    }
}
