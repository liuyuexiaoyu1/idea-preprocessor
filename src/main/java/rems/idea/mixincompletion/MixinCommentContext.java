package rems.idea.mixincompletion;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

final class MixinCommentContext {
   private static final String LINE_PREFIX = "//$$";
   private static final String BLOCK_OPEN = "/*$$";
   private static final String BLOCK_CLOSE = "$$*/";
   private static final int BLOCK_SEARCH = 20000;
   private static final Pattern ATTRIBUTE_VALUE = Pattern.compile("\\b(?:method|target)\\s*=\\s*\"[^\"]*$");
   private static final Pattern ANNOTATION = Pattern.compile("@[A-Za-z0-9_$]*$");
   private static final Pattern AT_VALUE = Pattern.compile("@At\\s*\\(\\s*(?:value\\s*=\\s*)?\"[^\"]*$");
   private static final Pattern JAVA_KEYWORD = Pattern.compile("^\\s*(?://\\$\\$\\s*)+(?:[A-Za-z_$][A-Za-z0-9_$]*\\s+)*[A-Za-z_$]*$");
   private static final Pattern JAVA_IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*$");
   private static final Pattern JAVA_MEMBER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*\\.[A-Za-z_$]*$");
   private static final Pattern ANNOTATION_ATTRIBUTE = Pattern.compile("@([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\((?:[^@)]*,\\s*)?([A-Za-z_$]*)$");
   private static final Pattern PREPROCESSOR_DIRECTIVE = Pattern.compile("^\\s*(?://\\$\\$\\s*)*//#[A-Za-z]*$");
   private static final Pattern PREPROCESSOR_OPERATOR = Pattern.compile("^\\s*(?://\\$\\$\\s*)*//#(?:if|elseif|elif|replace)\\s+MC\\s*(?:[!<>=]*|(?:not\\s+)?i?n?)$");
   private static final Pattern PREPROCESSOR_VERSION = Pattern.compile("^\\s*(?://\\$\\$\\s*)*//#(?:if|elseif|elif|replace)\\s+.*(?:>=|<=|==|!=|>|<|\\.\\.|\\bin\\s+|\\[|,)\\s*[0-9._]*$");
   private static final Pattern PREPROCESSOR_EXPRESSION = Pattern.compile("^\\s*(?://\\$\\$\\s*)*//#(?:if|elseif|elif|replace)\\s+.*$");

   private MixinCommentContext() {
   }

   private static String slice(Document document, int start, int end) {
      int length = document.getTextLength();
      int from = Math.max(0, Math.min(start, length));
      int to = Math.max(from, Math.min(end, length));
      return document.getImmutableCharSequence().subSequence(from, to).toString();
   }

   static boolean isCompletionPosition(Document document, int offset) {
      return isCompletionPosition(document, offset, logicalContext(document, offset));
   }

   static boolean isCompletionPosition(Document document, int offset, String logicalContext) {
      if (offset >= 0 && offset <= document.getTextLength()) {
         String beforeCaret = javaCodeBeforeCaret(document, offset);
         return beforeCaret != null && (ATTRIBUTE_VALUE.matcher(beforeCaret).find() || ANNOTATION.matcher(beforeCaret).find() || AT_VALUE.matcher(beforeCaret).find() || JAVA_KEYWORD.matcher(beforeCaret).find() || annotationAttribute(logicalContext) != null || JAVA_MEMBER.matcher(beforeCaret).find() || JAVA_IDENTIFIER.matcher(beforeCaret).find());
      } else {
         return false;
      }
   }

   static boolean isPreprocessorCompletionPosition(Document document, int offset) {
      if (offset >= 0 && offset <= document.getTextLength()) {
         int line = document.getLineNumber(offset);
         int lineStart = document.getLineStartOffset(line);
         String beforeCaret = slice(document, lineStart, offset);
         return PREPROCESSOR_DIRECTIVE.matcher(beforeCaret).matches() || PREPROCESSOR_OPERATOR.matcher(beforeCaret).matches() || PREPROCESSOR_VERSION.matcher(beforeCaret).matches() || PREPROCESSOR_EXPRESSION.matcher(beforeCaret).matches() || beforeCaret.stripLeading().startsWith("//?") && javaCodeBeforeCaret(document, offset) == null;
      } else {
         return false;
      }
   }

