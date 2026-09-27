package rems.idea.mixincompletion;

import com.intellij.codeInspection.InspectionSuppressor;
import com.intellij.codeInspection.SuppressQuickFix;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Silences inspections inside code that is not compiled on this version.
 *
 * <p>Dropping the highlighting findings is not enough. Inspections are a pass of their own and never reach
 * the highlighting filter, so a branch written for another version keeps producing them: a generic used raw
 * because the type it is written against was not parameterised yet, an annotation whose check only holds for
 * live code. Each of those is true about the text and wrong about the file, and together they bury the ones
 * that matter - a branch is exactly where a real mistake is hardest to notice.
 *
 * <p>Like the highlighting filter this has two routes to cover. An inspection that ran on the fragment files
 * its result against the fragment; one that ran on the host while that pass walked into the injected code
 * files it against the host. Both are about carried code, and the second is the one the annotation checks
 * were getting through on.
 */
public final class InactiveInspectionSuppressor implements InspectionSuppressor {
   @Override
   public boolean isSuppressedFor(@NotNull PsiElement element, @NotNull String toolId) {
      PsiFile file = element.getContainingFile();

      if (file == null) {
         return false;
      }

      Project project = file.getProject();
      InjectedLanguageManager injected = InjectedLanguageManager.getInstance(project);

      // The fragment's own inspections, all of which are about code that is not built here.
      if (injected.isInjectedFragment(file)) {
         return true;
      }

      // And the ones filed against the host, which the marker text decides - the same question the
      // highlighting filter asks, answered from the same place so the two cannot disagree.
      Document document = PsiDocumentManager.getInstance(project).getDocument(file);

      if (document == null) {
         return false;
      }

      int offset = Math.max(0, element.getTextRange().getStartOffset());

      if (offset > document.getTextLength()) {
         return false;
      }

      return MixinCommentContext.isHiddenOffset(document, offset);
   }

   @Override
   public SuppressQuickFix @NotNull [] getSuppressActions(@Nullable PsiElement element, @NotNull String toolId) {
      return SuppressQuickFix.EMPTY_ARRAY;
   }
}
