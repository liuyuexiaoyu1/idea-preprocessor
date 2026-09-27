package rems.idea.mixincompletion.template;

import com.demonwav.mcdev.MinecraftSettings;
import com.intellij.ide.ApplicationInitializedListener;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Tells the Minecraft wizard about the repository the multi-version template is served from.
 *
 * <p>The wizard does not ask every template provider what it has. It reads a list of repositories from
 * Minecraft Development's settings and, for the one that is selected, asks the provider named by that entry -
 * a lookup by key, which is why a provider whose key is in no entry is a provider that is never called and a
 * template that is never offered. The list is the wizard's, so the entry has to be in it.
 *
 * <p>Registered in the config file that is read only alongside Minecraft Development, so nothing here is
 * loaded in an IDE that does not have it.
 *
 * <p>The entry is added once and left alone afterwards. Adding it is what makes the template reachable at
 * all; a reader who removes it in Minecraft Development's settings gets it back on the next start, which is
 * the price of a template that cannot be offered any other way.
 */
public final class MultiVersionTemplateRepoInstaller implements ApplicationInitializedListener {

    /**
     * The startup entry point.
     *
     * <p>The listener interface carries two of them, an older method and a coroutine one, and which of the two
     * a platform calls for a given listener is not something this code can decide. Both are implemented and
     * the install is idempotent, so it happens whichever one runs.
     */
    @Override
    public @NotNull Object execute(@NotNull Continuation<? super Unit> continuation) {
        install();

        return Unit.INSTANCE;
    }

    /** The older of the two entry points. */
    @Override
    public void componentsInitialized() {
        install();
    }

    /** Adds the repository to the wizard's list, unless it is already there. */
    static void install() {
        MinecraftSettings settings = MinecraftSettings.Companion.getInstance();
        List<MinecraftSettings.TemplateRepo> repos = settings.getCreatorTemplateRepos();

        for (MinecraftSettings.TemplateRepo repo : repos) {
            if (MultiVersionTemplateProvider.KEY.equals(repo.getProvider())) {
                return;
            }
        }

        // The list comes from a settings component and is not ours to write into, so a copy is written back.
        List<MinecraftSettings.TemplateRepo> updated = new ArrayList<>(repos);
        updated.add(new MinecraftSettings.TemplateRepo(
                MultiVersionTemplateProvider.LABEL,
                MultiVersionTemplateProvider.KEY,
                MultiVersionTemplateProvider.REPOSITORY));

        settings.setCreatorTemplateRepos(updated);
    }
}
