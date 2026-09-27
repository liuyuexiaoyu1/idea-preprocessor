package rems.idea.mixincompletion;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.Service.Level;
import org.jetbrains.annotations.NotNull;

@Service({Level.APP})
@State(
   name = "MixinDescriptorCompletionSettings",
   storages = {@Storage("mixinDescriptorCompletion.xml")}
)
public final class MixinCompletionSettings implements PersistentStateComponent<MixinCompletionSettings.SettingsState> {
   private SettingsState state = new SettingsState();

   static MixinCompletionSettings getInstance() {
      return (MixinCompletionSettings)ApplicationManager.getApplication().getService(MixinCompletionSettings.class);
   }

   boolean isAutoInsertPreprocessorComment() {
      return this.state.autoInsertPreprocessorComment;
   }

   void setAutoInsertPreprocessorComment(boolean enabled) {
      this.state.autoInsertPreprocessorComment = enabled;
   }

   public @NotNull SettingsState getState() {
      SettingsState var10000 = this.state;

      return var10000;
   }

   public void loadState(@NotNull SettingsState state) {

      this.state = state;
   }

   // $FF: synthetic method
   

   public static final class SettingsState {
      public boolean autoInsertPreprocessorComment = true;
   }
}
