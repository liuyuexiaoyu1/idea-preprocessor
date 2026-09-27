package rems.idea.mixincompletion.template;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Where the conversion is offered, checked against the platform rather than against the descriptor.
 *
 * <p>The descriptor can name a group that does not exist, and the platform's answer to that is a warning at
 * startup and no menu entry anywhere - a plugin that looks broken, with nothing on screen to say why. This is
 * what happened: the group was named {@code RefactorMenu}, which is not a group the platform has. It is called
 * {@code RefactoringMenu}. Nothing in the descriptor text would have shown that; asking the registry does.
 */
public class RefactorMenuRegistrationTest extends BasePlatformTestCase {

    /** The conversion is on the refactor menu. */
    public void testTheConversionIsOnTheRefactorMenu() {
        ActionManager manager = ActionManager.getInstance();
        AnAction group = manager.getAction("RefactoringMenu");

        assertNotNull("the refactor menu is registered", group);
        assertTrue("it is a group of actions", group instanceof DefaultActionGroup);

        AnAction action = manager.getAction("rems.idea.mixincompletion.MakeMultiVersion");

        assertNotNull("the action is registered", action);

        boolean found = false;

        // By id, not by identity: the children of a group are stubs that are turned into actions when the menu
        // is built, so the action the registry hands back is never the object sitting in the group.
        for (AnAction child : ((DefaultActionGroup) group).getChildActionsOrStubs()) {
            if ("rems.idea.mixincompletion.MakeMultiVersion".equals(manager.getId(child))) {
                found = true;
            }
        }

        assertTrue("the conversion is on the refactor menu, and the menu it names exists", found);
    }

    /**
     * The wizard is told about the repository, in the file that is only read alongside Minecraft Development.
     *
     * <p>Two things have to hold for the template to be offered, and neither shows up as an error when it does
     * not: the key the provider is registered under has to be the key of an entry in the wizard's repository
     * list, and something has to add that entry. A provider that no entry names is never asked.
     */
    public void testTemplateRepositoryIsRegisteredForTheWizard() throws Exception {
        String descriptor;

        try (InputStream in = getClass().getResourceAsStream("/META-INF/mcdev.xml")) {
            assertNotNull("the optional descriptor is on the classpath", in);
            descriptor = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue("the provider is registered under the key the entry will name",
                descriptor.contains("creatorTemplateProvider key=\"liuyue_multi_version\""));
        assertTrue("something adds the entry that names it",
                descriptor.contains("applicationInitializedListener")
                        && descriptor.contains("MultiVersionTemplateRepoInstaller"));
    }
}
