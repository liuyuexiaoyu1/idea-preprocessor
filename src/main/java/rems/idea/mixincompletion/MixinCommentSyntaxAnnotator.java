package rems.idea.mixincompletion;

import com.intellij.ide.highlighter.JavaFileHighlighter;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.EffectType;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.util.TextRange;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.ui.JBColor;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;

public final class MixinCommentSyntaxAnnotator implements Annotator {
   private static final Logger LOG = Logger.getInstance(MixinCommentSyntaxAnnotator.class);
   private static final String LINE_PREFIX = "//$$";
   private static final String BLOCK_OPEN = "/*$$";
   private static final String BLOCK_CLOSE = "$$*/";
   static final TextAttributesKey CONDITION = TextAttributesKey.createTextAttributesKey("REMS_PREPROCESSOR_CONDITION", new TextAttributes(new JBColor(new Color(8080304), new Color(13081582)), (Color)null, (Color)null, (EffectType)null, 0));
   static final TextAttributesKey EXPRESSION = TextAttributesKey.createTextAttributesKey("REMS_PREPROCESSOR_EXPRESSION", new TextAttributes(new JBColor(new Color(3042962), new Color(7321304)), (Color)null, (Color)null, (EffectType)null, 0));
   static final TextAttributesKey OPERATOR = TextAttributesKey.createTextAttributesKey("REMS_PREPROCESSOR_OPERATOR", new TextAttributes(new JBColor(new Color(9071135), new Color(13938268)), (Color)null, (Color)null, (EffectType)null, 0));
   static final TextAttributesKey MUTED_ROSE;
   static final TextAttributesKey MUTED_YELLOW;
   static final TextAttributesKey MUTED_PURPLE;
   static final TextAttributesKey MUTED_BLUE;
   static final TextAttributesKey MUTED_GREEN;
   static final TextAttributesKey LIGHT_GRAY;
   static final TextAttributesKey CONDITION_PLAIN;
   static final TextAttributesKey CODE_PLAIN;
   static final TextAttributesKey[] BLOCK_BACKGROUNDS;
   private static final int LIGHT_WASH = 18;
   private static final int DARK_WASH = 24;

   /** The conditional depth of every line, as it was when the document last changed. */
   private static final Key<Depths> DEPTHS = Key.create("rems.preprocessor.conditional.depths");
   static final TextAttributesKey[] PREPROCESSOR_SCOPE_COLORS;
   static final Pattern DECLARATION;
   static final Pattern PREPROCESSOR;
   static final Pattern EMBEDDED_PREPROCESSOR;
   static final Pattern PREPROCESSOR_WORD;
   static final Pattern PREPROCESSOR_OPERATOR;
   static final Pattern PREPROCESSOR_NUMBER;

   static TextAttributesKey blockBackground(int depth) {
      return BLOCK_BACKGROUNDS[Math.floorMod(depth, BLOCK_BACKGROUNDS.length)];
   }

   private static TextAttributesKey background(String name, int light, int dark) {
      return TextAttributesKey.createTextAttributesKey(name, new TextAttributes((Color)null, new JBColor(withAlpha(light, 18), withAlpha(dark, 24)), (Color)null, (EffectType)null, 0));
   }

   private static Color withAlpha(int rgb, int alpha) {
      return new Color(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, alpha);
   }

