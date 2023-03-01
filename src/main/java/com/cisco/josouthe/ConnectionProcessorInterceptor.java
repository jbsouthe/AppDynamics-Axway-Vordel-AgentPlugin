package com.cisco.josouthe;

import com.appdynamics.agent.api.AppdynamicsAgent;
import com.appdynamics.agent.api.ExitCall;
import com.appdynamics.agent.api.Transaction;
import com.appdynamics.instrumentation.sdk.Rule;
import com.appdynamics.instrumentation.sdk.SDKClassMatchType;
import com.appdynamics.instrumentation.sdk.SDKStringMatchType;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.IReflector;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.ReflectorException;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConnectionProcessorInterceptor extends MyBaseInterceptor{
    IReflector messageGet; //com.vordel.circuit.Message
    IReflector addHeader; //com.vordel.mime.HeaderSet

    public ConnectionProcessorInterceptor() {
        super();

        messageGet = makeInvokeInstanceMethodReflector("get", String.class.getCanonicalName()); //returns Object
        addHeader = makeInvokeInstanceMethodReflector("addHeader", String.class.getCanonicalName(), String.class.getCanonicalName()); //returns void
    }

    @Override
    public Object onMethodBegin(Object objectIntercepted, String className, String methodName, Object[] params) {
        //Object circuit = params[0];
        Object message = params[1];
        Object headers = params[2];
        String verb = (String) params[3];
        //Object body = params[4];

        Transaction transaction = AppdynamicsAgent.getTransaction();
        if( isFakeTransaction(transaction) ) {
            getLogger().debug("No transaction is active while this exit call was made, we should review.");
            return null;
        }

        URI uri = (URI) getReflectiveObject(message, messageGet, "http.request.uri");
        Map<String, String> map = new HashMap<>();
        map.put("Host", (String) getReflectiveObject(message, messageGet, "http.destination.host") );
        map.put("Port", (String) getReflectiveObject(message, messageGet, "http.destination.port") );
        map.put("Method", verb);
        try {
            ExitCall exitCall = transaction.startHttpExitCall(map, uri.toURL(), false);
            addHeader.execute( headers.getClass().getClassLoader(), headers, new Object[]{ AppdynamicsAgent.TRANSACTION_CORRELATION_HEADER, exitCall.getCorrelationHeader()});
            getLogger().debug(String.format("Exit Call '%s' tagged with singularity header: '%s'", uri.toURL(), exitCall.getCorrelationHeader()));
            return exitCall;
        } catch (MalformedURLException e) {
            getLogger().info(String.format("Unable to create url for exit call. Exception: %s", e));
        } catch (ReflectorException exception) {
            getLogger().info(String.format("Reflection error while attempting to add correlation header to exitcall. Exception: %s",exception));
        }
        return null;
    }

    @Override
    public void onMethodEnd(Object state, Object object, String className, String methodName, Object[] params, Throwable exception, Object returnVal) {
        if( state == null ) return;
        ExitCall exitCall = (ExitCall) state;

        Transaction transaction = AppdynamicsAgent.getTransaction();
        if( exception != null ) {
            transaction.markAsError(String.format("Error in exit call: %s",exception));
        }

        Object message = params[1];
        Integer responseStatus = (Integer) getReflectiveObject(message, messageGet, "http.response.status");
        if( responseStatus == null ) getLogger().info(String.format("Response Status is null for BT '%s'?",transaction.getUniqueIdentifier()));
        if( responseStatus != null && responseStatus >= 400 ) {
            String responseInfo = (String) getReflectiveObject(message, messageGet, "http.response.info");
            String responseFailureMessage = (String) getReflectiveObject(message, messageGet, "filter.connect.to.url.failure.description");
            transaction.markAsError(String.format("HTTP Response: %d '%s' Failure Message: '%s'", responseStatus, responseInfo, responseFailureMessage));
        }

        exitCall.end();
    }

    @Override
    public List<Rule> initializeRules() {
        //ConnectionProcessor.invoke(Circuit c, Message m, HeaderSet headers, String verb, Body body)
        List<Rule> rules = new ArrayList<>();
        rules.add(new Rule.Builder(
                "com.vordel.circuit.net.ConnectionProcessor")
                .classMatchType(SDKClassMatchType.MATCHES_CLASS)
                .methodMatchString("invoke")
                .methodStringMatchType(SDKStringMatchType.EQUALS)
                //protected boolean invoke(Circuit c, Message m, HeaderSet headers, String verb, Body body) throws CircuitAbortException {
                .withParams("com.vordel.config.Circuit", "com.vordel.circuit.Message", "com.vordel.mime.HeaderSet", "java.lang.String", "com.vordel.mime.Body")
                .build()
        );
        return rules;
    }
}
