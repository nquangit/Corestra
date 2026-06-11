package dev.nquangit.corestra.access.matrix;

import dev.nquangit.corestra.access.postcheck.PostCheckAssertion;
import dev.nquangit.corestra.access.postcheck.PostCheckAssertionType;
import dev.nquangit.corestra.access.postcheck.PostCheckAuthBinding;
import dev.nquangit.corestra.access.postcheck.PostCheckRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccessTestCaseTest {
    @Test
    void defaultSetupNoneWorks() {
        AccessTestCase testCase = new AccessTestCase(
                "case-1",
                "Target reads owner account",
                "tpl-1",
                "flow-1",
                "step-1",
                null,
                null,
                null,
                null,
                true,
                null,
                0L);

        assertEquals(SetupMode.NONE, testCase.setup().mode());
        assertEquals(TargetBinding.empty(), testCase.target());
        assertEquals(ExpectedResult.REVIEW, testCase.expected());
        assertEquals("", testCase.evaluationProfileId());
        assertEquals(List.of(), testCase.postChecks());
        assertEquals(RunStatus.PENDING, testCase.status());
    }

    @Test
    void evaluationProfileIdIsOptionalAndStoredWhenProvided() {
        AccessTestCase testCase = new AccessTestCase(
                "case-1",
                "Target reads owner account",
                "tpl-1",
                "flow-1",
                "step-1",
                SetupConfig.none(),
                TargetBinding.empty(),
                ExpectedResult.ALLOW,
                "eval-1",
                List.of(),
                true,
                RunStatus.PENDING,
                0L);

        assertEquals("eval-1", testCase.evaluationProfileId());
    }

    @Test
    void postChecksAreDefensivelyCopiedAndUnmodifiable() {
        List<PostCheckAssertion> assertions = new ArrayList<>();
        assertions.add(new PostCheckAssertion(
                "assert-1",
                "Balance unchanged",
                true,
                PostCheckAssertionType.JSON_PATH_EQUALS,
                "balance",
                "100",
                false));
        PostCheckRequest postCheck = new PostCheckRequest(
                "post-1",
                "Check balance",
                true,
                "template-post",
                "",
                PostCheckAuthBinding.DATA_OWNER,
                "",
                "",
                assertions);
        List<PostCheckRequest> postChecks = new ArrayList<>();
        postChecks.add(postCheck);

        AccessTestCase testCase = new AccessTestCase(
                "case-1",
                "Target updates owner account",
                "tpl-1",
                "flow-1",
                "step-1",
                SetupConfig.none(),
                TargetBinding.empty(),
                ExpectedResult.DENY,
                "",
                List.of(),
                postChecks,
                true,
                RunStatus.PENDING,
                0L);
        postChecks.clear();
        assertions.clear();

        assertEquals(1, testCase.postChecks().size());
        assertEquals(1, testCase.postChecks().getFirst().assertions().size());
        assertThrows(UnsupportedOperationException.class, () -> testCase.postChecks().clear());
        assertThrows(UnsupportedOperationException.class, () -> testCase.postChecks().getFirst().assertions().clear());
    }

    @Test
    void runPrefixSetupStoresFlowAndStepIds() {
        SetupConfig setup = new SetupConfig(
                SetupMode.RUN_PREFIX,
                "flow-login",
                "step-transfer",
                "auth-owner",
                SetupAuthBinding.DATA_OWNER,
                ScenarioPolicy.FRESH_PER_TEST);
        AccessTestCase testCase = sampleCase(setup, TargetBinding.empty());

        assertEquals(SetupMode.RUN_PREFIX, testCase.setup().mode());
        assertEquals("flow-login", testCase.setup().setupFlowId());
        assertEquals("step-transfer", testCase.setup().untilBeforeStepId());
        assertEquals("auth-owner", testCase.setup().setupAuthProfileId());
        assertEquals(SetupAuthBinding.DATA_OWNER, testCase.setup().setupAuthBinding());
        assertEquals(ScenarioPolicy.FRESH_PER_TEST, testCase.setup().scenarioPolicy());
    }

    @Test
    void targetBindingStoresAuthDataEnvironmentAndScenarioIds() {
        TargetBinding target = new TargetBinding("auth-target", "data-owner", "env-dev", "scenario-1");
        AccessTestCase testCase = sampleCase(SetupConfig.none(), target);

        assertEquals("auth-target", testCase.target().targetAuthProfileId());
        assertEquals("data-owner", testCase.target().dataProfileId());
        assertEquals("env-dev", testCase.target().environmentId());
        assertEquals("scenario-1", testCase.target().scenarioInstanceId());
    }

    @Test
    void assertionsAreDefensivelyCopiedAndUnmodifiable() {
        List<ResponseAssertion> assertions = new ArrayList<>();
        assertions.add(new ResponseAssertion("assert-1", "Deny status", AssertionType.STATUS_CODE, "status", "403", true));

        AccessTestCase testCase = new AccessTestCase(
                "case-1",
                "Target reads owner account",
                "tpl-1",
                "flow-1",
                "step-1",
                SetupConfig.none(),
                TargetBinding.empty(),
                ExpectedResult.DENY,
                assertions,
                true,
                RunStatus.PENDING,
                0L);
        assertions.clear();

        assertEquals(1, testCase.assertions().size());
        assertThrows(UnsupportedOperationException.class, () -> testCase.assertions().add(
                new ResponseAssertion("assert-2", "Body", AssertionType.BODY_CONTAINS, "$", "denied", true)));
    }

    private static AccessTestCase sampleCase(SetupConfig setup, TargetBinding target) {
        return new AccessTestCase(
                "case-1",
                "Target reads owner account",
                "tpl-1",
                "flow-1",
                "step-1",
                setup,
                target,
                ExpectedResult.DENY,
                List.of(),
                true,
                RunStatus.PENDING,
                0L);
    }
}
