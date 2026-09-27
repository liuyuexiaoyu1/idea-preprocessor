package rems.idea.mixincompletion;

import com.intellij.codeInsight.folding.impl.FoldingUpdate;
import com.intellij.lang.folding.CustomFoldingProvider;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.Nullable;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/**
 * What a folded region says while it is closed.
 *
 * <p>The placeholder is the whole of what a closed region shows, so it is what a reader sees of code written
 * for another version. A block marker is a comment and the text standing in for it has to close that comment,
 * or the rest of the file reads as though it were inside one.
 */
public class PreprocessorFoldingTest extends BasePlatformTestCase {

    /** A block of carried code is replaced by a marker that closes itself. */
    public void testBlockPlaceholderClosesItsOwnMarker() {
        String placeholder = placeholderOf("""
                package p;
                public class Fold {
                    static void use() {
                        /*$$ int three = 3;
                        int four = 4;
                        $$*/
                        int live = 5;
                    }
                }
                """);

        assertEquals("        /*$$ inactive line 3 $$*/", placeholder);
    }

    /** A run of carried lines is replaced by the line marker and how many lines it stands for. */
    public void testLinePlaceholderCountsTheLines() {
        String placeholder = placeholderOf("""
                package p;
                public class Fold {
                    static void use() {
                        //$$ int one = 1;
                        //$$ int two = 2;
                        int live = 3;
                    }
                }
                """);

        assertEquals("        //$$ inactive line 2", placeholder);
    }

    /** One carried line says one line. */
    public void testSingleLinePlaceholder() {
        String placeholder = placeholderOf("""
                package p;
                public class Fold {
                    //$$ int only = 1;
                    int live = 2;
                }
                """);

        assertEquals("    //$$ inactive line 1", placeholder);
    }

    /**
     * Every character of a marker answers to a click, and nothing else does.
     *
     * <p>Four characters, and the click has to be on one of them: this is what decides whether the pointer
     * over a marker is a hand and whether a click on the line folds it. Holding the click to a shorter run is
     * what leaves part of a marker dead.
     */
    public void testEveryMarkerCharacterResponds() {
        Document document = documentOf("""
                //$$ int one = 1;
                /*$$ int two = 2; $$*/
                // plain comment
                int live = 3;
                """);

        int first = document.getLineStartOffset(0);

        for (int offset = first; offset < first + 4; offset++) {
            assertTrue("offset " + offset, PreprocessorFoldToggleHandler.onMarker(document, offset));
        }

        assertFalse("the code beside the marker is not the marker",
                PreprocessorFoldToggleHandler.onMarker(document, first + 5));

        int block = document.getLineStartOffset(1);
        assertTrue("the block marker answers", PreprocessorFoldToggleHandler.onMarker(document, block));
        assertTrue("and so does its closing marker",
                PreprocessorFoldToggleHandler.onMarker(document, document.getLineEndOffset(1) - 3));

        int plain = document.getLineStartOffset(2);
        assertFalse("an ordinary comment is not a marker",
                PreprocessorFoldToggleHandler.onMarker(document, plain));
    }

    /** The four characters of each marker form, and that a longer run of them is not one. */
    public void testMarkerForms() {
        assertTrue(PreprocessorFoldToggleHandler.startsMarker("//$$ int x;", 0));
        assertTrue(PreprocessorFoldToggleHandler.startsMarker("/*$$int x;$$*/", 0));
        assertTrue(PreprocessorFoldToggleHandler.startsMarker("/*$$int x;$$*/", 10));
        assertTrue(PreprocessorFoldToggleHandler.startsMarker("    //$$ x", 4));

        assertFalse(PreprocessorFoldToggleHandler.startsMarker("//$x", 0));
        assertFalse(PreprocessorFoldToggleHandler.startsMarker("// comment", 0));
        assertFalse(PreprocessorFoldToggleHandler.startsMarker("/**/", 0));
        assertFalse("a window that runs past the end is not a marker",
                PreprocessorFoldToggleHandler.startsMarker("//$$", 1));
    }

    /** The platform is told about the block form, and not about the line form. */
    public void testOnlyTheBlockFormIsClaimedAsARegion() {
        PreprocessorFoldingProvider provider = new PreprocessorFoldingProvider();

        assertTrue(provider.isCustomRegionStart("/*$$ int a = 1;"));
        assertTrue("indentation is not a difference", provider.isCustomRegionStart("        /*$$"));
        assertTrue(provider.isCustomRegionEnd("int a = 1;$$*/"));

        assertFalse("a run of carried lines has no end marker to find",
                provider.isCustomRegionStart("//$$ int a = 1;"));
        assertFalse(provider.isCustomRegionEnd("//$$ int a = 1;"));
        assertFalse("an ordinary comment is not a region", provider.isCustomRegionStart("/* ordinary */"));

        assertTrue("carried code is what the file is not being read for", provider.isCollapsedByDefault(""));
    }

