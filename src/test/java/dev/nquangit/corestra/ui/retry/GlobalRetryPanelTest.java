package dev.nquangit.corestra.ui.retry;

import dev.nquangit.corestra.http.retry.RetryConfig;
import dev.nquangit.corestra.http.retry.RetryPresets;
import dev.nquangit.corestra.http.retry.RetryProfile;
import dev.nquangit.corestra.http.retry.RetryRule;
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
    void savingTheLoadedFormKeepsProfilesRulesAndOtherSettings() throws Exception {
        ProjectSettingsRepository repository = new ProjectSettingsRepository();
        RetryProfile cdn = new RetryProfile(null, "CDN host", true, List.of("PROXY", "SCANNER"), false, "cdn\\..*",
                "/api", List.of("GET"), RetryPresets.all().stream().map(RetryPresets.Preset::create).toList(),
                5, 250, 10_000, 1.5, false, 90, false);
        RetryConfig config = new RetryConfig(true, List.of(cdn, RetryProfile.standard("Default")));
        ProjectSettings settings = ProjectSettings.defaults().withProxyPort(9091).withRetry(config);
        repository.save(settings);

        SwingUtilities.invokeAndWait(() -> {
            GlobalRetryPanel panel = new GlobalRetryPanel(repository);
            assertEquals(config, panel.currentConfig(), "form round-trips every field");
            repository.save(settings.withRetry(RetryConfig.defaults()));
            assertTrue(panel.save(), "form still holds the configuration loaded at construction");
        });
        assertEquals(settings, repository.load());
    }

    @Test
    void presetsAreAddedToTheSelectedProfile() throws Exception {
        ProjectSettingsRepository repository = new ProjectSettingsRepository();
        SwingUtilities.invokeAndWait(() -> {
            GlobalRetryPanel panel = new GlobalRetryPanel(repository);
            panel.addPreset("Cloudflare WAF block (stop)");
            List<RetryRule> rules = panel.workingProfiles().get(0).rules();
            assertEquals(List.of("Rate limited (429/503)", "Cloudflare WAF block"),
                    rules.stream().map(RetryRule::name).toList());
            assertEquals(RetryRule.Action.STOP, rules.get(1).action());
            assertTrue(panel.save());
        });
        assertEquals(2, repository.load().retry().profiles().get(0).rules().size());
    }

    @Test
    void splitsCommaOrSpaceSeparatedLists() {
        assertEquals(List.of("GET", "HEAD"), GlobalRetryPanel.splitList("GET HEAD,"));
        assertEquals(List.of(), GlobalRetryPanel.splitList("  "));
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
