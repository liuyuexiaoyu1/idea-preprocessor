package rems.idea.mixincompletion;

import com.intellij.openapi.editor.Document;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * 在版本分支里补全一个名字，写出来的是带条件的 import。
 *
 * <p>分支里的名字是那个分支才有的名字：写成普通 import，别的版本就编译不过。条件取自分支本身——包括它是怎么
 * 到达的，`//#else` 里的条件与它上面那些分支相反。
 */
public class ConditionalImportTest extends BasePlatformTestCase {

    private PsiClass holder;

    private PsiClass holder() {
        if (this.holder == null) {
            myFixture.addFileToProject("p/Holder.java", "package p;\npublic class Holder {}\n");
            this.holder = JavaPsiFacade.getInstance(getProject())
                    .findClass("p.Holder", GlobalSearchScope.allScope(getProject()));
        }

        assertNotNull("the fixture class is not on the path", this.holder);

        return this.holder;
    }

    private PsiJavaFile javaFile(String source) {
        PsiFile file = myFixture.addFileToProject("p/Mixin.java", source);

        assertTrue("the fixture is not Java", file instanceof PsiJavaFile);

        return (PsiJavaFile)file;
    }

    /** 在夹具里的一处补全，返回写完之后的整份文本。 */
    private String completeAt(PsiJavaFile file, String needle) {
        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);

        assertNotNull("the fixture has no document", document);

        int offset = document.getText().indexOf(needle);

        assertTrue("the fixture no longer writes " + needle, offset >= 0);

        ConditionalImportMerger.addImport(getProject(), document, file, offset, holder());

