package dev.nquangit.corestra.ui.flowise;
import burp.api.montoya.*;
import burp.api.montoya.persistence.*;
import burp.api.montoya.ui.*;
import burp.api.montoya.ui.editor.*;
import burp.api.montoya.internal.*;
import dev.nquangit.corestra.flow.*;
import dev.nquangit.corestra.flow.execution.*;
import dev.nquangit.corestra.persistence.*;
import dev.nquangit.corestra.ui.flowise.*;
import javax.swing.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("MontoyaObjectFactory")
class FlowisePerformanceTest {
  MontoyaObjectFactory previous;
  @BeforeEach void setUp() {
    previous = ObjectFactoryLocator.FACTORY;
    ObjectFactoryLocator.FACTORY=mock(MontoyaObjectFactory.class,(p,m,a)->value(m));
    created=requests=responses=writes=reads=0;
    store = new java.util.concurrent.ConcurrentHashMap<>();
  }
  @AfterEach void tearDown() { ObjectFactoryLocator.FACTORY=previous; }
  static int created, requests, responses, writes, reads;
  static long readChars, writtenChars;
  static Map<String,String> store = new HashMap<>();
  static Object value(Method m) {
    Class<?> c=m.getReturnType();
    if(c==void.class)return null;
    if(c==boolean.class)return false;
    if(c==int.class)return 0;
    if(c==long.class)return 0L;
    if(c==String.class)return "";
    if(c==Optional.class)return Optional.empty();
    if(c==List.class)return List.of();
    if(c==Set.class)return Set.of();
    if(c.isInterface())return mock(c,(p,x,a)->value(x));
    return null;
  }
  interface Call { Object call(Object p, Method m, Object[] a) throws Throwable; }
  @SuppressWarnings("unchecked") static <T>T mock(Class<T> c,Call f){
    return (T)Proxy.newProxyInstance(c.getClassLoader(),new Class<?>[]{c},(p,m,a)->{
      if(m.getName().equals("hashCode"))return System.identityHashCode(p);
      if(m.getName().equals("equals"))return p==a[0];
      return f.call(p,m,a);
    });
  }
  static Persistence persistence(){
    PersistedObject data=mock(PersistedObject.class,(p,m,a)->switch(m.getName()){
      case "getString"->{reads++;String s=store.get(a[0]);if(s!=null)readChars+=s.length();yield s;}
      case "setString"->{writes++;writtenChars+=((String)a[1]).length();store.put((String)a[0],(String)a[1]);yield null;}
      case "deleteString"->{store.remove(a[0]);yield null;}
      case "stringKeys"->{ assertFalse(SwingUtilities.isEventDispatchThread()); yield Set.copyOf(store.keySet()); }
      default->value(m);
    });
    Preferences prefs=mock(Preferences.class,(p,m,a)-> m.getName().equals("getString")?null:value(m));
    return mock(Persistence.class,(p,m,a)->switch(m.getName()){
      case "extensionData"->data; case "preferences"->prefs; default->value(m);
    });
  }
  static MontoyaApi api(Persistence persistence){
    UserInterface ui=mock(UserInterface.class,(p,m,a)->{
      if(m.getName().equals("createHttpRequestEditor")){
        created++; JPanel component=new JPanel();
        return mock(HttpRequestEditor.class,(q,x,b)->switch(x.getName()){
          case "uiComponent"->component;
          case "setRequest"->{requests++;yield null;}
          default->value(x);
        });
      }
      if(m.getName().equals("createHttpResponseEditor")){
        created++;JPanel component=new JPanel();
        return mock(HttpResponseEditor.class,(q,x,b)->switch(x.getName()){
          case "uiComponent"->component;
          case "setResponse"->{responses++;yield null;}
          default->value(x);
        });
      }
      return value(m);
    });
    return mock(MontoyaApi.class,(p,m,a)->switch(m.getName()){
      case "userInterface"->ui;case "persistence"->persistence;default->value(m);
    });
  }
  static FlowDefinition flow(String id){
    var template=new StoredRequestTemplate("GET","https","example.test",443,"/"+id,List.of(),"");
    var step=new FlowStep("step-"+id,"Request",true,"",template,List.of(),List.of(),List.of(),FlowStep.StepErrorStrategy.FAIL_FLOW);
    return new FlowDefinition(id,id,"",true,List.of(step),FlowRuleSet.empty(),AggregationPlan.disabled(),FlowTriggerConfig.none());
  }
  static int count(Object target,String field)throws Exception{
    var f=target.getClass().getDeclaredField(field);f.setAccessible(true);
    return ((Collection<?>)f.get(target)).size();
  }
  @Test void switchingAndDeletingFlowsKeepsEditorsAndListenersBounded() throws Exception {
    SwingUtilities.invokeAndWait(()->{
      try {
        var persistence=persistence();
        var controller=new FlowWorkspaceController(new FlowRepository(persistence,new FlowSerializer(),new PersistenceMigrationService()));
        var log=new FlowExecutionLog();
        var a=flow("A");var b=flow("B");controller.addFlow(a);controller.addFlow(b);
        var tabs=new FlowTabsPanel(controller,api(persistence),null,log);
        var firstHeader = tabs.getTabComponentAt(0);
        int listeners=count(controller,"changeListeners");
        assertEquals(4, created);
        requests=responses=writes=0;
        for(int i=0;i<20;i++)controller.setActiveFlow(i%2==0?b:a);
        assertEquals(20, requests);
        assertEquals(0, responses);
        assertEquals(0, writes);
        assertSame(firstHeader, tabs.getTabComponentAt(0));
        for(int i=0;i<20;i++){var f=flow("temporary-"+i);controller.addFlow(f);controller.setActiveFlow(f);controller.removeFlow(f);}
        assertEquals(listeners, count(controller,"changeListeners"));
        assertEquals(1, count(log,"listeners"));
        assertEquals(4, created);
        requests=responses=0;
        controller.setLastExecutionResult(null);
        assertEquals(0, requests);
        assertEquals(0, responses);
        var response=mock(burp.api.montoya.http.message.responses.HttpResponse.class,(p,m,v)->value(m));
        controller.setCapturedResponse(a.steps().getFirst().id(),response);
        controller.removeFlow(a);
        assertNull(controller.getCapturedResponse(a.steps().getFirst().id()));
      }catch(Exception e){throw new RuntimeException(e);}
    });
  }

