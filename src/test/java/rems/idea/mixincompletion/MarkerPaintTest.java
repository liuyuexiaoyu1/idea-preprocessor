package rems.idea.mixincompletion;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

/**
 * 真实块形态下，闭合标记在编辑器 markup 上被画了多宽。
 *
 * <p>拿的是用户文件里那一段的形态：条件行与块同缩进、块首行紧接 {@code /*$$}、结束行是 {@code );$$*}。
 * 问的是编辑器自己的 markup，而不是某条路径打算画什么。
 */
public class MarkerPaintTest extends BasePlatformTestCase {

    /** 立即上色用的层，比 annotator 的层高，所以它画的范围就是看得见的范围。 */
    private static final int IMMEDIATE_LAYER = 3001;

    public void testTheClosingMarkerIsPaintedWhole() {
        myFixture.configureByText("IGNYSettings.java", """
                package p;

                public class IGNYSettings {
                    //#if >= 1.21.6
                    /*$$public static final Object THING = build(
                            "a",
                            "b"
                    );$$*/
                    //#endif
                }
                """);

        Editor editor = myFixture.getEditor();

        editor.getContentComponent().setSize(800, 600);
        MixinCommentImmediateHighlighter.refresh(editor);

        Document document = editor.getDocument();
        int close = document.getText().indexOf("$$*/");
        List<String> painted = new ArrayList<>();

        for (RangeHighlighter highlighter : editor.getMarkupModel().getAllHighlighters()) {
            if (highlighter.getLayer() == IMMEDIATE_LAYER) {
                painted.add("[" + highlighter.getStartOffset() + "," + highlighter.getEndOffset() + ")");
            }
        }

        assertTrue("the closing marker at " + close + " is painted whole, got " + painted,
                painted.contains("[" + close + "," + (close + 4) + ")"));
    }

    /** 行尾的 `//#replace` 自己也上色，而不是整行都平着。 */
    public void testAReplacementAtTheEndOfALineIsPainted() {
        myFixture.configureByText("Settings.java", """
                package p;
                public class Settings {
                    static void use() {
                        int live = 1; //#replace <= 1.20.4 ? int other = 2;
                    }
                }
                """);

        Editor editor = myFixture.getEditor();

        editor.getContentComponent().setSize(800, 600);
        MixinCommentImmediateHighlighter.refresh(editor);

        Document document = editor.getDocument();
        int marker = document.getText().indexOf("//#replace");
        List<String> painted = new ArrayList<>();

        for (RangeHighlighter highlighter : editor.getMarkupModel().getAllHighlighters()) {
            if (highlighter.getLayer() == IMMEDIATE_LAYER) {
                painted.add("[" + highlighter.getStartOffset() + "," + highlighter.getEndOffset() + ")");
            }
        }

        assertTrue("the directive at " + marker + " is painted, got " + painted,
                painted.contains("[" + marker + "," + (marker + "//#replace".length()) + ")"));
    }
}