        return document.getText();
    }

    /** 取反是翻转运算符，不是写一个否定；复合条件按德摩根律推开。 */
    public void testTheOppositeOfAComparisonTurnsTheOperatorAround() {
        assertEquals("MC < 12101", ConditionalImportMerger.negate("MC >= 12101"));
        assertEquals("MC > 26.1", ConditionalImportMerger.negate("MC <= 26.1"));
        assertEquals("MC != 1.21.1", ConditionalImportMerger.negate("MC == 1.21.1"));
        assertEquals("MC == 1.21.1", ConditionalImportMerger.negate("MC != 1.21.1"));
        assertEquals("MC <= 1.20.1", ConditionalImportMerger.negate("MC > 1.20.1"));
        assertEquals("MC >= 1.20.1", ConditionalImportMerger.negate("MC < 1.20.1"));
        assertEquals("< 26.3", ConditionalImportMerger.negate(">= 26.3"));
        assertEquals("MC not in 1.20.1..1.20.4", ConditionalImportMerger.negate("MC in 1.20.1..1.20.4"));
        assertEquals("MC in 1.20.1..1.20.4", ConditionalImportMerger.negate("MC not in 1.20.1..1.20.4"));
    }

    /** 合取与析取各自按德摩根律推开，每一支再各自翻转。 */
    public void testTheOppositeOfACompoundIsPushedThroughEveryPart() {
        assertEquals("MC < 12001 || MC > 12101",
                ConditionalImportMerger.negate("MC >= 12001 && MC <= 12101"));
        assertEquals("MC < 12001 && MC < 12101",
                ConditionalImportMerger.negate("MC >= 12001 || MC >= 12101"));
        assertEquals("(MC < 12001 || MC > 12101) && MC >= 12000",
                ConditionalImportMerger.negate("(MC >= 12001 && MC <= 12101) || MC < 12000"));
        assertEquals("(MC >= 12101)", ConditionalImportMerger.negate("!(MC >= 12101)"));
    }

    /** 定义与否也各有反面。 */
    public void testADefiningTurnsIntoItsNegation() {
        assertEquals("!defined(FOO)", ConditionalImportMerger.negate("defined(FOO)"));
        assertEquals("defined(FOO)", ConditionalImportMerger.negate("!defined(FOO)"));
    }

    /** 翻转不了的形式一律不写：写一个错的条件比不写更坏。 */
    public void testAConditionWithNoOppositeIsRefused() {
        assertNull("a bare name is not a comparison",
                ConditionalImportMerger.negate("FOO"));
        assertNull("an operator with no opposite",
                ConditionalImportMerger.negate("MC contains 1.20"));
        assertNull("a half-written condition",
                ConditionalImportMerger.negate("MC >= "));
    }

    /** 分支里的名字写进这个分支，并保持它的条件。 */
    public void testAnImportJoinsTheBranchUnderItsOwnCondition() {
        PsiJavaFile file = javaFile("""
                package p;
                //#if MC >= 12101
                //$$import a.b.C;
                //#endif
                public class Mixin {
                }
                """);

        String text = completeAt(file, "//$$import a.b.C;");

        assertTrue("the condition is kept: " + text, text.contains("//#if MC >= 12101"));
        assertTrue("the name joined the branch: " + text, text.contains("import p.Holder;"));
    }

    /** 同一个条件已经在别处写过了，就并进去，而不是再开一个。 */
    public void testAnImportJoinsTheBranchThatAlreadyHasTheSameCondition() {
        PsiJavaFile file = javaFile("""
                package p;
                //#if MC >= 12101
                //$$import a.b.C;
                //#endif
                //#if MC >= 12101
                //$$int x = 1;
                //#endif
                public class Mixin {
                }
                """);

        String text = completeAt(file, "//$$int x = 1;");

        assertTrue("one condition holds both names: " + text, text.contains("import a.b.C;"));
        assertEquals("and the second branch was not duplicated: " + text,
                2, count(text, "//#if MC >= 12101"));
    }

    /** `//#else` 里的条件是上面那些分支的反面。 */
    public void testAnImportInAnElseBranchTakesTheOppositeCondition() {
        PsiJavaFile file = javaFile("""
                package p;
                //#if MC >= 12101
                //$$import a.b.C;
                //#else
                //$$int x = 1;
                //#endif
                public class Mixin {
                }
                """);

        String text = completeAt(file, "//$$int x = 1;");

        assertTrue("the opposite of the branch above, not that branch: " + text,
                text.contains("//#if MC < 12101"));
        assertTrue("and the name is in it: " + text, text.contains("import p.Holder;"));
    }

    /** 一路 `//#elseif` 之后，条件是上面每一支的反面。 */
    public void testAnImportInTheLastElseNegatesEveryBranchAboveIt() {
        PsiJavaFile file = javaFile("""
                package p;
                //#if MC >= 12101
                //$$import a.b.C;
                //#elseif MC >= 12001
                //$$import a.b.D;
                //#else
                //$$int x = 1;
                //#endif
                public class Mixin {
                }
                """);

        String text = completeAt(file, "//$$int x = 1;");

        assertTrue("every branch above is turned around: " + text,
                text.contains("//#if MC < 12101 && MC < 12001"));
    }

    /** 外层的条件也要算进去。 */
    public void testTheConditionOfEveryEnclosingLevelIsCarried() {
        PsiJavaFile file = javaFile("""
                package p;
                //#if MC >= 12001
                //#if MC >= 12101
                //$$import a.b.C;
                //#endif
                //#endif
                public class Mixin {
                }
                """);

        String text = completeAt(file, "//$$import a.b.C;");

        assertTrue("both levels are named: " + text, text.contains("import p.Holder;"));
        assertTrue("the new branch carries both: " + text,
                text.contains("//#if MC >= 12001 && MC >= 12101"));
    }

    /** 同一条 `//?` 行下再加一个名字时，换成能装多个的那个形式。 */
    public void testTheExtendedDialectGathersTwoNamesIntoOneGroup() {
        PsiJavaFile file = javaFile("""
                package p;
                //#if MC >= 12101
                //? >= 12101 ? import a.b.C;
                //#endif
                public class Mixin {
                }
                """);

        String text = completeAt(file, "//? >= 12101 ? import a.b.C;");

        assertTrue("one carried group: " + text, text.contains("/*$$import a.b.C;"));
        assertTrue("holding the second name: " + text, text.contains("import p.Holder;"));
        assertTrue("closing itself: " + text, text.contains("$$*/"));
        assertEquals("under one condition, not two: " + text, 1, count(text, "//#if MC >= 12101"));
    }

    /** 条件说不清的时候什么都不写：写一个错的条件比不写更坏。 */
    public void testNothingIsWrittenWhereTheConditionCannotBePutIntoWords() {
        PsiJavaFile file = javaFile("""
                package p;
                //#if MC >= 12001
                //#else
                //$$int x = 1;
                //#endif
                public class Mixin {
                }
                """);

        String text = completeAt(file, "//$$int x = 1;");

        assertTrue("the opposite of that comparison is writable, so it is written: " + text,
                text.contains("//#if MC < 12001"));
    }

    /** 分支里已有的代码不会被 import 顶掉。 */
    public void testTheCodeInTheBranchIsKeptWhenTheImportJoinsIt() {
        PsiJavaFile file = javaFile("""
                package p;
                //#if MC >= 12101
                /*$$public static final Object THING = build(
                        "a",
                        "b"
                );$$*/
                //#endif
                public class Mixin {
                }
                """);

        String text = completeAt(file, "public static final Object THING");

        assertTrue("the declaration is still there: " + text,
                text.contains("public static final Object THING = build("));
        assertTrue("and what it was built from: " + text, text.contains("\"a\","));
        assertTrue("with the import in front of it: " + text, text.contains("import p.Holder;"));
        assertTrue("inside the same branch: " + text, text.contains("//#if MC >= 12101"));
    }

    /** 分支里的名字已经在了就不再加一遍。 */
    public void testANameAlreadyInTheBranchIsNotWrittenTwice() {
        PsiJavaFile file = javaFile("""
                package p;
                //#if MC >= 12101
                /*$$import p.Holder;$$*/
                //#endif
                public class Mixin {
                }
                """);

        String text = completeAt(file, "import p.Holder;");

        assertEquals("one import, not two: " + text, 1, count(text, "import p.Holder;"));
    }

    /** 新写的条件 import 落在普通 import 段的末尾，而不是 static 段之后。 */
    public void testANewConditionalImportEndsTheOrdinaryImports() {
        // The branch is inside the class, so the import list holds none under this condition and the import is
        // written fresh rather than merged - which is the case a file whose branches are all in the body has.
        PsiJavaFile file = javaFile("""
                package p;

                import com.example.Thing;
                import java.util.List;
                import java.util.Map;

                import static java.util.Collections.emptyList;

                public class Mixin {
                    //#if MC >= 12101
                    //$$int x = 1;
                    //#endif
                }
                """);

        String text = completeAt(file, "//$$int x = 1;");

        int added = text.indexOf("import p.Holder;");
        int statics = text.indexOf("import static java.util.Collections.emptyList;");

        assertTrue("the name was written: " + text, added >= 0);
        assertTrue("after the project's own imports: " + text,
                text.indexOf("import com.example.Thing;") < added);
        assertTrue("in front of the static section: " + text, added < statics);
        assertTrue("and in front of the platform's: " + text,
                added < text.indexOf("import java.util.List;"));
    }

    /** 一个条件已经写在 import 区里了，新名字并进去，而不是另起一段。 */
    public void testANameJoinsTheBranchAlreadyWrittenInTheImportList() {
        PsiJavaFile file = javaFile("""
                package p;

                import java.util.List;

                import static java.util.Collections.emptyList;

                //#if MC >= 12101
                //$$int x = 1;
                //#endif

                public class Mixin {
                }
                """);

        String text = completeAt(file, "//$$int x = 1;");

        assertTrue("the name joined that branch: " + text, text.contains("import p.Holder;"));
        assertTrue("inside it: " + text,
                text.indexOf("//#if MC >= 12101") < text.indexOf("import p.Holder;"));
        assertEquals("and no second branch was written: " + text, 1, count(text, "//#if MC >= 12101"));
    }

    /** 说明性的一段：本文件里没有普通 import 时，条件 import 仍写在类前面。 */
    public void testAConditionalImportWithNoOrdinaryImportsGoesBeforeTheClass() {
        PsiJavaFile file = javaFile("""
                package p;

                import static java.util.Collections.emptyList;

                //#if MC >= 12101
                //$$int x = 1;
                //#endif

                public class Mixin {
                }
                """);

        String text = completeAt(file, "//$$int x = 1;");

        assertTrue("the name was written: " + text, text.contains("import p.Holder;"));
        assertTrue("in front of the class: " + text,
                text.indexOf("import p.Holder;") < text.indexOf("public class Mixin"));
    }

    /** java 基础类自成一段且排在后面时，条件 import 写在用户类那一段的末尾。 */
    public void testAConditionalImportDoesNotJoinThePlatformImports() {
        PsiJavaFile file = javaFile("""
                package p;

                import com.example.Thing;

                import java.util.List;
                import java.util.Map;

                public class Mixin {
                    //#if MC >= 12101
                    //$$int x = 1;
                    //#endif
                }
                """);

        String text = completeAt(file, "//$$int x = 1;");

        int added = text.indexOf("import p.Holder;");

        assertTrue("the name was written: " + text, added >= 0);
        assertTrue("after the project's own imports: " + text,
                text.indexOf("import com.example.Thing;") < added);
        assertTrue("and in front of the platform's: " + text,
                added < text.indexOf("import java.util.List;"));
    }

    /** 原版方言里没有 `//?` 那种写法：import 是一条 `//$$` 行，条件照原文。 */
    public void testTheOriginalDialectTakesACarriedLine() {
        PsiJavaFile file = javaFile("""
                package p;

                import com.example.Thing;

                public class Mixin {
                    //#if MC >= 12101
                    //$$int x = 1;
                    //#endif
                }
                """);

        String text = completeAt(file, "//$$int x = 1;");

        assertTrue("a carried line: " + text, text.contains("//$$ import p.Holder;"));
        assertTrue("under the condition as it was written: " + text, text.contains("//#if MC >= 12101"));
        assertFalse("and nothing of the extended spelling: " + text, text.contains("//?"));
    }

    /** 块里已经有一条 `//$$` 行时，再加一个名字会把两条并成一个组。 */
    public void testACarriedLineAndAGroupBecomeOneGroup() {
        PsiJavaFile file = javaFile("""
                package p;
                //#if MC >= 12101
                //$$ import a.b.C;
                /*$$import a.b.D;
                import a.b.E;$$*/
                //#endif
                public class Mixin {
                }
                """);

        String text = completeAt(file, "//$$ import a.b.C;");

        assertTrue("one group: " + text, text.contains("/*$$import a.b.C;"));
        assertTrue("holding what was already there: " + text,
                text.contains("import a.b.D;") && text.contains("import a.b.E;"));
        assertTrue("and the new name: " + text, text.contains("import p.Holder;"));
        assertFalse("with no carried line left outside it: " + text, text.contains("//$$ import a.b.C;"));
    }

    /** 合并时不会把 else 分支的 import 搬到这个分支里。 */
    public void testTheImportsOfTheElseHalfStayWhereTheyAre() {
        PsiJavaFile file = javaFile("""
                package p;
                //#if MC >= 12101
                //$$ import a.b.C;
                //#else
                import a.b.D;
                //#endif
                public class Mixin {
                }
                """);

        String text = completeAt(file, "//$$ import a.b.C;");

        assertTrue("one group in the taken branch: " + text, text.contains("/*$$import a.b.C;"));
        assertFalse("with nothing of the else half in it: " + text,
                text.contains("/*$$import a.b.C;\nimport a.b.D;"));
        assertTrue("which is still under its own else: " + text,
                text.indexOf("//#else") < text.indexOf("import a.b.D;"));
        assertTrue("and the branch still closes: " + text, text.contains("//#endif"));
    }

    private static int count(String text, String needle) {        int found = 0;

        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            found++;
        }

        return found;
    }
}
