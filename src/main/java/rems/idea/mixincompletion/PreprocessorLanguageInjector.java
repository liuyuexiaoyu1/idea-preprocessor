package rems.idea.mixincompletion;

import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class PreprocessorLanguageInjector implements MultiHostInjector {
   private static final String LINE_PREFIX = "//$$";
   private static final String SHELL_CLASS = "__PreprocessorFragment";
   private static final String SHELL_METHOD = "__preprocessorBody";

   /** A `@Mixin(...)` written in a marker, which is how a target for another version is given. */
   private static final Pattern CARRIED_MIXIN = Pattern.compile("@Mixin\\s*\\([^\\n]*\\)");

   public void getLanguagesToInject(@NotNull MultiHostRegistrar registrar, @NotNull PsiElement host) {

      if (host instanceof PsiComment comment) {
         if (host instanceof PsiLanguageInjectionHost injectionHost) {
            if (!(comment.getContainingFile() instanceof PsiJavaFile)) {
               return;
            }

            if (isInactiveLine(comment) && !previousIsInactiveLine(comment)) {
               injectRun(registrar, comment);
               return;
            }

            TextRange range = MixinCommentContext.codeRange(comment.getText());
            if (range == null) {
               return;
            }

            injectSingle(registrar, comment, injectionHost, range);
            return;
         }
      }

   }

   public @NotNull List<? extends Class<? extends PsiElement>> elementsToInjectIn() {
      List var10000 = List.of(PsiComment.class);

      return var10000;
   }

   private static void injectSingle(MultiHostRegistrar registrar, PsiComment comment, PsiLanguageInjectionHost host, TextRange range) {
      registrar.startInjecting(JavaLanguage.INSTANCE).addPlace(shellOpen(comment), shellClose(comment), host, range).doneInjecting();
   }

   private static void injectRun(MultiHostRegistrar registrar, PsiComment first) {
      List<PsiComment> run = new ArrayList();

      for(PsiElement current = first; current instanceof PsiComment; current = PsiTreeUtil.nextLeaf(current)) {
         PsiComment comment = (PsiComment)current;
         if (!isInactiveLine(comment)) {
            break;
         }

         run.add(comment);
      }

      List<PsiLanguageInjectionHost> hosts = new ArrayList();
      List<TextRange> bodies = new ArrayList();

      for(PsiComment line : run) {
         if (line instanceof PsiLanguageInjectionHost lineHost) {
            TextRange range = MixinCommentContext.codeRange(line.getText());
            if (range != null) {
               hosts.add(lineHost);
               bodies.add(range);
            }
         }
      }

      if (!hosts.isEmpty()) {
         registrar.startInjecting(JavaLanguage.INSTANCE);

         for(int index = 0; index < hosts.size(); ++index) {
            String prefix = index == 0 ? shellOpen(first) : "";
            String suffix = index == hosts.size() - 1 ? shellClose(first) : "\n";
            registrar.addPlace(prefix, suffix, (PsiLanguageInjectionHost)hosts.get(index), (TextRange)bodies.get(index));
         }

         registrar.doneInjecting();
      }
   }

   static String shellOpen(PsiComment comment) {
      PsiMethod method = enclosingMethod(comment);
      String head;
      if (method == null) {
         head = "class __PreprocessorFragment {\n";
      } else {
         PsiType returned = method.getReturnType();
         String type = returned == null ? "void" : returned.getCanonicalText();
         head = "class __PreprocessorFragment {\n" + type + " __preprocessorBody() {\n";
      }

      // A replacement tail continues the statement written above it, and without that statement the fragment
      // cannot tell what the tail belongs to. The platform recovers from a leading dot by hanging the call on
      // the wrapper class, so asking what ".addValidator" is answers "__PreprocessorFragment.addValidator" -
      // a method that exists nowhere except in that recovery, and which then answers every question about the
      // line: what it is, where it goes, what its parameters are.
      //
      // The statement is handed over as part of the opening, and the tail takes the place the line itself
      // would have taken. Its own text already ends at a line break, so nothing is appended after it - one
      // more would move every fragment line one line away from the host line it belongs to.
      return mixinOf(comment) + head + replacementContextOf(comment);
   }

   /**
    * The {@code @Mixin(...)} the fragment should carry, which is the one this line's version is mixed by.
    *
    * <p>What the annotations in a branch can offer is worked out from the class being mixed into: which methods
    * {@code @Inject(method = "...")} may name, which points {@code @At("...")} may be. That is read off the
    * {@code @Mixin} on the class - and the class is the host one, which is not in the fragment, so there was
    * nothing to read and the annotations inside a branch had no suggestions at all.
    *
    * <p>Which {@code @Mixin} to write is not always the one on the class. A mixin whose target differs by game
    * version carries the annotation for the other versions in a marker, and the class itself holds the one the
    * version being built uses. Copying the class's own annotation into a branch written for another version is
    * answering about the wrong class - so a line the current version does not take is given the carried
    * annotation, and a line it does take is given the one on the class.
    */
   private static String mixinOf(PsiComment comment) {
      PsiClass owner = (PsiClass)PsiTreeUtil.getParentOfType(comment, PsiClass.class, false);

      if (owner == null) {
         return "";
      }

      String written = mixinAnnotationOf(owner);
      String carried = carriedMixinOf(owner);
      String chosen = activeAt(comment)
              ? (written != null ? written : carried)
              : (carried != null ? carried : written);

      return chosen == null ? "" : chosen + "\n";
   }

   /** The annotation on the class itself, which is the one the version being built goes by. */
   private static @Nullable String mixinAnnotationOf(PsiClass owner) {
      for(PsiAnnotation annotation : owner.getAnnotations()) {
         String qualified = annotation.getQualifiedName();

         if (qualified != null && qualified.endsWith(".Mixin")) {
            return annotation.getText();
         }
      }

      return null;
   }

   /** The annotation written in the markers of the class, which is how the other versions' targets are given. */
   private static @Nullable String carriedMixinOf(PsiClass owner) {
      for(PsiComment each : PsiTreeUtil.findChildrenOfType(owner, PsiComment.class)) {
         Matcher matcher = CARRIED_MIXIN.matcher(each.getText());

         if (matcher.find()) {
            return matcher.group();
         }
      }

      return null;
   }

   /** Whether the line a comment is on is the branch the version being built takes. */
   private static boolean activeAt(PsiComment comment) {
      PsiFile file = comment.getContainingFile();
      Document document = PsiDocumentManager.getInstance(comment.getProject()).getDocument(file);
      int main = PreprocessorLanguage.mainVersionCode(file);

      return document != null && main >= 0 && PreprocessorLanguage.isLineActive(document,
              document.getLineNumber(comment.getTextRange().getStartOffset()), Map.of("MC", main));
   }

   private static String replacementContextOf(PsiComment comment) {
      if (MixinCommentContext.replacementBodyStart(comment.getText()) < 0) {
         return "";
      }

      PsiFile file = comment.getContainingFile();

      if (file == null) {
         return "";
      }

      Document document = PsiDocumentManager.getInstance(comment.getProject()).getDocument(file);

      if (document == null) {
         return "";
      }

      int offset = Math.max(0, Math.min(comment.getTextOffset(), document.getTextLength()));

      return MixinCommentContext.replacementContext(document, document.getLineNumber(offset));
   }

   private static String shellClose(PsiComment comment) {
      return enclosingMethod(comment) == null ? "\n}\n" : "\n}\n}\n";
   }

   private static PsiMethod enclosingMethod(PsiComment comment) {
      return (PsiMethod)PsiTreeUtil.getParentOfType(comment, PsiMethod.class, false, new Class[]{PsiClass.class});
   }

   private static boolean isInactiveLine(PsiComment comment) {
      String text = comment.getText();

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

   private static boolean previousIsInactiveLine(PsiComment comment) {
      PsiElement previous = PsiTreeUtil.prevLeaf(comment);
      boolean var10000;
      if (previous instanceof PsiComment candidate) {
         if (isInactiveLine(candidate)) {
            var10000 = true;
            return var10000;
         }
      }

      var10000 = false;
      return var10000;
   }

   // $FF: synthetic method
   
}
