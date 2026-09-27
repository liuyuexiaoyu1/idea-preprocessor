package rems.idea.mixincompletion;

import com.intellij.codeInsight.completion.CompletionConfidence;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.ThreeState;
import org.jetbrains.annotations.NotNull;

public final class MixinCommentCompletionConfidence extends CompletionConfidence {
   public @NotNull ThreeState shouldSkipAutopopup(@NotNull Editor editor, @NotNull PsiElement contextElement, @NotNull PsiFile psiFile, int offset) {

      ThreeState var10000 = !MixinCommentContext.isCompletionPosition(editor.getDocument(), offset) && !MixinCommentContext.isPreprocessorCompletionPosition(editor.getDocument(), offset) ? ThreeState.UNSURE : ThreeState.NO;

      return var10000;
   }

   // $FF: synthetic method
   
}
