package rems.idea.mixincompletion;

import com.intellij.ide.highlighter.JavaFileHighlighter;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.Alarm;
import com.intellij.util.Alarm.ThreadToUse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class MixinCommentImmediateHighlighter implements EditorFactoryListener {
   private static final Logger LOG = Logger.getInstance(MixinCommentImmediateHighlighter.class);

   private static final int LAYER = 3001;
   private static final String BLOCK_OPEN = "/*$$";
   private static final String BLOCK_CLOSE = "$$*/";
   private static final Set<String> VERSIONED_EXTENSIONS = Set.of("java", "json", "json5", "properties", "toml", "xml", "yaml", "yml", "txt", "mcmeta", "cfg", "conf", "gradle", "kts", "accesswidener");
   private static final int BACKGROUND_LAYER = 999;
   private static final Key<List<RangeHighlighter>> HIGHLIGHTERS = Key.create("rems.mixin.comment.immediate.highlighters");
   private static final Key<DocumentListener> LISTENER = Key.create("rems.mixin.comment.immediate.listener");
   private static final Key<Alarm> REFRESH = Key.create("rems.mixin.comment.immediate.refresh");

   public void editorCreated(@NotNull EditorFactoryEvent event) {

      final Editor editor = event.getEditor();
      if (editor.getProject() != null && versioned(editor.getVirtualFile())) {
         DocumentListener listener = new DocumentListener() {
            public void documentChanged(@NotNull DocumentEvent event) {

               if (!editor.isDisposed() && MixinCommentImmediateHighlighter.affectsHighlightedLine(editor, event)) {
                  MixinCommentImmediateHighlighter.schedule(editor);
               }

            }

            // $FF: synthetic method
            
         };
         editor.putUserData(LISTENER, listener);
         editor.getDocument().addDocumentListener(listener);
         refresh(editor);
      }
   }

   private static void schedule(Editor editor) {
      Alarm alarm = (Alarm)editor.getUserData(REFRESH);
      if (alarm == null) {
         alarm = new Alarm(ThreadToUse.SWING_THREAD, editor.getProject());
         editor.putUserData(REFRESH, alarm);
      }

      alarm.cancelAllRequests();
      alarm.addRequest(() -> {
         if (!editor.isDisposed()) {
            refresh(editor);
         }

      }, 250);
   }

   private static boolean versioned(@Nullable VirtualFile file) {
      if (file != null && !file.isDirectory()) {
         String extension = file.getExtension();
         return extension != null && VERSIONED_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
      } else {
         return false;
      }
   }

   public void editorReleased(@NotNull EditorFactoryEvent event) {

      Editor editor = event.getEditor();
      DocumentListener listener = (DocumentListener)editor.getUserData(LISTENER);
      if (listener != null) {
         editor.getDocument().removeDocumentListener(listener);
      }

      Alarm alarm = (Alarm)editor.getUserData(REFRESH);
      if (alarm != null) {
         alarm.cancelAllRequests();
      }

      clear(editor);
   }

   static void refresh(Editor editor) {
      clear(editor);
      Document document = editor.getDocument();
      List<RangeHighlighter> highlighters = new ArrayList();
      editor.putUserData(HIGHLIGHTERS, highlighters);
      paintBlockBackgrounds(editor, highlighters, document);
      boolean multilineCode = false;
      Deque<TextAttributesKey> conditionalColors = new ArrayDeque();

      for(int line = 0; line < document.getLineCount(); ++line) {
         int lineStart = document.getLineStartOffset(line);
         int lineEnd = document.getLineEndOffset(line);
         String text = document.getImmutableCharSequence().subSequence(lineStart, lineEnd).toString();

         // Most lines of a file are code, and nothing here has anything to say about code. Two substring scans
         // decide it, against the two regular expressions and the several indexOf calls below.
         if (text.indexOf("//#") < 0 && text.indexOf("//?") < 0 && text.indexOf("$$") < 0) {
            continue;
         }
         Matcher preprocessor = MixinCommentSyntaxAnnotator.PREPROCESSOR.matcher(text);
         if (preprocessor.find()) {
            TextAttributesKey directiveColor = conditionalColor(conditionalColors, preprocessor.group(2));
            if (directiveColor == null) {
               directiveColor = MixinCommentSyntaxAnnotator.MUTED_ROSE;
            }

            addGroup(editor, highlighters, lineStart, preprocessor, 1, directiveColor);
            addGroup(editor, highlighters, lineStart, preprocessor, 2, directiveColor);
            if (preprocessor.start(3) >= 0) {
               String argument = preprocessor.group(3);
               int argumentStart = preprocessor.start(3);
               int conditionEnd = argument.length();
               int codeStart = -1;
               if ("replace".equals(preprocessor.group(2))) {
                  int separator = argument.indexOf(63);
                  if (separator >= 0) {
                     conditionEnd = separator;
                     add(editor, highlighters, lineStart + argumentStart + separator, lineStart + argumentStart + separator + 1, MixinCommentSyntaxAnnotator.MUTED_YELLOW);
                     codeStart = separator + 1;
                  }
               }

               add(editor, highlighters, lineStart + argumentStart, lineStart + argumentStart + conditionEnd, MixinCommentSyntaxAnnotator.LIGHT_GRAY);
               String condition = argument.substring(0, conditionEnd);
               addMatches(editor, highlighters, lineStart + argumentStart, condition, MixinCommentSyntaxAnnotator.PREPROCESSOR_WORD, MixinCommentSyntaxAnnotator.MUTED_PURPLE);
               addMatches(editor, highlighters, lineStart + argumentStart, condition, MixinCommentSyntaxAnnotator.PREPROCESSOR_OPERATOR, MixinCommentSyntaxAnnotator.MUTED_YELLOW);
               addMatches(editor, highlighters, lineStart + argumentStart, condition, MixinCommentSyntaxAnnotator.PREPROCESSOR_NUMBER, MixinCommentSyntaxAnnotator.MUTED_BLUE);
            }
         } else {
            Matcher embeddedPreprocessor = MixinCommentSyntaxAnnotator.EMBEDDED_PREPROCESSOR.matcher(text);
            if (embeddedPreprocessor.find()) {
               List<TextAttributesKey> markerColors = conditionalColorsOuterFirst(conditionalColors);
               TextAttributesKey directiveColor = conditionalColor(conditionalColors, embeddedPreprocessor.group(3));
               if (directiveColor == null) {
                  directiveColor = MixinCommentSyntaxAnnotator.MUTED_ROSE;
               }

               addGroup(editor, highlighters, lineStart, embeddedPreprocessor, 2, directiveColor);
               addGroup(editor, highlighters, lineStart, embeddedPreprocessor, 3, directiveColor);
               if (embeddedPreprocessor.start(4) >= 0) {
                  String argument = embeddedPreprocessor.group(4);
                  int argumentStart = embeddedPreprocessor.start(4);
                  add(editor, highlighters, lineStart + argumentStart, lineStart + argumentStart + argument.length(), MixinCommentSyntaxAnnotator.LIGHT_GRAY);
                  addMatches(editor, highlighters, lineStart + argumentStart, argument, MixinCommentSyntaxAnnotator.PREPROCESSOR_WORD, MixinCommentSyntaxAnnotator.MUTED_PURPLE);
                  addMatches(editor, highlighters, lineStart + argumentStart, argument, MixinCommentSyntaxAnnotator.PREPROCESSOR_OPERATOR, MixinCommentSyntaxAnnotator.MUTED_YELLOW);
                  addMatches(editor, highlighters, lineStart + argumentStart, argument, MixinCommentSyntaxAnnotator.PREPROCESSOR_NUMBER, MixinCommentSyntaxAnnotator.MUTED_BLUE);
               }

               addLeadingCodeMarkerColors(editor, highlighters, lineStart, text, embeddedPreprocessor.start(1), embeddedPreprocessor.end(1), markerColors);
            } else {
                              int replacement = text.indexOf("//#replace");
               if (replacement > 0 && !text.substring(0, replacement).isBlank()) {
                  int after = replacement + "//#replace".length();
                  int separator = text.indexOf(63, after);
                  int conditionEnd = separator < 0 ? text.length() : separator;
                  add(editor, highlighters, lineStart + replacement, lineStart + after, MixinCommentSyntaxAnnotator.MUTED_ROSE);
                  LOG.info("preprocessor replace: painted ["
                          + (lineStart + replacement) + "," + (lineStart + after) + ") = \""
                          + text.substring(replacement, after) + "\"");
                  if (conditionEnd > after) {
                     add(editor, highlighters, lineStart + after, lineStart + conditionEnd, MixinCommentSyntaxAnnotator.LIGHT_GRAY);
                     String condition = text.substring(after, conditionEnd);
                     addMatches(editor, highlighters, lineStart + after, condition, MixinCommentSyntaxAnnotator.PREPROCESSOR_WORD, MixinCommentSyntaxAnnotator.MUTED_PURPLE);
                     addMatches(editor, highlighters, lineStart + after, condition, MixinCommentSyntaxAnnotator.PREPROCESSOR_OPERATOR, MixinCommentSyntaxAnnotator.MUTED_YELLOW);
                     addMatches(editor, highlighters, lineStart + after, condition, MixinCommentSyntaxAnnotator.PREPROCESSOR_NUMBER, MixinCommentSyntaxAnnotator.MUTED_BLUE);
                  }

                  if (separator >= 0) {
                     add(editor, highlighters, lineStart + separator, lineStart + separator + 1, MixinCommentSyntaxAnnotator.MUTED_YELLOW);
                  }
               }

               int caseMarker = text.indexOf("//?");
               if (caseMarker >= 0 && text.substring(0, caseMarker).isBlank()) {
                  int restStart = caseMarker + 3;
                  int separator = text.indexOf(63, restStart);
                  int codeStart = -1;
                  int conditionEnd;
                  if (text.substring(restStart).stripLeading().startsWith("else")) {
                     int nameStart;
                     for(nameStart = restStart; nameStart < text.length() && Character.isWhitespace(text.charAt(nameStart)); ++nameStart) {
                     }

                     conditionEnd = Math.min(text.length(), nameStart + 4);
                  } else {
                     conditionEnd = separator < 0 ? text.length() : separator;
                     if (separator >= 0) {
                        codeStart = separator + 1;
                     }
                  }

                  add(editor, highlighters, lineStart + caseMarker, lineStart + caseMarker + 3, MixinCommentSyntaxAnnotator.MUTED_ROSE);
                  if (conditionEnd > restStart) {
                     add(editor, highlighters, lineStart + restStart, lineStart + conditionEnd, MixinCommentSyntaxAnnotator.LIGHT_GRAY);
                     String condition = text.substring(restStart, conditionEnd);
                     addMatches(editor, highlighters, lineStart + restStart, condition, MixinCommentSyntaxAnnotator.PREPROCESSOR_WORD, MixinCommentSyntaxAnnotator.MUTED_PURPLE);
                     addMatches(editor, highlighters, lineStart + restStart, condition, MixinCommentSyntaxAnnotator.PREPROCESSOR_OPERATOR, MixinCommentSyntaxAnnotator.MUTED_YELLOW);
                     addMatches(editor, highlighters, lineStart + restStart, condition, MixinCommentSyntaxAnnotator.PREPROCESSOR_NUMBER, MixinCommentSyntaxAnnotator.MUTED_BLUE);
                  }

                  if (separator >= 0) {
                     add(editor, highlighters, lineStart + separator, lineStart + separator + 1, MixinCommentSyntaxAnnotator.MUTED_YELLOW);
                  }
               } else {
                  int blockStart = text.indexOf("/*$$");
                  if (blockStart >= 0 && text.substring(0, blockStart).isBlank()) {
                     multilineCode = true;
                     add(editor, highlighters, lineStart + blockStart, lineStart + blockStart + "/*$$".length(), activeConditionalColor(conditionalColors));
                     int contentStart = blockStart + "/*$$".length();
                     int blockEnd = text.indexOf("$$*/", contentStart);
                     if (blockEnd >= 0) {
                        add(editor, highlighters, lineStart + blockEnd, lineStart + blockEnd + "$$*/".length(), activeConditionalColor(conditionalColors));
                        multilineCode = false;
                     }
                  } else {
                     int closeMarker = text.lastIndexOf("$$*/");
                     if (closeMarker >= 0) {
                        add(editor, highlighters, lineStart + closeMarker, lineStart + closeMarker + "$$*/".length(), activeConditionalColor(conditionalColors));
                         // The layer this paints on sits above the one the annotator paints on, so this is what
                         // a reader actually sees over a closing marker. Said out loud for the same reason that
                         // one says it: a marker drawn a character short cannot be told apart from a marker
                         // that was asked for a character short, and this is the painting that would win.
                         LOG.info("preprocessor marker: painted [" + (lineStart + closeMarker) + ","
                                 + (lineStart + closeMarker + "$$*/".length()) + ") = \""
                                 + text.substring(closeMarker, Math.min(text.length(), closeMarker + 4)) + "\"");
                        multilineCode = false;
                     }

                     if (!multilineCode) {
                        int marker = text.indexOf("//$$");
                        if (marker >= 0 && text.substring(0, marker).isBlank()) {
                           addLeadingCodeMarkerColors(editor, highlighters, lineStart, text, marker, text.length(), conditionalColorsOuterFirst(conditionalColors));
                        }
                     }
                  }
               }
            }
         }
      }

   }

   private static boolean affectsHighlightedLine(Editor editor, DocumentEvent event) {
      String oldText = event.getOldFragment().toString();
      String newText = event.getNewFragment().toString();
      if (!oldText.contains("//$$") && !oldText.contains("//#") && !oldText.contains("//?") && !oldText.contains("/*$$") && !oldText.contains("$$*/") && !newText.contains("//$$") && !newText.contains("//#") && !newText.contains("//?") && !newText.contains("/*$$") && !newText.contains("$$*/")) {
         Document document = event.getDocument();
         int safeOffset = Math.min(event.getOffset(), document.getTextLength());
         int line = document.getLineNumber(safeOffset);

         for(int current = Math.max(0, line - 1); current <= Math.min(document.getLineCount() - 1, line + 1); ++current) {
            String text = document.getImmutableCharSequence().subSequence(document.getLineStartOffset(current), document.getLineEndOffset(current)).toString();
            if (text.contains("//$$") || text.contains("//#") || text.contains("//?") || text.contains("/*$$") || text.contains("$$*/")) {
               return true;
            }
         }

         List<RangeHighlighter> highlighters = (List)editor.getUserData(HIGHLIGHTERS);
         if (highlighters == null) {
            return false;
         } else {
            int changedEnd = event.getOffset() + Math.max(event.getOldLength(), event.getNewLength());

            for(RangeHighlighter highlighter : highlighters) {
               if (highlighter.getStartOffset() <= changedEnd && highlighter.getEndOffset() >= event.getOffset()) {
                  return true;
               }
            }

            return false;
         }
      } else {
         return true;
      }
   }

   private static void highlightJava(Editor editor, List<RangeHighlighter> highlighters, int absoluteStart, String body) {
      JavaFileHighlighter javaHighlighter = new JavaFileHighlighter(LanguageLevel.HIGHEST);
      Lexer lexer = javaHighlighter.getHighlightingLexer();
      lexer.start(body);

      List<Token> tokens;
      for(tokens = new ArrayList(); lexer.getTokenType() != null; lexer.advance()) {
         IElementType type = lexer.getTokenType();
         int start = lexer.getTokenStart();
         int end = lexer.getTokenEnd();
         String tokenText = body.substring(start, end);
         tokens.add(new Token(type, start, end, tokenText));
         TextAttributesKey key = null;
         if (type == JavaTokenType.AT) {
            key = MixinCommentSyntaxAnnotator.MUTED_YELLOW;
         } else {
            TextAttributesKey[] keys = javaHighlighter.getTokenHighlights(type);
            if (keys.length > 0 && MixinCommentSyntaxAnnotator.keepsJavaColor(keys[0])) {
               key = MixinCommentSyntaxAnnotator.mutedSyntaxColor(keys[0]);
            }
         }

         if (key != null) {
            add(editor, highlighters, absoluteStart + start, absoluteStart + end, key);
         }
      }

      Matcher declaration = MixinCommentSyntaxAnnotator.DECLARATION.matcher(body);
      int declarationStart = declaration.find() ? declaration.start(1) : -1;
      int declarationEnd = declarationStart < 0 ? -1 : declaration.end(1);

      for(int index = 0; index < tokens.size(); ++index) {
         Token token = (Token)tokens.get(index);
         if (token.type() == JavaTokenType.IDENTIFIER) {
            Token previous = significant(tokens, index, -1);
            Token next = significant(tokens, index, 1);
            TextAttributesKey key = null;
            if (previous != null && previous.type() == JavaTokenType.AT) {
               key = MixinCommentSyntaxAnnotator.MUTED_YELLOW;
            } else if (MixinCommentSyntaxAnnotator.isAllUppercase(token.text())) {
               key = MixinCommentSyntaxAnnotator.MUTED_PURPLE;
            } else if (next != null && next.type() == JavaTokenType.LPARENTH && declarationStart == token.start()) {
               key = MixinCommentSyntaxAnnotator.MUTED_BLUE;
            } else if (token.start() == declarationStart && token.end() == declarationEnd) {
               key = MixinCommentSyntaxAnnotator.MUTED_PURPLE;
            } else if (previous != null && previous.type() == JavaTokenType.DOT && (next == null || next.type() != JavaTokenType.LPARENTH)) {
               key = MixinCommentSyntaxAnnotator.MUTED_PURPLE;
            }

            if (key != null) {
               add(editor, highlighters, absoluteStart + token.start(), absoluteStart + token.end(), key);
            }
         }
      }

   }

   private static Token significant(List<Token> tokens, int index, int direction) {
      for(int current = index + direction; current >= 0 && current < tokens.size(); current += direction) {
         if (!((Token)tokens.get(current)).text().isBlank()) {
            return (Token)tokens.get(current);
         }
      }

      return null;
   }

   private static TextAttributesKey conditionalColor(Deque<TextAttributesKey> colors, String directive) {
      if (MixinCommentSyntaxAnnotator.closesConditional(directive)) {
         return colors.isEmpty() ? MixinCommentSyntaxAnnotator.PREPROCESSOR_SCOPE_COLORS[0] : (TextAttributesKey)colors.pop();
      } else if (MixinCommentSyntaxAnnotator.continuesConditional(directive)) {
         return colors.isEmpty() ? MixinCommentSyntaxAnnotator.PREPROCESSOR_SCOPE_COLORS[0] : (TextAttributesKey)colors.peek();
      } else if (MixinCommentSyntaxAnnotator.opensConditional(directive)) {
         TextAttributesKey color = MixinCommentSyntaxAnnotator.PREPROCESSOR_SCOPE_COLORS[colors.size() % MixinCommentSyntaxAnnotator.PREPROCESSOR_SCOPE_COLORS.length];
         colors.push(color);
         return color;
      } else {
         return null;
      }
   }

   private static TextAttributesKey activeConditionalColor(Deque<TextAttributesKey> colors) {
      return colors.isEmpty() ? MixinCommentSyntaxAnnotator.MUTED_ROSE : (TextAttributesKey)colors.peek();
   }

   private static List<TextAttributesKey> conditionalColorsOuterFirst(Deque<TextAttributesKey> colors) {
      List<TextAttributesKey> result = new ArrayList();
      Iterator<TextAttributesKey> iterator = colors.descendingIterator();

      while(iterator.hasNext()) {
         result.add((TextAttributesKey)iterator.next());
      }

      return result;
   }

   private static int addLeadingCodeMarkerColors(Editor editor, List<RangeHighlighter> highlighters, int lineStart, String text, int start, int end, List<TextAttributesKey> colors) {
      int index = 0;

      int cursor;
      for(cursor = start; cursor < end; cursor += 4) {
         while(cursor < end && Character.isWhitespace(text.charAt(cursor))) {
            ++cursor;
         }

         if (cursor + 4 > end || !text.startsWith("//$$", cursor)) {
            break;
         }

         TextAttributesKey key = index < colors.size() ? (TextAttributesKey)colors.get(index) : MixinCommentSyntaxAnnotator.MUTED_ROSE;
         add(editor, highlighters, lineStart + cursor, lineStart + cursor + 4, key);
         ++index;
      }

      return cursor;
   }

   private static void addGroup(Editor editor, List<RangeHighlighter> highlighters, int lineStart, Matcher matcher, int group, TextAttributesKey key) {
      if (matcher.start(group) >= 0) {
         add(editor, highlighters, lineStart + matcher.start(group), lineStart + matcher.end(group), key);
      }
   }

   private static void addMatches(Editor editor, List<RangeHighlighter> highlighters, int absoluteStart, String text, Pattern pattern, TextAttributesKey key) {
      Matcher matcher = pattern.matcher(text);

      while(matcher.find()) {
         add(editor, highlighters, absoluteStart + matcher.start(), absoluteStart + matcher.end(), key);
      }

   }

   private static void add(Editor editor, List<RangeHighlighter> highlighters, int start, int end, TextAttributesKey key) {
      highlighters.add(editor.getMarkupModel().addRangeHighlighter(MixinCommentSyntaxAnnotator.themeAwareKey(key), start, end, 3001, HighlighterTargetArea.EXACT_RANGE));
   }

   private static void paintBlockBackgrounds(Editor editor, List<RangeHighlighter> highlighters, Document document) {
      for(PreprocessorStructure.Region region : PreprocessorStructure.regions(document)) {
         TextAttributesKey colour = MixinCommentSyntaxAnnotator.blockBackground(region.depth());
         addBackground(editor, highlighters, document, region.startLine(), colour);
         addBackground(editor, highlighters, document, region.endLine(), colour);

         for(int branch : region.branches()) {
            addBackground(editor, highlighters, document, branch, colour);
         }
      }

   }

   private static void addBackground(Editor editor, List<RangeHighlighter> highlighters, Document document, int line, TextAttributesKey key) {
      if (line >= 0 && line < document.getLineCount()) {
         int start = document.getLineStartOffset(line);
         int end = document.getLineEndOffset(line);

         CharSequence text;
         for(text = document.getCharsSequence(); start < end && Character.isWhitespace(text.charAt(start)); ++start) {
         }

         while(end > start && Character.isWhitespace(text.charAt(end - 1))) {
            --end;
         }

         if (end > start) {
            end = Math.min(end, markerEnd(text, start));
            highlighters.add(editor.getMarkupModel().addRangeHighlighter(key, start, end, 999, HighlighterTargetArea.EXACT_RANGE));
         }
      }
   }

   private static int markerEnd(CharSequence text, int start) {
      if (text.length() - start < 4) {
         return text.length();
      } else {
         boolean marker = text.charAt(start) == '/' && text.charAt(start + 1) == '*' && text.charAt(start + 2) == '$' || text.charAt(start) == '/' && text.charAt(start + 1) == '/' && text.charAt(start + 2) == '$';
         return marker ? start + 4 : text.length();
      }
   }

   private static void clear(Editor editor) {
      List<RangeHighlighter> highlighters = (List)editor.getUserData(HIGHLIGHTERS);
      if (highlighters != null) {
         for(RangeHighlighter highlighter : highlighters) {
            if (highlighter.isValid()) {
               editor.getMarkupModel().removeHighlighter(highlighter);
            }
         }

         highlighters.clear();
      }
   }

   // $FF: synthetic method
   

   private static record Token(IElementType type, int start, int end, String text) {
   }
}
