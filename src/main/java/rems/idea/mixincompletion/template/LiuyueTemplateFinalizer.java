package rems.idea.mixincompletion.template;

import com.demonwav.mcdev.creator.custom.finalizers.CreatorFinalizer;
import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns the template repository into the project the reader asked for.
 *
 * <p>The files are written here rather than declared in the template because the layout depends on the answer
 * the reader gave. The repository holds every version it supports, and a project that builds against all of
 * them is not what most readers want: the range decides which version directories are part of this project,
 * and that is known only once the form has been filled in.
 *
 * <p>The preprocessor the project is built with is the one jitpack built most recently. The template names a
 * commit, because that is how that plugin is published - so a template carries a version that was current
 * when it was written, and a project started from it would otherwise begin several builds behind.
 */
public final class LiuyueTemplateFinalizer implements CreatorFinalizer {
    private static final Logger LOG = Logger.getInstance(LiuyueTemplateFinalizer.class);

    /** The plugin the generated project builds with, as jitpack spells the coordinate. */
    private static final String PREPROCESSOR = "liuyuexiaoyu1/preprocessor";

    /** The line naming that plugin and the commit it is taken at. */
    private static final Pattern PREPROCESSOR_VERSION = Pattern.compile(
            "(id\\s+'com\\.replaymod\\.preprocess'\\s+version\\s+')([^']*)(')");

    @Override
    public Object execute(@NotNull WizardContext context, @NotNull Project project,
                          @NotNull Map<String, ?> templateProperties,
                          @NotNull Map<String, ?> globalProperties,
                          @NotNull Continuation<? super Unit> continuation) {
        try {
            generate(context, templateProperties);
        } catch (RuntimeException | IOException failure) {
            // The wizard has already made a project directory by this point. Failing the whole creation over
            // a template that could not be laid out would leave the reader with neither a project nor an
            // explanation; the directory with the files that were written is the more useful outcome.
            LOG.warn("preprocessor template: could not lay out the project", failure);
        }

        continuation.resumeWith(Unit.INSTANCE);

        return Unit.INSTANCE;
    }

    private static void generate(WizardContext context, Map<String, ?> properties) throws IOException {
        VirtualFile source = TemplateRepository.root(MultiVersionTemplateProvider.REPOSITORY);

        if (source == null) {
            return;
        }

        List<String> supported = TemplateRepository.versions(source);
        List<String> chosen = TemplateRepository.range(supported,
                text(properties.get(MultiVersionTemplateProvider.FROM_VERSION)),
                text(properties.get(MultiVersionTemplateProvider.TO_VERSION)));

        if (chosen.isEmpty()) {
            LOG.warn("preprocessor template: the repository lists no versions to build against");

            return;
        }

        Path target = Path.of(context.getProjectFileDirectory());

        copy(source, target, chosen);
        writeVersionList(target, chosen);
        writeMainProject(target, chosen);
        pointAtLatestPreprocessor(target);

        LOG.info("preprocessor template: laid out " + chosen.size() + " version(s) in " + target);
    }

    /**
     * Copies the repository, leaving out the versions that were not asked for.
     *
     * <p>Everything outside {@code versions/} is shared by every version and is copied as it stands. The
     * version directories are the only part a range applies to - which is the whole point of the layout.
     */
    private static void copy(VirtualFile source, Path target, List<String> chosen) throws IOException {
        Path from = Path.of(source.getPath());

        try (Stream<Path> tree = Files.walk(from)) {
            for (Path path : tree.toList()) {
                String relative = from.relativize(path).toString().replace('\\', '/');

                if (relative.isEmpty()) {
                    continue;
                }

                String version = versionDirectoryOf(relative);

                if (version != null && !chosen.contains(version)) {
                    continue;
                }

                Path destination = target.resolve(relative);

                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Path parent = destination.getParent();

                    if (parent != null) {
                        Files.createDirectories(parent);
                    }

                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** The version a path belongs to, or null when it lies outside the version directories. */
    private static String versionDirectoryOf(String relative) {
        if (!relative.startsWith("versions/")) {
            return null;
        }

        String rest = relative.substring("versions/".length());
        int slash = rest.indexOf('/');

        return slash < 0 ? null : rest.substring(0, slash);
    }

    /**
     * Rewrites the version list the build reads.
     *
     * <p>This is what makes the project a project of the chosen versions: the settings file is the one place
     * that decides which subprojects exist, and a list still naming versions whose directories were left
     * behind describes a build that cannot run.
     */
    private static void writeVersionList(Path target, List<String> chosen) throws IOException {
        StringBuilder json = new StringBuilder("{\n  \"versions\": [\n");

        for (int index = 0; index < chosen.size(); index++) {
            json.append("    \"").append(chosen.get(index)).append('"');

            if (index < chosen.size() - 1) {
                json.append(',');
            }

            json.append('\n');
        }

        json.append("  ]\n}\n");

        write(target.resolve("settings.json"), json.toString());
    }

    /**
     * Names the version the project is developed against.
     *
     * <p>Taken as the lowest of the chosen ones, which is the version a change has to be written for first:
     * the code is written once and adapted upwards, so the oldest is where the source has to compile.
     */
    private static void writeMainProject(Path target, List<String> chosen) throws IOException {
        write(target.resolve("versions").resolve("mainProject"), chosen.get(0));
    }

    /**
     * Points the generated build at the preprocessor jitpack built most recently.
     *
     * <p>Left alone when jitpack has not been asked successfully. The template already names a version that
     * worked, and a project that cannot be built because a lookup failed is worse than one that starts on a
     * version a few builds old.
     */
    private static void pointAtLatestPreprocessor(Path target) throws IOException {
        Path build = target.resolve("build.gradle");

        if (!Files.isRegularFile(build)) {
            return;
        }

        String text = Files.readString(build, StandardCharsets.UTF_8);
        Matcher matcher = PREPROCESSOR_VERSION.matcher(text);

        if (!matcher.find()) {
            LOG.warn("preprocessor template: no preprocessor version to update in " + build);

            return;
        }

        String latest = JitpackVersions.latest(PREPROCESSOR);

        if (latest == null || latest.equals(matcher.group(2))) {
            return;
        }

        write(build, text.substring(0, matcher.start(2)) + latest + text.substring(matcher.end(2)));

        LOG.info("preprocessor template: preprocessor version " + matcher.group(2) + " -> " + latest);
    }

    private static void write(Path file, String contents) throws IOException {
        Path parent = file.getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.writeString(file, contents, StandardCharsets.UTF_8);
    }

    private static String text(Object value) {
        return value instanceof String string ? string : "";
    }
}
