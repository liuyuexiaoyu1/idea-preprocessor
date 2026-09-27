package rems.idea.mixincompletion;

import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import org.jetbrains.annotations.Nullable;

final class JvmDescriptors {
   private JvmDescriptors() {
   }

   static @Nullable String methodDescriptor(PsiMethod method) {
      StringBuilder result = new StringBuilder("(");

      for(PsiParameter parameter : method.getParameterList().getParameters()) {
         String descriptor = typeDescriptor(parameter.getType());
         if (descriptor == null) {
            return null;
         }

         result.append(descriptor);
      }

      result.append(')');
      if (method.isConstructor()) {
         return result.append('V').toString();
      } else {
         String returnDescriptor = typeDescriptor(method.getReturnType());
         return returnDescriptor == null ? null : result.append(returnDescriptor).toString();
      }
   }

   static @Nullable String owner(PsiClass psiClass) {
      String qualifiedName = psiClass.getQualifiedName();
      if (qualifiedName == null) {
         return null;
      } else {
         PsiClass containingClass = psiClass.getContainingClass();
         if (containingClass == null) {
            return qualifiedName.replace('.', '/');
         } else {
            String outer = owner(containingClass);
            return outer == null ? null : outer + "$" + psiClass.getName();
         }
      }
   }

   private static @Nullable String typeDescriptor(@Nullable PsiType type) {
      if (type == null) {
         return null;
      } else if (type instanceof PsiPrimitiveType) {
         PsiPrimitiveType primitive = (PsiPrimitiveType)type;
         String var10000;
         switch (primitive.getCanonicalText()) {
            case "void" -> var10000 = "V";
            case "boolean" -> var10000 = "Z";
            case "byte" -> var10000 = "B";
            case "char" -> var10000 = "C";
            case "short" -> var10000 = "S";
            case "int" -> var10000 = "I";
            case "long" -> var10000 = "J";
            case "float" -> var10000 = "F";
            case "double" -> var10000 = "D";
            default -> var10000 = null;
         }

         return var10000;
      } else if (type instanceof PsiArrayType) {
         PsiArrayType arrayType = (PsiArrayType)type;
         String component = typeDescriptor(arrayType.getComponentType());
         return component == null ? null : "[" + component;
      } else if (type instanceof PsiClassType) {
         PsiClassType classType = (PsiClassType)type;
         PsiClass psiClass = classType.resolve();
         String owner = psiClass == null ? null : owner(psiClass);
         return owner == null ? null : "L" + owner + ";";
      } else {
         return null;
      }
   }
}
