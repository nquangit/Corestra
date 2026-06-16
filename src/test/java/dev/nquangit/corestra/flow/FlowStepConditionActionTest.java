package dev.nquangit.corestra.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.flow.control.ConditionMatchPolicy;
import dev.nquangit.corestra.flow.control.ConditionValueSource;
import dev.nquangit.corestra.flow.control.StepAction;
import dev.nquangit.corestra.flow.control.StepActionType;
import dev.nquangit.corestra.flow.control.StepCondition;
import dev.nquangit.corestra.flow.control.StepConditionActionRule;
import dev.nquangit.corestra.flow.control.StepConditionOperator;
import dev.nquangit.corestra.flow.control.StepConditionSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowStepConditionActionTest {

    @Test
    void oldSerializedStepDefaultsConditionActionRulesToEmpty() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        FlowStep step = mapper.readValue("""
                {
                  "id": "step-1",
                  "name": "Step",
                  "enabled": true,
                  "notes": "",
                  "requestTemplate": {
                    "method": "GET",
                    "scheme": "http",
                    "host": "example.test",
                    "port": 80,
                    "pathAndQuery": "/",
                    "headerLines": [],
                    "body": ""
                  },
                  "extractRules": [],
                  "injectRules": [],
                  "preconditions": [],
                  "errorStrategy": "FAIL_FLOW"
                }
                """, FlowStep.class);

        assertTrue(step.conditionActionRules().isEmpty());
    }

    @Test
    void serializesConditionActionRules() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        FlowStep step = step().withConditionActionRules(List.of(rule()));

        FlowStep roundTripped = mapper.readValue(mapper.writeValueAsString(step), FlowStep.class);

        assertEquals(1, roundTripped.conditionActionRules().size());
        assertEquals(StepActionType.BREAK_FLOW, roundTripped.conditionActionRules().getFirst().actions().getFirst().type());
    }

    private static FlowStep step() {
        return new FlowStep(
                "step-1",
                "Step",
                true,
                "",
                new StoredRequestTemplate("GET", "http", "example.test", 80, "/", List.of(), ""),
                List.of(),
                List.of(),
                List.of(),
                FlowStep.StepErrorStrategy.FAIL_FLOW);
    }

    private static StepConditionActionRule rule() {
        return new StepConditionActionRule(
                "rule-1",
                "Rule",
                true,
                ConditionMatchPolicy.ALL,
                List.of(new StepCondition(
                        "condition-1",
                        "Status",
                        true,
                        StepConditionSource.RESPONSE_STATUS,
                        "",
                        StepConditionOperator.EQUALS,
                        ConditionValueSource.STATIC,
                        "200",
                        true,
                        false)),
                List.of(new StepAction(
                        "action-1",
                        "Break",
                        true,
                        StepActionType.BREAK_FLOW,
                        Map.of())));
    }
}
