package rems.idea.mixincompletion;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.codeInsight.lookup.LookupElementDecorator;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

public final class VersionedCommentCompletionContributor extends CompletionContributor {
   private static final Logger LOG = Logger.getInstance(VersionedCommentCompletionContributor.class);

   public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {

      PsiElement position = parameters.getPosition();
      PsiFile file = position.getContainingFile();
      Document document = parameters.getEditor().getDocument();
      int offset = parameters.getOffset();
      DocumentWindow window = windowOf(parameters);
      int mapped = window == null ? -1 : window.injectedToHost(offset);
      Document host = hostDocumentOf(parameters, mapped);
      boolean injected = InjectedLanguageManager.getInstance(position.getProject()).isInjectedFragment(file);
      String logical = MixinCommentContext.logicalContext(document, offset);
      boolean onPosition = MixinCommentContext.isCompletionPosition(document, offset, logical);

      // A completion in a fragment happens at two different places at once: in the fragment, and in the file.
      // The offset is a place in the fragment and the editor's document is the file's, so the two have to be
      // brought together before anything is read - the code before the caret, the line it is on, the name in
      // front of it. Read apart, everything below works on text that is somewhere else entirely: the prefix
      // came out empty, and a name being typed in an import line was offered the keywords of a class body.
      //
      // The host side is the one to work in: it is the file the reader sees, it is what the markers are in,
      // and the fragment's own document holds a class shell rather than the code.
      if (injected && host != null && mapped >= 0 && mapped <= host.getTextLength()) {
         document = host;
         offset = mapped;
         logical = MixinCommentContext.logicalContext(host, mapped);
         onPosition = MixinCommentContext.isCompletionPosition(host, mapped, logical);
      } else if (!onPosition && host != null && mapped >= 0 && mapped <= host.getTextLength()) {
         String hostLogical = MixinCommentContext.logicalContext(host, mapped);
         if (MixinCommentContext.isCompletionPosition(host, mapped, hostLogical)) {
            document = host;
            offset = mapped;
            logical = hostLogical;
            onPosition = true;
         }
      }

      // Written whatever the answer is, and before anything is offered.
      //
      // What stood here said what was offered, and only when the place had already been read as one where
      // completion belongs. The case that has to be looked at is the other one - nothing is offered - and that
      // case wrote nothing at all, so a report of "completion does not work here" left the log with nothing to
      // read. Everything the decision rests on is written on one line: the place, whether it was read as a
      // place for completion, what the code before the caret came out as, and what the position was.
      int length = document.getTextLength();
      int at = Math.max(0, Math.min(offset, length));
      int lineNumber = document.getLineNumber(at);

      LOG.info("preprocessor completion: file=" + file.getName()
              + " line=" + (lineNumber + 1)
              + " offset=" + offset
              + " text=[" + document.getText().substring(document.getLineStartOffset(lineNumber),
                      document.getLineEndOffset(lineNumber)).replace("\t", "\\t") + "]"
              + " element=" + position.getClass().getSimpleName()
              + " onPosition=" + onPosition
              + " injected=" + injected
              + " host=" + (host == null ? -1 : host.getTextLength())
              + " mapped=" + mapped
              + " logical=" + (logical == null ? "-" : "[" + logical + "]"));

      if (onPosition || injected) {
         // Every element on offer passes through here, not only the ones this plugin suggests.
         //
         // A name completed inside a version branch belongs to that branch whatever list it was picked from,
         // and most of the list is somebody else's: the carried code is injected as Java, so Java completion
         // answers for it, and Mixin Development answers for the annotations in it. Both arrive with nothing
         // attached, and both are handed on untouched - what is taken over is only the class elements, whose
         // import has to be written with the branch's condition on it.
         //
         // Handed on to the same result set they came from. Taking the other contributors' elements out and
         // adding them to a copy made with a prefix matcher lost them: the elements were collected here and
         // never reached the lookup, which is a list with this plugin's own names in it and nothing from the
         // platform - no imports in the import list, no parameters in a Mixin annotation.
         CompletionResultSet into = result;

         result.runRemainingContributors(parameters, completion -> {
            LookupElement element = completion.getLookupElement();
            Object object = element.getObject();

            if (object instanceof PsiClass type) {
               into.addElement(withImport(element, type));
            } else {
               into.addElement(element);
            }
         });

         int line = document.getLineNumber(offset);

         // Said here rather than at the writing end: whether a branch is this build's code decides whether an
         // import is written plainly or under a condition, and asking it at the writing end only answers for the
         // times an import is written. Asked here, every completion in a branch reports it.
         LOG.info("preprocessor completion: version=" + PreprocessorLanguage.moduleVersionCode(document.getText() == null ? null : PsiDocumentManager.getInstance(position.getProject()).getPsiFile(document))
                 + " hostLine=" + (line + 1) + " taken=" + PreprocessorLanguage.isLineActive(document, line,
                 Map.of("MC", PreprocessorLanguage.moduleVersionCode(PsiDocumentManager.getInstance(position.getProject()).getPsiFile(document)))));

         // An import line is not a member access, however much it looks like one.
         //
         // "fi.dy.masa.malilib.render." and "server." are the same shape, and the two want different lists: the
         // first wants the packages and classes under that name, the second the members of that object. Read as
         // a member access, an import line was answered with the members of something that is not a value at all
         // - which is nothing - and the name being typed got no suggestions.
         boolean importLine = logical != null && logical.stripLeading().startsWith("import ");

         // An annotation's arguments are answered by whoever provides that annotation, not by every class in the
         // project. Offered here as well, the list came out as a column of names with nothing to do with the
         // annotation being written, and the annotation's own suggestions were pushed down out of sight among
         // them.
         boolean inAnnotation = PsiTreeUtil.getParentOfType(position, PsiAnnotation.class, false) != null;
         String qualifier = importLine || inAnnotation ? null : VersionedCodeFragmentResolver.qualifierBefore(document, offset);
         List<LookupElement> items = inAnnotation ? null : qualifier != null ? VersionedCodeFragmentResolver.memberVariants(file, position, document, line, offset, qualifier, into) : VersionedCodeFragmentResolver.scopeVariants(file, position, document, line, offset, logical, into);

         if (items != null) {
            StringBuilder first = new StringBuilder();

            for(LookupElement item : items) {
               if (first.length() >= 160) {
                  break;
               }

               if (first.length() > 0) {
                  first.append(',');
               }

               first.append(item.getLookupString());
            }

            LOG.info("preprocessor completion: qualifier=" + qualifier + " importLine=" + importLine
                    + " prefix=\"" + result.getPrefixMatcher().getPrefix() + "\" items=" + items.size()
                    + " first=" + String.valueOf(first));
            result.addAllElements(withImportInsertion(items));
         } else {
            LOG.info("preprocessor completion: no items; qualifier=" + qualifier + " importLine=" + importLine
                    + " logical=" + (logical == null ? "-" : "[" + logical.replace('\n', ' ') + "]"));
         }

      }
   }

