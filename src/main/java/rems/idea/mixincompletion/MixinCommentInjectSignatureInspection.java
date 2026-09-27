package rems.idea.mixincompletion;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;

public final class MixinCommentInjectSignatureInspection extends LocalInspectionTool {
   private static final Pattern METHOD_DECLARATION = Pattern.compile("^\\s*//\\$\\$\\s+(?:(?:public|protected|private|abstract|final|static|synchronized|native|strictfp)\\s+)+[A-Za-z_$][A-Za-z0-9_$.<>?\\[\\]]*\\s+[A-Za-z_$][A-Za-z0-9_$]*\\(");
   private static final Pattern INJECT_METHOD = Pattern.compile("@Inject\\s*\\(.*?\\bmethod\\s*=\\s*\"([^\"]+)\"", 32);

   public @NotNull PsiElementVisitor buildVisitor(final @NotNull ProblemsHolder holder, boolean isOnTheFly) {

      return new JavaElementVisitor() {
         public void visitComment(@NotNull PsiComment comment) {

            MixinCommentInjectSignatureInspection.checkComment(comment, holder);
         }

         // $FF: synthetic method
         
      };
   }

   private static void checkComment(PsiComment comment, ProblemsHolder holder) {
      Matcher declaration = METHOD_DECLARATION.matcher(comment.getText());
      if (declaration.find()) {
         PsiFile file = comment.getContainingFile();
         Document document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
         if (document != null) {
            int line = document.getLineNumber(comment.getTextOffset());
            String selector = findInjectMethod(document, line);
            if (selector != null) {
               int opening = comment.getText().indexOf(40, declaration.start());
               if (opening >= 0) {
                  ParameterSpan span = findParameterSpan(document, line, comment.getTextOffset() + opening);
                  if (span != null) {
                     String expected = expectedParameters(comment, document, line, selector);
                     if (!normalize(span.actual()).equals(normalize(expected))) {
                        holder.registerProblem(comment, TextRange.create(opening, opening + 1), "Method signature does not match expected signature for Inject", new LocalQuickFix[]{new FixSignatureQuickFix(expected)});
                     }
                  }
               }
            }
         }
      }
   }

   private static ParameterSpan findParameterSpan(Document document, int openingLine, int openingOffset) {
      int lastLine = Math.min(document.getLineCount() - 1, openingLine + 20);

      for(int line = openingLine; line <= lastLine; ++line) {
         int start = line == openingLine ? openingOffset + 1 : document.getLineStartOffset(line);
         int end = document.getLineEndOffset(line);
         String text = document.getImmutableCharSequence().subSequence(start, end).toString();
         if (line > openingLine && !text.stripLeading().startsWith("//$$")) {
            return null;
         }

         int closingInPart = text.indexOf(41);
         if (closingInPart >= 0) {
            int closingOffset = start + closingInPart;
            String actual = document.getImmutableCharSequence().subSequence(openingOffset + 1, closingOffset).toString().replaceAll("(?m)^\\s*//\\$\\$\\s?", "");
            return new ParameterSpan(openingOffset, closingOffset, openingLine, line, actual);
         }
      }

      return null;
   }

   private static String expectedParameters(PsiComment comment, Document document, int line, String selector) {
      PsiClass currentTarget = MixinDescriptorCompletionContributor.findMixinTarget(comment);
      if (currentTarget == null) {
         return "CallbackInfo ci";
      } else {
         PsiClass target = VersionedModuleResolver.retarget(currentTarget, comment.getProject(), document, line);
         String name = selector.contains("(") ? selector.substring(0, selector.indexOf(40)) : selector;
         PsiMethod[] methods = target.findMethodsByName(name, false);
         return methods.length == 0 ? "CallbackInfo ci" : injectParameters(methods[0]);
      }
   }

