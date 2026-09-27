package rems.idea.mixincompletion;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons.Nodes;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileFilter;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiLiteralValue;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.ProcessingContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

public final class MixinDescriptorCompletionContributor extends CompletionContributor {
   private static final Logger LOG = Logger.getInstance(MixinDescriptorCompletionContributor.class);
   private static final Pattern COMMENT_ATTRIBUTE = Pattern.compile("\\b(method|target)\\s*=\\s*\"([^\"]*)$");
   private static final Pattern COMMENT_METHOD = Pattern.compile("\\bmethod\\s*=\\s*\"([^\"]+)\"");
   private static final Pattern COMMENT_ANNOTATION = Pattern.compile("@([A-Za-z0-9_$]*)$");
   private static final Pattern COMMENT_AT_VALUE = Pattern.compile("@At\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)$");
   private static final Pattern COMMENT_KEYWORD = Pattern.compile("^\\s*(?://\\$\\$\\s*)+(?:[A-Za-z_$][A-Za-z0-9_$]*\\s+)*([A-Za-z_$]*)$");
   private static final Pattern COMMENT_METHOD_PARAMETERS = Pattern.compile("^\\s*(?://\\$\\$\\s*)+(?:(?:public|protected|private|abstract|final|static|synchronized|native|strictfp)\\s+)+[A-Za-z_$][A-Za-z0-9_$.<>?\\[\\]]*\\s+[A-Za-z_$][A-Za-z0-9_$]*\\(([^)]*)$");
   private static final Pattern COMMENT_IDENTIFIER = Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)$");
   private static final Pattern COMMENT_MEMBER = Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)\\.([A-Za-z_$]*)$");
   private static final Pattern COMMENT_ANNOTATION_ATTRIBUTE = Pattern.compile("@([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\((?:[^@)]*,\\s*)?([A-Za-z_$]*)$");
   private static final Pattern PREPROCESSOR_DIRECTIVE = Pattern.compile("^\\s*(?://\\$\\$\\s*)*//#([A-Za-z]*)$");
   private static final Pattern PREPROCESSOR_OPERATOR = Pattern.compile("^\\s*(?://\\$\\$\\s*)*//#(?:if|elseif|elif|replace)\\s+MC\\s*([!<>=]*|(?:not\\s+)?i?n?)$");
   private static final Pattern PREPROCESSOR_VERSION = Pattern.compile("^\\s*(?://\\$\\$\\s*)*//#(?:if|elseif|elif|replace)\\s+.*(?:>=|<=|==|!=|>|<|\\.\\.|\\bin\\s+|\\[|,)\\s*([0-9._]*)$");
   private static final Pattern PREPROCESSOR_LOGICAL = Pattern.compile("^\\s*(?://\\$\\$\\s*)*//#(?:if|elseif|elif)\\s+.*(?:[0-9A-Za-z_$.)\\]])\\s*([&|]*)$");
   /** An import under a marker, with the qualified name still being typed after it. */
   private static final Pattern PREPROCESSOR_IMPORT = Pattern.compile("^\\s*(?://\\$\\$\\s*|/\\*\\$\\$)\\s*import\\s+([A-Za-z0-9_$.]*)$");
   private static final Pattern PREPROCESSOR_EXPRESSION_NAME = Pattern.compile("^\\s*(?://\\$\\$\\s*)*//#(?:if|elseif|elif|replace)\\s+(?:.*(?:&&|\\|\\||!|\\()\\s*)?([A-Za-z_$][A-Za-z0-9_$]*)?$");
   private static final Pattern PREPROCESSOR_DEFINE = Pattern.compile("(?m)^\\s*(?://\\$\\$\\s*)*//#define\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b");
   private static final Pattern REPLACE_TAIL = Pattern.compile("^\\s*(?://\\$\\$\\s*)*//#replace\\s+.*(?:[0-9A-Za-z_$.)\\]])\\s*([&|?]*)$");
   private static final Pattern CASE_NAME = Pattern.compile("^\\s*//\\?\\s*([A-Za-z_$][A-Za-z0-9_$]*)?$");
   private static final Pattern CASE_OPERATOR = Pattern.compile("^\\s*//\\?\\s*MC\\s*([!<>=]*|(?:not\\s+)?i?n?)$");
   private static final Pattern CASE_VERSION = Pattern.compile("^\\s*//\\?\\s*.*(?:>=|<=|==|!=|>|<|\\.\\.|\\bin\\s+|\\[|,)\\s*([0-9._]*)$");
   private static final Pattern CASE_TAIL = Pattern.compile("^\\s*//\\?\\s*.*(?:[0-9A-Za-z_$.)\\]])\\s*([&|?]*)$");
   private static final Pattern PREPROCESSOR_NODE = Pattern.compile("createNode\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*([0-9_]+)");
   private static final Pattern COMMENT_DECLARATION_NAME = Pattern.compile("^(?:(?:public|protected|private|abstract|final|static|synchronized|native|strictfp|transient|volatile)\\s+)*([A-Za-z_$][A-Za-z0-9_$.]*(?:\\s*<[^;=(){}]+>)?(?:\\s*\\[\\])*)\\s+([A-Za-z_$][A-Za-z0-9_$]*)$");
   private static final Pattern LOCAL_VARIABLE_DECLARATION = Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$.]*(?:\\s*<[^;=(){}]+>)?(?:\\s*\\[\\])*)\\s+([a-z_$][A-Za-z0-9_$]*)\\b");
   private static final Pattern COMMENT_METHOD_DECLARATION = Pattern.compile("(?:^|[;{}\\r\\n])\\s*(?:(?:public|protected|private|abstract|final|static|synchronized|native|strictfp)\\s+)*([A-Za-z_$][A-Za-z0-9_$.<>?, \\[\\]]*)\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*\\([^)]*\\)\\s*\\{");
   private static final List<String> PREFERRED_ANNOTATIONS = List.of("Inject", "At", "Shadow", "Mixin", "Unique", "Redirect", "Overwrite", "ModifyArg", "ModifyArgs", "ModifyVariable", "ModifyConstant", "WrapOperation", "ModifyExpressionValue", "WrapWithCondition", "Local", "Share");
   private static final List<String> JAVA_DECLARATION_KEYWORDS = List.of("private", "protected", "public", "abstract", "final", "static", "synchronized", "native", "strictfp", "transient", "volatile", "class", "interface", "enum", "record", "void", "boolean", "byte", "char", "double", "float", "int", "long", "short");
   private static final List<String> JAVA_INITIAL_MODIFIERS = List.of("private", "protected", "public", "abstract", "final", "static", "synchronized", "native", "strictfp", "transient", "volatile");
   private static final List<String> JAVA_EXPRESSION_KEYWORDS = List.of("this", "super", "new", "true", "false", "null");

   public MixinDescriptorCompletionContributor() {
      this.extend(CompletionType.BASIC, PlatformPatterns.psiElement(JavaTokenType.STRING_LITERAL), new Provider());
      this.extend(CompletionType.BASIC, PlatformPatterns.psiElement(JavaTokenType.END_OF_LINE_COMMENT), new CommentProvider());
      this.extend(CompletionType.BASIC, PlatformPatterns.psiElement(JavaTokenType.C_STYLE_COMMENT), new CommentProvider());
   }

   static LookupElement[] preprocessorItems(PsiFile file, String beforeCaret) {
      LookupElement[] caseItems = caseItems(file, beforeCaret);
      if (caseItems.length > 0) {
         return caseItems;
      } else {
         Matcher importMatcher = PREPROCESSOR_IMPORT.matcher(beforeCaret);
         if (importMatcher.matches()) {
            return preprocessorImportItems(file, importMatcher.group(1));
         } else {
            Matcher versionMatcher = PREPROCESSOR_VERSION.matcher(beforeCaret);
            if (versionMatcher.matches()) {
               String prefix = versionMatcher.group(1);
               return versionItems(preprocessorVersions(file), prefix, (item, value) -> withPreprocessorVersionReplacement(item, value));
            } else {
               Matcher operatorMatcher = PREPROCESSOR_OPERATOR.matcher(beforeCaret);
               if (operatorMatcher.matches()) {
                  String prefix = operatorMatcher.group(1);
                  return (LookupElement[])List.of(new PreprocessorOperator("!=", "Not equal to"), new PreprocessorOperator("<", "Less than"), new PreprocessorOperator("<=", "Less than or equal to"), new PreprocessorOperator(">", "Greater than"), new PreprocessorOperator(">=", "Greater than or equal to"), new PreprocessorOperator("==", "Equal to"), new PreprocessorOperator("in ", "Contained in a range or set"), new PreprocessorOperator("not in ", "Not contained in a range or set")).stream().filter((operator) -> operator.value().startsWith(prefix)).map((operator) -> withPreprocessorReplacement(LookupElementBuilder.create(operator.value()).withTypeText(operator.description(), true), operator.value(), true)).toArray((x$0) -> new LookupElement[x$0]);
               } else {
                  Matcher expressionNameMatcher = PREPROCESSOR_EXPRESSION_NAME.matcher(beforeCaret);
                  if (expressionNameMatcher.matches()) {
                     String prefix = expressionNameMatcher.group(1) == null ? "" : expressionNameMatcher.group(1);
                     LinkedHashSet<String> names = preprocessorNames(file);
                     boolean completeName = names.stream().anyMatch((name) -> name.equalsIgnoreCase(prefix));
                     if (!completeName) {
                        LookupElement[] nameItems = (LookupElement[])names.stream().filter((name) -> name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).map((name) -> withExpressionNameReplacement(LookupElementBuilder.create(name).withTypeText(name.equals("MC") ? "Primary version variable" : (name.equals("defined()") ? "Test whether a variable is defined" : "Condition alias"), true), name)).toArray((x$0) -> new LookupElement[x$0]);
                        if (nameItems.length > 0) {
                           return nameItems;
                        }
                     }
                  }

                  Matcher logicalMatcher = PREPROCESSOR_LOGICAL.matcher(beforeCaret);
                  if (logicalMatcher.matches()) {
                     String prefix = logicalMatcher.group(1);
                     return (LookupElement[])List.of(new PreprocessorOperator("&&", "Logical AND"), new PreprocessorOperator("||", "Logical OR")).stream().filter((operator) -> operator.value().startsWith(prefix)).map((operator) -> withTrailingTokenReplacement(LookupElementBuilder.create(operator.value()).withTypeText(operator.description(), true), logicalMatcher.start(1), operator.value() + " ")).toArray((x$0) -> new LookupElement[x$0]);
                  } else {
                     Matcher replaceTailMatcher = REPLACE_TAIL.matcher(beforeCaret);
                     if (replaceTailMatcher.matches()) {
                        String prefix = replaceTailMatcher.group(1);
                        return (LookupElement[])List.of(new PreprocessorOperator("&&", "Logical AND"), new PreprocessorOperator("||", "Logical OR"), new PreprocessorOperator("?", "Begin replacement source code")).stream().filter((item) -> item.value().startsWith(prefix)).map((item) -> withTrailingTokenReplacement(LookupElementBuilder.create(item.value()).withTypeText(item.description(), true), replaceTailMatcher.start(1), item.value() + " ")).toArray((x$0) -> new LookupElement[x$0]);
                     } else {
                        Matcher directiveMatcher = PREPROCESSOR_DIRECTIVE.matcher(beforeCaret);
                        if (!directiveMatcher.matches()) {
                           return LookupElement.EMPTY_ARRAY;
                        } else {
                           String prefix = directiveMatcher.group(1);
                           return (LookupElement[])PreprocessorLanguage.completionDirectives(file).stream().filter((directive) -> directive.name().startsWith(prefix)).map((directive) -> {
                              LookupElementBuilder var10000 = LookupElementBuilder.create(directive.name()).withTypeText(directive.description(), true);
                              String var10001 = directive.name();
                              return withPreprocessorReplacement(var10000, var10001 + (directive.argument() == PreprocessorLanguage.Argument.NONE ? "" : " "), false);
                           }).toArray((x$0) -> new LookupElement[x$0]);
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static String preprocessorPrefix(PsiFile file, String beforeCaret) {
      Matcher caseVersion = CASE_VERSION.matcher(beforeCaret);
      if (caseVersion.matches()) {
         return caseVersion.group(1);
      } else {
         Matcher caseOperator = CASE_OPERATOR.matcher(beforeCaret);
         if (caseOperator.matches()) {
            return caseOperator.group(1);
         } else {
            Matcher caseTail = CASE_TAIL.matcher(beforeCaret);
            if (caseTail.matches()) {
               return caseTail.group(1);
            } else {
               Matcher caseName = CASE_NAME.matcher(beforeCaret);
               if (caseName.matches()) {
                  return caseName.group(1) == null ? "" : caseName.group(1);
               } else {
                  Matcher importMatcher = PREPROCESSOR_IMPORT.matcher(beforeCaret);
                  if (importMatcher.matches()) {
                     return importMatcher.group(1);
                  } else {
                     Matcher versionMatcher = PREPROCESSOR_VERSION.matcher(beforeCaret);
                     if (versionMatcher.matches()) {
                        return versionMatcher.group(1);
                     } else {
                        Matcher operatorMatcher = PREPROCESSOR_OPERATOR.matcher(beforeCaret);
                        if (operatorMatcher.matches()) {
                           return operatorMatcher.group(1);
                        } else {
                           Matcher expressionNameMatcher = PREPROCESSOR_EXPRESSION_NAME.matcher(beforeCaret);
                           if (expressionNameMatcher.matches()) {
                              String prefix = expressionNameMatcher.group(1) == null ? "" : expressionNameMatcher.group(1);
                              LinkedHashSet<String> names = preprocessorNames(file);
                              boolean completeName = names.stream().anyMatch((name) -> name.equalsIgnoreCase(prefix));
                              boolean matchingName = names.stream().anyMatch((name) -> name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT)));
                              if (!completeName && matchingName) {
                                 return prefix;
                              }
                           }

                           Matcher logicalMatcher = PREPROCESSOR_LOGICAL.matcher(beforeCaret);
                           if (logicalMatcher.matches()) {
                              return logicalMatcher.group(1);
                           } else {
                              Matcher replaceTailMatcher = REPLACE_TAIL.matcher(beforeCaret);
                              if (replaceTailMatcher.matches()) {
                                 return replaceTailMatcher.group(1);
                              } else {
                                 Matcher directiveMatcher = PREPROCESSOR_DIRECTIVE.matcher(beforeCaret);
                                 return directiveMatcher.matches() ? directiveMatcher.group(1) : "";
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static LinkedHashSet<String> preprocessorNames(PsiFile file) {
      LinkedHashSet<String> names = new LinkedHashSet();
      names.add("MC");
      names.add("defined()");
      Matcher definitions = PREPROCESSOR_DEFINE.matcher(file.getText());

      while(definitions.find()) {
         names.add(definitions.group(1));
      }

      return names;
   }

   private static List<PreprocessorVersion> preprocessorVersions(PsiFile context) {
      for(VirtualFile directory = context.getVirtualFile() == null ? null : context.getVirtualFile().getParent(); directory != null; directory = directory.getParent()) {
         List<VirtualFile> graphFiles = new ArrayList();

         for(String name : List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) {
            VirtualFile graphFile = directory.findChild(name);
            if (graphFile != null && !graphFile.isDirectory()) {
               graphFiles.add(graphFile);
            }
         }

         if (!graphFiles.isEmpty()) {
            List<PreprocessorVersion> versions = new ArrayList();

            for(VirtualFile buildFile : graphFiles) {
               String text;
               try {
                  PsiFile psiBuildFile = PsiManager.getInstance(context.getProject()).findFile(buildFile);
                  Document document = psiBuildFile == null ? null : PsiDocumentManager.getInstance(context.getProject()).getDocument(psiBuildFile);
                  text = document == null ? VfsUtilCore.loadText(buildFile) : document.getText();
               } catch (IOException var9) {
                  text = "";
               }

               Matcher matcher = PREPROCESSOR_NODE.matcher(text);

               while(matcher.find()) {
                  PreprocessorVersion version = new PreprocessorVersion(matcher.group(1));
                  if (!versions.contains(version)) {
                     versions.add(version);
                  }
               }
            }

            if (!versions.isEmpty()) {
               return versions;
            }
         }
      }

      return List.of();
   }

   private static LookupElement[] caseItems(PsiFile file, String beforeCaret) {
      Matcher version = CASE_VERSION.matcher(beforeCaret);
      if (version.matches()) {
         String prefix = version.group(1);
         return versionItems(preprocessorVersions(file), prefix, (item, value) -> withTrailingTokenReplacement(item, version.start(1), value));
      } else {
         Matcher operator = CASE_OPERATOR.matcher(beforeCaret);
         if (operator.matches()) {
            String prefix = operator.group(1);
            return (LookupElement[])List.of(new PreprocessorOperator("!=", "Not equal to"), new PreprocessorOperator("<", "Less than"), new PreprocessorOperator("<=", "Less than or equal to"), new PreprocessorOperator(">", "Greater than"), new PreprocessorOperator(">=", "Greater than or equal to"), new PreprocessorOperator("==", "Equal to"), new PreprocessorOperator("in ", "Contained in a range or set"), new PreprocessorOperator("not in ", "Not contained in a range or set")).stream().filter((item) -> item.value().startsWith(prefix)).map((item) -> withTrailingTokenReplacement(LookupElementBuilder.create(item.value()).withTypeText(item.description(), true), operator.start(1), item.value())).toArray((x$0) -> new LookupElement[x$0]);
         } else {
            Matcher tail = CASE_TAIL.matcher(beforeCaret);
            if (tail.matches()) {
               String prefix = tail.group(1);
               return (LookupElement[])List.of(new PreprocessorOperator("&&", "Logical AND"), new PreprocessorOperator("||", "Logical OR"), new PreprocessorOperator("?", "Begin the selected source code")).stream().filter((item) -> item.value().startsWith(prefix)).map((item) -> withTrailingTokenReplacement(LookupElementBuilder.create(item.value()).withTypeText(item.description(), true), tail.start(1), item.value() + " ")).toArray((x$0) -> new LookupElement[x$0]);
            } else {
               Matcher name = CASE_NAME.matcher(beforeCaret);
               if (!name.matches()) {
                  return LookupElement.EMPTY_ARRAY;
               } else {
                  String prefix = name.group(1) == null ? "" : name.group(1);
                  LinkedHashSet<String> names = new LinkedHashSet();
                  names.add("MC");
                  names.add("defined()");
                  names.add("else ");
                  Matcher definitions = PREPROCESSOR_DEFINE.matcher(file.getText());

                  while(definitions.find()) {
                     names.add(definitions.group(1));
                  }

                  return (LookupElement[])names.stream().filter((item) -> item.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).map((item) -> withExpressionNameReplacement(LookupElementBuilder.create(item).withTypeText("Case condition", true), item)).toArray((x$0) -> new LookupElement[x$0]);
               }
            }
         }
      }
   }

   private static LookupElement[] preprocessorImportItems(PsiFile file, String prefix) {
      String shortPrefix = prefix.substring(prefix.lastIndexOf(46) + 1);
      if (shortPrefix.length() < 2) {
         return LookupElement.EMPTY_ARRAY;
      } else {
         PsiShortNamesCache cache = PsiShortNamesCache.getInstance(file.getProject());
         GlobalSearchScope scope = GlobalSearchScope.allScope(file.getProject());
         String lower = shortPrefix.toLowerCase(Locale.ROOT);
         List<LookupElement> result = new ArrayList();
         Set<String> added = new HashSet();

         for(String name : cache.getAllClassNames()) {
            if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
               for(PsiClass candidate : cache.getClassesByName(name, scope)) {
                  String qualifiedName = candidate.getQualifiedName();
                  if (qualifiedName != null) {
                     if (!prefix.contains(".")) {
                        if (!name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                           continue;
                        }
                     } else if (!qualifiedName.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                        continue;
                     }

                     if (added.add(qualifiedName)) {
                        result.add(withPreprocessorImportReplacement(LookupElementBuilder.create(candidate, qualifiedName).withPresentableText(name).withTypeText(qualifiedName.substring(0, qualifiedName.length() - name.length() - 1), true), qualifiedName));
                        if (result.size() >= 100) {
                           return (LookupElement[])result.toArray(LookupElement.EMPTY_ARRAY);
                        }
                     }
                  }
               }
            }
         }

         return (LookupElement[])result.toArray(LookupElement.EMPTY_ARRAY);
      }
   }

   private static LookupElement[] versionItems(List<PreprocessorVersion> versions, String prefix, VersionInsertHandler insertHandler) {
      List<LookupElement> items = new ArrayList();

      for(PreprocessorVersion version : versions) {
         if (version.name().startsWith(prefix)) {
            items.add(insertHandler.apply(LookupElementBuilder.create(version.name()).withTypeText("Dotted Minecraft version", true), version.name()));
         }
      }

      return (LookupElement[])items.toArray(LookupElement.EMPTY_ARRAY);
   }

   private static void addMixinMethods(PsiElement position, CompletionResultSet result) {
      addMixinMethods(position, result, (String)null);
   }

   private static void addMixinMethods(PsiElement position, CompletionResultSet result, String replaceAttribute) {
      result.addAllElements(mixinMethodItems(position, replaceAttribute));
   }

   private static List<LookupElement> mixinMethodItems(PsiElement position, String replaceAttribute) {
      PsiClass targetClass = findMixinTarget(position);
      return targetClass == null ? List.of() : mixinMethodItems(targetClass, replaceAttribute);
   }

   private static List<LookupElement> mixinMethodItems(PsiClass targetClass, String replaceAttribute) {
      List<LookupElement> items = new ArrayList();
      Set<String> addedNames = new HashSet();

      for(PsiMethod method : targetClass.getMethods()) {
         String descriptor = JvmDescriptors.methodDescriptor(method);
         if (descriptor != null) {
            String value = method.getName();
            if (addedNames.add(value)) {
               LookupElementBuilder item = LookupElementBuilder.create(value).withIcon(method.getIcon(0)).withPresentableText(method.getName()).withTailText(descriptor, true).withTypeText(targetClass.getName(), true);
               items.add(withCommentReplacement(item, replaceAttribute, value));
            }
         }
      }

      return items;
   }

   private static void addAtTargets(PsiElement position, CompletionResultSet result) {
      PsiClass targetClass = findMixinTarget(position);
      PsiAnnotation injector = findInjectorAnnotation(position);
      if (targetClass != null && injector != null) {
         addAtTargets((PsiClass)targetClass, result, annotationStrings(injector, "method"), (String)null);
      }
   }

   private static void addAtTargets(PsiElement position, CompletionResultSet result, String[] methodNames, String replaceAttribute) {
      PsiClass targetClass = findMixinTarget(position);
      if (targetClass != null) {
         addAtTargets(targetClass, result, methodNames, replaceAttribute);
      }
   }

   private static void addAtTargets(PsiClass targetClass, CompletionResultSet result, String[] methodNames, String replaceAttribute) {
      result.addAllElements(atTargetItems(targetClass, methodNames, replaceAttribute));
   }

   private static List<LookupElement> atTargetItems(PsiClass targetClass, String[] methodNames, String replaceAttribute) {
      Map<String, PsiMethod> calls = new LinkedHashMap();

      for(String methodName : methodNames) {
         String plainName = methodName.contains("(") ? methodName.substring(0, methodName.indexOf(40)) : methodName;

         for(PsiMethod targetMethod : targetClass.findMethodsByName(plainName, false)) {
            for(PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(targetMethod, PsiMethodCallExpression.class)) {
               PsiMethod called = call.resolveMethod();
               if (called != null) {
                  PsiClass ownerClass = called.getContainingClass();
                  String owner = ownerClass == null ? null : JvmDescriptors.owner(ownerClass);
                  String descriptor = JvmDescriptors.methodDescriptor(called);
                  if (owner != null && descriptor != null) {
                     calls.putIfAbsent("L" + owner + ";" + called.getName() + descriptor, called);
                  }
               }
            }
         }
      }

      List<LookupElement> items = new ArrayList();

      for(Map.Entry<String, PsiMethod> entry : calls.entrySet()) {
         PsiMethod method = (PsiMethod)entry.getValue();
         PsiClass owner = method.getContainingClass();
         LookupElementBuilder item = LookupElementBuilder.create((String)entry.getKey()).withIcon(method.getIcon(0)).withPresentableText(method.getName()).withTailText(JvmDescriptors.methodDescriptor(method), true).withTypeText(owner == null ? null : owner.getName(), true).withLookupString(method.getName());
         if (owner != null && owner.getName() != null) {
            String var10001 = owner.getName();
            item = item.withLookupString(var10001 + "." + method.getName());
         }

         items.add(withCommentReplacement(item, replaceAttribute, (String)entry.getKey()));
      }

      for(Map.Entry<String, BytecodeCall> entry : bytecodeCalls(targetClass, methodNames).entrySet()) {
         if (!calls.containsKey(entry.getKey())) {
            BytecodeCall call = (BytecodeCall)entry.getValue();
            LookupElementBuilder var10000 = LookupElementBuilder.create((String)entry.getKey()).withIcon(Nodes.Method).withPresentableText(call.name()).withTailText(call.descriptor(), true).withTypeText(simpleOwner(call.owner()), true).withLookupString(call.name());
            String var29 = simpleOwner(call.owner());
            LookupElementBuilder item = var10000.withLookupString(var29 + "." + call.name());
            items.add(withCommentReplacement(item, replaceAttribute, (String)entry.getKey()));
         }
      }

      return items;
   }

   private static Map<String, BytecodeCall> bytecodeCalls(PsiClass targetClass, final String[] methodNames) {
      final Map<String, BytecodeCall> calls = new LinkedHashMap();
      PsiFile containingFile = targetClass.getContainingFile();
      VirtualFile virtualFile = containingFile == null ? null : containingFile.getVirtualFile();
      if (virtualFile != null && "class".equalsIgnoreCase(virtualFile.getExtension())) {
         try {
            ClassReader reader = new ClassReader(virtualFile.contentsToByteArray());
            reader.accept(new ClassVisitor(589824) {
               public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                  return !MixinDescriptorCompletionContributor.matchesMethod(methodNames, name, descriptor) ? null : new MethodVisitor(589824) {
                     public void visitMethodInsn(int opcode, String owner, String invokedName, String invokedDescriptor, boolean isInterface) {
                        String value = "L" + owner + ";" + invokedName + invokedDescriptor;
                        calls.putIfAbsent(value, new BytecodeCall(owner, invokedName, invokedDescriptor));
                     }
                  };
               }
            }, 6);
            return calls;
         } catch (RuntimeException | IOException var6) {
            return Map.of();
         }
      } else {
         return calls;
      }
   }

   private static boolean matchesMethod(String[] selectors, String name, String descriptor) {
      for(String selector : selectors) {
         int descriptorStart = selector.indexOf(40);
         String selectedName = descriptorStart < 0 ? selector : selector.substring(0, descriptorStart);
         if (selectedName.equals(name) && (descriptorStart < 0 || selector.substring(descriptorStart).equals(descriptor))) {
            return true;
         }
      }

      return false;
   }

   private static String simpleOwner(String owner) {
      int slash = owner.lastIndexOf(47);
      return slash < 0 ? owner : owner.substring(slash + 1);
   }

   static LookupElement[] commentItems(PsiFile file, Document document, int offset) {
      if (!MixinCommentContext.isCompletionPosition(document, offset)) {
         return LookupElement.EMPTY_ARRAY;
      } else {
         int safeOffset = Math.max(0, Math.min(offset - 1, file.getTextLength() - 1));
         PsiElement position = file.findElementAt(safeOffset);
         if (position == null) {
            return LookupElement.EMPTY_ARRAY;
         } else {
            int line = document.getLineNumber(offset);
            document.getLineStartOffset(line);
            String beforeCaret = MixinCommentContext.javaCodeBeforeCaret(document, offset);
            if (beforeCaret == null) {
               return LookupElement.EMPTY_ARRAY;
            } else {
               String logicalContext = MixinCommentContext.logicalContext(document, offset);
               Matcher matcher = COMMENT_ATTRIBUTE.matcher(beforeCaret);
               Matcher annotationMatcher = COMMENT_ANNOTATION.matcher(beforeCaret);
               boolean attributePosition = matcher.find();
               Matcher atValueMatcher = COMMENT_AT_VALUE.matcher(logicalContext);
               boolean atValuePosition = !attributePosition && atValueMatcher.find();
               boolean annotationPosition = !attributePosition && !atValuePosition && annotationMatcher.find();
               Matcher keywordMatcher = COMMENT_KEYWORD.matcher(beforeCaret);
               boolean keywordPosition = !attributePosition && !atValuePosition && !annotationPosition && keywordMatcher.find();
               Matcher parametersMatcher = COMMENT_METHOD_PARAMETERS.matcher(beforeCaret);
               boolean parametersPosition = !attributePosition && !atValuePosition && !annotationPosition && !keywordPosition && parametersMatcher.find();
               Matcher annotationAttributeMatcher = COMMENT_ANNOTATION_ATTRIBUTE.matcher(beforeCaret);
               MixinCommentContext.AnnotationAttribute annotationAttribute = MixinCommentContext.annotationAttribute(logicalContext);
               boolean annotationAttributePosition = !attributePosition && !atValuePosition && !annotationPosition && !keywordPosition && !parametersPosition && annotationAttribute != null;
               Matcher identifierMatcher = COMMENT_IDENTIFIER.matcher(beforeCaret);
               Matcher memberMatcher = COMMENT_MEMBER.matcher(beforeCaret);
               boolean memberPosition = !attributePosition && !atValuePosition && !annotationPosition && !keywordPosition && !parametersPosition && !annotationAttributePosition && memberMatcher.find();
               boolean identifierPosition = !attributePosition && !atValuePosition && !annotationPosition && !keywordPosition && !parametersPosition && !annotationAttributePosition && !memberPosition && identifierMatcher.find();
               if (!attributePosition && !atValuePosition && !annotationPosition && !keywordPosition && !parametersPosition && !annotationAttributePosition && !memberPosition && !identifierPosition) {
                  return LookupElement.EMPTY_ARRAY;
               } else if (annotationAttributePosition) {
                  return (LookupElement[])annotationAttributeItems(position, document, line, annotationAttribute.annotation(), annotationAttribute.prefix()).toArray(LookupElement.EMPTY_ARRAY);
               } else if (parametersPosition) {
                  return (LookupElement[])injectParameterItems(file, document, line, position, currentParameterPrefix(parametersMatcher.group(1))).toArray(LookupElement.EMPTY_ARRAY);
               } else if (atValuePosition) {
                  return (LookupElement[])injectionPointItems(position, document, line).toArray(LookupElement.EMPTY_ARRAY);
               } else if (keywordPosition) {
                  String prefix = keywordMatcher.group(1);
                  DeclarationNameContext declaration = declarationNameContext(beforeCaret);
                  if (declaration != null) {
                     return (LookupElement[])variableNameItems(declaration, prefix).toArray(LookupElement.EMPTY_ARRAY);
                  } else {
                     List<LookupElement> items = new ArrayList(keywordItems(prefix, hasPreviousDeclarationWord(beforeCaret)));
                     PsiClass currentTarget = findMixinTarget(position);
                     if (currentTarget != null) {
                        PsiClass targetClass = VersionedModuleResolver.retarget(currentTarget, file.getProject(), document, line);
                        Module selectedModule = VersionedModuleResolver.resolveModule(file.getProject(), document, line);
                        items.addAll(classItems(targetClass, selectedModule, prefix));
                     }

                     items.addAll(localVariableItems(document, line, offset, prefix));
                     items.addAll(expressionKeywordItems(prefix, document, line));
                     return (LookupElement[])items.toArray(LookupElement.EMPTY_ARRAY);
                  }
               } else {
                  PsiClass currentTarget = findMixinTarget(position);
                  if (currentTarget == null) {
                     return LookupElement.EMPTY_ARRAY;
                  } else {
                     PsiClass targetClass = VersionedModuleResolver.retarget(currentTarget, file.getProject(), document, line);
                     Module selectedModule = VersionedModuleResolver.resolveModule(file.getProject(), document, line);
                     if (memberPosition) {
                        return (LookupElement[])memberItems(targetClass, selectedModule, document, line, memberMatcher.group(1), memberMatcher.group(2), position, beforeCaret).toArray(LookupElement.EMPTY_ARRAY);
                     } else if (identifierPosition) {
                        String prefix = identifierMatcher.group(1);
                        List<LookupElement> items = new ArrayList(classItems(targetClass, selectedModule, prefix));
                        items.addAll(localVariableItems(document, line, offset, prefix));
                        items.addAll(expressionKeywordItems(prefix, document, line));
                        return (LookupElement[])items.toArray(LookupElement.EMPTY_ARRAY);
                     } else if (annotationPosition) {
                        return (LookupElement[])annotationItems(targetClass, selectedModule, annotationMatcher.group(1)).toArray(LookupElement.EMPTY_ARRAY);
                     } else {
                        List<LookupElement> items;
                        if ("method".equals(matcher.group(1))) {
                           items = mixinMethodItems(targetClass, "method");
                        } else {
                           String method = findCommentMethod(document, line);
                           items = method == null ? List.of() : atTargetItems(targetClass, new String[]{method}, "target");
                        }

                        return (LookupElement[])items.toArray(LookupElement.EMPTY_ARRAY);
                     }
                  }
               }
            }
         }
      }
   }

   private static List<LookupElement> keywordItems(String prefix, boolean afterModifier) {
      String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
      List<String> candidates = afterModifier ? JAVA_DECLARATION_KEYWORDS : JAVA_INITIAL_MODIFIERS;
      Stream var10000 = candidates.stream().filter((keyword) -> keyword.startsWith(lowerPrefix)).map((keyword) -> withKeywordReplacement(LookupElementBuilder.create(keyword + " ").withPresentableText(keyword).bold(), keyword));
      Objects.requireNonNull(LookupElement.class);
      return var10000.map(LookupElement.class::cast).toList();
   }

   private static List<LookupElement> expressionKeywordItems(String prefix, Document document, int line) {
      String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
      List<LookupElement> items = new ArrayList();
      String returnType = enclosingCommentMethodReturnType(document, line);
      if (returnType != null && "return".startsWith(lowerPrefix)) {
         boolean voidMethod = "void".equals(returnType);
         String value = "return;";
         int caretOffset = voidMethod ? value.length() : "return".length();
         items.add(withStatementKeywordReplacement(LookupElementBuilder.create("return").withTypeText(voidMethod ? "Return from void method" : "Return a value", true).bold(), value, caretOffset));
      }

      for(String keyword : JAVA_EXPRESSION_KEYWORDS) {
         if (keyword.startsWith(lowerPrefix)) {
            LookupElementBuilder item = LookupElementBuilder.create(keyword).bold();
            items.add("new".equals(keyword) ? withKeywordReplacement(item, keyword) : withIdentifierReplacement(item, keyword));
         }
      }

      return items;
   }

   private static String enclosingCommentMethodReturnType(Document document, int currentLine) {
      int firstLine = Math.max(0, currentLine - 200);
      StringBuilder source = new StringBuilder();

      for(int line = firstLine; line < currentLine; ++line) {
         int start = document.getLineStartOffset(line);
         int end = document.getLineEndOffset(line);
         String code = commentJavaLine(document.getImmutableCharSequence().subSequence(start, end).toString());
         if (code != null) {
            source.append(code).append('\n');
         }
      }

      Matcher matcher = COMMENT_METHOD_DECLARATION.matcher(source);
      String returnType = null;

      while(matcher.find()) {
         int openingBrace = matcher.end() - 1;
         if (matchingClosingBrace(source, openingBrace) < 0) {
            returnType = matcher.group(1).replaceAll("\\s+", "");
         }
      }

      return returnType;
   }

   private static int matchingClosingBrace(CharSequence text, int openingBrace) {
      int depth = 0;
      char quote = 0;
      boolean lineComment = false;
      boolean blockComment = false;

      for(int index = openingBrace; index < text.length(); ++index) {
         char value = text.charAt(index);
         char next = index + 1 < text.length() ? text.charAt(index + 1) : 0;
         if (lineComment) {
            if (value == '\n' || value == '\r') {
               lineComment = false;
            }
         } else if (blockComment) {
            if (value == '*' && next == '/') {
               blockComment = false;
               ++index;
            }
         } else if (quote != 0) {
            if (value == quote && (index == 0 || text.charAt(index - 1) != '\\')) {
               quote = 0;
            }
         } else if (value == '/' && next == '/') {
            lineComment = true;
            ++index;
         } else if (value == '/' && next == '*') {
            blockComment = true;
            ++index;
         } else if (value != '"' && value != '\'') {
            if (value == '{') {
               ++depth;
            } else if (value == '}') {
               --depth;
               if (depth == 0) {
                  return index;
               }
            }
         } else {
            quote = value;
         }
      }

      return -1;
   }

   private static String commentJavaLine(String line) {
      String code = line.stripLeading();

      boolean marker;
      for(marker = false; code.startsWith("//$$"); code = code.substring(4).stripLeading()) {
         marker = true;
      }

      return marker ? code : null;
   }

   private static DeclarationNameContext declarationNameContext(String beforeCaret) {
      int marker = beforeCaret.indexOf("//$$");
      if (marker < 0) {
         return null;
      } else {
         String body = beforeCaret.substring(marker + 4).stripLeading();
         Matcher matcher = COMMENT_DECLARATION_NAME.matcher(body);
         if (!matcher.matches()) {
            return null;
         } else {
            String type = matcher.group(1).replaceAll("\\s+", "");
            String simpleType = simpleTypeName(type);
            return !isDeclarationType(simpleType) ? null : new DeclarationNameContext(simpleType);
         }
      }
   }

   private static boolean isDeclarationType(String simpleType) {
      if (simpleType.isEmpty()) {
         return false;
      } else {
         boolean var10000;
         switch (simpleType) {
            case "boolean":
            case "byte":
            case "char":
            case "double":
            case "float":
            case "int":
            case "long":
            case "short":
            case "var":
            case "void":
               var10000 = true;
               break;
            default:
               var10000 = Character.isUpperCase(simpleType.charAt(0));
         }

         return var10000;
      }
   }

   private static String simpleTypeName(String type) {
      String value = type.replace("[]", "");
      int generic = value.indexOf(60);
      if (generic >= 0) {
         value = value.substring(0, generic);
      }

      int dot = value.lastIndexOf(46);
      return dot < 0 ? value : value.substring(dot + 1);
   }

   private static List<LookupElement> variableNameItems(DeclarationNameContext declaration, String prefix) {
      if ("void".equals(declaration.simpleType())) {
         return List.of();
      } else {
         String[] words = declaration.simpleType().split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
         LinkedHashSet<String> names = new LinkedHashSet();
         names.add(lowerCamel(words, 0, words.length));

         for(int end = words.length - 1; end > 0; --end) {
            names.add(lowerCamel(words, 0, end));
         }

         for(int start = Math.max(0, words.length - 2); start < words.length; ++start) {
            names.add(lowerCamel(words, start, start + 1));
         }

         for(int start = 1; start < words.length; ++start) {
            names.add(lowerCamel(words, start, words.length));
         }

         String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
         Stream var10000 = names.stream().filter((name) -> !name.isEmpty() && name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)).sorted(Comparator.comparingInt(String::length)).map((name) -> withIdentifierReplacement(LookupElementBuilder.create(name).withIcon(Nodes.Variable).withTypeText(declaration.simpleType(), true), name));
         Objects.requireNonNull(LookupElement.class);
         return var10000.map(LookupElement.class::cast).toList();
      }
   }

   private static String lowerCamel(String[] words, int start, int end) {
      if (start >= end) {
         return "";
      } else {
         StringBuilder result = new StringBuilder();

         for(int index = start; index < end; ++index) {
            String word = words[index];
            if (!word.isEmpty()) {
               if (result.isEmpty()) {
                  result.append(Character.toLowerCase(word.charAt(0))).append(word.substring(1));
               } else {
                  result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
               }
            }
         }

         return result.toString();
      }
   }

   private static List<LookupElement> localVariableItems(Document document, int currentLine, int offset, String prefix) {
      String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
      Map<String, String> variables = new LinkedHashMap();

      for(int candidateLine = currentLine; candidateLine >= Math.max(0, currentLine - 100); --candidateLine) {
         int start = document.getLineStartOffset(candidateLine);
         int end = candidateLine == currentLine ? Math.min(offset, document.getLineEndOffset(candidateLine)) : document.getLineEndOffset(candidateLine);
         String text = document.getImmutableCharSequence().subSequence(start, end).toString();
         String stripped = text.stripLeading();
         if (!stripped.startsWith("//$$")) {
            break;
         }

         if (candidateLine != currentLine) {
            String body = stripped.substring(4);
            Matcher matcher = LOCAL_VARIABLE_DECLARATION.matcher(body);

            while(matcher.find()) {
               String type = matcher.group(1).replaceAll("\\s+", "");
               String simpleType = simpleTypeName(type);
               String name = matcher.group(2);
               if (isDeclarationType(simpleType) && !"void".equals(simpleType)) {
                  int after;
                  for(after = matcher.end(2); after < body.length() && Character.isWhitespace(body.charAt(after)); ++after) {
                  }

                  if (after >= body.length() || body.charAt(after) != '(') {
                     variables.putIfAbsent(name, simpleType);
                  }
               }
            }
         }
      }

      List<LookupElement> items = new ArrayList();

      for(Map.Entry<String, String> entry : variables.entrySet()) {
         String name = (String)entry.getKey();
         if (name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
            items.add(withIdentifierReplacement(LookupElementBuilder.create(name).withIcon(Nodes.Variable).withTypeText((String)entry.getValue(), true), name));
         }
      }

      return items;
   }

   private static LookupElementBuilder withKeywordReplacement(LookupElementBuilder item, String keyword) {
      return item.withInsertHandler((context, ignored) -> {
         Document document = context.getDocument();
         int end = Math.min(context.getTailOffset(), document.getTextLength());

         int start;
         for(start = end; start > 0 && Character.isWhitespace(document.getCharsSequence().charAt(start - 1)); --start) {
         }

         while(start > 0 && Character.isJavaIdentifierPart(document.getCharsSequence().charAt(start - 1))) {
            --start;
         }

         document.replaceString(start, end, keyword + " ");
         context.getEditor().getCaretModel().moveToOffset(start + keyword.length() + 1);
      });
   }

   private static LookupElementBuilder withStatementKeywordReplacement(LookupElementBuilder item, String value, int caretOffset) {
      return item.withInsertHandler((context, ignored) -> {
         Document document = context.getDocument();
         int end = Math.min(context.getTailOffset(), document.getTextLength());

         int start;
         for(start = end; start > 0 && Character.isJavaIdentifierPart(document.getCharsSequence().charAt(start - 1)); --start) {
         }

         document.replaceString(start, end, value);
         context.getEditor().getCaretModel().moveToOffset(start + caretOffset);
      });
   }

   private static List<LookupElement> annotationAttributeItems(PsiElement position, Document document, int line, String annotation, String prefix) {
      String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
      Module module = VersionedModuleResolver.resolveModule(position.getProject(), document, line);
      if (module == null) {
         module = ModuleUtilCore.findModuleForPsiElement(position);
      }

      GlobalSearchScope scope = module == null ? position.getResolveScope() : GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, false);
      Stream var10000 = MixinMetadataResolver.annotationAttributes(position, annotation, scope).stream().filter((attribute) -> attribute.getName().toLowerCase(Locale.ROOT).startsWith(lowerPrefix)).map((attribute) -> LookupElementBuilder.create(attribute.getName() + " = ").withPresentableText(attribute.getName()).withIcon(attribute.getIcon(0)).withTypeText(annotation, true));
      Objects.requireNonNull(LookupElement.class);
      return var10000.map(LookupElement.class::cast).toList();
   }

   private static boolean hasPreviousDeclarationWord(String beforeCaret) {
      int marker = beforeCaret.indexOf("//$$");
      if (marker < 0) {
         return false;
      } else {
         String body = beforeCaret.substring(marker + 4).stripLeading();
         return body.indexOf(32) >= 0 || body.indexOf(9) >= 0;
      }
   }

   private static String currentParameterPrefix(String parameters) {
      int separator = Math.max(parameters.lastIndexOf(44), parameters.lastIndexOf(32));
      return parameters.substring(separator + 1).strip();
   }

   private static List<LookupElement> injectParameterItems(PsiFile file, Document document, int line, PsiElement position, String prefix) {
      if (!hasCommentInject(document, line)) {
         return List.of();
      } else {
         String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
         List<LookupElement> items = new ArrayList();
         if ("callbackinfo".startsWith(lowerPrefix)) {
            String fallback = "CallbackInfo ci";
            items.add(withMethodParametersReplacement(LookupElementBuilder.create(fallback).withIcon(Nodes.Parameter).withPresentableText(fallback).withTypeText("@Inject", true), fallback));
         }

         String methodSelector = findCommentMethod(document, line);
         if (methodSelector == null) {
            return items;
         } else {
            PsiClass currentTarget = findMixinTarget(position);
            if (currentTarget == null) {
               return items;
            } else {
               PsiClass targetClass = VersionedModuleResolver.retarget(currentTarget, file.getProject(), document, line);
               String methodName = methodSelector.contains("(") ? methodSelector.substring(0, methodSelector.indexOf(40)) : methodSelector;

               for(PsiMethod method : targetClass.findMethodsByName(methodName, false)) {
                  String signature = injectParameters(method);
                  if (signature.toLowerCase(Locale.ROOT).startsWith(lowerPrefix) || "callbackinfo".startsWith(lowerPrefix)) {
                     LookupElementBuilder item = LookupElementBuilder.create(signature).withIcon(method.getIcon(0)).withPresentableText(signature).withTypeText(targetClass.getName(), true);
                     if (!"CallbackInfo ci".equals(signature)) {
                        items.add(withMethodParametersReplacement(item, signature));
                     }
                  }
               }

               return items;
            }
         }
      }
   }

   private static String injectParameters(PsiMethod method) {
      List<String> parameters = new ArrayList();

      for(PsiParameter parameter : method.getParameterList().getParameters()) {
         String var10001 = parameter.getType().getPresentableText();
         parameters.add(var10001 + " " + parameter.getName());
      }

      PsiType returnType = method.getReturnType();
      if (!PsiType.VOID.equals(returnType) && returnType != null) {
         parameters.add("CallbackInfoReturnable<" + returnType.getPresentableText() + "> cir");
      } else {
         parameters.add("CallbackInfo ci");
      }

      return String.join(", ", parameters);
   }

   private static boolean hasCommentInject(Document document, int currentLine) {
      int firstLine = Math.max(0, currentLine - 8);

      for(int line = currentLine - 1; line >= firstLine; --line) {
         String text = document.getImmutableCharSequence().subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString();
         if (!text.stripLeading().startsWith("//$$")) {
            break;
         }

         if (text.contains("@Inject")) {
            return true;
         }
      }

      return false;
   }

   private static LookupElementBuilder withMethodParametersReplacement(LookupElementBuilder item, String signature) {
      return item.withInsertHandler((context, ignored) -> {
         Document document = context.getDocument();
         int offset = Math.min(context.getTailOffset(), document.getTextLength());
         int line = document.getLineNumber(offset);
         int lineStart = document.getLineStartOffset(line);
         int lineEnd = document.getLineEndOffset(line);
         String text = document.getImmutableCharSequence().subSequence(lineStart, lineEnd).toString();
         int opening = text.lastIndexOf(40, Math.max(0, offset - lineStart - 1));
         if (opening >= 0) {
            int closing = text.indexOf(41, opening + 1);
            if (closing < 0) {
               closing = Math.min(text.length(), offset - lineStart);
            }

            document.replaceString(lineStart + opening + 1, lineStart + closing, signature);
            context.getEditor().getCaretModel().moveToOffset(lineStart + opening + 1 + signature.length());
         }
      });
   }

   private static List<LookupElement> injectionPointItems(PsiElement position, Document document, int line) {
      Module module = VersionedModuleResolver.resolveModule(position.getProject(), document, line);
      if (module == null) {
         module = ModuleUtilCore.findModuleForPsiElement(position);
      }

      GlobalSearchScope scope = module == null ? position.getResolveScope() : GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, false);
      Stream var10000 = MixinMetadataResolver.injectionPoints(position.getProject(), scope).stream().map((point) -> withCurrentStringReplacement(LookupElementBuilder.create(point.code()).withIcon(point.implementation().getIcon(0)).withTypeText(point.implementation().getName(), true), point.code()));
      Objects.requireNonNull(LookupElement.class);
      return var10000.map(LookupElement.class::cast).toList();
   }

   private static LookupElementBuilder withCurrentStringReplacement(LookupElementBuilder item, String value) {
      return item.withInsertHandler((context, ignored) -> {
         Document document = context.getDocument();
         int offset = Math.min(context.getTailOffset(), document.getTextLength());
         int line = document.getLineNumber(offset);
         int lineStart = document.getLineStartOffset(line);
         int lineEnd = document.getLineEndOffset(line);
         String text = document.getImmutableCharSequence().subSequence(lineStart, lineEnd).toString();
         int caretInLine = Math.min(offset - lineStart, text.length());
         int openingQuote = text.lastIndexOf(34, Math.max(0, caretInLine - 1));
         if (openingQuote >= 0) {
            int closingQuote = text.indexOf(34, openingQuote + 1);
            if (closingQuote < 0) {
               closingQuote = caretInLine;
            }

            document.replaceString(lineStart + openingQuote + 1, lineStart + closingQuote, value);
            context.getEditor().getCaretModel().moveToOffset(lineStart + openingQuote + 1 + value.length());
         }
      });
   }

   private static List<LookupElement> annotationItems(PsiClass versionTarget, Module selectedModule, String prefix) {
      Module module = selectedModule != null ? selectedModule : ModuleUtilCore.findModuleForPsiElement(versionTarget);
      GlobalSearchScope scope = module == null ? versionTarget.getResolveScope() : GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, false);
      PsiShortNamesCache cache = PsiShortNamesCache.getInstance(versionTarget.getProject());
      List<LookupElement> items = new ArrayList();
      String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
      Set<String> added = new HashSet();

      for(String preferred : PREFERRED_ANNOTATIONS) {
         if (preferred.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
            addAnnotationClasses(cache, scope, preferred, items, added);
         }
      }

      if (lowerPrefix.length() < 2) {
         return items;
      } else {
         for(String name : cache.getAllClassNames()) {
            ProgressManager.checkCanceled();
            if (name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
               for(PsiClass candidate : cache.getClassesByName(name, scope)) {
                  if (candidate.isAnnotationType()) {
                     String qualifiedName = candidate.getQualifiedName();
                     String key = qualifiedName == null ? name : qualifiedName;
                     if (added.add(key)) {
                        String packageName = qualifiedName != null && qualifiedName.contains(".") ? qualifiedName.substring(0, qualifiedName.lastIndexOf(46)) : "";
                        LookupElementBuilder item = LookupElementBuilder.create(candidate, name).withIcon(candidate.getIcon(0)).withTypeText(packageName, true);
                        items.add(withAnnotationReplacement(item, name));
                        if (items.size() >= 100) {
                           return items;
                        }
                     }
                  }
               }
            }
         }

         return items;
      }
   }

   private static void addAnnotationClasses(PsiShortNamesCache cache, GlobalSearchScope scope, String name, List<LookupElement> items, Set<String> added) {
      for(PsiClass candidate : cache.getClassesByName(name, scope)) {
         if (candidate.isAnnotationType()) {
            String qualifiedName = candidate.getQualifiedName();
            String key = qualifiedName == null ? name : qualifiedName;
            if (added.add(key)) {
               String packageName = qualifiedName != null && qualifiedName.contains(".") ? qualifiedName.substring(0, qualifiedName.lastIndexOf(46)) : "";
               items.add(withAnnotationReplacement(LookupElementBuilder.create(candidate, name).withIcon(candidate.getIcon(0)).withTypeText(packageName, true), name));
            }
         }
      }

   }

   private static List<LookupElement> classItems(PsiClass versionTarget, Module selectedModule, String prefix) {
      Module module = selectedModule != null ? selectedModule : ModuleUtilCore.findModuleForPsiElement(versionTarget);
      GlobalSearchScope scope = module == null ? versionTarget.getResolveScope() : GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, false);
      PsiShortNamesCache cache = PsiShortNamesCache.getInstance(versionTarget.getProject());
      List<LookupElement> items = new ArrayList();
      String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
      Set<String> added = new HashSet();
      if (module != null) {
         addVersionSourceClasses(versionTarget, module, lowerPrefix, items, added);
      }

      addClassItems(cache, scope, lowerPrefix, items, added);
      if (module == null && items.size() < 100) {
         addClassItems(cache, GlobalSearchScope.projectScope(versionTarget.getProject()), lowerPrefix, items, added);
      }

      if (module == null && items.size() < 100) {
         addClassItems(cache, GlobalSearchScope.allScope(versionTarget.getProject()), lowerPrefix, items, added);
      }

      if (module == null && items.size() < 100) {
         addRootSourceClasses(versionTarget, lowerPrefix, items, added);
      }

      return items;
   }

   private static void addClassItems(PsiShortNamesCache cache, GlobalSearchScope scope, String lowerPrefix, List<LookupElement> items, Set<String> added) {
      for (String name : cache.getAllClassNames()) {
         if (!name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
            continue;
         }

         for (PsiClass candidate : cache.getClassesByName(name, scope)) {
            String qualifiedName = candidate.getQualifiedName();
            String key = qualifiedName == null ? name : qualifiedName;
            if (!added.add(key)) {
               continue;
            }

            String packageName = qualifiedName == null || !qualifiedName.contains(".") ? "" : qualifiedName.substring(0, qualifiedName.lastIndexOf(46));
            items.add(withIdentifierReplacement(LookupElementBuilder.create(candidate, name).withIcon(candidate.getIcon(0)).withTypeText(packageName, true), name));
            if (items.size() >= 100) {
               return;
            }
         }
      }

   }

   private static void addVersionSourceClasses(PsiClass versionTarget, Module module, String lowerPrefix, List<LookupElement> items, Set<String> added) {
      String version = moduleVersion(module);
      VirtualFile base = versionTarget.getProject().getBaseDir();
      if (version != null && base != null) {
         addClassesBelow(versionTarget, base.findFileByRelativePath("versions/" + version + "/build/preprocessed/main/java"), lowerPrefix, items, added);
         addClassesBelow(versionTarget, base.findFileByRelativePath("versions/" + version + "/src/main/java"), lowerPrefix, items, added);
      }
   }

   private static void addRootSourceClasses(PsiClass versionTarget, String lowerPrefix, List<LookupElement> items, Set<String> added) {
      VirtualFile base = versionTarget.getProject().getBaseDir();
      VirtualFile sourceRoot = base == null ? null : base.findFileByRelativePath("src/main/java");
      addClassesBelow(versionTarget, sourceRoot, lowerPrefix, items, added);
   }

   private static void addClassesBelow(PsiClass versionTarget, VirtualFile sourceRoot, String lowerPrefix, List<LookupElement> items, Set<String> added) {
      if (sourceRoot != null && items.size() < 100) {
         PsiManager psiManager = PsiManager.getInstance(versionTarget.getProject());
         VfsUtilCore.iterateChildrenRecursively(sourceRoot, (VirtualFileFilter)null, (file) -> {
            if (items.size() >= 100) {
               return false;
            } else if (!file.isDirectory() && "java".equalsIgnoreCase(file.getExtension()) && file.getNameWithoutExtension().toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
               PsiFile psiFile = psiManager.findFile(file);
               if (!(psiFile instanceof PsiJavaFile)) {
                  return true;
               } else {
                  PsiJavaFile javaFile = (PsiJavaFile)psiFile;

                  for(PsiClass candidate : javaFile.getClasses()) {
                     String name = candidate.getName();
                     if (name != null && name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                        String qualifiedName = candidate.getQualifiedName();
                        String key = qualifiedName == null ? name : qualifiedName;
                        if (added.add(key)) {
                           String packageName = qualifiedName != null && qualifiedName.contains(".") ? qualifiedName.substring(0, qualifiedName.lastIndexOf(46)) : "";
                           items.add(withIdentifierReplacement(LookupElementBuilder.create(candidate, name).withIcon(candidate.getIcon(0)).withTypeText(packageName, true), name));
                        }
                     }
                  }

                  return true;
               }
            } else {
               return true;
            }
         });
      }
   }

   
   private static List<LookupElement> memberItems(PsiClass versionTarget, Module selectedModule, Document document, int line, String qualifier, String prefix, PsiElement position, String beforeCaret) {
      Module module = selectedModule != null ? selectedModule : ModuleUtilCore.findModuleForPsiElement(versionTarget);
      GlobalSearchScope scope = module == null ? versionTarget.getResolveScope() : GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, false);
      PsiClass type = resolveQualifierType(versionTarget, module, document, line, qualifier, scope);
      if (type == null) {
         return List.of();
      } else {
         String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
         List<LookupElement> items = new ArrayList();
         Set<String> added = new HashSet();
         boolean terminateStatement = completesJavaStatement(position, beforeCaret, qualifier, prefix, "__completion__()");
         boolean bareMemberStatement = isBareMemberStatement(beforeCaret, qualifier, prefix);

         for(PsiMethod method : type.getAllMethods()) {
            String name = method.getName();
            if (name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix) && added.add("M:" + name)) {
               String value = name + "()";
               items.add(withMemberReplacement(LookupElementBuilder.create(value).withIcon(method.getIcon(0)).withPresentableText(name + "()").withTypeText(method.getReturnType() == null ? null : method.getReturnType().getPresentableText(), true), value, terminateStatement || bareMemberStatement, method.getParameterList().getParametersCount() > 0));
            }
         }

         for(PsiField field : type.getAllFields()) {
            String name = field.getName();
            if (name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix) && added.add("F:" + name)) {
               items.add(withMemberReplacement(LookupElementBuilder.create(name).withIcon(field.getIcon(0)).withTypeText(field.getType().getPresentableText(), true), name, false, false));
            }
         }

         return items;
      }
   }

   static PsiClass resolveQualifierType(PsiClass versionTarget, Module selectedModule, Document document, int line, String qualifier, GlobalSearchScope scope) {
      PsiShortNamesCache cache = PsiShortNamesCache.getInstance(versionTarget.getProject());
      Module preferredModule = selectedModule != null ? selectedModule : ModuleUtilCore.findModuleForPsiElement(versionTarget);
      if (Character.isUpperCase(qualifier.charAt(0))) {
         PsiClass selected = findVersionSourceClass(versionTarget, preferredModule, qualifier);
         if (selected != null) {
            return selected;
         }

         PsiClass[] classes = cache.getClassesByName(qualifier, scope);
         selected = preferModuleClass(classes, preferredModule);
         if (selected == null && preferredModule == null) {
            selected = preferModuleClass(cache.getClassesByName(qualifier, GlobalSearchScope.projectScope(versionTarget.getProject())), preferredModule);
         }

         if (selected == null && preferredModule == null) {
            selected = preferModuleClass(cache.getClassesByName(qualifier, GlobalSearchScope.allScope(versionTarget.getProject())), preferredModule);
         }

         if (selected != null) {
            return selected;
         }
      }

      Pattern declaration = Pattern.compile("\\b([A-Z][A-Za-z0-9_$.]*)(?:\\s*<[^;=(){}]+>)?(?:\\s*\\[\\])*\\s+" + Pattern.quote(qualifier) + "\\b");

      for(int candidateLine = line; candidateLine >= Math.max(0, line - 50); --candidateLine) {
         String text = document.getImmutableCharSequence().subSequence(document.getLineStartOffset(candidateLine), document.getLineEndOffset(candidateLine)).toString();
         Matcher matcher = declaration.matcher(text);
         if (matcher.find()) {
            String typeName = matcher.group(1);
            int dot = typeName.lastIndexOf(46);
            String shortName = dot < 0 ? typeName : typeName.substring(dot + 1);
            PsiClass[] classes = cache.getClassesByName(shortName, scope);
            PsiClass selected = preferModuleClass(classes, preferredModule);
            if (selected != null) {
               return selected;
            }
         }
      }

      if ("this".equals(qualifier)) {
         return versionTarget;
      } else if ("super".equals(qualifier)) {
         return versionTarget.getSuperClass();
      } else {
         return null;
      }
   }

   private static PsiClass preferModuleClass(PsiClass[] classes, Module preferredModule) {
      if (preferredModule != null) {
         for(PsiClass candidate : classes) {
            if (preferredModule.equals(ModuleUtilCore.findModuleForPsiElement(candidate))) {
               return candidate;
            }
         }
      }

      if (preferredModule != null) {
         Matcher version = Pattern.compile("([0-9]+(?:\\.[0-9]+){1,2})\\.main$").matcher(preferredModule.getName());
         if (version.find()) {
            String marker = "/versions/" + version.group(1) + "/build/";

            for(PsiClass candidate : classes) {
               PsiFile file = candidate.getContainingFile();
               VirtualFile virtualFile = file == null ? null : file.getVirtualFile();
               if (virtualFile != null && virtualFile.getPath().replace('\\', '/').contains(marker)) {
                  return candidate;
               }
            }
         }
      }

      return classes.length == 0 ? null : classes[0];
   }

   static PsiClass findVersionSourceClass(PsiClass versionTarget, Module module, String className) {
      String version = moduleVersion(module);
      if (version == null) {
         return null;
      } else {
         VirtualFile base = versionTarget.getProject().getBaseDir();
         if (base == null) {
            return null;
         } else {
            for(String relativeRoot : List.of("versions/" + version + "/build/preprocessed/main/java", "versions/" + version + "/src/main/java")) {
               PsiClass found = findClassBelow(versionTarget, base.findFileByRelativePath(relativeRoot), className);
               if (found != null) {
                  return found;
               }
            }

            return null;
         }
      }
   }

   private static PsiClass findClassBelow(PsiClass versionTarget, VirtualFile root, String className) {
      if (root == null) {
         return null;
      } else {
         PsiClass[] found = new PsiClass[]{null};
         PsiManager psiManager = PsiManager.getInstance(versionTarget.getProject());
         VfsUtilCore.iterateChildrenRecursively(root, (VirtualFileFilter)null, (file) -> {
            if (found[0] != null) {
               return false;
            } else if (!file.isDirectory() && file.getName().equals(className + ".java")) {
               PsiFile psiFile = psiManager.findFile(file);
               if (psiFile instanceof PsiJavaFile) {
                  PsiJavaFile javaFile = (PsiJavaFile)psiFile;

                  for(PsiClass candidate : javaFile.getClasses()) {
                     if (className.equals(candidate.getName())) {
                        found[0] = candidate;
                        return false;
                     }
                  }
               }

               return true;
            } else {
               return true;
            }
         });
         return found[0];
      }
   }

   private static String moduleVersion(Module module) {
      if (module == null) {
         return null;
      } else {
         Matcher matcher = Pattern.compile("([0-9]+(?:\\.[0-9]+){1,2})\\.main$").matcher(module.getName());
         return matcher.find() ? matcher.group(1) : null;
      }
   }

   private static boolean completesJavaStatement(PsiElement position, String beforeCaret, String qualifier, String prefix, String completedMember) {
      int marker = beforeCaret.indexOf("//$$");
      if (marker < 0) {
         return false;
      } else {
         String body = beforeCaret.substring(marker + 4).strip();
         String unfinishedMember = qualifier + "." + prefix;
         if (!body.endsWith(unfinishedMember)) {
            return false;
         } else {
            String completed = body.substring(0, body.length() - unfinishedMember.length()) + qualifier + "." + completedMember + ";";

            try {
               PsiElement statement = JavaPsiFacade.getElementFactory(position.getProject()).createStatementFromText(completed, position);
               return PsiTreeUtil.findChildOfType(statement, PsiErrorElement.class) == null;
            } catch (IncorrectOperationException var10) {
               return false;
            }
         }
      }
   }

   private static boolean isBareMemberStatement(String beforeCaret, String qualifier, String prefix) {
      int marker = beforeCaret.indexOf("//$$");
      if (marker < 0) {
         return false;
      } else {
         String body = beforeCaret.substring(marker + 4).strip();
         return body.equals(qualifier + "." + prefix);
      }
   }

   private static LookupElementBuilder withMemberReplacement(LookupElementBuilder item, String value, boolean appendSemicolon, boolean placeCaretInsideParentheses) {
      return item.withInsertHandler((context, ignored) -> {
         Document document = context.getDocument();
         int offset = Math.min(context.getTailOffset(), document.getTextLength());
         int line = document.getLineNumber(offset);
         int lineStart = document.getLineStartOffset(line);
         int lineEnd = document.getLineEndOffset(line);
         String before = document.getImmutableCharSequence().subSequence(lineStart, offset).toString();
         int dot = before.lastIndexOf(46);
         if (dot >= 0) {
            boolean hasSemicolon = false;
            boolean hasFollowingCode = false;
            if (appendSemicolon) {
               CharSequence chars = document.getCharsSequence();

               int cursor;
               for(cursor = offset; cursor < lineEnd && Character.isWhitespace(chars.charAt(cursor)); ++cursor) {
               }

               hasSemicolon = cursor < lineEnd && chars.charAt(cursor) == ';';
               hasFollowingCode = cursor < lineEnd && !hasSemicolon;
            }

            String replacement = value + (appendSemicolon && !hasSemicolon && !hasFollowingCode ? ";" : "");
            int replacementStart = lineStart + dot + 1;
            document.replaceString(replacementStart, offset, replacement);
            int caret = placeCaretInsideParentheses ? replacementStart + value.indexOf(40) + 1 : replacementStart + replacement.length();
            context.getEditor().getCaretModel().moveToOffset(caret);
         }
      });
   }

   private static LookupElementBuilder withIdentifierReplacement(LookupElementBuilder item, String value) {
      return item.withInsertHandler((context, ignored) -> {
         Document document = context.getDocument();
         int end = Math.min(context.getTailOffset(), document.getTextLength());

         int start;
         for(start = end; start > 0 && Character.isJavaIdentifierPart(document.getCharsSequence().charAt(start - 1)); --start) {
         }

         document.replaceString(start, end, value);
         context.getEditor().getCaretModel().moveToOffset(start + value.length());
      });
   }

   private static LookupElementBuilder withPreprocessorReplacement(LookupElementBuilder item, String value, boolean operator) {
      return item.withInsertHandler((context, ignored) -> {
         Document document = context.getDocument();
         int end = Math.min(context.getTailOffset(), document.getTextLength());
         int line = document.getLineNumber(end);
         int lineStart = document.getLineStartOffset(line);
         String before = document.getImmutableCharSequence().subSequence(lineStart, end).toString();
         int replacementStart;
         if (operator) {
            int mc = before.lastIndexOf("MC");
            if (mc < 0) {
               return;
            }

            for(replacementStart = lineStart + mc + 2; replacementStart < end && Character.isWhitespace(document.getCharsSequence().charAt(replacementStart)); ++replacementStart) {
            }
         } else {
            int marker = before.lastIndexOf("//#");
            if (marker < 0) {
               return;
            }

            replacementStart = lineStart + marker + 3;
         }

         document.replaceString(replacementStart, end, value);
         context.getEditor().getCaretModel().moveToOffset(replacementStart + value.length());
      });
   }

   private static LookupElementBuilder withPreprocessorVersionReplacement(LookupElementBuilder item, String value) {
      return item.withInsertHandler((context, ignored) -> {
         Document document = context.getDocument();
         int end = Math.min(context.getTailOffset(), document.getTextLength());
         int line = document.getLineNumber(end);
         int lineStart = document.getLineStartOffset(line);
         String before = document.getImmutableCharSequence().subSequence(lineStart, end).toString();
         Matcher matcher = PREPROCESSOR_VERSION.matcher(before);
         if (matcher.matches()) {
            int start = lineStart + matcher.start(1);
            document.replaceString(start, end, value);
            context.getEditor().getCaretModel().moveToOffset(start + value.length());
         }
      });
   }

   private static LookupElementBuilder withTrailingTokenReplacement(LookupElementBuilder item, int relativeStart, String value) {
      return item.withInsertHandler((context, ignored) -> {
         Document document = context.getDocument();
         int end = Math.min(context.getTailOffset(), document.getTextLength());
         int line = document.getLineNumber(end);
         int start = document.getLineStartOffset(line) + relativeStart;
         document.replaceString(start, end, value);
         context.getEditor().getCaretModel().moveToOffset(start + value.length());
      });
   }

   private static LookupElementBuilder withPreprocessorImportReplacement(LookupElementBuilder item, String qualifiedName) {
      return item.withInsertHandler((context, ignored) -> {
         Document document = context.getDocument();
         int end = Math.min(context.getTailOffset(), document.getTextLength());
         int line = document.getLineNumber(end);
         int lineStart = document.getLineStartOffset(line);
         String before = document.getImmutableCharSequence().subSequence(lineStart, end).toString();
         int marker = before.lastIndexOf("import");
         if (marker >= 0) {
            int start;
            for(start = lineStart + marker + "import".length(); start < end && Character.isWhitespace(document.getCharsSequence().charAt(start)); ++start) {
            }

            String value = qualifiedName + ";";
            document.replaceString(start, end, value);
            context.getEditor().getCaretModel().moveToOffset(start + value.length());
         }
      });
   }

   private static LookupElementBuilder withExpressionNameReplacement(LookupElementBuilder item, String value) {
      return item.withInsertHandler((context, ignored) -> {
         Document document = context.getDocument();
         int end = Math.min(context.getTailOffset(), document.getTextLength());

         int start;
         for(start = end; start > 0 && Character.isJavaIdentifierPart(document.getCharsSequence().charAt(start - 1)); --start) {
         }

         document.replaceString(start, end, value);
         int caret = start + value.length();
         if (value.endsWith("()")) {
            --caret;
         }

         context.getEditor().getCaretModel().moveToOffset(caret);
         if ("MC".equals(value)) {
            AutoPopupController.getInstance(context.getProject()).scheduleAutoPopup(context.getEditor());
         }

      });
   }

   private static LookupElementBuilder withAnnotationReplacement(LookupElementBuilder item, String name) {
      return item.withInsertHandler((context, ignored) -> {
         Document document = context.getDocument();
         int line = document.getLineNumber(Math.min(context.getTailOffset(), document.getTextLength()));
         int start = document.getLineStartOffset(line);
         int end = document.getLineEndOffset(line);
         String text = document.getImmutableCharSequence().subSequence(start, end).toString();
         int at = text.lastIndexOf(64);
         if (at >= 0) {
            int tokenEnd;
            for(tokenEnd = at + 1; tokenEnd < text.length() && Character.isJavaIdentifierPart(text.charAt(tokenEnd)); ++tokenEnd) {
            }

            document.replaceString(start + at + 1, start + tokenEnd, name);
            context.getEditor().getCaretModel().moveToOffset(start + at + 1 + name.length());
         }
      });
   }

   private static LookupElementBuilder withCommentReplacement(LookupElementBuilder item, String attribute, String value) {
      return attribute == null ? item : item.withInsertHandler((context, ignored) -> {
         Document document = context.getDocument();
         int offset = Math.min(context.getTailOffset(), document.getTextLength());
         int line = document.getLineNumber(offset);
         int start = document.getLineStartOffset(line);
         int end = document.getLineEndOffset(line);
         String text = document.getImmutableCharSequence().subSequence(start, end).toString();
         Pattern quotedAttribute = Pattern.compile("\\b" + Pattern.quote(attribute) + "\\s*=\\s*\"[^\"]*\"");
         Matcher matcher = quotedAttribute.matcher(text);
         if (matcher.find()) {
            int openingQuote = text.indexOf(34, matcher.start());
            int closingQuote = text.indexOf(34, openingQuote + 1);
            if (openingQuote >= 0 && closingQuote >= 0) {
               document.replaceString(start + openingQuote + 1, start + closingQuote, value);
               context.getEditor().getCaretModel().moveToOffset(start + openingQuote + 1 + value.length());
            }
         }
      });
   }

   private static String findCommentMethod(Document document, int currentLine) {
      int firstLine = Math.max(0, currentLine - 8);
      String found = null;

      for(int line = currentLine; line >= firstLine; --line) {
         int start = document.getLineStartOffset(line);
         int end = document.getLineEndOffset(line);
         String text = document.getImmutableCharSequence().subSequence(start, end).toString();
         if (!text.stripLeading().startsWith("//$$")) {
            break;
         }

         Matcher matcher = COMMENT_METHOD.matcher(text);
         if (matcher.find()) {
            found = matcher.group(1);
         }
      }

      return found;
   }

   static PsiClass findMixinTarget(PsiElement position) {
      PsiClass mixinClass = (PsiClass)PsiTreeUtil.getParentOfType(position, PsiClass.class);
      if (mixinClass == null) {
         return null;
      } else {
         for(PsiAnnotation annotation : mixinClass.getAnnotations()) {
            if (hasSimpleName(annotation, "Mixin")) {
               PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
               if (value instanceof PsiClassObjectAccessExpression) {
                  PsiClassObjectAccessExpression classLiteral = (PsiClassObjectAccessExpression)value;
                  PsiType var9 = classLiteral.getOperand().getType();
                  if (var9 instanceof PsiClassType) {
                     PsiClassType type = (PsiClassType)var9;
                     return type.resolve();
                  }
               }
            }
         }

         return null;
      }
   }

   private static PsiAnnotation findInjectorAnnotation(PsiElement position) {
      for(PsiElement element = position; element != null; element = element.getParent()) {
         if (element instanceof PsiAnnotation annotation) {
            if (!hasSimpleName(annotation, "At") && annotation.findDeclaredAttributeValue("method") != null) {
               return annotation;
            }
         }
      }

      return null;
   }

   private static String[] annotationStrings(PsiAnnotation annotation, String attribute) {
      PsiAnnotationMemberValue expression = annotation.findAttributeValue(attribute);
      if (expression instanceof PsiLiteralExpression literal) {
         Object var5 = literal.getValue();
         if (var5 instanceof String value) {
            return new String[]{value};
         }
      }

      if (expression instanceof PsiArrayInitializerMemberValue array) {
         return Arrays.stream(array.getInitializers())
                 .filter(PsiLiteralExpression.class::isInstance)
                 .map(PsiLiteralExpression.class::cast)
                 .map(PsiLiteralValue::getValue)
                 .filter(String.class::isInstance)
                 .map(String.class::cast)
                 .toArray(String[]::new);
      } else {
         return new String[0];
      }
   }

   private static boolean isAtAnnotation(PsiAnnotation annotation) {
      return annotation == null ? false : hasSimpleName(annotation, "At");
   }

   private static boolean hasSimpleName(PsiAnnotation annotation, String expected) {
      String name = annotation.getQualifiedName();
      return name != null && (name.equals(expected) || name.endsWith("." + expected));
   }

   private static final class CommentProvider extends CompletionProvider<CompletionParameters> {
      protected void addCompletions(@NotNull CompletionParameters parameters, @NotNull ProcessingContext context, @NotNull CompletionResultSet result) {

         PsiFile file = parameters.getOriginalFile();
         Document document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
         if (document != null) {
            int offset = Math.min(parameters.getOffset(), document.getTextLength());
            int line = document.getLineNumber(offset);
            int lineStart = document.getLineStartOffset(line);
            PsiElement commentElement = file.findElementAt(Math.max(0, offset - 1));
            PsiComment comment = (PsiComment)PsiTreeUtil.getParentOfType(commentElement, PsiComment.class, false);
            int contextStart = comment == null ? lineStart : Math.max(lineStart, comment.getTextOffset());
            String physicalBeforeCaret = document.getImmutableCharSequence().subSequence(contextStart, offset).toString();
            String beforeCaret = MixinCommentContext.javaCodeBeforeCaret(document, offset);
            if (beforeCaret == null) {
               beforeCaret = physicalBeforeCaret;
            }

            String logicalContext = MixinCommentContext.logicalContext(document, offset);
            LookupElement[] preprocessorItems = MixinDescriptorCompletionContributor.preprocessorItems(file, beforeCaret);

            // Written whatever comes of it, for the same reason as the other contributor: an empty answer is the
            // one worth reading, and an empty answer that writes nothing is a report with nothing behind it.
            LOG.info("preprocessor directive completion: file=" + file.getName() + " line=" + (line + 1)
                    + " before=[" + beforeCaret + "] physical=[" + physicalBeforeCaret + "]"
                    + " items=" + preprocessorItems.length
                    + " logical=" + (logicalContext == null ? "-" : "[" + logicalContext + "]"));

            if (preprocessorItems.length > 0) {
               String prefix = MixinDescriptorCompletionContributor.preprocessorPrefix(file, beforeCaret);
               result.withPrefixMatcher(prefix).addAllElements(Arrays.asList(preprocessorItems));
               result.stopHere();
            } else if (MixinCommentContext.javaCodeBeforeCaret(document, offset) != null) {
               Matcher attributeMatcher = MixinDescriptorCompletionContributor.COMMENT_ATTRIBUTE.matcher(beforeCaret);
               String typedPrefix;
               if (attributeMatcher.find()) {
                  typedPrefix = attributeMatcher.group(2);
               } else {
                  Matcher atValueMatcher = MixinDescriptorCompletionContributor.COMMENT_AT_VALUE.matcher(logicalContext);
                  if (atValueMatcher.find()) {
                     typedPrefix = atValueMatcher.group(1);
                  } else {
                     Matcher parametersMatcher = MixinDescriptorCompletionContributor.COMMENT_METHOD_PARAMETERS.matcher(beforeCaret);
                     if (parametersMatcher.find()) {
                        typedPrefix = MixinDescriptorCompletionContributor.currentParameterPrefix(parametersMatcher.group(1));
                     } else {
                        Matcher annotationMatcher = MixinDescriptorCompletionContributor.COMMENT_ANNOTATION.matcher(beforeCaret);
                        if (annotationMatcher.find()) {
                           typedPrefix = annotationMatcher.group(1);
                        } else {
                           Matcher keywordMatcher = MixinDescriptorCompletionContributor.COMMENT_KEYWORD.matcher(beforeCaret);
                           if (keywordMatcher.find()) {
                              typedPrefix = keywordMatcher.group(1);
                           } else {
                              Matcher annotationAttributeMatcher = MixinDescriptorCompletionContributor.COMMENT_ANNOTATION_ATTRIBUTE.matcher(beforeCaret);
                              if (annotationAttributeMatcher.find()) {
                                 typedPrefix = annotationAttributeMatcher.group(2);
                              } else {
                                 MixinCommentContext.AnnotationAttribute annotationAttribute = MixinCommentContext.annotationAttribute(logicalContext);
                                 if (annotationAttribute != null) {
                                    typedPrefix = annotationAttribute.prefix();
                                 } else {
                                    Matcher memberMatcher = MixinDescriptorCompletionContributor.COMMENT_MEMBER.matcher(beforeCaret);
                                    if (memberMatcher.find()) {
                                       typedPrefix = memberMatcher.group(2);
                                    } else {
                                       Matcher identifierMatcher = MixinDescriptorCompletionContributor.COMMENT_IDENTIFIER.matcher(beforeCaret);
                                       if (!identifierMatcher.find()) {
                                          return;
                                       }

                                       typedPrefix = identifierMatcher.group(1);
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }
               }

               CompletionResultSet commentResult = result.withPrefixMatcher(typedPrefix);
               LookupElement[] commentItems = MixinDescriptorCompletionContributor.commentItems(file, document, offset);
               if (commentItems.length > 0) {
                  commentResult.addAllElements(Arrays.asList(commentItems));
                  result.stopHere();
               }

            }
         }
      }

      // $FF: synthetic method
      
   }

   private static record PreprocessorOperator(String value, String description) {
   }

   private static record PreprocessorVersion(String name) {
   }

   private static final class Provider extends CompletionProvider<CompletionParameters> {
      protected void addCompletions(@NotNull CompletionParameters parameters, @NotNull ProcessingContext context, @NotNull CompletionResultSet result) {

         PsiLiteralExpression literal = (PsiLiteralExpression)PsiTreeUtil.getParentOfType(parameters.getPosition(), PsiLiteralExpression.class, false);
         if (literal != null) {
            PsiNameValuePair pair = (PsiNameValuePair)PsiTreeUtil.getParentOfType(literal, PsiNameValuePair.class, true);
            if (pair != null) {
               String attribute = pair.getName();
               if (attribute == null) {
                  attribute = "value";
               }

               if ("method".equals(attribute)) {
                  MixinDescriptorCompletionContributor.addMixinMethods(literal, result);
               }

               PsiAnnotation containingAnnotation = (PsiAnnotation)PsiTreeUtil.getParentOfType(pair, PsiAnnotation.class);
               if ("target".equals(attribute) && MixinDescriptorCompletionContributor.isAtAnnotation(containingAnnotation)) {
                  MixinDescriptorCompletionContributor.addAtTargets(literal, result);
               }

            }
         }
      }

      // $FF: synthetic method
      
   }

   private static record BytecodeCall(String owner, String name, String descriptor) {
   }

   private static record DeclarationNameContext(String simpleType) {
   }

   @FunctionalInterface
   private interface VersionInsertHandler {
      LookupElementBuilder apply(LookupElementBuilder var1, String var2);
   }
}
