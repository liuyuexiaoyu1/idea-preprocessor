package rems.idea.mixincompletion;

import com.intellij.lang.Language;
import com.intellij.lang.folding.CustomFoldingProvider;
import com.intellij.lang.folding.FoldingBuilder;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Tells the platform that a carried block is a folding region of its own.
 *
 * <p>Registered for the version of the platform this plugin targets, and kept because it is the documented way
 * to say that a comment is a region of its own. It is not what makes the block draw as this plugin's text: the
 * editor takes one folding builder per language, which for Java is the platform's own, and the check that
 * consults these providers is not on that path any more - a log line here stays silent while a block is drawn
 * by the platform as a comment. What carries the placeholder is the region's range instead: see
 * {@link PreprocessorFoldingBuilder}, whose region starts at the beginning of the line and so contains the
 * platform's comment region rather than colliding with it.
 *
 * <p>Only the block form is claimed. A run of carried lines has no closing marker, and a provider that claims
 * a region start the platform then has to find an end for is a provider that can fold to the end of the file.
 */
public final class PreprocessorFoldingProvider extends CustomFoldingProvider {
    private static final Logger LOG = Logger.getInstance(PreprocessorFoldingProvider.class);

    private static final String BLOCK_OPEN = "/*$$";
    private static final String BLOCK_CLOSE = "$$*/";

    @Override
    public boolean isCustomRegionStart(String text) {
        return text.stripLeading().startsWith(BLOCK_OPEN);
    }

    @Override
    public boolean isCustomRegionEnd(String text) {
        return text.stripTrailing().endsWith(BLOCK_CLOSE);
    }

    /**
     * What a closed block shows.
     *
     * <p>The whole region is handed here, so the count is the region's own lines. A block marker is a comment,
     * and what stands in for one has to close its own syntax: left open, the closed line reads as a comment
     * that never ends, and the rest of the file reads as though it were inside one.
     */
    @Override
    public String getPlaceholderText(String text) {
        int lines = 1;

        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                lines++;
            }
        }

        String placeholder = BLOCK_OPEN + " inactive line " + lines + " " + BLOCK_CLOSE;

        // Said out loud because this is the whole question behind a block drawn as a pair of delimiters with an
        // ellipsis between them: whether the platform asked here at all. If it did, the text it was given is the
        // text that should be on screen, and what is on screen came from somewhere else; if it did not, the
        // region for the block was built by someone who never asked.
        LOG.info("preprocessor folding: asked for the placeholder of a block of " + lines
                + " line(s), answered [" + placeholder + "]");

        return placeholder;
    }

    @Override
    public boolean isCollapsedByDefault(String text) {
        // Carried code is what a file is not being read for; opening one at a time is how it stays out of the
        // way until it is wanted.
        return true;
    }

    @Override
    public @NotNull String getStartString() {
        return BLOCK_OPEN;
    }

    @Override
    public @NotNull String getEndString() {
        return BLOCK_CLOSE;
    }

    @Override
    public @NotNull String getDescription() {
        return "Preprocessor";
    }

    @Override
    public boolean isSupported(@NotNull Language language) {
        // The markers are read wherever the dialect's files are: sources, resources, and the language files
        // beside them, which is why the folding builder is declared for several languages as well.
        return true;
    }

    @Override
    public boolean isSupportedBy(@NotNull FoldingBuilder builder) {
        // Every builder is answered yes. The question the platform asks is whether a region a builder is about
        // to make is one of these, and the answer decides two things that both matter: whether the comment
        // folding leaves a marker alone, and whether the region built for it takes the placeholder from here.
        // Answering "only mine" read as "this is not one of yours" to the builder doing the comment folding,
        // and a block came out drawn as a comment again - a pair of delimiters with an ellipsis between them -
        // instead of the text asked for.
        return true;
    }
}
