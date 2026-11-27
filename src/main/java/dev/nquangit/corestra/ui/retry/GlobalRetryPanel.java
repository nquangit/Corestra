package dev.nquangit.corestra.ui.retry;

import dev.nquangit.corestra.http.retry.GlobalRetryPolicy;
import dev.nquangit.corestra.persistence.ProjectSettingsRepository;
import dev.nquangit.corestra.ui.component.StyledButton;
import dev.nquangit.corestra.ui.component.UiTheme;
import dev.nquangit.corestra.ui.component.WrappedText;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Root "Global Retry" tab: edits the project-wide live-traffic retry policy stored in
 * {@code ProjectSettings.globalRetry}. All guidance is plain text; Burp's look-and-feel does not
 * render HTML labels reliably.
 */
public final class GlobalRetryPanel extends JPanel {
    private final ProjectSettingsRepository repository;

    private final JCheckBox enabled = new JCheckBox("Retry rate-limited / blocked responses on live Burp traffic");
    private final JCheckBox scopeOnly = new JCheckBox("Only requests in Burp's target scope");
    private final JTextField hostPattern = new JTextField(32);
    private final Map<String, JCheckBox> tools = new LinkedHashMap<>();
    private final JTextField methods = new JTextField(32);
    private final JTextField statusCodes = new JTextField(32);
    private final JTextField responsePattern = new JTextField(32);
    private final JSpinner maxRetries = new JSpinner(new SpinnerNumberModel(3, 1, GlobalRetryPolicy.MAX_RETRIES, 1));
    private final JSpinner baseDelay = new JSpinner(new SpinnerNumberModel(1_000L, 0L, GlobalRetryPolicy.MAX_DELAY_MILLIS, 100L));
    private final JSpinner maxDelay = new JSpinner(new SpinnerNumberModel(30_000L, 0L, GlobalRetryPolicy.MAX_DELAY_MILLIS, 1_000L));
    private final JSpinner multiplier = new JSpinner(new SpinnerNumberModel(2.0, 1.0, 10.0, 0.5));
    private final JCheckBox retryAfter = new JCheckBox("Honor Retry-After header");
    private final JSpinner budget = new JSpinner(new SpinnerNumberModel(60, 1,
            (int) (GlobalRetryPolicy.MAX_DELAY_MILLIS / 1000), 5));
    private final JCheckBox cooldown = new JCheckBox("Host cooldown: hold other requests to a rate-limited host");
    private final JLabel status = new JLabel(" ");

