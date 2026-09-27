package rems.idea.mixincompletion;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * What the reader is offered while typing in code a marker carries.
 *
 * <p>Carried code is a comment as far as the platform is concerned, so nothing offers anything there by
 * itself: the names have to be worked out from the code inside the marker. Nothing here had a test, and a
 * completion that stops working is a completion nobody notices is gone until they are typing in it.
 *
 * <p>Every test here asks for two names and looks for both of them in the list. One name is not a list: the
 * platform inserts a single candidate and shows no lookup at all, which reads exactly like a completion that
 * did not run.
 */
public class CarriedCodeCompletionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        myFixture.addFileToProject("p/Holder.java", "package p;\n\npublic class Holder {\n    public static void call() {}\n}\n");
        myFixture.addFileToProject("p/Holding.java", "package p;\n\npublic class Holding {}\n");
    }

    /** Class names are offered in a carried line. */
    public void testNamesAreOfferedInACarriedLine() {
        myFixture.configureByText("Jump.java", """
                package p;

                public class Jump {
                    static void use() {
                        //$$ Hold<caret>
                        int live = 1;
                    }
                }
                """);

        myFixture.completeBasic();

        assertNotNull("the completion ran at all", myFixture.getLookupElementStrings());
        assertTrue("the names are offered: " + myFixture.getLookupElementStrings(),
                myFixture.getLookupElementStrings().containsAll(java.util.List.of("Holder", "Holding")));
    }

    /** And in a block a marker carries. */
    public void testNamesAreOfferedInACarriedBlock() {
        myFixture.configureByText("Block.java", """
                package p;

                public class Block {
                    static void use() {
                        /*$$
                        Hold<caret>
                        $$*/
                        int live = 1;
                    }
                }
                """);

        myFixture.completeBasic();

        assertNotNull("the completion ran at all", myFixture.getLookupElementStrings());
        assertTrue("the names are offered: " + myFixture.getLookupElementStrings(),
                myFixture.getLookupElementStrings().containsAll(java.util.List.of("Holder", "Holding")));
    }

    /** A class name being typed in a carried import is offered. */
    public void testNamesAreOfferedInACarriedImport() {
        myFixture.configureByText("Importing.java", """
                package p;

                public class Importing {
                    static void use() {
                        //$$ import p.Hold<caret>
                        int live = 1;
                    }
                }
                """);

        myFixture.completeBasic();

        assertNotNull("the completion ran at all", myFixture.getLookupElementStrings());
        assertTrue("the names are offered: " + myFixture.getLookupElementStrings(),
                myFixture.getLookupElementStrings().containsAll(java.util.List.of("Holder", "Holding")));
    }

    /** 块里已经有两行 import 了，第三行照样给得出包和类。 */
    public void testNamesAreOfferedAfterOtherImportsInTheBlock() {
        myFixture.addFileToProject("p/Hold.java", "package p;\npublic class Hold {}\n");
        myFixture.addFileToProject("p/Holdable.java", "package p;\npublic class Holdable {}\n");
        myFixture.addFileToProject("q/Other.java", "package q;\npublic class Other {}\n");

        myFixture.configureByText("Importing.java", """
                package p;

                public class Importing {
                    static void use() {
                        /*$$import p.Hold;
                        import q.Other;
                        import p.Hol<caret>$$*/
                        int live = 1;
                    }
                }
                """);

        myFixture.completeBasic();

        assertNotNull("the completion ran at all", myFixture.getLookupElementStrings());
        assertTrue("the names under that prefix: " + myFixture.getLookupElementStrings(),
                myFixture.getLookupElementStrings().contains("Holdable"));
    }

    /** A conditional line offers something to write the condition with. */
    public void testConditionalLineOffersSomething() {
        myFixture.configureByText("Conditional.java", """
                package p;

                public class Conditional {
                    static void use() {
                        //#if <caret>
                        int live = 1;
                        //#endif
                    }
                }
                """);

        myFixture.completeBasic();

        assertNotNull("the completion ran at all", myFixture.getLookupElementStrings());
        assertFalse("and the list is not empty: a condition needs something to write it with",
                myFixture.getLookupElementStrings().isEmpty());
    }

    /** A condition offers the versions the project is built against. */
    public void testVersionsAreOfferedInACondition() {
        myFixture.addFileToProject("build.gradle", """
                preprocess {
                    def mc1201 = createNode('1.20.1', 1_20_01, '')
                    def mc1211 = createNode('1.21.1', 1_21_01, '')
                }
                """);

        myFixture.configureByText("Versioned.java", """
                package p;

                public class Versioned {
                    static void use() {
                        //#if >= <caret>
                        int live = 1;
                        //#endif
                    }
                }
                """);

        myFixture.completeBasic();

        assertNotNull("the completion ran at all", myFixture.getLookupElementStrings());
        assertTrue("the versions are offered: " + myFixture.getLookupElementStrings(),
                myFixture.getLookupElementStrings().contains("1.20.1")
                        || myFixture.getLookupElementStrings().contains("1.21.1"));
    }

    /**
     * A block of several lines is completed on each of them.
     *
     * <p>The shape a block is actually written in: the code starts on the line after the opening marker and the
     * closing marker sits at the end of the last line of code, so the middle lines have no marker of their own
     * and the last one has code on both sides. A block of one line says nothing about any of that.
     */
    public void testNamesAreOfferedOnEveryLineOfABlock() {
        myFixture.configureByText("Many.java", """
                package p;

                public class Many {
                    static void use() {
                        /*$$
                        int green = 1;
                        Hold<caret>
                        int orange = 2;$$*/
                        int live = 3;
                    }
                }
                """);

        myFixture.completeBasic();

        assertNotNull("the completion ran at all", myFixture.getLookupElementStrings());
        assertTrue("a name is offered on the middle line: " + myFixture.getLookupElementStrings(),
                myFixture.getLookupElementStrings().containsAll(java.util.List.of("Holder", "Holding")));
    }

    /** And on the last line of a block, where the closing marker follows the code. */
    public void testNamesAreOfferedOnTheLastLineOfABlock() {
        myFixture.configureByText("Last.java", """
                package p;

                public class Last {
                    static void use() {
                        /*$$
                        int green = 1;
                        int gray = 2;
                        Hold<caret>int orange = 3;$$*/
                        int live = 4;
                    }
                }
                """);

        myFixture.completeBasic();

        assertNotNull("the completion ran at all", myFixture.getLookupElementStrings());
        assertTrue("a name is offered on the last line: " + myFixture.getLookupElementStrings(),
                myFixture.getLookupElementStrings().containsAll(java.util.List.of("Holder", "Holding")));
    }

    /** A member of a name already typed is offered after the dot. */
    public void testMemberIsOfferedAfterAQualifier() {
        myFixture.configureByText("Qualified.java", """
                package p;

                public class Qualified {
                    static void use() {
                        //$$ Holder.<caret>
                        int live = 1;
                    }
                }
                """);

        myFixture.completeBasic();

        assertNotNull("the completion ran at all", myFixture.getLookupElementStrings());
        assertTrue("the member is offered: " + myFixture.getLookupElementStrings(),
                myFixture.getLookupElementStrings().contains("call"));
    }
}