    /** The block's own lines are what the count is of. */
    public void testTheProviderCountsTheLinesItIsGiven() {
        PreprocessorFoldingProvider provider = new PreprocessorFoldingProvider();

        assertEquals("/*$$ inactive line 1 $$*/", provider.getPlaceholderText("/*$$ int a = 1; $$*/"));
        assertEquals("/*$$ inactive line 3 $$*/",
                provider.getPlaceholderText("/*$$\nint a = 1;\n$$*/"));
    }

    /** And the platform has it: a provider that is declared and not registered changes nothing. */
    public void testTheProviderIsRegistered() {
        boolean found = false;

        for (CustomFoldingProvider provider : CustomFoldingProvider.getAllProviders()) {
            if (provider instanceof PreprocessorFoldingProvider) {
                found = true;
            }
        }

        assertTrue("the platform knows about the block form", found);
    }

    /**
     * 类体内缩进的块画的是本插件的文本，不是平台按注释画的。
     *
     * <p>编辑器为每个语言只用一个折叠构建器，用的一定是平台自己那一个：本插件的构建器排在后面，从来不会被问到。所以能
     * 不能出现在屏幕上只取决于范围——平台给多行注释的区域正好是注释自身的范围，与它相同的区域会被丢掉。块从行首起而不是从
     * 注释起，这个区域就把平台那一个<em>包含</em>在里面，而包含关系的两个区域都会留下。
     */
    public void testABlockInsideAClassIsDrawnWithThisPluginsText() {
        myFixture.configureByText("Fold.java", """
                package p;
                public class Fold {
                    static void use() {
                        /*$$ int three = 3;
                        int four = 4;
                        $$*/
                        int live = 5;
                    }
                }
                """);

        PsiFile file = myFixture.getFile();

        assertNotNull(file);

        Document document = myFixture.getEditor().getDocument();
        int commentStart = document.getText().indexOf("/*$$");
        int commentEnd = document.getText().indexOf("$$*/") + 4;
        String comment = "[" + commentStart + "," + commentEnd + ")";
        List<String> covering = new ArrayList<>();

        for (FoldingUpdate.RegionInfo info : FoldingUpdate.getFoldingsFor(file, false)) {
            TextRange range = info.descriptor.getRange();

            if (range.getStartOffset() <= commentStart && range.getEndOffset() >= commentEnd) {
                covering.add(range + " " + info.descriptor.getPlaceholderText());
            }
        }

        assertTrue("the block is drawn with this plugin's text, got " + covering,
                covering.stream().anyMatch(text -> text.endsWith("/*$$ inactive line 3 $$*/")));
        assertTrue("and its region is the wider one, so the platform's own is kept inside it: " + covering,
                covering.stream().anyMatch(text -> !text.startsWith(comment)));
    }

    /**
     * 折叠后的缩进与被折叠的代码一致，不论缩进多深。
     *
     * <p>区域必须从行首起算——与平台注释区域同范围的那一个会被丢掉——所以占位符是从第 0 列画的：缩进十六格的块折起来
     * 就贴在左边，比它原来的位置少十六格。区域说不出的话由文本说，缩进写在占位符前面。
     */
    public void testTheIndentationIsKeptWhenTheBlockIsClosed() {
        for (int indent : new int[]{4, 8, 16, 24}) {
            String padding = " ".repeat(indent);
            myFixture.configureByText("Fold.java",
                    "class Fold {\n"
                            + padding + "/*$$ int a = 1;\n"
                            + padding + "int b = 2;\n"
                            + padding + "$$*/\n"
                            + "}\n");

            PsiFile file = myFixture.getFile();

            assertNotNull(file);

            Document document = myFixture.getEditor().getDocument();
            int commentStart = document.getText().indexOf("/*$$");
            int commentEnd = document.getText().indexOf("$$*/") + 4;
            List<String> covering = new ArrayList<>();

            for (FoldingUpdate.RegionInfo info : FoldingUpdate.getFoldingsFor(file, false)) {
                TextRange range = info.descriptor.getRange();

                if (range.getStartOffset() <= commentStart && range.getEndOffset() >= commentEnd) {
                    covering.add(range + " [" + info.descriptor.getPlaceholderText() + "]");
                }
            }

            String expected = "[" + padding + "/*$$ inactive line 3 $$*/]";

            assertTrue("indent " + indent + ": the block is drawn where it was written, got " + covering,
                    covering.stream().anyMatch(text -> text.endsWith(expected)));
        }
    }

