package rems.idea.mixincompletion.template;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

/**
 * The latest build of the preprocessor plugin, as jitpack has it.
 *
 * <p>The generated project names a commit rather than a release, because that is how the plugin is published:
 * a build of a specific commit, resolved by hash. Asking jitpack which commit it built most recently is what
 * keeps a new project from starting on a version that has since been superseded.
 *
 * <p>Quiet on failure. The endpoint is someone else's service, and a project that cannot be generated because
 * a version lookup timed out would be a worse outcome than one generated against the version the template
 * already names.
 */
final class JitpackVersions {
    private static final Logger LOG = Logger.getInstance(JitpackVersions.class);

    private static final String ENDPOINT = "https://jitpack.io/api/builds/";
    private static final int TIMEOUT_MS = 5000;

    private JitpackVersions() {}

    /**
     * The newest build jitpack has of an artifact that actually built, or null when it could not be asked.
     *
     * <p>The newest build and the newest usable build are not the same thing here: a commit is attempted and
     * may fail, and the endpoint that answers "latest" answers with the attempt. A project started against a
     * version that failed to build is a project that cannot resolve its own preprocessor, so the answer taken
     * is the one that says it came out - which jitpack reports separately, precisely because the two differ.
     *
     * @param coordinate the repository and name, as jitpack spells them - {@code owner/repo}
     */
    static @Nullable String latest(String coordinate) {
        String owner = coordinate.substring(0, coordinate.indexOf('/'));
        String name = coordinate.substring(coordinate.indexOf('/') + 1);
        String url = ENDPOINT + "com.github." + owner + "/" + name + "/latest";

        try {
            URLConnection connection = new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);

            String body;

            try (InputStream in = connection.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            JsonObject answer = JsonParser.parseString(body).getAsJsonObject();

            // Named outright when it differs from the newest attempt, and empty when the newest attempt is
            // itself the newest that built - so an empty answer here is not a failure, it is agreement.
            String built = text(answer, "latestOk");

            if (built != null) {
                return built;
            }

            // Nothing named, so the newest attempt stands only if it is one that came out.
            return "ok".equals(text(answer, "status")) ? text(answer, "version") : null;
        } catch (Exception failure) {
            LOG.warn("preprocessor template: could not ask jitpack for the latest " + coordinate, failure);

            return null;
        }
    }

    /** A field of the answer, or null when it is absent or blank. */
    private static @Nullable String text(JsonObject answer, String field) {
        JsonElement value = answer.get(field);

        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }

        String text = value.getAsString();

        return text.isBlank() ? null : text;
    }
}
