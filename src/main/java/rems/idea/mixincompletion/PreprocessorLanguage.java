package rems.idea.mixincompletion;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

final class PreprocessorLanguage {
   private static final List<Directive> LEGACY_DIRECTIVES;
   private static final List<Directive> LIUYUE_DIRECTIVES;
   static final List<Directive> DIRECTIVES;
   private static final Pattern DIRECTIVE;
   private static final Pattern DEFINE;
   private static final Pattern VERSION_MODULE;
   private static final Pattern DEFINED;
   private static final Pattern BARE_COMPARISON;
   private static final Pattern BARE_VERSION;
   private static final Pattern BARE_RANGE;
   private static final int MAX_DEFINE_DEPTH = 8;
   private static final Pattern GRAPH_NODE;
   private static final Pattern GRAPH_LINK;

   private PreprocessorLanguage() {
   }

   static Directive directive(String name) {
      for(Directive directive : DIRECTIVES) {
         if (directive.name().equals(name)) {
            return directive;
         }
      }

      return null;
   }

   static List<Directive> completionDirectives(PsiFile file) {
      return usesLiuyuePreprocessor(file) ? LIUYUE_DIRECTIVES : LEGACY_DIRECTIVES;
   }

   static boolean usesLiuyuePreprocessor(PsiFile file) {
      for(VirtualFile directory = file.getVirtualFile() == null ? null : file.getVirtualFile().getParent(); directory != null; directory = directory.getParent()) {
         for(String name : List.of("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts", "gradle.properties")) {
            VirtualFile configuration = directory.findChild(name);
            if (configuration != null && !configuration.isDirectory()) {
               try {
                  if (VfsUtilCore.loadText(configuration).toLowerCase(Locale.ROOT).contains("liuyuexiaoyu1")) {
                     return true;
                  }
               } catch (IOException var6) {
               }
            }
         }
      }

      return false;
   }

   static ParsedDirective parseDirective(String line) {
      Matcher matcher = DIRECTIVE.matcher(line);
      return !matcher.matches() ? null : new ParsedDirective(matcher.group(1), matcher.group(2) == null ? "" : matcher.group(2).strip());
   }

   static boolean opensConditional(String line) {
      ParsedDirective directive = parseDirective(line);
      return directive != null && (directive.name().equals("if") || directive.name().equals("ifdef") || directive.name().equals("ifndef"));
   }

   static boolean closesConditional(String line) {
      ParsedDirective directive = parseDirective(line);
      return directive != null && directive.name().equals("endif");
   }

   static boolean hasEnclosingConditional(Document document, int currentLine) {
      int depth = 0;

      for(int line = Math.min(currentLine - 1, document.getLineCount() - 1); line >= 0; --line) {
         String text = lineText(document, line);
         if (closesConditional(text)) {
            ++depth;
         } else if (opensConditional(text)) {
            if (depth == 0) {
               return true;
            }

            --depth;
         }
      }

      return false;
   }

   static int conditionalDepthBeforeLine(Document document, int currentLine) {
      int depth = 0;
      int end = Math.min(currentLine, document.getLineCount());

      for(int line = 0; line < end; ++line) {
         String text = lineText(document, line);
         if (opensConditional(text)) {
            ++depth;
         } else if (closesConditional(text)) {
            depth = Math.max(0, depth - 1);
         }
      }

      return depth;
   }

   static String codePrefixBeforeLine(Document document, int currentLine) {
      Deque<String> prefixes = new ArrayDeque();
      int end = Math.min(currentLine, document.getLineCount());

      for(int line = 0; line < end; ++line) {
         String text = lineText(document, line).stripLeading();
         ParsedDirective directive = parseDirective(text);
         if (directive != null) {
            if (!directive.name().equals("if") && !directive.name().equals("ifdef") && !directive.name().equals("ifndef")) {
               if (directive.name().equals("endif") && !prefixes.isEmpty()) {
                  prefixes.pop();
               }
            } else {
               int directiveStart = text.lastIndexOf("//#");
               String parentPrefix = directiveStart < 0 ? "" : text.substring(0, directiveStart);
               prefixes.push(parentPrefix + "//$$ ");
            }
         }
      }

      return prefixes.isEmpty() ? "" : (String)prefixes.peek();
   }

