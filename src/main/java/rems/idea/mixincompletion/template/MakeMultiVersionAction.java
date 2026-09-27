package rems.idea.mixincompletion.template;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a project that builds against one Minecraft version into one that builds against several.
 *
 * <p>The work is in {@link MultiVersionLayout}, which knows nothing about an IDE and can therefore be run
 * against a real checkout and read afterwards. What is here is the part that needs one: which project the
 * reader is looking at, asking them for a range, and saying what was done.
 */
public final class MakeMultiVersionAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(MakeMultiVersionAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile root = project == null ? null : project.getBaseDir();

        if (project == null || root == null) {
            return;
        }

        if (MultiVersionLayout.isMultiVersion(Path.of(root.getPath()))) {
            Messages.showInfoMessage(project,
                    "这个项目已经是多版本项目了，不再转换。\n\n"
                            + "要重新转换，先删掉 settings.json 与 versions 目录，"
                            + "再用 *" + MultiVersionLayout.BACKUP_SUFFIX + " 备份恢复 build.gradle、"
                            + "settings.gradle 和 gradle.properties。",
                    "转换为多版本项目");

            return;
        }

        VirtualFile source = TemplateRepository.root(MultiVersionTemplateProvider.REPOSITORY);

        if (source == null) {
            Messages.showErrorDialog(project, "取不到模板仓库，请检查网络后重试。", "转换为多版本项目");

            return;
        }

        List<String> supported = TemplateRepository.versions(source);

        if (supported.isEmpty()) {
            Messages.showErrorDialog(project, "模板仓库没有列出任何版本。", "转换为多版本项目");

            return;
        }

        String from = Messages.showInputDialog(project,
                "起始版本（可选版本：" + String.join("、", supported) + "）", "转换为多版本项目",
                null, supported.get(0), null);

        if (from == null) {
            return;
        }

        String to = Messages.showInputDialog(project, "结束版本（留空则到列表末尾）", "转换为多版本项目",
                null, supported.get(supported.size() - 1), null);

        if (to == null) {
            return;
        }

        List<String> chosen = TemplateRepository.range(supported, from, to);

        if (chosen.isEmpty()) {
            Messages.showErrorDialog(project, "选出的版本范围是空的。", "转换为多版本项目");

            return;
        }

        List<String> notes = new ArrayList<>();

        try {
            convert(project, root, Path.of(source.getPath()), chosen, notes);
        } catch (IOException | RuntimeException failure) {
            LOG.warn("preprocessor template: could not convert the project", failure);
            Messages.showErrorDialog(project, "转换失败：" + failure.getMessage(), "转换为多版本项目");

            return;
        }

        Messages.showInfoMessage(project, "已转换为多版本项目，包含 " + chosen.size() + " 个版本。\n"
                + "原来的配置保留为 *" + MultiVersionLayout.BACKUP_SUFFIX + "。\n\n"
                + summarize(notes) + "\n\n请同步一次 Gradle，然后检查 common.gradle 里的依赖和资源路径。",
                "转换为多版本项目");
    }

    /**
     * Offered where it can be used, and shown but unavailable where it has already been used.
     *
     * <p>Left on the menu rather than taken out of it. A command that disappears leaves the reader looking for
     * it and finding nothing, which is what makes them doubt the plugin rather than the state of the project;
     * one that is there and greyed out says which of the two it is. Only a project this can do nothing at all
     * to - one without a build script - is left out.
     */
    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile root = project == null ? null : project.getBaseDir();

        if (root == null || root.findChild("build.gradle") == null) {
            event.getPresentation().setEnabledAndVisible(false);

            return;
        }

        event.getPresentation().setEnabledAndVisible(true);
        event.getPresentation().setEnabled(!MultiVersionLayout.isMultiVersion(Path.of(root.getPath())));
    }

    /** Writes the layout, then says what it decided. */
    private static void convert(Project project, VirtualFile root, Path template, List<String> chosen,
                                List<String> notes) throws IOException {
        String preprocessor = preprocessorVersion(template);

        WriteCommandAction.runWriteCommandAction(project, "转换为多版本项目", null, () -> {
            try {
                MultiVersionLayout.convert(Path.of(root.getPath()), template, chosen, preprocessor, notes);
            } catch (IOException failure) {
                throw new RuntimeException(failure.getMessage(), failure);
            }
        });
    }

    /**
     * The preprocessor the converted project builds against.
     *
     * <p>The newest build jitpack has, and the template's own pin when jitpack cannot be asked. A conversion
     * that cannot reach the network still has to produce a project, and the template's pin is a version that
     * built.
     */
    private static String preprocessorVersion(Path template) {
        String latest = JitpackVersions.latest(MultiVersionLayout.PREPROCESSOR);

        if (latest != null) {
            return latest;
        }

        try {
            return MultiVersionLayout.pinnedPreprocessor(template);
        } catch (IOException failure) {
            LOG.warn("preprocessor template: could not read the pinned preprocessor version", failure);

            return "bc74432";
        }
    }

    /** The decisions, as far as a dialog can hold them. */
    private static String summarize(List<String> notes) {
        return String.join("\n", notes.stream().limit(8).toList());
    }
}
