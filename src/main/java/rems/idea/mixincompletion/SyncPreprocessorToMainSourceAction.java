package rems.idea.mixincompletion;

import com.intellij.icons.AllIcons.Actions;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;

public final class SyncPreprocessorToMainSourceAction extends DumbAwareAction {
   private static final Pattern VERSION_SOURCE = Pattern.compile("^(.*)/versions/([^/]+)/(?:build/preprocessed/main/java|src/main/java)/(.+\\.java)$");

   public SyncPreprocessorToMainSourceAction() {
      super("Sync to Main src", "Sync the selected preprocessor block to the matching file in the main src tree", Actions.Refresh);
   }

   public void update(@NotNull AnActionEvent event) {

      Editor editor = (Editor)event.getData(CommonDataKeys.EDITOR);
      VirtualFile file = editor == null ? null : editor.getVirtualFile();
      String selection = editor == null ? null : editor.getSelectionModel().getSelectedText();
      SourceLocation location = file == null ? null : sourceLocation(file);
      boolean available = location != null && selection != null && completeConditionalBlock(selection, versionCode(location.version())) != null;
      event.getPresentation().setEnabledAndVisible(available);
   }

   public void actionPerformed(@NotNull AnActionEvent event) {

      Project project = event.getProject();
      Editor editor = (Editor)event.getData(CommonDataKeys.EDITOR);
      if (project != null && editor != null && editor.getVirtualFile() != null) {
         SourceLocation location = sourceLocation(editor.getVirtualFile());
         SelectionModel selection = editor.getSelectionModel();
         String selectedText = selection.getSelectedText();
         ConditionalBlock block = selectedText != null && location != null ? completeConditionalBlock(selectedText, versionCode(location.version())) : null;
         if (location != null && block != null) {
            if (!Files.isRegularFile(mainProjectMarker(location), new LinkOption[0])) {
               showError(project, "The ReplayMod Preprocessor main project marker was not found.");
            } else {
               Path targetPath = location.root().resolve("src/main/java").resolve(location.relativePath());
               if (!Files.isRegularFile(targetPath, new LinkOption[0])) {
                  showError(project, "The matching main source file does not exist:\n" + String.valueOf(targetPath));
               } else {
                  VirtualFile targetFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(targetPath);
                  Document targetDocument = targetFile == null ? null : FileDocumentManager.getInstance().getDocument(targetFile);
                  if (targetFile != null && targetDocument != null) {
                     String targetText = normalizeNewlines(targetDocument.getText());
                     String wrapped = block.text();
                     int alreadySynced = uniqueIndexOf(targetText, wrapped);
                     if (alreadySynced >= 0) {
                        openTarget(project, targetFile, alreadySynced, alreadySynced + wrapped.length());
                     } else {
                        TextRange replacement = locateReplacement(editor.getDocument(), selection, targetText, block);
                        if (replacement == null) {
                           showError(project, "The insertion point in the main source file is not unique. Keep the unchanged lines immediately before and after the selected block, then try again.");
                        } else {
                           WriteCommandAction.runWriteCommandAction(project, "Sync Preprocessor Block to Main src", (String)null, () -> targetDocument.replaceString(replacement.start(), replacement.end(), replacement.text()), new PsiFile[0]);
                           FileDocumentManager.getInstance().saveDocument(targetDocument);
                           openTarget(project, targetFile, replacement.start(), replacement.start() + replacement.text().length());
                        }
                     }
                  } else {
                     showError(project, "The matching main source file could not be opened:\n" + String.valueOf(targetPath));
                  }
               }
            }
         }
      }
   }

