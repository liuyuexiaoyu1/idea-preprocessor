package rems.idea.mixincompletion;

import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where carried code is, read from the markers alone.
 *
 * <p>Both the highlighting filter and the inspection suppressor ask this one question, so a form that is not
 * recognised here is a form whose findings are shown on a line that is comment as far as the host file is
 * concerned. That is how a lone {@code import} and a {@code //#replace} tail came to be underlined: the
 * fragments are real Java to the platform and not complete compilation units, so what it reports about them
 * is a syntax error about text that was never going to parse on its own.
 */
public class CarriedCodeTest extends BasePlatformTestCase {

    /** Every form that hides code has to be recognised, or the findings about it get through. */
    public void testEveryFormIsRecognised() {
        PsiFile file = myFixture.addFileToProject("p/Carried.java", """
                package p;
                public class Carried {
                    //#if >= 1.20
                    /*$$import p.Missing;
                    import p.AlsoMissing;$$*/
                    //#endif
                    static void use() {
                        int live = 1; //#replace < 1.20 ? int other = 2;
                        //$$int carried = 3;
                        //? >= 1.21 ? int caseBody = 4;
                    }
                }
                """);

        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);

        assertNotNull("the fixture has no document", document);

        String text = document.getText();

        assertTrue("the line a block opens on",
                MixinCommentContext.isHiddenOffset(document, text.indexOf("p.Missing")));
        assertTrue("the line a block closes on",
                MixinCommentContext.isHiddenOffset(document, text.indexOf("p.AlsoMissing")));
        assertTrue("the body of a //#replace",
                MixinCommentContext.isHiddenOffset(document, text.indexOf("int other")));
        assertTrue("a //$$ line",
                MixinCommentContext.isHiddenOffset(document, text.indexOf("int carried")));
        assertTrue("the body of a //? case",
                MixinCommentContext.isHiddenOffset(document, text.indexOf("int caseBody")));
        assertFalse("live code in front of a marker",
                MixinCommentContext.isHiddenOffset(document, text.indexOf("int live")));
    }

    /**
     * 块的闭合标记是标记，不是被藏起来的代码。
     *
     * <p>四个字符里第一个曾经读成"被藏起来的"：判断问的是"这个位置起还能不能找到闭合标记"，而标记自己正好满足这个
     * 条件。于是颜色请求下来又被丢掉，闭合标记只画出后三个字符。开口标记不会被这样问到，所以只有它出问题。
     */
    public void testEveryCharacterOfTheClosingMarkerIsVisible() {
        PsiFile file = myFixture.addFileToProject("p/Marker.java", """
                package p;
                public class Marker {
                    //#if >= 1.20
                    /*$$import p.Missing;$$*/
                    //#endif
                }
                """);

        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);

        assertNotNull("the fixture has no document", document);

        String text = document.getText();
        int close = text.indexOf("$$*/");

        assertTrue("the fixture lost its closing marker", close > 0);

        for (int index = 0; index < 4; ++index) {
            assertFalse("character " + index + " of the closing marker reads as carried code",
                    MixinCommentContext.isHiddenOffset(document, close + index));
        }

        assertTrue("the carried code itself is still hidden",
                MixinCommentContext.isHiddenOffset(document, text.indexOf("p.Missing")));
    }

    /**
     * A replacement tail is a continuation, so the statement it stands in for has to be parsed in front of it.
     *
     * <p>On its own the tail begins at a dot and resolves to nothing, which is how a line that is perfectly
     * valid in the version it is written for came to report itself as broken. Two properties matter: the
     * statement is all there, and the replaced line itself is not part of it - the tail is what takes that
     * line's place, so putting both in would parse the call twice.
     */
    public void testReplacementTailIsGivenTheStatementItReplaces() {
        PsiFile file = myFixture.addFileToProject("p/Settings.java", """
                package p;
                public class Settings {
                    static void use() {
                        RuleFactory.of("x", false)
                                .addCategories(1)
                                .addValidator(first()) //#replace < 1.21.1 ? .addValidator(second())
                                .build();
                    }
                }
                """);

        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);

        assertNotNull("the fixture has no document", document);

        String text = document.getText();
        int line = document.getLineNumber(text.indexOf("//#replace"));
        String context = MixinCommentContext.replacementContext(document, line);

        assertTrue("the statement the line belongs to is missing: " + context,
                context.contains("RuleFactory.of"));
        assertTrue("the chain above the line is missing: " + context,
                context.contains(".addCategories(1)"));
        assertFalse("the replaced line is the tail, so it is not context: " + context,
                context.contains("addValidator(first())"));

        int lineStart = document.getLineStartOffset(line);
        int lineEnd = document.getLineEndOffset(line);
        String lineText = document.getText(TextRange.create(lineStart, lineEnd));
        int body = MixinCommentContext.replacementBodyStart(lineText);

        assertTrue("the tail was not found on the line", body >= 0);
        assertEquals("the tail has to start at the dot that continues the statement",
                '.', lineText.charAt(body));
    }

    /** A line that opens a statement of its own has nothing in front of it to carry. */
    public void testReplacementContextIsEmptyWhenTheLineStandsAlone() {
        assertEquals("", contextOf("""
                package p;
                public class Alone {
                    static void use() {
                        int before = 1;
                        int replaced = 2; //#replace < 1.21.1 ? int other = 3;
                    }
                }
                """));
    }

    /** Nor has the first line of a file, which cannot have anything before it. */
    public void testReplacementContextIsEmptyAtTheStartOfAFile() {
        assertEquals("", contextOf("//#replace < 1.21.1 ? int other = 1;\n"));
    }

    /** A blank line ends a statement as surely as a semicolon does. */
    public void testReplacementContextStopsAtABlankLine() {
        assertEquals("", contextOf("""
                package p;
                public class Gap {
                    static void use() {
                        int before = 1;

                        int replaced = 2; //#replace < 1.21.1 ? int other = 3;
                    }
                }
                """));
    }

    /**
     * Code written for another version is not context for this one.
     *
     * <p>A marker line ends the walk on purpose. Reading through it would put the other version's code in
     * front of this version's tail, and the fragment would be resolved against something that is not
     * compiled here at all - which is the opposite of what the context is for.
     */
    public void testReplacementContextStopsAtADirective() {
        assertEquals("", contextOf("""
                package p;
                public class Directed {
                    static void use() {
                        //#if >= 1.20
                        //$$int carried = RuleFactory.of("x", false)
                        int replaced = 2; //#replace < 1.21.1 ? int other = 3;
                    }
                }
                """));
    }

    /** A lone comment above the line is not part of the statement either. */
    public void testReplacementContextStopsAtAComment() {
        assertEquals("", contextOf("""
                package p;
                public class Commented {
                    static void use() {
                        // what this does
                        int replaced = 2; //#replace < 1.21.1 ? int other = 3;
                    }
                }
                """));
    }

    /** The whole chain is carried, however many lines it was written across. */
    public void testReplacementContextCarriesTheWholeChain() {
        String context = contextOf("""
                package p;
                public class Chained {
                    static void use() {
                        RuleFactory.of("x", false)
                                .addCategories(1)
                                .addValidator(first()) //#replace < 1.21.1 ? .addValidator(second())
                                .build();
                    }
                }
                """);

        assertTrue(context, context.contains("RuleFactory.of(\"x\", false)"));
        assertTrue(context, context.contains(".addCategories(1)"));
        assertFalse(context, context.contains("addValidator(first())"));
    }

    /** A marker with nothing after the question mark carries no code at all. */
    public void testReplacementBodyStartWithoutATail() {
        assertEquals(-1, MixinCommentContext.replacementBodyStart("int x = 1; // no marker here"));
        assertEquals(-1, MixinCommentContext.replacementBodyStart("int x = 1; //#replace < 1.21.1"));
        assertEquals("int x = 1; //#replace < 1.21.1 ? ".length(),
                MixinCommentContext.replacementBodyStart("int x = 1; //#replace < 1.21.1 ? "));

        // Whitespace after the question mark is stepped over, and only the first question mark counts.
        assertEquals("int x = 1; //#replace < 1.21.1 ? ".length() + 1,
                MixinCommentContext.replacementBodyStart("int x = 1; //#replace < 1.21.1 ?  y"));
        assertEquals("//#replace a ? b ? c".indexOf('b'),
                MixinCommentContext.replacementBodyStart("//#replace a ? b ? c"));
    }

    /** Each form hands over the code it carries and nothing else. */
    public void testCodeRangeForEachForm() {
        assertEquals("int carried = 1;", carried("//$$int carried = 1;"));
        assertEquals("int classBody = 1;", carried("/*$$int classBody = 1;$$*/"));
        assertEquals("int other = 3;", carried("int live = 1; //#replace < 1.21.1 ? int other = 3;"));
        assertEquals("int caseBody = 4;", carried("//? >= 1.21 ? int caseBody = 4;"));
        // A block carries every line it spans, not only the one it opens on.
        assertEquals("int block = 5;\nint more = 6;", carried("/*$$int block = 5;\nint more = 6;$$*/"));

        // A directive carries no code, a plain comment carries none, and a marker with nothing behind it
        // carries none either - each of those has to answer that way rather than answering with the text.
        assertNull(carried("//#if >= 1.20"));
        assertNull(carried("just a comment"));
        assertNull(carried("//$$"));
        assertNull(carried("int live = 1; //#replace < 1.21.1 ? "));
    }

    /** An offset outside the document is not carried code, whatever is written near it. */
    public void testHiddenOffsetOutsideTheDocument() {
        PsiFile file = myFixture.addFileToProject("p/Bounds.java", """
                package p;
                public class Bounds {
                    //$$int carried = 1;
                }
                """);

        Document document = documentOf(file);

        assertFalse("a negative offset", MixinCommentContext.isHiddenOffset(document, -1));
        assertFalse("an offset past the end",
                MixinCommentContext.isHiddenOffset(document, document.getTextLength() + 1));
    }

    /** Carried code ends where its block closes, and the lines after it are live again. */
    public void testHiddenOffsetEndsAtTheClosingMarker() {
        PsiFile file = myFixture.addFileToProject("p/Closed.java", """
                package p;
                public class Closed {
                    /*$$int carried = 1;$$*/
                    static void use() {
                        int live = 2;
                    }
                }
                """);

        Document document = documentOf(file);
        String text = document.getText();

        assertTrue("inside the block",
                MixinCommentContext.isHiddenOffset(document, text.indexOf("int carried")));
        assertFalse("after the block closed",
                MixinCommentContext.isHiddenOffset(document, text.indexOf("int live")));
    }

    /**
     * Only a replacement line carries a tail.
     *
     * <p>This is what decides whether the statement above is taken as context. A {@code //$$} line is a
     * statement of its own, and treating it as a replacement puts a statement in front of it that it never
     * had - after which the line parses as something else entirely and stops being coloured at all.
     */
    public void testOnlyAReplacementLineHasATail() {
        assertTrue("//$$ is not a replacement",
                MixinCommentContext.replacementBodyStart("//$$int carried = 1;") < 0);
        assertTrue("//#if is not a replacement",
                MixinCommentContext.replacementBodyStart("//#if >= 1.20") < 0);
        assertTrue("a //? case is not a replacement",
                MixinCommentContext.replacementBodyStart("//? >= 1.21 ? int body = 1;") < 0);
        assertTrue("a block is not a replacement",
                MixinCommentContext.replacementBodyStart("/*$$int block = 1;$$*/") < 0);
        assertTrue("a replacement line is",
                MixinCommentContext.replacementBodyStart("int x = 1; //#replace < 1.21 ? int y = 2;") >= 0);
    }

    /**
     * A replacement tail resolves against the statement it replaces, and nothing in the tail is left over.
     *
     * <p>This is the shape a settings class is full of: a call chain written across several lines, with one
     * of them swapped for another version. The tail alone resolves to nothing, and the context alone is not
     * what is being asked about - so what has to come back is the names of the tail, resolved, and nothing
     * from the statement in front of it.
     */
    public void testReplacementTailResolvesAgainstTheStatementAbove() {
        myFixture.addFileToProject("p/Validator.java",
                "package p; public class Validator {"
                        + " public static Validator create(String id, String name) { return null; } }");
        myFixture.addFileToProject("p/Factory.java",
                "package p; public class Factory {"
                        + " public static Factory of(String name, boolean value) { return null; }"
                        + " public Factory addCategories(int category) { return this; }"
                        + " public Factory addValidator(Validator validator) { return this; }"
                        + " public Factory build() { return this; } }");

        PsiFile file = myFixture.addFileToProject("p/Replace.java", """
                package p;
                public class Replace {
                    static void use() {
                        Factory.of("x", false)
                                .addCategories(1)
                                .addValidator(Validator.create("scalablelux", "ScalableLux")) //#replace < 1.21.1 ? .addValidator(Validator.create("starlight", "StarLight"))
                                .build();
                    }
                }
                """);

        PsiComment comment = carriedCommentOf(file);
        TextRange code = MixinCommentContext.codeRange(comment.getText());

        assertNotNull("the comment carries no code", code);

        String text = comment.getText();
        Map<TextRange, PsiElement> resolved = VersionedBlockAnnotator.resolveNames(comment, code);
        List<String> unresolved = new ArrayList<>();
        List<String> names = new ArrayList<>();

        for (Map.Entry<TextRange, PsiElement> entry : resolved.entrySet()) {
            int start = entry.getKey().getStartOffset() - comment.getTextRange().getStartOffset();
            String name = text.substring(start, start + entry.getKey().getLength());

            names.add(name);

            if (entry.getValue() == null) {
                unresolved.add(name);
            }
        }

        assertFalse("correctly written code was reported as unresolved: " + unresolved + " of " + names,
                !unresolved.isEmpty());

        // The statement in front of it is context, not content: it must not be carried back as a name.
        for (String name : names) {
            assertFalse("the statement above leaked into the names: " + name,
                    name.contains("scalablelux") || name.contains("ScalableLux"));
        }
    }

    /**
     * Every form of the dialect has to be recognised, or that whole form does nothing.
     *
     * <p>A form left out of this check is skipped before anything reads it: no colour on the names it holds,
     * nothing jumps from them, and the analysis that reports what a version does not have never sees them.
     * The failure is silent, which is how {@code //#replace} sat unrecognised for so long.
     */
    public void testEveryFormCarriesCode() {
        assertTrue("//$$", MixinCommentContext.carriesCode("//$$int carried = 1;"));
        assertTrue("/*$$", MixinCommentContext.carriesCode("/*$$int block = 1;$$*/"));
        assertTrue("//?", MixinCommentContext.carriesCode("//? >= 1.21 ? int body = 1;"));
        assertTrue("/*?", MixinCommentContext.carriesCode("/*? >= 1.21 ? int body = 1;*/"));
        assertTrue("//#replace",
                MixinCommentContext.carriesCode("int x = 1; //#replace < 1.21 ? int y = 2;"));

        assertFalse("a directive carries nothing", MixinCommentContext.carriesCode("//#if >= 1.20"));
        assertFalse("nor does a plain comment", MixinCommentContext.carriesCode("just a comment"));
    }

    /**
     * The wrapper a replacement line is parsed in has to open the statement it belongs to.
     *
     * <p>Without it the fragment parses the leading dot as a recovery, and the recovery answers every question
     * about the line wrongly: the call resolves to a method of the wrapper class, its parameter types are
     * unknown, and the jump from it goes nowhere near the code that actually declares it.
     */
    public void testReplacementShellOpensTheStatementAbove() {
        PsiFile file = myFixture.addFileToProject("p/Shell.java", """
                package p;
                public class Shell {
                    static void use() {
                        RuleFactory.of("x", false)
                                .addCategories(1)
                                .addValidator(first()) //#replace < 1.21.1 ? .addValidator(second())
                                .build();
                    }
                }
                """);

        PsiComment comment = carriedCommentOf(file);
        String shell = PreprocessorLanguageInjector.shellOpen(comment);

        assertTrue("the statement is not opened in the fragment: " + shell,
                shell.contains("RuleFactory.of"));
        assertTrue("the chain is not opened in the fragment: " + shell,
                shell.contains(".addCategories(1)"));
        assertFalse("the replaced line belongs to the tail, not to the opening: " + shell,
                shell.contains("addValidator(first())"));

        // The tail is placed straight after it, so the opening has to end where the tail begins.
        assertTrue("the opening does not end at a line break: " + shell, shell.endsWith("\n"));
        assertFalse("the opening adds a line of its own: " + shell, shell.endsWith("\n\n"));
    }

    /**
     * An import written in carried code is an import for the version that carries it.
     *
     * <p>This is the ordinary shape of a branch: the types it needs are declared at the top, inside a marker,
     * and used further down, also inside a marker. To the host file neither line is an import at all; to the
     * branch they are the two halves of one statement. A version-aware reading has to see the second half.
     */
    public void testImportInsideCarriedCodeResolvesTheNamesBelowIt() {
        myFixture.addFileToProject("p/Holder.java", "package p; public class Holder {}");

        PsiFile file = myFixture.addFileToProject("p/Marked.java", """
                package p;
                public class Marked {
                    //#if >= 26.3
                    /*$$import p.Holder;$$*/
                    //#endif
                    //#if >= 26.3
                    //$$ int carried = 1;
                    //$$ private Holder field;
                    //#endif
                }
                """);

        PsiComment comment = commentContaining(file, "private Holder field");
        TextRange code = MixinCommentContext.codeRange(comment.getText());

        assertNotNull("the //$$ line carries no code", code);

        String text = comment.getText();
        Map<TextRange, PsiElement> resolved = VersionedBlockAnnotator.resolveNames(comment, code);
        List<String> unresolved = new ArrayList<>();
        List<String> names = new ArrayList<>();

        for (Map.Entry<TextRange, PsiElement> entry : resolved.entrySet()) {
            int start = entry.getKey().getStartOffset() - comment.getTextRange().getStartOffset();
            String name = text.substring(start, start + entry.getKey().getLength());

            names.add(name);

            if (entry.getValue() == null) {
                unresolved.add(name);
            }
        }

        assertFalse("a type imported inside a marker was not resolved for the line below it: "
                        + unresolved + " of " + names,
                !unresolved.isEmpty());
        assertTrue("the type was never looked at: " + names, names.contains("Holder"));
    }

    /**
     * A field takes the theme's own field colour, the way types and methods take theirs.
     *
     * <p>The platform cannot colour it in a branch: the branch is a fragment, so the initialiser that gives the
     * field its type does not resolve there, and the semantic colouring drops the name to plain text. What was
     * left was a line of coloured types and calls around a name with no colour at all - and the name is the
     * one thing on such a line worth being able to find.
     */
    public void testFieldsTakeTheThemesFieldColours() {
        PsiFile file = myFixture.addFileToProject("p/Fields.java", """
                package p;
                public class Fields {
                    static final int CONSTANT = 1;
                    int instance;
                }
                """);

        PsiClass type = PsiTreeUtil.findChildOfType(file, PsiClass.class);

        assertNotNull("the fixture has no class", type);

        PsiField constant = type.findFieldByName("CONSTANT", false);
        PsiField instance = type.findFieldByName("instance", false);

        assertNotNull("the fixture has no constant", constant);
        assertNotNull("the fixture has no instance field", instance);

        assertEquals("a static field",
                DefaultLanguageHighlighterColors.STATIC_FIELD, VersionedBlockAnnotator.keyFor(constant));
        assertEquals("an instance field",
                DefaultLanguageHighlighterColors.INSTANCE_FIELD, VersionedBlockAnnotator.keyFor(instance));
    }

    /**
     * No two levels of nesting may share a colour.
     *
     * <p>The markers say where a block starts and ends; the colour is what says which start goes with which
     * end when there are several of them. Sharing one colour across every level leaves the pairing to be
     * worked out by counting, which is exactly what reading a file of version logic should not require.
     */
    public void testNestingColoursAreDistinct() {
        TextAttributesKey[] levels = MixinCommentSyntaxAnnotator.PREPROCESSOR_SCOPE_COLORS;

        assertTrue("there has to be more than one level to tell apart", levels.length > 1);

        Set<String> seen = new HashSet<>();

        for (TextAttributesKey key : levels) {
            assertTrue("two levels share the colour '" + key.getExternalName() + "'",
                    seen.add(key.getExternalName()));
        }
    }

    /**
     * An import written under a marker resolves to the class it names.
     *
     * <p>There is no reference to walk for on such a line: the host file reads it as comment, so no import was
     * ever parsed, and the fragment builder answers null for that reason. Left at that, the entire form had no
     * colour and nothing jumped from it - which is what a branch's own imports looked like.
     */
    public void testMarkedImportResolvesToItsClass() {
        myFixture.addFileToProject("p/ValueInput.java", "package p; public class ValueInput {}");

        PsiFile file = myFixture.addFileToProject("p/Imports.java", """
                package p;
                public class Imports {
                    //#if >= 1.21.6
                    //$$ import p.ValueInput;
                    //#endif
                }
                """);

        PsiComment comment = commentContaining(file, "p.ValueInput");
        TextRange code = MixinCommentContext.codeRange(comment.getText());

        assertNotNull("the marked import carries no code", code);

        String text = comment.getText();
        Map<String, PsiElement> byName = new LinkedHashMap<>();

        for (Map.Entry<TextRange, PsiElement> entry
                : VersionedBlockAnnotator.resolveNames(comment, code).entrySet()) {
            int start = entry.getKey().getStartOffset() - comment.getTextRange().getStartOffset();

            byName.put(text.substring(start, start + entry.getKey().getLength()), entry.getValue());
        }

        assertTrue("the import was never looked at: " + byName.keySet(),
                byName.containsKey("p.ValueInput"));
        assertNotNull("an import naming a class that exists did not resolve: " + byName,
                byName.get("p.ValueInput"));
    }

    /**
     * Carried code is folded, and folded by default.
     *
     * <p>The point of folding it is that a branch written for another version is not what the file is being
     * read for; opening one at a time is how it stays out of the way until it is wanted. A region that is
     * offered but left open gives up all of that, and a quick pass that answers with nothing gives up more -
     * it hands the file back to the platform's own folding, which folds comments as comments.
     */
    public void testCarriedCodeFoldsAndFoldsByDefault() {
        PsiFile file = myFixture.addFileToProject("p/Fold.java", """
                package p;
                public class Fold {
                    static void use() {
                        //$$ int one = 1;
                        //$$ int two = 2;
                        /*$$int three = 3;$$*/
                        int live = 4;
                    }
                }
                """);

        Document document = documentOf(file);
        PreprocessorFoldingBuilder builder = new PreprocessorFoldingBuilder();

        FoldingDescriptor[] full = builder.buildFoldRegions(file, document, false);
        FoldingDescriptor[] quick = builder.buildFoldRegions(file, document, true);

        assertTrue("no region was offered for carried code", full.length >= 2);
        assertEquals("a quick pass answers differently from a full one",
                full.length, quick.length);

        for (FoldingDescriptor region : full) {
            assertTrue("the region covers live code: " + region.getRange(),
                    !region.getRange().contains(document.getText().indexOf("int live")));
        }

        assertTrue("the builder does not ask for its regions to start folded",
                builder.isCollapsedByDefault(file.getNode()));
    }

    /**
     * A name a branch declares is carried too.
     *
     * <p>A declaration is not a reference, so it was never in the table the colouring reads - and the field
     * a branch exists to introduce is exactly the name a reader looks for on that line.
     */
    public void testDeclaredNamesAreCarried() {
        PsiFile file = myFixture.addFileToProject("p/Declared.java", """
                package p;
                public class Declared {
                    //#if >= 26.3
                    //$$ private int counter;
                    //#endif
                }
                """);

        PsiComment comment = commentContaining(file, "private int counter");
        TextRange code = MixinCommentContext.codeRange(comment.getText());

        assertNotNull("the marked declaration carries no code", code);

        String text = comment.getText();
        Map<String, PsiElement> byName = new LinkedHashMap<>();

        for (Map.Entry<TextRange, PsiElement> entry
                : VersionedBlockAnnotator.resolveNames(comment, code).entrySet()) {
            int start = entry.getKey().getStartOffset() - comment.getTextRange().getStartOffset();

            byName.put(text.substring(start, start + entry.getKey().getLength()), entry.getValue());
        }

        assertTrue("the declared name was not carried: " + byName.keySet(), byName.containsKey("counter"));
        assertNotNull("the declared name resolved to nothing: " + byName, byName.get("counter"));
        assertNotNull("the declared name has no colour",
                VersionedBlockAnnotator.keyFor(byName.get("counter")));
    }

    /**
     * A qualified call colours the name it calls, not the receiver in front of it.
     *
     * <p>{@code bag.put(...)} is a single reference whose range begins at the qualifier, so painting it by
     * what it resolved to spread the method's colour back over the variable - and a plain local variable
     * then read as though it were part of the call.
     */
    public void testQualifiedCallDoesNotColourItsReceiver() {
        myFixture.addFileToProject("p/Bag.java",
                "package p; public class Bag { public void put(Object value) {} }");

        PsiFile file = myFixture.addFileToProject("p/Qualified.java", """
                package p;
                public class Qualified {
                    static void use() {
                        /*$$Bag bag = new Bag();
                        bag.put(null);$$*/
                    }
                }
                """);

        PsiComment comment = commentContaining(file, "bag.put");
        TextRange code = MixinCommentContext.codeRange(comment.getText());

        assertNotNull("the block carries no code", code);

        String text = comment.getText();
        Map<String, PsiElement> byName = new LinkedHashMap<>();

        for (Map.Entry<TextRange, PsiElement> entry
                : VersionedBlockAnnotator.resolveNames(comment, code).entrySet()) {
            int start = entry.getKey().getStartOffset() - comment.getTextRange().getStartOffset();

            byName.put(text.substring(start, start + entry.getKey().getLength()), entry.getValue());
        }

        assertTrue("the called name was not carried: " + byName.keySet(), byName.containsKey("put"));
        assertFalse("the receiver was carried along with it: " + byName.keySet(),
                byName.containsKey("bag.put"));
    }

    /** A method a branch declares is carried on the same footing as a field. */
    public void testDeclaredMethodNamesAreCarried() {
        PsiFile file = myFixture.addFileToProject("p/Declared.java", """
                package p;
                public class Declared {
                    //#if >= 26.3
                    //$$ private int counter() { return 1; }
                    //#endif
                }
                """);

        PsiComment comment = commentContaining(file, "private int counter()");
        TextRange code = MixinCommentContext.codeRange(comment.getText());

        assertNotNull("the marked declaration carries no code", code);

        String text = comment.getText();
        Map<String, PsiElement> byName = new LinkedHashMap<>();

        for (Map.Entry<TextRange, PsiElement> entry
                : VersionedBlockAnnotator.resolveNames(comment, code).entrySet()) {
            int start = entry.getKey().getStartOffset() - comment.getTextRange().getStartOffset();

            byName.put(text.substring(start, start + entry.getKey().getLength()), entry.getValue());
        }

        assertTrue("the declared method was not carried: " + byName.keySet(), byName.containsKey("counter"));
        assertNotNull("the declared method has no colour",
                VersionedBlockAnnotator.keyFor(byName.get("counter")));
    }

    /**
     * A parameter takes the theme's parameter colour, on its declaration and everywhere it is used.
     *
     * <p>This is the colour the reader asked for by name. A branch is mostly written in terms of its
     * parameters, so leaving them plain makes the code read as a wall of names that mean nothing - and the
     * uses matter as much as the declaration, since that is where the name is being read rather than
     * introduced.
     */
    public void testParametersTakeTheThemesColourWhereverTheyAppear() {
        // Declared in the class body rather than inside a method, because a method is what is being declared
        // here and a method cannot be declared inside one.
        PsiFile file = myFixture.addFileToProject("p/Params.java", """
                package p;
                public class Params {
                    //$$ int counted(int first, int second) { return first + second; }
                }
                """);

        PsiComment comment = commentContaining(file, "int counted(");
        TextRange code = MixinCommentContext.codeRange(comment.getText());

        assertNotNull("the line carries no code", code);

        String text = comment.getText();
        Map<String, PsiElement> byName = new LinkedHashMap<>();

        for (Map.Entry<TextRange, PsiElement> entry
                : VersionedBlockAnnotator.resolveNames(comment, code).entrySet()) {
            int start = entry.getKey().getStartOffset() - comment.getTextRange().getStartOffset();

            byName.put(text.substring(start, start + entry.getKey().getLength()), entry.getValue());
        }

        assertTrue("the parameter was not carried: " + byName.keySet(), byName.containsKey("first"));
        assertEquals("a parameter does not take the parameter colour",
                DefaultLanguageHighlighterColors.PARAMETER,
                VersionedBlockAnnotator.keyFor(byName.get("first")));

        // The use of the parameter is carried as well, not only its declaration.
        assertTrue("the use of the parameter was not carried: " + byName.keySet(),
                byName.containsKey("second"));
        assertEquals("the use of a parameter takes the same colour as its declaration",
                DefaultLanguageHighlighterColors.PARAMETER,
                VersionedBlockAnnotator.keyFor(byName.get("second")));
    }

    private PsiComment commentContaining(PsiFile file, String needle) {
        for (PsiComment candidate : PsiTreeUtil.findChildrenOfType(file, PsiComment.class)) {
            if (candidate.getText().contains(needle)) {
                return candidate;
            }
        }

        fail("the fixture has no comment containing '" + needle + "'");

        return null;
    }

    private PsiComment carriedCommentOf(PsiFile file) {
        for (PsiComment candidate : PsiTreeUtil.findChildrenOfType(file, PsiComment.class)) {
            if (MixinCommentContext.codeRange(candidate.getText()) != null) {
                return candidate;
            }
        }

        fail("the fixture has no comment carrying code");

        return null;
    }

    /**
     * A name in carried code has somewhere to go.
     *
     * <p>Carried code is a comment as far as the file is concerned, so nothing in it is a reference and Go to
     * Declaration had nothing to follow - right-clicking a class in a branch said nothing, which reads as a
     * plugin that does not know what the class is, while the colouring had already resolved it. The reference
     * is taken from that same answer, so a name coloured as a class is a name that can be jumped to.
     */
    public void testANameInCarriedCodeCanBeJumpedTo() {
        myFixture.addFileToProject("p/Holder.java", "package p;\n\npublic class Holder {}\n");

        PsiFile file = myFixture.addFileToProject("p/Jump.java", """
                package p;

                public class Jump {
                    static void use() {
                        //$$ Holder holder = new Holder();
                        int live = 1;
                    }
                }
                """);

        int at = file.getText().indexOf("Holder holder");
        PsiElement element = file.findElementAt(at);

        assertTrue("the carried line is a comment, holding: " + element, element instanceof PsiComment);

        PsiComment comment = (PsiComment)element;
        PsiReference reference = comment.findReferenceAt(at - comment.getTextRange().getStartOffset());

        assertNotNull("a name in carried code has somewhere to go", reference);

        PsiElement resolved = reference.resolve();

        assertTrue("and it goes to the class: " + resolved, resolved instanceof PsiClass);
        assertEquals("p.Holder", ((PsiClass)resolved).getQualifiedName());
    }

    /** A block a marker carries is the same: its names can be jumped to. */
    public void testANameInACarriedBlockCanBeJumpedTo() {
        myFixture.addFileToProject("p/Holder.java", "package p;\n\npublic class Holder {}\n");

        PsiFile file = myFixture.addFileToProject("p/Block.java", """
                package p;

                public class Block {
                    static void use() {
                        /*$$
                        Holder holder = new Holder();
                        holder.toString();
                        $$*/
                        int live = 1;
                    }
                }
                """);

        int at = file.getText().indexOf("Holder holder");
        PsiElement element = file.findElementAt(at);

        assertTrue("the carried block is a comment, holding: " + element, element instanceof PsiComment);

        PsiComment comment = (PsiComment)element;
        PsiReference reference = comment.findReferenceAt(at - comment.getTextRange().getStartOffset());

        assertNotNull("a name in a carried block has somewhere to go", reference);
        assertEquals("p.Holder", ((PsiClass)reference.resolve()).getQualifiedName());
    }

    private String carried(String commentText) {
        TextRange range = MixinCommentContext.codeRange(commentText);

        return range == null ? null : commentText.substring(range.getStartOffset(), range.getEndOffset());
    }

    private String contextOf(String source) {
        PsiFile file = myFixture.addFileToProject("p/Context.java", source);
        Document document = documentOf(file);

        return MixinCommentContext.replacementContext(document, lineOf(document, "//#replace"));
    }

    private Document documentOf(PsiFile file) {
        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);

        assertNotNull("the fixture has no document", document);

        return document;
    }

    private int lineOf(Document document, String needle) {
        int at = document.getText().indexOf(needle);

        assertTrue("the fixture has no '" + needle + "'", at >= 0);

        return document.getLineNumber(at);
    }
}