   /**
    * Whether a comment carries code at all, which is what decides whether anything looks at it.
    *
    * <p>Every form has to be listed here. {@code //#replace} was left out, and the effect was not small: a
    * replacement line was skipped before its tail was ever read, so nothing about it was resolved - the names
    * in it had no colour, nothing jumped from them, and the analysis that reports what a version does not
    * have never saw them. A form missing from this check is a whole form of the dialect that does nothing,
    * and it fails silently, which is why it went unnoticed for so long.
    */
   static boolean carriesCode(String commentText) {
      if (commentText.contains("//#replace")) return true;
      if (commentText.contains("//$$")) return true;
      if (commentText.contains("/*$$")) return true;
      if (commentText.contains("//?")) return true;

      return commentText.contains("/*?");
   }

   static String logicalContext(Document document, int offset) {
      int safeOffset = Math.max(0, Math.min(offset, document.getTextLength()));
      int blockStart = enclosingBlockStart(document, safeOffset);
      if (blockStart >= 0) {
         String text = slice(document, blockStart + 4, safeOffset);
         return text.stripLeading();
      } else {
         int currentLine = document.getLineNumber(safeOffset);
         int currentStart = document.getLineStartOffset(currentLine);
         String physical = slice(document, currentStart, safeOffset);
         if (!physical.stripLeading().startsWith("//$$")) {
            String synthetic = javaCodeBeforeCaret(document, safeOffset);
            if (synthetic != null) {
               return synthetic.substring(4).stripLeading();
            }
         }

         int firstLine;
         for(firstLine = currentLine; firstLine > 0; --firstLine) {
            int start = document.getLineStartOffset(firstLine - 1);
            int end = document.getLineEndOffset(firstLine - 1);
            String previous = slice(document, start, end).stripLeading();
            if (!previous.startsWith("//$$")) {
               break;
            }
         }

         StringBuilder result = new StringBuilder();

         for(int line = firstLine; line <= currentLine; ++line) {
            int start = document.getLineStartOffset(line);
            int end = line == currentLine ? safeOffset : document.getLineEndOffset(line);
            String text = slice(document, start, end).stripLeading();
            if (text.startsWith("//$$")) {
               if (!result.isEmpty()) {
                  result.append('\n');
               }

               result.append(text.substring(4).stripLeading());
            }
         }

         return result.toString();
      }
   }

   static String javaCodeBeforeCaret(Document document, int offset) {
      int safeOffset = Math.max(0, Math.min(offset, document.getTextLength()));
      int line = document.getLineNumber(safeOffset);
      int lineStart = document.getLineStartOffset(line);
      String before = slice(document, lineStart, safeOffset);
      if (before.stripLeading().startsWith("//$$")) {
         return before;
      } else {
         int caseBody = caseBodyStart(before);
         if (caseBody >= 0) {
            String var13 = before.substring(caseBody);
            return "//$$ " + var13;
         } else {
            int replaceBody = replacementBodyStart(before);
            if (replaceBody >= 0) {
               String var12 = before.substring(replaceBody);
               return "//$$ " + var12;
            } else {
               int inlineBody = inlineCaseBodyStart(document, safeOffset);
               if (inlineBody >= 0) {
                  String var11 = slice(document, inlineBody, safeOffset);
                  return "//$$ " + var11;
               } else {
                  int blockStart = enclosingBlockStart(document, safeOffset);
                  if (blockStart < 0) {
                     return trailingConditionAbove(document, line) ? before : null;
                  } else {
                     int contentStart = Math.max(lineStart, blockStart + 4);
                     String var10000 = slice(document, contentStart, safeOffset);
                     return "//$$ " + var10000;
                  }
               }
            }
         }
      }
   }

   static boolean trailingConditionAbove(Document document, int line) {
      if (line <= 0) {
         return false;
      } else {
         String above = lineTextAt(document, line - 1);
         int marker = above.lastIndexOf("//?");
         if (marker > 0 && !above.substring(0, marker).isBlank()) {
            return true;
         } else {
            int directive = above.lastIndexOf("//#if");
            return directive > 0 && !above.substring(0, directive).isBlank();
         }
      }
   }