   static boolean hasVersionContext(Document document, int currentLine) {
      if (hasEnclosingConditional(document, currentLine)) {
         return true;
      } else if (currentLine >= 0 && currentLine < document.getLineCount()) {
         String line = lineText(document, currentLine);
         return line.contains("//?") || line.contains("/*?") || line.contains("//#replace");
      } else {
         return false;
      }
   }

   static boolean hasClosingConditional(Document document, int currentLine, int requiredDepth) {
      int depth = requiredDepth;

      for(int line = currentLine; line < document.getLineCount(); ++line) {
         String text = lineText(document, line);
         if (opensConditional(text)) {
            ++depth;
         } else if (closesConditional(text)) {
            --depth;
            if (depth == 0) {
               return true;
            }
         }
      }

      return false;
   }

   static boolean isLineActive(Document document, int targetLine, Map<String, Integer> variables) {
      List<String> lines = new ArrayList(document.getLineCount());

      for(int line = 0; line < document.getLineCount(); ++line) {
         lines.add(lineText(document, line));
      }

      return isLineActive(lines, targetLine, variables);
   }

   static boolean isLineActive(List<String> lines, int targetLine, Map<String, Integer> variables) {
      Map<String, String> definitions = collectDefinitions(lines);
      Deque<Branch> stack = new ArrayDeque();
      boolean active = true;
      boolean inCase = false;
      boolean caseMatched = false;
      int end = Math.min(targetLine, lines.size() - 1);

      for(int index = 0; index <= end; ++index) {
         ParsedDirective directive = parseDirective((String)lines.get(index));
         if (directive == null) {
            String replacementCondition = replacementCondition((String)lines.get(index));
            if (replacementCondition != null && index == targetLine) {
               try {
                  return active && evaluate(replacementCondition, variables, definitions);
               } catch (IllegalArgumentException var17) {
                  return false;
               }
            }

            CaseBranch caseBranch = parseCaseBranch((String)lines.get(index));
            if (caseBranch != null) {
               boolean matches = false;
               if (!inCase || !caseMatched) {
                  try {
                     matches = caseBranch.fallback() || evaluate(caseBranch.condition(), variables, definitions);
                  } catch (IllegalArgumentException var19) {
                     matches = false;
                  }
               }

               boolean lineActive = active && matches && (!inCase || !caseMatched);
               if (inCase && lineActive) {
                  caseMatched = true;
               }

               if (index == targetLine) {
                  return lineActive;
               }
            }
         } else {
            String name = directive.name();
            if (name.equals("replace") && index == targetLine) {
               String condition = replacementCondition((String)lines.get(index));

               try {
                  return condition != null && active && evaluate(condition, variables, definitions);
               } catch (IllegalArgumentException var18) {
                  return false;
               }
            }

            if (name.equals("case")) {
               inCase = true;
               caseMatched = false;
            } else if (name.equals("endcase")) {
               inCase = false;
               caseMatched = false;
            } else if (!name.equals("if") && !name.equals("ifdef") && !name.equals("ifndef")) {
               if (!name.equals("elseif") && !name.equals("elif")) {
                  if (name.equals("else")) {
                     if (!stack.isEmpty()) {
                        Branch previous = (Branch)stack.pop();
                        stack.push(new Branch(!previous.matched(), previous.matched(), true));
                        active = allActive(stack);
                     }
                  } else if (name.equals("endif")) {
                     if (!stack.isEmpty()) {
                        stack.pop();
                     }

                     active = allActive(stack);
                  }
               } else if (!stack.isEmpty()) {
                  Branch previous = (Branch)stack.pop();
                  boolean result = false;
                  if (!previous.matched()) {
                     try {
                        result = evaluate(directive.argument(), variables, definitions);
                     } catch (IllegalArgumentException var15) {
                        result = false;
                     }
                  }

                  stack.push(new Branch(result, previous.matched() || result, previous.elseSeen()));
                  active = allActive(stack);
               }
            } else {
               boolean result;
               try {
                  boolean var10000;
                  switch (name) {
                     case "ifdef" -> var10000 = variables.containsKey(directive.argument());
                     case "ifndef" -> var10000 = !variables.containsKey(directive.argument());
                     default -> var10000 = evaluate(directive.argument(), variables, definitions);
                  }

                  result = var10000;
               } catch (IllegalArgumentException var16) {
                  result = false;
               }

               stack.push(new Branch(result, result, false));
               active = allActive(stack);
            }
         }
      }

      return active;
   }

