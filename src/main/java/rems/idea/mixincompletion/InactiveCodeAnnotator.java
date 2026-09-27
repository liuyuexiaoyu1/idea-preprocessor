package rems.idea.mixincompletion;

import com.intellij.ide.highlighter.JavaFileHighlighter;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import org.jetbrains.annotations.NotNull;

/**
 * Washes the code carried for another version, so that it reads as set aside.
 *
 * <p>Every token of a carried fragment is drawn in a muted form of the colour it would have had: the code is
 * the reader's own code, written for a version other than the one in hand, and the wash is what says so.
 */
public final class InactiveCodeAnnotator implements Annotator {
   private final JavaFileHighlighter highlighter;

   public InactiveCodeAnnotator() {
      this.highlighter = new JavaFileHighlighter(LanguageLevel.HIGHEST);
   }

   public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
      PsiFile file = element.getContainingFile();

      if (file instanceof PsiJavaFile && file.getContext() != null && element.getFirstChild() == null) {
         TextAttributesKey[] keys = this.highlighter.getTokenHighlights(element.getNode().getElementType());

         holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(element.getTextRange())
                 .textAttributes(keys.length == 0 ? InactiveColors.backdrop() : InactiveColors.washed(keys[0]))
                 .create();
      }
   }
}
