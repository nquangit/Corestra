package dev.nquangit.corestra.ui.flowise;

import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import dev.nquangit.corestra.persistence.FlowRepository;
import dev.nquangit.corestra.persistence.FlowSerializer;
import dev.nquangit.corestra.persistence.PersistenceMigrationService;
import org.junit.jupiter.api.Test;

import javax.swing.JSplitPane;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RuleSummaryPanelTest {

    @Test
    void keepsExtractAndInjectVisibleSideBySide() {
        Map<String, String> store = new HashMap<>();
        PersistedObject extensionData = (PersistedObject) Proxy.newProxyInstance(
                PersistedObject.class.getClassLoader(),
                new Class<?>[]{PersistedObject.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getString" -> store.get((String) args[0]);
                    case "setString" -> {
                        store.put((String) args[0], (String) args[1]);
                        yield null;
                    }
                    case "deleteString" -> {
                        store.remove((String) args[0]);
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                });
        Persistence persistence = (Persistence) Proxy.newProxyInstance(
                Persistence.class.getClassLoader(),
                new Class<?>[]{Persistence.class},
                (proxy, method, args) -> method.getName().equals("extensionData")
                        ? extensionData
                        : defaultValue(method.getReturnType()));
        FlowRepository repository = new FlowRepository(
                persistence, new FlowSerializer(), new PersistenceMigrationService());

        RuleSummaryPanel panel = new RuleSummaryPanel(new FlowWorkspaceController(repository));

        JSplitPane split = findHorizontalSplit(panel);
        assertNotNull(split);
        assertEquals(JSplitPane.HORIZONTAL_SPLIT, split.getOrientation());
    }

    @Test
    void reservesACompactDefaultHeightForStepDetails() {
        assertEquals(592, FlowWorkspacePanel.defaultDetailDividerLocation(800));
        assertEquals(370, FlowWorkspacePanel.defaultDetailDividerLocation(500));
    }

    private static JSplitPane findHorizontalSplit(Component component) {
        if (component instanceof JSplitPane split
                && split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT) {
            return split;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JSplitPane found = findHorizontalSplit(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) return null;
        if (returnType == boolean.class) return false;
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0F;
        if (returnType == double.class) return 0D;
        if (returnType == char.class) return '\0';
        return null;
    }
}