   private static TextRange locateReplacement(Document sourceDocument, SelectionModel selection, String targetText, ConditionalBlock block) {
      String activeCode = block.activeCode();
      int activeIndex = uniqueIndexOf(targetText, activeCode);
      if (activeIndex >= 0) {
         return new TextRange(activeIndex, activeIndex + activeCode.length(), block.text());
      } else {
         int startLine = sourceDocument.getLineNumber(selection.getSelectionStart());
         int endOffset = Math.max(selection.getSelectionStart(), selection.getSelectionEnd() - 1);
         int endLine = sourceDocument.getLineNumber(endOffset);
         LineAnchor before = previousAnchor(sourceDocument, startLine, targetText);
         LineAnchor after = nextAnchor(sourceDocument, endLine, targetText);
         if (before != null && after != null && before.targetEnd() < after.targetStart()) {
            int blankBefore = startLine - before.sourceLine() - 1;
            int blankAfter = after.sourceLine() - endLine - 1;
            String var10000 = "\n".repeat(Math.max(1, blankBefore + 1));
            String replacement = var10000 + block.text() + "\n".repeat(Math.max(1, blankAfter + 1));
            return new TextRange(before.targetEnd(), after.targetStart(), replacement);
         } else {
            return null;
         }
      }
   }

   private static LineAnchor previousAnchor(Document document, int startLine, String targetText) {
      int anchorLine;
      for(anchorLine = startLine - 1; anchorLine >= 0 && lineText(document, anchorLine).isBlank(); --anchorLine) {
      }

      if (anchorLine < 0) {
         return null;
      } else {
         int contextEnd = document.getLineEndOffset(anchorLine);

         for(int firstLine = anchorLine; firstLine >= Math.max(0, anchorLine - 19); --firstLine) {
            int contextStart = document.getLineStartOffset(firstLine);
            String context = document.getImmutableCharSequence().subSequence(contextStart, contextEnd).toString();
            int targetStart = uniqueIndexOf(targetText, context);
            if (targetStart >= 0) {
               return new LineAnchor(anchorLine, targetStart, targetStart + context.length());
            }
         }

         return null;
      }
   }

   private static LineAnchor nextAnchor(Document document, int endLine, String targetText) {
      int anchorLine;
      for(anchorLine = endLine + 1; anchorLine < document.getLineCount() && lineText(document, anchorLine).isBlank(); ++anchorLine) {
      }

      if (anchorLine >= document.getLineCount()) {
         return null;
      } else {
         int contextStart = document.getLineStartOffset(anchorLine);

         for(int lastLine = anchorLine; lastLine < Math.min(document.getLineCount(), anchorLine + 20); ++lastLine) {
            int contextEnd = document.getLineEndOffset(lastLine);
            String context = document.getImmutableCharSequence().subSequence(contextStart, contextEnd).toString();
            int targetStart = uniqueIndexOf(targetText, context);
            if (targetStart >= 0) {
               return new LineAnchor(anchorLine, targetStart, targetStart + context.length());
            }
         }

         return null;
      }
   }

   private static int uniqueIndexOf(String text, String value) {
      if (value.isEmpty()) {
         return -1;
      } else {
         int first = text.indexOf(value);
         if (first < 0) {
            return -1;
         } else {
            return text.indexOf(value, first + 1) < 0 ? first : -2;
         }
      }
   }

   private static ConditionalBlock completeConditionalBlock(String selection, int versionCode) {
      String normalized = trimOuterLineBreaks(normalizeNewlines(selection));
      if (normalized.isEmpty()) {
         return null;
      } else {
         String[] lines = normalized.split("\n", -1);
         if (PreprocessorLanguage.opensConditional(lines[0]) && PreprocessorLanguage.closesConditional(lines[lines.length - 1])) {
            int depth = 0;

            for(int index = 0; index < lines.length; ++index) {
               if (PreprocessorLanguage.opensConditional(lines[index])) {
                  ++depth;
               } else if (PreprocessorLanguage.closesConditional(lines[index])) {
                  --depth;
               }

               if (depth < 0 || depth == 0 && index < lines.length - 1) {
                  return null;
               }
            }

            if (depth != 0) {
               return null;
            } else {
               List<String> activeLines = new ArrayList();
               List<String> allLines = Arrays.asList(lines);

               for(int index = 1; index < lines.length - 1; ++index) {
                  String line = lines[index];
                  PreprocessorLanguage.ParsedDirective directive = PreprocessorLanguage.parseDirective(line);
                  if (directive != null) {
                     if ("import".equals(directive.name()) && PreprocessorLanguage.isLineActive(allLines, index, Map.of("MC", versionCode))) {
                        String indent = line.substring(0, line.indexOf(47));
                        activeLines.add(indent + "import " + directive.argument().replaceFirst("^import\\s+", ""));
                     }
                  } else if (PreprocessorLanguage.isLineActive(allLines, index, Map.of("MC", versionCode))) {
                     String code = MixinCommentContext.codeOfLine(line);
                     if (!code.isEmpty()) {
                        String var10001 = indentOf(line);
                        activeLines.add(var10001 + code);
                     }
                  }
               }

               if (activeLines.isEmpty()) {
                  return null;
               } else {
                  return new ConditionalBlock(normalized, String.join("\n", activeLines));
               }
            }
         } else {
            return null;
         }
      }
   }

