package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthProvidersPanelTest {
    @Test
    void providerFormShowsFlowContractAndGeneratesSuggestedBearerRule() throws Exception {
        FlowDefinition login = loginFlow();
        AuthProviderFormModel model = new AuthProviderFormModel(
                "Provider", "", true, login.id(), "", "", AuthFallbackPolicy.LOGIN_ONLY,
                AuthRetryPolicy.NO_RETRY, AuthProvider.DEFAULT_STATE_TTL_SECONDS,
                List.of(), List.of(), List.of());
        JPanel form = constructForm(model, List.of(login));

        JTextArea contract = descendants(form, JTextArea.class).stream()
                .filter(area -> area.getText().contains("Produced Auth State"))
                .findFirst()
                .orElseThrow();
        assertTrue(contract.getText().contains("accessToken → {{auth.accessToken}}"));

        JButton generate = descendants(form, JButton.class).stream()
                .filter(button -> "Generate from flow".equals(button.getText()))
                .findFirst()
                .orElseThrow();
        JTable injectionTable = descendants(form, JTable.class).stream()
                .filter(table -> table.getModel().getColumnCount() == 6)
                .findFirst()
                .orElseThrow();

        SwingUtilities.invokeAndWait(generate::doClick);

        assertEquals(1, injectionTable.getModel().getRowCount());
        assertEquals("Authorization", injectionTable.getModel().getValueAt(0, 2));
        assertEquals("Bearer {{auth.accessToken}}", injectionTable.getModel().getValueAt(0, 3));
    }

    private static JPanel constructForm(AuthProviderFormModel model, List<FlowDefinition> flows) throws Exception {
        Class<?> formClass = Class.forName(
                "dev.nquangit.corestra.ui.access.AuthProvidersPanel$AuthProviderFormPanel");
        Constructor<?> constructor = formClass.getDeclaredConstructor(AuthProviderFormModel.class, List.class);
        constructor.setAccessible(true);
        AtomicReference<JPanel> form = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                form.set((JPanel) constructor.newInstance(model, flows));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertNotNull(form.get());
        return form.get();
    }

    private static <T extends Component> List<T> descendants(Container root, Class<T> type) {
        ArrayList<T> matches = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                matches.add(type.cast(child));
            }
            if (child instanceof Container container) {
                matches.addAll(descendants(container, type));
            }
        }
        return matches;
    }

    private static FlowDefinition loginFlow() {
        ExtractRule accessToken = new ExtractRule(
                "extract-token", "Access token", true, ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.JSON_PATH, "$.access_token", "accessToken", List.of());
        FlowStep step = new FlowStep(
                "login-step", "Login", true, "",
                new StoredRequestTemplate("POST", "https", "example.test", 443, "/login", List.of(), ""),
                List.of(accessToken), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
        return new FlowDefinition(
                "login-flow", "Login", "", true, List.of(step), FlowRuleSet.empty(),
                AggregationPlan.disabled(), FlowTriggerConfig.none());
    }
}
