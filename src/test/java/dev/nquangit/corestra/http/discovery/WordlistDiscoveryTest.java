package dev.nquangit.corestra.http.discovery;

import burp.api.montoya.core.*;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.*;
import burp.api.montoya.http.message.params.*;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.*;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import dev.nquangit.corestra.access.security.RedactionService;
import dev.nquangit.corestra.integration.ProxyPipelineHttpClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.nquangit.corestra.http.discovery.WordlistDictionary.Category.*;
import static dev.nquangit.corestra.integration.ManualTestMessages.*;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("MontoyaObjectFactory")
class WordlistDiscoveryTest {
    MontoyaObjectFactory old;
    ParameterDictionary inventory;
    ParameterDiscoveryHandler collector;
    @BeforeEach void setup(){old=ObjectFactoryLocator.FACTORY;ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->{
        if(m.getName().equals("requestResult"))return proxy(RequestToBeSentAction.class,(q,n,b)->n.getName().equals("request")?a[0]:null);
        if(m.getName().equals("responseResult"))return proxy(ResponseReceivedAction.class,(q,n,b)->n.getName().equals("response")?a[0]:null);
        throw new UnsupportedOperationException(m.getName());});inventory=new ParameterDictionary();collector=new ParameterDiscoveryHandler(inventory,new RedactionService());}
    @AfterEach void cleanup(){collector.close();ObjectFactoryLocator.FACTORY=old;}
    static ParsedHttpParameter param(String name,String value,HttpParameterType type){return proxy(ParsedHttpParameter.class,(p,m,a)->switch(m.getName()){case "name"->name;case "value"->value;case "type"->type;default->null;});}
    static HttpRequest request(String path,boolean scope,List<HttpHeader> headers,List<ParsedHttpParameter> params,String body){return proxy(HttpRequest.class,(p,m,a)->switch(m.getName()){
        case "httpService"->service("api.test",443,true);case "path"->path;case "pathWithoutQuery"->path.split("\\?",2)[0];case "isInScope"->scope;case "method"->"GET";
        case "headers"->headers;case "parameters"->params;case "body"->bytes(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));case "bodyToString"->body;
        case "headerValue"->headers.stream().filter(h->h.name().equalsIgnoreCase((String)a[0])).map(HttpHeader::value).findFirst().orElse(null);
        case "hasHeader"->headers.stream().anyMatch(h->h.name().equalsIgnoreCase((String)a[0]));default->throw new UnsupportedOperationException(m.getName());});}
    static HttpRequest request(String path,ParsedHttpParameter... params){return request(path,true,List.of(header("X-User","alice")),List.of(params),"");}
    static HttpResponse response(String raw){var base=dev.nquangit.corestra.integration.ManualTestMessages.response(raw);return proxy(HttpResponse.class,(p,m,a)->
            m.getName().equals("headerValue")?base.headers().stream().filter(h->h.name().equalsIgnoreCase((String)a[0])).map(HttpHeader::value).findFirst().orElse(null):m.invoke(base,a));}
    private Map<WordlistDictionary.Category,List<String>> groups(){return WordlistDictionary.grouped(collector.wordlists().snapshot(),null,"","");}
    private void live(HttpRequest request,ToolType source){var wrapped=proxy(HttpRequestToBeSent.class,(p,m,a)->switch(m.getName()){
        case "toolSource"->proxy(ToolSource.class,(q,n,b)->source);case "messageId"->7;default->HttpRequest.class.getMethod(m.getName(),m.getParameterTypes()).invoke(request,a);});assertSame(wrapped,collector.handleHttpRequestToBeSent(wrapped).request());}

    @Test void partitionsNamesPathsAndOnlyCategoricalValuesWithoutSavingSecrets(){
        var request=request("/api/orders/123456/view.php?action=export",param("action","export",HttpParameterType.URL),param("UserId","12345",HttpParameterType.URL),
                param("userId","12345",HttpParameterType.URL),param("password","SECRET",HttpParameterType.BODY),param("token","secret-token",HttpParameterType.COOKIE),param("role","admin",HttpParameterType.JSON));
        var response=response("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nX-Result: ok\r\nSet-Cookie: session=SECRET; HttpOnly\r\n\r\n{\"data\":[{\"status\":\"active\",\"refreshToken\":\"SECRET\"}]}");
        assertTrue(collector.scanStored(request,response));var groups=groups();
        assertEquals(List.of("UserId","action","userId"),groups.get(QUERY_PARAMETERS));assertEquals(List.of("role"),groups.get(JSON_KEYS));
        assertEquals(List.of("active","admin","export"),groups.get(VALUE_WORDS));assertEquals(List.of("/api/orders/{id}/view.php"),groups.get(URL_PATHS));
        assertEquals(List.of("view.php"),groups.get(FILE_NAMES));assertEquals(List.of("php"),groups.get(FILE_EXTENSIONS));
        assertTrue(groups.get(COOKIE_NAMES).containsAll(List.of("token","session")));assertFalse(groups.get(COOKIE_NAMES).contains("HttpOnly"));
        assertTrue(groups.get(RESPONSE_JSON_KEYS).containsAll(List.of("data","status","refreshToken")));
        assertTrue(groups.values().stream().flatMap(List::stream).noneMatch(v->v.contains("SECRET")||v.equals("123456")||v.equals("12345")));
        var before=collector.wordlists().snapshot();collector.scanStored(request,response);assertEquals(before,collector.wordlists().snapshot());
        collector.setCollectValues(false);assertNull(groups().get(VALUE_WORDS));
    }
    @Test void liveSourceAllowlistScopeAndPipelineMarkersApplyBeforeAnalysis() throws Exception {
        var request=request("/api/users",param("id","1",HttpParameterType.URL));
        for(var tool:List.of(ToolType.SCANNER,ToolType.INTRUDER,ToolType.EXTENSIONS,ToolType.TARGET,ToolType.REPEATER))live(request,tool);
        collector.awaitIdle();assertTrue(collector.wordlists().snapshot().isEmpty());
        live(request,ToolType.PROXY);collector.awaitIdle();assertEquals(List.of("id"),groups().get(QUERY_PARAMETERS));
        collector.clear();collector.setIncludeRepeater(true);live(request,ToolType.REPEATER);collector.awaitIdle();assertFalse(collector.wordlists().snapshot().isEmpty());
        collector.clear();live(request("/out",false,List.of(),List.of(),""),ToolType.PROXY);
        live(request("/internal",true,List.of(header(ProxyPipelineHttpClient.MARKER_HEADER,"one")),List.of(),""),ToolType.PROXY);collector.awaitIdle();assertTrue(collector.wordlists().snapshot().isEmpty());
    }
    @Test void encodedPayloadsRejectTheWholeExchangeAndMalformedNamesNeverBecomeWords(){
        for(String path:List.of("/api?q=%253Cscript%253E", "/api?q=%27%20OR%201=1", "/api/../../etc/passwd"))
            assertFalse(collector.scanStored(request(path,param("innocent","value",HttpParameterType.URL)),response("{\"polluted\":1}")));
        assertTrue(collector.wordlists().snapshot().isEmpty());
        collector.scanStored(request("/api",param("x\r\nInjected","x",HttpParameterType.URL),param("good","x",HttpParameterType.URL)),null);
        assertEquals(List.of("good"),groups().get(QUERY_PARAMETERS));
    }
    @Test void rescanSkipsEditedAndOriginalOrFinalMarkersAndDoesNotSendRequests(){
        var clean=request("/api",param("name","a",HttpParameterType.URL));var marker=request("/api",true,List.of(header(ProxyPipelineHttpClient.MARKER_HEADER,"x")),List.of(),"");
        var response=response("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<input name=\"csrf\"><select name='mode'>");
        var service=new ProxyHistoryWordlistRescan(()->List.of(record(clean,clean,response,false),record(clean,clean,response,true),record(marker,clean,response,false),record(clean,marker,response,false)),collector);
        var result=service.scan(()->false,n->{});assertEquals(4,result.examined());assertEquals(1,result.collected());assertEquals(3,result.skipped());
        assertEquals(List.of("csrf","mode"),groups().get(HTML_FORM_NAMES));
        assertEquals(0,service.scan(()->true,n->{}).examined());
    }
    @Test void clearingInvalidatesQueuedWorkAndCloseStopsCollection() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        var base=request("/api",param("beforeClear","x",HttpParameterType.URL));
        var waiting=proxy(HttpRequest.class,(p,m,a)->{if(m.getName().equals("headerValue")){entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));}return m.invoke(base,a);});
        live(waiting,ToolType.PROXY);assertTrue(entered.await(5,TimeUnit.SECONDS));collector.clear();release.countDown();collector.awaitIdle();
        assertTrue(collector.wordlists().snapshot().isEmpty());assertTrue(inventory.snapshot().isEmpty());collector.close();assertFalse(collector.scanStored(base,null));
    }
    @Test void responseIsExcludedEvenAfterPipelineMarkerWasRemoved() throws Exception {
        var marked=request("/flow",true,List.of(header(ProxyPipelineHttpClient.MARKER_HEADER,"internal")),List.of(),"");
        live(marked,ToolType.PROXY);
        var clean=request("/flow");var base=response("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"mustNotCollect\":true}");
        var received=proxy(HttpResponseReceived.class,(p,m,a)->switch(m.getName()){
            case "toolSource"->proxy(ToolSource.class,(q,n,b)->ToolType.PROXY);case "messageId"->7;case "initiatingRequest"->clean;
            default->HttpResponse.class.getMethod(m.getName(),m.getParameterTypes()).invoke(base,a);
        });
        collector.handleHttpResponseReceived(received);collector.awaitIdle();assertTrue(collector.wordlists().snapshot().isEmpty());
    }
    @Test void hashedAssetNamesDoNotBecomeVocabularyButTheirExtensionsRemain() {
        collector.scanStored(request("/assets/app.abcdef0123456789.js"),null);
        assertEquals(List.of("/assets/{id}.js"),groups().get(URL_PATHS));assertEquals(List.of("js"),groups().get(FILE_EXTENSIONS));
        assertNull(groups().get(FILE_NAMES));assertEquals(List.of("assets"),groups().get(PATH_SEGMENTS));
    }
    static ProxyHttpRequestResponse record(HttpRequest original,HttpRequest sent,HttpResponse response,boolean edited){return proxy(ProxyHttpRequestResponse.class,(p,m,a)->switch(m.getName()){
        case "request"->original;case "finalRequest"->sent;case "response"->response;case "hasResponse"->response!=null;case "edited"->edited;default->throw new UnsupportedOperationException(m.getName());});}
}