   private static String indentOf(String line) {
      int end;
      for(end = 0; end < line.length() && Character.isWhitespace(line.charAt(end)); ++end) {
      }

      return line.substring(0, end);
   }

   private static int versionCode(String version) {
      int code = PreprocessorLanguage.versionCode(version);
      return code < 0 ? 0 : code;
   }

   private static Path mainProjectMarker(SourceLocation location) {
      Pattern absolute = Pattern.compile("mainProjectFile\\s*=\\s*['\"]([^'\"]+)['\"]");
      Pattern relative = Pattern.compile("mainProjectFileRel\\s*=\\s*['\"]([^'\"]+)['\"]");

      for(String name : List.of("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")) {
         Path graph = location.root().resolve(name);
         if (Files.isRegularFile(graph, new LinkOption[0])) {
            try {
               String text = Files.readString(graph);
               Matcher absoluteMatcher = absolute.matcher(text);
               if (absoluteMatcher.find()) {
                  return location.root().resolve(absoluteMatcher.group(1)).normalize();
               }

               Matcher relativeMatcher = relative.matcher(text);
               if (relativeMatcher.find()) {
                  return location.root().resolve("versions").resolve(location.version()).resolve(relativeMatcher.group(1)).normalize();
               }
            } catch (IOException var9) {
            }
         }
      }

      return location.root().resolve("versions/mainProject");
   }

   private static SourceLocation sourceLocation(VirtualFile file) {
      String path = file.getPath().replace('\\', '/');
      Matcher matcher = VERSION_SOURCE.matcher(path);
      if (!matcher.matches()) {
         return null;
      } else {
         Path root = Path.of(matcher.group(1));
         return new SourceLocation(root, matcher.group(2), Path.of(matcher.group(3)));
      }
   }

   private static String lineText(Document document, int line) {
      return document.getImmutableCharSequence().subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString();
   }

   private static String normalizeNewlines(String value) {
      return value.replace("\r\n", "\n").replace('\r', '\n');
   }

   private static String trimOuterLineBreaks(String value) {
      int start = 0;

      int end;
      for(end = value.length(); start < end && value.charAt(start) == '\n'; ++start) {
      }

      while(end > start && value.charAt(end - 1) == '\n') {
         --end;
      }

      return value.substring(start, end);
   }

   private static void openTarget(Project project, VirtualFile file, int start, int end) {
      Editor targetEditor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, start), true);
      if (targetEditor != null) {
         targetEditor.getSelectionModel().setSelection(start, end);
         targetEditor.getCaretModel().moveToOffset(end);
         targetEditor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
      }
   }

   private static void showError(Project project, String message) {
      Messages.showErrorDialog(project, message, "Sync to Main src");
   }

   // $FF: synthetic method
   

   private static record SourceLocation(Path root, String version, Path relativePath) {
   }

   private static record ConditionalBlock(String text, String activeCode) {
   }

   private static record LineAnchor(int sourceLine, int targetStart, int targetEnd) {
   }

   private static record TextRange(int start, int end, String text) {
   }
}
