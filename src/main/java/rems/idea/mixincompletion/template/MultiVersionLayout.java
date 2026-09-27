package rems.idea.mixincompletion.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the multi-version layout over a project that builds against one Minecraft version.
 *
 * <p>Touches nothing but the file system, so it can be run against a real checkout and read afterwards
 * without an IDE. The action that offers this to a reader is a thin front end over it.
 *
 * <p>The layout is the one a working multi-version project uses, and it is not the shape of the template
 * repository the wizard installs. A template is a project of its own: its build script names its own mod, its
 * own mixin config, its own language directory, and the plugins it happens to use. Copying it over an
 * existing project produces a project that builds something else - the sources stay, and everything that
 * describes them does not. So the template contributes only what is about versions rather than about a mod:
 * the settings script that registers a subproject per version, the node list, the gathering task, and the
 * shape of a per-version properties file. The rest is derived from the project being converted.
 *
 * <p>What is derived is a short list, and each entry is a place where a single-version script states a
 * property of its version as a constant:
 * <ol>
 *   <li>the loom plugin, which is the obfuscating one below 26.x and the other one above it;</li>
 *   <li>the Java release, which is 25 for 26.x and 21 or lower before it;</li>
 *   <li>a path the script reaches for, which resolves against the version's own directory rather than the
 *       root, so the file has to exist in every version;</li>
 *   <li>the dependency helpers, which put a dependency on the remapper on the versions that need one;</li>
 *   <li>the mod identity, which a template names after itself and every project names after its own mod.</li>
 * </ol>
 */
final class MultiVersionLayout {

    /** The files this rewrites, kept under this suffix so nothing is lost. */
    static final String BACKUP_SUFFIX = ".single-version";

    /** The plugin the converted project builds with, as jitpack spells the coordinate. */
    static final String PREPROCESSOR = "liuyuexiaoyu1/preprocessor";

    /** A Minecraft version as the preprocess plugin takes it: major, minor and patch. */
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    /**
     * A property line. The name may hold a hyphen: release tooling uses names like
     * {@code release-curse-versions}, and a reader that only accepts identifiers drops those lines without
     * saying so.
     */
    private static final Pattern PROPERTY =
            Pattern.compile("(?m)^[ \\t]*([A-Za-z_][A-Za-z0-9_.-]*)[ \\t]*[=:][ \\t]*(.*?)[ \\t]*$");

    /** Whatever a project writes instead of the names the shared script looks for. */
    private static final Map<String, String> PROPERTY_ALIASES = Map.of(
            "version", "mod_version",
            "group", "maven_group",
            "archivesBaseName", "archives_base_name");

    /** Read out of {@code fabric.mod.json}, which is where a mod names itself. */
    private static final Pattern MOD_JSON_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MOD_JSON_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    /** The identity of the mod, which the shared properties file has to carry. */
    private static final List<String> IDENTITY = List.of(
            "mod_id", "mod_name", "mod_version", "maven_group", "archives_base_name");

    /** Properties that belong to one version, and are therefore not written at the root. */
    private static final Set<String> PER_VERSION = Set.of(
            "minecraft_version", "minecraft_dependency", "parchment_version", "game_versions");

    /**
     * What a dependency is called, which is what makes it version-scoped.
     *
     * <p>A dependency is pinned per game version: {@code 0.161.0+26.3} does not exist for 1.20.1, and a pin
     * written at the root is a pin every version inherits. The exception is the loader, which is one version
     * for the whole project and sits at the root of both the template and a working project, and the loom,
     * which is a plugin rather than a dependency.
     */
    private static final Pattern VERSION_SCOPED = Pattern.compile("[A-Za-z0-9_.-]*(_version|_dependency)");

    private static final Set<String> GLOBAL_VERSIONS = Set.of("loader_version", "loom_version");

    /** A file the build script reaches for by a path of its own, resolved against the project directory. */
    private static final Pattern PROJECT_FILE =
            Pattern.compile("file\\(\\s*[\"'](src/[^\"']+)[\"']\\s*\\)");

    /** A file the build takes from the build root, which in a version directory is not there. */
    private static final Pattern ROOT_FILE = Pattern.compile("from\\(\\s*[\"']([^\"'$:]+)[\"']\\s*\\)");

    /** A path built from the project directory, which in a version directory is a different directory. */
    private static final Pattern PROJECT_DIRECTORY = Pattern.compile("\"\\$projectDir/([^\"']+)\"");