  @Test void editorsRefreshChangedRequestAndResponseButNotMetadata() throws Exception {
    SwingUtilities.invokeAndWait(()->{
      var persistence=persistence();
      var controller=new FlowWorkspaceController(new FlowRepository(persistence,new FlowSerializer(),new PersistenceMigrationService()));
      var flow=flow("A");controller.addFlow(flow);controller.setActiveFlow(flow);
      var api=api(persistence);
      new RequestResponseController(controller,api.userInterface().createHttpRequestEditor(),
          api.userInterface().createHttpResponseEditor(),api);
      requests=responses=0;
      var step=flow.steps().getFirst().withNotes("Only metadata changed");
      controller.updateFlow(new FlowDefinition(flow.id(),flow.name(),flow.description(),flow.enabled(),List.of(step),
          flow.rules(),flow.aggregation(),flow.triggerConfig()));
      assertEquals(0,requests);
      assertEquals(0,responses);
      String raw=Base64.getEncoder().encodeToString("HTTP/1.1 200 OK\r\n\r\nchanged".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      step=step.withLastResponseRaw(raw);
      controller.updateFlow(new FlowDefinition(flow.id(),flow.name(),flow.description(),flow.enabled(),List.of(step),
          flow.rules(),flow.aggregation(),flow.triggerConfig()));
      assertEquals(0,requests);
      assertEquals(1,responses);
      step=step.withRequestTemplate(new StoredRequestTemplate("POST","https","example.test",443,"/changed",List.of(),"updated"));
      controller.updateFlow(new FlowDefinition(flow.id(),flow.name(),flow.description(),flow.enabled(),List.of(step),
          flow.rules(),flow.aggregation(),flow.triggerConfig()));
      assertEquals(1,requests);
      assertEquals(1,responses);
      var captured=mock(burp.api.montoya.http.message.responses.HttpResponse.class,(p,m,v)->value(m));
      controller.setCapturedResponse(step.id(),captured);
      controller.setSelectedStep(step);
      assertEquals(2,responses);
      controller.setSelectedStep(null);
      assertEquals(2,requests);
      assertEquals(3,responses);
    });
  }

  @Test void stepSelectionRebuildsOnlyAffectedRowsAndKeepsPerFlowViewState() throws Exception {
    SwingUtilities.invokeAndWait(()->{
      try {
        var persistence=persistence();
        var controller=new FlowWorkspaceController(new FlowRepository(persistence,new FlowSerializer(),new PersistenceMigrationService()));
        var a=flow("A");var b=flow("B");
        var third=flow("C").steps().getFirst();
        a=new FlowDefinition(a.id(),a.name(),a.description(),a.enabled(),List.of(a.steps().getFirst(),b.steps().getFirst(),third),
            a.rules(),a.aggregation(),a.triggerConfig());
        controller.addFlow(a);controller.addFlow(b);controller.setActiveFlow(a);
        var rows=new StepListPanel(controller);
        var field=StepListPanel.class.getDeclaredField("listContainer");field.setAccessible(true);
        var container=(JPanel)field.get(rows);
        var untouched=container.getComponent(2);
        controller.setSelectedStep(a.steps().get(1));
        assertSame(untouched, container.getComponent(2));
        var first=container.getComponent(0);
        controller.setLastExecutionResult(null);
        assertSame(first,container.getComponent(0));
        var tabs=new FlowTabsPanel(controller,api(persistence),null,new FlowExecutionLog());
        var workspaceField=FlowTabsPanel.class.getDeclaredField("workspace");workspaceField.setAccessible(true);
        var workspace=(FlowWorkspacePanel)workspaceField.get(tabs);
        workspace.restoreViewState(new FlowWorkspacePanel.ViewState(2,350,210));
        controller.setActiveFlow(b);
        assertEquals(0,workspace.viewState().detailTab());
        controller.setActiveFlow(a);
        assertEquals(new FlowWorkspacePanel.ViewState(2,350,210),workspace.viewState());
      }catch(Exception e){throw new RuntimeException(e);}
    });
  }

  @Test void clickingRequestsUpdatesSelectionHighlightTogetherWithNativeEditor() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      var persistence = persistence();
      var controller = new FlowWorkspaceController(new FlowRepository(persistence, new FlowSerializer(), new PersistenceMigrationService()));
      var first = flow("A");
      var second = flow("B").steps().getFirst();
      var third = flow("C").steps().getFirst();
      var login = new FlowDefinition(first.id(), first.name(), first.description(), first.enabled(),
          List.of(first.steps().getFirst(), second, third), first.rules(), first.aggregation(), first.triggerConfig());
      var other = flow("Other");
      controller.addFlow(login);
      controller.addFlow(other);
      var tabs = new FlowTabsPanel(controller, api(persistence), null, new FlowExecutionLog());
      JPanel host = (JPanel) tabs.getComponentAt(0);
      var workspace = (FlowWorkspacePanel) host.getComponent(0);
      var split = (JSplitPane) workspace.getComponent(0);
      var stepList = (StepListPanel) split.getLeftComponent();
      var scroll = (JScrollPane) stepList.getComponent(0);
      var rows = (JPanel) scroll.getViewport().getView();
      java.awt.Color selectedBackground = rows.getComponent(0).getBackground();
      java.awt.Color normalBackground = rows.getComponent(1).getBackground();
      assertNotEquals(selectedBackground, normalBackground);
      requests = responses = 0;
      JPanel unaffected = (JPanel) rows.getComponent(2);
      clickRequest(rows.getComponent(1));
      assertEquals(second.id(), controller.selectedStep().id());
      assertEquals(1, requests);
      assertEquals(normalBackground, rows.getComponent(0).getBackground());
      assertEquals(selectedBackground, rows.getComponent(1).getBackground());
      assertSame(unaffected, rows.getComponent(2));
      clickRequest(rows.getComponent(2));
      assertEquals(third.id(), controller.selectedStep().id());
      assertEquals(2, requests);
      assertEquals(normalBackground, rows.getComponent(1).getBackground());
      assertEquals(selectedBackground, rows.getComponent(2).getBackground());
      controller.setActiveFlow(other);
      controller.setActiveFlow(login);
      assertEquals(first.steps().getFirst().id(), controller.selectedStep().id());
      assertEquals(selectedBackground, rows.getComponent(0).getBackground());
      assertEquals(normalBackground, rows.getComponent(1).getBackground());
      assertEquals(normalBackground, rows.getComponent(2).getBackground());
      // A click on a nested label must follow the same selection path as a row click.
      var row = (JPanel) rows.getComponent(1);
      var text = (JPanel) ((java.awt.BorderLayout) row.getLayout()).getLayoutComponent(java.awt.BorderLayout.CENTER);
      clickRequest(text.getComponent(0));
      assertEquals(second.id(), controller.selectedStep().id());
      assertEquals(selectedBackground, rows.getComponent(1).getBackground());
      controller.setSelectedStep(null);
      for (int index = 0; index < 3; index++) assertEquals(normalBackground, rows.getComponent(index).getBackground());
    });
  }

  private static void clickRequest(java.awt.Component component) {
    long now = System.currentTimeMillis();
    component.dispatchEvent(new java.awt.event.MouseEvent(component, java.awt.event.MouseEvent.MOUSE_PRESSED,
        now, java.awt.event.InputEvent.BUTTON1_DOWN_MASK, 8, 8, 1, false, java.awt.event.MouseEvent.BUTTON1));
    component.dispatchEvent(new java.awt.event.MouseEvent(component, java.awt.event.MouseEvent.MOUSE_RELEASED,
        now + 1, 0, 8, 8, 1, false, java.awt.event.MouseEvent.BUTTON1));
  }

  @Test void projectMeasuresStorageOffEdtAndDoesNotRereadItOnSelection() throws Exception {
    var persistence=persistence();
    var serializer=new FlowSerializer();
    var controller=new FlowWorkspaceController(new FlowRepository(persistence,serializer,new PersistenceMigrationService()));
    var flow=flow("A");
    final dev.nquangit.corestra.ui.project.ProjectPanel[] panel = new dev.nquangit.corestra.ui.project.ProjectPanel[1];
    final javax.swing.SwingWorker<?,?>[] worker = new javax.swing.SwingWorker<?,?>[1];
    SwingUtilities.invokeAndWait(()->{
      controller.addFlow(flow);controller.setActiveFlow(flow);
      panel[0]=new dev.nquangit.corestra.ui.project.ProjectPanel(controller,
          new ProjectMetadataRepository(persistence),new ProjectSerializer(serializer),
          new ProjectSettingsRepository(),new dev.nquangit.corestra.access.repository.InMemoryAccessRepository(),
          new dev.nquangit.corestra.session.SessionProfileRepository(),()->{},new ProjectDataMaintenanceService(persistence));
      try {
        var field=panel[0].getClass().getDeclaredField("usageWorker");field.setAccessible(true);
        worker[0]=(javax.swing.SwingWorker<?,?>)field.get(panel[0]);
      }catch(Exception e){throw new RuntimeException(e);}
    });
    worker[0].get(10,java.util.concurrent.TimeUnit.SECONDS);
    int before=reads;
    SwingUtilities.invokeAndWait(()->{
      for(int i=0;i<20;i++)controller.setSelectedStep(flow.steps().getFirst());
      assertEquals(before, reads);
      panel[0].close();
    });
  }
}