   static String codeOfLine(String line) {
      if (PreprocessorLanguage.parseDirective(line) != null) {
         return "";
      } else {
         int trailing = line.lastIndexOf("//?");
         String content;
         if (trailing > 0 && !line.substring(0, trailing).isBlank()) {
            content = line.substring(0, trailing);
         } else {
            int caseBody = caseBodyStart(line);
            if (caseBody >= 0) {
               return line.substring(caseBody);
            }

            int blockStart = line.indexOf("/*$$");
            if (blockStart >= 0) {
               int blockEnd = line.indexOf("$$*/", blockStart + 4);
               return blockEnd < 0 ? line.substring(blockStart + 4) : line.substring(blockStart + 4, blockEnd);
            }

            int replacement = replacementBodyStart(line);
            if (replacement >= 0) {
               return line.substring(replacement);
            }

            int marker = line.indexOf("//$$");
            if (marker < 0 || !line.substring(0, marker).isBlank()) {
               return "";
            }

            content = line.substring(marker);
         }

         int cursor;
         for(cursor = 0; cursor < content.length() && Character.isWhitespace(content.charAt(cursor)); ++cursor) {
         }

         while(cursor + 4 <= content.length() && content.startsWith("//$$", cursor)) {
            for(cursor += 4; cursor < content.length() && Character.isWhitespace(content.charAt(cursor)); ++cursor) {
            }
         }

         return content.substring(cursor);
      }
   }

   static @Nullable TextRange codeRange(String commentText) {
      int replacement = replacementBodyStart(commentText);
      if (replacement >= 0 && replacement < commentText.length()) {
         return TextRange.create(replacement, commentText.length());
      } else {
         int blockStart = commentText.indexOf("/*$$");
         if (blockStart >= 0) {
            int bodyStart = blockStart + "/*$$".length();
            int close = commentText.indexOf("$$*/", bodyStart);
            int bodyEnd = close < 0 ? commentText.length() : close;
            return bodyEnd > bodyStart ? TextRange.create(bodyStart, bodyEnd) : null;
         } else {
            int caseBody = caseBodyStart(commentText);
            if (caseBody >= 0 && caseBody < commentText.length()) {
               return TextRange.create(caseBody, commentText.length());
            } else if (PreprocessorLanguage.parseDirective(commentText) != null) {
               return null;
            } else {
               int trailing = commentText.lastIndexOf("//?");
               if (trailing > 0 && !commentText.substring(0, trailing).isBlank()) {
                  return null;
               } else {
                  int marker = commentText.indexOf("//$$");
                  if (marker >= 0 && commentText.substring(0, marker).isBlank()) {
                     int cursor = marker;

                     while(cursor + "//$$".length() <= commentText.length() && commentText.startsWith("//$$", cursor)) {
                        for(cursor += "//$$".length(); cursor < commentText.length() && Character.isWhitespace(commentText.charAt(cursor)); ++cursor) {
                        }
                     }

                     return cursor < commentText.length() ? TextRange.create(cursor, commentText.length()) : null;
                  } else {
                     return null;
                  }
               }
            }
         }
      }
   }

   static boolean isHiddenOffset(Document document, int offset) {
      if (offset >= 0 && offset <= document.getTextLength()) {
         int line = document.getLineNumber(offset);
         int relative = offset - document.getLineStartOffset(line);
         String lineText = lineTextAt(document, line);
         int replacement = replacementBodyStart(lineText);
         if (replacement >= 0 && relative >= replacement) {
            return true;
         } else {
            int caseBody = caseBodyStart(lineText);
            if (caseBody >= 0 && relative >= caseBody) {
               return true;
            } else if (inactiveLineAt(document, line)) {
               return true;
            } else {
               CharSequence text = document.getImmutableCharSequence();
               int before = Math.max(0, offset - 1);
               int floor = Math.max(0, offset - 20000);
               int blockStart = lastIndexOf(text, "/*$$", before, floor);
               if (blockStart < 0) {
                  return false;
               } else {
                  int closed = lastIndexOf(text, "$$*/", before, floor);
                  if (closed > blockStart) {
                     return false;
                  } else {
                     // Where the marker that closes this block begins, rather than whether one can still be
                     // found at or after this offset. The marker satisfies the second question about itself:
                     // its first character read as carried code, the colouring asked for it and had it thrown
                     // away, and a closing marker came out drawn three characters wide. The opening marker is
                     // not asked about the same way, which is why only this one showed the fault.
                     int close = indexOf(text, "$$*/", blockStart + "/*$$".length(), text.length());
                     return close >= 0 && offset < close;
                  }
               }
            }
         }
      } else {
         return false;
      }
   }

   private static String lineTextAt(Document document, int line) {
      return line >= 0 && line < document.getLineCount() ? document.getCharsSequence().subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString() : "";
   }