    /**
     * Creates the tab.
     *
     * @param repository project settings store
     */
    public GlobalRetryPanel(ProjectSettingsRepository repository) {
        super(new BorderLayout());
        this.repository = Objects.requireNonNull(repository, "repository");
        for (String tool : GlobalRetryPolicy.SUPPORTED_TOOLS) {
            JCheckBox box = new JCheckBox(tool);
            if (tool.equals("SCANNER")) {
                box.setToolTipText("Retry waits add to the measured response time and can cause false positives"
                        + " in time-based checks (e.g. blind SQLi).");
            }
            tools.put(tool, box);
        }
        responsePattern.setToolTipText("Regex searched over the full response (status line, headers, blank line, body),"
                + " e.g. (?i)request blocked|cf-mitigated");

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        content.add(left(new WrappedText("Retries live Proxy, Repeater, Intruder and Scanner responses that look"
                + " rate-limited or blocked. The Burp tool receives the final response; history still shows the"
                + " original request, and the response notes record the resends.", 760)));
        content.add(Box.createVerticalStrut(10));
        content.add(left(section("Coverage", coverageForm())));
        content.add(Box.createVerticalStrut(8));
        content.add(left(section("Retry when", conditionForm())));
        content.add(Box.createVerticalStrut(8));
        content.add(left(section("Waiting", waitingForm())));
        content.add(Box.createVerticalStrut(10));
        content.add(left(actionRow()));
        content.add(Box.createVerticalStrut(10));
        content.add(left(section("Limits", new WrappedText(String.join("\n",
                "- Requests that get no response at all (timeouts, dropped connections) cannot be retried.",
                "- Scanner is off by default: retry waits add to measured response time and can cause false"
                        + " positives in time-based checks.",
                "- Retrying non-idempotent methods (POST, PUT, DELETE) on 5xx can repeat a side effect;"
                        + " restrict Methods if that matters. 429 is usually safe because the server did not process"
                        + " the request.",
                "- While waiting, the Burp tool's thread is held (the browser waits for Proxy, Intruder threads"
                        + " wait). Unloading the extension releases every waiting request."), 760))));
        // Keep sections at their preferred height and let the page scroll instead of stretching.
        JPanel page = new WidthTrackingPanel();
        page.add(content, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(page);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        refresh();
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                refresh();
            }
        });
    }

    /** Reloads the stored policy into the form, e.g. after a project import. */
    public void refresh() {
        GlobalRetryPolicy current = repository.load().globalRetry();
        enabled.setSelected(current.enabled());
        scopeOnly.setSelected(current.burpScopeOnly());
        hostPattern.setText(current.hostPattern());
        tools.forEach((tool, box) -> box.setSelected(current.toolTypes().contains(tool)));
        methods.setText(String.join(", ", current.methods()));
        statusCodes.setText(String.join(", ", current.statusCodes().stream().map(String::valueOf).toList()));
        responsePattern.setText(current.responsePattern());
        maxRetries.setValue(current.maxRetries());
        baseDelay.setValue(current.baseDelayMillis());
        maxDelay.setValue(current.maxDelayMillis());
        multiplier.setValue(current.backoffMultiplier());
        retryAfter.setSelected(current.respectRetryAfter());
        budget.setValue(current.maxTotalWaitSeconds());
        cooldown.setSelected(current.hostCooldown());
        showStatus(current.summary(), false);
    }

    /**
     * Validates the form and saves it.
     *
     * @return {@code true} when saved
     */
    boolean save() {
        try {
            for (JSpinner spinner : List.of(maxRetries, baseDelay, maxDelay, multiplier, budget)) spinner.commitEdit();
            GlobalRetryPolicy policy = new GlobalRetryPolicy(enabled.isSelected(), scopeOnly.isSelected(),
                    hostPattern.getText(), tools.entrySet().stream().filter(entry -> entry.getValue().isSelected())
                    .map(Map.Entry::getKey).toList(), parseStatuses(statusCodes.getText()),
                    responsePattern.getText(), splitList(methods.getText()),
                    ((Number) maxRetries.getValue()).intValue(), ((Number) baseDelay.getValue()).longValue(),
                    ((Number) maxDelay.getValue()).longValue(), ((Number) multiplier.getValue()).doubleValue(),
                    retryAfter.isSelected(), ((Number) budget.getValue()).intValue(), cooldown.isSelected());
            repository.save(repository.load().withGlobalRetry(policy));
            showStatus("Saved. " + policy.summary(), false);
            return true;
        } catch (IllegalArgumentException | java.text.ParseException exception) {
            showStatus(exception.getMessage() == null ? "Check the retry settings." : exception.getMessage(), true);
            return false;
        }
    }

    private JPanel coverageForm() {
        JPanel toolRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tools.values().forEach(toolRow::add);
        Form form = new Form();
        form.span(enabled);
        form.row("Tools:", toolRow);
        form.span(scopeOnly);
        form.row("Host regex (blank = any):", hostPattern);
        form.row("Methods (blank = any):", methods);
        return form.panel;
    }

    private JPanel conditionForm() {
        Form form = new Form();
        form.row("Status codes:", statusCodes);
        form.row("or response regex:", responsePattern);
        form.span(new WrappedText("Example WAF pattern: (?i)request blocked|access denied|cf-mitigated|captcha."
                + " Avoid adding 403 to status codes: it would also retry real authorization denials.", 640));
        return form.panel;
    }

    private JPanel waitingForm() {
        Form form = new Form();
        form.row("Max retries:", maxRetries);
        form.row("Base delay (ms):", baseDelay);
        form.row("Max delay per wait (ms):", maxDelay);
        form.row("Backoff multiplier:", multiplier);
        form.span(retryAfter);
        form.row("Max total wait per request (s):", budget);
        form.span(cooldown);
        return form.panel;
    }

    private JPanel actionRow() {
        JButton saveButton = StyledButton.success("Save");
        JButton revert = StyledButton.neutral("Revert");
        JButton defaults = StyledButton.neutral("Load defaults");
        saveButton.addActionListener(event -> save());
        revert.addActionListener(event -> refresh());
        defaults.addActionListener(event -> {
            GlobalRetryPolicy policy = GlobalRetryPolicy.defaults();
            repository.save(repository.load().withGlobalRetry(policy));
            refresh();
            showStatus("Defaults restored (disabled).", false);
        });
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(saveButton);
        row.add(revert);
        row.add(defaults);
        row.add(Box.createHorizontalStrut(10));
        row.add(status);
        return row;
    }

    private void showStatus(String text, boolean error) {
        status.setText(text);
        status.setForeground(error ? UiTheme.pick(new Color(0xC0392B), new Color(0xFF7B72))
                : javax.swing.UIManager.getColor("Label.foreground"));
    }

    private static JPanel section(String title, JComponent body) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title),
                BorderFactory.createEmptyBorder(4, 6, 6, 6)));
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private static JComponent left(JComponent component) {
        component.setAlignmentX(LEFT_ALIGNMENT);
        return component;
    }

    static List<Integer> parseStatuses(String text) {
        List<Integer> statuses = new ArrayList<>();
        for (String token : splitList(text)) {
            try {
                statuses.add(Integer.parseInt(token));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Status codes must be numbers separated by commas: " + token);
            }
        }
        return statuses;
    }

    static List<String> splitList(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.split("[,\\s]+")).map(String::strip).filter(token -> !token.isEmpty()).toList();
    }

    /** Scroll page that follows the viewport width so wrapped guidance reflows. */
    private static final class WidthTrackingPanel extends JPanel implements javax.swing.Scrollable {
        private WidthTrackingPanel() { super(new BorderLayout()); }
        @Override public java.awt.Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) { return 16; }
        @Override public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return Math.max(16, visible.height - 32);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Two-column label/field grid. */
    private static final class Form {
        private final JPanel panel = new JPanel(new GridBagLayout());
        private int row;

        void row(String label, JComponent field) {
            GridBagConstraints lc = new GridBagConstraints();
            lc.gridx = 0; lc.gridy = row; lc.anchor = GridBagConstraints.WEST; lc.insets = new Insets(3, 0, 3, 10);
            GridBagConstraints fc = new GridBagConstraints();
            fc.gridx = 1; fc.gridy = row; fc.anchor = GridBagConstraints.WEST; fc.weightx = 1;
            fc.fill = GridBagConstraints.HORIZONTAL; fc.insets = new Insets(3, 0, 3, 0);
            panel.add(new JLabel(label), lc);
            panel.add(field, fc);
            row++;
        }

        void span(JComponent field) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0; c.gridy = row; c.gridwidth = 2; c.anchor = GridBagConstraints.WEST; c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(3, 0, 3, 0);
            panel.add(field, c);
            row++;
        }
    }
}
