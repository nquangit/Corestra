package dev.nquangit.corestra.ui.retry;

import dev.nquangit.corestra.http.retry.GlobalRetryPolicy;
import dev.nquangit.corestra.persistence.ProjectSettings;
import dev.nquangit.corestra.persistence.ProjectSettingsRepository;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class GlobalRetryPanelTest {

    @Test
    void savingTheLoadedFormKeepsThePolicyAndOtherSettings() throws Exception {
        ProjectSettingsRepository repository = new ProjectSettingsRepository();
        GlobalRetryPolicy policy = new GlobalRetryPolicy(true, false, "api\\..*", List.of("PROXY", "SCANNER"),
                List.of(429, 503), "(?i)captcha", List.of("GET"), 5, 250, 10_000, 1.5, false, 90, false);
        ProjectSettings settings = ProjectSettings.defaults().withProxyPort(9091).withGlobalRetry(policy);
        repository.save(settings);

        SwingUtilities.invokeAndWait(() -> {
            GlobalRetryPanel panel = new GlobalRetryPanel(repository);
            repository.save(settings.withGlobalRetry(GlobalRetryPolicy.defaults()));
            assertTrue(panel.save(), "form still holds the policy loaded at construction");
        });
        assertEquals(settings, repository.load());
    }

    @Test
    void parsesCommaOrSpaceSeparatedLists() {
        assertEquals(List.of(429, 503), GlobalRetryPanel.parseStatuses(" 429, 503 "));
        assertEquals(List.of("GET", "HEAD"), GlobalRetryPanel.splitList("GET HEAD,"));
        assertEquals(List.of(), GlobalRetryPanel.splitList("  "));
        assertThrows(IllegalArgumentException.class, () -> GlobalRetryPanel.parseStatuses("429, abc"));
    }

    /** Burp's look-and-feel renders HTML labels badly; UI text must be plain (see WrappedText). */
    @Test
    void uiSourcesDoNotUseHtmlLabels() throws IOException {
        Pattern html = Pattern.compile("\"\\s*<html", Pattern.CASE_INSENSITIVE);
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/dev/nquangit/corestra/ui"))) {
            List<Path> offenders = files.filter(path -> path.toString().endsWith(".java")).filter(path -> {
                try {
                    return html.matcher(Files.readString(path)).find();
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            }).toList();
            assertEquals(List.of(), offenders);
        }
    }
}