    /** A dependency call that has to go through the remapper on the versions that have one. */
    private static final Pattern DEPENDENCY_CALL = Pattern.compile("(?m)^(\\s*)(implementation|runtimeOnly|compileOnly)(\\s|\\()");

    /**
     * A Java release kept in a variable of the project's own.
     *
     * <p>Not every project writes the release where the platform's own template writes it. One keeps it in a
     * variable and sets the compile options from that, which is the same decision written twice: the variable
     * is the literal, and every use of it follows. Rewriting the literal is what makes the uses follow.
     */
    private static final Pattern JAVA_VERSION_VARIABLE = Pattern.compile(
            "(?m)^(\\s*)(def\\s+)?([A-Za-z_][A-Za-z0-9_]*[Jj]ava[Vv]ersion)\\s*=\\s*\\d+\\s*$");

    private static final String LOOM_UNOBFUSCATED = "net.fabricmc.fabric-loom";
    private static final String LOOM_REMAP = "net.fabricmc.fabric-loom-remap";
    private static final String PREPROCESS_APPLY = "apply plugin: 'com.replaymod.preprocess'\n";

    private MultiVersionLayout() {}

    /**
     * Converts a project, version by version.
     *
     * @param project  the project directory, holding the shared sources under {@code src}
     * @param template the root of the template repository
     * @param versions the versions to build, in the order they are to be listed
     * @param preprocessorVersion the version of the preprocessor plugin to pin in the root script
     * @param notes    collects what the conversion decided, and what it could not
     */
    static void convert(Path project, Path template, List<String> versions,
                        String preprocessorVersion, List<String> notes) throws IOException {
        if (versions.isEmpty()) {
            throw new IOException("没有选中任何版本");
        }

        if (isMultiVersion(project)) {
            throw new IOException("这个项目已经是多版本项目，不再转换：再转一次会覆盖原来的 *"
                    + BACKUP_SUFFIX + " 备份，那是单版本配置仅存的一份");
        }

        Map<String, String> existing = properties(project.resolve("gradle.properties"));
        Map<String, String> identity = identity(project, existing);
        String build = text(project.resolve("build.gradle"));
        String settings = text(project.resolve("settings.gradle"));

        // Kept before anything is written. These hold settings that differ from project to project - the mod's
        // own name, the dependencies it uses, the plugins it applies - and a version list knows about none of
        // them.
        keep(project, "build.gradle");
        keep(project, "settings.gradle");
        keep(project, "gradle.properties");

        write(project.resolve("settings.json"), settingsJson(versions));
        write(project.resolve("settings.gradle"), settingsScript(template, settings));
        write(project.resolve("build.gradle"),
                rootBuildScript(template, build, versions, preprocessorVersion, existing, notes));
        write(project.resolve("common.gradle"), commonScript(build, project, notes));
        write(project.resolve("gradle.properties"), rootProperties(template, existing, identity));

        String main = mainProject(versions, existing);
        Path versionsDirectory = project.resolve("versions");
        Files.createDirectories(versionsDirectory);
        write(versionsDirectory.resolve("mainProject"), main);
        notes.add("主版本 versions/mainProject = " + main + "（共享源码挂在它上面，其余版本由它传递）");

        Set<String> overlays = new LinkedHashSet<>();

        for (String version : versions) {
            Path directory = versionsDirectory.resolve(version);
            Files.createDirectories(directory);
            write(directory.resolve("gradle.properties"), versionProperties(template, version, existing, notes));
            overlays.addAll(copyOverlays(project, build, directory));
        }

        if (!overlays.isEmpty()) {
            notes.add("构建脚本按路径引用的文件已复制进每个版本目录：" + String.join("、", overlays)
                    + "（每个版本可以各改各的）");
        }
    }

    /**
     * Whether a project already has the layout this writes.
     *
     * <p>Both files are this layout's own; nothing else in a Fabric project is named either. A project that has
     * one of them has been converted, or was converted and interrupted, and converting it a second time would
     * write over the files the first conversion kept - which are the only copy of the settings a single-version
     * script had, since nothing here can know what they were.
     */
    static boolean isMultiVersion(Path project) {
        return Files.isRegularFile(project.resolve("settings.json"))
                || Files.isRegularFile(project.resolve("versions").resolve("mainProject"));
    }

