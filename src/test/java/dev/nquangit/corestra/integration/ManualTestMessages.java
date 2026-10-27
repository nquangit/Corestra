package dev.nquangit.corestra.integration;

import burp.api.montoya.core.*;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native-message test seam supporting actual immutable request mutations and exact response bytes. */
public final class ManualTestMessages {
    private ManualTestMessages() { }
    /** Object factory for stored-template execution and native editor tests without Burp. */
    public static MontoyaObjectFactory factory() {
        return proxy(MontoyaObjectFactory.class,(p,m,a)->switch(m.getName()) {
            case "httpHeader" -> header((String)a[0],(String)a[1]);
            case "httpService" -> service((String)a[0],(int)a[1],(boolean)a[2]);
            case "httpRequest" -> parse(a.length == 2 ? (HttpService)a[0] : service("api.test",443,true),a[a.length-1].toString());
            case "httpResponse" -> response(a[0].toString());
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }
    /** Parses a raw request while preserving its explicit service. */
    public static HttpRequest parse(HttpService service,String raw) {
        if(raw.isEmpty()) raw="GET / HTTP/1.1\r\n\r\n";
        String[] parts=raw.split("\r\n\r\n",2),lines=parts[0].split("\r\n"); String[] first=lines[0].split(" ",3);
        List<HttpHeader> headers=Arrays.stream(lines).skip(1).map(line->{int c=line.indexOf(':');return header(line.substring(0,c),line.substring(c+1).stripLeading());}).toList();
        return request(service,first[0],first[1],headers,parts.length==2?parts[1]:"");
    }
    /** Mutable-API-compatible fake request; every mutation returns a fresh snapshot. */
    public static HttpRequest request(HttpService service,String method,String path,List<HttpHeader> headers,String body) {
        return proxy(HttpRequest.class,(p,m,a)->switch(m.getName()) {
            case "httpService" -> service; case "isInScope" -> true; case "method" -> method; case "path" -> path; case "httpVersion" -> "HTTP/1.1";
            case "headers" -> headers; case "bodyToString" -> body; case "body" -> bytes(body.getBytes(StandardCharsets.UTF_8));
            case "headerValue" -> headers.stream().filter(h->h.name().equalsIgnoreCase((String)a[0])).map(HttpHeader::value).findFirst().orElse(null);
            case "hasHeader" -> headers.stream().anyMatch(h->h.name().equalsIgnoreCase((String)a[0]));
            case "withMethod" -> request(service,(String)a[0],path,headers,body);
            case "withPath" -> request(service,method,(String)a[0],headers,body);
            case "withBody" -> {
                String changed=(String)a[0];List<HttpHeader> updated=new ArrayList<>(headers.stream().filter(h->!h.name().equalsIgnoreCase("Content-Length")).toList());
                updated.add(header("Content-Length",Integer.toString(changed.getBytes(StandardCharsets.UTF_8).length)));yield request(service,method,path,updated,changed);
            }
            case "withRemovedHeaders" -> request(service,method,path,List.of(),body);
            case "withAddedHeaders" -> { List<HttpHeader> updated=new ArrayList<>(headers);updated.addAll((List<HttpHeader>)a[0]);yield request(service,method,path,updated,body); }
            case "withAddedHeader" -> { List<HttpHeader> updated=new ArrayList<>(headers);updated.add(a.length == 2 ? header((String)a[0], (String)a[1]) : (HttpHeader)a[0]);yield request(service,method,path,updated,body); }
            case "withRemovedHeader" -> request(service,method,path,headers.stream().filter(h->!h.name().equalsIgnoreCase((String)a[0])).toList(),body);
            case "toString" -> method+" "+path+" HTTP/1.1\r\n"+String.join("\r\n",headers.stream().map(Object::toString).toList())+"\r\n\r\n"+body;
            case "hashCode" -> System.identityHashCode(p);case "equals" -> p==a[0];default -> throw new UnsupportedOperationException(m.getName());
        });
    }
    /** Builds a response preserving exact binary bytes. */
    public static HttpResponse binaryResponse(byte[] content) { return response(200,List.of(),content); }
    /** Parses response text for native editor tests. */
    public static HttpResponse response(String raw) {
        if(!raw.startsWith("HTTP/")) return response(200,List.of(),raw.getBytes(StandardCharsets.UTF_8));
        String[] parts=raw.split("\r\n\r\n",2),lines=parts[0].split("\r\n");
        return response(Integer.parseInt(lines[0].split(" ")[1]),Arrays.stream(lines).skip(1).map(line->{int c=line.indexOf(':');return header(line.substring(0,c),line.substring(c+1).stripLeading());}).toList(),
                (parts.length==2?parts[1]:"").getBytes(StandardCharsets.UTF_8));
    }
    private static HttpResponse response(int status,List<HttpHeader> headers,byte[] body) {
        return proxy(HttpResponse.class,(p,m,a)->switch(m.getName()) {
            case "statusCode" -> (short)status;case "httpVersion" -> "HTTP/1.1";case "reasonPhrase" -> "OK";case "headers" -> headers;
            case "bodyToString" -> new String(body,StandardCharsets.UTF_8);case "body" -> bytes(body);
            case "toString" -> "HTTP/1.1 "+status+" OK\r\n"+String.join("\r\n",headers.stream().map(Object::toString).toList())+"\r\n\r\n"+new String(body,StandardCharsets.UTF_8);
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }
    /** Builds a service stub. */
    public static HttpService service(String host,int port,boolean secure) { return proxy(HttpService.class,(p,m,a)->switch(m.getName()){case "host"->host;case "port"->port;case "secure"->secure;default->null;}); }
    /** Builds a header stub. */
    public static HttpHeader header(String name,String value) { return proxy(HttpHeader.class,(p,m,a)->switch(m.getName()){case "name"->name;case "value"->value;case "toString"->name+": "+value;default->null;}); }
    /** Builds an exact byte-array stub. */
    public static ByteArray bytes(byte[] bytes) { return proxy(ByteArray.class,(p,m,a)->switch(m.getName()){case "getBytes"->bytes.clone();case "length"->bytes.length;case "toString"->new String(bytes,StandardCharsets.UTF_8);default->throw new UnsupportedOperationException(m.getName());}); }
    /** Builds an interface stub. */
    public static <T> T proxy(Class<T> type,InvocationHandler handler) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler)); }
}