    /** `//?` 写在一行上的分支也折起来。 */
    public void testAQuestionBranchIsFolded() {
        myFixture.configureByText("Fold.java", """
                package p;
                public class Fold {
                    static void use() {
                        //? >= 1.21.6 ? int other = 1;
                        int live = 2;
                    }
                }
                """);

        assertTrue("the question branch is folded: " + placeholdersOf(myFixture.getFile()),
                placeholdersOf(myFixture.getFile()).contains("//? inactive line 1"));
    }

    /** 行尾的 `//#replace` 折起来，行首那段这一版真的要跑的代码留着。 */
    public void testAReplacementAtTheEndOfALineIsFolded() {
        myFixture.configureByText("Fold.java", """
                package p;
                public class Fold {
                    static void use() {
                        int live = 1; //#replace <= 1.20.4 ? int other = 2;
                    }
                }
                """);

        assertTrue("the replacement is folded: " + placeholdersOf(myFixture.getFile()),
                placeholdersOf(myFixture.getFile()).contains("//#replace inactive line 1"));
    }

    /** `//?` 与 `//#replace` 的判定语句整段都可以点。 */
    public void testTheWholeTestOfAOneLineBranchAnswersToAClick() {
        Document document = documentOf("""
                package p;
                public class Fold {
                    static void use() {
                        //? >= 1.21.9 ? int other = 1;
                        int live = 1; //#replace < 1.20.5 ? int other = 2;
                    }
                }
                """);

        String text = document.getText();
        int question = text.indexOf("//? >= 1.21.9 ?");
        int questionMark = text.indexOf('?', question + "//?".length());

        for (int offset = question; offset <= questionMark; offset++) {
            assertTrue("offset " + offset + " of the question's test",
                    PreprocessorFoldToggleHandler.onMarker(document, offset));
        }

        assertFalse("the code behind the test is a place for a caret",
                PreprocessorFoldToggleHandler.onMarker(document, questionMark + 2));

        int replacement = text.indexOf("//#replace < 1.20.5 ?");
        int replacementMark = text.indexOf('?', replacement + "//#replace".length());

        for (int offset = replacement; offset <= replacementMark; offset++) {
            assertTrue("offset " + offset + " of the replacement's test",
                    PreprocessorFoldToggleHandler.onMarker(document, offset));
        }

        assertFalse("the code in front of it is this version's own",
                PreprocessorFoldToggleHandler.onMarker(document, replacement - 3));
    }

    /** Every placeholder the builder offers for a file. */
    private List<String> placeholdersOf(PsiFile file) {
        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);
        PreprocessorFoldingBuilder builder = new PreprocessorFoldingBuilder();
        List<String> placeholders = new ArrayList<>();

        assertNotNull("the fixture has no document", document);

        for (FoldingDescriptor region : builder.buildFoldRegions(file, document, false)) {
            placeholders.add(region.getPlaceholderText());
        }

