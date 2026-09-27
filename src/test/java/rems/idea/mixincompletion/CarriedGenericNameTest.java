package rems.idea.mixincompletion;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Map;

/**
 * 泛型参数不是名字的一部分。
 *
 * <p>写在未激活分支里的声明带着泛型，{@code Holder<ContextFloatProvider>} 这样的引用元素把整段文本当成自己的范围，
 * 于是名字表里只有一段 {@code Holder<ContextFloatProvider>}：没有一条能按键找到，颜色和跳转两样都没有。类型参数里
 * 的类名同样是名字，它自己就是一个引用元素。
 */
public class CarriedGenericNameTest extends BasePlatformTestCase {

    private static PsiComment commentCarrying(PsiFile file, String needle) {
        for (PsiComment comment : PsiTreeUtil.findChildrenOfType(file, PsiComment.class)) {
            if (comment.getText().contains(needle)) {
                return comment;
            }
        }

        throw new AssertionError("no comment carries " + needle);
    }

    /** 一条声明：类型自己，和类型参数里的类，各自是一个名字，各自能被找到。 */
    public void testTypeArgumentIsNotPartOfTheName() {
        myFixture.addFileToProject("p/Holder.java", "package p;\npublic interface Holder<T> {}\n");
        myFixture.addFileToProject("p/ContextFloatProvider.java", "package p;\npublic class ContextFloatProvider {}\n");

        PsiFile file = myFixture.addFileToProject("p/CarriedGenerics.java", """
                package p;
                //#if >= 26.3
                /*$$import p.Holder;
                import p.ContextFloatProvider;$$*/
                //#endif
                public class CarriedGenerics {
                    //#if >= 26.3
                    //$$ @Deprecated private Holder<ContextFloatProvider> count;
                    //#endif
                }
                """);

        PsiComment comment = commentCarrying(file, "count");
        Map<TextRange, PsiElement> names = VersionedBlockAnnotator.namesOf(comment);

        assertFalse("nothing was read from the line at all", names.isEmpty());

        for (String name : new String[]{"Holder", "ContextFloatProvider"}) {
            int at = comment.getText().indexOf(name);

            assertTrue("the fixture no longer writes " + name, at >= 0);

            PsiElement target = VersionedBlockAnnotator.resolveName(comment,
                    TextRange.create(at, at + name.length()));

            assertNotNull("[" + name + "] is not a name of its own in " + names.keySet(), target);
        }
    }

    /** 注解的 @ 跟着注解一起，而不是一个单独的白字符。 */
    public void testAnnotationAtSignIsPartOfTheAnnotation() {
        myFixture.addFileToProject("p/Marker.java", "package p;\npublic @interface Marker {}\n");

        PsiFile file = myFixture.addFileToProject("p/AtSign.java", """
                package p;
                public class AtSign {
                    //#if >= 1.20
                    //$$ @Marker private int count;
                    //#endif
                }
                """);

        PsiComment comment = commentCarrying(file, "count");
        Map<TextRange, PsiElement> names = VersionedBlockAnnotator.namesOf(comment);
        int at = comment.getText().indexOf("@Marker");

        assertTrue("the fixture no longer writes the annotation", at >= 0);

        PsiElement target = VersionedBlockAnnotator.resolveName(comment,
                TextRange.create(at, at + "@Marker".length()));

        assertNotNull("the mark in front of the annotation is not part of it: at=" + at
                        + " comment=" + comment.getTextRange() + " text=[" + comment.getText() + "] " + names.keySet(),
                target);
    }

    /**
     * 限定名的包段也是名字，但不另给颜色。
     *
     * <p>平台对限定名就是这个做法：类名有色，前面那串包名留在默认前景上。跟着它走，同一个限定名在真实代码里和
     * 分支里长得一样；给它一个别的颜色，包名反而成了这条限定名上最显眼的一段。
     */
    public void testThePackagePartOfAQualifiedNameIsAName() {
        myFixture.addFileToProject("p/Other.java", "package p;\npublic class Other {}\n");

        PsiFile file = myFixture.addFileToProject("p/Names.java", """
                package p;
                public class Names {
                    //#if >= 1.21.6
                    //$$ @Deprecated private p.Other other;
                    //#endif
                }
                """);

        PsiComment comment = commentCarrying(file, "other");
        Map<TextRange, PsiElement> names = VersionedBlockAnnotator.namesOf(comment);
        int at = comment.getText().indexOf("p.Other");

        assertTrue("the fixture no longer writes the qualified name", at >= 0);

        PsiElement packagePart = VersionedBlockAnnotator.resolveName(comment, TextRange.create(at, at + 1));

        assertNotNull("the package half is not a name of its own: " + names.keySet(), packagePart);
        assertTrue("and it stands for a package: " + packagePart,
                packagePart instanceof com.intellij.psi.PsiPackage);
        assertNull("which is left at the foreground the platform leaves it at",
                VersionedBlockAnnotator.keyFor(packagePart));
    }
}