   private static CaseBranch parseCaseBranch(String line) {
      String trimmed = line.strip();
      int marker = trimmed.startsWith("//?") ? 0 : trimmed.lastIndexOf("//?");
      if (marker < 0) {
         return null;
      } else {
         String directive = trimmed.substring(marker + 3).strip();
         if (!directive.equals("else") && !directive.startsWith("else ")) {
            int separator = directive.indexOf(63);
            String condition = separator < 0 ? directive : directive.substring(0, separator).strip();
            return condition.isEmpty() ? null : new CaseBranch(condition, false);
         } else {
            return new CaseBranch("1", true);
         }
      }
   }

   private static String replacementCondition(String line) {
      int marker = line.indexOf("//#replace");
      if (marker < 0) {
         return null;
      } else {
         String directive = line.substring(marker + "//#replace".length()).strip();
         int separator = directive.indexOf(63);
         if (separator < 0) {
            return null;
         } else {
            String condition = directive.substring(0, separator).strip();
            return condition.isEmpty() ? null : condition;
         }
      }
   }

   static boolean isValidExpression(String expression) {
      try {
         Map<String, Integer> variables = new LinkedHashMap();
         variables.put("MC", 12104);
         Matcher names = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*").matcher(expression);

         while(names.find()) {
            String name = names.group();
            if (!name.equals("in") && !name.equals("not") && !name.equals("defined")) {
               variables.putIfAbsent(name, 1);
            }
         }

         evaluate(expression, variables, Map.of());
         return true;
      } catch (IllegalArgumentException var4) {
         return false;
      }
   }

   static boolean evaluate(String expression, Map<String, Integer> variables, Map<String, String> definitions) {
      if (expression != null && !expression.isBlank()) {
         String expanded = expandShorthand(expression.strip(), variables);
         expanded = expandDefined(expanded, variables, definitions);
         expanded = expandDefinitions(expanded, definitions);
         return (new ExpressionParser(expanded, variables)).parse();
      } else {
         throw new IllegalArgumentException("Empty expression");
      }
   }

   static int versionCode(String value) {
      String cleaned = value.replace("_", "");
      if (!cleaned.contains(".")) {
         try {
            return Integer.parseInt(cleaned);
         } catch (NumberFormatException var6) {
            return -1;
         }
      } else {
         String[] parts = cleaned.split("\\.");
         if (parts.length >= 2 && parts.length <= 3) {
            try {
               int major = Integer.parseInt(parts[0]);
               int minor = Integer.parseInt(parts[1]);
               int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
               return major * 10000 + minor * 100 + patch;
            } catch (NumberFormatException var7) {
               return -1;
            }
         } else {
            return -1;
         }
      }
   }

   static int moduleVersionCode(String moduleName) {
      Matcher matcher = VERSION_MODULE.matcher(moduleName);
      return matcher.find() ? versionCode(matcher.group(1)) : -1;
   }

