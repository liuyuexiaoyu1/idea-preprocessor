package rems.idea.mixincompletion;

import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class MixinMetadataResolver {
   private static final String INJECTION_POINT_CLASS = "org.spongepowered.asm.mixin.injection.InjectionPoint";

   private MixinMetadataResolver() {
   }

   static PsiClass findAnnotationClass(PsiElement context, String name, GlobalSearchScope scope) {
      Project project = context.getProject();
      JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
      if (name.indexOf(46) >= 0) {
         PsiClass exact = facade.findClass(name, scope);
         return exact != null && exact.isAnnotationType() ? exact : null;
      } else {
         PsiFile hostFile = context.getContainingFile();
         if (hostFile instanceof PsiJavaFile) {
            PsiJavaFile javaFile = (PsiJavaFile)hostFile;
            PsiImportList imports = javaFile.getImportList();
            if (imports != null) {
               for(PsiImportStatement statement : imports.getImportStatements()) {
                  String qualifiedName = statement.getQualifiedName();
                  if (qualifiedName != null && !statement.isOnDemand() && qualifiedName.endsWith("." + name)) {
                     PsiClass imported = facade.findClass(qualifiedName, scope);
                     if (imported != null && imported.isAnnotationType()) {
                        return imported;
                     }
                  }
               }
            }
         }

         for(PsiClass candidate : PsiShortNamesCache.getInstance(project).getClassesByName(name, scope)) {
            if (candidate.isAnnotationType()) {
               return candidate;
            }
         }

         return null;
      }
   }

   static List<PsiMethod> annotationAttributes(PsiElement context, String annotationName, GlobalSearchScope scope) {
      PsiClass annotation = findAnnotationClass(context, annotationName, scope);
      if (annotation == null) {
         return List.of();
      } else {
         List<PsiMethod> attributes = new ArrayList(List.of(annotation.getMethods()));
         attributes.sort(Comparator.comparing(PsiMethod::getName));
         return attributes;
      }
   }

   static List<InjectionPoint> injectionPoints(Project project, GlobalSearchScope scope) {
      PsiClass base = JavaPsiFacade.getInstance(project).findClass("org.spongepowered.asm.mixin.injection.InjectionPoint", scope);
      if (base == null) {
         return List.of();
      } else {
         Map<String, InjectionPoint> points = new LinkedHashMap();

         for(PsiClass candidate : ClassInheritorsSearch.search(base, scope, true).findAll()) {
            PsiModifierList modifiers = candidate.getModifierList();
            if (modifiers != null) {
               for(PsiAnnotation annotation : modifiers.getAnnotations()) {
                  String annotationName = annotation.getQualifiedName();
                  if (annotationName != null && annotationName.endsWith(".AtCode")) {
                     PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
                     Object constant = value == null ? null : JavaPsiFacade.getInstance(project).getConstantEvaluationHelper().computeConstantExpression(value);
                     if (constant instanceof String) {
                        String code = (String)constant;
                        if (!code.isBlank()) {
                           String key = code.toUpperCase(Locale.ROOT);
                           points.putIfAbsent(key, new InjectionPoint(code, candidate));
                        }
                     }
                  }
               }
            }
         }

         return points.values().stream().sorted(Comparator.comparing(InjectionPoint::code, String.CASE_INSENSITIVE_ORDER)).toList();
      }
   }

   static PsiClass findInjectionPoint(Project project, GlobalSearchScope scope, String code) {
      for(InjectionPoint point : injectionPoints(project, scope)) {
         if (point.code().equalsIgnoreCase(code)) {
            return point.implementation();
         }
      }

      return null;
   }

   static record InjectionPoint(String code, PsiClass implementation) {
   }
}
