package dev.nquangit.corestra.watch;

import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;

import java.util.List;

/** Shared Watch configuration fixtures for persistence and UI-model tests. */
public final class WatchConfigFixtures {
    private WatchConfigFixtures() {
    }

    /**
     * Returns a watch with every field set to a non-default value.
     *
     * @param id watch id
     * @return definition
     */
    public static WatchDefinition fullWatch(String id) {
        StoredRequestTemplate probe = new StoredRequestTemplate("GET", "https", "bank.test", 8443,
                "/accounts?owner={{owner}}", List.of("Accept: application/json", "Cookie: sid=abc"), "");
        ExtractRule amount = new ExtractRule("r1", "amount", true, ExtractRule.SourceSide.REQUEST,
                ExtractRule.ExtractorType.JSON_PATH, "$.amount", "amount", List.of());
        ExtractRule account = new ExtractRule("r2", "from", true, ExtractRule.SourceSide.REQUEST,
                ExtractRule.ExtractorType.REGEX, "from=(\\d+)", "from", List.of(),
                ExtractRule.RegexSource.FULL_MESSAGE, 1);
        WatchObservation balances = new WatchObservation("o1", "balance", WatchObservation.Source.JSON_COLLECTION,
                "", "$.accounts", "accountNo", "balance.available", WatchObservation.ValueType.NUMBER,
                new WatchNumberFormat(false, ",", ".", 2, "[^0-9.,-]"));
        WatchObservation status = new WatchObservation("o2", "status", WatchObservation.Source.REGEX,
                "status=(\\w+)", "", "", "", WatchObservation.ValueType.TEXT, null);
        WatchExpectation fee = new WatchExpectation("e1", true, "balance[*]", WatchExpectation.Type.DELTA_BETWEEN,
                "-{{amount}} - 10", "-{{amount}}", "0.01");
        return new WatchDefinition(id, "Transfer " + id, true,
                new WatchScope(List.of("post", "PUT"), "bank\\.test", "^/transfer"),
                new WatchProbe(probe, List.of("Authorization", "X-Csrf")),
                List.of(amount, account), List.of(balances, status), List.of(fee),
                new WatchDisplayOptions(false, true, true, false),
                new WatchTiming(2_000, 300, 3, 9_000, 20_000));
    }
}
