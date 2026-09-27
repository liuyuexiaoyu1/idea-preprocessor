package rems.idea.mixincompletion;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Where a click in a preprocessor file goes.
 *
 * <p>Two different questions can be asked at one offset, and they have different answers. A name written in
 * carried code has already been resolved - the colouring worked out what it stands for, and which version's
 * copy of it - and that is what a click on the name is asking for. A marker line is a different question: it
 * asks for the other versions of the same piece of code, and there the answer is the branch next to it.
 *
 * <p>Answering the second question first is what stood here, and the effect was that a class name which had
 * been resolved perfectly well jumped to the branch above or below it instead of to the class. The name is
 * asked about first now, and the marker lines are the fallback rather than the answer.
 */
public final class PreprocessorGotoDeclarationHandler implements GotoDeclarationHandler {
   public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement sourceElement, int offset, @Nullable Editor editor) {
      if (sourceElement == null) {
         return null;
      } else {
         PsiFile file = sourceElement.getContainingFile();
         if (file == null) {
            return null;
         } else {
            Project project = file.getProject();

            // Read from the file the comment is written in, not from the fragment the offset may be inside.
            // What a click in carried code is asking about is a comment: the injected fragment has its own
            // view of the line, and the names resolved for it are kept against the comment.
            PsiFile hostFile = InjectedLanguageManager.getInstance(project).getTopLevelFile(file);
            Document document = PsiDocumentManager.getInstance(project).getDocument(hostFile);

            if (document == null || offset < 0 || offset > document.getTextLength()) {
               return null;
            }

            PsiComment comment = commentAt(hostFile, offset);

            if (comment != null) {
               PsiElement carried = carriedTargetAt(comment, offset);

               if (carried != null) {
                  return new PsiElement[]{carried};
               }
            }

            List<Integer> partners = PreprocessorStructure.partners(document, document.getLineNumber(offset));
            if (partners.isEmpty()) {
               return null;
            } else {
               List<PsiElement> targets = new ArrayList();

               for(int line : partners) {
                  PsiComment partner = commentOnLine(hostFile, document, line);
                  if (partner != null) {
                     targets.add(partner);
                  }
               }

               return targets.isEmpty() ? null : (PsiElement[])targets.toArray(PsiElement.EMPTY_ARRAY);
            }
         }
      }
   }

   /**
    * The element the name under an offset was found to stand for.
    *
    * <p>From the names the colouring already resolved, so a name that is coloured as a class is a name that
    * can be jumped to, and both point at the same version's copy of it. Only names that were found are
    * answered: an unresolved name has nothing to go to, and the branch beside the line is a better answer
    * than a click that does nothing. The innermost name wins, because a qualified name is written as several
    * of them and the one the reader means is the one the caret is in.
    */
   private static @Nullable PsiElement carriedTargetAt(PsiComment comment, int offset) {
      TextRange commentRange = comment.getTextRange();
      int at = offset - commentRange.getStartOffset();

      if (at < 0 || at > commentRange.getLength()) {
         return null;
      } else {
         Map<TextRange, PsiElement> names = VersionedBlockAnnotator.namesOf(comment);
         TextRange best = null;
         PsiElement found = null;

         for(Map.Entry<TextRange, PsiElement> entry : names.entrySet()) {
            PsiElement target = entry.getValue();

            if (target == null) {
               continue;
            }

            TextRange range = entry.getKey();
            int start = range.getStartOffset() - commentRange.getStartOffset();
            int end = range.getEndOffset() - commentRange.getStartOffset();

            if (start <= at && at <= end && (best == null || range.getLength() < best.getLength())) {
               best = range;
               found = target;
            }
         }

         return found;
      }
   }

   private static @Nullable PsiComment commentAt(PsiFile file, int offset) {
      PsiElement element = file.findElementAt(offset);
      PsiComment comment = element instanceof PsiComment candidate
              ? candidate
              : (element == null ? null : PsiTreeUtil.getParentOfType(element, PsiComment.class, false));

      if (comment != null && comment.getTextRange().contains(offset)) {
         return comment;
      }

      // A block that holds code is one comment over several lines, so the element under the caret is not
      // always the comment itself; the walk above answers for that, and this is what answers for the rest.
      for(PsiComment candidate : PsiTreeUtil.findChildrenOfType(file, PsiComment.class)) {
         if (candidate.getTextRange().contains(offset)) {
            return candidate;
         }
      }

      return null;
   }

   private static @Nullable PsiComment commentOnLine(PsiFile file, Document document, int line) {
      if (line >= 0 && line < document.getLineCount()) {
         int index = document.getLineStartOffset(line);
         int end = document.getLineEndOffset(line);

         for(CharSequence text = document.getCharsSequence(); index < end && Character.isWhitespace(text.charAt(index)); ++index) {
         }

         PsiElement element;
         for(element = file.findElementAt(index); element != null && !(element instanceof PsiComment); element = element.getParent()) {
         }

         PsiComment var10000;
         if (element instanceof PsiComment) {
            PsiComment comment = (PsiComment)element;
            var10000 = comment;
         } else {
            var10000 = null;
         }

         return var10000;
      } else {
         return null;
      }
   }
}