   private static boolean inactiveLineAt(Document document, int line) {
      if (line >= 0 && line < document.getLineCount()) {
         int start = document.getLineStartOffset(line);
         int end = document.getLineEndOffset(line);
         CharSequence text = document.getCharsSequence();

         int index;
         for(index = start; index < end && Character.isWhitespace(text.charAt(index)); ++index) {
         }

         if (index + "//$$".length() > end) {
            return false;
         } else {
            for(int position = 0; position < "//$$".length(); ++position) {
               if (text.charAt(index + position) != "//$$".charAt(position)) {
                  return false;
               }
            }

            int after = index + "//$$".length();
            return after >= end || text.charAt(after) != '*';
         }
      } else {
         return false;
      }
   }

   private static int lastIndexOf(CharSequence text, String needle, int from, int floor) {
      for(int index = Math.min(from, text.length() - needle.length()); index >= floor; --index) {
         if (matchesAt(text, needle, index)) {
            return index;
         }
      }

      return -1;
   }

   private static int indexOf(CharSequence text, String needle, int from, int ceiling) {
      int limit = Math.min(ceiling, text.length() - needle.length());

      for(int index = Math.max(0, from); index <= limit; ++index) {
         if (matchesAt(text, needle, index)) {
            return index;
         }
      }

      return -1;
   }

   private static boolean matchesAt(CharSequence text, String needle, int index) {
      for(int position = 0; position < needle.length(); ++position) {
         if (text.charAt(index + position) != needle.charAt(position)) {
            return false;
         }
      }

      return true;
   }

   static boolean isMultilineCodePosition(Document document, int offset) {
      return enclosingBlockStart(document, Math.max(0, Math.min(offset, document.getTextLength()))) >= 0;
   }

   private static int enclosingBlockStart(Document document, int offset) {
      CharSequence text = document.getImmutableCharSequence();
      int start = lastIndexOf(text, "/*$$", Math.max(0, offset - 1), 0);
      if (start < 0) {
         return -1;
      } else {
         int previousEnd = lastIndexOf(text, "$$*/", Math.max(0, offset - 1), 0);
         if (previousEnd > start) {
            return -1;
         } else {
            int nextEnd = indexOf(text, "$$*/", offset, text.length());
            return nextEnd < 0 ? -1 : start;
         }
      }
   }

   static int caseBodyStart(String linePrefix) {
      int marker = linePrefix.indexOf("//?");
      if (marker >= 0 && linePrefix.substring(0, marker).isBlank()) {
         int cursor;
         for(cursor = marker + 3; cursor < linePrefix.length() && Character.isWhitespace(linePrefix.charAt(cursor)); ++cursor) {
         }

         if (linePrefix.startsWith("else", cursor)) {
            int end = cursor + 4;
            if (end < linePrefix.length() && Character.isWhitespace(linePrefix.charAt(end))) {
               while(end < linePrefix.length() && Character.isWhitespace(linePrefix.charAt(end))) {
                  ++end;
               }

               return end;
            } else {
               return -1;
            }
         } else {
            int separator = linePrefix.indexOf(63, cursor);
            if (separator < 0) {
               return -1;
            } else {
               int body;
               for(body = separator + 1; body < linePrefix.length() && Character.isWhitespace(linePrefix.charAt(body)); ++body) {
               }

               return body;
            }
         }
      } else {
         return -1;
      }
   }

   static int replacementBodyStart(String linePrefix) {
      int marker = linePrefix.lastIndexOf("//#replace");
      if (marker < 0) {
         return -1;
      } else {
         int separator = linePrefix.indexOf(63, marker + "//#replace".length());
         if (separator < 0) {
            return -1;
         } else {
            int body;
            for(body = separator + 1; body < linePrefix.length() && Character.isWhitespace(linePrefix.charAt(body)); ++body) {
            }

            return body;
         }
      }
   }