        return placeholders;
    }

    /** The placeholder of the region the carried code is in. */
    private String placeholderOf(String source) {        PsiFile file = myFixture.addFileToProject("p/Fold.java", source);
        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);
        PreprocessorFoldingBuilder builder = new PreprocessorFoldingBuilder();
        FoldingDescriptor[] regions = builder.buildFoldRegions(file, document, false);

        assertTrue("no region was offered for the carried code", regions.length >= 1);

        String placeholder = null;

        for (FoldingDescriptor region : regions) {
            // The region this plugin builds, told apart by its opening line: the platform's own folding adds
            // regions over the same file, and asking this builder about one of those asks about a line no
            // marker is on.
            if (!opensOnMarker(document, region.getRange().getStartOffset())) {
                continue;
            }

            // What the editor shows, and the answer the other path would give, which have to be the same
            // thing: a region the platform builds without a placeholder is answered from the builder instead.
            String shown = region.getPlaceholderText();

            assertNotNull("a region on a marker has a placeholder", shown);
            assertEquals("the two ways of asking disagree", shown,
                    builder.getPlaceholderText(file.getNode(), region.getRange()));
            placeholder = shown;
        }

        assertNotNull("no region opens on a marker", placeholder);

        return placeholder;
    }

    private static boolean opensOnMarker(Document document, int offset) {
        int line = document.getLineNumber(offset);
        String text = document.getCharsSequence()
                .subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString();

        return text.contains("//$$") || text.contains("/*$$");
    }

    /**
     * A click on the text a closed block is drawn as opens it, and a click beside that text does not.
     *
     * <p>The block occupies a few characters where the code used to be, and those characters are the whole of
     * what it answers to. A block that opens when its line is clicked is a block that opens while the file is
     * being edited - the reader is putting a caret in, not asking for code - and one that does not open when
     * the text itself is clicked is one that cannot be opened at all.
     */
    public void testAClickOnThePlaceholderOpensItAndAClickBesideItDoesNot() {
        Editor editor = editorWithCarriedCode();
        FoldRegion region = foldLines(editor, 3, 4, false);
        Point onPlaceholder = placeholderPoint(editor, region);

        clickAt(editor, onPlaceholder, region.getStartOffset());

        assertTrue("a click on the placeholder opens the block", region.isExpanded());

        setExpanded(editor, region, false);

        // Left of the block, on the line it is drawn on.
        clickAt(editor, new Point(onPlaceholder.x - 30, onPlaceholder.y), region.getStartOffset() - 3);

        assertFalse("a click in front of the placeholder is a caret", region.isExpanded());

        // And right of it, on the same line.
        clickAt(editor, new Point(onPlaceholder.x + 400, onPlaceholder.y), region.getEndOffset() - 1);

        assertFalse("a click past the placeholder is a caret too", region.isExpanded());
    }

    /**
     * A block with two regions over it opens, whichever of them the text belongs to.
     *
     * <p>The same text can carry two: the one this plugin builds, and one the platform builds for the markers
     * it has been told are regions of their own. Opening one and leaving the other closed over the same code
     * is what a click on a folded block that does nothing looks like.
     */
    public void testBothRegionsOverABlockOpen() {
        Editor editor = editorWithCarriedCode();
        Document document = editor.getDocument();
        FoldRegion first = foldLines(editor, 3, 4, true);
        FoldRegion[] second = new FoldRegion[1];

        editor.getFoldingModel().runBatchFoldingOperation(() -> {
            // The second one begins at the start of the line, which is where the region the platform builds
            // for a comment begins - the same code, a different range, and both of them kept.
            second[0] = editor.getFoldingModel().addFoldRegion(document.getLineStartOffset(3),
                    first.getEndOffset(), "/*$$ inactive line 2 $$*/");
            second[0].setExpanded(false);
        });

        assertNotNull("the second region was kept", second[0]);

        setExpanded(editor, first, false);
        clickAt(editor, placeholderPoint(editor, first), first.getStartOffset());

        assertTrue("the block opens", first.isExpanded());
        assertTrue("and so does the other region over the same code", second[0].isExpanded());
    }

    /**
     * Only the four characters of a marker fold the block they introduce.
     *
     * <p>The marker is what a reader points at to close code they are not working on, and it is small: the
     * indentation in front of it and the code beside it on the same line are places a reader puts a caret.
     */
    public void testOnlyTheMarkerClosesTheBlock() {
        Editor editor = editorWithCarriedCode();
        FoldRegion region = foldLines(editor, 3, 4, true);

        clickAt(editor, null, region.getStartOffset() + 1);

        assertFalse("a click on the marker closes the block", region.isExpanded());

        setExpanded(editor, region, true);
        clickAt(editor, null, region.getStartOffset() - 1);

        assertTrue("a click on the indentation in front of it does not", region.isExpanded());

        setExpanded(editor, region, true);
        clickAt(editor, null, offsetOf(editor, "int one"));

        assertTrue("nor does a click on the code beside it", region.isExpanded());
    }

    /** A click on code that is not carried leaves everything as it was. */
    public void testAClickOnOrdinaryCodeDoesNothing() {
        Editor editor = editorWithCarriedCode();
        FoldRegion region = foldLines(editor, 3, 4, true);
        clickAt(editor, null, offsetOf(editor, "int live"));

        assertTrue("carried code is not folded by a click elsewhere", region.isExpanded());
    }

    private Document documentOf(String text) {
        PsiFile file = myFixture.addFileToProject("p/Markers.java", text);

        return PsiDocumentManager.getInstance(getProject()).getDocument(file);
    }

    private static FoldRegion addRegion(Editor editor, int start, int end, String placeholder) {
        FoldRegion[] holder = new FoldRegion[1];

        editor.getFoldingModel().runBatchFoldingOperation(() -> {
            holder[0] = editor.getFoldingModel().addFoldRegion(start, end, placeholder);
            holder[0].setExpanded(false);
        });

        assertNotNull("the region was added", holder[0]);

        return holder[0];
    }

    /**
     * 顶格的块折叠后也画成这个方言的写法。
     *
     * <p>记在行首的块没有左边可以借：本插件的区域只能靠"比平台那个更宽"活下来，而缩进为零时两个范围一样大，先到
     * 的平台那一个留下。范围赢不了的，占位符能改——折叠之前改写盖在同一段代码上的每一个区域，屏幕上就是这里的写法。
     */
    public void testAFoldedBlockAtTheMarginIsDrawnWithThisPluginsText() {
        myFixture.configureByText("Fold.java", """
                package p;
                //#if >= 1.21.6
                /*$$int a = 1;
                int b = 2;$$*/
                //#endif
                public class Fold {
                }
                """);

        Editor editor = myFixture.getEditor();
        Document document = editor.getDocument();
        int start = document.getText().indexOf("/*$$");
        int end = document.getText().indexOf("$$*/") + 4;
        FoldRegion region = addRegion(editor, start, end, "/*...*/");

        PreprocessorFoldToggleHandler.rename(editor.getFoldingModel(), document, region);

        assertEquals("/*$$ inactive line 2 $$*/", region.getPlaceholderText());
    }

    /** A configured editor holding carried code and one live line. */
    private Editor editorWithCarriedCode() {
        myFixture.configureByText("Fold.java", """
                package p;
                public class Fold {
                    static void use() {
                        //$$ int one = 1;
                        //$$ int two = 2;
                        int live = 3;
                    }
                }
                """);

        // Sized, because a click is a point on screen and a point needs a screen: without this the editor has
        // no laid-out text to hit-test against, and the place a folded block occupies cannot be asked about.
        myFixture.getEditor().getContentComponent().setSize(800, 600);

        return myFixture.getEditor();
    }

    /** The point the text of a closed block is drawn at - the middle of its first line. */
    private static Point placeholderPoint(Editor editor, FoldRegion region) {
        Point start = editor.visualPositionToXY(editor.offsetToVisualPosition(region.getStartOffset()));

        return new Point(start.x + 2, start.y + 3);
    }

    private static void clickAt(Editor editor, @Nullable Point point, int offset) {
        PreprocessorFoldToggleHandler.toggle(editor, point, offset);
    }

    /**
     * Folds a range of the document, the way the builder's descriptors are turned into regions.
     *
     * <p>By line rather than by searching for the text: the region a marker describes starts at the first
     * character of its line and ends at the end of the last line it covers, which is what the builder does.
     */
    private static FoldRegion foldLines(Editor editor, int firstLine, int lastLine, boolean expanded) {
        Document document = editor.getDocument();
        int lineEnd = document.getLineEndOffset(firstLine);
        int start = document.getLineStartOffset(firstLine);
        String opening;

        while (start < lineEnd && Character.isWhitespace(document.getCharsSequence().charAt(start))) {
            start++;
        }

        opening = document.getText(new TextRange(start, lineEnd));

        assertTrue("the line carries a marker, but reads: " + opening,
                opening.startsWith("//$$") || opening.startsWith("/*$$"));

        int end = document.getLineEndOffset(lastLine);
        // Final copies: the lambda below runs in a batch operation and cannot read a local that was moved.
        int from = start;
        int to = end;
        FoldRegion[] holder = new FoldRegion[1];

        editor.getFoldingModel().runBatchFoldingOperation(() -> {
            holder[0] = editor.getFoldingModel().addFoldRegion(from, to, "//$$ inactive line 2");
            holder[0].setExpanded(expanded);
        });

        assertNotNull("the region was added", holder[0]);

        return holder[0];
    }

    /** The platform only lets a region be opened or closed from inside a batch operation. */
    private static void setExpanded(Editor editor, FoldRegion region, boolean expanded) {
        editor.getFoldingModel().runBatchFoldingOperation(() -> region.setExpanded(expanded));
    }

    private static int offsetOf(Editor editor, String needle) {
        int offset = editor.getDocument().getText().indexOf(needle);

        assertTrue("the document holds " + needle, offset >= 0);

        return offset;
    }
}
