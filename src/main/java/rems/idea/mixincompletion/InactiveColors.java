package rems.idea.mixincompletion;

import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.EffectType;
import com.intellij.openapi.editor.markup.TextAttributes;
import java.awt.Color;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

final class InactiveColors {
   private static final double DIM = 0.22;
   private static final double WASH = 0.1;
   private static final int WASH_ALPHA = 30;
   private static final Map<String, TextAttributesKey> CACHE = new ConcurrentHashMap();

   private InactiveColors() {
   }

   static TextAttributesKey dim(TextAttributesKey source) {
      EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
      return (TextAttributesKey)CACHE.computeIfAbsent(scheme.getName() + "\u0000" + source.getExternalName(), (ignored) -> compute(scheme, source));
   }

   static TextAttributesKey washed(TextAttributesKey source) {
      EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
      return (TextAttributesKey)CACHE.computeIfAbsent(scheme.getName() + "\u0000WASHED\u0000" + source.getExternalName(), (ignored) -> computeWashed(scheme, source));
   }

   static TextAttributesKey backdrop() {
      EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
      return (TextAttributesKey)CACHE.computeIfAbsent(scheme.getName() + "\u0000REMS_INACTIVE_BACKDROP", (ignored) -> computeBackdrop(scheme));
   }

   private static TextAttributesKey computeBackdrop(EditorColorsScheme scheme) {
      TextAttributes attributes = new TextAttributes((Color)null, washColour(scheme), (Color)null, (EffectType)null, 0);
      return TextAttributesKey.createTextAttributesKey("REMS_INACTIVE_BACKDROP", attributes);
   }

   private static TextAttributesKey computeWashed(EditorColorsScheme scheme, TextAttributesKey source) {
      TextAttributes attributes = scheme.getAttributes(source);
      Color foreground = attributes.getForegroundColor();
      Color wash = washColour(scheme);
      return foreground == null ? TextAttributesKey.createTextAttributesKey("REMS_INACTIVE_WASHED_" + source.getExternalName(), new TextAttributes((Color)null, wash, (Color)null, (EffectType)null, 0)) : TextAttributesKey.createTextAttributesKey("REMS_INACTIVE_WASHED_" + source.getExternalName(), new TextAttributes(mix(foreground, scheme.getDefaultBackground(), 0.22), wash, attributes.getEffectColor(), attributes.getEffectType(), attributes.getFontType()));
   }

   private static TextAttributesKey compute(EditorColorsScheme scheme, TextAttributesKey source) {
      TextAttributes attributes = scheme.getAttributes(source);
      Color foreground = attributes.getForegroundColor();
      if (foreground == null) {
         return source;
      } else {
         TextAttributes dimmed = new TextAttributes(mix(foreground, scheme.getDefaultBackground(), 0.22), attributes.getBackgroundColor(), attributes.getEffectColor(), attributes.getEffectType(), attributes.getFontType());
         return TextAttributesKey.createTextAttributesKey("REMS_INACTIVE_" + source.getExternalName(), dimmed);
      }
   }

   private static Color washColour(EditorColorsScheme scheme) {
      Color tinted = mix(scheme.getDefaultBackground(), scheme.getDefaultForeground(), 0.1);
      return new Color(tinted.getRed(), tinted.getGreen(), tinted.getBlue(), 30);
   }

   private static Color mix(Color color, @Nullable Color other, double weight) {
      return other == null ? color : new Color((int)Math.round((double)color.getRed() * ((double)1.0F - weight) + (double)other.getRed() * weight), (int)Math.round((double)color.getGreen() * ((double)1.0F - weight) + (double)other.getGreen() * weight), (int)Math.round((double)color.getBlue() * ((double)1.0F - weight) + (double)other.getBlue() * weight));
   }
}
