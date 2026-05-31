package dev.nquangit.corestra.persistence;

import burp.api.montoya.persistence.Preferences;
import dev.nquangit.corestra.config.CorestraPreferencesKeys;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UiPreferencesRepositoryTest {
    @Test
    void setLastSelectedFlowIdStoresNonBlankValue() {
        FakePreferencesState preferencesState = new FakePreferencesState();
        UiPreferencesRepository repository = new UiPreferencesRepository(preferencesState.proxy());

        repository.setLastSelectedFlowId("flow-login");

        assertEquals("flow-login", preferencesState.getString(CorestraPreferencesKeys.LAST_SELECTED_FLOW_ID));
        assertEquals(1, preferencesState.setStringCallCount());
        assertEquals(0, preferencesState.deleteStringCallCount());
    }

    @Test
    void setLastSelectedFlowIdClearsNullOrBlankValues() {
        FakePreferencesState preferencesState = new FakePreferencesState();
        UiPreferencesRepository repository = new UiPreferencesRepository(preferencesState.proxy());
        preferencesState.seedString(CorestraPreferencesKeys.LAST_SELECTED_FLOW_ID, "flow-login");

        repository.setLastSelectedFlowId(null);
        repository.setLastSelectedFlowId("   ");

        assertNull(preferencesState.getString(CorestraPreferencesKeys.LAST_SELECTED_FLOW_ID));
        assertEquals(0, preferencesState.setStringCallCount());
        assertEquals(2, preferencesState.deleteStringCallCount());
    }

    @Test
    void lastSelectedFlowIdReturnsNullAndClearsStoredBlankValue() {
        FakePreferencesState preferencesState = new FakePreferencesState();
        UiPreferencesRepository repository = new UiPreferencesRepository(preferencesState.proxy());
        preferencesState.seedString(CorestraPreferencesKeys.LAST_SELECTED_FLOW_ID, "  ");

        assertNull(repository.lastSelectedFlowId());
        assertNull(preferencesState.getString(CorestraPreferencesKeys.LAST_SELECTED_FLOW_ID));
        assertEquals(1, preferencesState.deleteStringCallCount());
    }

    @Test
    void clearLastSelectedFlowIdDeletesStoredValue() {
        FakePreferencesState preferencesState = new FakePreferencesState();
        UiPreferencesRepository repository = new UiPreferencesRepository(preferencesState.proxy());
        preferencesState.seedString(CorestraPreferencesKeys.LAST_SELECTED_FLOW_ID, "flow-login");

        repository.clearLastSelectedFlowId();

        assertNull(preferencesState.getString(CorestraPreferencesKeys.LAST_SELECTED_FLOW_ID));
        assertEquals(1, preferencesState.deleteStringCallCount());
    }

    private static final class FakePreferencesState {
        private final Map<String, String> stringValues = new HashMap<>();
        private int setStringCallCount;
        private int deleteStringCallCount;
        private final Preferences proxy = (Preferences) Proxy.newProxyInstance(
                Preferences.class.getClassLoader(),
                new Class<?>[]{Preferences.class},
                (instance, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return handleObjectMethod(instance, method.getName(), args);
                    }

                    return switch (method.getName()) {
                        case "getString" -> stringValues.get((String) args[0]);
                        case "setString" -> {
                            setStringCallCount++;
                            stringValues.put((String) args[0], (String) args[1]);
                            yield null;
                        }
                        case "deleteString" -> {
                            deleteStringCallCount++;
                            stringValues.remove((String) args[0]);
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException(method.toString());
                    };
                });

        private Preferences proxy() {
            return proxy;
        }

        private void seedString(String key, String value) {
            stringValues.put(key, value);
        }

        private String getString(String key) {
            return stringValues.get(key);
        }

        private int setStringCallCount() {
            return setStringCallCount;
        }

        private int deleteStringCallCount() {
            return deleteStringCallCount;
        }
    }

    private static Object handleObjectMethod(Object proxy, String methodName, Object[] args) {
        return switch (methodName) {
            case "toString" -> proxy.getClass().getName();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(methodName);
        };
    }
}
