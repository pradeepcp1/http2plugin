package com.blazemeter.jmeter.http2.sampler;

import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
public class HTTP2Connection {

    private static Logger LOG = LoggerFactory.getLogger(HTTP2Connection.class);
    private String connectionId;
    private Session session;
    Map<Integer, Integer> settings=new HashMap<Integer,Integer>();
    {{  settings.put(SettingsFrame.HEADER_TABLE_SIZE, 4096);
    	settings.put(SettingsFrame.ENABLE_PUSH,0);
    	settings.put(SettingsFrame.MAX_CONCURRENT_STREAMS,1000);
    	settings.put(SettingsFrame.MAX_FRAME_SIZE,16384);
    	settings.put(SettingsFrame.MAX_HEADER_LIST_SIZE,65536);
    	settings.put(SettingsFrame.INITIAL_WINDOW_SIZE,65535);
    }}
    SettingsFrame settingsFrame = new SettingsFrame(settings, true);
    //session.setMaxLocalStreams(2000);
    private HTTP2Client client;
    private SslContextFactory sslContextFactory;
    private Queue<HTTP2StreamHandler> streamHandlers = new ConcurrentLinkedQueue<>();
    //public SettingsFrame settingsFrame1=new SettingsFrame(settings1,false);
	public void setSession(Session session) {
        this.session = session;
       // this.session.settings(settingsFrame,Callback.NOOP);
    }

    public HTTP2Connection(String connectionId, boolean isSSL) throws Exception {
        this.session = null;
        //this.session.settings(frame, callback);
    	this.connectionId = connectionId;
        this.client = new HTTP2Client();
        this.sslContextFactory = null;
        if (isSSL) {
            this.sslContextFactory = new SslContextFactory(true);
        }
        this.client.addBean(sslContextFactory);
        this.client.start();
    }

    public String getConnectionId() {
        return connectionId;
    }

    public void connect(String hostname, int port) throws InterruptedException, ExecutionException, TimeoutException {
        FuturePromise<Session> sessionFuture = new FuturePromise<>();
        this.client.connect(this.sslContextFactory, new InetSocketAddress(hostname, port),
                new Session.Listener.Adapter(), sessionFuture);
        setSession(sessionFuture.get(5, TimeUnit.SECONDS));
    }

    public boolean isClosed() {
        return this.session.isClosed();
    }

    private synchronized void sendMutExc(String method, HeadersFrame headersFrame, FuturePromise<Stream> streamPromise,
                                         HTTP2StreamHandler http2StreamHandler, RequestBody requestBody) throws Exception {
        session.newStream(headersFrame, streamPromise, http2StreamHandler);
       // LOG.warn("PCP:sendMutExc stream_id :"+ (streamPromise.get()).getId());
        if ((HTTPConstants.POST.equals(method)) || (HTTPConstants.PUT.equals(method)) ||
        		(HTTPConstants.PATCH.equals(method)) ) {
            Stream actualStream = streamPromise.get();
            int streamID = actualStream.getId();
            
            DataFrame data = new DataFrame(streamID, ByteBuffer.wrap(requestBody.getPayloadBytes()), true);
            //LOG.warn("PCP:sendMutExc stream_id POST|PUT|PATCH:"+ data.getStreamId());
            actualStream.data(data, Callback.NOOP);
        }
    }

    public void send(String method, URL url, HeaderManager headerManager, CookieManager cookieManager,
                     RequestBody requestBody, HTTP2SampleResult sampleResult, int timeout) throws Exception {
        HttpFields headers = buildHeaders(url, headerManager, cookieManager);

        if (requestBody != null) {
            headers.put(HTTPConstants.HEADER_CONTENT_LENGTH, Long.toString(requestBody.getPayloadBytes().length));
            // Check if the header manager had a content type header
            // This allows the user to specify his own content-type for a POST request
            String contentTypeHeader = headers.get(HTTPConstants.HEADER_CONTENT_TYPE);
            if (contentTypeHeader == null || contentTypeHeader.isEmpty()) {
                headers.put(HTTPConstants.HEADER_CONTENT_TYPE, HTTPConstants.APPLICATION_X_WWW_FORM_URLENCODED);
            }
            sampleResult.setQueryString(requestBody.getPayload());
        }

        MetaData.Request metaData = null;
        boolean endOfStream = true;
        switch (method) {
            case "GET":
                metaData = new MetaData.Request("GET", new HttpURI(url.toString()), HttpVersion.HTTP_2,
                        headers);
                break;
            case "POST":
                metaData = new MetaData.Request("POST", new HttpURI(url.toString()), HttpVersion.HTTP_2,
                        headers);
                endOfStream = false;
                break;
            case "PUT":
                metaData = new MetaData.Request("PUT", new HttpURI(url.toString()), HttpVersion.HTTP_2,
                        headers);
                endOfStream = false;
                break;
            case "PATCH":
                metaData = new MetaData.Request("PATCH", new HttpURI(url.toString()), HttpVersion.HTTP_2,
                        headers);
                endOfStream = false;
                break;
            case "DELETE":
                metaData = new MetaData.Request("DELETE", new HttpURI(url.toString()), HttpVersion.HTTP_2,
                        headers);
                break;

            default:
                break;
        }

        HeadersFrame headersFrame = new HeadersFrame(metaData, null, endOfStream);
        // we do this replacement and remove final char to be consistent with jmeter HTTP request sampler
        String headersString = headers.toString().replaceAll("\r\n", "\n");
        sampleResult.setRequestHeaders(headersString.substring(0, headersString.length() - 1));

        HTTP2StreamHandler http2StreamHandler = new HTTP2StreamHandler(this, headerManager, cookieManager,
                sampleResult);
        http2StreamHandler.setTimeout(timeout);
        sampleResult.setCookies(headers.get(HTTPConstants.HEADER_COOKIE));
        
        sampleResult.sampleStart();
        sendMutExc(method, headersFrame, new FuturePromise<>(), http2StreamHandler, requestBody);
        addStreamHandler(http2StreamHandler);
    }

