package com.cisco.josouthe;

import com.appdynamics.agent.api.AppdynamicsAgent;
import com.appdynamics.agent.api.EntryTypes;
import com.appdynamics.agent.api.ExitCall;
import com.appdynamics.agent.api.Transaction;
import com.appdynamics.instrumentation.sdk.Rule;
import com.appdynamics.instrumentation.sdk.SDKClassMatchType;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.IReflector;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.ReflectorException;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClientTransactionInterceptor extends MyBaseInterceptor {
    IReflector addHeader;

    public ClientTransactionInterceptor() {
        super();

        addHeader = makeInvokeInstanceMethodReflector("addHeader", String.class.getCanonicalName(), String.class.getCanonicalName());
    }

    @Override
    public Object onMethodBegin(Object objectIntercepted, String className, String methodName, Object[] params) {
        ExitCall exitCall = null;
        switch(methodName) {
            case "sendHeaders": {
                String verb = (String) params[0];
                String urlString = (String) params[1];
                Object body = params[2];
                try {
                    URL url = new URL(urlString);
                    Map<String, String> map = new HashMap<>();
                    map.put("Host", url.getHost());
                    map.put("Port", String.valueOf(url.getPort()) );
                    map.put("Method", verb);
                    Transaction transaction = AppdynamicsAgent.getTransaction();
                    if( isFakeTransaction(transaction) ) {
                        getLogger().debug("No transaction is active while this exit call was made, we should review.");
                        return null;
                    }
                    //collectSnapshotData( AppdynamicsAgent.getTransaction(), "Body of external request", String.valueOf(body) );
                    exitCall = transaction.startHttpExitCall(map, url, true);
                    addHeader.execute( objectIntercepted.getClass().getClassLoader(), objectIntercepted, new Object[]{ AppdynamicsAgent.TRANSACTION_CORRELATION_HEADER, exitCall.getCorrelationHeader()});
                    getLogger().debug(String.format("Exit Call tagged with singularity header: '%s'", exitCall.getCorrelationHeader()));
                } catch (MalformedURLException e) {
                    getLogger().info(String.format("Unable to create url for exit call. Exception: %s", e));
                } catch (ReflectorException exception) {
                    getLogger().info(String.format("Reflection error while attempting to add correlation header to exitcall. Exception: %s",exception));
                }
                break;
            }
            case "dispose": {
                exitCall = AppdynamicsAgent.fetchExitCall(objectIntercepted);
                break;
            }
            default: {
                getLogger().info(String.format("Method name not supported '%s.%s()'", className, methodName));
                return null;
            }
        }
        return exitCall;
    }

    @Override
    public void onMethodEnd(Object state, Object objectIntercepted, String className, String methodName, Object[] params, Throwable exception, Object returnVal) {
        if( state == null ) return;
        ExitCall exitCall = (ExitCall) state;

        if( exception != null ) {
            AppdynamicsAgent.getTransaction().markAsError(String.format("Error in exit call: %s",exception));
        }

        switch(methodName) {
            case "sendHeaders": {
                exitCall.stash(objectIntercepted);
                break;
            }
            case "dispose": {
                exitCall.end();
                break;
            }
            default: {
                getLogger().info(String.format("Method name not supported '%s.%s()'", className, methodName));
                return;
            }
        }

    }

    @Override
    public List<Rule> initializeRules() {
        List<Rule> rules = new ArrayList<>();
        rules.add(new Rule.Builder(
                "com.vordel.dwe.http.ClientTransaction")
                .classMatchType(SDKClassMatchType.MATCHES_CLASS)
                .methodMatchString("sendHeaders")
                .withParams(String.class.getCanonicalName(), String.class.getCanonicalName(), "com.vordel.mime.HeaderSet", "com.vordel.mime.HeaderSet", boolean.class.getCanonicalName(), boolean.class.getCanonicalName(),boolean.class.getCanonicalName())
                .build()
        );
        rules.add(new Rule.Builder(
                "com.vordel.dwe.http.ClientTransaction")
                .classMatchType(SDKClassMatchType.MATCHES_CLASS)
                .methodMatchString("dispose")
                .build()
        );
        return rules;
    }
}