    /**
     * The version list, which every other part of the layout is read from.
     *
     * <p>Written first and read by the settings script, so the set of subprojects and the set of directories
     * cannot come apart: both are this list.
     */
    static String settingsJson(List<String> versions) {
        StringBuilder json = new StringBuilder("{\n  \"versions\": [\n");

        for (int index = 0; index < versions.size(); index++) {
            json.append("    \"").append(versions.get(index)).append('"');

            if (index < versions.size() - 1) {
                json.append(',');
            }

            json.append('\n');
        }

        return json.append("  ]\n}\n").toString();
    }

    /**
     * The settings script: the template's, with the project's own name kept.
     *
     * <p>A name given there is not decoration. Gradle falls back to the directory name, and the directory a
     * project is cloned into is not always what it calls itself, so losing the line renames the build - and
     * with it every artifact it produces.
     */
    static String settingsScript(Path template, String existing) throws IOException {
        String text = text(template.resolve("settings.gradle"));
        String name = rootProjectName(existing);

        if (name == null || rootProjectName(text) != null) {
            return text;
        }

        return text + "\nrootProject.name = '" + name + "'\n";
    }

    /**
     * The root build script: the template's, with the project's plugins and a node per version.
     *
     * <p>The plugin ids come from the project rather than the template. The template pins the loom it was
     * written against; the project already has a loom that builds its sources, and which loom that is is the
     * project's business, not the version list's.
     */
    static String rootBuildScript(Path template, String build, List<String> versions,
                                  String preprocessorVersion, Map<String, String> existing,
                                  List<String> notes) throws IOException {
        String script = text(template.resolve("build.gradle"));
        String plugins = pluginsBlock(build, preprocessorVersion, existing, notes);
        int open = script.indexOf('{');
        int close = open < 0 ? -1 : matchingBrace(script, open);

        if (script.stripLeading().startsWith("plugins {") && close > 0) {
            int start = script.indexOf("plugins {");
            script = plugins + script.substring(close + 1);
        } else {
            notes.add("模板的 build.gradle 没有 plugins 块，插件声明写在最前面");
            script = plugins + script;
        }

        int preprocess = script.indexOf("preprocess {");
        int gather = script.indexOf("tasks.register('buildAndGather'");

        if (preprocess < 0 || gather < 0) {
            notes.add("模板的 build.gradle 结构不认识，节点列表没有写进去，需要手工补 preprocess 块");

            return script;
        }

        // The node list is written here rather than taken from the template, and the part after it is kept.
        // Cutting the template's own list out by looking for a closing brace finds the one that ends the loop
        // inside it instead of the one that ends the block, and what is left does not parse.
        return script.substring(0, preprocess) + preprocessBlock(versions) + script.substring(gather);
    }

    /**
     * The plugins the root script declares.
     *
     * <p>Both loom ids are declared and neither is applied here: which one a version uses depends on whether
     * the game is obfuscated at that version, and that is not known until the node list is built.
     */
    static String pluginsBlock(String build, String preprocessorVersion, Map<String, String> existing,
                               List<String> notes) {
        String loom = loomId(build);

        if (loom == null) {
            notes.add("构建脚本里没有找到 loom 插件，按 " + LOOM_UNOBFUSCATED + " 处理，请确认");

            loom = LOOM_UNOBFUSCATED;
        }

        String other = LOOM_UNOBFUSCATED.equals(loom) ? LOOM_REMAP : LOOM_UNOBFUSCATED;
        String version = loomVersionToken(build, existing);

        return "plugins {\n"
                + "\tid 'maven-publish'\n"
                + "\tid '" + loom + "' version " + version + " apply false\n"
                + "\tid '" + other + "' version " + version + " apply false\n"
                + "\n"
                + "\t// https://github.com/ReplayMod/preprocessor\n"
                + "\t// https://github.com/Fallen-Breath/preprocessor\n"
                + "\t// https://github.com/liuyuexiaoyu1/preprocessor\n"
                + "\tid 'com.replaymod.preprocess' version '" + preprocessorVersion + "'\n"
                + "}\n";
    }

