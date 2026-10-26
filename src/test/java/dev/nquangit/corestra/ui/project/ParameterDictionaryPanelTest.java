package dev.nquangit.corestra.ui.project;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.proxy.Proxy;
import dev.nquangit.corestra.access.security.RedactionService;
import dev.nquangit.corestra.http.discovery.*;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static dev.nquangit.corestra.integration.ManualTestMessages.proxy;
import static dev.nquangit.corestra.http.discovery.WordlistDictionary.Category.*;
import static org.junit.jupiter.api.Assertions.*;

class ParameterDictionaryPanelTest {
    @Test void wordTableIsUniqueAcrossHostsAndRemovalRespectsHostAndSortedRows() throws Exception {
        var inventory=new ParameterDictionary();var collector=new ParameterDiscoveryHandler(inventory,new RedactionService());
        ParameterDictionaryPanel[] panel=new ParameterDictionaryPanel[1];
        try {
            collector.wordlists().observe(QUERY_PARAMETERS,"id","a.test");collector.wordlists().observe(QUERY_PARAMETERS,"id","b.test");
            collector.wordlists().observe(QUERY_PARAMETERS,"name","a.test");collector.wordlists().observe(REQUEST_HEADERS,"X-User","a.test");
            SwingUtilities.invokeAndWait(()->{
                panel[0]=new ParameterDictionaryPanel(inventory,collector);
                JTable table=field(panel[0],"wordTable",JTable.class);assertEquals(3,table.getRowCount());
                field(panel[0],"category",JComboBox.class).setSelectedItem(QUERY_PARAMETERS);assertEquals(2,table.getRowCount());
                field(panel[0],"host",JComboBox.class).setSelectedItem("a.test");
                table.getRowSorter().toggleSortOrder(0);table.getRowSorter().toggleSortOrder(0);assertEquals("name",table.getValueAt(0,0));
                table.setRowSelectionInterval(0,0);button(panel[0],"Remove selected").doClick();
                assertTrue(collector.wordlists().snapshot().stream().noneMatch(e->e.word().equals("name")));
                assertEquals(2,collector.wordlists().snapshot().stream().filter(e->e.word().equals("id")).count());
                assertFalse(button(panel[0],"Rescan Proxy History").isEnabled());
            });
        } finally {SwingUtilities.invokeAndWait(()->{if(panel[0]!=null)panel[0].close();});collector.close();}
    }
    @Test void rescanReadsHistoryOffEdtAndCancelDoesNotSendOrClearData() throws Exception {
        var inventory=new ParameterDictionary();var collector=new ParameterDiscoveryHandler(inventory,new RedactionService());
        collector.wordlists().observe(QUERY_PARAMETERS,"retained","a.test");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ParameterDictionaryPanel[] panel=new ParameterDictionaryPanel[1];
        var history=proxy(Proxy.class,(p,m,a)->{assertEquals("history",m.getName());assertFalse(SwingUtilities.isEventDispatchThread());entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return List.of();});
        MontoyaApi api=proxy(MontoyaApi.class,(p,m,a)->{assertEquals("proxy",m.getName());return history;});
        try {
            SwingUtilities.invokeAndWait(()->{panel[0]=new ParameterDictionaryPanel(inventory,collector,api);button(panel[0],"Rescan Proxy History").doClick();});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(()->{assertFalse(button(panel[0],"Clear runtime data").isEnabled());button(panel[0],"Cancel job").doClick();});
            release.countDown();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<end){AtomicReference<Object> job=new AtomicReference<>();SwingUtilities.invokeAndWait(()->job.set(field(panel[0],"job",SwingWorker.class)));if(job.get()==null)break;Thread.sleep(10);}
            SwingUtilities.invokeAndWait(()->{assertNull(field(panel[0],"job",SwingWorker.class));assertTrue(field(panel[0],"status",JTextField.class).getText().startsWith("Cancelled"));});
            assertEquals(1,collector.wordlists().snapshot().size());
        } finally {release.countDown();SwingUtilities.invokeAndWait(()->{if(panel[0]!=null)panel[0].close();});collector.close();}
    }
    private static <T>T field(Object target,String name,Class<T> type){try{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(target));}catch(Exception e){throw new AssertionError(e);}}
    private static JButton button(Component root,String text){if(root instanceof JButton b&&b.getText().equals(text))return b;if(root instanceof Container c)for(var child:c.getComponents()){var b=button(child,text);if(b!=null)return b;}return null;}
}
