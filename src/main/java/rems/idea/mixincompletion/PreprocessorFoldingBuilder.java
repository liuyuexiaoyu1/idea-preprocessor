package rems.idea.mixincompletion;

import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class PreprocessorFoldingBuilder extends FoldingBuilderEx {
   private static final String LINE_PREFIX = "//$$";
   private static final String BLOCK_OPEN = "/*$$";
   private static final String BLOCK_CLOSE = "$*/";

   /** The two branches this dialect writes on a line rather than around a block. */
   private static final String QUESTION = "//?";
   private static final String REPLACE = "//#replace";

   public FoldingDescriptor @NotNull [] buildFoldRegions(@NotNull PsiElement root, @NotNull Document document, boolean quick) {

      // Answered in full either way. A quick pass is asked for on every reparse, and answering it with
      // nothing is what handed these files back to the platform's own folding: a comment got folded as a
      // comment and an import list as an import list, neither of which is what a marker is describing, and
      // the style the plugin draws was replaced by the one it was meant to replace.
      List<FoldingDescriptor> regions = new ArrayList();
      int lineCount = document.getLineCount();
      int line = 0;

      while(line < lineCount) {
         if (contains(document, line, "/*$$")) {
            int last;
            for(last = line; last + 1 < lineCount && !contains(document, last, "$$*/"); ++last) {
            }

            addRegion(regions, root, document, line, last, true);
            line = last + 1;
         } else if (!inactiveLine(document, line)) {
            // A branch written on the line itself: a `//?` case, or a `//#replace` at the end of the code it
            // replaces. Neither is a run of markers, and neither was folded at all - the whole of this dialect
            // that is written per line rather than per block arrived unfolded, which is the one thing folding
            // here exists to prevent.
            int branch = branchStart(document, line);

            if (branch >= 0) {
               addBranch(regions, root, document, line, branch);
            }

            ++line;
         } else {
            int last;
            for(last = line; last + 1 < lineCount && inactiveLine(document, last + 1); ++last) {
            }

            addRegion(regions, root, document, line, last, false);
            line = last + 1;
         }
      }

      FoldingDescriptor[] var10000 = (FoldingDescriptor[])regions.toArray(FoldingDescriptor.EMPTY);

      return var10000;
   }

   public @NotNull String getPlaceholderText(@NotNull ASTNode node) {

      return "//$$ ...";
   }

   public @Nullable String getPlaceholderText(@NotNull ASTNode node, @NotNull TextRange range) {

      PsiFile file = node.getPsi().getContainingFile();
      Document document = file == null ? null : PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
      if (document == null) {
         return "//$$ ...";
      } else {
         int first = document.getLineNumber(range.getStartOffset());
         CharSequence chars = document.getCharsSequence();
         int lineStart = document.getLineStartOffset(first);
         String opening = chars.subSequence(lineStart, document.getLineEndOffset(first)).toString();
         int last = document.getLineNumber(Math.max(range.getStartOffset(), range.getEndOffset() - 1));

         if (opening.contains(REPLACE)) {
            return REPLACE + " inactive line " + (last - first + 1);
         }

         if (MixinCommentContext.caseBodyStart(opening) >= 0) {
            return QUESTION + " inactive line " + (last - first + 1);
         }

         return placeholder(opening.contains("/*$$"), last - first + 1, indentOf(opening));
      }
   }

   /** How far the first thing written on a line is from the margin. */
   static int indentOf(String line) {      int index = 0;

      while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
         ++index;
      }

      return index;
   }

   /**
    * What a closed region shows.
    *
    * <p>A block marker is a comment, so the text standing in for one has to close its own syntax: left open -
    * {@code /*$$ inactive line 3} - the collapsed line reads as a comment that never ends, and the rest of the
    * file reads as though it were inside one. A line marker ends at the line, so it needs no closing.
    *
    * <p>The indentation is part of it. The region begins at the first character of the line - it has to, since
    * a region equal to the platform's comment region is the one dropped - so the text standing in for the code
    * is drawn from column zero, and a block written twenty spaces in came back flush against the margin. What
    * the region cannot say, the text can: the same number of spaces the line is indented by are written in
    * front of it, and the collapsed line then sits where the code it stands for sat.
    *
    * <p>Written here and handed to each region as it is built, rather than left to the platform to ask for
    * afterwards: the marker form, the line count and the indentation are known at this point and nowhere else.
    */
   static String placeholder(boolean block, int lines, int indent) {
      String text = block ? "/*$$ inactive line " + lines + " $$*/" : "//$$ inactive line " + lines;

      return indent <= 0 ? text : " ".repeat(indent) + text;
   }

   public boolean isCollapsedByDefault(@NotNull ASTNode node) {

      return true;
   }

   private static boolean inactiveLine(Document document, int line) {
      int index = document.getLineStartOffset(line);
      int end = document.getLineEndOffset(line);

      CharSequence text;
      for(text = document.getCharsSequence(); index < end && Character.isWhitespace(text.charAt(index)); ++index) {
      }

      if (index + "//$$".length() > end) {
         return false;
      } else {
         for(int offset = 0; offset < "//$$".length(); ++offset) {
            if (text.charAt(index + offset) != "//$$".charAt(offset)) {
               return false;
            }
         }

         int after = index + "//$$".length();
         return after >= end || text.charAt(after) != '*';
      }
   }

   private static boolean contains(Document document, int line, String needle) {
      int start = document.getLineStartOffset(line);
      int limit = document.getLineEndOffset(line) - needle.length();
      CharSequence text = document.getCharsSequence();

      for(int index = start; index <= limit; ++index) {
         int offset;
         for(offset = 0; offset < needle.length() && text.charAt(index + offset) == needle.charAt(offset); ++offset) {
         }

         if (offset == needle.length()) {
            return true;
         }
      }

      return false;
   }

   private static void addRegion(List<FoldingDescriptor> regions, PsiElement root, Document document, int firstLine, int lastLine, boolean block) {
      // From the first character of the line, not from the first character that is not indented.
      //
      // A carried block is a block comment, and the platform folds block comments too: its region covers the
      // comment's own text. Two regions with the same range do not both survive - the later one is dropped -
      // and that is this one, because the folding the editor uses is not reached through an extension point
      // that can be ordered around this one. Starting at the line's own first character makes this region
      // contain the platform's instead of equalling it, and a region inside another is kept.
      int start = document.getLineStartOffset(firstLine);

      int end = document.getLineEndOffset(lastLine);
      if (end > start) {
         // The element the region opens on, not the file. A descriptor hung off the file covers every region
         // in that file as one, so collapsing any of them collapsed all of them - which is the behaviour a
         // reader meets as a fold that will not stay where it was put.
         PsiElement opening = root.findElementAt(start);
         ASTNode node = opening == null ? null : opening.getNode();

         if (node == null) {
            node = root.getNode();
         }

         regions.add(new FoldingDescriptor(node, new TextRange(start, end), null,
                 placeholder(block, lastLine - firstLine + 1, indentOf(document, firstLine))));
      }

   }

   /** How far the first thing written on a line is from the margin. */
   private static int indentOf(Document document, int line) {
      int start = document.getLineStartOffset(line);
      int end = document.getLineEndOffset(line);
      CharSequence text = document.getCharsSequence();
      int index = start;

      while (index < end && Character.isWhitespace(text.charAt(index))) {
         ++index;
      }

      return index - start;
   }

   /**
    * Where a branch written on the line itself begins, or -1 when the line is not one.
    *
    * <p>A {@code //?} case covers the line from its first character, because the line is the branch. A
    * {@code //#replace} covers from the marker to the end of the line: what stands in front of it is the code
    * this version runs, and folding that away as well would hide the line rather than the other version's
    * version of it. Neither is drawn with spaces in front of it - the region begins after the indentation, so
    * the indentation stays where it was.
    */
   private static int branchStart(Document document, int line) {
      String text = lineText(document, line);

      if (MixinCommentContext.caseBodyStart(text) >= 0) {
         return indentOf(text);
      }

      int replacement = text.indexOf(REPLACE);

      return replacement > 0 && !text.substring(0, replacement).isBlank() ? replacement : -1;
   }

   private static void addBranch(List<FoldingDescriptor> regions, PsiElement root, Document document, int line,
                                 int from) {
      int start = document.getLineStartOffset(line) + from;
      int end = document.getLineEndOffset(line);

      if (end <= start) {
         return;
      }

      String text = lineText(document, line);
      String marker = MixinCommentContext.caseBodyStart(text) >= 0 ? QUESTION : REPLACE;
      PsiElement opening = root.findElementAt(start);
      ASTNode node = opening == null ? null : opening.getNode();

      if (node == null) {
         node = root.getNode();
      }

      regions.add(new FoldingDescriptor(node, new TextRange(start, end), null,
              marker + " inactive line 1"));
   }

   private static String lineText(Document document, int line) {
      int length = document.getTextLength();

      return line >= 0 && line < document.getLineCount()
              ? document.getImmutableCharSequence()
                      .subSequence(document.getLineStartOffset(line), Math.min(document.getLineEndOffset(line), length))
                      .toString()
              : "";
   }

   // $FF: synthetic method
   
}