   /**
    * A lookup element that writes the import for its class as it is inserted.
    *
    * <p>Rebuilt rather than decorated. What an element is gets checked before it reaches the lookup, and a
    * decorated one was turned away there - a wrapper has no class of its own for that check to know, and the
    * names this plugin offered were dropped with a complaint that they were not valid lookup elements. Built
    * here, the element carries the name the list showed, the class behind it, and an insert handler, which is
    * what the check asks for.
    *
    * <p>A name the platform wrote out in full is shortened as it goes in. Completion cannot add an import to a
    * file it is not editing - the carried code is a fragment, and the import belongs to the host - so the
    * platform's own element falls back to writing the qualified name, which is what a branch would then be full
    * of. The import is written by this handler, so the name itself can be the short one.
    */
   private static LookupElement withImport(LookupElement item, PsiClass type) {
      // What the list was showing is read off the element and written back onto the new one. Rebuilt without it,
      // the names came out as bare words - no icon, no type beside them - which is a column of names with
      // nothing to tell one from another and no sign of what kind of thing each is.
      LookupElementPresentation presentation = new LookupElementPresentation();
      item.renderElement(presentation);

      return LookupElementBuilder.create(type, item.getLookupString())
              .withIcon(presentation.getIcon())
              .withTypeText(presentation.getTypeText(), presentation.isTypeGrayed())
              .withTailText(presentation.getTailText())
              .withInsertHandler((context, element) -> {
                 String qualified = type.getQualifiedName();
                 Document document = context.getDocument();
                 int start = context.getStartOffset();
                 int end = context.getTailOffset();

                 if (qualified != null && start >= 0 && start < end && end <= document.getTextLength()) {
                    String written = document.getImmutableCharSequence().subSequence(start, end).toString();

                    if (qualified.equals(written)) {
                       document.replaceString(start, end, qualified.substring(qualified.lastIndexOf('.') + 1));
                    }
                 }

                 ConditionalImportMerger.addImport(context, type);
              });
   }

   private static List<LookupElement> withImportInsertion(List<LookupElement> items) {
      List<LookupElement> wrapped = new ArrayList(items.size());

      for(LookupElement item : items) {
         Object var5 = item.getObject();

         if (var5 instanceof PsiClass type) {
            wrapped.add(withImport(item, type));
         } else {
            wrapped.add(item);
         }
      }

      return wrapped;
   }

   private static DocumentWindow windowOf(CompletionParameters parameters) {
      PsiFile original = parameters.getOriginalFile();
      if (original == null) {
         return null;
      } else {
         Document document = original.getViewProvider().getDocument();
         DocumentWindow var10000;
         if (document instanceof DocumentWindow) {
            DocumentWindow window = (DocumentWindow)document;
            var10000 = window;
         } else {
            var10000 = null;
         }

         return var10000;
      }
   }

   private static Document hostDocumentOf(CompletionParameters parameters, int mapped) {
      PsiFile original = parameters.getOriginalFile();
      if (original == null) {
         return null;
      } else {
         Project project = original.getProject();
         List<Document> candidates = new ArrayList();
         candidates.add(documentOf(project, InjectedLanguageManager.getInstance(project).getTopLevelFile(original)));
         candidates.add(documentOf(project, original));
         VirtualFile virtualFile = original.getVirtualFile();
         candidates.add(virtualFile == null ? null : FileDocumentManager.getInstance().getDocument(virtualFile));

         for(Document candidate : candidates) {
            if (candidate != null && (mapped < 0 || mapped <= candidate.getTextLength())) {
               return candidate;
            }
         }

         return null;
      }
   }

   private static Document documentOf(Project project, PsiFile file) {
      return file == null ? null : PsiDocumentManager.getInstance(project).getDocument(file);
   }

   // $FF: synthetic method
   
}