    /**
     * The loom version as a plugins block can take it.
     *
     * <p>A plugins block is read before the script it is in, so it is the one place a property reference does
     * not resolve on its own; a reference written with single quotes is not a reference at all, it is the
     * literal text. The value is written out here instead, and a reference that cannot be resolved is left as
     * one, quoted the way it has to be for it to work.
     */
    static String loomVersionToken(String build, Map<String, String> existing) {
        String version = loomVersion(build);
        Matcher matcher = Pattern.compile("^\\$\\{([A-Za-z_][A-Za-z0-9_.]*)\\}$").matcher(version);

        if (matcher.matches()) {
            String resolved = existing.get(matcher.group(1));

            return resolved == null || resolved.isBlank() ? "\"" + version + "\"" : "'" + resolved + "'";
        }

        return "'" + version + "'";
    }

    /** The preprocessor version the template pins, for when the newest one cannot be looked up. */
    static String pinnedPreprocessor(Path template) throws IOException {
        Matcher matcher = Pattern
                .compile("id\\s+['\"]com\\.replaymod\\.preprocess['\"]\\s+version\\s+['\"]([^'\"]+)['\"]")
                .matcher(text(template.resolve("build.gradle")));

        return matcher.find() ? matcher.group(1) : "bc74432";
    }

    /** The loom plugin the project applies. */
    static String loomId(String build) {
        for (String id : List.of(LOOM_UNOBFUSCATED, LOOM_REMAP)) {
            if (build.contains("'" + id + "'") || build.contains("\"" + id + "\"")) {
                return id;
            }
        }

        for (String id : List.of("fabric-loom")) {
            if (build.contains("'" + id + "'")) {
                return id;
            }
        }

        return null;
    }

    /**
     * The loom version the project asks for.
     *
     * <p>A version written as a property reference stays one: the property is carried into the shared
     * properties file, so the reference resolves the same way it did before.
     */
    static String loomVersion(String build) {
        Matcher matcher = Pattern
                .compile("id\\s+['\"][^'\"]*fabric-loom[^'\"]*['\"]\\s+version\\s+([^\\n]+)")
                .matcher(build);

        if (matcher.find()) {
            String version = matcher.group(1).trim().replace("'", "").replace("\"", "").trim();

            if (!version.isEmpty()) {
                return version;
            }
        }

        return "1.17-SNAPSHOT";
    }

    /**
     * The preprocess block: one node per version, each linked to the one before it.
     *
     * <p>A chain rather than a fan. A link says two versions can be written as one piece of source, and a
     * chain is what lets a project spanning a range share source across it instead of only with one version.
     */
    static String preprocessBlock(List<String> versions) {
        StringBuilder nodes = new StringBuilder();
        StringBuilder links = new StringBuilder();
        List<String> names = new ArrayList<>();

        for (String version : versions) {
            String name = "mc" + version.replace(".", "");
            names.add(name);
            nodes.append("\tdef ").append(name).append(" = createNode('").append(version).append("', ")
                    .append(codeOf(version)).append(", '')\n");
        }

        for (int index = 1; index < names.size(); index++) {
            links.append('\t').append(names.get(index)).append(".link(").append(names.get(index - 1))
                    .append(", null)\n");
        }

        return "preprocess {\n\tstrictExtraMappings = false\n\n"
                + nodes
                + "\n"
                + links
                + "\n\tfor (final def node in getNodes()) {\n"
                + "\t\tfindProject(node.project).ext.mcVersion = node.mcVersion\n"
                + "\t}\n}\n\n";
    }

