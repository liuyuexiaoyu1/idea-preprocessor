package rems.idea.mixincompletion;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Says what the plugin makes of the place the caret is in, and what it would offer there.
 *
 * <p>Completion that offers nothing has several causes that look the same from outside: the place not read as
 * one where code can be written, a name that does not resolve, a module that cannot be found - and each of
 * them is decided somewhere else in the plugin. This asks the same questions the completion does and shows the
 * answers, so a report of "nothing is offered here" can be turned into the step that answered no.
 *
 * <p>Shown in a dialog rather than written to the log: the log is where a reader does not look, and the
 * question is about one place in one file that only they can point at.
 */
public final class DumpCompletionAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);

        if (project == null || editor == null) {
            return;
        }

        Document document = editor.getDocument();
        int length = document.getTextLength();
        int offset = Math.max(0, Math.min(editor.getCaretModel().getOffset(), length));
        int line = document.getLineNumber(offset);
        int lineStart = document.getLineStartOffset(line);
        int lineEnd = document.getLineEndOffset(line);
        PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(document);
        String logical = MixinCommentContext.logicalContext(document, offset);
        PsiElement element = file == null ? null : file.findElementAt(Math.max(0, offset - 1));
        String qualifier = VersionedCodeFragmentResolver.qualifierBefore(document, offset);
        List<LookupElement> items = null;
        String failure = null;

        try {
            items = qualifier != null
                    ? VersionedCodeFragmentResolver.memberVariants(file, element, document, line, offset, qualifier, null)
                    : VersionedCodeFragmentResolver.scopeVariants(file, element, document, line, offset, logical, null);
        } catch (RuntimeException problem) {
            failure = problem.toString();
        }

        StringBuilder report = new StringBuilder();
        report.append("行 ").append(line + 1).append("：")
                .append(document.getImmutableCharSequence().subSequence(lineStart, lineEnd).toString().replace("\t", "\\t")).append('\n');
        report.append("元素：").append(element == null ? "-" : element.getClass().getSimpleName()).append('\n');
        report.append("逻辑代码：").append(logical == null ? "-" : "[" + logical + "]").append('\n');
        report.append("补全位置：")
                .append(MixinCommentContext.isCompletionPosition(document, offset, logical) ? "是" : "否").append('\n');
        report.append("前置指令位置：")
                .append(MixinCommentContext.isPreprocessorCompletionPosition(document, offset) ? "是" : "否").append('\n');
        report.append("限定符：").append(qualifier == null ? "-" : "[" + qualifier + "]").append('\n');

        if (failure != null) {
            report.append("算出条目时抛异常：").append(failure).append('\n');
        } else if (items == null) {
            report.append("条目：这一处不提供补全（返回空判断）\n");
        } else {
            report.append("条目数：").append(items.size()).append('\n');

            for (int index = 0; index < Math.min(12, items.size()); index++) {
                report.append("  ").append(items.get(index).getLookupString()).append('\n');
            }
        }

        Messages.showInfoMessage(project, report.toString(), "Preprocessor：本处补全");
    }
}