   /**
    * The live code a {@code //#replace} line would be replacing, from the start of its statement to it.
    *
    * <p>A replacement tail is nearly always a continuation - {@code .addValidator(...)} carrying on the call
    * chain written on the line above - so on its own it begins at a dot and stands for nothing. What it
    * means is "in the versions below this one, this line is that text instead", and that only parses once
    * the statement it would stand in for is in front of it. A fragment built from the tail alone is a
    * fragment that cannot resolve anything, which is what a replacement line reported about itself.
    *
    * <p>Only whole live lines are taken, and the walk stops at the first thing that could not have been
    * part of the same statement: a blank line, a comment, or a line that ends a statement rather than
    * continuing one. Anything a marker carries is left out on purpose - code written for another version is
    * not the context this one compiles against.
    *
    * @return the text from the start of that statement to the beginning of the given line, empty when the
    *         line stands on its own
    */
   static String replacementContext(Document document, int line) {
      int first = line;

      while (first > 0) {
         String previous = lineTextAt(document, first - 1).strip();

         if (previous.isEmpty() || previous.startsWith("//") || previous.startsWith("/*")
                 || previous.startsWith("*") || previous.startsWith("//$$")) {
            break;
         }

         if (previous.endsWith(";") || previous.endsWith("{") || previous.endsWith("}")) {
            break;
         }

         --first;
      }

      if (first >= line) {
         return "";
      }

      int start = document.getLineStartOffset(first);
      int end = document.getLineStartOffset(line);

      return end > start ? document.getText(TextRange.create(start, end)) : "";
   }

   private static int inlineCaseBodyStart(Document document, int offset) {
      CharSequence text = document.getImmutableCharSequence();
      int marker = lastIndexOf(text, "/*?", Math.max(0, offset - 1), 0);
      if (marker < 0) {
         return -1;
      } else {
         int previousEnd = lastIndexOf(text, "*/", Math.max(0, offset - 1), 0);
         if (previousEnd <= marker && indexOf(text, "*/", offset, text.length()) >= 0) {
            int separator = indexOf(text, "?", marker + 3, text.length());
            if (separator >= 0 && separator < offset) {
               int body;
               for(body = separator + 1; body < offset && Character.isWhitespace(text.charAt(body)); ++body) {
               }

               return body;
            } else {
               return -1;
            }
         } else {
            return -1;
         }
      }
   }

   static AnnotationAttribute annotationAttribute(String text) {
      int prefixStart;
      for(prefixStart = text.length(); prefixStart > 0 && Character.isJavaIdentifierPart(text.charAt(prefixStart - 1)); --prefixStart) {
      }

      String prefix = text.substring(prefixStart);

      int delimiter;
      for(delimiter = prefixStart - 1; delimiter >= 0 && Character.isWhitespace(text.charAt(delimiter)); --delimiter) {
      }

      if (delimiter >= 0 && (text.charAt(delimiter) == '(' || text.charAt(delimiter) == ',')) {
         String annotation = enclosingAnnotation(text, prefixStart);
         return annotation == null ? null : new AnnotationAttribute(annotation, prefix);
      } else {
         return null;
      }
   }

   static String enclosingAnnotation(String text) {
      return enclosingAnnotation(text, text.length());
   }

   private static String enclosingAnnotation(String text, int endOffset) {
      List<String> parentheses = new ArrayList();
      char quote = 0;

      for(int i = 0; i < endOffset; ++i) {
         char value = text.charAt(i);
         if ((value == '"' || value == '\'') && !isEscaped(text, i)) {
            if (quote == 0) {
               quote = value;
            } else if (quote == value) {
               quote = 0;
            }
         } else if (quote == 0) {
            if (value != '(') {
               if (value == ')' && !parentheses.isEmpty()) {
                  parentheses.remove(parentheses.size() - 1);
               }
            } else {
               int cursor;
               for(cursor = i - 1; cursor >= 0 && Character.isWhitespace(text.charAt(cursor)); --cursor) {
               }

               int end;
               for(end = cursor + 1; cursor >= 0 && (Character.isJavaIdentifierPart(text.charAt(cursor)) || text.charAt(cursor) == '.'); --cursor) {
               }

               String qualified = cursor >= 0 && text.charAt(cursor) == '@' ? text.substring(cursor + 1, end) : "";
               int dot = qualified.lastIndexOf(46);
               String name = dot < 0 ? qualified : qualified.substring(dot + 1);
               parentheses.add(name);
            }
         }
      }

      for(int i = parentheses.size() - 1; i >= 0; --i) {
         if (!((String)parentheses.get(i)).isEmpty()) {
            return (String)parentheses.get(i);
         }
      }

      return null;
   }

   private static boolean isEscaped(String text, int offset) {
      int slashes = 0;

      for(int i = offset - 1; i >= 0 && text.charAt(i) == '\\'; --i) {
         ++slashes;
      }

      return (slashes & 1) != 0;
   }

   static record AnnotationAttribute(String annotation, String prefix) {
   }
}
