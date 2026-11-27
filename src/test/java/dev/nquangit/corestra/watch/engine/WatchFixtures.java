package dev.nquangit.corestra.watch.engine;

import dev.nquangit.corestra.watch.WatchNumberFormat;
import dev.nquangit.corestra.watch.WatchObservation;
import dev.nquangit.corestra.watch.WatchObservation.Source;
import dev.nquangit.corestra.watch.WatchObservation.ValueType;
import dev.nquangit.corestra.watch.WatchProbeClient.ProbeResponse;

import java.util.List;

/** Shared builders and realistic bodies for Watch engine tests. */
final class WatchFixtures {
    private WatchFixtures() {
    }

    static String bankAccounts(String checking, String savings) {
        return """
                {"status":"ok","customer":{"id":"C-77","tier":"GOLD"},
                 "accounts":[
                   {"accountNo":"0123","type":"CHECKING","currency":"VND","balance":{"available":%s,"ledger":%s}},
                   {"accountNo":"0456","type":"SAVINGS","currency":"VND","balance":{"available":%s,"ledger":%s}}
                 ],
                 "summary":{"total":"%s"}}
                """.formatted(checking, checking, savings, savings, "n/a");
    }

    static String vndHtml(String checking, String savings) {
        return """
                <table class="accounts">
                  <tr><td class="no">0123</td><td class="bal">%s VND</td></tr>
                  <tr><td class="no">0456</td><td class="bal">%s VND</td></tr>
                </table>
                <div id="total">Total: %s</div>
                """.formatted(checking, savings, "n/a");
    }

    static ProbeResponse ok(String body) {
        return new ProbeResponse(200, List.of("Content-Type: application/json"), body, null);
    }

    static WatchObservation jsonCollection(String name, String itemsPath, String keyPath, String valuePath) {
        return new WatchObservation("o-" + name, name, Source.JSON_COLLECTION, "", itemsPath, keyPath, valuePath,
                ValueType.NUMBER, WatchNumberFormat.automatic());
    }

    static WatchObservation jsonSelector(String name, String selector, ValueType type) {
        return new WatchObservation("o-" + name, name, Source.JSON_SELECTOR, selector, "", "", "", type,
                WatchNumberFormat.automatic());
    }

    static WatchObservation regex(String name, String regex, ValueType type) {
        return new WatchObservation("o-" + name, name, Source.REGEX, regex, "", "", "", type,
                WatchNumberFormat.automatic());
    }

    static WatchObservation regexCollection(String name, String regex, WatchNumberFormat format) {
        return new WatchObservation("o-" + name, name, Source.REGEX_COLLECTION, regex, "", "", "", ValueType.NUMBER,
                format);
    }
}