   /**
    * The game version of the module a file belongs to - the one that file's code is built for.
    *
    * <p>Read from the module's name, which is where a multi-version project keeps it. What the versions folder
    * holds is the project's own list, and its first entry is not this file's version: such a project builds one
    * module per game version, and asking the list answered with the first of them. A branch written for 26.2 was
    * read as one no build takes, so its code was treated as carried and its import was written with a condition.
    */
   private static final Logger LOG = Logger.getInstance(PreprocessorLanguage.class);

   static int moduleVersionCode(@Nullable PsiFile context) {
      Module module = context == null ? null : ModuleUtilCore.findModuleForPsiElement(context);

      if (module == null) {
         return -1;
      }

      Matcher matcher = MODULE_VERSION.matcher(module.getName());
      int code = matcher.find() ? versionCode(matcher.group()) : -1;

      // Said out loud because the whole question of whether a branch is this build's code turns on it, and the
      // answer is read off a name.
      LOG.info("preprocessor module version: module=" + module.getName() + " code=" + code);

      return code;
   }

   /** The version a module's name carries, as in "Carpet-Igny-Addition.26.2.main". */
   private static final Pattern MODULE_VERSION = Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)?");

   static int mainVersionCode(@Nullable PsiFile context) {
      if (context == null) {
         return -1;
      } else {
         for(VirtualFile directory = context.getVirtualFile() == null ? null : context.getVirtualFile().getParent(); directory != null; directory = directory.getParent()) {
            VirtualFile versions = directory.findChild("versions");
            if (versions != null && versions.isDirectory()) {
               int code = mainVersionMarker(versions);
               if (code >= 0) {
                  return code;
               }
            }

            for(String name : List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) {
               VirtualFile graphFile = directory.findChild(name);
               if (graphFile != null && !graphFile.isDirectory()) {
                  int code = mainVersionCode(context, graphFile);
                  if (code >= 0) {
                     return code;
                  }
               }
            }
         }

         int fallback = -1;

         for(Module module : ModuleManager.getInstance(context.getProject()).getModules()) {
            int code = moduleVersionCode(module.getName());
            if (code >= 0) {
               fallback = fallback < 0 ? code : Math.min(fallback, code);
            }
         }

         return fallback;
      }
   }

   private static int mainVersionMarker(VirtualFile versionsDirectory) {
      VirtualFile marker = versionsDirectory.findChild("mainProject");
      if (marker != null && !marker.isDirectory()) {
         try {
            return versionCode(VfsUtilCore.loadText(marker).strip());
         } catch (IOException var3) {
            return -1;
         }
      } else {
         return -1;
      }
   }

   private static int mainVersionCode(PsiFile context, VirtualFile graphFile) {
      String text;
      try {
         PsiFile psiFile = PsiManager.getInstance(context.getProject()).findFile(graphFile);
         Document document = psiFile == null ? null : PsiDocumentManager.getInstance(context.getProject()).getDocument(psiFile);
         text = document == null ? VfsUtilCore.loadText(graphFile) : document.getText();
      } catch (IOException var10) {
         return -1;
      }

      Map<String, Integer> nodes = new LinkedHashMap();
      Matcher node = GRAPH_NODE.matcher(text);

      while(node.find()) {
         try {
            nodes.putIfAbsent(node.group(1), Integer.parseInt(node.group(2).replace("_", "")));
         } catch (NumberFormatException var9) {
         }
      }

      if (nodes.isEmpty()) {
         return -1;
      } else {
         Set<String> incoming = new LinkedHashSet();
         Matcher link = GRAPH_LINK.matcher(text);

         while(link.find()) {
            incoming.add(link.group(2));
         }

         for(Map.Entry<String, Integer> entry : nodes.entrySet()) {
            if (!incoming.contains(entry.getKey())) {
               return (Integer)entry.getValue();
            }
         }

         return (Integer)nodes.values().iterator().next();
      }
   }

   private static Map<String, String> collectDefinitions(List<String> lines) {
      Map<String, String> result = new LinkedHashMap();

      for(String line : lines) {
         Matcher matcher = DEFINE.matcher(line);
         if (matcher.matches()) {
            result.putIfAbsent(matcher.group(1), matcher.group(2));
         }
      }

      return result;
   }

   private static boolean isDefined(String name, Map<String, Integer> variables, Map<String, String> definitions) {
      return variables.containsKey(name.strip()) || definitions.containsKey(name.strip());
   }

   private static String expandShorthand(String text, Map<String, Integer> variables) {
      if (!text.isEmpty() && !Character.isLetter(text.charAt(0)) && text.charAt(0) != '_' && text.charAt(0) != '!' && text.charAt(0) != '(') {
         String primary = variables.containsKey("MC") ? "MC" : (variables.size() == 1 ? (String)variables.keySet().iterator().next() : null);
         if (primary == null) {
            return text;
         } else if (BARE_RANGE.matcher(text).matches()) {
            return primary + " in " + text;
         } else if (BARE_COMPARISON.matcher(text).matches()) {
            return primary + " " + text;
         } else {
            return BARE_VERSION.matcher(text).matches() && text.contains(".") ? primary + " == " + text : text;
         }
      } else {
         return text;
      }
   }

   private static String expandDefined(String text, Map<String, Integer> variables, Map<String, String> definitions) {
      Matcher matcher = DEFINED.matcher(text);
      StringBuffer result = new StringBuffer();

      while(matcher.find()) {
         matcher.appendReplacement(result, isDefined(matcher.group(1), variables, definitions) ? "1" : "0");
      }

      matcher.appendTail(result);
      return result.toString();
   }

   private static String expandDefinitions(String text, Map<String, String> definitions) {
      String result = text;

      for(int pass = 0; pass < 8; ++pass) {
         StringBuilder expanded = new StringBuilder();
         boolean changed = false;
         int index = 0;

         while(index < result.length()) {
            char value = result.charAt(index);
            if (!Character.isLetter(value) && value != '_') {
               expanded.append(value);
               ++index;
            } else {
               int end;
               for(end = index + 1; end < result.length() && (Character.isLetterOrDigit(result.charAt(end)) || result.charAt(end) == '_'); ++end) {
               }

               String word = result.substring(index, end);
               String replacement = (String)definitions.get(word);
               if (replacement == null) {
                  expanded.append(word);
               } else {
                  expanded.append('(').append(replacement).append(')');
                  changed = true;
               }

               index = end;
            }
         }

         result = expanded.toString();
         if (!changed) {
            return result;
         }
      }

      throw new IllegalArgumentException("Cyclic definition");
   }

   private static boolean allActive(Deque<Branch> stack) {
      for(Branch branch : stack) {
         if (!branch.active()) {
            return false;
         }
      }

      return true;
   }

   private static String lineText(Document document, int line) {
      return document.getImmutableCharSequence().subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString();
   }

   static {
      LEGACY_DIRECTIVES = List.of(new Directive("if", "Start a conditional block", PreprocessorLanguage.Argument.EXPRESSION), new Directive("ifdef", "Start a block when a variable is defined", PreprocessorLanguage.Argument.VARIABLE), new Directive("elseif", "Add another conditional branch", PreprocessorLanguage.Argument.EXPRESSION), new Directive("else", "Add a fallback branch", PreprocessorLanguage.Argument.NONE), new Directive("endif", "End a conditional block", PreprocessorLanguage.Argument.NONE), new Directive("disable-remap", "Disable remapping for following source", PreprocessorLanguage.Argument.NONE), new Directive("enable-remap", "Enable remapping again", PreprocessorLanguage.Argument.NONE));
      LIUYUE_DIRECTIVES = List.of(new Directive("if", "Start a conditional block", PreprocessorLanguage.Argument.EXPRESSION), new Directive("ifdef", "Start a block when a variable is defined", PreprocessorLanguage.Argument.VARIABLE), new Directive("ifndef", "Start a block when a variable is not defined", PreprocessorLanguage.Argument.VARIABLE), new Directive("elseif", "Add another conditional branch", PreprocessorLanguage.Argument.EXPRESSION), new Directive("elif", "Alias for elseif", PreprocessorLanguage.Argument.EXPRESSION), new Directive("else", "Add a fallback branch", PreprocessorLanguage.Argument.NONE), new Directive("endif", "End a conditional block", PreprocessorLanguage.Argument.NONE), new Directive("define", "Define a reusable condition alias", PreprocessorLanguage.Argument.DEFINITION), new Directive("error", "Fail preprocessing when this line is active", PreprocessorLanguage.Argument.MESSAGE), new Directive("warn", "Print a warning when this line is active", PreprocessorLanguage.Argument.MESSAGE), new Directive("replace", "Replace the preceding code when a condition matches", PreprocessorLanguage.Argument.REPLACEMENT), new Directive("case", "Start a first-matching case group", PreprocessorLanguage.Argument.OPTIONAL), new Directive("endcase", "End a case group", PreprocessorLanguage.Argument.NONE), new Directive("disable-remap", "Disable remapping for following source", PreprocessorLanguage.Argument.NONE), new Directive("enable-remap", "Enable remapping again", PreprocessorLanguage.Argument.NONE));
      DIRECTIVES = Stream.concat(LIUYUE_DIRECTIVES.stream(), Stream.of(new Directive("import", "Add an import from an active conditional branch", PreprocessorLanguage.Argument.IMPORT))).toList();
      DIRECTIVE = Pattern.compile("^\\s*(?://\\$\\$\\s*)*//#([A-Za-z-]+)(?:\\s+(.*?))?\\s*$");
      DEFINE = Pattern.compile("^\\s*//#define\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s+(.+?)\\s*$");
      VERSION_MODULE = Pattern.compile("(?:^|[^0-9A-Za-z])([0-9]+(?:\\.[0-9]+){1,2})(?:\\.main)?$");
      DEFINED = Pattern.compile("defined\\s*\\(\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\)");
      BARE_COMPARISON = Pattern.compile("(?:>=|<=|==|!=|>|<)\\s*[0-9][\\w.]*");
      BARE_VERSION = Pattern.compile("[0-9][\\w.]*");
      BARE_RANGE = Pattern.compile("[0-9][\\w.]*\\.\\.[0-9][\\w.]*");
      GRAPH_NODE = Pattern.compile("(?:def|val|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)[^=]*=\\s*createNode\\s*\\(\\s*['\"][^'\"]+['\"]\\s*,\\s*([0-9_]+)");
      GRAPH_LINK = Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\.\\s*link\\s*\\(\\s*([A-Za-z_$][A-Za-z0-9_$]*)");
   }

   static enum Argument {
      NONE,
      EXPRESSION,
      VARIABLE,
      DEFINITION,
      MESSAGE,
      REPLACEMENT,
      OPTIONAL,
      IMPORT;

      // $FF: synthetic method
      private static Argument[] $values() {
         return new Argument[]{NONE, EXPRESSION, VARIABLE, DEFINITION, MESSAGE, REPLACEMENT, OPTIONAL, IMPORT};
      }
   }

   static record Directive(String name, String description, Argument argument) {
   }

   static record ParsedDirective(String name, String argument) {
   }

   private static record Branch(boolean active, boolean matched, boolean elseSeen) {
   }

   private static record CaseBranch(String condition, boolean fallback) {
   }

   private static final class ExpressionParser {
      private final String source;
      private final Map<String, Integer> variables;
      private int position;

      private ExpressionParser(String source, Map<String, Integer> variables) {
         this.source = source;
         this.variables = variables;
      }

      private boolean parse() {
         boolean result = this.parseOr(true);
         this.whitespace();
         if (this.position != this.source.length()) {
            throw new IllegalArgumentException(this.source);
         } else {
            return result;
         }
      }

      private boolean parseOr(boolean evaluate) {
         boolean left = this.parseAnd(evaluate);

         while(true) {
            this.whitespace();
            if (!this.source.startsWith("||", this.position)) {
               return left;
            }

            this.position += 2;
            boolean right = this.parseAnd(evaluate && !left);
            left = evaluate && (left || right);
         }
      }

      private boolean parseAnd(boolean evaluate) {
         boolean left = this.parseUnary(evaluate);

         while(true) {
            this.whitespace();
            if (!this.source.startsWith("&&", this.position)) {
               return left;
            }

            this.position += 2;
            boolean right = this.parseUnary(evaluate && left);
            left = evaluate && left && right;
         }
      }

      private boolean parseUnary(boolean evaluate) {
         this.whitespace();
         if (this.position >= this.source.length()) {
            throw new IllegalArgumentException(this.source);
         } else if (this.source.charAt(this.position) == '!') {
            ++this.position;
            boolean value = this.parseUnary(evaluate);
            return evaluate && !value;
         } else if (this.source.charAt(this.position) == '(') {
            ++this.position;
            boolean value = this.parseOr(evaluate);
            this.whitespace();
            if (this.position < this.source.length() && this.source.charAt(this.position++) == ')') {
               return value;
            } else {
               throw new IllegalArgumentException(this.source);
            }
         } else {
            int start = this.position;

            for(int bracketDepth = 0; this.position < this.source.length(); ++this.position) {
               char value = this.source.charAt(this.position);
               if (value == '[') {
                  ++bracketDepth;
               } else if (value == ']') {
                  --bracketDepth;
               }

               if (bracketDepth == 0 && (value == ')' || this.source.startsWith("&&", this.position) || this.source.startsWith("||", this.position))) {
                  break;
               }
            }

            String atom = this.source.substring(start, this.position).strip();
            if (atom.isEmpty()) {
               throw new IllegalArgumentException(this.source);
            } else {
               return evaluate && this.evaluateAtom(atom);
            }
         }
      }

      private boolean evaluateAtom(String atom) {
         Matcher set = Pattern.compile("(.+?)\\s+(not\\s+)?in\\s+\\[(.+)]").matcher(atom);
         if (set.matches()) {
            int left = this.value(set.group(1));
            boolean inside = false;

            for(String item : set.group(3).split(",")) {
               inside |= this.value(item) == left;
            }

            return set.group(2) == null ? inside : !inside;
         } else {
            Matcher range = Pattern.compile("(.+?)\\s+(not\\s+)?in\\s+(.+?)\\.\\.(.+)").matcher(atom);
            if (!range.matches()) {
               Matcher comparison = Pattern.compile("(.+?)(==|!=|<=|>=|<|>)(.+)").matcher(atom);
               if (comparison.matches()) {
                  int left = this.value(comparison.group(1));
                  int right = this.value(comparison.group(3));
                  boolean var10000;
                  switch (comparison.group(2)) {
                     case "==" -> var10000 = left == right;
                     case "!=" -> var10000 = left != right;
                     case "<=" -> var10000 = left <= right;
                     case ">=" -> var10000 = left >= right;
                     case "<" -> var10000 = left < right;
                     case ">" -> var10000 = left > right;
                     default -> var10000 = false;
                  }

                  return var10000;
               } else {
                  return this.value(atom) != 0;
               }
            } else {
               int left = this.value(range.group(1));
               boolean inside = left >= this.value(range.group(3)) && left < this.value(range.group(4));
               return range.group(2) == null ? inside : !inside;
            }
         }
      }

      private int value(String token) {
         String cleaned = token.strip();
         Integer variable = (Integer)this.variables.get(cleaned);
         if (variable != null) {
            return variable;
         } else {
            int number = PreprocessorLanguage.versionCode(cleaned);
            if (number >= 0) {
               return number;
            } else {
               throw new IllegalArgumentException("Unknown value " + cleaned);
            }
         }
      }

      private void whitespace() {
         while(this.position < this.source.length() && Character.isWhitespace(this.source.charAt(this.position))) {
            ++this.position;
         }

      }
   }
}
