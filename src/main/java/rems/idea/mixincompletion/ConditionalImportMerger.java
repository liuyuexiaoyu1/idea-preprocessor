package rems.idea.mixincompletion;

import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Writes an import that belongs to one version into the marker that version is written under.
 *
 * <p>A class offered inside a branch is a class that branch has; written as a plain import it would be a name
 * the other versions cannot compile against. So the import is written under the same condition the branch is
 * written under, in whichever of the two spellings the file's dialect takes.
 *
 * <p>The condition is not the one on the nearest {@code //#if}. A branch reached through {@code //#else} holds
 * when the branches above it do not, and one reached through {@code //#elseif} holds when that branch's own
 * condition does and the ones above it do not. Every enclosing level contributes, each with the branch the
 * line is actually in - which is why an import completed inside an {@code //#else} used to come out under the
 * condition it was the opposite of, in a block no version would ever open.
 */
final class ConditionalImportMerger {
   private static final Logger LOG = Logger.getInstance(ConditionalImportMerger.class);

   private static final Pattern QUESTION_IMPORT = Pattern.compile("^\\s*(?:(?://\\$\\$\\s*)+)?//\\?\\s*(.+?)\\s*\\?\\s*import\\s+([A-Za-z_$][A-Za-z0-9_$.]*)\\s*;?\\s*$");
   private static final Pattern MARKED_IMPORT = Pattern.compile("^\\s*(?://\\$\\$\\s*)+import\\s+([A-Za-z_$][A-Za-z0-9_$.]*)\\s*;?\\s*$");
   private static final Pattern BLOCK_IMPORT = Pattern.compile("^\\s*/\\*\\$\\$\\s*import\\s+([A-Za-z_$][A-Za-z0-9_$.]*)\\s*;?\\s*(?:\\$\\*/)?\\s*$");
   /**
    * A carried import, in either spelling, at the head of a line.
    *
    * <p>Anchored: the marker is what says the line is a carried import rather than code that mentions the word
    * - and reading {@code import} anywhere would take {@code //$$import a.b.C;} as a name only if the marker in
    * front of it is allowed to stand there, which is what the earlier form of this refused to do.
    */
   private static final Pattern ANY_IMPORT =
           Pattern.compile("^\\s*(?://\\$\\$\\s*|/\\*\\$\\$\\s*)?import\\s+([A-Za-z_$][A-Za-z0-9_$.]*)\\s*;");

   /** One comparison: the variable, the operator, and what it is compared against. */
   private static final Pattern COMPARISON =
           Pattern.compile("^\\s*(?:(MC)\\s+)?(>=|<=|==|!=|>|<|not\\s+in|in)\\s+(.+?)\\s*$", Pattern.CASE_INSENSITIVE);

   /**
    * What each operator becomes when the branch that carries it is not the one taken.
    *
    * <p>Read off the comparison rather than written as a negation: {@code MC >= 12101} is false exactly when
    * {@code MC < 12101} is true, and a spelling the preprocessor understands is the point - {@code not (...)}
    * is not one of them. An operator with no opposite here is a condition this will not rewrite.
    */
   private static final Map<String, String> OPPOSITE = Map.of(
           ">=", "<",
           "<=", ">",
           "==", "!=",
           "!=", "==",
           ">", "<=",
           "<", ">=");

   private ConditionalImportMerger() {
   }

   /** Adds the import for a class offered in completion, when the place it was offered in is conditional. */
   static void addImport(InsertionContext context, PsiClass type) {
      Editor editor = context.getEditor();
      PsiFile file = context.getFile();
      Project project = editor.getProject();
      InjectedLanguageManager manager = InjectedLanguageManager.getInstance(project);
      PsiFile host = file == null ? null : manager.getTopLevelFile(file);

      if (!(host instanceof PsiJavaFile)) {
         return;
      }

      // The place has to be read in the host file, not in the fragment the completion happened in.
      //
      // Carried code is injected as Java, so completion inside a marker runs against a fragment - a file of a
      // few lines holding the carried text inside a class shell - and the caret is a place in that fragment.
      // The fragment's document is a window onto the host's and can say where that is in the file; what is
      // written to is the host document behind the window, a window taking no edits.
      //
      // Whether there is a fragment is asked of the file, which is what the platform answers this question
      // with everywhere else. Comparing the editor's document against the host's looked like the same test and
      // is not: a fragment's document and the window over the host are two different documents, so a caret in
      // a fragment was read as a caret in the file - four lines into the fragment being a line of the import
      // list in the file, a line with no condition under it and nothing to write.
      // Where the element was inserted, not where the caret happens to be. The editor is asked for its caret
      // and answered with a place somewhere else entirely - the completion's own editor is the host's, and the
      // caret has moved by the time this runs, so a completion on the line an import belongs to was read as a
      // line of the import list. What the insertion context keeps is the offset the element went in at.
      //
      // The window comes from the fragment's own view provider. The host file's provider hands back the host
      // document - it is the outermost file that is asked, and a file that merely contains injections is not
      // wrapped in a window at all - so the check below never held, the offset was never mapped, and a place
      // four lines into the fragment went on being read as a place in the file.
      Document editorDocument = editor.getDocument();
      Document fragmentDocument = file == null ? null : file.getViewProvider().getDocument();
      boolean editorOnFragment = file != null && manager.isInjectedFragment(file)
              && editorDocument == fragmentDocument;
      Document hostDocument = host.getViewProvider().getDocument();
      int offset = context.getStartOffset();

      if (editorOnFragment && fragmentDocument instanceof DocumentWindow window && window.isValid()) {
         offset = window.injectedToHost(offset);
         hostDocument = window.getDelegate();
      }

      if (hostDocument == null) {
         return;
      }

      LOG.info("preprocessor import: at=" + offset + " editorOnFragment=" + editorOnFragment
              + " host=" + host.getName());

      addImport(project, hostDocument, (PsiJavaFile)host, offset, type);
   }

   /** The same, reachable without an insertion: it needs a file, a place in it, and the class to name. */
   static void addImport(Project project, Document document, PsiJavaFile javaFile, int offset, PsiClass type) {
      String qualified = type.getQualifiedName();

      if (qualified == null || qualified.isBlank()) {
         return;
      }

      int line = document.getLineNumber(Math.max(0, Math.min(offset, document.getTextLength())));
      String condition = enclosingCondition(document, line);

      // Said out loud whatever the answer is. A completion inside a branch that leaves no import behind has
      // exactly three explanations - no condition was read, no branch under that condition was found, or the
      // name was already there - and from outside they look the same: nothing happened.
      LOG.info("preprocessor import: file=" + javaFile.getName() + " line=" + (line + 1)
              + " text=[" + lineText(document, line).strip() + "]"
              + " condition=[" + condition + "] qualified=" + qualified);

      // A name the file already imports unconditionally needs nothing written for it: a second import of one
      // name is a file that does not compile, and a conditional one beside an ordinary one is that second
      // import. The platform writes the ordinary one; this only ever adds the conditional kind.
      if (alreadyImported(javaFile, qualified)) {
         LOG.info("preprocessor import: nothing written; " + qualified + " is already imported");

         return;
      }

      // A branch the version being built takes is ordinary code: this is the source it compiles, so the import
      // belongs to the file as a whole. Written by the platform's own import machinery, which is what completion
      // in live code uses - it knows the file's import layout, whether the name is in scope already, and where
      // the import list ends. Only a branch this version does not take - code carried for another version -
      // needs the condition written around its import, and that is the one thing the platform cannot do.
      int main = PreprocessorLanguage.moduleVersionCode(javaFile);
      boolean taken = main >= 0 && PreprocessorLanguage.isLineActive(document, line, Map.of("MC", main));

      LOG.info("preprocessor import: version=" + main + " taken=" + taken + " module=" + javaFile.getName());

      if (taken) {
         // Said out loud because this is the one step the platform does for live code and cannot do here: the
         // element being completed is inside an injected fragment, and adding the import means writing to a
         // different file than the one the completion is in. What the log records is whether the call found
         // anything to do and what the file's imports were before and after it.
         modify(project, "Add import", () -> {
            String before = javaFile.getImportList() == null ? "" : javaFile.getImportList().getText();
            JavaCodeStyleManager.getInstance(project).addImport(javaFile, type);
            String after = javaFile.getImportList() == null ? "" : javaFile.getImportList().getText();

            LOG.info("preprocessor import: platform import for " + qualified
                    + " changed=" + !before.equals(after) + " after=[" + after.replace('\n', ' ').strip() + "]");
         });

         return;
      }

      // A place with no condition around it is ordinary code, and an ordinary import is the platform's to
      // write. Nothing is added here: two imports for one name is a file that does not compile.
      if (condition == null || condition.isBlank()) {
         return;
      }

      int areaEnd = importAreaEnd(javaFile, document);
      int container = importContainer(document, line, areaEnd, condition);

      LOG.info("preprocessor import: branch=" + (container < 0 ? "none" : String.valueOf(container + 1))
              + " extended=" + PreprocessorLanguage.usesLiuyuePreprocessor(javaFile));

      if (container >= 0) {
         if (!holds(document, container, qualified)) {
            modify(project, "Add conditional import", () -> place(document, container, condition, qualified));
         }

         return;
      }

      boolean extended = PreprocessorLanguage.usesLiuyuePreprocessor(javaFile);
      int target = Math.max(0, Math.min(areaEnd, document.getLineCount() - 1));
      String statement = extended
              ? "//? " + condition + " ? import " + qualified + ";"
              : markedForm(condition, qualified);

      // At the end of the ordinary imports, which is not the line in front of the class.
      //
      // An import list is written in two parts - the ordinary names first, then the static ones - and the line
      // in front of the class sits beyond the static part. A conditional import is an ordinary one, so it
      // belongs where the ordinary ones end, in front of the static section rather than behind it. A file whose
      // imports are all static, or that has none at all, has no such place and takes the line it always took.
      int last = lastPlainImport(document, areaEnd);
      int at = last < 0 ? target : last;
      boolean after = last >= 0;

      modify(project, "Add conditional import", () -> {
         if (after) {
            insertAfter(document, at, statement);
         } else {
            insertBefore(document, at, statement);
         }
      });
   }

   /**
    * The last import in a file that belongs with the names this plugin writes, or -1 when there is none.
    *
    * <p>Two kinds of line are not it. A static import has a section of its own, and a conditional import is
    * not a member of it. The platform's own packages - {@code java.*} and {@code javax.*} - are another section
    * again, and where it sits is the project's arrangement rather than a fixed one: an import list may keep them
    * first or last. Taking whichever ordinary import came last put a class the project does not own among the
    * standard library's, which in a list with the platform section at the end means the very last line of it.
    *
    * <p>Conditional imports do count: a carried one is an ordinary import written the dialect's way, and a
    * second one belongs beside the first.
    */
   private static int lastPlainImport(Document document, int areaEnd) {
      int last = -1;

      for (int line = 0; line <= areaEnd && line < document.getLineCount(); ++line) {
         String text = lineText(document, line);
         String trimmed = text.stripLeading();

         if (trimmed.startsWith("import static ") || platformImport(trimmed)) {
            continue;
         }

         if (trimmed.startsWith("import ") || ANY_IMPORT.matcher(text).find()
                 || QUESTION_IMPORT.matcher(text).matches()) {
            last = line;
         }
      }

      return last;
   }

   /** Whether an import line names something out of the platform's own packages. */
   private static boolean platformImport(String line) {
      return line.startsWith("import java.") || line.startsWith("import javax.");
   }

   /**
    * Makes the change, in whatever write access there already is.
    *
    * <p>This runs from a completion's insert, which is already inside one, and a write command started inside a
    * write action is refused - so a completion inside a version branch wrote its import and the import was not
    * there. Outside one, a command is what makes the change undoable in one step.
    */
   private static void modify(Project project, String name, Runnable change) {
      Application application = ApplicationManager.getApplication();

      if (application.isWriteAccessAllowed()) {
         change.run();

         return;
      }

      WriteCommandAction.runWriteCommandAction(project, name, (String)null, change, new PsiFile[0]);
   }

   static void mergeIfNeeded(Project project, Document document, PsiJavaFile file, int offset) {
      int line = document.getLineNumber(Math.max(0, Math.min(offset, document.getTextLength())));
      CarriedImport carried = parse(lineText(document, line));

      if (carried == null) {
         return;
      }

      String condition = carried.condition() == null ? enclosingCondition(document, line) : carried.condition();

      if (condition == null || condition.isBlank()) {
         return;
      }

      int target = importContainer(document, line, importAreaEnd(file, document), condition);

      if (target >= 0 && !holds(document, target, carried.qualified())) {
         modify(project, "Merge conditional import", () -> place(document, target, condition, carried.qualified()));
      }
   }

   /**
    * The condition a line is written under, from every level that encloses it.
    *
    * <p>Each level contributes the branch the line turned out to be in: the condition of the {@code //#if} it
    * opened with, or, for a branch reached any other way, the opposite of every branch above it in that level.
    * Null when a branch cannot be put into words - a comparison this cannot turn around, or a form it does not
    * read - because a wrong condition is worse than no import at all.
    */
   static @Nullable String enclosingCondition(Document document, int line) {
      List<Branch> stack = new ArrayList<>();

      for (int current = 0; current <= line && current < document.getLineCount(); ++current) {
         String text = lineText(document, current);
         PreprocessorLanguage.ParsedDirective directive = PreprocessorLanguage.parseDirective(text);

         if (directive == null) {
            continue;
         }

         if (PreprocessorLanguage.opensConditional(text)) {
            stack.add(new Branch(conditionOf(directive)));
         } else if (MixinCommentSyntaxAnnotator.continuesConditional(directive.name())) {
            if (!stack.isEmpty()) {
               stack.get(stack.size() - 1).leave(directive.argument());
            }
         } else if (PreprocessorLanguage.closesConditional(text) && !stack.isEmpty()) {
            stack.remove(stack.size() - 1);
         }
      }

      List<String> parts = new ArrayList<>();

      for (Branch branch : stack) {
         String taken = branch.taken();

         if (taken != null) {
            parts.add(taken.strip());

            continue;
         }

         for (String left : branch.left()) {
            String opposite = negate(left);

            if (opposite == null) {
               return null;
            }

            parts.add(opposite);
         }
      }

      return parts.isEmpty() ? null : String.join(" && ", parts);
   }

   /**
    * The opposite of a condition, as the dialect spells it.
    *
    * <p>A comparison turns its operator around: {@code MC >= 12101} is false exactly when {@code MC < 12101}
    * is true, {@code MC <= 26.1} turns into {@code MC > 26.1}, {@code MC == x} into {@code MC != x}, and each
    * of those back again. A range turns {@code in} into {@code not in}. A defining turns into its own
    * negation, {@code defined(X)} into {@code !defined(X)}.
    *
    * <p>Whole conditions are built out of those, so the same applies through them: {@code A && B} is not
    * false merely because one side is, and it is written as {@code !A || !B}; {@code A || B} becomes
    * {@code !A && !B}; a bundled group keeps its brackets and is turned around inside; a leading {@code !} is
    * taken off and what it covered is asked for as it stands. Null when some part of it has no opposite this
    * knows how to write - a name on its own, a form that is not read - because writing a wrong condition under
    * an import is worse than writing none.
    */
   static @Nullable String negate(String condition) {
      try {
         return new Opposite(condition).run();
      } catch (IllegalArgumentException unreadable) {
         return null;
      }
   }

   /** The condition a directive states, in the form the other conditions are written in. */
   private static String conditionOf(PreprocessorLanguage.ParsedDirective directive) {
      String argument = directive.argument() == null ? "" : directive.argument().strip();

      if ("ifdef".equals(directive.name())) {
         return "defined(" + argument + ")";
      }

      if ("ifndef".equals(directive.name())) {
         return "!defined(" + argument + ")";
      }

      return argument;
   }

   private static @Nullable CarriedImport parse(String text) {
      Matcher question = QUESTION_IMPORT.matcher(text);

      if (question.matches()) {
         return new CarriedImport(question.group(1).strip(), question.group(2));
      }

      Matcher marked = MARKED_IMPORT.matcher(text);

      if (marked.matches()) {
         return new CarriedImport((String)null, marked.group(1));
      }

      Matcher block = BLOCK_IMPORT.matcher(text);

      return block.matches() ? new CarriedImport((String)null, block.group(1)) : null;
   }

   private static int importAreaEnd(PsiJavaFile file, Document document) {
      if (file.getClasses().length == 0) {
         return document.getLineCount() - 1;
      }

      int start = file.getClasses()[0].getTextRange().getStartOffset();

      return document.getLineNumber(Math.max(0, Math.min(start, document.getTextLength())));
   }

   /**
    * Where an import for this condition goes.
    *
    * <p>A branch written already under the same condition, in either spelling, is where it belongs: the name
    * goes beside the ones that are already known to exist for that version. Which branch that is cannot be
    * told from the text of one {@code //#if} alone - {@code >= 12101} nested inside {@code < 13000} is a
    * different condition from the same line written outside it - so each candidate is asked for the condition
    * of its own branch rather than read as the one line it is.
    */
   private static int importContainer(Document document, int from, int areaEnd, String condition) {
      int question = -1;

      for (int line = 0; line <= areaEnd && line < document.getLineCount(); ++line) {
         if (line == from) {
            continue;
         }

         String text = lineText(document, line);

         if (PreprocessorLanguage.opensConditional(text)) {
            String own = enclosingCondition(document, line);

            if (condition.equals(own)) {
               // The head of the branch: where inside it the name goes is the shape of the branch, which is
               // what the writer looks at rather than this search.
               return line;
            }
         }

         if (question < 0) {
            Matcher matcher = QUESTION_IMPORT.matcher(text);

            if (matcher.matches() && matcher.group(1).strip().equals(condition)) {
               question = line;
            }
         }
      }

      return question;
   }

   private static int conditionalEnd(Document document, int start, int areaEnd) {
      int depth = 0;

      for (int line = start; line <= areaEnd && line < document.getLineCount(); ++line) {
         String text = lineText(document, line);

         if (PreprocessorLanguage.opensConditional(text)) {
            ++depth;
         } else if (PreprocessorLanguage.closesConditional(text)) {
            --depth;

            if (depth == 0) {
               return line;
            }
         }
      }

      return areaEnd;
   }

   private static boolean holds(Document document, int container, String qualified) {
      String text = lineText(document, container);

      if (QUESTION_IMPORT.matcher(text).matches() && text.contains(qualified + ";")) {
         return true;
      }

      int start = blockStart(document, container);
      int end = start < 0 ? container : conditionalEnd(document, start, document.getLineCount() - 1);

      for (int line = start < 0 ? container : start; line <= end && line < document.getLineCount(); ++line) {
         if (lineText(document, line).contains(qualified + ";")) {
            return true;
         }
      }

      return false;
   }

   /** Where the branch a line is inside of begins; -1 when the line is not inside one. */
   private static int blockStart(Document document, int line) {
      for (int current = Math.min(line, document.getLineCount() - 1); current >= 0; --current) {
         String text = lineText(document, current);

         if (PreprocessorLanguage.opensConditional(text)) {
            return current;
         }
      }

      return -1;
   }

   /**
    * Puts one name into the branch the container belongs to, in the shape that branch already has.
    *
    * <p>An extended {@code //?} line holds one import and says its condition once, so another name for the same
    * condition is another line of the same shape. A branch is a block of carried code, and a block holding
    * several names is written as one carried group - so a second name rewrites the group rather than leaving a
    * line of one shape beside a group of the other. A branch holding nothing yet takes the shortest form there
    * is, one carried line.
    */
   private static void place(Document document, int container, String condition, String qualified) {
      String text = lineText(document, container);
      Matcher question = QUESTION_IMPORT.matcher(text);

      if (question.matches()) {
         // One name per line is what this form is: the condition is written on the line it applies to, so a
         // second name under that condition cannot be put on the same line. It goes into the form that holds
         // several - the carried group the other spelling uses - carrying the condition the line already said.
         List<String> names = new ArrayList<>();
         names.add(question.group(2));

         if (!names.contains(qualified)) {
            names.add(qualified);
         }

         groupLine(document, container, condition, names);

         return;
      }

      int start = blockStart(document, container);

      if (start < 0) {
         insertAfter(document, container, "//$$ import " + qualified + ";");

         return;
      }

      int existing = carriedLine(document, start, conditionalEnd(document, start, document.getLineCount() - 1));

      LOG.info("preprocessor import: place branch=" + (start + 1) + " carried=" + (existing + 1)
              + " container=" + (container + 1));

      if (existing < 0) {
         insertAfter(document, start, "//$$ import " + qualified + ";");

         return;
      }

      Matcher carried = QUESTION_IMPORT.matcher(lineText(document, existing));

      if (carried.matches()) {
         // The same form as above, reached through the branch instead of through the line: a second name
         // under one condition becomes the carried group, which is the form that holds more than one.
         List<String> names = new ArrayList<>();
         names.add(carried.group(2));

         if (!names.contains(qualified)) {
            names.add(qualified);
         }

         groupLine(document, existing, condition, names);
      } else {
         rewriteBlock(document, existing, condition, qualified);
      }
   }

   /** Where in a branch its first carried import is written, or -1 when it holds none. */
   private static int carriedLine(Document document, int start, int end) {
      for (int line = start + 1; line <= end && line < document.getLineCount(); ++line) {
         String text = lineText(document, line);

         if (text.contains("$$") || MARKED_IMPORT.matcher(text).matches()
                 || QUESTION_IMPORT.matcher(text).matches()) {
            return line;
         }
      }

      return -1;
   }

   /**
    * Rewrites a branch's carried imports as one group, with this name among them.
    *
    * <p>Whatever the branch already holds, in whatever spelling: a run of {@code //$$} lines, one group, or
    * both at once - which is what a branch written to over time looks like, since each name added before this
    * existed was written as a line of its own. Everything the branch holds is read out, the new name goes in
    * with it, and the import half of the branch becomes one group. What is not an import is left alone.
    */
   private static void rewriteBlock(Document document, int container, String condition, String qualified) {
      int start = blockStart(document, container);

      if (start < 0) {
         return;
      }

      int end = conditionalEnd(document, start, document.getLineCount() - 1);

      // A branch runs from its opening to wherever the next one begins, and what is under that next one is not
      // this branch's code. Taken as one stretch to the closing line, the imports of the else half were read as
      // this half's and moved up into the group - the name the other version imports, carried under this
      // version's condition.
      int stop = branchEnd(document, start, end);
      List<String> names = importsIn(document, start, stop);

      if (!names.contains(qualified)) {
         names.add(qualified);
      }

      StringBuilder replacement = new StringBuilder("//#if ").append(condition).append('\n').append(groupText(names));

      for (int line = start + 1; line < stop; ++line) {
         String text = lineText(document, line);

         if (isCarriedImport(text)) {
            continue;
         }

         replacement.append('\n').append(withoutGroupMarkers(text, line, start, stop));
      }

      int last = Math.max(start, stop - 1);

      document.replaceString(document.getLineStartOffset(start), document.getLineEndOffset(last),
              replacement.toString());
   }

   /**
    * Where this branch ends and the next one starts, or the closing line when there is no next one.
    *
    * <p>An else is the boundary: it says the condition above it stops holding here.
    */
   private static int branchEnd(Document document, int start, int end) {
      for (int line = start + 1; line < end; ++line) {
         String text = lineText(document, line).stripLeading();

         if (text.startsWith("//#else")) {
            return line;
         }
      }

      return end;
   }

   /** Whether a line is a carried import in one of its spellings. */
   private static boolean isCarriedImport(String text) {
      return MARKED_IMPORT.matcher(text).matches() || QUESTION_IMPORT.matcher(text).matches()
              || ANY_IMPORT.matcher(text).find();
   }

   /**
    * A line of a branch with the group markers taken off it.
    *
    * <p>The first and last lines of a group share their line with the marker: the code that was there keeps its
    * place, and the group is written around it rather than over it.
    */
   private static String withoutGroupMarkers(String text, int line, int start, int end) {
      String result = text;

      if (line == start + 1) {
         int at = result.indexOf("/*$$");

         if (at >= 0) {
            result = result.substring(0, at) + result.substring(at + "/*$$".length());
         }
      }

      if (line == end - 1) {
         int at = result.lastIndexOf("$$*/");

         if (at >= 0) {
            result = result.substring(0, at) + result.substring(at + "$$*/".length());
         }
      }

      return result;
   }

   /** Every carried name a branch holds, however the branch spells them. */
   private static List<String> importsIn(Document document, int start, int end) {
      List<String> names = new ArrayList<>();

      for (int line = start; line <= end && line < document.getLineCount(); ++line) {
         Matcher matcher = ANY_IMPORT.matcher(lineText(document, line));

         while (matcher.find()) {
            String name = matcher.group(1);

            if (!names.contains(name)) {
               names.add(name);
            }
         }
      }

      return names;
   }

   /** A branch holding one name: the shortest form the dialect has for a carried import. */
   private static String markedForm(String condition, String qualified) {
      return "//#if " + condition + "\n//$$ import " + qualified + ";\n//#endif";
   }

   /** A branch holding several: one carried group, which is what the dialect uses for more than one name. */
   private static String groupForm(String condition, List<String> qualified) {
      return "//#if " + condition + "\n" + groupText(qualified) + "\n//#endif";
   }

   /** The carried group itself, without the condition around it. */
   private static String groupText(List<String> qualified) {
      StringBuilder text = new StringBuilder("/*$$");

      for (int index = 0; index < qualified.size(); ++index) {
         if (index > 0) {
            text.append('\n');
         }

         text.append("import ").append(qualified.get(index)).append(';');
      }

      return text.append("$$*/").toString();
   }

   /**
    * Turns a {@code //?} line into a carried group holding both names.
    *
    * <p>The condition is put around it only when the line is not already inside one. These lines are written
    * both ways - on their own, and inside the {@code //#if} that says the same thing in the other spelling -
    * and a group written under a branch that is already there is a branch inside a branch.
    */
   private static void groupLine(Document document, int line, String condition, List<String> names) {
      if (blockStart(document, line) >= 0) {
         replaceLine(document, line, groupText(names));
      } else {
         replaceLine(document, line, groupForm(condition, names));
      }
   }

   private static void insertBefore(Document document, int line, String text) {
      document.insertString(document.getLineStartOffset(line), text + "\n");
   }

   private static void insertAfter(Document document, int line, String text) {
      document.insertString(document.getLineEndOffset(line), "\n" + text);
   }

   /** Replaces what a line holds, leaving the line break where it is. */
   private static void replaceLine(Document document, int line, String text) {
      document.replaceString(document.getLineStartOffset(line), document.getLineEndOffset(line), text);
   }

   /**
    * Whether the file already imports this name - by an ordinary import, or by one carried under a condition.
    *
    * <p>Both are read from the same place the fragments are built from, so a name resolution can already see is
    * a name no second import is written for. Two imports of one name do not compile, and that includes one
    * ordinary import with a carried one beside it.
    */
   private static boolean alreadyImported(PsiJavaFile file, String qualified) {
      for (String line : VersionedCodeFragmentResolver.importsOf(file).split("\n")) {
         if (qualified.equals(line.strip())) {
            return true;
         }
      }

      return false;
   }

   private static String lineText(Document document, int line) {
      int length = document.getTextLength();

      return line >= 0 && line < document.getLineCount()
              ? document.getImmutableCharSequence().subSequence(document.getLineStartOffset(line),
                      Math.min(document.getLineEndOffset(line), length)).toString()
              : "";
   }

   /** A side of a rewritten condition is bracketed when it is itself a condition, and not twice over. */
   private static String bundle(String text) {
      if (!text.contains("&&") && !text.contains("||")) {
         return text;
      }

      return bracketed(text) ? text : "(" + text + ")";
   }

   /** Whether the whole of a text is one bracket group of its own. */
   private static boolean bracketed(String text) {
      String trimmed = text.strip();

      if (!trimmed.startsWith("(") || !trimmed.endsWith(")")) {
         return false;
      }

      int depth = 0;

      for (int index = 0; index < trimmed.length(); ++index) {
         char value = trimmed.charAt(index);

         if (value == '(') {
            ++depth;
         } else if (value == ')') {
            --depth;

            if (depth == 0) {
               return index == trimmed.length() - 1;
            }
         }
      }

      return false;
   }

   /**
    * A reader that answers with the opposite of what it reads.
    *
    * <p>Walks an expression the way the preprocessor's own parser does - or, then and, then a value - handing
    * back the opposite instead of the value: an {@code &&} comes out as an {@code ||}, and each side is asked
    * for its own opposite. What it cannot turn around it refuses, and the refusal leaves as an exception rather
    * than as half a condition.
    */
   private static final class Opposite {
      private static final Pattern DEFINED = Pattern.compile("^!?\\s*defined\\s*\\([^)]*\\)$");

      private final String source;
      private int at;

      private Opposite(String source) {
         this.source = source;
      }

      private String run() {
         String text = this.negateOr();
         this.skipSpace();

         if (this.at != this.source.length()) {
            throw new IllegalArgumentException(this.source);
         }

         return text;
      }

      private String negateOr() {
         String left = this.negateAnd();

         while (true) {
            this.skipSpace();

            if (!this.starts("||")) {
               return left;
            }

            this.at += 2;
            left = bundle(left) + " && " + bundle(this.negateAnd());
         }
      }

      private String negateAnd() {
         String left = this.negateValue();

         while (true) {
            this.skipSpace();

            if (!this.starts("&&")) {
               return left;
            }

            this.at += 2;
            left = bundle(left) + " || " + bundle(this.negateValue());
         }
      }

      private String negateValue() {
         this.skipSpace();

         if (this.starts("!")) {
            this.at++;

            return this.value();
         }

         if (this.starts("(")) {
            this.at++;
            String inner = this.negateOr();
            this.skipSpace();

            if (!this.starts(")")) {
               throw new IllegalArgumentException(this.source);
            }

            this.at++;

            return "(" + inner + ")";
         }

         return this.oppositeOf(this.value());
      }

      /** One value as it stands: a comparison, a defining, or whatever else is written in one piece. */
      private String value() {
         this.skipSpace();
         int start = this.at;
         int depth = 0;

         while (this.at < this.source.length()) {
            char value = this.source.charAt(this.at);

            if (value == '(') {
               depth++;
            } else if (value == ')') {
               if (depth == 0) {
                  break;
               }

               depth--;
            } else if (depth == 0 && (this.startsAt(this.at, "&&") || this.startsAt(this.at, "||")
                    || value == '!' && !this.startsAt(this.at, "!="))) {
               break;
            }

            this.at++;
         }

         String text = this.source.substring(start, this.at).strip();

         if (text.isEmpty()) {
            throw new IllegalArgumentException(this.source);
         }

         return text;
      }

      private String oppositeOf(String value) {
         if (DEFINED.matcher(value.strip()).matches()) {
            String text = value.strip();

            return text.startsWith("!") ? text.substring(1).strip() : "!" + text;
         }

         Matcher matcher = COMPARISON.matcher(value);

         if (!matcher.matches()) {
            throw new IllegalArgumentException(value);
         }

         String variable = matcher.group(1) == null ? "" : matcher.group(1).strip() + " ";
         String operator = matcher.group(2).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
         String compared = matcher.group(3).strip();

         if ("in".equals(operator)) {
            return variable + "not in " + compared;
         }

         if ("not in".equals(operator)) {
            return variable + "in " + compared;
         }

         String opposite = OPPOSITE.get(operator);

         if (opposite == null) {
            throw new IllegalArgumentException(value);
         }

         return variable + opposite + " " + compared;
      }

      private void skipSpace() {
         while (this.at < this.source.length() && Character.isWhitespace(this.source.charAt(this.at))) {
            this.at++;
         }
      }

      private boolean starts(String text) {
         return this.startsAt(this.at, text);
      }

      private boolean startsAt(int position, String text) {
         return this.source.startsWith(text, position);
      }
   }

   /** One level of nesting, and which of its branches the line being asked about is in. */
   private static final class Branch {
      private String taken;
      private final List<String> left = new ArrayList<>();

      private Branch(String condition) {
         this.taken = condition;
      }

      /** Moves on to the next branch of this level, which is taken when the ones before it are not. */
      private void leave(@Nullable String condition) {
         if (this.taken != null) {
            this.left.add(this.taken);
         }

         this.taken = condition == null || condition.isBlank() ? null : condition;
      }

      private @Nullable String taken() {
         return this.taken;
      }

      private List<String> left() {
         return this.left;
      }
   }

   private static record CarriedImport(@Nullable String condition, String qualified) {
   }
}