   public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {

      if (element instanceof PsiComment comment) {
         String text = comment.getText();
         Matcher preprocessor = PREPROCESSOR.matcher(text);
         if (preprocessor.find()) {
            TextAttributesKey directiveColor = conditionalDirectiveColor(comment, preprocessor.group(2));
            if (directiveColor == null) {
               directiveColor = MUTED_ROSE;
            }

            colorGroup(holder, comment, preprocessor, 1, directiveColor);
            colorGroup(holder, comment, preprocessor, 2, directiveColor);
            if (preprocessor.start(3) >= 0) {
               String argument = preprocessor.group(3);
               int argumentStart = preprocessor.start(3);
               int conditionEnd = argument.length();
               int codeStart = -1;
               if ("replace".equals(preprocessor.group(2))) {
                  int separator = argument.indexOf(63);
                  if (separator >= 0) {
                     conditionEnd = separator;
                     color(holder, comment, 0, argumentStart + separator, argumentStart + separator + 1, MUTED_YELLOW);
                     codeStart = separator + 1;
                  }
               }

               color(holder, comment, 0, argumentStart, argumentStart + conditionEnd, CONDITION_PLAIN);
               String condition = argument.substring(0, conditionEnd);
               colorMatches(holder, comment, condition, argumentStart, PREPROCESSOR_WORD, MUTED_PURPLE);
               colorMatches(holder, comment, condition, argumentStart, PREPROCESSOR_OPERATOR, MUTED_YELLOW);
               colorMatches(holder, comment, condition, argumentStart, PREPROCESSOR_NUMBER, MUTED_BLUE);
            }

         } else {
            Matcher embeddedPreprocessor = EMBEDDED_PREPROCESSOR.matcher(text);
            if (embeddedPreprocessor.find()) {
               TextAttributesKey directiveColor = conditionalDirectiveColor(comment, embeddedPreprocessor.group(3));
               if (directiveColor == null) {
                  directiveColor = MUTED_ROSE;
               }

               colorGroup(holder, comment, embeddedPreprocessor, 2, directiveColor);
               colorGroup(holder, comment, embeddedPreprocessor, 3, directiveColor);
               if (embeddedPreprocessor.start(4) >= 0) {
                  String argument = embeddedPreprocessor.group(4);
                  int argumentStart = embeddedPreprocessor.start(4);
                  color(holder, comment, 0, argumentStart, argumentStart + argument.length(), CONDITION_PLAIN);
                  colorMatches(holder, comment, argument, argumentStart, PREPROCESSOR_WORD, MUTED_PURPLE);
                  colorMatches(holder, comment, argument, argumentStart, PREPROCESSOR_OPERATOR, MUTED_YELLOW);
                  colorMatches(holder, comment, argument, argumentStart, PREPROCESSOR_NUMBER, MUTED_BLUE);
               }

               colorLeadingCodeMarkers(holder, comment, text, embeddedPreprocessor.start(1), embeddedPreprocessor.end(1));
            } else if (!text.startsWith("/*#case") && !text.startsWith("/*?")) {
               // A replacement written at the end of the line it replaces. The directive at the head of a line
               // is answered above; this form is the common one, and it was not answered anywhere - the whole
               // line was plain, directive, condition and replacement alike.
               int replacement = text.indexOf("//#replace");

               if (replacement > 0 && !text.substring(0, replacement).isBlank()) {
                  int after = replacement + "//#replace".length();
                  int separator = text.indexOf(63, after);
                  int conditionEnd = separator < 0 ? text.length() : separator;
                  color(holder, comment, 0, replacement, after, MUTED_ROSE);

                  // Said out loud because this form came out plain while the same line was coloured with the
                  // caret on it, which is the one difference the daemon's own reach does not explain: an
                  // annotator is asked about the whole file. What is written here is what was asked for.
                  LOG.info("preprocessor replace: at "
                          + (comment.getTextRange().getStartOffset() + replacement) + " covers ["
                          + (comment.getTextRange().getStartOffset() + replacement) + ","
                          + (comment.getTextRange().getStartOffset() + after) + ") = \""
                          + text.substring(replacement, after) + "\"");

                  if (conditionEnd > after) {
                     color(holder, comment, 0, after, conditionEnd, CONDITION_PLAIN);
                     String condition = text.substring(after, conditionEnd);
                     colorMatches(holder, comment, condition, after, PREPROCESSOR_WORD, MUTED_PURPLE);
                     colorMatches(holder, comment, condition, after, PREPROCESSOR_OPERATOR, MUTED_YELLOW);
                     colorMatches(holder, comment, condition, after, PREPROCESSOR_NUMBER, MUTED_BLUE);
                  }

                  if (separator >= 0) {
                     color(holder, comment, 0, separator, separator + 1, MUTED_YELLOW);
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

                  color(holder, comment, 0, caseMarker, caseMarker + 3, MUTED_ROSE);
                  if (conditionEnd > restStart) {
                     color(holder, comment, 0, restStart, conditionEnd, CONDITION_PLAIN);
                     String condition = text.substring(restStart, conditionEnd);
                     colorMatches(holder, comment, condition, restStart, PREPROCESSOR_WORD, MUTED_PURPLE);
                     colorMatches(holder, comment, condition, restStart, PREPROCESSOR_OPERATOR, MUTED_YELLOW);
                     colorMatches(holder, comment, condition, restStart, PREPROCESSOR_NUMBER, MUTED_BLUE);
                  }

                  if (separator >= 0) {
                     color(holder, comment, 0, separator, separator + 1, MUTED_YELLOW);
                  }

               } else {
                  int marker = text.indexOf("//$$");
                  boolean block = false;
                  if (marker < 0) {
                     marker = text.indexOf("/*$$");
                     block = marker >= 0;
                  }

                  if (marker >= 0 && text.substring(0, marker).isBlank()) {
                     int bodyStart = marker + "//$$".length();
                     int bodyEnd = block ? text.lastIndexOf("$$*/") : text.length();
                     if (bodyEnd < bodyStart) {
                        bodyEnd = text.length();
                     }

                     if (!block) {
                        colorLeadingCodeMarkers(holder, comment, text, marker, bodyEnd);
                     } else {
                        TextAttributesKey markerColor = activeConditionalColor(comment);
                        if (markerColor == null) {
                           markerColor = MUTED_ROSE;
                        }

                        color(holder, comment, 0, marker, marker + "/*$$".length(), markerColor);
                        if (block && bodyEnd < text.length()) {
                           int closeEnd = Math.min(text.length(), bodyEnd + "$$*/".length());
                           color(holder, comment, 0, bodyEnd, closeEnd, markerColor);

                           // Said out loud because a marker drawn one character short cannot be told from a
                           // marker that is one character short: this is the range the colouring covers, and
                           // what it covers it with, so a report of "only three characters are drawn" can be
                           // answered by what was asked for rather than by reading the arithmetic again.
                           LOG.info("preprocessor colour: closing marker of the block at ("
                                   + comment.getTextRange().getStartOffset() + ") covers ["
                                   + (comment.getTextRange().getStartOffset() + bodyEnd) + ","
                                   + (comment.getTextRange().getStartOffset() + closeEnd) + ") = \""
                                   + text.substring(bodyEnd, closeEnd).replace("\n", "\\n") + "\" key="
                                   + markerColor.getExternalName());
                        }

                     }
                  }
               }
            } else {
               int markerEnd = text.startsWith("/*#case") ? Math.min(text.length(), 8) : 3;
               color(holder, comment, 0, 0, markerEnd, MUTED_ROSE);
               if (text.startsWith("/*?")) {
                  int separator = text.indexOf(63, 3);
                  int commentEnd = text.endsWith("*/") ? text.length() - 2 : text.length();
                  int conditionEnd = separator < 0 ? commentEnd : separator;
                  if (conditionEnd > 3) {
                     color(holder, comment, 0, 3, conditionEnd, CONDITION_PLAIN);
                     String condition = text.substring(3, conditionEnd);
                     colorMatches(holder, comment, condition, 3, PREPROCESSOR_WORD, MUTED_PURPLE);
                     colorMatches(holder, comment, condition, 3, PREPROCESSOR_OPERATOR, MUTED_YELLOW);
                     colorMatches(holder, comment, condition, 3, PREPROCESSOR_NUMBER, MUTED_BLUE);
                  }

                  if (separator >= 0) {
                     color(holder, comment, 0, separator, separator + 1, MUTED_YELLOW);
                  }
               }

            }
         }
      }
   }

   private static void highlightJava(AnnotationHolder holder, PsiComment comment, int bodyStart, String body) {
      JavaFileHighlighter highlighter = new JavaFileHighlighter(LanguageLevel.HIGHEST);
      Lexer lexer = highlighter.getHighlightingLexer();
      lexer.start(body);

      List<Token> tokens;
      for(tokens = new ArrayList(); lexer.getTokenType() != null; lexer.advance()) {
         IElementType type = lexer.getTokenType();
         int start = lexer.getTokenStart();
         int end = lexer.getTokenEnd();
         tokens.add(new Token(type, start, end, body.substring(start, end)));
         TextAttributesKey[] keys = highlighter.getTokenHighlights(type);
         if (type == JavaTokenType.AT) {
            color(holder, comment, bodyStart, start, end, DefaultLanguageHighlighterColors.METADATA);
         } else if (keys.length > 0) {
            color(holder, comment, bodyStart, start, end, keys[0]);
         }
      }

      Matcher declaration = DECLARATION.matcher(body);
      int declarationStart = declaration.find() ? declaration.start(1) : -1;
      int declarationEnd = declarationStart < 0 ? -1 : declaration.end(1);

      for(int index = 0; index < tokens.size(); ++index) {
         Token token = (Token)tokens.get(index);
         if (token.type() == JavaTokenType.IDENTIFIER) {
            Token previous = significant(tokens, index, -1);
            Token next = significant(tokens, index, 1);
            TextAttributesKey key = null;
            if (previous != null && previous.type() == JavaTokenType.AT) {
               key = DefaultLanguageHighlighterColors.METADATA;
            } else if (next != null && next.type() == JavaTokenType.EQ && isAnnotationAttribute(comment, bodyStart + token.end())) {
               key = DefaultLanguageHighlighterColors.METADATA;
            } else if (isAllUppercase(token.text())) {
               key = DefaultLanguageHighlighterColors.STATIC_FIELD;
            } else if (next != null && next.type() == JavaTokenType.LPARENTH) {
               if (declarationStart == token.start()) {
                  key = DefaultLanguageHighlighterColors.FUNCTION_DECLARATION;
               }
            } else if (token.start() == declarationStart && token.end() == declarationEnd) {
               key = DefaultLanguageHighlighterColors.LOCAL_VARIABLE;
            } else if (previous != null && previous.type() == JavaTokenType.DOT) {
               key = DefaultLanguageHighlighterColors.INSTANCE_FIELD;
            }

            if (key != null) {
               color(holder, comment, bodyStart, token.start(), token.end(), key);
            }
         }
      }

   }

   static boolean opensConditional(String directive) {
      return "if".equals(directive) || "ifdef".equals(directive) || "ifndef".equals(directive);
   }

   static boolean continuesConditional(String directive) {
      return "elseif".equals(directive) || "elif".equals(directive) || "else".equals(directive);
   }

   static boolean closesConditional(String directive) {
      return "endif".equals(directive);
   }

   private static TextAttributesKey conditionalDirectiveColor(PsiComment comment, String directive) {
      if (!opensConditional(directive) && !continuesConditional(directive) && !closesConditional(directive)) {
         return null;
      } else {
         Document document = PsiDocumentManager.getInstance(comment.getProject()).getDocument(comment.getContainingFile());
         if (document == null) {
            return PREPROCESSOR_SCOPE_COLORS[0];
         } else {
            int depth = conditionalDepthBeforeLine(document, document.getLineNumber(comment.getTextOffset()));
            int level = opensConditional(directive) ? depth : Math.max(0, depth - 1);
            return PREPROCESSOR_SCOPE_COLORS[level % PREPROCESSOR_SCOPE_COLORS.length];
         }
      }
   }

   private static TextAttributesKey activeConditionalColor(PsiComment comment) {
      Document document = PsiDocumentManager.getInstance(comment.getProject()).getDocument(comment.getContainingFile());
      if (document == null) {
         return null;
      } else {
         int depth = conditionalDepthBeforeLine(document, document.getLineNumber(comment.getTextOffset()));
         return depth == 0 ? null : PREPROCESSOR_SCOPE_COLORS[(depth - 1) % PREPROCESSOR_SCOPE_COLORS.length];
      }
   }

   private static int colorLeadingCodeMarkers(AnnotationHolder holder, PsiComment comment, String text, int start, int end) {
      Document document = PsiDocumentManager.getInstance(comment.getProject()).getDocument(comment.getContainingFile());
      int depth = document == null ? 0 : conditionalDepthBeforeLine(document, document.getLineNumber(comment.getTextOffset()));
      int index = 0;

      int cursor;
      for(cursor = start; cursor < end; cursor += 4) {
         while(cursor < end && Character.isWhitespace(text.charAt(cursor))) {
            ++cursor;
         }

         if (cursor + 4 > end || !text.startsWith("//$$", cursor)) {
            break;
         }

         TextAttributesKey key = index < depth ? PREPROCESSOR_SCOPE_COLORS[index % PREPROCESSOR_SCOPE_COLORS.length] : MUTED_ROSE;
         color(holder, comment, 0, cursor, cursor + 4, key);
         ++index;
      }

      return cursor;
   }

   /**
    * How deep in conditionals a line sits, read from a table of the whole file.
    *
    * <p>Asked once per comment, and answered here by walking every line above it, matching two patterns on each.
    * A file of any size put that walk on every marker in it, and the daemon asks again on every keystroke: the
    * same answers, recomputed from the top of the file each time. The table is built once per revision of the
    * document and every question after that is an array read.
    */
   private static int conditionalDepthBeforeLine(Document document, int currentLine) {
      long stamp = document.getModificationStamp();
      Depths cached = (Depths)document.getUserData(DEPTHS);

      if (cached == null || cached.stamp() != stamp) {
         cached = new Depths(stamp, depthsOf(document));
         document.putUserData(DEPTHS, cached);
      }

      int[] before = cached.before();

      return currentLine >= 0 && currentLine < before.length ? before[currentLine] : 0;
   }

   private static int[] depthsOf(Document document) {
      int lines = document.getLineCount();
      int[] before = new int[lines + 1];
      int depth = 0;

      for(int line = 0; line < lines; ++line) {
         before[line] = depth;
         String directive = directiveOnLine(lineText(document, line));

         if (directive != null) {
            if (opensConditional(directive)) {
               ++depth;
            } else if (closesConditional(directive)) {
               depth = Math.max(0, depth - 1);
            }
         }
      }

      before[lines] = depth;

      return before;
   }

   private static String lineText(Document document, int line) {
      int length = document.getTextLength();

      return line >= 0 && line < document.getLineCount()
              ? document.getImmutableCharSequence()
                      .subSequence(document.getLineStartOffset(line), Math.min(document.getLineEndOffset(line), length))
                      .toString()
              : "";
   }

   private static String directiveOnLine(String lineText) {
      Matcher direct = PREPROCESSOR.matcher(lineText);
      if (direct.find()) {
         return direct.group(2);
      } else {
         Matcher embedded = EMBEDDED_PREPROCESSOR.matcher(lineText);
         return embedded.find() ? embedded.group(3) : null;
      }
   }

   private static TextAttributesKey scopeColor(String name, int light, int dark) {
      return TextAttributesKey.createTextAttributesKey(name, new TextAttributes(new JBColor(new Color(light), new Color(dark)), (Color)null, (Color)null, (EffectType)null, 0));
   }

   private static boolean isAnnotationAttribute(PsiComment comment, int relativeOffset) {
      Document document = PsiDocumentManager.getInstance(comment.getProject()).getDocument(comment.getContainingFile());
      if (document == null) {
         return false;
      } else {
         String logical = MixinCommentContext.logicalContext(document, comment.getTextOffset() + relativeOffset);
         return MixinCommentContext.enclosingAnnotation(logical) != null;
      }
   }

   private static Token significant(List<Token> tokens, int index, int direction) {
      for(int current = index + direction; current >= 0 && current < tokens.size(); current += direction) {
         Token token = (Token)tokens.get(current);
         if (!token.text().isBlank()) {
            return token;
         }
      }

      return null;
   }

   static boolean isAllUppercase(String value) {
      boolean letter = false;

      for(int index = 0; index < value.length(); ++index) {
         char character = value.charAt(index);
         if (Character.isLetter(character)) {
            letter = true;
            if (Character.isLowerCase(character)) {
               return false;
            }
         }
      }

      return letter;
   }

   static boolean keepsJavaColor(TextAttributesKey key) {
      return true;
   }

   static TextAttributesKey mutedSyntaxColor(TextAttributesKey key) {
      return key;
   }

   static TextAttributesKey themeAwareKey(TextAttributesKey key) {
      return key;
   }

   private static void color(AnnotationHolder holder, PsiComment comment, int bodyStart, int start, int end, TextAttributesKey key) {
      int absoluteStart = comment.getTextRange().getStartOffset() + bodyStart + start;
      holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(TextRange.create(absoluteStart, absoluteStart + end - start)).textAttributes(themeAwareKey(key)).create();
   }

   private static void colorGroup(AnnotationHolder holder, PsiComment comment, Matcher matcher, int group, TextAttributesKey key) {
      if (matcher.start(group) >= 0) {
         color(holder, comment, 0, matcher.start(group), matcher.end(group), key);
      }
   }

   private static void colorMatches(AnnotationHolder holder, PsiComment comment, String text, int base, Pattern pattern, TextAttributesKey key) {
      Matcher matcher = pattern.matcher(text);

      while(matcher.find()) {
         color(holder, comment, 0, base + matcher.start(), base + matcher.end(), key);
      }

   }

   static {
      MUTED_ROSE = CONDITION;
      MUTED_YELLOW = OPERATOR;
      MUTED_PURPLE = EXPRESSION;
      MUTED_BLUE = EXPRESSION;
      MUTED_GREEN = EXPRESSION;
      LIGHT_GRAY = EXPRESSION;
      CONDITION_PLAIN = EXPRESSION;
      CODE_PLAIN = DefaultLanguageHighlighterColors.IDENTIFIER;
      BLOCK_BACKGROUNDS = new TextAttributesKey[]{background("REMS_PREPROCESSOR_BLOCK_0", 9133248, 13081582), background("REMS_PREPROCESSOR_BLOCK_1", 7300038, 11508716), background("REMS_PREPROCESSOR_BLOCK_2", 5994176, 9678568), background("REMS_PREPROCESSOR_BLOCK_3", 5473968, 9027293), background("REMS_PREPROCESSOR_BLOCK_4", 6065308, 9421775), background("REMS_PREPROCESSOR_BLOCK_5", 7048338, 10273477)};
      // One colour per level of nesting. An //#if and the //#endif closing it take the same colour, and a
      // pair written inside them takes the next one along - which is the only thing that makes the pairing
      // readable without counting markers, and a file of version logic is mostly pairings.
      PREPROCESSOR_SCOPE_COLORS = new TextAttributesKey[]{
              scopeColor("REMS_PREPROCESSOR_SCOPE_BLUE", 0x2F668A, 0x4F83A8),
              scopeColor("REMS_PREPROCESSOR_SCOPE_ORANGE", 0x8A5A1F, 0xB17A3E),
              scopeColor("REMS_PREPROCESSOR_SCOPE_PURPLE", 0x70507F, 0x9870AA),
              scopeColor("REMS_PREPROCESSOR_SCOPE_GREEN", 0x3F7049, 0x61916A),
              scopeColor("REMS_PREPROCESSOR_SCOPE_RED", 0x8B4D54, 0xAD6970),
              scopeColor("REMS_PREPROCESSOR_SCOPE_CYAN", 0x2D7273, 0x559798)
      };
      DECLARATION = Pattern.compile("(?:(?:public|protected|private|abstract|final|static|synchronized|native|strictfp|transient|volatile)\\s+)*[A-Za-z_$][A-Za-z0-9_$.<>?, \\[\\]]*\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:=|;|\\()");
      PREPROCESSOR = Pattern.compile("^\\s*(//#)([A-Za-z-]+)(?:\\s+(.*))?$");
      EMBEDDED_PREPROCESSOR = Pattern.compile("^\\s*((?://\\$\\$\\s*)+)(//#)([A-Za-z-]+)(?:\\s+(.*))?$");
      PREPROCESSOR_WORD = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
      PREPROCESSOR_OPERATOR = Pattern.compile("&&|\\|\\||==|!=|<=|>=|<|>|\\.\\.|\\bin\\b|\\bnot\\b");
      PREPROCESSOR_NUMBER = Pattern.compile("(?<![A-Za-z_$])[0-9][0-9._]*(?![A-Za-z_$])");
   }

   // $FF: synthetic method
   

   private static record Token(IElementType type, int start, int end, String text) {
   }

   /** The depth table, and the revision of the document it was built from. */
   private static record Depths(long stamp, int[] before) {
   }
}