    /**
     * A version as the preprocess plugin wants it: {@code 1.21.1} becomes {@code 1_21_01}.
     *
     * <p>Not an index or a hash - the number is what the preprocessor's own syntax compares against, so the
     * same version has to come out as the same number everywhere, and a project whose node codes disagree
     * with the codes in its markers is a project whose markers never match.
     */
    static String codeOf(String version) {
        Matcher matcher = VERSION.matcher(version);

        if (!matcher.find()) {
            return "0_00_00";
        }

        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));

        return String.format("%d_%02d_%02d", major, minor, patch);
    }

    /**
     * The shared build script, derived from the one the project already had.
     *
     * <p>Derived rather than replaced. A single-version script is already the right script: what makes it
     * single-version is a handful of constants written into it, and each of those is a place where a property
     * of the version belongs instead. The rest of it, which is most of it, is the project's and is kept.
     */
    static String commonScript(String build, Path project, List<String> notes) {
        String body = withoutPluginsBlock(build, notes);
        boolean perVersionJava = body.contains("JavaVersion.VERSION_") || body.contains("options.release")
                || JAVA_VERSION_VARIABLE.matcher(body).find();

        body = parameterizeJava(body, perVersionJava);
        body = parameterizePaths(body, project);
        body = parameterizeDependencies(body, notes);

        // A project that splits its client and server code has a second source set, and the sources of that one
        // are shared the same way. What is not shared is a file only some versions need, and the reader is the
        // one who knows which: the preprocessor places versions of it where it puts the rest, not here.
        if (build.contains("splitEnvironmentSourceSets") || build.contains("sourceSets.client")) {
            notes.add("工程把 client 与 main 分开（splitEnvironmentSourceSets）：两侧源码都按版本合并；"
                    + "某一版才需要的 client 文件放进 versions/<版本>/src/client/...");
        }

        // Losing a block leaves the blank lines that framed it, and a script with three of them in a row reads
        // as though something is missing from it.
        return (header(build, perVersionJava) + body).replaceAll("\n{3,}", "\n\n");
    }

    /** The part that has to run before the project's own script body. */
    private static String header(String build, boolean perVersionJava) {
        StringBuilder header = new StringBuilder()
                .append("final int mcVersion = project.mcVersion\n")
                .append("final boolean unobfuscated = mcVersion >= 26_00_00\n")
                .append('\n')
                .append("apply plugin: 'maven-publish'\n")
                .append("apply plugin: unobfuscated ? '").append(LOOM_UNOBFUSCATED).append("' : '")
                .append(LOOM_REMAP).append("'\n")
                .append(PREPROCESS_APPLY);

        if (perVersionJava) {
            header.append('\n')
                    .append("// The Java release is a property of the version: 26.x is built with 25 and\n")
                    .append("// everything before it with 21 or less, and a converted project holds both.\n")
                    .append("JavaVersion JAVA_COMPATIBILITY\n")
                    .append("if (mcVersion >= 260000) {\n")
                    .append("\tJAVA_COMPATIBILITY = JavaVersion.VERSION_25\n")
                    .append("} else if (mcVersion >= 12005) {\n")
                    .append("\tJAVA_COMPATIBILITY = JavaVersion.VERSION_21\n")
                    .append("} else if (mcVersion >= 11800) {\n")
                    .append("\tJAVA_COMPATIBILITY = JavaVersion.VERSION_17\n")
                    .append("} else if (mcVersion >= 11700) {\n")
                    .append("\tJAVA_COMPATIBILITY = JavaVersion.VERSION_16\n")
                    .append("} else {\n")
                    .append("\tJAVA_COMPATIBILITY = JavaVersion.VERSION_1_8\n")
                    .append("}\n");
        }

        return header.append('\n').toString();
    }

    /** The project's script without its plugins block, which the root script declares instead. */
    private static String withoutPluginsBlock(String build, List<String> notes) {
        if (!build.stripLeading().startsWith("plugins {")) {
            return build;
        }

        int start = build.indexOf("plugins {");
        int close = matchingBrace(build, build.indexOf('{', start));

        if (close < 0) {
            notes.add("构建脚本的 plugins 块括号不配对，没有去掉，请手工确认共享脚本");

            return build;
        }

        return build.substring(0, start) + build.substring(close + 1);
    }

    /** The Java declarations, replaced by the release the version calls for. */
    private static String parameterizeJava(String body, boolean perVersionJava) {
        if (!perVersionJava) {
            return body;
        }

        return body.replace("JavaVersion.VERSION_25", "JAVA_COMPATIBILITY")
                .replace("JavaVersion.VERSION_21", "JAVA_COMPATIBILITY")
                .replace("JavaVersion.VERSION_17", "JAVA_COMPATIBILITY")
                .replace("JavaVersion.VERSION_16", "JAVA_COMPATIBILITY")
                .replace("JavaVersion.VERSION_1_8", "JAVA_COMPATIBILITY")
                .replaceAll("(?m)^(\\s*)(it\\.)?options\\.release\\s*=\\s*\\d+\\s*$",
                        "$1$2options.release = JAVA_COMPATIBILITY.ordinal() + 1")
                .replaceAll(JAVA_VERSION_VARIABLE.pattern(),
                        "$1$2$3 = JAVA_COMPATIBILITY.ordinal() + 1");
    }

    /**
     * The paths that a version directory changes the meaning of.
     *
     * <p>A path in a build script is resolved against the project directory; a version's project directory is
     * the version's own. A file that lives at the root of the build therefore has to be reached through the
     * root, and a file that lives in the sources is copied into every version so a version can override it -
     * which is what an access widener needs, since the entries it needs differ by version.
     */
    private static String parameterizePaths(String body, Path project) {
        String result = PROJECT_DIRECTORY.matcher(body).replaceAll("rootProject.file('$1')");
        Matcher matcher = ROOT_FILE.matcher(result);
        StringBuilder output = new StringBuilder();

        while (matcher.find()) {
            String relative = matcher.group(1);
            boolean atRoot = Files.isRegularFile(project.resolve(relative));

            matcher.appendReplacement(output, atRoot
                    ? Matcher.quoteReplacement("from(rootProject.file('" + relative + "'))")
                    : Matcher.quoteReplacement(matcher.group()));
        }

        matcher.appendTail(output);

        return output.toString();
    }

    /**
     * The dependency calls, routed through the remapper where the version has one.
     *
     * <p>Above 26.x the game is unobfuscated and a dependency is a plain one; below it the game is obfuscated
     * and a mod dependency has to go through the remapper. The same line cannot be both, so the call is
     * wrapped in the helper the template uses and the choice is made per version.
     */
    private static String parameterizeDependencies(String body, List<String> notes) {
        String[] lines = body.split("\n", -1);
        boolean needed = false;

        for (String line : lines) {
            if (!isComment(line) && DEPENDENCY_CALL.matcher(line).find()) {
                needed = true;

                break;
            }
        }

        boolean dependenciesSeen = false;

        for (String line : lines) {
            if (line.stripLeading().startsWith("dependencies {")) {
                dependenciesSeen = true;

                break;
            }
        }

        if (!dependenciesSeen) {
            notes.add("构建脚本里没有 dependencies 块，依赖没有走重映射器，请确认");

            return body;
        }

        if (!needed) {
            return body;
        }

        StringBuilder result = new StringBuilder();
        boolean helpersWritten = false;

        for (String line : lines) {
            if (!helpersWritten && line.stripLeading().startsWith("dependencies {")) {
                result.append(line).append('\n').append(HELPERS);
                helpersWritten = true;

                continue;
            }

            Matcher matcher = isComment(line) ? null : DEPENDENCY_CALL.matcher(line);

            if (matcher != null && matcher.find()) {
                result.append(matcher.replaceFirst("$1auto" + capitalize(matcher.group(2)) + "$3")).append('\n');
            } else {
                result.append(line).append('\n');
            }
        }

        notes.add("依赖调用已改为 auto* 包装：26.x 之前由重映射器处理，26.x 起直连");

        return result.toString();
    }

    private static boolean isComment(String line) {
        String trimmed = line.stripLeading();

        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
    }

    /** What the dependency helpers are, written the way the template writes them. */
    private static final String HELPERS =
            "\tdef processDependency = { dep ->\n"
                    + "\t\t// https://github.com/FabricMC/fabric-loader/issues/783\n"
                    + "\t\tif (dep instanceof ModuleDependency\n"
                    + "\t\t\t\t&& !(dep.group == 'net.fabricmc' && dep.name == 'fabric-loader')) {\n"
                    + "\t\t\tdep.exclude group: 'net.fabricmc', module: 'fabric-loader'\n"
                    + "\t\t}\n"
                    + "\t\treturn dep\n"
                    + "\t}\n"
                    + "\tdef autoImplementation = { Object... args ->\n"
                    + "\t\tprocessDependency(unobfuscated ? implementation(*args) : modImplementation(*args))\n"
                    + "\t}\n"
                    + "\tdef autoRuntimeOnly = { Object... args ->\n"
                    + "\t\tprocessDependency(unobfuscated ? runtimeOnly(*args) : modRuntimeOnly(*args))\n"
                    + "\t}\n"
                    + "\tdef autoCompileOnly = { Object... args ->\n"
                    + "\t\tprocessDependency(unobfuscated ? compileOnly(*args) : modCompileOnly(*args))\n"
                    + "\t}\n";

    private static String capitalize(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * The shared properties: the template's keys, the project's values.
     *
     * <p>The values and not the keys, because the template names a mod of its own. A project that names its
     * properties differently - {@code version} rather than {@code mod_version} - is renamed on the way in, and
     * a project that names its mod nowhere but in its metadata file is read out of that file.
     */
    static String rootProperties(Path template, Map<String, String> existing, Map<String, String> identity)
            throws IOException {
        String text = text(template.resolve("gradle.properties"));
        StringBuilder result = new StringBuilder();
        Set<String> written = new LinkedHashSet<>();

        for (String line : text.split("\n", -1)) {
            Matcher matcher = PROPERTY.matcher(line);

            if (!matcher.matches()) {
                result.append(line).append('\n');

                continue;
            }

            String name = matcher.group(1);
            String carried = valueOf(existing, identity, name);

            written.add(name);
            result.append(name).append('=').append(carried == null ? matcher.group(2) : carried).append('\n');
        }

        // Whatever the project has that the template does not names something about this mod rather than about
        // a version, and is the project's to keep. What names a dependency is the exception: it is pinned per
        // game version and goes to the version files instead.
        for (Map.Entry<String, String> entry : existing.entrySet()) {
            if (isVersionScoped(entry.getKey()) || written.contains(entry.getKey())) {
                continue;
            }

            result.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }

        for (String name : IDENTITY) {
            if (!written.contains(name)) {
                result.append(name).append('=').append(identity.get(name)).append('\n');
            }
        }

        return result.toString();
    }

    /**
     * The properties of one version.
     *
     * <p>Started from the template's entry for that version, so the mappings and loader versions are ones
     * known to work together. The project's own dependency versions are laid over it for the version the
     * project already builds, and only for that one: a dependency pinned as {@code 0.161.0+26.3} is pinned to
     * that game version and to no other, and writing it into every version would be a claim the project never
     * made. The other versions are left with the template's values, which are the ones known to exist.
     */
    static String versionProperties(Path template, String version, Map<String, String> existing,
                                    List<String> notes) throws IOException {
        Path file = template.resolve("versions").resolve(version).resolve("gradle.properties");
        StringBuilder result = new StringBuilder();
        Set<String> written = new LinkedHashSet<>();

        if (Files.isRegularFile(file)) {
            for (String line : text(file).split("\n", -1)) {
                Matcher matcher = PROPERTY.matcher(line);

                if (!matcher.matches()) {
                    result.append(line).append('\n');

                    continue;
                }

                String name = matcher.group(1);
                written.add(name);
                result.append(name).append('=')
                        .append("minecraft_version".equals(name) ? version : matcher.group(2)).append('\n');
            }
        }

        if (!written.contains("minecraft_version")) {
            result.append("minecraft_version=").append(version).append('\n');
        }

        if (!version.equals(existing.get("minecraft_version"))) {
            return result.toString();
        }

        int carried = 0;

        for (Map.Entry<String, String> entry : existing.entrySet()) {
            if (!isVersionScoped(entry.getKey()) || written.contains(entry.getKey())) {
                continue;
            }

            result.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            carried++;
        }

        if (carried > 0) {
            notes.add("项目的依赖版本写进了 versions/" + version + "/gradle.properties"
                    + "（它当前构建的那个版本），其余版本用模板的值");
        }

        return result.toString();
    }

    /**
     * Whether a property is pinned to a game version.
     *
     * <p>A dependency named as such is: the loader and the loom are not, because one version of each serves
     * every game version the project builds.
     */
    static boolean isVersionScoped(String name) {
        return !GLOBAL_VERSIONS.contains(name) && !IDENTITY.contains(name)
                && (VERSION_SCOPED.matcher(name).matches() || PER_VERSION.contains(name));
    }

    /**
     * Copies the files the build script reaches for into each version.
     *
     * <p>A path in a build script resolves against the project directory, and a version's project directory is
     * the version's own. A file named as {@code file("src/...")} therefore has to exist in every version
     * directory, or the build fails there and nowhere else - which is how a version list that is otherwise
     * right produces one version that will not build.
     */
    static Set<String> copyOverlays(Path project, String build, Path versionDirectory) throws IOException {
        Matcher matcher = PROJECT_FILE.matcher(build);
        Set<String> copied = new LinkedHashSet<>();

        while (matcher.find()) {
            String relative = matcher.group(1);
            Path source = project.resolve(relative);

            if (!Files.isRegularFile(source)) {
                continue;
            }

            Path target = versionDirectory.resolve(relative);
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            copied.add(relative);
        }

        return copied;
    }

    /**
     * What the project says it is.
     *
     * <p>The metadata file is the authority on the id and the name, because that is what the game reads and
     * what has to match. The version and the group only ever live in the properties, under whichever of two
     * names the project happened to use.
     */
    static Map<String, String> identity(Path project, Map<String, String> existing) {
        Map<String, String> identity = new LinkedHashMap<>();
        Path metadata = project.resolve("src/main/resources/fabric.mod.json");
        String json = null;

        if (Files.isRegularFile(metadata)) {
            try {
                json = Files.readString(metadata, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                json = null;
            }
        }

        String directory = project.getFileName() == null ? "mod" : project.getFileName().toString();
        String id = first(json, MOD_JSON_ID);
        String name = first(json, MOD_JSON_NAME);

        if (id == null) {
            id = existing.getOrDefault("mod_id", existing.getOrDefault("archives_base_name", directory));
        }

        identity.put("mod_id", id);
        identity.put("mod_name", name == null ? existing.getOrDefault("mod_name", id) : name);
        identity.put("mod_version", existing.getOrDefault("mod_version",
                existing.getOrDefault("version", "1.0.0")));
        identity.put("maven_group", existing.getOrDefault("maven_group",
                existing.getOrDefault("group", "com.example")));
        identity.put("archives_base_name", existing.getOrDefault("archives_base_name", directory));

        return identity;
    }

    /**
     * The version whose sources the project's own are.
     *
     * <p>The preprocessor attaches the shared source tree to one version and derives the others from it, so
     * this has to name a version in the list. The version the project already builds is the one its sources
     * have been written against, which is the only answer here that is the project's rather than a guess.
     */
    static String mainProject(List<String> versions, Map<String, String> existing) {
        String current = existing.get("minecraft_version");

        if (current != null && versions.contains(current)) {
            return current;
        }

        return versions.get(0);
    }

    /** The properties of a file, in the order they are written. */
    static Map<String, String> properties(Path file) throws IOException {
        Map<String, String> properties = new LinkedHashMap<>();

        if (!Files.isRegularFile(file)) {
            return properties;
        }

        for (String line : text(file).split("\n", -1)) {
            Matcher matcher = PROPERTY.matcher(line);

            if (matcher.matches()) {
                properties.put(matcher.group(1), matcher.group(2));
            }
        }

        return properties;
    }

    private static String valueOf(Map<String, String> existing, Map<String, String> identity, String name) {
        if (identity.containsKey(name)) {
            return identity.get(name);
        }

        String direct = existing.get(name);

        if (direct != null) {
            return direct;
        }

        for (Map.Entry<String, String> alias : PROPERTY_ALIASES.entrySet()) {
            if (alias.getValue().equals(name)) {
                String aliased = existing.get(alias.getKey());

                if (aliased != null) {
                    return aliased;
                }
            }
        }

        return null;
    }

    static String rootProjectName(String settings) {
        Matcher matcher = Pattern.compile("(?m)^\\s*rootProject\\.name\\s*=\\s*['\"]([^'\"]+)['\"]")
                .matcher(settings);

        return matcher.find() ? matcher.group(1) : null;
    }

    private static String first(String text, Pattern pattern) {
        if (text == null) {
            return null;
        }

        Matcher matcher = pattern.matcher(text);

        return matcher.find() ? matcher.group(1) : null;
    }

    /** The index of the brace closing the one at {@code open}. */
    private static int matchingBrace(String text, int open) {
        int depth = 0;

        for (int index = open; index < text.length(); index++) {
            char character = text.charAt(index);

            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;

                if (depth == 0) {
                    return index;
                }
            }
        }

        return -1;
    }

    private static void keep(Path directory, String name) throws IOException {
        Path file = directory.resolve(name);

        if (Files.isRegularFile(file)) {
            Files.copy(file, directory.resolve(name + BACKUP_SUFFIX), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * A file's text, with its line endings taken out of the picture.
     *
     * <p>A checkout on Windows has carriage returns in it, and a pattern anchored at both ends of a line does
     * not match a line that ends in one - it matches up to the carriage return and then fails, because the
     * match has to cover the whole line. Every property would be read as absent and every line written back
     * unchanged, and nothing would say so. Read once, here, rather than guarded against in each reader.
     */
    static String text(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("缺少 " + file.getFileName());
        }

        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static void write(Path file, String contents) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents, StandardCharsets.UTF_8);
    }
}