   private static String injectParameters(PsiMethod method) {
      List<String> parameters = new ArrayList();

      for(PsiParameter parameter : method.getParameterList().getParameters()) {
         String var10001 = parameter.getType().getPresentableText();
         parameters.add(var10001 + " " + parameter.getName());
      }

      PsiType returnType = method.getReturnType();
      if (returnType != null && !"void".equals(returnType.getCanonicalText())) {
         parameters.add("CallbackInfoReturnable<" + boxedType(returnType) + "> cir");
      } else {
         parameters.add("CallbackInfo ci");
      }

      return String.join(", ", parameters);
   }

   private static String boxedType(PsiType type) {
      String var10000;
      switch (type.getCanonicalText()) {
         case "boolean" -> var10000 = "Boolean";
         case "byte" -> var10000 = "Byte";
         case "char" -> var10000 = "Character";
         case "short" -> var10000 = "Short";
         case "int" -> var10000 = "Integer";
         case "long" -> var10000 = "Long";
         case "float" -> var10000 = "Float";
         case "double" -> var10000 = "Double";
         default -> var10000 = type.getPresentableText();
      }

      return var10000;
   }

   private static String findInjectMethod(Document document, int currentLine) {
      int firstLine = Math.max(0, currentLine - 12);
      int blockStart = currentLine;

      for(int line = currentLine - 1; line >= firstLine; blockStart = line--) {
         String text = document.getImmutableCharSequence().subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString();
         if (!text.stripLeading().startsWith("//$$")) {
            break;
         }
      }

      StringBuilder logical = new StringBuilder();

      for(int line = blockStart; line < currentLine; ++line) {
         String text = document.getImmutableCharSequence().subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString().stripLeading();
         if (text.startsWith("//$$")) {
            logical.append(text.substring(4).stripLeading()).append('\n');
         }
      }

      Matcher matcher = INJECT_METHOD.matcher(logical);
      return matcher.find() ? matcher.group(1) : null;
   }

   private static String normalize(String value) {
      return value.replaceAll("\\s+", "").trim();
   }

   // $FF: synthetic method
   

   private static record ParameterSpan(int openingOffset, int closingOffset, int openingLine, int closingLine, String actual) {
   }

   private static final class FixSignatureQuickFix implements LocalQuickFix {
      private final String expected;

      private FixSignatureQuickFix(String expected) {
         this.expected = expected;
      }

      public @NotNull String getFamilyName() {
         return "Fix method signature";
      }

      public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {

         PsiComment comment = (PsiComment)descriptor.getPsiElement();
         Document document = PsiDocumentManager.getInstance(project).getDocument(comment.getContainingFile());
         if (document != null) {
            String text = comment.getText();
            Matcher matcher = MixinCommentInjectSignatureInspection.METHOD_DECLARATION.matcher(text);
            if (matcher.find()) {
               int opening = text.indexOf(40, matcher.start());
               if (opening >= 0) {
                  int openingOffset = comment.getTextOffset() + opening;
                  int openingLine = document.getLineNumber(openingOffset);
                  ParameterSpan span = MixinCommentInjectSignatureInspection.findParameterSpan(document, openingLine, openingOffset);
                  if (span != null) {
                     String replacement = this.expected;
                     if (span.openingLine() != span.closingLine()) {
                        int lineStart = document.getLineStartOffset(openingLine);
                        String openingText = document.getImmutableCharSequence().subSequence(lineStart, openingOffset).toString();
                        int marker = openingText.indexOf("//$$");
                        String indentation = marker < 0 ? "" : openingText.substring(0, marker);
                        String lineSeparator = document.getText().contains("\r\n") ? "\r\n" : "\n";
                        replacement = lineSeparator + indentation + "//$$         " + this.expected + lineSeparator + indentation + "//$$ ";
                     }

                     document.replaceString(span.openingOffset() + 1, span.closingOffset(), replacement);
                     PsiDocumentManager.getInstance(project).commitDocument(document);
                  }
               }
            }
         }
      }

      // $FF: synthetic method
      
   }
}
