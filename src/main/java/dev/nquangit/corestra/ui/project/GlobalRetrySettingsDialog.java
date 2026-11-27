package dev.nquangit.corestra.ui.project;

import dev.nquangit.corestra.http.retry.GlobalRetryPolicy;
import dev.nquangit.corestra.persistence.ProjectSettingsRepository;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Edits the project-wide live-traffic retry policy. */
final class GlobalRetrySettingsDialog extends JDialog {
    private boolean saved;

    /**
     * Shows the dialog modally.
     *
     * @param parent     owner component
     * @param repository project settings store
     * @return {@code true} when the policy was saved
     */
    static boolean open(Component parent, ProjectSettingsRepository repository) {
        GlobalRetrySettingsDialog dialog = new GlobalRetrySettingsDialog(parent, repository);
        dialog.setVisible(true);
        return dialog.saved;
    }

    private GlobalRetrySettingsDialog(Component parent, ProjectSettingsRepository repository) {
        super(SwingUtilities.getWindowAncestor(parent), "Global retry — project settings", ModalityType.APPLICATION_MODAL);
        GlobalRetryPolicy current = repository.load().globalRetry();

        JCheckBox enabled = new JCheckBox("Retry rate-limited / blocked responses on live Burp traffic", current.enabled());
        JCheckBox scopeOnly = new JCheckBox("Only requests in Burp's target scope", current.burpScopeOnly());
        JTextField hostPattern = new JTextField(current.hostPattern(), 28);
        Map<String, JCheckBox> tools = new LinkedHashMap<>();
        JPanel toolRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (String tool : GlobalRetryPolicy.SUPPORTED_TOOLS) {
            JCheckBox box = new JCheckBox(tool, current.toolTypes().contains(tool));
            if (tool.equals("SCANNER")) {
                box.setToolTipText("Retry waits add to the measured response time and can cause false positives"
                        + " in time-based checks (e.g. blind SQLi).");
            }
            tools.put(tool, box);
            toolRow.add(box);
        }
        JTextField statusCodes = new JTextField(String.join(", ",
                current.statusCodes().stream().map(String::valueOf).toList()), 28);
        JTextField responsePattern = new JTextField(current.responsePattern(), 28);
        responsePattern.setToolTipText("Regex searched over the full response (status line, headers, blank line, body),"
                + " e.g. (?i)access denied|cf-mitigated");
        JTextField methods = new JTextField(String.join(", ", current.methods()), 28);
        JSpinner maxRetries = new JSpinner(new SpinnerNumberModel(current.maxRetries(), 1, GlobalRetryPolicy.MAX_RETRIES, 1));
        JSpinner baseDelay = new JSpinner(new SpinnerNumberModel(current.baseDelayMillis(), 0L,
                GlobalRetryPolicy.MAX_DELAY_MILLIS, 100L));
        JSpinner maxDelay = new JSpinner(new SpinnerNumberModel(current.maxDelayMillis(), 0L,
                GlobalRetryPolicy.MAX_DELAY_MILLIS, 1_000L));
        JSpinner multiplier = new JSpinner(new SpinnerNumberModel(current.backoffMultiplier(), 1.0, 10.0, 0.5));
        JCheckBox retryAfter = new JCheckBox("Honor Retry-After header", current.respectRetryAfter());
        JSpinner budget = new JSpinner(new SpinnerNumberModel(current.maxTotalWaitSeconds(), 1,
                (int) (GlobalRetryPolicy.MAX_DELAY_MILLIS / 1000), 5));
        JCheckBox cooldown = new JCheckBox("Host cooldown: hold other requests to a rate-limited host", current.hostCooldown());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(4, 0, 4, 10);
        lc.gridx = 0;
        GridBagConstraints fc = new GridBagConstraints();
        fc.anchor = GridBagConstraints.WEST;
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1;
        fc.insets = new Insets(4, 0, 4, 0);
        fc.gridx = 1;
        int row = 0;
        row = addRow(form, lc, fc, row, null, enabled);
        row = addRow(form, lc, fc, row, "Tools:", toolRow);
        row = addRow(form, lc, fc, row, null, scopeOnly);
        row = addRow(form, lc, fc, row, "Host regex (blank = any):", hostPattern);
        row = addRow(form, lc, fc, row, "Methods (blank = any):", methods);
        row = addRow(form, lc, fc, row, "Retry on status:", statusCodes);
        row = addRow(form, lc, fc, row, "…or response regex:", responsePattern);
        row = addRow(form, lc, fc, row, "Max retries:", maxRetries);
        row = addRow(form, lc, fc, row, "Base delay (ms):", baseDelay);
        row = addRow(form, lc, fc, row, "Max delay per wait (ms):", maxDelay);
        row = addRow(form, lc, fc, row, "Backoff multiplier:", multiplier);
        row = addRow(form, lc, fc, row, null, retryAfter);
        row = addRow(form, lc, fc, row, "Max total wait per request (s):", budget);
        addRow(form, lc, fc, row, null, cooldown);

        JLabel notes = new JLabel("<html><body style='width:520px'>"
                + "The Burp tool receives the final response; the original request is shown unchanged and the"
                + " response note records the resends. Requests that get no response at all (timeouts, dropped"
                + " connections) cannot be retried. Retrying non-idempotent methods on 5xx can repeat a side effect —"
                + " restrict methods if that matters. Scanner is off by default: retry waits distort time-based checks."
                + "</body></html>");

        JLabel error = new JLabel(" ");
        error.setForeground(dev.nquangit.corestra.ui.component.UiTheme.pick(new Color(0xC0392B), new Color(0xFF7B72)));
        JButton save = StyledButton.success("Save");
        JButton cancel = StyledButton.neutral("Cancel");
        save.addActionListener(event -> {
            try {
                for (JSpinner spinner : List.of(maxRetries, baseDelay, maxDelay, multiplier, budget)) spinner.commitEdit();
                List<String> selectedTools = tools.entrySet().stream().filter(entry -> entry.getValue().isSelected())
                        .map(Map.Entry::getKey).toList();
                GlobalRetryPolicy policy = new GlobalRetryPolicy(enabled.isSelected(), scopeOnly.isSelected(),
                        hostPattern.getText(), selectedTools, parseStatuses(statusCodes.getText()),
                        responsePattern.getText(), splitList(methods.getText()),
                        ((Number) maxRetries.getValue()).intValue(), ((Number) baseDelay.getValue()).longValue(),
                        ((Number) maxDelay.getValue()).longValue(), ((Number) multiplier.getValue()).doubleValue(),
                        retryAfter.isSelected(), ((Number) budget.getValue()).intValue(), cooldown.isSelected());
                repository.save(repository.load().withGlobalRetry(policy));
                saved = true;
                dispose();
            } catch (IllegalArgumentException | java.text.ParseException exception) {
                error.setText(exception.getMessage() == null ? "Check the retry settings." : exception.getMessage());
            }
        });
        cancel.addActionListener(event -> dispose());

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(form, BorderLayout.NORTH);
        content.add(notes, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(error, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(save);
        footer.add(buttons, BorderLayout.EAST);
        content.add(footer, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(save);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        pack();
        setLocationRelativeTo(parent);
    }

    private static int addRow(JPanel form, GridBagConstraints lc, GridBagConstraints fc, int row,
                              String label, JComponent field) {
        lc.gridy = row;
        fc.gridy = row;
        if (label == null) {
            GridBagConstraints span = (GridBagConstraints) fc.clone();
            span.gridx = 0;
            span.gridwidth = 2;
            form.add(field, span);
        } else {
            form.add(new JLabel(label), lc);
            form.add(field, fc);
        }
        return row + 1;
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
        return java.util.Arrays.stream(text.split("[,\\s]+")).map(String::strip).filter(token -> !token.isEmpty()).toList();
    }
}
