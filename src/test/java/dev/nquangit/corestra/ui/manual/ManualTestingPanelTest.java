package dev.nquangit.corestra.ui.manual;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.internal.*;
import burp.api.montoya.persistence.*;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.*;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.flow.*;
import dev.nquangit.corestra.flow.execution.*;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.integration.*;
import dev.nquangit.corestra.manual.*;
import dev.nquangit.corestra.persistence.*;
import dev.nquangit.corestra.session.*;
import dev.nquangit.corestra.ui.access.AccessUiController;
import dev.nquangit.corestra.ui.flowise.FlowWorkspaceController;
import dev.nquangit.corestra.ui.flowise.FlowEditorLogPanel;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.lang.reflect.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.nquangit.corestra.integration.ManualTestMessages.*;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("MontoyaObjectFactory")
class ManualTestingPanelTest {
    private MontoyaObjectFactory old;
    private final Map<String,String> saved=new HashMap<>();
    private final AtomicReference<String> edited=new AtomicReference<>("");
    private final AtomicBoolean modified=new AtomicBoolean();
    private final AtomicInteger editorCount=new AtomicInteger(),sends=new AtomicInteger();
    private final AtomicReference<java.util.function.Function<HttpRequest, HttpResponse>> responder = new AtomicReference<>(r -> ManualTestMessages.response("{\"token\":\"value\"}"));
    private FlowWorkspaceController source,manual;
    private ManualTestingPanel panel;
    private FlowRepository manualRepo;
    @BeforeEach void setup() throws Exception {
        old=ObjectFactoryLocator.FACTORY;ObjectFactoryLocator.FACTORY=factory();
        SwingUtilities.invokeAndWait(()->{
            var data=proxy(PersistedObject.class,(p,m,a)->switch(m.getName()){
                case "getString"->saved.get(a[0]);case "setString"->{saved.put((String)a[0],(String)a[1]);yield null;}
                case "deleteString"->{saved.remove(a[0]);yield null;}case "stringKeys"->Set.copyOf(saved.keySet());default->null;
            });
            var persistence=proxy(Persistence.class,(p,m,a)->m.getName().equals("extensionData")?data:null);
            source=new FlowWorkspaceController(new FlowRepository(persistence,new FlowSerializer(),new PersistenceMigrationService()));
            manualRepo=new FlowRepository(persistence,new FlowSerializer(),new PersistenceMigrationService(),"corestra.manual.flows.document");
            var extraction=new ExtractionService();var injection=new InjectionService();var sessions=new SessionProfileRepository();sessions.saveProfile(SessionProfile.general().withEnabled(false));
            var manager=new SessionManager(sessions,source::flows,new FlowEngine(extraction,injection,r->null),extraction,Clock.systemUTC());
            var selector=new SessionSelectionService(manager,SessionSelectionConfig::disabled,(c,o,t)->Optional.empty());
            var transport=new ManualSessionHttpClient(r->{sends.incrementAndGet();return responder.get().apply(r);},manager,selector,injection);
            var engine=new FlowEngine(extraction,injection,transport);var log=new FlowExecutionLog();engine.addExecutionListener(log::record);
            manual=new FlowWorkspaceController(manualRepo,engine);
            var ui=proxy(UserInterface.class,(p,m,a)->switch(m.getName()){
                case "createHttpRequestEditor"->{int index=editorCount.getAndIncrement();AtomicReference<HttpRequest> current=new AtomicReference<>();JPanel component=new JPanel();
                    yield proxy(HttpRequestEditor.class,(q,n,b)->switch(n.getName()){
                        case "uiComponent"->component;
                        case "setRequest"->{current.set((HttpRequest)b[0]);if(index==0){edited.set(b[0].toString());modified.set(false);}yield null;}
                        case "getRequest"->index==0?parse(service("api.test",443,true),edited.get()):current.get();case "isModified"->index==0&&modified.get();
                        case "selection"->Optional.empty();default->value(n.getReturnType());
                    });}
                case "createHttpResponseEditor"->{editorCount.incrementAndGet();JPanel component=new JPanel();AtomicReference<HttpResponse> current=new AtomicReference<>();
                    yield proxy(HttpResponseEditor.class,(q,n,b)->switch(n.getName()){
                        case "uiComponent"->component;case "setResponse"->{current.set((HttpResponse)b[0]);yield null;}case "getResponse"->current.get();case "selection"->Optional.empty();default->value(n.getReturnType());
                    });}
                default->value(m.getReturnType());
            });
            MontoyaApi api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("userInterface")?ui:null);
            panel=new ManualTestingPanel(manual,source,new ManualTestRunner(engine,transport),manager,new AccessUiController(new InMemoryAccessRepository()),log,api);
        });
    }
    @AfterEach void cleanup() throws Exception {SwingUtilities.invokeAndWait(()->{if(panel!=null)panel.close();});ObjectFactoryLocator.FACTORY=old;}
    private static Object value(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
    private static FlowDefinition validFlow(String id){var t=new StoredRequestTemplate("POST","https","api.test",443,"/one",List.of(),"original");
        return new FlowDefinition(id,id,"",true,List.of(new FlowStep("one","One",true,"",t,List.of(),List.of(),List.of(),FlowStep.StepErrorStrategy.FAIL_FLOW),
                new FlowStep("two","Two",true,"",new StoredRequestTemplate("GET","https","api.test",443,"/two",List.of(),""),List.of(),List.of(),List.of(),FlowStep.StepErrorStrategy.FAIL_FLOW)),null,null,null);}
    private void copy() {var f=validFlow("source");source.addFlow(f);panel.copyFrom(f);}
    private <T>T field(String key,Class<T> type){try{Field f=ManualTestingPanel.class.getDeclaredField(key);f.setAccessible(true);return type.cast(f.get(panel));}catch(Exception e){throw new AssertionError(e);}}
    private void edit(String body){edited.set("PATCH /modified?path=/login.php HTTP/1.1\r\nHost: virtual.test\r\n\r\n"+body);modified.set(true);}

    @Test void editingAndSwitchingRequestsSavesOnlyTheManualCopy() throws Exception {
        SwingUtilities.invokeAndWait(()->{copy();String first=manual.selectedStep().id();edit("{{value}}");manual.setSelectedStep(manual.activeFlow().steps().get(1));
            var savedStep=manualRepo.loadAll().getFirst().steps().getFirst();assertEquals("PATCH",savedStep.requestTemplate().method());assertEquals("{{value}}",savedStep.requestTemplate().body());
            assertEquals("original",source.flows().getFirst().steps().getFirst().requestTemplate().body());
            manual.setSelectedStep(manual.activeFlow().steps().getFirst());assertTrue(edited.get().contains("{{value}}"));assertEquals(first,manual.selectedStep().id());assertEquals(0,sends.get());});
    }
    @Test void invalidTargetPreventsSwitchAndKeepsUnsavedEditorContent() throws Exception {
        SwingUtilities.invokeAndWait(()->{copy();String first=manual.selectedStep().id();edit("keep-unsaved");field("target",JTextField.class).setText("https://api.test/not-an-origin");
            manual.setSelectedStep(manual.activeFlow().steps().get(1));assertEquals(first,manual.selectedStep().id());assertTrue(edited.get().contains("keep-unsaved"));
            assertEquals("original",manualRepo.loadAll().getFirst().steps().getFirst().requestTemplate().body());});
    }
    @Test void draftVariablesStaySeparateAreNotPersistedAndEditorsStayBounded() throws Exception {
        SwingUtilities.invokeAndWait(()->{copy();var first=manual.activeFlow();field("variables",DefaultTableModel.class).addRow(new String[]{"runtime","private-runtime-secret"});
            panel.copyFrom(source.flows().getFirst());var second=manual.activeFlow();assertEquals(0,field("variables",DefaultTableModel.class).getRowCount());
            for(int i=0;i<20;i++){manual.setActiveFlow(first);manual.setActiveFlow(second);}manual.setActiveFlow(first);
            assertEquals("private-runtime-secret",field("variables",DefaultTableModel.class).getValueAt(0,1));assertEquals(4,editorCount.get());
            assertTrue(saved.values().stream().noneMatch(v->v.contains("private-runtime-secret")));assertEquals(0,sends.get());});
    }
    @Test void changingSessionClearsVariablesByDefaultAndExplicitKeepOptionPreservesThem() throws Exception {
        SwingUtilities.invokeAndWait(()->{copy();var vars=field("variables",DefaultTableModel.class);vars.addRow(new String[]{"actorToken","x"});
            var combo=field("session",JComboBox.class);combo.setSelectedIndex(2);assertEquals(0,vars.getRowCount());
            vars.addRow(new String[]{"testObject","x-owned"});field("keepVariables",JCheckBox.class).setSelected(true);combo.setSelectedIndex(0);
            assertEquals("x-owned",vars.getValueAt(0,1));});
    }
    @Test void runSelectedCommitsTemplateSendsOnlyOneAndKeepsFocusOnItsResponse() throws Exception {
        AtomicReference<String> selected=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{copy();selected.set(manual.selectedStep().id());edit("manual-body");
            try {Method run=ManualTestingPanel.class.getDeclaredMethod("run",int.class);run.setAccessible(true);run.invoke(panel,0);}catch(Exception e){throw new AssertionError(e);}});
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline){AtomicBoolean done=new AtomicBoolean();SwingUtilities.invokeAndWait(()->{try{panel.assertIdle();done.set(true);}catch(IllegalStateException ignored){}});if(done.get())break;Thread.sleep(10);}
        SwingUtilities.invokeAndWait(()->{panel.assertIdle();assertEquals(1,sends.get());assertEquals(selected.get(),manual.selectedStep().id());
            assertEquals("manual-body",manual.activeFlow().steps().getFirst().requestTemplate().body());
            assertTrue(field("response",HttpResponseEditor.class).getResponse().bodyToString().contains("token"));});
    }
    @Test void cleanupClearsOnlyManualConfigurationAndRuntime() throws Exception {
        SwingUtilities.invokeAndWait(()->{copy();panel.clearWorkspace();assertTrue(manualRepo.loadAll().isEmpty());assertEquals(1,source.flows().size());assertNull(manual.activeFlow());});
    }
    @Test void fullHeightLogSwitchKeepsUnsavedRequestAndReusesNativeEditors() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            copy(); edit("unsaved-test-value");
            FlowEditorLogPanel view = findView(panel); assertNotNull(view);
            for (int i = 0; i < 20; i++) { view.showLog(true); assertTrue(view.isLogVisible()); view.showLog(false); }
            assertTrue(modified.get()); assertTrue(edited.get().contains("unsaved-test-value"));
            assertEquals(4, editorCount.get()); assertEquals(0, sends.get());
            manual.setSelectedStep(manual.activeFlow().steps().get(1));
            assertEquals("unsaved-test-value", manualRepo.loadAll().getFirst().steps().getFirst().requestTemplate().body());
        });
    }
    private static FlowEditorLogPanel findView(java.awt.Component component) {
        if (component instanceof FlowEditorLogPanel view) return view;
        if (component instanceof java.awt.Container container) for (var child : container.getComponents()) {
            var found = findView(child); if (found != null) return found;
        }
        return null;
    }
    @Test void runAllUpdatesRowsDuringBlockedRequestsWithoutChangingSelectionEditorOrPersistence() throws Exception {
        var first = new CountDownLatch(1); var second = new CountDownLatch(1); var releaseFirst = new CountDownLatch(1); var releaseSecond = new CountDownLatch(1);
        responder.set(r -> {
            try {
                if (sends.get() == 1) { first.countDown(); assertTrue(releaseFirst.await(5, TimeUnit.SECONDS)); }
                if (sends.get() == 2) { second.countDown(); assertTrue(releaseSecond.await(5, TimeUnit.SECONDS)); }
                return ManualTestMessages.response("HTTP/1.1 " + (sends.get() == 1 ? "302 Found" : "403 Forbidden") + "\r\n\r\nresult");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        });
        Map<String, String> persisted = new HashMap<>(); AtomicReference<String> selection = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                copy(); selection.set(manual.selectedStep().id()); persisted.putAll(saved); invokeRun(2);
            });
            assertTrue(first.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(ManualRunProgress.State.RUNNING, rowState(0)); assertEquals(ManualRunProgress.State.NOT_RUN, rowState(1));
                assertEquals("", rowCode(0)); assertEquals("", rowCode(1));
                assertEquals(persisted, saved); edit("prepared-for-next-run");
            });
            releaseFirst.countDown(); assertTrue(second.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(ManualRunProgress.State.COMPLETED, rowState(0)); assertEquals(ManualRunProgress.State.RUNNING, rowState(1));
                assertEquals("302", rowCode(0)); assertEquals("", rowCode(1));
                assertEquals(selection.get(), manual.selectedStep().id()); assertTrue(edited.get().contains("prepared-for-next-run"));
                assertEquals(persisted, saved); assertEquals(4, editorCount.get());
            });
            releaseSecond.countDown(); waitIdle();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(ManualRunProgress.State.COMPLETED, rowState(0)); assertEquals(ManualRunProgress.State.COMPLETED, rowState(1));
                assertEquals("302", rowCode(0)); assertEquals("403", rowCode(1));
                assertEquals(selection.get(), manual.selectedStep().id()); assertTrue(edited.get().contains("prepared-for-next-run"));
                assertTrue(field("progressLabel", JLabel.class).getText().contains("2/2 done"));
            });
        } finally { releaseFirst.countDown(); releaseSecond.countDown(); }
    }
    @Test void stoppingRunAndStartingAnotherClearsOldColorsAndDoesNotSendRemainingRequests() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        responder.set(r -> { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException("cancelled"); } return ManualTestMessages.response("ok"); });
        try {
            SwingUtilities.invokeAndWait(() -> { copy(); invokeRun(2); }); assertTrue(entered.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> field("stop", JButton.class).doClick()); waitIdle();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(1, sends.get()); assertEquals(ManualRunProgress.State.STOPPED, rowState(0)); assertEquals(ManualRunProgress.State.NOT_RUN, rowState(1));
            });
            responder.set(r -> ManualTestMessages.response("ok"));
            SwingUtilities.invokeAndWait(() -> invokeRun(0)); waitIdle();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(ManualRunProgress.State.COMPLETED, rowState(0)); assertEquals(ManualRunProgress.State.SKIPPED, rowState(1));
                assertEquals(2, sends.get());
            });
        } finally { release.countDown(); }
    }
    private ManualRunProgress.State rowState(int row) {
        try {
            var steps = field("steps", dev.nquangit.corestra.ui.flowise.StepListPanel.class);
            var f = steps.getClass().getDeclaredField("listContainer"); f.setAccessible(true);
            return (ManualRunProgress.State) ((JPanel) ((JPanel) f.get(steps)).getComponent(row)).getClientProperty("executionState");
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private String rowCode(int row) {
        try {
            var steps = field("steps", dev.nquangit.corestra.ui.flowise.StepListPanel.class);
            var f = steps.getClass().getDeclaredField("listContainer"); f.setAccessible(true);
            return findHttpCode(((JPanel) f.get(steps)).getComponent(row));
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static String findHttpCode(java.awt.Component component) {
        if (component instanceof JLabel label && "requestHttpStatus".equals(label.getName())) return label.getText();
        if (component instanceof java.awt.Container c) for (var child : c.getComponents()) { String value = findHttpCode(child); if (value != null) return value; }
        return null;
    }
    @Test void manualSidebarKeepsUserWidthWithOversizedNativeEditorsAcrossFlowsAndViews() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            copy(); var first = manual.activeFlow();
            var split = (JSplitPane) ((java.awt.BorderLayout) panel.getLayout()).getLayoutComponent(java.awt.BorderLayout.CENTER);
            split.setSize(1100, 700); split.doLayout(); split.setDividerLocation(320); split.doLayout();
            field("request", HttpRequestEditor.class).uiComponent().setMinimumSize(new java.awt.Dimension(1800, 800));
            field("response", HttpResponseEditor.class).uiComponent().setMinimumSize(new java.awt.Dimension(1800, 800));
            panel.copyFrom(source.flows().getFirst()); split.doLayout(); assertEquals(320, split.getLeftComponent().getWidth());
            manual.setActiveFlow(first); split.setSize(1400, 700); split.doLayout(); assertEquals(320, split.getLeftComponent().getWidth());
            var view = (FlowEditorLogPanel) split.getRightComponent(); view.showLog(true); split.doLayout(); assertEquals(320, split.getLeftComponent().getWidth());
            view.showLog(false); split.doLayout(); assertEquals(320, split.getLeftComponent().getWidth());
            assertEquals(4, editorCount.get());
        });
    }
    private void invokeRun(int mode) { try { Method run=ManualTestingPanel.class.getDeclaredMethod("run",int.class);run.setAccessible(true);run.invoke(panel,mode); } catch(Exception e) { throw new AssertionError(e); } }
    private void waitIdle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            AtomicBoolean done = new AtomicBoolean(); SwingUtilities.invokeAndWait(() -> { try { panel.assertIdle(); done.set(true); } catch (IllegalStateException ignored) { } });
            if (done.get()) return; Thread.sleep(10);
        }
        fail("Manual run did not finish");
    }
}
