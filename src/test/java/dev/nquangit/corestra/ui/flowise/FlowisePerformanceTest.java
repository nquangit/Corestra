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
        workspace.restoreViewState(new FlowWorkspacePanel.ViewState(2,350,false));
        controller.setActiveFlow(b);
        assertEquals(0,workspace.viewState().detailTab());
        controller.setActiveFlow(a);
        assertEquals(new FlowWorkspacePanel.ViewState(2,350,false),workspace.viewState());
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

  @Test void manualCopyMenuUsesTheRightClickedFlowAndItsLatestContentsWithoutExtraEditors() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      try {
        var persistence = persistence();
        var controller = new FlowWorkspaceController(new FlowRepository(persistence, new FlowSerializer(), new PersistenceMigrationService()));
        var a = flow("A"); var b = flow("B"); controller.addFlow(a); controller.addFlow(b); controller.setActiveFlow(a);
        var panel = new FlowisePanel(controller, api(persistence));
        var selected = new java.util.concurrent.atomic.AtomicReference<FlowDefinition>();
        panel.setManualTestingAction(selected::set);
        assertNull(((java.awt.BorderLayout) panel.getLayout()).getLayoutComponent(java.awt.BorderLayout.NORTH));
        var tabsField = FlowisePanel.class.getDeclaredField("flowTabsPanel"); tabsField.setAccessible(true);
        var tabs = (FlowTabsPanel) tabsField.get(panel);
        var method = FlowTabsPanel.class.getDeclaredMethod("buildTabContextMenu", FlowDefinition.class); method.setAccessible(true);
        var menu = (JPopupMenu) method.invoke(tabs, b);
        var changed = new FlowDefinition(b.id(), "B updated", b.description(), b.enabled(), b.steps(), b.rules(), b.aggregation(), b.triggerConfig());
        controller.updateFlow(changed);
        for (java.awt.Component item : menu.getComponents()) if (item instanceof JMenuItem action && action.getText().equals("Copy to Manual Testing")) action.doClick();
        assertEquals(changed, selected.get()); assertEquals(a.id(), controller.activeFlow().id());
        assertEquals(4, created);
      } catch (Exception e) { throw new RuntimeException(e); }
    });
  }

  @Test void compactRowsKeepTheirSizeAcrossNamesEndpointsAndProgressAndRetainFullUrlInTooltip() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      try {
        var persistence = persistence();
        var controller = new FlowWorkspaceController(new FlowRepository(persistence, new FlowSerializer(), new PersistenceMigrationService()));
        var f = flow("A"); controller.addFlow(f); controller.setActiveFlow(f);
        var panel = new StepListPanel(controller);
        var field = StepListPanel.class.getDeclaredField("listContainer"); field.setAccessible(true);
        var rows = (JPanel) field.get(panel); var before = rows.getComponent(0).getPreferredSize();
        String path = "/endpoint-is-only-in-the-tooltip/" + "segment/".repeat(80);
        var step = f.steps().getFirst().withName("Long request name ".repeat(30)).withRequestTemplate(
            new StoredRequestTemplate("GET", "https", "api.example.test", 443, path, List.of(), ""));
        controller.updateFlow(new FlowDefinition(f.id(), f.name(), f.description(), true, List.of(step), f.rules(), f.aggregation(), f.triggerConfig()));
        assertEquals(before, rows.getComponent(0).getPreferredSize());
        for (var state : dev.nquangit.corestra.manual.ManualRunProgress.State.values()) {
          panel.setExecutionStatuses(f.id(), Map.of(step.id(), new dev.nquangit.corestra.manual.ManualRunProgress.Status(state, 2, 200, "Error details".repeat(100))));
          var row = (JPanel) rows.getComponent(0);
          assertEquals(before, row.getPreferredSize()); assertEquals(before.height, row.getMinimumSize().height);
          assertTrue(row.getToolTipText().contains(path));
          assertFalse(labelTexts(row).stream().anyMatch(text -> text.contains("endpoint-is-only-in-the-tooltip") || text.equals("api.example.test")));
        }
      } catch (Exception e) { throw new RuntimeException(e); }
    });
  }

  @Test void sidebarWidthIsSharedAcrossFlowTabsAndSurvivesEditorLogAndContentChanges() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      try {
        var persistence = persistence();
        var controller = new FlowWorkspaceController(new FlowRepository(persistence, new FlowSerializer(), new PersistenceMigrationService()));
        var a = flow("A"); var b = flow("B"); controller.addFlow(a); controller.addFlow(b);
        var tabs = new FlowTabsPanel(controller, api(persistence), null, new FlowExecutionLog());
        var field = FlowTabsPanel.class.getDeclaredField("workspace"); field.setAccessible(true);
        var workspace = (FlowWorkspacePanel) field.get(tabs); var split = (JSplitPane) workspace.getComponent(0);
        split.setSize(1000, 600); split.doLayout(); split.setDividerLocation(310); split.doLayout();
        controller.setActiveFlow(b); split.setSize(1200, 600); split.doLayout(); assertEquals(310, split.getLeftComponent().getWidth());
        split.setDividerLocation(350); split.doLayout(); controller.setActiveFlow(a); split.doLayout(); assertEquals(350, split.getLeftComponent().getWidth());
        ((FlowEditorLogPanel) split.getRightComponent()).showLog(true); split.doLayout(); assertEquals(350, split.getLeftComponent().getWidth());
        ((FlowEditorLogPanel) split.getRightComponent()).showLog(false); split.doLayout(); assertEquals(350, split.getLeftComponent().getWidth());
        assertEquals(4, created);
      } catch (Exception e) { throw new RuntimeException(e); }
    });
  }

  private static List<String> labelTexts(java.awt.Component root) {
    var labels = new ArrayList<String>();
    if (root instanceof JLabel label && label.getText() != null) labels.add(label.getText());
    if (root instanceof java.awt.Container c) for (var child : c.getComponents()) labels.addAll(labelTexts(child));
    return labels;
  }

  @Test void selectionRemainsDistinctAcrossBothLayoutsThemesAndEveryExecutionState() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      Object panelBackground = UIManager.get("Panel.background"), listBackground = UIManager.get("List.background");
      try {
        for (boolean dark : List.of(false, true)) {
          UIManager.put("Panel.background", dark ? new java.awt.Color(0x20242B) : java.awt.Color.WHITE);
          UIManager.put("List.background", dark ? new java.awt.Color(0x20242B) : java.awt.Color.WHITE);
          for (var presentation : StepListPanel.Presentation.values()) {
            var controller = new FlowWorkspaceController(new FlowRepository(persistence(), new FlowSerializer(), new PersistenceMigrationService()));
            var a = flow("A"); var first = a.steps().getFirst(); var second = flow("B").steps().getFirst();
            var current = new FlowDefinition(a.id(), a.name(), "", true, List.of(first, second), a.rules(), a.aggregation(), a.triggerConfig());
            controller.addFlow(current); controller.setActiveFlow(current);
            var panel = new StepListPanel(controller, null, presentation);
            var field = StepListPanel.class.getDeclaredField("listContainer"); field.setAccessible(true);
            var rows = (JPanel) field.get(panel);
            java.awt.Color selectedBackground = null;
            for (var state : dev.nquangit.corestra.manual.ManualRunProgress.State.values()) {
              var progress = new dev.nquangit.corestra.manual.ManualRunProgress.Status(state, 1,
                  state == dev.nquangit.corestra.manual.ManualRunProgress.State.COMPLETED ? 302 : 0, "");
              panel.setExecutionStatuses(current.id(), Map.of(first.id(), progress, second.id(), progress));
              controller.setSelectedStep(first);
              var selected = (JPanel) rows.getComponent(0); var other = (JPanel) rows.getComponent(1);
              assertNotEquals(other.getBackground(), selected.getBackground(), presentation + " / " + state);
              assertTrue(colorDistance(other.getBackground(), selected.getBackground()) > 30);
              if (selectedBackground == null) selectedBackground = selected.getBackground();
              assertEquals(selectedBackground, selected.getBackground(), "Progress must not overwrite selection");
              assertEquals(state, selected.getClientProperty("executionState"));
              assertTrue(labelTexts(selected).contains(state.label()));
              assertTrue(contrast(findLabel(selected, state.label()).getForeground(), selected.getBackground()) >= 4.5,
                  "Execution state must stay readable on the selection background");
              var name = findLabel(selected, first.name());
              assertTrue(contrast(name.getForeground(), selected.getBackground()) >= 4.5, "Selected name must be readable");
              controller.setSelectedStep(second);
              assertEquals(other.getBackground(), rows.getComponent(0).getBackground());
              assertEquals(selectedBackground, rows.getComponent(1).getBackground());
            }
          }
        }
      } catch (Exception e) { throw new RuntimeException(e); }
      finally { UIManager.put("Panel.background", panelBackground); UIManager.put("List.background", listBackground); }
    });
  }

  @Test void manualStatusCodesAreVisibleAndResetWithoutChangingRowDimensions() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      try {
        var controller = new FlowWorkspaceController(new FlowRepository(persistence(), new FlowSerializer(), new PersistenceMigrationService()));
        var f = flow("A"); var step = f.steps().getFirst(); controller.addFlow(f); controller.setActiveFlow(f); controller.setSelectedStep(step);
        var panel = new StepListPanel(controller);
        panel.setExecutionStatuses(f.id(), Map.of());
        var field = StepListPanel.class.getDeclaredField("listContainer"); field.setAccessible(true); var rows = (JPanel) field.get(panel);
        var before = rows.getComponent(0).getPreferredSize();
        var badgeSize = named(panel, "requestHttpStatus").getPreferredSize();
        assertEquals("", ((JLabel) named(panel, "requestHttpStatus")).getText());
        for (int code : List.of(200, 302, 403, 500)) {
          panel.setExecutionStatuses(f.id(), Map.of(step.id(), new dev.nquangit.corestra.manual.ManualRunProgress.Status(
              dev.nquangit.corestra.manual.ManualRunProgress.State.COMPLETED, 1, code, "")));
          var badge = (JLabel) named(panel, "requestHttpStatus");
          assertEquals(Integer.toString(code), badge.getText()); assertTrue(badge.getToolTipText().contains("HTTP " + code));
          assertEquals(badgeSize, badge.getPreferredSize()); assertEquals(before, rows.getComponent(0).getPreferredSize());
        }
        panel.setExecutionStatuses(f.id(), Map.of(step.id(), new dev.nquangit.corestra.manual.ManualRunProgress.Status(
            dev.nquangit.corestra.manual.ManualRunProgress.State.FAILED, 1, 0, "Network failed")));
        assertEquals("—", ((JLabel) named(panel, "requestHttpStatus")).getText());
        assertTrue(((JLabel) named(panel, "requestHttpStatus")).getToolTipText().contains("No HTTP response"));
        panel.setExecutionStatuses(f.id(), Map.of());
        assertEquals("", ((JLabel) named(panel, "requestHttpStatus")).getText());
        assertEquals(before, rows.getComponent(0).getPreferredSize());
      } catch (Exception e) { throw new RuntimeException(e); }
    });
  }

  private static JLabel findLabel(java.awt.Component root, String text) {
    if (root instanceof JLabel label && text.equals(label.getText())) return label;
    if (root instanceof java.awt.Container c) for (var child : c.getComponents()) { var found = findLabel(child, text); if (found != null) return found; }
    return null;
  }
  private static double colorDistance(java.awt.Color a, java.awt.Color b) {
    return Math.sqrt(Math.pow(a.getRed() - b.getRed(), 2) + Math.pow(a.getGreen() - b.getGreen(), 2) + Math.pow(a.getBlue() - b.getBlue(), 2));
  }
  private static double contrast(java.awt.Color a, java.awt.Color b) {
    double x = luminance(a), y = luminance(b); return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
  }
  private static double luminance(java.awt.Color c) {
    return .2126 * linear(c.getRed()) + .7152 * linear(c.getGreen()) + .0722 * linear(c.getBlue());
  }
  private static double linear(int channel) { double value = channel / 255.; return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4); }

  @Test void flowiseUsesRoomierRowsWithSmallRuleBadgesWhileCompactLayoutStaysUnchanged() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      try {
        var persistence = persistence();
        var controller = new FlowWorkspaceController(new FlowRepository(persistence, new FlowSerializer(), new PersistenceMigrationService()));
        var f = flow("A");
        var extract = new dev.nquangit.corestra.flow.rule.ExtractRule("e", "Extract", true,
            dev.nquangit.corestra.flow.rule.ExtractRule.SourceSide.RESPONSE, dev.nquangit.corestra.flow.rule.ExtractRule.ExtractorType.BODY_TEXT, "", "out", List.of());
        var inject = new dev.nquangit.corestra.flow.rule.InjectRule("i", "Inject", true,
            dev.nquangit.corestra.flow.rule.InjectRule.Target.REQUEST_HEADER, "X-Test", dev.nquangit.corestra.flow.rule.InjectRule.ValueSourceType.STATIC,
            "", "value", dev.nquangit.corestra.flow.rule.InjectRule.WriteStrategy.OVERWRITE);
        var action = new dev.nquangit.corestra.flow.control.StepAction("a", "Stop", true, dev.nquangit.corestra.flow.control.StepActionType.BREAK_FLOW, Map.of());
        var conditions = new dev.nquangit.corestra.flow.control.StepConditionActionRule("c", "Rules", true,
            dev.nquangit.corestra.flow.control.ConditionMatchPolicy.ALL, List.of(), List.of(action));
        var step = f.steps().getFirst().withExtractRules(List.of(extract)).withInjectRules(List.of(inject)).withConditionActionRules(List.of(conditions));
        controller.addFlow(new FlowDefinition(f.id(), f.name(), f.description(), true, List.of(step), f.rules(), f.aggregation(), f.triggerConfig()));
        controller.setActiveFlow(controller.flows().getFirst());
        var tabs = new FlowTabsPanel(controller, api(persistence), null, new FlowExecutionLog());
        var field = FlowTabsPanel.class.getDeclaredField("workspace"); field.setAccessible(true);
        var workspace = (FlowWorkspacePanel) field.get(tabs); var split = (JSplitPane) workspace.getComponent(0);
        split.setSize(1100, 650); split.doLayout(); assertEquals(320, split.getLeftComponent().getWidth());
        var flowise = (StepListPanel) split.getLeftComponent(); var compact = new StepListPanel(controller);
        var method = (JLabel) named(flowise, "flowiseMethodBadge"); assertNotNull(method);
        var badges = new ArrayList<JLabel>(); ruleBadges(flowise, badges); assertEquals(3, badges.size());
        assertEquals(List.of("E 1", "I 1", "C 1"), badges.stream().map(JLabel::getText).toList());
        for (var b : badges) { assertTrue(b.getFont().getSize2D() < method.getFont().getSize2D()); assertTrue(b.getPreferredSize().height < method.getPreferredSize().height); }
        assertNull(named(compact, "flowiseRuleBadge")); assertTrue(labelTexts(compact).contains("E1 · I1 · C1"));
        var rowsField = StepListPanel.class.getDeclaredField("listContainer"); rowsField.setAccessible(true);
        assertTrue(((JPanel) rowsField.get(flowise)).getComponent(0).getPreferredSize().height > ((JPanel) rowsField.get(compact)).getComponent(0).getPreferredSize().height);
        layoutTree(flowise);
        var row = (JPanel) ((JPanel) rowsField.get(flowise)).getComponent(0);
        var text = (JPanel) ((java.awt.BorderLayout) row.getLayout()).getLayoutComponent(java.awt.BorderLayout.CENTER);
        var name = text.getComponent(0);
        assertTrue(SwingUtilities.convertPoint(name, 0, name.getHeight(), row).y <= SwingUtilities.convertPoint(method, 0, 0, row).y);
        assertEquals(4, created);
      } catch (Exception e) { throw new RuntimeException(e); }
    });
  }
  private static java.awt.Component named(java.awt.Component root, String name) {
    if (name.equals(root.getName())) return root;
    if (root instanceof java.awt.Container c) for (var child : c.getComponents()) { var found = named(child, name); if (found != null) return found; }
    return null;
  }
  private static void ruleBadges(java.awt.Component root, List<JLabel> found) {
    if (root instanceof JLabel l && "flowiseRuleBadge".equals(l.getName())) found.add(l);
    if (root instanceof java.awt.Container c) for (var child : c.getComponents()) ruleBadges(child, found);
  }

  @Test void draggingTheRequestNameReordersWithoutAHandleAndCheckboxRemainsIndependent() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      try {
        var persistence = persistence();
        var controller = new FlowWorkspaceController(new FlowRepository(persistence, new FlowSerializer(), new PersistenceMigrationService()));
        var a = flow("A"); var b = flow("B").steps().getFirst(); var c = flow("C").steps().getFirst();
        controller.addFlow(new FlowDefinition(a.id(), a.name(), a.description(), true,
            List.of(a.steps().getFirst(), b, c), a.rules(), a.aggregation(), a.triggerConfig()));
        controller.setActiveFlow(controller.flows().getFirst());
        var panel = new StepListPanel(controller); panel.setSize(280, 350); layoutTree(panel);
        var field = StepListPanel.class.getDeclaredField("listContainer"); field.setAccessible(true); var rows = (JPanel) field.get(panel);
        var row = (JPanel) rows.getComponent(0); assertFalse(hasLabelIcon(row));
        var text = (JPanel) ((java.awt.BorderLayout) row.getLayout()).getLayoutComponent(java.awt.BorderLayout.CENTER);
        var name = text.getComponent(0);
        var last = rows.getComponent(2);
        var destination = SwingUtilities.convertPoint(rows, new java.awt.Point(30, last.getY() + last.getHeight() - 1), name);
        long now = System.currentTimeMillis();
        name.dispatchEvent(new java.awt.event.MouseEvent(name, java.awt.event.MouseEvent.MOUSE_PRESSED, now,
            java.awt.event.InputEvent.BUTTON1_DOWN_MASK, 5, 5, 1, false, java.awt.event.MouseEvent.BUTTON1));
        name.dispatchEvent(new java.awt.event.MouseEvent(name, java.awt.event.MouseEvent.MOUSE_DRAGGED, now + 1,
            java.awt.event.InputEvent.BUTTON1_DOWN_MASK, destination.x, destination.y, 0, false, java.awt.event.MouseEvent.NOBUTTON));
        name.dispatchEvent(new java.awt.event.MouseEvent(name, java.awt.event.MouseEvent.MOUSE_RELEASED, now + 2,
            0, destination.x, destination.y, 1, false, java.awt.event.MouseEvent.BUTTON1));
        assertEquals(List.of(b.id(), c.id(), a.steps().getFirst().id()), controller.activeFlow().steps().stream().map(FlowStep::id).toList());
        var left = (JPanel) ((java.awt.BorderLayout) ((JPanel) rows.getComponent(0)).getLayout()).getLayoutComponent(java.awt.BorderLayout.WEST);
        ((JCheckBox) left.getComponent(0)).doClick();
        assertFalse(controller.activeFlow().steps().getFirst().enabled());
        assertEquals(List.of(b.id(), c.id(), a.steps().getFirst().id()), controller.activeFlow().steps().stream().map(FlowStep::id).toList());
      } catch (Exception e) { throw new RuntimeException(e); }
    });
  }
  private static void layoutTree(java.awt.Container root) { root.doLayout(); for (var c : root.getComponents()) if (c instanceof java.awt.Container child) layoutTree(child); }
  private static boolean hasLabelIcon(java.awt.Component root) {
    if (root instanceof JLabel l && l.getIcon() != null) return true;
    if (root instanceof java.awt.Container c) for (var child : c.getComponents()) if (hasLabelIcon(child)) return true;
    return false;
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
