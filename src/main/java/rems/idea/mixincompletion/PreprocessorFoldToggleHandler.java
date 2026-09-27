package rems.idea.mixincompletion;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.FoldingModel;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseMotionListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.impl.FoldingModelImpl;
import com.intellij.openapi.util.Key;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Clicking a marker folds the code it introduces, and clicking a folded block opens it again.
 *
 * <p>Only the regions this plugin builds answer to it. The platform folds a run of line comments as a comment
 * and an import list as an import list, and those regions lie over the same lines the markers do; a reader who
 * clicks {@code //$$} means that marker, and a handler that takes whichever region happens to start nearest
 * sometimes opens the platform's fold instead - which, from outside, is a marker that does nothing.
 *
 * <p>Opening asks the editor what was clicked rather than working it out from the offset. The characters of a
 * closed region are not on screen, so the offset a click on one reports is a position in the document that
 * need not be the text that was clicked; reading it as the place the region lives is what made part of a
 * folded block dead to opening and part of it live. Whether the click landed on a block's placeholder is a
 * question the editor answers, and its answer holds for the whole of that placeholder.
 *
 * <p>Closing goes the other way: the text is on screen, the offset is the character under the pointer, and the
 * handle is the line the region opens on - the marker, the indentation in front of it, or the code beside it.
 */
public final class PreprocessorFoldToggleHandler implements EditorFactoryListener {
   private static final Key<MouseAdapter> COMPONENT = Key.create("rems.preprocessor.fold.component.listener");
   private static final Key<EditorMouseMotionListener> MOTION = Key.create("rems.preprocessor.fold.motion.listener");
   private static final Object CURSOR_OWNER = new Object();

   public void editorCreated(@NotNull EditorFactoryEvent event) {

      final Editor editor = event.getEditor();
      if (editor.getProject() != null) {
         // A listener of the editor's own, and of the component under it.
         //
         // The component's is the one that matters. A click on a folded block is a click the editor has a use
         // for - it places the caret against the fold - and the editor consumes it; a listener of the editor's
         // is not told about the clicks it has consumed, which is every click on a folded block and no other.
         // A handler that only listens to the editor therefore opens a block when the click is beside it and
         // says nothing when the click is on it, which is the wrong way round. AWT hands the event to every
         // listener on the component whatever anyone did with it, so this one is always told.
         editor.putUserData(COMPONENT, new MouseAdapter() {
            public void mousePressed(@NotNull MouseEvent click) {
               if (click.getButton() == MouseEvent.BUTTON1) {
                  PreprocessorFoldToggleHandler.click(editor, click);
               }
            }
         });
         editor.getContentComponent().addMouseListener(editor.getUserData(COMPONENT));

         EditorMouseMotionListener motion = new EditorMouseMotionListener() {
            public void mouseMoved(@NotNull EditorMouseEvent event) {

               PreprocessorFoldToggleHandler.setCursor(editor, PreprocessorFoldToggleHandler.responds(editor, event));
            }

            // $FF: synthetic method
             
         };
         editor.putUserData(MOTION, motion);
         editor.addEditorMouseMotionListener(motion);

         // A block already closed when the file was opened is drawn before anything here is asked for its text,
         // and for a block written at the margin that drawing is the platform's. Later, because the regions a
         // file is folded by are worked out after the editor exists.
         ApplicationManager.getApplication().invokeLater(() -> collapse(editor));
      }
   }

   /**
    * Closes every region over carried code, and writes its placeholder.
    *
    * <p>Carried code is what a file is not being read for, so it is closed when the file is opened - that is
    * what the folding builder asks for, and what a block written at the margin never got: the region that
    * survives there is the platform's comment fold, whose default state is the platform's setting and not this
    * dialect's wish. Closing it here is what makes a block arrive closed wherever it was written. A reader who
    * opens one keeps it open: this runs once, when the editor is made.
    */
   static void collapse(Editor editor) {
      Document document = editor.getDocument();
      FoldingModel model = editor.getFoldingModel();

      if (document == null || model == null) {
         return;
      }

      model.runBatchFoldingOperation(() -> {
         for (FoldRegion region : model.getAllFoldRegions()) {
            if (!opensOnMarker(document, region)) {
               continue;
            }

            String placeholder = placeholderFor(document, region);

            if (placeholder != null) {
               region.setPlaceholderText(placeholder);
            }

            if (region.isExpanded()) {
               region.setExpanded(false);
            }
         }
      });
   }

   public void editorReleased(@NotNull EditorFactoryEvent event) {

      Editor editor = event.getEditor();
      MouseAdapter click = editor.getUserData(COMPONENT);
      EditorMouseMotionListener motion = (EditorMouseMotionListener)editor.getUserData(MOTION);
      if (click != null) {
         editor.getContentComponent().removeMouseListener(click);
      }

      if (motion != null) {
         editor.removeEditorMouseMotionListener(motion);
      }

      setCursor(editor, false);
   }

   /** Turns a click on the component into a click at a place in the document. */
   private static void click(Editor editor, MouseEvent event) {
      int offset = -1;

      try {
         LogicalPosition position = editor.xyToLogicalPosition(event.getPoint());

         if (position != null) {
            offset = editor.logicalPositionToOffset(position);
         }
      } catch (RuntimeException outside) {
         // A point the editor has no position for - below the last line, or in the margin.
      }

      toggle(editor, event.getPoint(), offset);
   }

   private static void setCursor(Editor editor, boolean hand) {
      if (editor instanceof EditorEx ex) {
         ex.setCustomCursor(CURSOR_OWNER, hand ? Cursor.getPredefinedCursor(12) : null);
      }
   }

   /**
    * Opens the block whose text was clicked, or folds the one whose marker was clicked.
    *
    * <p>Two small places do something, and the rest of the line is left to the editor. Opening is offered on
    * the text standing in for a closed region - the handful of characters a folded block is drawn as - and
    * nowhere else: a click beside it is a click a reader means as a caret, and a block that opens whenever its
    * line is clicked is a block that opens while the file is being edited. Folding is offered on the four
    * characters of a marker, for the same reason.
    *
    * <p>Which text a point is on is asked of the editor rather than worked out from the offset. The characters
    * of a closed region are not on screen, so the offset a click there reports is a position in the document
    * that need not be the text that was clicked - while the editor keeps the place that text occupies, and can
    * say whether a point is inside it.
    */
   static void toggle(Editor editor, @Nullable Point point, int offset) {
      Document document = editor.getDocument();
      FoldingModel model = editor.getFoldingModel();
      FoldRegion onPlaceholder = placeholderAt(model, point);

      if (onPlaceholder != null && !onPlaceholder.isExpanded() && opensOnMarker(document, onPlaceholder)) {
         openSameText(model, document, onPlaceholder);

         return;
      }

      if (!onMarker(document, offset)) {
         return;
      }

      FoldRegion region = markerAt(model, document, offset);

      if (region != null && region.isExpanded()) {
         FoldRegion target = region;
         rename(model, document, target);
         model.runBatchFoldingOperation(() -> target.setExpanded(false));
      }
   }

   /**
    * Writes this dialect's placeholder over the block, in place of whatever it would be drawn as.
    *
    * <p>A block written at the margin is where the range trick runs out. This plugin's region has to contain
    * the platform's comment region to survive - two regions of the same range do not both - and a block at
    * column zero has nothing to the left of it to take in, so the two ranges are the same and the platform's,
    * being built first, is the one that is kept. What the range cannot win is not lost: both regions are over
    * the same code and both carry a placeholder, and the placeholder can be written. Renaming it as the block
    * closes is what puts this dialect's text on screen where the platform's comment marker would have been.
    *
    * <p>Every region over that code is renamed, not only the one that was clicked: the two of them are the same
    * fold to a reader, and leaving one holding the platform's text is a block that says two different things
    * depending on which of them is closed.
    */
   static void rename(FoldingModel model, Document document, FoldRegion region) {
      if (placeholderFor(document, region) == null) {
         return;
      }

      // Its own batch: a placeholder is a property of a fold region, and the platform only lets those be
      // changed inside one.
      model.runBatchFoldingOperation(() -> {
         for (FoldRegion other : model.getAllFoldRegions()) {
            if (opensOnMarker(document, other) && overlaps(other, region)) {
               // Asked of each region rather than copied from the one that was clicked: the two regions over a
               // block do not begin in the same column, and the indentation one of them needs is the doubling
               // the other must not have.
               other.setPlaceholderText(placeholderFor(document, other));
            }
         }
      });
   }

   /**
    * What a region over carried code should read as, in this dialect's spelling.
    *
    * <p>The indentation is written into the text only for a region that begins at the start of its line. A
    * region that begins at the comment - which is what the platform builds - is already drawn where the comment
    * was, so writing the indentation there as well puts the block twice as far in as the code it stands for.
    */
   private static @Nullable String placeholderFor(Document document, FoldRegion region) {
      int length = document.getTextLength();
      int start = Math.max(0, Math.min(region.getStartOffset(), length));
      int end = Math.max(start, Math.min(region.getEndOffset(), length));

      if (end <= start) {
         return null;
      }

      int first = document.getLineNumber(start);
      int last = document.getLineNumber(Math.max(start, end - 1));
      String opening = lineText(document, first);
      boolean atLineStart = start == document.getLineStartOffset(first);
      int indent = atLineStart ? PreprocessorFoldingBuilder.indentOf(opening) : 0;

      if (opening.contains("//#replace")) {
         return "//#replace inactive line " + (last - first + 1);
      }

      if (MixinCommentContext.caseBodyStart(opening) >= 0) {
         return "//? inactive line " + (last - first + 1);
      }

      if (opening.contains("/*$$")) {
         return PreprocessorFoldingBuilder.placeholder(true, last - first + 1, indent);
      }

      if (opening.contains("//$$")) {
         return PreprocessorFoldingBuilder.placeholder(false, last - first + 1, indent);
      }

      return null;
   }

   private static String lineText(Document document, int line) {
      int length = document.getTextLength();

      return line >= 0 && line < document.getLineCount()
              ? document.getImmutableCharSequence()
                      .subSequence(document.getLineStartOffset(line), Math.min(document.getLineEndOffset(line), length))
                      .toString()
              : "";
   }

   /**
    * Opens the block that was clicked on, and any other region over the same code.
    *
    * <p>The same text can carry two regions - this plugin's, and one the platform builds for the markers it
    * has been told are regions of their own - and opening one of them leaves the other closed over the same
    * code. From outside, that is a click on a folded block doing nothing at all.
    */
   private static void openSameText(FoldingModel model, Document document, FoldRegion clicked) {
      List<FoldRegion> same = new ArrayList<>(List.of(clicked));

      for (FoldRegion region : model.getAllFoldRegions()) {
         if (!region.isExpanded() && opensOnMarker(document, region) && overlaps(region, clicked)
                 && !same.contains(region)) {
            same.add(region);
         }
      }

      model.runBatchFoldingOperation(() -> {
         for (FoldRegion region : same) {
            region.setExpanded(true);
         }
      });
   }

   private static boolean overlaps(FoldRegion one, FoldRegion other) {
      return one.getStartOffset() <= other.getEndOffset() && other.getStartOffset() <= one.getEndOffset();
   }

   private static boolean responds(Editor editor, EditorMouseEvent event) {
      Document document = editor.getDocument();
      Point point = event.getMouseEvent().getPoint();
      FoldRegion onPlaceholder = placeholderAt(editor.getFoldingModel(), point);

      if (onPlaceholder != null && !onPlaceholder.isExpanded() && opensOnMarker(document, onPlaceholder)) {
         return true;
      }

      return onMarker(document, event.getOffset())
              && markerAt(editor.getFoldingModel(), document, event.getOffset()) != null;
   }

   /** The region whose placeholder text is at a point on screen, which is the platform's own hit test. */
   private static @Nullable FoldRegion placeholderAt(FoldingModel model, @Nullable Point point) {
      if (point == null || !(model instanceof FoldingModelImpl impl)) {
         return null;
      }

      try {
         return impl.getFoldingPlaceholderAt(point);
      } catch (RuntimeException outside) {
         return null;
      }
   }

   /**
    * The innermost region this plugin built that holds the offset, for a click on a marker inside a block - the
    * closing marker of one, or a later carried line of a run.
    */
   private static FoldRegion markerAt(FoldingModel model, Document document, int offset) {
      if (!onMarker(document, offset)) {
         return null;
      }

      FoldRegion best = null;

      for (FoldRegion region : model.getAllFoldRegions()) {
         if (!opensOnMarker(document, region)) {
            continue;
         }

         if (offset >= region.getStartOffset() && offset <= region.getEndOffset()
                 && (best == null || region.getStartOffset() >= best.getStartOffset())) {
            best = region;
         }
      }

      return best;
   }

   /**
    * Whether a region is one of this plugin's, or one the platform built for the same markers.
    *
    * <p>Not by asking which builder made it - a fold region does not say - but by what it opens on: the first
    * thing on its first line is a marker of this dialect, and no other folding in a Java file opens that way.
    *
    * <p>Whitespace in front of that marker is read past. The two kinds of region over a marker do not agree on
    * where to start - the one this plugin builds begins at the marker, and the one the platform builds for a
    * comment begins at the comment, which for an indented line is the same thing, or at the line, which is not
    * - and a region that is not recognised is a region a click on it will not open.
    */
   private static boolean opensOnMarker(Document document, FoldRegion region) {
      CharSequence text = document.getCharsSequence();
      int length = text.length();
      int start = region.getStartOffset();

      if (start < 0 || start >= length) {
         return false;
      }

      int lineEnd = document.getLineEndOffset(document.getLineNumber(start));
      int at = start;

      while (at < lineEnd && at < length && Character.isWhitespace(text.charAt(at))) {
         at++;
      }

      return startsMarker(text, at) || opensOnBranch(document, at);
   }

   /** Whether the text from an offset begins a branch this dialect writes on a line of its own. */
   private static boolean opensOnBranch(Document document, int offset) {
      if (offset < 0 || offset >= document.getTextLength()) {
         return false;
      }

      int line = document.getLineNumber(offset);
      String text = lineText(document, line);
      int relative = offset - document.getLineStartOffset(line);

      if (MixinCommentContext.caseBodyStart(text) >= 0) {
         return relative == PreprocessorFoldingBuilder.indentOf(text);
      }

      // A replacement at the end of a line has its own start, which is where the region begins: the code in
      // front of it is this version's code and is not part of the fold.
      return relative == text.indexOf("//#replace") && relative >= 0;
   }

   /** Whether the offset is inside one of the four characters of a marker on its own line. */
   static boolean onMarker(Document document, int offset) {
      if (offset < 0 || offset > document.getTextLength()) {
         return false;
      }

      int line = document.getLineNumber(offset);
      int start = document.getLineStartOffset(line);
      int end = Math.min(document.getLineEndOffset(line), document.getTextLength());
      CharSequence text = document.getCharsSequence();

      // All four characters of a marker answer to the click. Matching on where a marker happens to start
      // inside its own run left the first of them dead, and a region whose opening characters do not
      // respond is a region that reads as one which cannot be opened.
      for(int index = start; index + 4 <= end; ++index) {
         if (startsMarker(text, index) && offset >= index && offset < index + 4) {
            return true;
         }
      }

      return onBranch(document, offset);
   }

   /**
    * Whether the offset is anywhere in the test a one-line branch is written under.
    *
    * <p>{@code //? >= 1.21.9 ?} and {@code //#replace < 1.20.5 ?} are one thing to a reader: the test under
    * which the code beside it is this version's code, and the place to point at to fold it. Only the four
    * characters a block is marked with answered to a click, so those two forms folded from the gutter and from
    * nowhere else, and a click on the test itself did nothing at all. The whole test answers now - from the
    * marker to the question mark - and not the code behind it, which is a place a reader puts a caret.
    */
   private static boolean onBranch(Document document, int offset) {
      int line = document.getLineNumber(offset);
      String text = lineText(document, line);
      int relative = offset - document.getLineStartOffset(line);
      boolean question = MixinCommentContext.caseBodyStart(text) >= 0;
      int start = question ? PreprocessorFoldingBuilder.indentOf(text) : text.indexOf("//#replace");

      if (start < 0 || relative < start) {
         return false;
      }

      int after = start + (question ? "//?".length() : "//#replace".length());
      int separator = text.indexOf('?', after);

      return relative <= (separator < 0 ? text.length() : separator);
   }

   /** Whether four characters are one of the markers this dialect writes. */
   static boolean startsMarker(CharSequence text, int index) {
      if (index < 0 || index + 4 > text.length()) {
         return false;
      }

      char first = text.charAt(index);
      char second = text.charAt(index + 1);
      char third = text.charAt(index + 2);
      char fourth = text.charAt(index + 3);

      return first == '/' && second == '/' && third == '$' && fourth == '$'
              || first == '/' && second == '*' && third == '$' && fourth == '$'
              || first == '$' && second == '$' && third == '*' && fourth == '/';
   }

   // $FF: synthetic method
   
}
