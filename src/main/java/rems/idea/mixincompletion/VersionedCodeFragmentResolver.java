package rems.idea.mixincompletion;

import com.intellij.codeInsight.completion.CompletionData;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.PrefixMatcher;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.JavaCodeFragment;
import com.intellij.psi.JavaCodeFragmentFactory;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiImportStatementBase;
import com.intellij.psi.PsiImportStaticStatement;
import com.intellij.psi.PsiJavaCodeReferenceCodeFragment;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReference;
import com.intellij.psi.JavaCodeFragment.VisibilityChecker;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.CachedValueProvider.Result;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

final class VersionedCodeFragmentResolver {
   private static final String PROBE = "ignyProbe";
   private static final Logger LOG = Logger.getInstance(VersionedCodeFragmentResolver.class);

   /** The window the offered classes are kept for. */
   private static final int SECOND = 1000;
   private static final Pattern MEMBER_START = Pattern.compile("^\\s*(?:@[A-Za-z_$][A-Za-z0-9_$.]*(?:\\([^)]*\\))?\\s*)*(?:public|protected|private|static|final|abstract|transient|volatile|native|synchronized|strictfp)\\b");
   private static final Pattern MARKED_IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([A-Za-z_$][A-Za-z0-9_$.]*(?:\\.\\*)?)\\s*;");

   /** The classes offered for a prefix, and the index revision they were collected at. */
   private static final Key<Scoped> SCOPED = Key.create("rems.versioned.scoped.classes");

   private static record Scoped(long stamp, Map<String, List<LookupElement>> entries) {
      private Scoped(long stamp) {
         this(stamp, new HashMap<>());
      }
   }

   private VersionedCodeFragmentResolver() {
   }

