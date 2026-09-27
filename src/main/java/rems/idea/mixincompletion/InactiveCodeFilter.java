package rems.idea.mixincompletion;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps the analysis of inactive code out of the editor.
 *
 * <p>Injected fragments are real Java, so the platform analyses them like any other file, and it analyses
 * them against the version being compiled. A branch written for a different game version then reports
 * everything that version moved: types that do not exist here, members that were renamed, calls whose
 * signatures changed. Every one of those findings is correct about the text and wrong about the file.
 *
 * <p>The parser's own complaints belong to the same group and are the noisier half of it. A fragment is not
 * a compilation unit: a {@code //$$} run of imports ends up in a class body an import could never appear
 * in, and a {@code //#replace} tail begins at the dot continuing a call written on the line above. Neither
 * parses as a file, and nothing is wrong with the code that says so.
 *
 * <p>A finding reaches this filter by one of two routes, and both have to be covered. The fragment's own
 * analysis files it against the fragment; the pass that walks the host file reaches into the injected code
 * as well, and what it concludes there is filed against the <em>host</em>. The second route is the one that
 * used to get through, and it is the larger half.
 */
public final class InactiveCodeFilter implements HighlightInfoFilter {
   @Override
   public boolean accept(@NotNull HighlightInfo info, @Nullable PsiFile file) {
      if (file == null) {
         return true;
      }

      // The colouring this plugin applies is INFORMATION and has to survive everything below.
      if (info.getSeverity() == HighlightSeverity.INFORMATION) {
         return true;
      }

      Project project = file.getProject();
      InjectedLanguageManager manager = InjectedLanguageManager.getInstance(project);
      boolean fragment = manager.isInjectedFragment(file);
      PsiFile host = fragment ? manager.getTopLevelFile(file) : file;

      if (!(host instanceof PsiJavaFile)) {
         return true;
      }

      // What a fragment has to say is only listened to when a check said it.
      //
      // A finding from a check carries the id of the check that made it, and the checks wanted here are the
      // ones that know this dialect: the mixin annotations read against the class being mixed into, the
      // injection signature, the at value. Everything else said about a fragment is the platform reading it as
      // a file - an import in a class body, a call continued from the line above, a name that resolves in
      // another version - and none of that is about the code.
      if (fragment) {
         String tool = info.getInspectionToolId();

         if (tool == null || tool.isEmpty()) {
            return false;
         }
      }

      Document document = PsiDocumentManager.getInstance(project).getDocument(host);

      if (document == null) {
         return true;
      }

      int version = PreprocessorLanguage.moduleVersionCode(host);
      int offset = Math.max(0, Math.min(info.getStartOffset(), document.getTextLength()));

      // With no version to read, the older rule stands: the host's findings count when they land inside a
      // marker, which is where the carried code is.
      if (version < 0) {
         return !MixinCommentContext.isHiddenOffset(document, offset);
      }

      // And a finding about a line the build in hand compiles is about the reader's own code.
      return PreprocessorLanguage.isLineActive(document, document.getLineNumber(offset), Map.of("MC", version));
   }
}
