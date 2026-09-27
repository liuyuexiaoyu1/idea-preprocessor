package rems.idea.mixincompletion;

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate;
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate.Result;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class PreprocessorEnterHandler implements EnterHandlerDelegate {
   @NotNull
   public EnterHandlerDelegate.@NotNull Result preprocessEnter(@NotNull PsiFile file, @NotNull Editor editor, @NotNull Ref<Integer> caretOffset, @NotNull Ref<Integer> caretAdvance, @NotNull DataContext dataContext, @Nullable EditorActionHandler originalHandler) {

      EnterHandlerDelegate.Result var10000 = Result.Continue;

      return var10000;
   }

   @NotNull
   public EnterHandlerDelegate.@NotNull Result postProcessEnter(@NotNull PsiFile file, @NotNull Editor editor, @NotNull DataContext dataContext) {

      if (file instanceof PsiJavaFile && MixinCompletionSettings.getInstance().isAutoInsertPreprocessorComment()) {
         Document document = editor.getDocument();
         int offset = editor.getCaretModel().getOffset();
         int line = document.getLineNumber(Math.min(offset, document.getTextLength()));
         if (!insidePreprocessorBlock(document, line)) {
            EnterHandlerDelegate.Result var22 = Result.Continue;

            return var22;
         } else {
            int lineStart = document.getLineStartOffset(line);
            int lineEnd = document.getLineEndOffset(line);
            String lineText = document.getImmutableCharSequence().subSequence(lineStart, lineEnd).toString();

            int indentLength;
            for(indentLength = 0; indentLength < lineText.length() && Character.isWhitespace(lineText.charAt(indentLength)); ++indentLength) {
            }

            String content = lineText.substring(indentLength);
            String previousPrefix = previousCodeMarkerPrefix(document, line);
            int mainVersion = PreprocessorLanguage.mainVersionCode(file);
            boolean needsMarkers = mainVersion >= 0 ? !PreprocessorLanguage.isLineActive(document, line, Map.of("MC", mainVersion)) : !previousPrefix.isEmpty();
            if (!needsMarkers) {
               removeGeneratedCommentPrefix(document, editor, lineStart, lineEnd, indentLength, content);
               EnterHandlerDelegate.Result var21 = Result.Continue;

               return var21;
            } else {
               int depth = PreprocessorLanguage.conditionalDepthBeforeLine(document, line);
               if (depth <= 0) {
                  EnterHandlerDelegate.Result var20 = Result.Continue;

                  return var20;
               } else {
                  String expectedPrefix = PreprocessorLanguage.codePrefixBeforeLine(document, line);
                  if (expectedPrefix.isEmpty()) {
                     expectedPrefix = previousPrefix;
                  }

                  expectedPrefix = preservePreviousCodeIndent(document, line, expectedPrefix);
                  if (content.startsWith("//$$")) {
                     int prefixEnd = leadingMarkerPrefixEnd(content);
                     document.replaceString(lineStart + indentLength, lineStart + indentLength + prefixEnd, expectedPrefix);
                     editor.getCaretModel().moveToOffset(lineStart + indentLength + expectedPrefix.length());
                  } else if (content.startsWith("// ")) {
                     document.replaceString(lineStart + indentLength, lineStart + indentLength + 3, expectedPrefix);
                     editor.getCaretModel().moveToOffset(lineStart + indentLength + expectedPrefix.length());
                  } else if (content.isEmpty()) {
                     document.insertString(lineStart + indentLength, expectedPrefix);
                     editor.getCaretModel().moveToOffset(lineStart + indentLength + expectedPrefix.length());
                  }

                  EnterHandlerDelegate.Result var19 = Result.Continue;

                  return var19;
               }
            }
         }
      } else {
         EnterHandlerDelegate.Result var10000 = Result.Continue;

         return var10000;
      }
   }

   private static String preservePreviousCodeIndent(Document document, int currentLine, String markerPrefix) {
      if (currentLine > 0 && !markerPrefix.isEmpty()) {
         int start = document.getLineStartOffset(currentLine - 1);
         int end = document.getLineEndOffset(currentLine - 1);
         String previous = document.getImmutableCharSequence().subSequence(start, end).toString().stripLeading();
         if (!previous.startsWith(markerPrefix)) {
            return markerPrefix;
         } else {
            int cursor;
            for(cursor = markerPrefix.length(); cursor < previous.length(); ++cursor) {
               char value = previous.charAt(cursor);
               if (value != ' ' && value != '\t') {
                  break;
               }
            }

            return markerPrefix + previous.substring(markerPrefix.length(), cursor);
         }
      } else {
         return markerPrefix;
      }
   }

   private static void removeGeneratedCommentPrefix(Document document, Editor editor, int lineStart, int lineEnd, int indentLength, String content) {
      String rest;
      for(rest = content; rest.startsWith("//$$"); rest = rest.substring(4).stripLeading()) {
      }

      if (rest.equals("//")) {
         rest = "";
      } else if (rest.startsWith("//") && rest.substring(2).isBlank()) {
         rest = "";
      }

      if (rest.isBlank()) {
         document.deleteString(lineStart + indentLength, lineEnd);
         editor.getCaretModel().moveToOffset(lineStart + indentLength);
      }
   }

   private static String previousCodeMarkerPrefix(Document document, int currentLine) {
      if (currentLine <= 0) {
         return "";
      } else {
         int start = document.getLineStartOffset(currentLine - 1);
         int end = document.getLineEndOffset(currentLine - 1);
         String text = document.getImmutableCharSequence().subSequence(start, end).toString().stripLeading();
         return !text.startsWith("//$$") ? "" : text.substring(0, leadingMarkerPrefixEnd(text));
      }
   }

   private static int leadingMarkerPrefixEnd(String text) {
      int cursor = 0;

      while(cursor + 4 <= text.length() && text.startsWith("//$$", cursor)) {
         for(cursor += 4; cursor < text.length() && Character.isWhitespace(text.charAt(cursor)); ++cursor) {
         }
      }

      return cursor;
   }

   private static boolean insidePreprocessorBlock(Document document, int currentLine) {
      int depth = PreprocessorLanguage.conditionalDepthBeforeLine(document, currentLine);
      return depth > 0 && PreprocessorLanguage.hasClosingConditional(document, currentLine, depth);
   }

   // $FF: synthetic method
   
}