    private HttpFields buildHeaders(URL url, HeaderManager headerManager, CookieManager cookieManager) {
        HttpFields headers = new HttpFields();
        if (headerManager != null) {
            CollectionProperty headersProps = headerManager.getHeaders();
            if (headersProps != null) {
                for (JMeterProperty prop : headersProps) {
                    Header header = (Header) prop.getObjectValue();
                    String n = header.getName();
                    if(n.startsWith(":")){
                        LOG.warn("The specified pseudo header {} is not allowed "
                            + "and will be ignored", n);
                    }
                    else if (!HTTPConstants.HEADER_CONTENT_LENGTH.equalsIgnoreCase(n)) {
                        String v = header.getValue();
                        v = v.replaceFirst(":\\d+$", ""); // remove any port
                        headers.put(n, v);
                    }
                }
            }
            // TODO CacheManager
        }
        if (cookieManager != null) {
            String cookieHeader = cookieManager.getCookieHeaderForURL(url);
            if (cookieHeader != null) {
                headers.put(HTTPConstants.HEADER_COOKIE, cookieHeader);
            }
        }

        return headers;
    }

    public void addStreamHandler(HTTP2StreamHandler http2StreamHandler) {
        streamHandlers.add(http2StreamHandler);
    }

    public void disconnect() throws Exception {
        client.stop();
    }

    public synchronized List<HTTP2SampleResult> awaitResponses() throws InterruptedException {
        List<HTTP2SampleResult> results = new ArrayList<>();
        while (!streamHandlers.isEmpty()) {

        	HTTP2StreamHandler h = streamHandlers.poll();
            results.add(h.getHTTP2SampleResult());
            
           // LOG.warn("PCP: Waiting for Response() "+h.getHTTP2SampleResult().getResponseCode());
             if(false==h.getHTTP2SampleResult().isSync())
             {
            
            	h.getCompletedFuture().getNow(null);
             }
             else
             {
	            try {
	                // wait to receive all the response of the request
	               h.getCompletedFuture().get(h.getTimeout(), TimeUnit.MILLISECONDS);
	               // LOG.warn("PCP: Returning from Waiting for Response "+ h.getHTTP2SampleResult().getResponseCode() );
	               
	                
	            } catch (ExecutionException | TimeoutException e) {
	                HTTP2SampleResult sample = h.getHTTP2SampleResult();
	                sample.setErrorResult("Error while await for response", e);
	                sample.setResponseHeaders("");
	            }
             }

           
        }
        
        return results;
    }
    public List<HTTP2SampleResult> awaitResponses1() throws InterruptedException {
        List<HTTP2SampleResult> results = new ArrayList<>();
        while (!streamHandlers.isEmpty()) {

        	HTTP2StreamHandler h = streamHandlers.poll();
        	//HTTP2StreamHandler h = streamHandlers.peek();
            results.add(h.getHTTP2SampleResult());
            
            LOG.warn("PCP: Waiting for Response()1 ");
           // h.getCompletedFuture().getNow(null);
            try {
                // wait to receive all the response of the request
               h.getCompletedFuture().get(500, TimeUnit.MILLISECONDS);
                LOG.warn("PCP: Returning from Waiting for Response "+ h.getHTTP2SampleResult().getResponseCode() );
               
                
            } catch (ExecutionException | TimeoutException e) {
                HTTP2SampleResult sample = h.getHTTP2SampleResult();
                sample.setErrorResult("Error while await for response", e);
                sample.setResponseHeaders("");
            }
           

           
        }
        
        return results;
    }
}
