package rems.idea.mixincompletion;

import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * 点击一个名字，去的是那个名字的类。
 *
 * <p>同一个位置上能问两个问题：这个名字是什么，和这段标记属于哪个另一版本。前者是点击名字时被问的那个，
 * 而先前答的是后者——一个已经解析得好好的类名跳到相邻分支去，跳转看起来像没生效。
 */
public class PreprocessorGotoTest extends BasePlatformTestCase {

    private PsiFile fixture() {
        myFixture.addFileToProject("p/Holder.java", "package p;\npublic interface Holder<T> {}\n");
        myFixture.addFileToProject("p/ContextFloatProvider.java", "package p;\npublic class ContextFloatProvider {}\n");

        return myFixture.addFileToProject("p/Goto.java", """
                package p;
                //#if >= 26.3
                /*$$import p.Holder;
                import p.ContextFloatProvider;$$*/
                //#endif
                public class Goto {
                    //#if >= 26.3
                    //$$ @Deprecated private Holder<ContextFloatProvider> count;
                    //#else
                    private int count;
                    //#endif
                }
                """);
    }

    /** 名字上的点击落到类型自己，而不是相邻的分支。 */
    public void testClickOnACarriedNameGoesToTheClass() {
        PsiFile file = fixture();
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());

        Document document = myFixture.getEditor().getDocument();
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

        int offset = document.getText().indexOf("Holder<");
        assertTrue("the fixture no longer writes the generic declaration", offset > 0);

        PsiElement[] targets = new PreprocessorGotoDeclarationHandler()
                .getGotoDeclarationTargets(file.findElementAt(offset), offset, myFixture.getEditor());

        assertNotNull("a click on a name the colouring resolved has to go somewhere", targets);
        assertEquals("one target, and it is the class", 1, targets.length);
        assertTrue("the click has to land on the class, not on the branch: " + targets[0].getText(),
                targets[0] instanceof PsiClass && "Holder".equals(((PsiClass)targets[0]).getName()));
    }

    /** 条件标记行上的点击仍然去这个条件的其它分支。 */
    public void testClickOnAConditionMarkerReachesTheOtherBranches() {
        PsiFile file = fixture();
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());

        Document document = myFixture.getEditor().getDocument();
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

        // 第二个条件才是这个类里的那个：第一个包着 carried import，只有一条 endif 是它的伙伴。
        int offset = document.getText().lastIndexOf("//#if >= 26.3");

        assertTrue("the fixture no longer writes the condition", offset > 0);

        PsiElement[] targets = new PreprocessorGotoDeclarationHandler()
                .getGotoDeclarationTargets(file.findElementAt(offset), offset, myFixture.getEditor());

        assertNotNull("a condition marker has to reach the branches it opens", targets);
        assertTrue("branches " + PreprocessorStructure.partners(document, document.getLineNumber(offset))
                        + " gave " + targets.length + " targets, first " + targets[0].getText(),
                targets.length >= 2);
    }
}
