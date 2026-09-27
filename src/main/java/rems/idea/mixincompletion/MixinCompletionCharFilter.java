package rems.idea.mixincompletion;

import com.intellij.codeInsight.lookup.CharFilter;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.CharFilter.Result;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiJavaFile;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class MixinCompletionCharFilter extends CharFilter {
   private static final Pattern PRIMARY_VERSION_PREFIX = Pattern.compile("^\\s*(?://\\$\\$\\s*)*//#(?:if|elseif|elif|replace)\\s+M$");

   @Nullable
   public CharFilter.@Nullable Result acceptChar(char value, int prefixLength, Lookup lookup) {
      if (!(lookup.getPsiFile() instanceof PsiJavaFile)) {
         return null;
      } else {
         Editor editor = lookup.getEditor();
         Document document = editor.getDocument();
         int offset = Math.min(editor.getCaretModel().getOffset(), document.getTextLength());
         if (!MixinCommentContext.isPreprocessorCompletionPosition(document, offset) && !MixinCommentContext.isCompletionPosition(document, offset)) {
            return null;
         } else {
            if (value == 'C') {
               int line = document.getLineNumber(offset);
               int lineStart = document.getLineStartOffset(line);
               String beforeCaret = document.getImmutableCharSequence().subSequence(lineStart, offset).toString();
               if (PRIMARY_VERSION_PREFIX.matcher(beforeCaret).matches()) {
                  return Result.HIDE_LOOKUP;
               }
            }

            return !Character.isJavaIdentifierPart(value) && value != '.' ? Result.HIDE_LOOKUP : Result.ADD_TO_PREFIX;
         }
      }
   }
}
