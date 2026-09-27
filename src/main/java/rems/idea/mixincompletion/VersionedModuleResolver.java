package rems.idea.mixincompletion;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class VersionedModuleResolver {
   private static final Logger LOG = Logger.getInstance(VersionedModuleResolver.class);
   private static final Key<Cached> LAST = Key.create("rems.versioned.resolved.module");

   /** A class every version of the game has, asked of a module to tell one that can see it from one that cannot. */
   private static final String GAME_CLASS = "net.minecraft.server.MinecraftServer";

   private VersionedModuleResolver() {
   }

   /**
    * Whether a module resolves the game.
    *
    * <p>One index lookup, and the answer is cached with the module for the line: this is the cheap question
    * that decides between two modules of one version, as against trying a branch against several modules -
    * which parses the branch again every time.
    */
   static boolean seesGame(Project project, Module module) {
      return JavaPsiFacade.getInstance(project)
              .findClass(GAME_CLASS, GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, false))
              != null;
   }

   static PsiClass retarget(PsiClass currentTarget, Project project, Document document, int currentLine) {
      String qualifiedName = currentTarget.getQualifiedName();
      Module selectedModule = resolveModule(project, document, currentLine);
      if (qualifiedName != null && selectedModule != null) {
         JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
         GlobalSearchScope scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(selectedModule, false);
         PsiClass[] matches = facade.findClasses(qualifiedName, scope);
         if (matches.length > 0) {
            LOG.info("//$$ completion module: " + selectedModule.getName());
            return matches[0];
         } else {
            return currentTarget;
         }
      } else {
         return currentTarget;
      }
   }

   static Module resolveModule(Project project, Document document, int currentLine) {
      long stamp = document.getModificationStamp();
      Cached cached = (Cached)document.getUserData(LAST);
      if (cached != null && cached.stamp() == stamp && cached.line() == currentLine) {
         return cached.module();
      } else {
         Module module = computeModule(project, document, currentLine);
         document.putUserData(LAST, new Cached(stamp, currentLine, module));
         return module;
      }
   }

   private static Module computeModule(Project project, Document document, int currentLine) {
      List<Module> candidates = computeCandidates(project, document, currentLine);

      return candidates.isEmpty() ? null : candidates.get(0);
   }

   /** The modules a branch on a line could be resolved against, the one it is expected to belong to first. */
   private static List<Module> computeCandidates(Project project, Document document, int currentLine) {
      if (!PreprocessorLanguage.hasVersionContext(document, currentLine)) {
         LOG.warn("preprocessor module: line=" + currentLine + " no version context");

         return List.of();
      } else {
         List<VersionedModule> candidates = new ArrayList();
         int modules = 0;
         int versioned = 0;

         for(Module module : ModuleManager.getInstance(project).getModules()) {
            ++modules;

            int code = PreprocessorLanguage.moduleVersionCode(module.getName());

            if (code < 0) {
               continue;
            }

            ++versioned;

            if (PreprocessorLanguage.isLineActive(document, currentLine, Map.of("MC", code))) {
               candidates.add(new VersionedModule(code, module));
            }
         }

         if (candidates.isEmpty()) {
            LOG.warn("preprocessor module: line=" + currentLine + " modules=" + modules
                    + " withVersion=" + versioned + " active=none");

            return List.of();
         } else {
            int main = mainVersionCode(project, document);
            candidates.sort(Comparator.comparingInt((VersionedModule candidate) -> main < 0 ? candidate.code() : Math.abs(candidate.code() - main)).thenComparingInt(VersionedModule::code));

            // Two modules can carry the same version. A Gradle project gives a module to the project and one to
            // each source set of it, and both names read as the same version here - Carpet-Igny-Addition.1.21.8
            // and Carpet-Igny-Addition.1.21.8.main are one version to this code. Which of them comes first is
            // decided by the order the modules happen to be listed in, and the one that cannot see the game is
            // a module where every name in a branch comes out unresolved: a branch drawn white, with nothing
            // on screen to say why - which reads as the colouring having been taken away.
            //
            // Asked of the module rather than assumed from its name: the one whose scope the game is in comes
            // first, and whichever of the two it is, the other follows it in the list.
            List<VersionedModule> ordered = new ArrayList();

            for(VersionedModule candidate : candidates) {
               if (seesGame(project, candidate.module())) {
                  ordered.add(candidate);
               }
            }

            for(VersionedModule candidate : candidates) {
               if (!ordered.contains(candidate)) {
                  ordered.add(candidate);
               }
            }

            boolean seesGame = !ordered.isEmpty() && seesGame(project, ((VersionedModule)ordered.get(0)).module());
            List<Module> answer = new ArrayList();

            for(VersionedModule candidate : ordered) {
               answer.add(candidate.module());
            }

            LOG.warn("preprocessor module: line=" + currentLine + " modules=" + modules
                    + " withVersion=" + versioned + " active=" + candidates.size()
                    + " main=" + main + " chosen=" + ((Module)answer.get(0)).getName() + " seesGame=" + seesGame
                    + " candidates=" + answer.size());

            return answer;
         }
      }
   }

   static @Nullable Module mainModule(Project project, Document document) {
      int main = mainVersionCode(project, document);
      if (main < 0) {
         return null;
      } else {
         for(Module module : ModuleManager.getInstance(project).getModules()) {
            if (PreprocessorLanguage.moduleVersionCode(module.getName()) == main) {
               return module;
            }
         }

         return null;
      }
   }

   private static int mainVersionCode(Project project, Document document) {
      VirtualFile file = FileDocumentManager.getInstance().getFile(document);
      PsiFile psiFile = file == null ? null : PsiManager.getInstance(project).findFile(file);
      return PreprocessorLanguage.mainVersionCode(psiFile);
   }

   private static record Cached(long stamp, int line, Module module) {
   }

   private static record VersionedModule(int code, Module module) {
   }
}
