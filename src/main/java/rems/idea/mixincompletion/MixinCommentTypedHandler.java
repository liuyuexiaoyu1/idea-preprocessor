package rems.idea.mixincompletion;

import com.intellij.codeInsight.completion.CodeCompletionHandlerBase;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;

public final class MixinCommentTypedHandler extends TypedHandlerDelegate {
   private static final Logger LOG = Logger.getInstance(MixinCommentTypedHandler.class);
   private static final Pattern COMPLETE_MC_EXPRESSION = Pattern.compile("^\\s*(?://\\$\\$\\s*)*//#(?:if|elseif|elif|replace)\\s+MC\\s*$");

   @NotNull
   public TypedHandlerDelegate.@NotNull Result beforeCharTyped(char charTyped, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file, @NotNull FileType fileType) {

      if (!(file instanceof PsiJavaFile)) {
         TypedHandlerDelegate.Result var17 = Result.CONTINUE;

         return var17;
      } else {
         int caretOffset = editor.getCaretModel().getOffset();
         if (LookupManager.getActiveLookup(editor) != null && (MixinCommentContext.isPreprocessorCompletionPosition(editor.getDocument(), caretOffset) || MixinCommentContext.isCompletionPosition(editor.getDocument(), caretOffset))) {
            LookupManager.getInstance(project).hideActiveLookup();
         }

         if ("(){}[]\"".indexOf(charTyped) < 0) {
            TypedHandlerDelegate.Result var16 = Result.CONTINUE;

            return var16;
         } else {
            Document document = editor.getDocument();
            int line = document.getLineNumber(Math.min(caretOffset, document.getTextLength()));
            document.getLineStartOffset(line);
            String beforeCaret = MixinCommentContext.javaCodeBeforeCaret(document, caretOffset);
            if (beforeCaret == null) {
               TypedHandlerDelegate.Result var15 = Result.CONTINUE;

               return var15;
            } else if (")}]}\"".indexOf(charTyped) >= 0 && caretOffset < document.getTextLength() && document.getCharsSequence().charAt(caretOffset) == charTyped) {
               editor.getCaretModel().moveToOffset(caretOffset + 1);
               TypedHandlerDelegate.Result var14 = Result.STOP;

               return var14;
            } else if (charTyped == '"') {
               if ((countUnescapedQuotes(beforeCaret) & 1) != 0) {
                  TypedHandlerDelegate.Result var13 = Result.CONTINUE;

                  return var13;
               } else {
                  TypedHandlerDelegate.Result var12 = insertPair(document, editor, caretOffset, "\"\"");

                  return var12;
               }
            } else {
               TypedHandlerDelegate.Result var10000;
               switch (charTyped) {
                  case '(' -> var10000 = insertPair(document, editor, caretOffset, "()");
                  case '[' -> var10000 = insertPair(document, editor, caretOffset, "[]");
                  case '{' -> var10000 = insertPair(document, editor, caretOffset, "{}");
                  default -> var10000 = Result.CONTINUE;
               }

               return var10000;
            }
         }
      }
   }

   @NotNull
   public TypedHandlerDelegate.@NotNull Result charTyped(char charTyped, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {

      if (!(file instanceof PsiJavaFile)) {
         TypedHandlerDelegate.Result var12 = Result.CONTINUE;

         return var12;
      } else if (charTyped == ';') {
         ConditionalImportMerger.mergeIfNeeded(project, editor.getDocument(), (PsiJavaFile)file, editor.getCaretModel().getOffset());
         TypedHandlerDelegate.Result var11 = Result.CONTINUE;

         return var11;
      } else if (!isCompletionCharacter(charTyped)) {
         TypedHandlerDelegate.Result var10 = Result.CONTINUE;

         return var10;
      } else {
         int offset = editor.getCaretModel().getOffset();
         if (appendDirectiveSpace(editor.getDocument(), editor, offset)) {
            TypedHandlerDelegate.Result var9 = Result.CONTINUE;

            return var9;
         } else if (MixinCommentContext.isPreprocessorCompletionPosition(editor.getDocument(), offset)) {
            schedulePreprocessorLookup(project, editor, file);
            TypedHandlerDelegate.Result var8 = Result.CONTINUE;

            return var8;
         } else if (!MixinCommentContext.isCompletionPosition(editor.getDocument(), offset)) {
            TypedHandlerDelegate.Result var7 = Result.CONTINUE;

            return var7;
         } else {
            LookupElement[] items = MixinDescriptorCompletionContributor.commentItems(file, editor.getDocument(), offset);
            LOG.info("//$$ completion candidates: " + items.length);
            showLookup(project, editor, items);
            TypedHandlerDelegate.Result var10000 = Result.CONTINUE;

            return var10000;
         }
      }
   }

   private static boolean isCompletionCharacter(char value) {
      return Character.isJavaIdentifierPart(value) || ".,@#<>=!&|[?".indexOf(value) >= 0;
   }

   private static void showLookup(Project project, Editor editor, LookupElement[] items) {
      LookupManager lookupManager = LookupManager.getInstance(project);
      if (LookupManager.getActiveLookup(editor) != null) {
         lookupManager.hideActiveLookup();
      }

      if (items.length > 0) {
         lookupManager.showLookup(editor, items);
      }

   }

   private static void schedulePreprocessorLookup(Project project, Editor editor, PsiFile file) {
      ApplicationManager.getApplication().invokeLater(() -> {
         if (!editor.isDisposed() && !project.isDisposed()) {
            Document document = editor.getDocument();
            int offset = Math.min(editor.getCaretModel().getOffset(), document.getTextLength());
            if (MixinCommentContext.isPreprocessorCompletionPosition(document, offset)) {
               int line = document.getLineNumber(offset);
               int lineStart = document.getLineStartOffset(line);
               String beforeCaret = document.getImmutableCharSequence().subSequence(lineStart, offset).toString();
               LookupElement[] items = MixinDescriptorCompletionContributor.preprocessorItems(file, beforeCaret);
               if (items.length > 0 && COMPLETE_MC_EXPRESSION.matcher(beforeCaret).matches()) {
                  CodeCompletionHandlerBase.createHandler(CompletionType.BASIC).invokeCompletion(project, editor, 1);
               } else {
                  showLookup(project, editor, items);
               }
            }
         }
      });
   }

   private static boolean appendDirectiveSpace(Document document, Editor editor, int offset) {
      int line = document.getLineNumber(Math.min(offset, document.getTextLength()));
      int lineStart = document.getLineStartOffset(line);
      String beforeCaret = document.getImmutableCharSequence().subSequence(lineStart, offset).toString().stripLeading();
      PreprocessorLanguage.ParsedDirective parsed = PreprocessorLanguage.parseDirective(beforeCaret);
      if (parsed != null && parsed.argument().isEmpty()) {
         PreprocessorLanguage.Directive directive = PreprocessorLanguage.directive(parsed.name());
         if (directive != null && directive.argument() != PreprocessorLanguage.Argument.NONE) {
            document.insertString(offset, " ");
            editor.getCaretModel().moveToOffset(offset + 1);
            return true;
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private static TypedHandlerDelegate.Result insertPair(Document document, Editor editor, int offset, String pair) {
      document.insertString(offset, pair);
      editor.getCaretModel().moveToOffset(offset + 1);
      return Result.STOP;
   }

   private static int countUnescapedQuotes(String text) {
      int unescapedQuotes = 0;

      for(int i = 0; i < text.length(); ++i) {
         if (text.charAt(i) == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
            ++unescapedQuotes;
         }
      }

      return unescapedQuotes;
   }

   // $FF: synthetic method
   
}
