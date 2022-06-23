package com.cisco.josouthe;

import com.appdynamics.agent.api.AppdynamicsAgent;
import com.appdynamics.agent.api.EntryTypes;
import com.appdynamics.agent.api.ServletContext;
import com.appdynamics.agent.api.Transaction;
import com.appdynamics.instrumentation.sdk.Rule;
import com.appdynamics.instrumentation.sdk.SDKClassMatchType;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.IReflector;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.*;

public class HTTPPluginInterceptor extends MyBaseInterceptor{

    IReflector getRequestURI, getHeaders, getMethod, getSNI; //ServerTransaction
    IReflector getHeader, keySet; //HeaderSet ala Google
    IReflector toString;

    public HTTPPluginInterceptor() {
        super();
        getRequestURI = makeInvokeInstanceMethodReflector("getRequestURI"); //returns a String
        getMethod = makeInvokeInstanceMethodReflector("getMethod"); //returns String
        getSNI = makeInvokeInstanceMethodReflector("getSNI"); // returns String
        getHeaders = makeInvokeInstanceMethodReflector("getHeaders"); //returns a com.vordel.mime.HeaderSet
        getHeader = makeInvokeInstanceMethodReflector("getHeader", String.class.getCanonicalName()); //returns a String
        keySet = makeInvokeInstanceMethodReflector("keySet"); //returns Set<K>
        toString = makeInvokeInstanceMethodReflector("toString");
    }

    //public void invoke(HTTPProtocol protocol, HTTPProtocol handler, ServerTransaction txn, CorrelationID id, Map<String, Object> loopbackMessage) throws IOException {
    @Override
    public Object onMethodBegin(Object objectIntercepted, String className, String methodName, Object[] params) {
        Object serverTransaction = params[2];
        Object correlationId = params[3];
        //ServletContext servletContext = buildServletContext(serverTransaction);
        Transaction transaction = AppdynamicsAgent.getTransaction();
        if( isFakeTransaction(transaction) ) {
            transaction = AppdynamicsAgent.startServletTransaction(buildServletContext(serverTransaction), EntryTypes.HTTP, getCorrelationID(serverTransaction), false);
        }
        transaction.collectData("correlationId", String.valueOf(correlationId), dataScopes );
        return transaction;
    }

    private ServletContext buildServletContext(Object serverTransaction) {
        getLogger().debug("entering buildServletContext");
        ServletContext.ServletContextBuilder builder = new ServletContext.ServletContextBuilder();
        Object headerset = getReflectiveObject(serverTransaction, getHeaders);
        String hostname = (String) getReflectiveObject(headerset, getHeader, "Host");
        try {
            //getLogger().info("buildServletContext what is an SNI? "+ getReflectiveString(serverTransaction, getSNI, "I DO NOT KNOW"));
            String requestURI = (String)getReflectiveObject(serverTransaction, getRequestURI);
            String url = String.format("https://%s%s", hostname, requestURI);
            getLogger().debug(String.format("buildServletContext Built Request URL: '%s'", url));
            builder.withURL( url );
        } catch (MalformedURLException e) {
            getLogger().info("buildServletContext Bad URL, can't start a servlet! Exception: "+ e.getMessage());
        }
        builder.withRequestMethod( getReflectiveString(serverTransaction,getMethod, "UNKNOWN-METHOD"));
        builder.withHostValue(hostname);

        Set<Object> headerKeySet = (Set<Object>) getReflectiveObject(headerset, keySet);
        Map<String,String> appdHeaderMap = new HashMap<>();
        for( Object keyObject : headerKeySet ){
            String headerName = (String) getReflectiveObject(keyObject, toString);
            String value = (String) getReflectiveObject(headerset, getHeader, headerName);
            appdHeaderMap.put(headerName, value);
        }
        builder.withHeaders(appdHeaderMap);
        getLogger().debug("leaving buildServletContext");
        return builder.build();
    }

    private String getCorrelationID( Object serverTransaction ) {
        Object headerset = getReflectiveObject(serverTransaction, getHeaders);
        String singularityHeader = (String) getReflectiveObject(headerset, getHeader, String.valueOf(CORRELATION_HEADER_KEY));
        getLogger().debug(String.format("Singularity Header from incoming request: '%s'", singularityHeader));
        return singularityHeader;
    }

    @Override
    public void onMethodEnd(Object state, Object object, String className, String methodName, Object[] params, Throwable exception, Object returnVal) {
        Transaction transaction = (Transaction) state;
        if( transaction == null ) return;

        if( exception != null ) {
            transaction.markAsError( exception.getMessage() );
        }
        transaction.end();
    }

    @Override
    public List<Rule> initializeRules() {
        List<Rule> rules = new ArrayList<>();
        rules.add(new Rule.Builder(
                "com.vordel.dwe.http.HTTPPlugin")
                .classMatchType(SDKClassMatchType.MATCHES_CLASS)
                .methodMatchString("invoke")
                .build()
        );
        return rules;
    }
}