   static @Nullable List<LookupElement> memberVariants(PsiFile file, PsiElement context, Document document, int line, int offset, String qualifier, CompletionResultSet result) {
      if (!qualifier.isEmpty() && file instanceof PsiJavaFile) {
         Project project = file.getProject();
         JavaCodeFragmentFactory factory = JavaCodeFragmentFactory.getInstance(project);
         PsiJavaCodeReferenceCodeFragment fragment = factory.createReferenceCodeFragment(qualifier + ".ignyProbe", context, false, false);
         Module module = VersionedModuleResolver.resolveModule(project, document, line);
         if (module != null) {
            fragment.forceResolveScope(GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, false));
         }

         fragment.setVisibilityChecker(VisibilityChecker.EVERYTHING_VISIBLE);
         PsiFile hostFile = hostFileOf(project, document, file);
         if (hostFile instanceof PsiJavaFile) {
            PsiJavaFile javaFile = (PsiJavaFile)hostFile;
            fragment.addImportsFromString(importsOf(javaFile));
         }

         PsiJavaCodeReferenceElement reference = (PsiJavaCodeReferenceElement)PsiTreeUtil.findChildOfType(fragment, PsiJavaCodeReferenceElement.class);
         if (reference == null) {
            return null;
         } else {
            Set<LookupElement> fromEngine = new LinkedHashSet();
            (new Engine()).completeReference(reference, fromEngine, reference, reference.getContainingFile());
            if (!fromEngine.isEmpty()) {
               Logger var10000 = LOG;
               int var10001 = fromEngine.size();
               var10000.warn("preprocessor member: engine returned " + var10001 + " qualifier=" + qualifier + " module=" + (module == null ? "-" : module.getName()));
               return new ArrayList(fromEngine);
            } else {
               List<LookupElement> items = lookupItems(reference.getVariants());
               LOG.warn("preprocessor member: qualifier=" + qualifier + " host=" + hostFile.getClass().getSimpleName() + (hostFile instanceof PsiJavaFile ? "(java)" : "") + " module=" + (module == null ? "-" : module.getName()) + " items=" + (items == null ? -1 : items.size()));
               return items;
            }
         }
      } else {
         return null;
      }
   }

   static @Nullable List<LookupElement> scopeVariants(PsiFile file, PsiElement context, Document document, int line, int offset, String codeBefore, CompletionResultSet result) {
      if (codeBefore.isBlank()) {
         return null;
      } else {
         long started = System.currentTimeMillis();
         Project project = file.getProject();
         JavaCodeFragmentFactory factory = JavaCodeFragmentFactory.getInstance(project);
         String prefix = withoutTrailingIdentifier(codeBefore);
         String probe = "ignyProbe.class" + ")".repeat(openBrackets(prefix)) + ";\n";
         PsiFile hostFile = hostFileOf(project, document, file);
         PsiElement host = hostAt(project, document, offset);
         Scope scope = scopeOf(host, prefix);
         String supertype = pendingSupertype(prefix);
         Object var10000;
         if (supertype != null) {
            var10000 = factory.createReferenceCodeFragment(supertype.endsWith(".") ? supertype + "ignyProbe" : "ignyProbe", hostFile, false, false);
         } else {
            switch (scope.ordinal()) {
               // An import line is answered by asking what is in that package, and the fragment has to hold
               // something to ask about: the name being typed is not written yet. Written without it, the
               // fragment was the qualified name as it stood, with nothing in it to resolve and an empty list
               // back - which is why a name in an import line was offered the keywords of a class body instead.
               // The probe is the place the rest of the name would go, the same one the other two scopes append.
               case 0 -> var10000 = factory.createReferenceCodeFragment(importReference(prefix) + "ignyProbe", hostFile, false, false);
               case 1 -> var10000 = factory.createMemberCodeFragment(prefix + probe, hostFile, false);
               case 2 -> var10000 = factory.createCodeBlockCodeFragment(methodParameters(host) + prefix + probe, hostFile, false);
               default -> throw new MatchException((String)null, (Throwable)null);
            }
         }

         JavaCodeFragment fragment = (JavaCodeFragment)var10000;
         Module module = VersionedModuleResolver.resolveModule(project, document, line);
         if (module != null) {
            fragment.forceResolveScope(GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, false));
         }

         fragment.setVisibilityChecker(VisibilityChecker.EVERYTHING_VISIBLE);
         PsiClass hostClass = hostClassOf(host, hostFile);
         if (hostClass != null) {
            fragment.setThisType(JavaPsiFacade.getElementFactory(project).createType(hostClass));
         }

         if (hostFile instanceof PsiJavaFile) {
            PsiJavaFile javaFile = (PsiJavaFile)hostFile;
            fragment.addImportsFromString(importsOf(javaFile));
         }

         int probeAt = fragment.getText().indexOf("ignyProbe");
         if (probeAt < 0) {
            LOG.warn("preprocessor scope: the fragment holds no probe; text=" + preview(fragment.getText()));

            return null;
         } else {
            PsiReference reference = fragment.findReferenceAt(probeAt);
            List<LookupElement> items = new ArrayList();
            Set<String> taken = new HashSet();
            boolean answered = false;

            if (reference instanceof PsiJavaCodeReferenceElement) {
               PsiJavaCodeReferenceElement element = (PsiJavaCodeReferenceElement)reference;
               Set<LookupElement> fromEngine = new LinkedHashSet();
               (new Engine()).completeReference(element, fromEngine, element, element.getContainingFile());

               for(LookupElement item : fromEngine) {
                  taken.add(simpleName(item.getLookupString()));
               }

               items.addAll(fromEngine);
               answered = !fromEngine.isEmpty();
               Logger var33 = LOG;
               int var10001 = fromEngine.size();
               var33.warn("preprocessor scope: engine=" + var10001 + " names=" + firstNames(items) + " module=" + (module == null ? "-" : module.getName()));
            }

            // Asked of the reference only when the engine said nothing.
            //
            // Both of them run a completion over the same place, and the engine's answer already covers what
            // the reference's would be - the second call re-parsed the fragment and ran the whole completion
            // again for a list that was thrown away whenever the first one had anything in it. Completion is
            // asked for on every keystroke, and this was the larger half of what it cost.
            if (reference != null && !answered) {
               List<LookupElement> fromReference = lookupItems(reference.getVariants());
               if (fromReference != null) {
                  for(LookupElement item : fromReference) {
                     if (taken.add(simpleName(item.getLookupString()))) {
                        items.add(item);
                     }
                  }
               }
            }

            GlobalSearchScope searchScope = module == null ? GlobalSearchScope.allScope(project) : GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, false);
            Module main = VersionedModuleResolver.mainModule(project, document);
            GlobalSearchScope mainScope = main == null ? null : GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(main, false);
            items.addAll(scopedClasses(project, searchScope, mainScope, versionOf(module), result.getPrefixMatcher(), taken, module));
            Logger var34 = LOG;
            String var35 = String.valueOf(scope);
            var34.warn("preprocessor scope: scope=" + var35 + " host=" + hostFile.getClass().getSimpleName() + (hostFile instanceof PsiJavaFile ? "(java)" : "") + " at=" + (host == null ? "null" : host.getClass().getSimpleName()) + " module=" + (module == null ? "-" : module.getName()) + " class=" + (hostClass == null ? "-" : hostClass.getName()) + " reference=" + (reference != null) + " items=" + (items.isEmpty() ? -1 : items.size()) + " first=" + firstNames(items) + " took=" + (System.currentTimeMillis() - started) + "ms fragment=" + preview(fragment.getText()));
            if (items.isEmpty()) {
               LOG.warn("preprocessor scope: nothing to offer; fragment=" + preview(fragment.getText()));
            }

            return items.isEmpty() ? null : items;
         }
      }
   }

   /**
    * The classes in scope whose names match the prefix, from a table kept per index revision.
    *
    * <p>Collecting them walks every class name the project knows and filters each by prefix, and the reader is
    * offered this list on every keystroke. Within one revision of the index the answer for a prefix is the same
    * answer, so it is kept; a revision that changed what classes exist is a different answer. The edit count
    * would be the wrong thing to key it on - typing changes the document on every keystroke, which would drop
    * the table exactly as often as it is read.
    */
   private static List<LookupElement> scopedClasses(Project project, GlobalSearchScope scope,
                                                    @Nullable GlobalSearchScope mainScope, String version,
                                                    PrefixMatcher prefix, Set<String> taken,
                                                    @Nullable Module module) {
      List<LookupElement> all = cachedClasses(project, scope, mainScope, version, prefix, module);
      List<LookupElement> items = new ArrayList();

      for(LookupElement item : all) {
         // Kept for a second, so the class behind an element can go away while it is in the table - a file
         // closed and reopened, a fixture torn down between tests. An element whose class is gone is not one to
         // offer, and the platform says so in as many words when it is handed the file behind it.
         if (item.isValid() && !taken.contains(simpleName(item.getLookupString()))) {
            items.add(item);
         }
      }

      return items;
   }

   /**
    * The classes in scope whose names match the prefix, from a table that lives a second at a time.
    *
    * <p>Collecting them walks every class name the project knows and filters each by prefix, and this list is
    * asked for on every keystroke. Inside a second the answer for a prefix is the same answer, and a class that
    * appears or disappears meanwhile is one entry in a list being read while typing - a second late and never
    * noticed. The document's edit count would be the wrong thing to key it on: typing changes that on every
    * keystroke, so the table would be dropped exactly as often as it is read.
    */
   private static List<LookupElement> cachedClasses(Project project, GlobalSearchScope scope,
                                                    @Nullable GlobalSearchScope mainScope, String version,
                                                    PrefixMatcher prefix, @Nullable Module module) {
      long stamp = System.currentTimeMillis() / SECOND;
      Scoped cache = (Scoped)project.getUserData(SCOPED);
      String key = (module == null ? "-" : module.getName()) + '\u0000' + version + '\u0000' + prefix.getPrefix();

      if (cache == null || cache.stamp() != stamp) {
         cache = new Scoped(stamp);
         project.putUserData(SCOPED, cache);
      }

      List<LookupElement> found = (List)cache.entries().get(key);

      if (found == null) {
         found = collectClasses(project, scope, mainScope, version, prefix);
         cache.entries().put(key, found);
      }

      return found;
   }

   private static List<LookupElement> collectClasses(Project project, GlobalSearchScope scope,
                                                     @Nullable GlobalSearchScope mainScope, String version,
                                                     PrefixMatcher prefix) {
      List<LookupElement> items = new ArrayList();
      PsiShortNamesCache names = PsiShortNamesCache.getInstance(project);

      for(String name : names.getAllClassNames()) {
         if (name != null && prefix.prefixMatches(name)) {
            boolean newer = mainScope != null && names.getClassesByName(name, mainScope).length == 0;

            for(PsiClass type : names.getClassesByName(name, scope)) {
               // The tail text is appended to the name with nothing between them, so the space that separates a
               // class from the package it lives in is the one written here. Without it the two ran together
               // into a single word - "HappyGhastnet.minecraft.world.entity.animal" - and the list read as a
               // column of names that are not the ones being offered.
               String context = contextOf(type);
               items.add(LookupElementBuilder.create(type, name).withIcon(type.getIcon(0)).withTailText(context.isEmpty() ? "" : " " + context, true).withTypeText(newer ? version : "", true));
            }

            if (items.size() >= 400) {
               break;
            }
         }
      }

      return items;
   }

   private static String versionOf(@Nullable Module module) {
      if (module == null) {
         return "";
      } else {
         int code = PreprocessorLanguage.moduleVersionCode(module.getName());
         return code < 0 ? "" : code / 10000 + "." + code / 100 % 100 + "." + code % 100;
      }
   }

   private static String contextOf(PsiClass type) {
      PsiClass outer = type.getContainingClass();
      if (outer != null) {
         String qualified = outer.getQualifiedName();
         String name = outer.getName();
         return qualified != null ? qualified : (name == null ? "" : name);
      } else {
         PsiFile file = type.getContainingFile();
         String var10000;
         if (file instanceof PsiJavaFile) {
            PsiJavaFile javaFile = (PsiJavaFile)file;
            var10000 = javaFile.getPackageName();
         } else {
            var10000 = "";
         }

         return var10000;
      }
   }

   private static String simpleName(String name) {
      int dot = name.lastIndexOf(46);
      return dot < 0 ? name : name.substring(dot + 1);
   }

   private static String firstNames(List<LookupElement> items) {
      StringBuilder names = new StringBuilder();

      for(LookupElement item : items) {
         if (names.length() >= 160) {
            break;
         }

         if (names.length() > 0) {
            names.append(',');
         }

         names.append(item.getLookupString());
      }

      return names.toString();
   }

   private static String preview(String text) {
      String flat = text.replace("\n", "\\n").replace("\r", "");
      return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
   }

   static Scope scopeOfComment(PsiComment comment, String body) {
      return scopeOf(comment, body);
   }

   static Scope scopeOf(PsiElement host, String prefix) {
      if (prefix.stripLeading().startsWith("import ")) {
         return VersionedCodeFragmentResolver.Scope.IMPORT;
      } else {
         for(PsiElement element = host; element != null; element = element.getParent()) {
            if (element instanceof PsiImportList) {
               return VersionedCodeFragmentResolver.Scope.IMPORT;
            }

            if (element instanceof PsiCodeBlock) {
               return VersionedCodeFragmentResolver.Scope.STATEMENT;
            }

            if (element instanceof PsiClass || element instanceof PsiJavaFile) {
               return VersionedCodeFragmentResolver.Scope.MEMBER;
            }
         }

         return VersionedCodeFragmentResolver.Scope.MEMBER;
      }
   }

   private static @Nullable String pendingSupertype(String prefix) {
      int boundary = -1;

      for(String keyword : List.of("extends", "implements")) {
         int at = prefix.lastIndexOf(keyword);
         if (at >= 0 && at + keyword.length() > boundary) {
            boundary = at + keyword.length();
         }
      }

      if (boundary < 0) {
         return null;
      } else {
         for(int comma = prefix.indexOf(44, boundary); comma >= 0; comma = prefix.indexOf(44, boundary)) {
            boundary = comma + 1;
         }

         return prefix.substring(boundary).strip();
      }
   }

   private static String importReference(String prefix) {
      String text = prefix.strip();
      if (text.startsWith("import static ")) {
         return text.substring("import static ".length()).strip();
      } else {
         return text.startsWith("import ") ? text.substring("import ".length()).strip() : text;
      }
   }

   private static String methodParameters(PsiElement host) {
      PsiMethod hostMethod = enclosingHostMethod(host);
      return hostMethod == null ? "" : parametersOf(hostMethod);
   }

   private static @Nullable PsiElement hostAt(Project project, Document document, int offset) {
      PsiFile file = psiFileOf(project, document);
      return file == null ? null : file.findElementAt(Math.max(0, Math.min(offset, document.getTextLength())));
   }

   private static @Nullable PsiFile psiFileOf(Project project, Document document) {
      return project != null && document != null ? PsiDocumentManager.getInstance(project).getPsiFile(document) : null;
   }

   private static int openBrackets(String code) {
      int open = 0;

      for(int index = 0; index < code.length(); ++index) {
         char value = code.charAt(index);
         if (value == '(') {
            ++open;
         } else if (value == ')') {
            --open;
         }
      }

      return Math.max(0, open);
   }

   private static PsiFile hostFileOf(Project project, Document document, PsiFile fallback) {
      PsiFile fromDocument = psiFileOf(project, document);
      return fromDocument == null ? fallback : fromDocument;
   }

   private static @Nullable PsiClass hostClassOf(@Nullable PsiElement host, PsiFile hostFile) {
      PsiClass fromHost = host == null ? null : (PsiClass)PsiTreeUtil.getParentOfType(host, PsiClass.class);
      return fromHost != null ? fromHost : (PsiClass)PsiTreeUtil.getParentOfType(hostFile, PsiClass.class);
   }

   private static String withoutTrailingIdentifier(String codeBefore) {
      int end;
      for(end = codeBefore.length(); end > 0 && Character.isJavaIdentifierPart(codeBefore.charAt(end - 1)); --end) {
      }

      return codeBefore.substring(0, end);
   }

   private static boolean startsMemberDeclaration(String prefix) {
      return MEMBER_START.matcher(prefix).find();
   }

   private static PsiMethod enclosingHostMethod(@Nullable PsiElement host) {
      return host == null ? null : (PsiMethod)PsiTreeUtil.getParentOfType(host, PsiMethod.class, false, new Class[]{PsiClass.class});
   }

   private static String parametersOf(PsiMethod method) {
      StringBuilder declarations = new StringBuilder();

      for(PsiParameter parameter : method.getParameterList().getParameters()) {
         declarations.append(parameter.getText()).append(";\n");
      }

      return declarations.toString();
   }

   static String importsOf(PsiJavaFile file) {
      return (String)CachedValuesManager.getCachedValue(file, () -> Result.create(buildImports(file), new Object[]{file}));
   }

   private static String buildImports(PsiJavaFile file) {
      StringBuilder imports = new StringBuilder();
      PsiImportList list = file.getImportList();
      if (list != null) {
         for(PsiImportStatement statement : list.getImportStatements()) {
            appendImport(imports, statement);
         }

         for(PsiImportStaticStatement statement : list.getImportStaticStatements()) {
            appendImport(imports, statement);
         }
      }

      for(PsiComment comment : PsiTreeUtil.findChildrenOfType(file, PsiComment.class)) {
         TextRange code = MixinCommentContext.codeRange(comment.getText());
         if (code != null) {
            appendMarkedImports(comment.getText().substring(code.getStartOffset(), code.getEndOffset()), imports);
         }
      }

      return imports.toString();
   }

   private static void appendMarkedImports(String code, StringBuilder imports) {
      Matcher matcher = MARKED_IMPORT.matcher(code);

      while(matcher.find()) {
         imports.append(matcher.group(1)).append('\n');
      }

   }

   private static void appendImport(StringBuilder imports, PsiImportStatementBase statement) {
      PsiJavaCodeReferenceElement reference = statement.getImportReference();
      if (reference != null) {
         imports.append(reference.getQualifiedName()).append('\n');
      }

   }

   private static @Nullable List<LookupElement> lookupItems(Object[] variants) {
      List<LookupElement> items = new ArrayList();

      for(Object variant : variants) {
         if (variant instanceof LookupElement element) {
            items.add(element);
         } else if (variant instanceof PsiNamedElement named) {
            items.add(LookupElementBuilder.create(named, named.getName()));
         } else if (variant instanceof PsiElement element) {
            items.add(LookupElementBuilder.create(element.getText()));
         }
      }

      return items.isEmpty() ? null : items;
   }

   static @Nullable String qualifierBefore(Document document, int offset) {
      String logical = MixinCommentContext.logicalContext(document, offset);

      int end;
      for(end = logical.length(); end > 0; --end) {
         char value = logical.charAt(end - 1);
         if (!Character.isJavaIdentifierPart(value) && value != '.') {
            break;
         }
      }

      String chain = logical.substring(end);
      int dot = chain.lastIndexOf(46);
      return dot <= 0 ? null : chain.substring(0, dot);
   }

   private static final class Engine extends CompletionData {
   }

   static enum Scope {
      IMPORT,
      MEMBER,
      STATEMENT;

      // $FF: synthetic method
      private static Scope[] $values() {
         return new Scope[]{IMPORT, MEMBER, STATEMENT};
      }
   }
}
