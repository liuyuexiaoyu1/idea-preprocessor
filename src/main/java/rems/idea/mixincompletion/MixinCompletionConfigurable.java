package rems.idea.mixincompletion;

import com.intellij.openapi.options.Configurable;
import java.awt.BorderLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

public final class MixinCompletionConfigurable implements Configurable {
   private JCheckBox autoInsert;

   public @Nls String getDisplayName() {
      return "Preprocessor Support";
   }

   public @Nullable JComponent createComponent() {
      this.autoInsert = new JCheckBox("在主版本不生效的预处理分支中换行时自动插入 //$$");
      JPanel panel = new JPanel(new BorderLayout());
      panel.add(this.autoInsert, "North");
      this.reset();
      return panel;
   }

   public boolean isModified() {
      return this.autoInsert != null && this.autoInsert.isSelected() != MixinCompletionSettings.getInstance().isAutoInsertPreprocessorComment();
   }

   public void apply() {
      if (this.autoInsert != null) {
         MixinCompletionSettings.getInstance().setAutoInsertPreprocessorComment(this.autoInsert.isSelected());
      }

   }

   public void reset() {
      if (this.autoInsert != null) {
         this.autoInsert.setSelected(MixinCompletionSettings.getInstance().isAutoInsertPreprocessorComment());
      }

   }

   public void disposeUIResources() {
      this.autoInsert = null;
   }
}
