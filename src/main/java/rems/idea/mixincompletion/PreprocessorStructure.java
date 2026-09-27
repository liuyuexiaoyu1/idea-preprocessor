package rems.idea.mixincompletion;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.Key;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class PreprocessorStructure {
   private static final String LINE_PREFIX = "//$$";
   private static final String BLOCK_OPEN = "/*$$";
   private static final String BLOCK_CLOSE = "$$*/";

   /** The markers of a document, and the revision they were worked out from. */
   private static final Key<Cached> REGIONS = Key.create("rems.preprocessor.regions");

   private static record Cached(long stamp, List<Region> regions) {
   }

   private PreprocessorStructure() {
   }

   /**
    * The markers of a document, worked out once per revision of it.
    *
    * <p>This is asked for by the click handler and, on every keystroke, by the immediate colouring - which walks
    * the whole file each time it is asked. The answer only changes when the text does.
    */
   static List<Region> regions(Document document) {
      long stamp = document.getModificationStamp();
      Cached cached = (Cached)document.getUserData(REGIONS);

      if (cached != null && cached.stamp() == stamp) {
         return cached.regions();
      }

      List<Region> computed = computeRegions(document);
      document.putUserData(REGIONS, new Cached(stamp, computed));

      return computed;
   }

   private static List<Region> computeRegions(Document document) {
      List<Region> regions = new ArrayList();
      Deque<Frame> frames = new ArrayDeque();
      int lineCount = document.getLineCount();
      int line = 0;

      while(line < lineCount) {
         String text = lineText(document, line);
         int blockStart = text.indexOf("/*$$");
         if (blockStart >= 0 && text.substring(0, blockStart).isBlank()) {
            int last;
            for(last = line; last + 1 < lineCount && lineText(document, last).indexOf("$$*/") < 0; ++last) {
            }

            regions.add(new Region(line, last, List.of(), frames.size()));
            line = last + 1;
         } else if (!hiddenLine(text)) {
            PreprocessorLanguage.ParsedDirective directive = PreprocessorLanguage.parseDirective(text);
            if (directive != null) {
               String name = directive.name();
               if (opens(name)) {
                  frames.push(new Frame(line, new ArrayList()));
               } else if (continues(name)) {
                  if (!frames.isEmpty()) {
                     ((Frame)frames.peek()).branches().add(line);
                  }
               } else if (closes(name)) {
                  if (!frames.isEmpty()) {
                     Frame frame = (Frame)frames.pop();
                     regions.add(new Region(frame.opener(), line, frame.branches(), frames.size()));
                  }
               } else {
                  regions.add(new Region(line, line, List.of(), frames.size()));
               }
            } else {
               switch (caseMarker(text).ordinal()) {
                  case 0:
                     frames.push(new Frame(line, new ArrayList()));
                     break;
                  case 1:
                     if (!frames.isEmpty()) {
                        ((Frame)frames.peek()).branches().add(line);
                     }
                     break;
                  case 2:
                     if (!frames.isEmpty()) {
                        Frame frame = (Frame)frames.pop();
                        regions.add(new Region(frame.opener(), line, frame.branches(), frames.size()));
                     }
                     break;
                  case 3:
                     regions.add(new Region(line, line, List.of(), frames.size()));
                     break;
                  case 4:
                     if (inlineBlockMarker(text)) {
                        regions.add(new Region(line, line, List.of(), frames.size()));
                     }
               }
            }

            ++line;
         } else {
            int last;
            for(last = line; last + 1 < lineCount && hiddenLine(lineText(document, last + 1)); ++last) {
            }

            regions.add(new Region(line, last, List.of(), frames.size()));
            line = last + 1;
         }
      }

      while(!frames.isEmpty()) {
         Frame frame = (Frame)frames.pop();
         regions.add(new Region(frame.opener(), lineCount - 1, frame.branches(), frames.size()));
      }

      regions.sort((first, second) -> Integer.compare(first.startLine(), second.startLine()));
      return regions;
   }

   static @Nullable Region regionAt(Document document, int line) {
      Region best = null;

      for(Region region : regions(document)) {
         if (region.startLine() > line) {
            break;
         }

         if ((line <= region.endLine() || region.branches().contains(line)) && (best == null || region.depth() >= best.depth())) {
            best = region;
         }
      }

      return best;
   }

   static int partner(Document document, int line) {
      for(Region region : regions(document)) {
         if (region.startLine() == line) {
            return region.endLine();
         }

         if (region.endLine() == line) {
            return region.startLine();
         }

         if (region.branches().contains(line)) {
            return region.startLine();
         }
      }

      return -1;
   }

   static List<Integer> partners(Document document, int line) {
      for(Region region : regions(document)) {
         if (region.startLine() == line || region.endLine() == line || region.branches().contains(line)) {
            List<Integer> result = new ArrayList();
            result.add(region.startLine());
            result.addAll(region.branches());
            result.add(region.endLine());
            result.removeIf((candidate) -> candidate == line);
            return result;
         }
      }

      return List.of();
   }

   private static boolean opens(String name) {
      return name.equals("if") || name.equals("ifdef") || name.equals("ifndef") || name.equals("case");
   }

   private static boolean continues(String name) {
      return name.equals("else") || name.equals("elseif") || name.equals("elif");
   }

   private static boolean closes(String name) {
      return name.equals("endif") || name.equals("endcase");
   }

   private static CaseMarker caseMarker(String text) {
      int marker = text.indexOf("//?");
      if (marker >= 0 && text.substring(0, marker).isBlank()) {
         String rest = text.substring(marker + 3).strip();
         if (rest.startsWith("}")) {
            return PreprocessorStructure.CaseMarker.CLOSE;
         } else if (rest.startsWith("else")) {
            return PreprocessorStructure.CaseMarker.BRANCH;
         } else if (!rest.startsWith("ifdef") && !rest.startsWith("ifndef")) {
            if (rest.startsWith("if")) {
               return rest.indexOf(63) < 0 ? PreprocessorStructure.CaseMarker.OPEN : PreprocessorStructure.CaseMarker.INLINE;
            } else {
               return rest.indexOf(63) >= 0 ? PreprocessorStructure.CaseMarker.INLINE : PreprocessorStructure.CaseMarker.OPEN;
            }
         } else {
            return PreprocessorStructure.CaseMarker.OPEN;
         }
      } else {
         return PreprocessorStructure.CaseMarker.NONE;
      }
   }

   private static boolean inlineBlockMarker(String text) {
      String stripped = text.stripLeading();
      return stripped.startsWith("/*?") || stripped.startsWith("/*#case");
   }

   private static boolean hiddenLine(String text) {
      int index;
      for(index = 0; index < text.length() && Character.isWhitespace(text.charAt(index)); ++index) {
      }

      if (!text.startsWith("//$$", index)) {
         return false;
      } else {
         int after = index + "//$$".length();
         return after >= text.length() || text.charAt(after) != '*';
      }
   }

   private static String lineText(Document document, int line) {
      return line >= 0 && line < document.getLineCount() ? document.getCharsSequence().subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString() : "";
   }

   static record Region(int startLine, int endLine, List<Integer> branches, int depth) {
   }

   private static enum CaseMarker {
      OPEN,
      BRANCH,
      CLOSE,
      INLINE,
      NONE;

      // $FF: synthetic method
      private static CaseMarker[] $values() {
         return new CaseMarker[]{OPEN, BRANCH, CLOSE, INLINE, NONE};
      }
   }

   private static record Frame(int opener, List<Integer> branches) {
   }
}
