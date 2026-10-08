package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.http.transform.ValueTransformResult;
import dev.nquangit.corestra.http.transform.ValueTransformService;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.HexFormat;

/** Shared syntax reference and sample-value tester for extract/inject transform chains. */
final class ValueTransformHelp {
    private static final String SYNTAX = """
            One transform per line; operations run from top to bottom.
            Names are case-insensitive. Arguments are literal, without quotes.

            trim                              Remove surrounding whitespace
            lowercase (or lower)              Convert to lowercase
            uppercase (or upper)              Convert to uppercase
            urlEncode / urlDecode             UTF-8 form encoding (+ means space)
            base64Encode                      Raw bytes → Base64; text uses UTF-8
            base64Decode                      Base64 → UTF-8 text
            hexEncode                         Raw bytes or UTF-8 text → hex
            decodeText[:CHARSET]               Raw bytes → text; default UTF-8, strict
            substring:START[:END]             Slice bytes/text; 0-based, END exclusive
            prefix:TEXT / suffix:TEXT          Add literal text before / after value
            stripPrefix:TEXT / stripSuffix:TEXT Remove only if present (case-sensitive)
            replaceRegex:PATTERN=>REPLACEMENT  Java regex, all matches; $1 = group 1

            Bearer example: prefix:Bearer followed by ONE space.
            Use “Add Bearer prefix” to insert it with the space preserved.
            Do not type quotes or \\s to represent that space.

            Binary image: BODY_RAW → base64Encode → prefix:data:image/png;base64,
            BODY_RAW extracts only; conversion is always an explicit transform.
            Text operations require conversion first. A raw result cannot enter a text variable.

            A failed transform skips the entire extract/inject rule and reports an error.
            Injection templates resolve first; transforms then apply to the whole value.
            """;

    private ValueTransformHelp() { }

    static JPanel controls(JTextArea transforms) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton help = new JButton("Syntax & test…");
        JButton bearer = new JButton("Add Bearer prefix");
        help.addActionListener(event -> showTester(transforms));
        bearer.setToolTipText("Append prefix:Bearer with its required trailing space");
        bearer.addActionListener(event -> {
            String text = transforms.getText();
            transforms.append((text.isEmpty() || text.endsWith("\n") ? "" : "\n") + "prefix:Bearer ");
            transforms.requestFocusInWindow();
        });
        panel.add(help);
        panel.add(bearer);
        return panel;
    }

    private static void showTester(JTextArea transforms) {
        JTextArea syntax = new JTextArea(SYNTAX, 19, 72);
        syntax.setEditable(false);
        JTextArea sample = new JTextArea("example-token", 3, 72);
        JTextArea output = new JTextArea(4, 72);
        output.setEditable(false);
        JLabel status = new JLabel("Tests the current chain on this sample only.");
        JComboBox<String> inputType = new JComboBox<>(new String[]{"Text", "Raw bytes (hex)"});
        inputType.setToolTipText("For raw bytes enter hexadecimal, for example 89 50 4e 47 00 ff");
        JButton test = new JButton("Test current transforms");
        test.addActionListener(event -> {
            ValueTransformResult result;
            try {
                result = inputType.getSelectedIndex() == 0
                        ? new ValueTransformService().apply(sample.getText(), RuleEditorDialog.transformLines(transforms.getText()))
                        : new ValueTransformService().applyBytes(HexFormat.of().parseHex(sample.getText().replaceAll("\\s+", "")),
                                RuleEditorDialog.transformLines(transforms.getText()));
            } catch (IllegalArgumentException exception) {
                status.setText("Invalid raw hex input: " + exception.getMessage());
                output.setText("");
                return;
            }
            status.setText(result.success() ? "Success — length: " + result.value().length() : result.error());
            output.setText(result.success() ? result.value() : "");
        });
        JPanel values = new JPanel(new java.awt.GridLayout(2, 1, 0, 6));
        JScrollPane samplePane = new JScrollPane(sample);
        samplePane.setBorder(BorderFactory.createTitledBorder("Sample input"));
        JScrollPane outputPane = new JScrollPane(output);
        outputPane.setBorder(BorderFactory.createTitledBorder("Transformed output"));
        values.add(samplePane);
        values.add(outputPane);
        JPanel actions = new JPanel(new BorderLayout(4, 4));
        JPanel testControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        testControls.add(new JLabel("Sample type:"));
        testControls.add(inputType);
        testControls.add(test);
        actions.add(testControls, BorderLayout.NORTH);
        actions.add(status, BorderLayout.CENTER);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JScrollPane(syntax), BorderLayout.NORTH);
        panel.add(values, BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        JOptionPane.showMessageDialog(transforms, panel, "Value transforms", JOptionPane.PLAIN_MESSAGE);
    }
}
