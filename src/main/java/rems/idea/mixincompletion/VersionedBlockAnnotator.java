package rems.idea.mixincompletion;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.JavaCodeFragment;
import com.intellij.psi.JavaCodeFragmentFactory;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.JavaCodeFragment.VisibilityChecker;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.CachedValueProvider.Result;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class VersionedBlockAnnotator implements Annotator {
   private static final Logger LOG = Logger.getInstance(VersionedBlockAnnotator.class);

   private static final Key<CachedValue<Map<TextRange, PsiElement>>> NAMES = Key.create("rems.versioned.carried.names");

   /**
    * The qualified name on an import line.
    *
    * <p>Deliberately stops before the semicolon and before anything the line carries after it: what is being
    * looked up is the name, and the rest of the line is not part of it.
    */
   private static final Pattern IMPORTED_NAME =
           Pattern.compile("\\bimport\\s+(?:static\\s+)?([A-Za-z_$][A-Za-z0-9_$.]*)");

   public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {

      if (element instanceof PsiComment comment) {
         String text = comment.getText();
         if (MixinCommentContext.carriesCode(text)) {
            PsiFile hostFile = comment.getContainingFile();
            if (hostFile instanceof PsiJavaFile) {
               Map<TextRange, PsiElement> resolved;
               try {
                  TextRange code = MixinCommentContext.codeRange(text);
                  if (code == null) {
                     return;
                  }

                  resolved = resolvedNames(comment, code);
               } catch (StackOverflowError | RuntimeException var11) {
                  return;
               }

               int detailed = 0;

               for(Map.Entry<TextRange, PsiElement> entry : resolved.entrySet()) {
                  PsiElement target = (PsiElement)entry.getValue();
                  if (target == null) {
                     int start = entry.getKey().getStartOffset() - comment.getTextRange().getStartOffset();
                     int end = start + entry.getKey().getLength();
                     String name = start >= 0 && end <= text.length() ? text.substring(start, end) : "?";

                     // What a name came out unresolved against, written down for the first few names of a
                     // comment. "White" has two causes that look the same on screen - the name is not in what
                     // the module depends on, or no module was chosen for the line at all - and the difference
                     // decides whether anything here can be fixed. Asking the index twice per name, and only
                     // while a name is unresolved, is what it costs.
                     String context = detailed < 3 ? unresolvedContext(comment, name) : "";
                     ++detailed;
                     LOG.warn("preprocessor annotate: unresolved '" + name + "' in " + comment.getTextRange() + context);

                     holder.newAnnotation(HighlightSeverity.WARNING, "Cannot resolve symbol").range((TextRange)entry.getKey()).create();
                  } else {
                     TextAttributesKey key = keyFor(target);
                     if (key != null) {
                        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range((TextRange)entry.getKey()).textAttributes(key).create();
                     }

                     // Said out loud because the package half of a qualified name comes out in the class colour
                     // when its range covers too much: what was painted, what it resolved to, and in which
                     // colour is the whole of that question.
                     int from = Math.max(0, entry.getKey().getStartOffset() - comment.getTextRange().getStartOffset());
                     int to = Math.min(comment.getText().length(), entry.getKey().getEndOffset() - comment.getTextRange().getStartOffset());

                     LOG.info("preprocessor annotate: painted [" + entry.getKey() + "] target="
                             + target.getClass().getSimpleName() + " key=" + (key == null ? "-" : key.getExternalName())
                             + " text=[" + (from < to ? comment.getText().substring(from, to) : "") + "]");
                  }
               }

            }
         }
      }
   }

   static @Nullable PsiElement resolveName(PsiComment comment, TextRange range) {
      if (range == null) {
         return null;
      } else {
         String text = comment.getText();
         if (!MixinCommentContext.carriesCode(text)) {
            return null;
         } else {
            TextRange code = MixinCommentContext.codeRange(text);
            return code == null ? null : (PsiElement)resolvedNames(comment, code).get(shift(comment, range));
         }
      }
   }

   static @Nullable PsiElement resolveNameCovering(PsiComment comment, TextRange range) {
      if (range == null) {
         return null;
      } else {
         String text = comment.getText();
         if (!MixinCommentContext.carriesCode(text)) {
            return null;
         } else {
            TextRange code = MixinCommentContext.codeRange(text);
            if (code == null) {
               return null;
            } else {
               TextRange wanted = shift(comment, range);
               PsiElement exact = (PsiElement)resolvedNames(comment, code).get(wanted);
               if (exact != null) {
                  return exact;
               } else {
                  for(Map.Entry<TextRange, PsiElement> entry : resolvedNames(comment, code).entrySet()) {
                     TextRange found = (TextRange)entry.getKey();
                     if (found.contains(wanted) || wanted.contains(found)) {
                        return (PsiElement)entry.getValue();
                     }
                  }

                  return null;
               }
            }
         }
      }
   }

   /**
    * Every name the carried code in a comment was found to hold, and what each one stands for.
    *
    * <p>In document offsets. Everything downstream - the colouring, the references - reads the one answer, so
    * a name that is coloured as a class is a name that can be jumped to, and both point at the same version's
    * copy of the project for the same reason.
    */
   static Map<TextRange, PsiElement> namesOf(PsiComment comment) {
      String text = comment.getText();

      if (!MixinCommentContext.carriesCode(text)) {
         return Map.of();
      }

      TextRange code = MixinCommentContext.codeRange(text);

      return code == null ? Map.of() : resolvedNames(comment, code);
   }

   /**
    * The document range a range written inside a comment stands for.
    *
    * <p>The range handed in is an offset into the comment's own text - which is what a reference on the
    * comment reports - so the comment is all that has to be added to it. The start of the carried code was
    * added as well, and both are true of the same offset: a range that began at the code was moved a second
    * time by the length of the marker in front of it. Every lookup by a range came out empty for that, which
    * is why a name the colouring had resolved perfectly well still had nowhere to jump to.
    */
   private static TextRange shift(PsiComment comment, TextRange range) {
      int base = comment.getTextRange().getStartOffset();
      return TextRange.create(base + range.getStartOffset(), base + range.getEndOffset());
   }

   private static Map<TextRange, PsiElement> resolvedNames(PsiComment comment, TextRange code) {
      return (Map)CachedValuesManager.getManager(comment.getProject()).getCachedValue(comment, NAMES, () -> Result.create(resolveNames(comment, code), new Object[]{comment}), false);
   }

   static Map<TextRange, PsiElement> resolveNames(PsiComment comment, TextRange code) {
      Map<TextRange, PsiElement> names = new LinkedHashMap();
      PsiFile hostFile = comment.getContainingFile();
      Project project = comment.getProject();
      String body = comment.getText().substring(code.getStartOffset(), code.getEndOffset());
      Document document = PsiDocumentManager.getInstance(project).getDocument(hostFile);
      int line = document == null ? 0 : document.getLineNumber(comment.getTextRange().getStartOffset());

      // The statement a //#replace line would stand in for, parsed in front of the tail so the tail reads as
      // the continuation it is. Only a replacement line needs this: a //$$ line is a statement of its own,
      // and putting something in front of that parses as text the line never said. Its length is carried
      // through to collect - the names being looked for start after it, and the live code it holds is not
      // what is being asked about.
      // An import line has nothing to walk for. The host file reads it as comment, so no import was ever
      // parsed here and there is no reference to ask - which is why the fragment builder answers null for it
      // and the line came out with no colour at all. The qualified name is taken from the text and looked up
      // directly instead, which is the same question with fewer moving parts. It is also the one place a name
      // belonging to another version is guaranteed to be written: an import under a marker exists for no
      // other reason.
      if (VersionedCodeFragmentResolver.scopeOfComment(comment, body)
              == VersionedCodeFragmentResolver.Scope.IMPORT) {
         collectImports(project, document, comment, code, body, names);
         return names;
      }

      String context = document == null || MixinCommentContext.replacementBodyStart(comment.getText()) < 0
              ? "" : MixinCommentContext.replacementContext(document, line);
      JavaCodeFragment fragment = fragmentFor(comment, hostFile, context + body);
      if (fragment == null) {
         return names;
      } else {
         fragment.setVisibilityChecker(VisibilityChecker.EVERYTHING_VISIBLE);
         PsiClass hostClass = (PsiClass)PsiTreeUtil.getParentOfType(comment, PsiClass.class);
         if (hostClass != null) {
            fragment.setThisType(JavaPsiFacade.getElementFactory(project).createType(hostClass));
         }

         if (hostFile instanceof PsiJavaFile) {
            PsiJavaFile javaFile = (PsiJavaFile)hostFile;
            fragment.addImportsFromString(VersionedCodeFragmentResolver.importsOf(javaFile));
         }

         // Type references, and the calls of methods.
         //
         // A method call is an expression, so walking only the reference elements leaves every call in a
         // branch plain while the types beside it are coloured - and a call is most of what a branch is
         // made of. Anything that resolves to something other than a type or a method is skipped downstream,
         // so pulling in the expressions does not drag variables along with them.
         boolean clean = parsedCleanly(fragment);
         int skip = context.length();
         Module module = document == null ? null : VersionedModuleResolver.resolveModule(project, document, line);

         // Resolved against one module, which is the one whose scope the game is in among those the markers at
         // this line are true for. Trying the others as well was the obvious way to survive a wrong guess, and
         // it is the wrong way: every attempt parses the branch again, so a branch that resolves nothing - the
         // case it was written for - would pay for every version the project builds. The guess is made better
         // instead, and what it came to is written down: how many of the names were found, which says whether
         // the module it was resolved against was the right one.
         if (module != null) {
            fragment.forceResolveScope(GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, false));
         }

         collect(fragment, PsiJavaCodeReferenceElement.class, comment, code, names, clean, skip);
         collect(fragment, PsiReferenceExpression.class, comment, code, names, clean, skip);
         collectDeclarations(fragment, comment, code, names, skip);

         LOG.info("preprocessor annotate: ("
                 + comment.getTextRange().getStartOffset() + "," + comment.getTextRange().getEndOffset()
                 + ") resolved " + resolvedCount(names) + " of " + names.size()
                 + " against " + (module == null ? "the project" : module.getName()));

         return names;
      }
   }

   /**
    * What a name came out unresolved against.
    *
    * <p>Two causes look the same on screen: the name is not in what the chosen module depends on, or no module
    * was chosen for the line at all. The first is a project that has not synced that version, the second is
    * this code. Which one it is decides whether anything here can be fixed, so both are asked and both are
    * written down beside the name.
    */
   private static String unresolvedContext(PsiComment comment, String name) {
      Project project = comment.getProject();
      PsiFile hostFile = comment.getContainingFile();
      Document document = PsiDocumentManager.getInstance(project).getDocument(hostFile);

      if (document == null) {
         return "";
      }

      int line = document.getLineNumber(comment.getTextRange().getStartOffset());
      Module module = VersionedModuleResolver.resolveModule(project, document, line);
      PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
      StringBuilder context = new StringBuilder(" module=");
      context.append(module == null ? "-" : module.getName());

      if (module != null) {
         GlobalSearchScope scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, false);
         context.append(" inModule=").append(cache.getClassesByName(name, scope).length);
         context.append(" seesGame=").append(VersionedModuleResolver.seesGame(project, module));
      }

      context.append(" inProject=").append(cache.getClassesByName(name, GlobalSearchScope.allScope(project)).length);
      context.append(" import=").append(importedName(hostFile, name));

      return context.toString();
   }

   /** The import written anywhere in the file whose short name is this one, if there is one. */
   private static String importedName(PsiFile hostFile, String name) {
      if (!(hostFile instanceof PsiJavaFile javaFile)) {
         return "none";
      }

      for(String qualified : VersionedCodeFragmentResolver.importsOf(javaFile).split("\n")) {
         String trimmed = qualified.strip();
         int dot = trimmed.lastIndexOf('.');

         if (dot >= 0 && trimmed.length() - dot - 1 == name.length() && trimmed.endsWith(name)) {
            return trimmed;
         }
      }

      return "none";
   }

   /** How many of the names in a branch were found. */
   private static int resolvedCount(Map<TextRange, PsiElement> names) {      int resolved = 0;

      for(PsiElement found : names.values()) {
         if (found != null) {
            ++resolved;
         }
      }

      return resolved;
   }

   private static boolean parsedCleanly(JavaCodeFragment fragment) {
      return PsiTreeUtil.findChildrenOfType(fragment, PsiErrorElement.class).isEmpty();
   }

   /**
    * The name an element introduces, which is the identifier and not what is written around it.
    *
    * <p>An expression gives the part after the last dot. A type reference gives the name before its type
    * arguments - and it has to, because a reference element's range covers the whole of
    * {@code Holder<ContextFloatProvider>}: one record for a line that names two classes, keyed by a range no
    * name can be found at. Everything downstream looks a name up by its own range, so the single record was
    * reachable by neither - the colour went on the angle brackets as well as the type, and nothing in the line
    * could be jumped to. What is inside the arguments is a reference of its own and is recorded as one.
    */
   private static TextRange nameRangeOf(PsiElement element) {      if (element instanceof PsiReferenceExpression expression) {
         PsiElement name = expression.getReferenceNameElement();

         if (name != null && name.getTextRange() != null) {
            return name.getTextRange();
         }
      }

      if (element instanceof PsiJavaCodeReferenceElement reference) {
         PsiElement name = reference.getReferenceNameElement();

         if (name != null && name.getTextRange() != null) {
            return name.getTextRange();
         }
      }

      return element.getTextRange();
   }

   /**
    * Records the names a declaration introduces.
    *
    * <p>Both walks above follow references, and a declared name is not one - it is the thing a reference
    * would point at. So a field or a method written in a branch was never in the table at all, and the one
    * name on the line that says what the line is about was the one name left uncoloured.
    */
   private static void collectDeclarations(JavaCodeFragment fragment, PsiComment comment, TextRange code,
                                           Map<TextRange, PsiElement> names, int skip) {
      int base = comment.getTextRange().getStartOffset() + code.getStartOffset() - skip;

      for(PsiField field : PsiTreeUtil.findChildrenOfType(fragment, PsiField.class)) {
         record(names, base, skip, field, field.getNameIdentifier());
      }

      for(PsiMethod method : PsiTreeUtil.findChildrenOfType(fragment, PsiMethod.class)) {
         record(names, base, skip, method, method.getNameIdentifier());
      }

      for(PsiParameter parameter : PsiTreeUtil.findChildrenOfType(fragment, PsiParameter.class)) {
         record(names, base, skip, parameter, parameter.getNameIdentifier());
      }

   }

   /** Puts one declared name into the table, unless it belongs to the context rather than the branch. */
   private static void record(Map<TextRange, PsiElement> names, int base, int skip,
                              PsiElement declaration, @Nullable PsiElement name) {
      if (name == null || name.getTextRange() == null) {
         return;
      }

      TextRange range = name.getTextRange();

      if (range.getStartOffset() < skip) {
         return;
      }

      names.put(TextRange.create(base + range.getStartOffset(), base + range.getEndOffset()), declaration);
   }

   /**
    * Records what each qualified name an import line carries resolves to.
    *
    * <p>A name that resolves to nothing is recorded as a null, the same as in the walk below, so the editor
    * can mark it: an import written for a version that does not have the class is the plainest case there is,
    * and it is what a reader scanning a branch is looking for.
    */
   private static void collectImports(Project project, Document document, PsiComment comment,
                                      TextRange code, String body, Map<TextRange, PsiElement> names) {
      int base = comment.getTextRange().getStartOffset() + code.getStartOffset();
      Matcher imported = IMPORTED_NAME.matcher(body);
      JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
      int line = document == null ? 0 : document.getLineNumber(comment.getTextRange().getStartOffset());
      Module module = document == null ? null : VersionedModuleResolver.resolveModule(project, document, line);
      GlobalSearchScope scope = module == null
              ? GlobalSearchScope.allScope(project)
              : GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, false);

      while(imported.find()) {
         names.put(TextRange.create(base + imported.start(1), base + imported.end(1)),
                 qualifiedClass(facade, scope, imported.group(1)));
      }

   }

   /**
    * The class a qualified name names, shortened while it only names a member of one.
    *
    * <p>{@code import static a.b.C.member;} names a member rather than a class, and the class is the longest
    * prefix of that name which is one. Shortening all the way and answering with whatever is left would turn
    * every missing class into its package, so the walk stops as soon as nothing is found.
    */
   private static @Nullable PsiClass qualifiedClass(JavaPsiFacade facade, GlobalSearchScope scope,
                                                    String qualified) {
      PsiClass found = facade.findClass(qualified, scope);

      for(int dot = qualified.lastIndexOf('.'); found == null && dot > 0; dot = qualified.lastIndexOf('.')) {
         qualified = qualified.substring(0, dot);
         found = facade.findClass(qualified, scope);
      }

      return found;
   }

   private static JavaCodeFragment fragmentFor(PsiComment comment, PsiFile hostFile, String body) {
      JavaCodeFragmentFactory factory = JavaCodeFragmentFactory.getInstance(comment.getProject());

      // Closed off before parsing. A block usually ends mid-construct - "register(" carrying on past the
      // lines the block holds - and code that does not parse resolves nothing past the point it breaks, so
      // a file's worth of names came back as the two that happened to be written before the break.
      String closed = body + ")".repeat(openBrackets(body)) + "\n";

      // The class the comment sits in, not the file it sits in. A fragment hangs off the element it came
      // from, and what resolution can see is decided there: handed the file, the fragment sits beside the
      // class rather than inside it, and every member of the class the branch is written against is out of
      // reach - the private ones always, and the static ones as well. A private method of the class the code
      // belongs to is that class's own code; whether it is public decides nothing.
      PsiElement owner = PsiTreeUtil.getParentOfType(comment, PsiClass.class);
      PsiElement context = owner != null ? owner : comment;
      JavaCodeFragment var10000;
      switch (VersionedCodeFragmentResolver.scopeOfComment(comment, body)) {
         case IMPORT -> var10000 = null;
         case STATEMENT -> var10000 = factory.createCodeBlockCodeFragment(closed, context, false);
         case MEMBER -> var10000 = factory.createMemberCodeFragment(closed, context, false);
         default -> throw new MatchException((String)null, (Throwable)null);
      }

      return var10000;
   }

   /** How many brackets the text leaves open. */
   private static int openBrackets(String code) {
      int open = 0;

      for(int index = 0; index < code.length(); ++index) {
         char value = code.charAt(index);
         if (value == '(' || value == '[' || value == '{') {
            ++open;
         } else if (value == ')' || value == ']' || value == '}') {
            --open;
         }
      }

      return Math.max(0, open);
   }

   private static void collect(JavaCodeFragment fragment, Class<? extends PsiElement> type, PsiComment comment, TextRange code, Map<TextRange, PsiElement> names, boolean reportUnresolved, int skip) {
      // The fragment opens with the live statement a replacement line belongs to. The names being looked for
      // start after it, so the opening length is taken back off - a range has to land in the comment, and
      // anything inside that context is the live code in front of the marker rather than carried code.
      int base = comment.getTextRange().getStartOffset() + code.getStartOffset() - skip;
      String fragmentText = fragment.getText();

      for(PsiElement element : PsiTreeUtil.findChildrenOfType(fragment, type)) {
         if (element.getTextRange() == null || element.getTextRange().getStartOffset() < skip) {
            continue;
         }

         PsiReference var10000;
         if (element instanceof PsiReference found) {
            var10000 = found;
         } else {
            var10000 = element.getReference();
         }

         PsiReference reference = var10000;
         if (reference != null) {
            PsiElement target = reference.resolve();
            if (target != null || reportUnresolved) {
               // The name itself, not the whole expression it sits in. A qualified expression such as
               // "stats.add(...)" is one reference whose range starts at the qualifier, so colouring it by
               // what it resolved to paints the receiver in the method's colour as well - which is how a
               // local variable came out looking like a call.
               TextRange inFragment = nameRangeOf(element);

               // An annotation's at-sign is part of it. What resolves here is the type a "@Shadow" names, and
               // its range begins at the identifier, so the mark in front of it stayed plain on every
               // annotated line - the one character a reader uses to tell an annotation from a type. Taken in
               // only where the character before the name is that mark and nothing else.
               if (inFragment.getStartOffset() > 0
                       && fragmentText.charAt(inFragment.getStartOffset() - 1) == '@') {
                  inFragment = TextRange.create(inFragment.getStartOffset() - 1, inFragment.getEndOffset());
               }

               names.put(TextRange.create(base + inFragment.getStartOffset(), base + inFragment.getEndOffset()), target);
            }
         }
      }

   }

   static TextAttributesKey keyFor(PsiElement target) {
      if (target instanceof PsiClass) {
         PsiClass type = (PsiClass)target;
         // An annotation is a type, and a reference to one resolves to a class - so it has to be told apart
         // before the class rule takes it, or "@Shadow" comes out in the class colour while the theme calls
         // annotations something else.
         return type.isAnnotationType()
                 ? DefaultLanguageHighlighterColors.METADATA
                 : DefaultLanguageHighlighterColors.CLASS_NAME;
      } else if (target instanceof PsiMethod) {
         PsiMethod method = (PsiMethod)target;
         return method.hasModifierProperty("static") ? DefaultLanguageHighlighterColors.STATIC_METHOD : DefaultLanguageHighlighterColors.FUNCTION_CALL;
      } else if (target instanceof PsiField) {
         // A field of the class the branch belongs to is a name the platform cannot colour here: the branch is
         // a fragment, so the initialiser that gives the field its type does not resolve, and the semantic
         // colouring drops it to plain text. What was left was a line of coloured types around a name with no
         // colour at all - and the name is the one thing on the line worth being able to find.
         PsiField field = (PsiField)target;
         return field.hasModifierProperty("static")
                 ? DefaultLanguageHighlighterColors.STATIC_FIELD
                 : DefaultLanguageHighlighterColors.INSTANCE_FIELD;
      } else if (target instanceof PsiParameter) {
         // The same answer for a parameter, and for the same reason. A parameter is what the branch is mostly
         // written in terms of, so a branch whose parameters were left plain reads as though the code were
         // full of names that mean nothing.
         return DefaultLanguageHighlighterColors.PARAMETER;
      } else {
         return null;
      }
   }

   // $FF: synthetic method
   
}
