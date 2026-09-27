package rems.idea.mixincompletion;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiImportStaticStatement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPolyVariantReferenceBase;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class MixinCommentReferenceContributor extends PsiReferenceContributor {
   private static final Pattern ATTRIBUTE = Pattern.compile("\\b(method|target)\\s*=\\s*\"([^\"]+)\"");
   private static final Pattern CLASS_MEMBER = Pattern.compile("\\b([A-Z][A-Za-z0-9_$]*)\\.([A-Za-z_$][A-Za-z0-9_$]*)\\b");
   private static final Pattern AT_VALUE = Pattern.compile("@At\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");
   private static final Pattern SHADOW_ANNOTATION = Pattern.compile("@Shadow\\b");
   private static final Pattern ANNOTATION = Pattern.compile("@([A-Z][A-Za-z0-9_$]*)\\b");
   private static final Pattern ANNOTATION_ATTRIBUTE = Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$]*)\\s*=");
   private static final Pattern CLASS_NAME = Pattern.compile("\\b([A-Z][A-Za-z0-9_$]*)\\b");
   private static final Pattern IDENTIFIER = Pattern.compile("\\b([a-z_$][A-Za-z0-9_$]*)\\b");
   private static final Pattern INSTANCE_MEMBER = Pattern.compile("\\b([a-z_$][A-Za-z0-9_$]*)\\.([A-Za-z_$][A-Za-z0-9_$]*)\\b");
   private static final Pattern SHADOW_METHOD = Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\([^;{}]*\\)\\s*;");
   private static final Pattern SHADOW_FIELD = Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:=[^;]*)?;");
   private static final Pattern DECLARATION_NAME = Pattern.compile("\\b(?:(?:public|protected|private|abstract|final|static|synchronized|native|strictfp|transient|volatile)\\s+)*[A-Za-z_$][A-Za-z0-9_$.<>?, \\[\\]]*\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*(=|;|\\()");
   /**
    * An import written inside the code a marker carries.
    *
    * <p>The qualified name is what has to be resolved; the semicolon and anything after it are not part of
    * it. This is the only form there is - an import under a marker is a {@code //$$} line, or a block that
    * can hold several of them at once.
    */
   private static final Pattern CARRIED_IMPORT =
            Pattern.compile("\\bimport\\s+(?:static\\s+)?([A-Za-z_$][A-Za-z0-9_$.]*)\\s*;?");

   public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {

      registrar.registerReferenceProvider(PlatformPatterns.psiElement(PsiComment.class), new PsiReferenceProvider() {
         public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element, @NotNull ProcessingContext context) {

            PsiComment comment = (PsiComment)element;

            // Every import the marker carries, in whatever form it was written in.
            //
            // What stood here matched one spelling only, `//#import`, while the dialect writes them as a
            // `//$$` line or inside a `/*$$ ... $$*/` block that can hold several. Neither of those matched,
            // so no reference was offered for them at all - a name resolved perfectly well for the colouring
            // and still had nowhere to jump to.
            List<PsiReference> carriedImports = MixinCommentReferenceContributor.importReferences(comment);

            // A line carrying an import is answered with its imports and nothing else. What the patterns below
            // read is a mixin comment - a line about a target and what is done to it - and an import line read
            // as one produces references over the qualified name that are about nothing, which is what took
            // completion away from the one place a reader is most likely to be typing a class name.
            if (!carriedImports.isEmpty()) {
               PsiReference[] var26 = (PsiReference[])carriedImports.toArray(PsiReference.EMPTY_ARRAY);

               return var26;
            }

            List<PsiReference> references = new ArrayList();

            // And every name in the code the marker carries, which the colouring has already resolved.
            references.addAll(MixinCommentReferenceContributor.carriedReferences(comment, references));

            if (MixinCommentContext.codeOfLine(comment.getText()).isEmpty()) {
               PsiReference[] var24 = (PsiReference[])references.toArray(PsiReference.EMPTY_ARRAY);

               return var24;
            } else {
               Matcher declarationMatcher = MixinCommentReferenceContributor.DECLARATION_NAME.matcher(comment.getText());
               TextRange declarationRange = null;
               if (declarationMatcher.find()) {
                  declarationRange = TextRange.create(declarationMatcher.start(1), declarationMatcher.end(1));
                  references.add(new VersionDeclarationReference(comment, declarationRange, declarationMatcher.group(1), "(".equals(declarationMatcher.group(2))));
               }

               Matcher matcher = MixinCommentReferenceContributor.ATTRIBUTE.matcher(comment.getText());

               while(matcher.find()) {
                  references.add(new CommentReference(comment, TextRange.create(matcher.start(2), matcher.end(2)), matcher.group(1), matcher.group(2)));
               }

               Matcher memberMatcher = MixinCommentReferenceContributor.CLASS_MEMBER.matcher(comment.getText());

               while(memberMatcher.find()) {
                  references.add(new MemberReference(comment, TextRange.create(memberMatcher.start(2), memberMatcher.end(2)), memberMatcher.group(1), memberMatcher.group(2)));
               }

               Matcher atMatcher = MixinCommentReferenceContributor.AT_VALUE.matcher(comment.getText());

               while(atMatcher.find()) {
                  references.add(new AtValueReference(comment, TextRange.create(atMatcher.start(1), atMatcher.end(1)), atMatcher.group(1)));
               }

               Matcher shadowAnnotation = MixinCommentReferenceContributor.SHADOW_ANNOTATION.matcher(comment.getText());

               while(shadowAnnotation.find()) {
                  references.add(new ClassReference(comment, TextRange.create(shadowAnnotation.start() + 1, shadowAnnotation.end()), "org.spongepowered.asm.mixin.Shadow"));
               }

               Matcher annotationMatcher = MixinCommentReferenceContributor.ANNOTATION.matcher(comment.getText());

               while(annotationMatcher.find()) {
                  String name = annotationMatcher.group(1);
                  if (!"Shadow".equals(name)) {
                     references.add(new AnnotationReference(comment, TextRange.create(annotationMatcher.start(1), annotationMatcher.end(1)), name));
                  }
               }

               Matcher attributeNameMatcher = MixinCommentReferenceContributor.ANNOTATION_ATTRIBUTE.matcher(comment.getText());

               while(attributeNameMatcher.find()) {
                  String annotationName = MixinCommentReferenceContributor.enclosingAnnotation(comment, attributeNameMatcher.end(1));
                  if (annotationName != null) {
                     references.add(new AnnotationAttributeReference(comment, TextRange.create(attributeNameMatcher.start(1), attributeNameMatcher.end(1)), annotationName, attributeNameMatcher.group(1)));
                  }
               }

               Matcher classMatcher = MixinCommentReferenceContributor.CLASS_NAME.matcher(comment.getText());

               while(classMatcher.find()) {
                  int start = classMatcher.start(1);
                  if (start <= 0 || comment.getText().charAt(start - 1) != '@') {
                     references.add(new ClassNameReference(comment, TextRange.create(start, classMatcher.end(1)), classMatcher.group(1)));
                  }
               }

               Matcher identifierMatcher = MixinCommentReferenceContributor.IDENTIFIER.matcher(comment.getText());

               while(identifierMatcher.find()) {
                  String name = identifierMatcher.group(1);
                  if ((declarationRange == null || declarationRange.getStartOffset() != identifierMatcher.start(1) || declarationRange.getEndOffset() != identifierMatcher.end(1)) && !MixinCommentReferenceContributor.isJavaWord(name) && !MixinCommentReferenceContributor.isAnnotationAttributeAt(comment.getText(), identifierMatcher.start(1), identifierMatcher.end(1))) {
                     PsiComment declaration = MixinCommentReferenceContributor.findVariableDeclaration(comment, name);
                     if (declaration != null) {
                        references.add(new VariableReference(comment, TextRange.create(identifierMatcher.start(1), identifierMatcher.end(1)), declaration));
                     }
                  }
               }

               Matcher instanceMemberMatcher = MixinCommentReferenceContributor.INSTANCE_MEMBER.matcher(comment.getText());

               while(instanceMemberMatcher.find()) {
                  references.add(new InstanceMemberReference(comment, TextRange.create(instanceMemberMatcher.start(2), instanceMemberMatcher.end(2)), instanceMemberMatcher.group(1), instanceMemberMatcher.group(2)));
               }

               if (MixinCommentReferenceContributor.isShadowDeclaration(comment)) {
                  Matcher shadowMethod = MixinCommentReferenceContributor.SHADOW_METHOD.matcher(comment.getText());
                  if (shadowMethod.find() && !"Shadow".equals(shadowMethod.group(1))) {
                     references.add(new ShadowMemberReference(comment, TextRange.create(shadowMethod.start(1), shadowMethod.end(1)), shadowMethod.group(1), true));
                  } else {
                     Matcher shadowField = MixinCommentReferenceContributor.SHADOW_FIELD.matcher(comment.getText());
                     if (shadowField.find()) {
                        references.add(new ShadowMemberReference(comment, TextRange.create(shadowField.start(1), shadowField.end(1)), shadowField.group(1), false));
                     }
                  }
               }

               PsiReference[] var10000 = (PsiReference[])references.toArray(PsiReference.EMPTY_ARRAY);

               return var10000;
            }
         }

         // $FF: synthetic method
         
      });
   }

   static @Nullable PsiElement resolveVersionDeclaration(PsiComment comment, String memberName, boolean method) {
      Resolution resolution = resolution(comment);
      if (resolution != null && resolution.module() != null) {
         PsiClass containingClass = (PsiClass)PsiTreeUtil.getParentOfType(comment, PsiClass.class, false);
         if (containingClass != null && containingClass.getName() != null) {
            PsiClass selectedClass = MixinDescriptorCompletionContributor.findVersionSourceClass(containingClass, resolution.module(), containingClass.getName());
            if (selectedClass == null) {
               return null;
            } else if (!method) {
               return selectedClass.findFieldByName(memberName, false);
            } else {
               PsiMethod[] methods = selectedClass.findMethodsByName(memberName, false);
               return methods.length == 0 ? null : methods[0];
            }
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   private static boolean isShadowDeclaration(PsiComment comment) {
      if (SHADOW_ANNOTATION.matcher(comment.getText()).find()) {
         return true;
      } else {
         Document document = PsiDocumentManager.getInstance(comment.getProject()).getDocument(comment.getContainingFile());
         if (document == null) {
            return false;
         } else {
            int line = document.getLineNumber(comment.getTextOffset());

            for(int previous = line - 1; previous >= Math.max(0, line - 3); --previous) {
               int start = document.getLineStartOffset(previous);
               int end = document.getLineEndOffset(previous);
               String text = document.getImmutableCharSequence().subSequence(start, end).toString();
               if (!text.stripLeading().startsWith("//$$")) {
                  return false;
               }

               if (SHADOW_ANNOTATION.matcher(text).find()) {
                  return true;
               }

               if (!text.strip().equals("//$$")) {
                  return false;
               }
            }

            return false;
         }
      }
   }

   private static String enclosingAnnotation(PsiComment comment, int relativeOffset) {
      Document document = PsiDocumentManager.getInstance(comment.getProject()).getDocument(comment.getContainingFile());
      if (document == null) {
         return null;
      } else {
         String logical = MixinCommentContext.logicalContext(document, comment.getTextOffset() + relativeOffset);
         return MixinCommentContext.enclosingAnnotation(logical);
      }
   }

   private static boolean isAnnotationAttributeAt(String text, int start, int end) {
      Matcher matcher = ANNOTATION_ATTRIBUTE.matcher(text);

      while(matcher.find()) {
         if (matcher.start(1) == start && matcher.end(1) == end) {
            return true;
         }
      }

      return false;
   }

   private static boolean isJavaWord(String value) {
      boolean var10000;
      switch (value) {
         case "if":
         case "else":
         case "for":
         case "while":
         case "return":
         case "new":
         case "this":
         case "super":
         case "true":
         case "false":
         case "null":
         case "private":
         case "protected":
         case "public":
         case "static":
         case "final":
         case "abstract":
         case "void":
         case "boolean":
         case "byte":
         case "char":
         case "short":
         case "int":
         case "long":
         case "float":
         case "double":
         case "class":
         case "interface":
         case "enum":
         case "record":
         case "extends":
         case "implements":
         case "instanceof":
         case "throw":
         case "throws":
         case "try":
         case "catch":
         case "finally":
         case "switch":
         case "case":
         case "default":
            var10000 = true;
            break;
         default:
            var10000 = false;
      }

      return var10000;
   }

   private static PsiComment findVariableDeclaration(PsiComment usage, String name) {
      Document document = PsiDocumentManager.getInstance(usage.getProject()).getDocument(usage.getContainingFile());
      if (document == null) {
         return null;
      } else {
         int currentLine = document.getLineNumber(usage.getTextOffset());
         Pattern declaration = Pattern.compile("\\b(?:[A-Z][A-Za-z0-9_$.]*(?:\\s*<[^;=(){}]+>)?(?:\\s*\\[\\])*|boolean|byte|char|short|int|long|float|double|var)\\s+" + Pattern.quote(name) + "\\b");

         for(int line = currentLine; line >= Math.max(0, currentLine - 100); --line) {
            int start = document.getLineStartOffset(line);
            int end = document.getLineEndOffset(line);
            String text = document.getImmutableCharSequence().subSequence(start, end).toString();
            if (!text.stripLeading().startsWith("//$$")) {
               break;
            }

            if (declaration.matcher(text).find()) {
               PsiElement element = usage.getContainingFile().findElementAt(Math.min(end - 1, start + Math.max(0, text.indexOf("//$$"))));
               PsiComment comment = (PsiComment)PsiTreeUtil.getParentOfType(element, PsiComment.class, false);
               if (comment != null) {
                  return comment;
               }
            }
         }

         return null;
      }
   }

   private static PsiClass resolveAnnotationClass(PsiComment comment, String shortName) {
      Resolution resolution = resolution(comment);
      return resolution == null ? null : MixinMetadataResolver.findAnnotationClass(comment, shortName, resolution.scope());
   }

   private static @Nullable PsiField resolveStaticImportedField(PsiComment comment, String fieldName, Resolution resolution) {
      PsiFile hostFile = comment.getContainingFile();
      if (!(hostFile instanceof PsiJavaFile javaFile)) {
         return null;
      } else {
         PsiImportList imports = javaFile.getImportList();
         if (imports == null) {
            return null;
         } else {
            JavaPsiFacade facade = JavaPsiFacade.getInstance(comment.getProject());

            for(PsiImportStaticStatement statement : imports.getImportStaticStatements()) {
               if (statement.isOnDemand() || fieldName.equals(statement.getReferenceName())) {
                  PsiClass importedClass = statement.resolveTargetClass();
                  if (importedClass != null) {
                     String qualifiedName = importedClass.getQualifiedName();
                     PsiClass selectedClass = qualifiedName == null ? importedClass : facade.findClass(qualifiedName, resolution.scope());
                     if (selectedClass == null) {
                        selectedClass = importedClass;
                     }

                     PsiField field = selectedClass.findFieldByName(fieldName, true);
                     if (field != null) {
                        return field;
                     }
                  }
               }
            }

            return null;
         }
      }
   }

   private static Resolution resolution(PsiComment comment) {
      Document document = PsiDocumentManager.getInstance(comment.getProject()).getDocument(comment.getContainingFile());
      if (document == null) {
         return null;
      } else {
         int line = document.getLineNumber(comment.getTextOffset());
         PsiClass target = resolveVersionTarget(comment);
         if (target == null) {
            return null;
         } else {
            Module module = VersionedModuleResolver.resolveModule(comment.getProject(), document, line);
            GlobalSearchScope scope = module == null ? target.getResolveScope() : GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, false);
            return new Resolution(target, module, scope);
         }
      }
   }

   private static @Nullable PsiClass resolveVersionTarget(PsiComment comment) {
      PsiClass currentTarget = MixinDescriptorCompletionContributor.findMixinTarget(comment);
      if (currentTarget == null) {
         currentTarget = (PsiClass)PsiTreeUtil.getParentOfType(comment, PsiClass.class, false);
      }

      if (currentTarget == null) {
         return null;
      } else {
         Document document = PsiDocumentManager.getInstance(comment.getProject()).getDocument(comment.getContainingFile());
         if (document == null) {
            return null;
         } else {
            int line = document.getLineNumber(comment.getTextOffset());
            return VersionedModuleResolver.retarget(currentTarget, comment.getProject(), document, line);
         }
      }
   }

   // $FF: synthetic method
   

   /**
    * Where every name in the code a marker carries goes.
    *
    * <p>Carried code is a comment as far as the file is concerned, so nothing in it is a reference and Go to
    * Declaration has nothing to follow. The colouring has already worked out what each of those names stands
    * for - and, in a project built against several versions at once, which version's copy of it - so the
    * references are read from there rather than answered a second time. Two answers to the same question is
    * one answer too many: a name coloured as a class has to be a name that can be jumped to.
    *
    * <p>Ranges the comment's other references already cover are left alone. What those answer is not this
    * function's business, and offering a second reference over the same characters is how one of the two ends
    * up answering for both.
    */
   private static List<PsiReference> carriedReferences(PsiComment comment, List<PsiReference> taken) {
      List<PsiReference> references = new ArrayList<>();
      int base = comment.getTextRange().getStartOffset();

      for(Map.Entry<TextRange, PsiElement> entry : VersionedBlockAnnotator.namesOf(comment).entrySet()) {
         PsiElement target = (PsiElement)entry.getValue();
         TextRange range = (TextRange)entry.getKey();

         if (target == null || !target.isValid()) {
            continue;
         }

         TextRange inComment = TextRange.create(range.getStartOffset() - base, range.getEndOffset() - base);
         if (inComment.getStartOffset() < 0 || inComment.getEndOffset() > comment.getTextLength()) {
            continue;
         }

         if (covers(taken, inComment) || covers(references, inComment)) {
            continue;
         }

         references.add(new CarriedCodeReference(comment, inComment, target));
      }

      return references;
   }

   /** Whether any of these references is written over some of the same characters. */
   private static boolean covers(List<PsiReference> references, TextRange range) {
      for(PsiReference reference : references) {
         TextRange other = reference.getRangeInElement();
         if (other.getStartOffset() < range.getEndOffset() && range.getStartOffset() < other.getEndOffset()) {
            return true;
         }
      }

      return false;
   }

   /** A name in carried code, going wherever the colouring found it to belong. */
   private static final class CarriedCodeReference extends PsiReferenceBase<PsiComment> {
      private final PsiElement target;

      CarriedCodeReference(PsiComment comment, TextRange range, PsiElement target) {
         // Soft: what the name is has already been decided, and a reference that cannot be followed is a name
         // the colouring left unresolved - not something to draw the reader's attention to twice.
         super(comment, range, true);
         this.target = target;
      }

      public @Nullable PsiElement resolve() {
         return this.target.isValid() ? this.target : null;
      }

      public Object @NotNull [] getVariants() {
         return EMPTY_ARRAY;
      }
   }

   private static final class VersionDeclarationReference extends PsiPolyVariantReferenceBase<PsiComment> {
      private final String memberName;
      private final boolean method;

      private VersionDeclarationReference(PsiComment element, TextRange range, String memberName, boolean method) {
         super(element, range, true);
         this.memberName = memberName;
         this.method = method;
      }

      public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
         PsiElement target = MixinCommentReferenceContributor.resolveVersionDeclaration((PsiComment)this.getElement(), this.memberName, this.method);
         if (target == null) {
            ResolveResult[] var18 = ResolveResult.EMPTY_ARRAY;

            return var18;
         } else {
            Resolution resolution = MixinCommentReferenceContributor.resolution((PsiComment)this.getElement());
            if (resolution != null && resolution.module() != null) {
               List<PsiElement> all = new ArrayList();
               List<PsiElement> source = new ArrayList();

               for(PsiReference reference : ReferencesSearch.search(target, GlobalSearchScope.moduleScope(resolution.module())).findAll()) {
                  PsiElement element = reference.getElement();
                  if (element.isValid() && !(element instanceof PsiComment)) {
                     all.add(element);
                     PsiFile usageFile = element.getContainingFile();
                     String path = usageFile != null && usageFile.getVirtualFile() != null ? usageFile.getVirtualFile().getPath().replace('\\', '/') : "";
                     if (!path.contains("/build/preprocessed/")) {
                        source.add(element);
                     }
                  }
               }

               List<PsiElement> preferred = source.isEmpty() ? all : source;
               if (preferred.isEmpty()) {
                  preferred = List.of(target);
               }

               LinkedHashMap<String, PsiElement> unique = new LinkedHashMap();

               for(PsiElement element : preferred) {
                  PsiFile usageFile = element.getContainingFile();
                  String path = usageFile != null && usageFile.getVirtualFile() != null ? usageFile.getVirtualFile().getPath() : "";
                  unique.putIfAbsent(path + ":" + element.getTextOffset(), element);
               }

               ResolveResult[] var17 = (ResolveResult[])unique.values().stream().map(PsiElementResolveResult::new).toArray((x$0) -> new ResolveResult[x$0]);

               return var17;
            } else {
               ResolveResult[] var10000 = new ResolveResult[]{new PsiElementResolveResult(target)};

               return var10000;
            }
         }
      }

      // $FF: synthetic method
      
   }

   private static final class ClassNameReference extends PsiReferenceBase<PsiComment> {
      private final String className;

      private ClassNameReference(PsiComment element, TextRange range, String className) {
         super(element, range, true);
         this.className = className;
      }

      public @Nullable PsiElement resolve() {
         PsiElement carried = VersionedBlockAnnotator.resolveNameCovering((PsiComment)this.getElement(), this.getRangeInElement());
         if (carried != null) {
            return carried;
         } else {
            Resolution resolution = MixinCommentReferenceContributor.resolution((PsiComment)this.getElement());
            if (resolution == null) {
               return null;
            } else {
               PsiClass sourceClass = MixinDescriptorCompletionContributor.findVersionSourceClass(resolution.target(), resolution.module(), this.className);
               if (sourceClass != null) {
                  return sourceClass;
               } else {
                  PsiClass[] classes = PsiShortNamesCache.getInstance(((PsiComment)this.getElement()).getProject()).getClassesByName(this.className, resolution.scope());
                  return (PsiElement)(classes.length > 0 ? classes[0] : MixinCommentReferenceContributor.resolveStaticImportedField((PsiComment)this.getElement(), this.className, resolution));
               }
            }
         }
      }
   }

   private static final class AnnotationAttributeReference extends PsiReferenceBase<PsiComment> {
      private final String annotationName;
      private final String attributeName;

      private AnnotationAttributeReference(PsiComment element, TextRange range, String annotationName, String attributeName) {
         super(element, range, true);
         this.annotationName = annotationName;
         this.attributeName = attributeName;
      }

      public @Nullable PsiElement resolve() {
         PsiClass annotationClass = MixinCommentReferenceContributor.resolveAnnotationClass((PsiComment)this.getElement(), this.annotationName);
         if (annotationClass == null) {
            return null;
         } else {
            PsiMethod[] methods = annotationClass.findMethodsByName(this.attributeName, false);
            return methods.length == 0 ? null : methods[0];
         }
      }
   }

   private static final class VariableReference extends PsiReferenceBase<PsiComment> {
      private final PsiComment declaration;

      private VariableReference(PsiComment element, TextRange range, PsiComment declaration) {
         super(element, range, true);
         this.declaration = declaration;
      }

      public PsiElement resolve() {
         return this.declaration.isValid() ? this.declaration : null;
      }
   }

   private static final class InstanceMemberReference extends PsiReferenceBase<PsiComment> {
      private final String qualifier;
      private final String memberName;

      private InstanceMemberReference(PsiComment element, TextRange range, String qualifier, String memberName) {
         super(element, range, true);
         this.qualifier = qualifier;
         this.memberName = memberName;
      }

      public @Nullable PsiElement resolve() {
         Resolution resolution = MixinCommentReferenceContributor.resolution((PsiComment)this.getElement());
         if (resolution == null) {
            return null;
         } else {
            Document document = PsiDocumentManager.getInstance(((PsiComment)this.getElement()).getProject()).getDocument(((PsiComment)this.getElement()).getContainingFile());
            if (document == null) {
               return null;
            } else {
               int line = document.getLineNumber(((PsiComment)this.getElement()).getTextOffset());
               PsiClass type = MixinDescriptorCompletionContributor.resolveQualifierType(resolution.target(), resolution.module(), document, line, this.qualifier, resolution.scope());
               if (type == null) {
                  return null;
               } else {
                  PsiField field = type.findFieldByName(this.memberName, true);
                  if (field != null) {
                     return field;
                  } else {
                     PsiMethod[] methods = type.findMethodsByName(this.memberName, true);
                     return methods.length == 0 ? null : methods[0];
                  }
               }
            }
         }
      }
   }

   private static final class CommentReference extends PsiReferenceBase<PsiComment> {
      private final String attribute;
      private final String value;

      private CommentReference(PsiComment element, TextRange range, String attribute, String value) {
         super(element, range, true);
         this.attribute = attribute;
         this.value = value;
      }

      public @Nullable PsiElement resolve() {
         PsiClass versionTarget = MixinCommentReferenceContributor.resolveVersionTarget((PsiComment)this.getElement());
         if (versionTarget == null) {
            return null;
         } else {
            return "method".equals(this.attribute) ? findMethod(versionTarget, this.value, false) : this.resolveAtTarget(versionTarget, this.value);
         }
      }

      private @Nullable PsiMethod resolveAtTarget(PsiClass versionTarget, String target) {
         if (!target.startsWith("L")) {
            return null;
         } else {
            int ownerEnd = target.indexOf(59);
            int descriptorStart = target.indexOf(40, ownerEnd + 1);
            if (ownerEnd >= 2 && descriptorStart >= 0) {
               String ownerName = target.substring(1, ownerEnd).replace('/', '.').replace('$', '.');
               String selector = target.substring(ownerEnd + 1);
               Resolution resolution = MixinCommentReferenceContributor.resolution((PsiComment)this.getElement());
               GlobalSearchScope scope = resolution == null ? versionTarget.getResolveScope() : resolution.scope();
               PsiClass owner = JavaPsiFacade.getInstance(((PsiComment)this.getElement()).getProject()).findClass(ownerName, scope);
               return owner == null ? null : findMethod(owner, selector, true);
            } else {
               return null;
            }
         }
      }

      private static @Nullable PsiMethod findMethod(PsiClass owner, String selector, boolean includeBases) {
         int descriptorStart = selector.indexOf(40);
         String name = descriptorStart < 0 ? selector : selector.substring(0, descriptorStart);
         String descriptor = descriptorStart < 0 ? null : selector.substring(descriptorStart);

         for(PsiMethod method : owner.findMethodsByName(name, includeBases)) {
            if (descriptor == null || descriptor.equals(JvmDescriptors.methodDescriptor(method))) {
               return method;
            }
         }

         return null;
      }
   }

   private static final class MemberReference extends PsiReferenceBase<PsiComment> {
      private final String className;
      private final String memberName;

      private MemberReference(PsiComment element, TextRange range, String className, String memberName) {
         super(element, range, true);
         this.className = className;
         this.memberName = memberName;
      }

      public @Nullable PsiElement resolve() {
         Resolution resolution = MixinCommentReferenceContributor.resolution((PsiComment)this.getElement());
         if (resolution == null) {
            return null;
         } else {
            PsiClass selectedClass = MixinDescriptorCompletionContributor.findVersionSourceClass(resolution.target(), resolution.module(), this.className);
            PsiClass[] classes = PsiShortNamesCache.getInstance(((PsiComment)this.getElement()).getProject()).getClassesByName(this.className, resolution.scope());

            for(PsiClass candidate : classes) {
               if (resolution.module() != null && resolution.module().equals(ModuleUtilCore.findModuleForPsiElement(candidate))) {
                  selectedClass = candidate;
                  break;
               }

               if (selectedClass == null) {
                  selectedClass = candidate;
               }
            }

            if (selectedClass == null) {
               return null;
            } else {
               PsiField field = selectedClass.findFieldByName(this.memberName, true);
               if (field != null) {
                  return field;
               } else {
                  PsiMethod[] methods = selectedClass.findMethodsByName(this.memberName, true);
                  return methods.length == 0 ? null : methods[0];
               }
            }
         }
      }
   }

   private static final class AtValueReference extends PsiReferenceBase<PsiComment> {
      private final String code;

      private AtValueReference(PsiComment element, TextRange range, String code) {
         super(element, range, true);
         this.code = code;
      }

      public @Nullable PsiElement resolve() {
         Resolution resolution = MixinCommentReferenceContributor.resolution((PsiComment)this.getElement());
         if (resolution == null) {
            return null;
         } else {
            return this.code.indexOf(46) > 0 ? JavaPsiFacade.getInstance(((PsiComment)this.getElement()).getProject()).findClass(this.code, resolution.scope()) : MixinMetadataResolver.findInjectionPoint(((PsiComment)this.getElement()).getProject(), resolution.scope(), this.code);
         }
      }
   }

   private static final class AnnotationReference extends PsiReferenceBase<PsiComment> {
      private final String shortName;

      private AnnotationReference(PsiComment element, TextRange range, String shortName) {
         super(element, range, true);
         this.shortName = shortName;
      }

      public @Nullable PsiElement resolve() {
         return MixinCommentReferenceContributor.resolveAnnotationClass((PsiComment)this.getElement(), this.shortName);
      }
   }

   private static final class ClassReference extends PsiReferenceBase<PsiComment> {
      private final String qualifiedName;

      private ClassReference(PsiComment element, TextRange range, String qualifiedName) {
         super(element, range, true);
         this.qualifiedName = qualifiedName;
      }

      public @Nullable PsiElement resolve() {
         Resolution resolution = MixinCommentReferenceContributor.resolution((PsiComment)this.getElement());
         return resolution == null ? null : JavaPsiFacade.getInstance(((PsiComment)this.getElement()).getProject()).findClass(this.qualifiedName, resolution.scope());
      }
   }

   /**
    * A reference for each import the code a marker carries holds.
    *
    * <p>Read from the carried code rather than from the comment as a whole, which is what lets one block
    * contribute several of them.
    */
   private static List<PsiReference> importReferences(PsiComment comment) {
      List<PsiReference> references = new ArrayList();
      TextRange carried = MixinCommentContext.codeRange(comment.getText());

      if (carried == null) {
         return references;
      }

      Matcher matcher = CARRIED_IMPORT.matcher(
              comment.getText().substring(carried.getStartOffset(), carried.getEndOffset()));

      while(matcher.find()) {
         references.add(new ImportClassReference(comment,
                 TextRange.create(carried.getStartOffset() + matcher.start(1),
                         carried.getStartOffset() + matcher.end(1)),
                 matcher.group(1)));
      }

      return references;
   }

   private static final class ImportClassReference extends PsiReferenceBase<PsiComment> {
      private final String qualifiedName;

      private ImportClassReference(PsiComment element, TextRange range, String qualifiedName) {
         super(element, range, true);
         this.qualifiedName = qualifiedName;
      }

      public @Nullable PsiElement resolve() {
         Document document = PsiDocumentManager.getInstance(((PsiComment)this.getElement()).getProject()).getDocument(((PsiComment)this.getElement()).getContainingFile());
         if (document == null) {
            return null;
         } else {
            int line = document.getLineNumber(((PsiComment)this.getElement()).getTextOffset());
            Module module = VersionedModuleResolver.resolveModule(((PsiComment)this.getElement()).getProject(), document, line);
            GlobalSearchScope scope = module == null ? GlobalSearchScope.allScope(((PsiComment)this.getElement()).getProject()) : GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, false);
            return JavaPsiFacade.getInstance(((PsiComment)this.getElement()).getProject()).findClass(this.qualifiedName, scope);
         }
      }
   }

   private static final class ShadowMemberReference extends PsiReferenceBase<PsiComment> {
      private final String memberName;
      private final boolean method;

      private ShadowMemberReference(PsiComment element, TextRange range, String memberName, boolean method) {
         super(element, range, true);
         this.memberName = memberName;
         this.method = method;
      }

      public @Nullable PsiElement resolve() {
         PsiClass target = MixinCommentReferenceContributor.resolveVersionTarget((PsiComment)this.getElement());
         if (target == null) {
            return null;
         } else if (!this.method) {
            return target.findFieldByName(this.memberName, true);
         } else {
            PsiMethod[] methods = target.findMethodsByName(this.memberName, true);
            return methods.length == 0 ? null : methods[0];
         }
      }
   }

   private static record Resolution(PsiClass target, Module module, GlobalSearchScope scope) {
   }
}
