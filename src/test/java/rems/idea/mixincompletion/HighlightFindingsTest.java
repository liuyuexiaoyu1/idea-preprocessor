package rems.idea.mixincompletion;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.lang.LanguageExtensionPoint;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * Whether the findings filter is wired to the platform at all, and what it decides.
 *
 * <p>Both halves matter and they fail differently. A filter whose logic is right but whose extension point
 * name is wrong is never called, and every finding about a branch for another version reaches the editor -
 * which is exactly what happened here: the entry was written as {@code <highlightInfoFilter>} under the
 * {@code com.intellij} namespace, resolving to an extension point the platform does not define, so the class
 * was loaded and then ignored for as long as it existed.
 *
 * <p>Findings are built by hand rather than taken from a run of the daemon. A pass can be cancelled part way
 * through in a test environment, and a test that fails because of that says nothing about the filter.
 */
public class HighlightFindingsTest extends BasePlatformTestCase {

    /** The plugin has to be loaded for any extension of it to exist. */
    public void testPluginIsLoaded() {
        IdeaPluginDescriptor descriptor =
                PluginManagerCore.getPlugin(PluginId.getId("rems.idea.preprocessor"));

        assertNotNull("the plugin is not loaded in this environment", descriptor);
    }

    /** Registered under the name the platform actually defines, or never called. */
    public void testFilterIsRegistered() {
        List<HighlightInfoFilter> filters = HighlightInfoFilter.EXTENSION_POINT_NAME.getExtensionList();

        assertTrue("InactiveCodeFilter is not registered with '"
                        + HighlightInfoFilter.EXTENSION_POINT_NAME.getName() + "', only: " + filters,
                filters.stream().anyMatch(f -> f instanceof InactiveCodeFilter));
    }

    /** The suppressor sits under a namespace of its own, and is read through the language extension point. */
    public void testSuppressorIsRegistered() {
        ExtensionPointName<LanguageExtensionPoint> point =
                ExtensionPointName.create("com.intellij.lang.inspectionSuppressor");

        boolean registered = point.getExtensionList().stream()
                .anyMatch(p -> p.getInstance() instanceof InactiveInspectionSuppressor);

        assertTrue("InactiveInspectionSuppressor is not registered with '" + point.getName()
                        + "', only: " + point.getExtensionList(),
                registered);
    }

    /** A finding inside carried code is dropped, whatever kind it is. */
    public void testFindingInsideCarriedCodeIsDropped() {
        PsiFile file = carriedFile();
        Document document = documentOf(file);
        InactiveCodeFilter filter = new InactiveCodeFilter();

        // The import line of a carried block: the shapes the editor was showing were this, a syntax error,
        // and an unresolved name - all three of them about the same line.
        String text = document.getText();
        int at = text.indexOf("p.Missing");

        assertTrue("the fixture lost its import line", at > 0);
        assertFalse("a syntax finding inside carried code was let through",
                filter.accept(finding(HighlightInfoType.ERROR, at, at + 9), file));

        int name = text.indexOf("MissingType");
        assertFalse("a resolution finding inside carried code was let through",
                filter.accept(finding(HighlightInfoType.WRONG_REF, name, name + 11), file));
    }

    /** The live code beside it is still analysed. */
    public void testFindingInLiveCodeIsKept() {
        PsiFile file = carriedFile();
        Document document = documentOf(file);
        int at = document.getText().indexOf("int live");

        assertTrue("the fixture lost its live line", at > 0);
        assertTrue("a finding in live code was dropped",
                new InactiveCodeFilter().accept(finding(HighlightInfoType.ERROR, at, at + 8), file));
    }

    private HighlightInfo finding(HighlightInfoType type, int start, int end) {
        return HighlightInfo.newHighlightInfo(type)
                .range(start, end)
                .descriptionAndTooltip("Unexpected token")
                .create();
    }

    private PsiFile carriedFile() {
        return myFixture.addFileToProject("p/Settings.java", """
                package p;
                public class Settings {
                    private static Object register(Object factory) { return factory; }
                    static void use() {
                        //#if >= 1.20
                        /*$$import p.Missing;
                        import p.AlsoMissing;$$*/
                        //#endif
                        //#if >= 1.21
                        //$$Object carried = register(MissingType.of());
                        //#endif
                        int live = 1;
                    }
                }
                """);
    }

    private Document documentOf(PsiFile file) {
        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);

        assertNotNull("the fixture has no document", document);

        return document;
    }
}
