package rems.idea.mixincompletion.template;

import com.demonwav.mcdev.MinecraftSettings;
import com.demonwav.mcdev.creator.custom.TemplatePropertyDescriptor;
import com.demonwav.mcdev.creator.custom.providers.LoadedTemplate;
import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.io.HttpRequests;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import org.jetbrains.annotations.NotNull;
import org.junit.Assume;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * What the wizard is handed when it asks this provider for templates.
 *
 * <p>The wizard shows "There are no templates available" for anything that comes back empty, and an empty
 * answer here has several causes that look identical from outside: a repository that could not be fetched, a
 * descriptor that could not be built, a provider that was asked and said nothing. This asks the provider in
 * the same way the wizard does, so the answer is one of them rather than a guess.
 */
public class MultiVersionTemplateProviderTest extends BasePlatformTestCase {

    /** The provider offers the repository's template, once the repository is there. */
    public void testTheProviderOffersTheTemplate() {
        Assume.assumeTrue("the template repository is not reachable from here", reachable());

        // The copy is kept under the IDE's system directory, which a test may not read until it says so.
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), PathManager.getSystemPath());

        MinecraftSettings.TemplateRepo repo = new MinecraftSettings.TemplateRepo(
                MultiVersionTemplateProvider.LABEL,
                MultiVersionTemplateProvider.KEY,
                MultiVersionTemplateProvider.REPOSITORY);
        Recorder<Collection<? extends LoadedTemplate>> answer = new Recorder<>();

        // The provider does not read the context; the wizard always has one, and the parameter is not optional.
        Object returned = new MultiVersionTemplateProvider().loadTemplates(context(), repo, answer);
        Collection<? extends LoadedTemplate> templates = templatesFrom(returned);

        // A suspend function that finishes without suspending answers through its return. Resuming the
        // continuation as well is what the wizard choked on: it read the return, found Unit, and cast it.
        assertNull("the continuation is the caller's and is left alone", answer.value);

        assertEquals("it offers exactly the template: " + templates, 1, templates.size());

        LoadedTemplate template = templates.iterator().next();

        assertEquals("the template is named as the wizard shows it",
                MultiVersionTemplateProvider.LABEL, template.getLabel());
        assertNotNull("and carries a descriptor to read it from", template.getDescriptor());
        assertTrue("and is one the wizard will offer", template.isValid());
    }

    /** An entry naming another repository is followed rather than ignored. */
    public void testTheEntryNamesTheRepository() {
        Assume.assumeTrue("the template repository is not reachable from here", reachable());

        VfsRootAccess.allowRootAccess(getTestRootDisposable(), PathManager.getSystemPath());

        Recorder<Collection<? extends LoadedTemplate>> answer = new Recorder<>();

        // The same repository, spelled as an entry a reader could have added themselves.
        Object returned = new MultiVersionTemplateProvider().loadTemplates(context(),
                new MinecraftSettings.TemplateRepo("elsewhere", MultiVersionTemplateProvider.KEY,
                        MultiVersionTemplateProvider.REPOSITORY),
                answer);

        assertEquals("the entry's repository is the one read", 1, templatesFrom(returned).size());
    }

    @SuppressWarnings("unchecked")
    private static Collection<? extends LoadedTemplate> templatesFrom(Object returned) {
        assertTrue("the templates come back from the return: " + returned, returned instanceof Collection);

        return (Collection<? extends LoadedTemplate>)returned;
    }

    private WizardContext context() {
        return new WizardContext(getProject(), getTestRootDisposable());
    }

    /** 运行时真正构造出来的属性表：两个属性都在，次序与名字对得上。 */
    public void testDescriptorsAreBuiltAgainstThisWizardsConstructor() {
        Assume.assumeTrue("the template repository is not reachable from here", reachable());

        VfsRootAccess.allowRootAccess(getTestRootDisposable(), PathManager.getSystemPath());

        Recorder<Collection<? extends LoadedTemplate>> answer = new Recorder<>();

        Object returned = new MultiVersionTemplateProvider().loadTemplates(context(),
                new MinecraftSettings.TemplateRepo(MultiVersionTemplateProvider.LABEL,
                        MultiVersionTemplateProvider.KEY, MultiVersionTemplateProvider.REPOSITORY),
                answer);
        Collection<? extends LoadedTemplate> templates = templatesFrom(returned);

        assertEquals("it offers exactly the template: " + templates, 1, templates.size());

        LoadedTemplate template = templates.iterator().next();

        assertNotNull("the descriptor was built", template.getDescriptor());
        assertEquals("with both ends of the version range asked for",
                2, template.getDescriptor().getProperties().size());
        assertEquals(MultiVersionTemplateProvider.FROM_VERSION,
                template.getDescriptor().getProperties().get(0).getName());
        assertEquals(MultiVersionTemplateProvider.TO_VERSION,
                template.getDescriptor().getProperties().get(1).getName());
    }

    /**
     * 版本是选出来的，不是打出来的。
     *
     * <p>选项来自仓库自己的版本列表，也就是生成项目时读的那一份。有选项时向导画的是下拉（版本数远超一行能放的按钮数，
     * 另有 forceDropdown 保证）；选项为空时才是文本框，那时没有东西可选。
     */
    public void testTheVersionRangeIsChosenFromTheRepositoriesVersions() {
        Assume.assumeTrue("the template repository is not reachable from here", reachable());

        VfsRootAccess.allowRootAccess(getTestRootDisposable(), PathManager.getSystemPath());

        Object returned = new MultiVersionTemplateProvider().loadTemplates(context(),
                new MinecraftSettings.TemplateRepo(MultiVersionTemplateProvider.LABEL,
                        MultiVersionTemplateProvider.KEY, MultiVersionTemplateProvider.REPOSITORY),
                new Recorder<>());

        LoadedTemplate template = templatesFrom(returned).iterator().next();
        TemplatePropertyDescriptor from = template.getDescriptor().getProperties().get(0);

        assertTrue("the versions are offered as a list: " + from.getOptions(),
                from.getOptions() instanceof List);
        assertEquals("and as a dropdown, however few of them there are",
                Boolean.TRUE, from.getForceDropdown());

        List<?> versions = (List<?>)from.getOptions();

        assertFalse("with the repository's versions in it", versions.isEmpty());
        assertTrue("the list is the repository's own: " + versions, versions.contains("1.21.8"));
    }

    private static boolean reachable() {
        try {
            return HttpRequests.head(firstArchive()).connectTimeout(4000).readTimeout(4000).tryConnect() > 0;
        } catch (IOException failure) {
            return false;
        }
    }

    private static String firstArchive() {
        return TemplateRepository.archiveUrls(MultiVersionTemplateProvider.REPOSITORY).get(0);
    }

    /**
     * A continuation that records what it was handed.
     *
     * <p>What it is here to record is that it was handed nothing: the provider answers a repository it can
     * read without ever suspending, so the value belongs in the return and this must stay untouched.
     */
    private static final class Recorder<T> implements Continuation<T> {
        private T value;

        @Override
        public @NotNull CoroutineContext getContext() {
            return EmptyCoroutineContext.INSTANCE;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void resumeWith(@NotNull Object result) {
            // Result is an inline class, so a success arrives here as the value itself.
            this.value = (T)result;
        }
    }
}
