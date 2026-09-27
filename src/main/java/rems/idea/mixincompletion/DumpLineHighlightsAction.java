package rems.idea.mixincompletion;

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Writes what is drawn over the line the caret is on into the log.
 *
 * <p>A range that comes out one character short cannot be settled by reading the code that computes it: what
 * is on screen is built from two places at once - the annotations the analysis produced, and the highlighters
 * the editor holds - and either can be the one covering the wrong characters. This writes both, with the text
 * each range covers, so that what a reader sees can be turned into the range that did it.
 *
 * <p>Left in the plugin rather than taken out after the one investigation it was written for. The failure it
 * exists to answer - "the marker is drawn three characters long" - is invisible from inside the test framework,
 * which has no screen, and this is the shortest path from a screen to a range.
 */
public final class DumpLineHighlightsAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(DumpLineHighlightsAction.class);

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
        int start = document.getLineStartOffset(line);
        int end = document.getLineEndOffset(line);
        StringBuilder report = new StringBuilder("\npreprocessor dump: line " + (line + 1) + " = ["
                + document.getImmutableCharSequence().subSequence(start, end).toString().replace("\t", "\\t") + "]\n");

        for (HighlightInfo info : DaemonCodeAnalyzerImpl.getHighlights(document, null, project)) {
            if (info.getEndOffset() < start || info.getStartOffset() > end) {
                continue;
            }

            report.append("  annotation ").append(one(document, info.getStartOffset(), info.getEndOffset()))
                    .append(" severity=").append(info.getSeverity())
                    .append(" key=").append(key(info.forcedTextAttributesKey))
                    .append(" description=").append(info.getDescription())
                    .append('\n');
        }

        for (RangeHighlighter highlighter : editor.getMarkupModel().getAllHighlighters()) {
            if (highlighter.getEndOffset() < start || highlighter.getStartOffset() > end) {
                continue;
            }

            report.append("  highlighter ").append(one(document, highlighter.getStartOffset(), highlighter.getEndOffset()))
                    .append(" layer=").append(highlighter.getLayer())
                    .append(" key=").append(key(highlighter.getTextAttributesKey()))
                    .append('\n');
        }

        // Said at a level that reaches the log without being an error: nothing has gone wrong here, the reader
        // is asking what is drawn.
        LOG.info(report.toString());
    }

    private static String one(Document document, int start, int end) {
        int length = document.getTextLength();
        String text = document.getText().substring(Math.max(0, Math.min(start, length)),
                Math.max(0, Math.min(end, length)));

        return "[" + start + "," + end + ") \"" + text.replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }

    private static String key(TextAttributesKey key) {
        return key == null ? "-" : key.getExternalName();
    }
}
