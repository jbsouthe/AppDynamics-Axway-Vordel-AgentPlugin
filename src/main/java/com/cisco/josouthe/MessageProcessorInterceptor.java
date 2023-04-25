package com.cisco.josouthe;

import com.appdynamics.agent.api.AppdynamicsAgent;
import com.appdynamics.agent.api.Transaction;
import com.appdynamics.agent.api.impl.NoOpTransaction;
import com.appdynamics.instrumentation.sdk.Rule;
import com.appdynamics.instrumentation.sdk.SDKClassMatchType;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.IReflector;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.ReflectorException;

import java.util.ArrayList;
import java.util.List;

public class MessageProcessorInterceptor extends MyBaseInterceptor {

    IReflector getName;

    IReflector getCorrelationID, get;

    public MessageProcessorInterceptor() {
        getName = getNewReflectionBuilder().invokeInstanceMethod("getName", true).build(); //String
        getCorrelationID = getNewReflectionBuilder().accessFieldValue( "correlationID", true ).build(); //CorrelationId
        get = getNewReflectionBuilder().invokeInstanceMethod("get", true, new String[]{ String.class.getCanonicalName() }).build();
    }

    @Override
    public Object onMethodBegin(Object objectIntercepted, String className, String methodName, Object[] params) {
        Object circuit = params[0];
        Object message = params[1];
        getLogger().info(String.format("onMethodBegin %s.%s( %s )[%d]", className, methodName, paramsToString(params), (params != null ? params.length : 0)));
        Transaction transaction = AppdynamicsAgent.getTransaction();
        if( transaction instanceof NoOpTransaction ) {
            getLogger().info("BT not active for Message: "+ String.valueOf(message));
        } else {
            getLogger().info("BT "+ transaction.getUniqueIdentifier() +" active for Message: "+ String.valueOf(message));
        }
        String circuitName = "UNKNOWN-CIRCUIT";
        try {
            circuitName = (String) getName.execute(circuit.getClass().getClassLoader(), circuit);
        } catch (ReflectorException e) {
            getLogger().info("Reflection exception on call to getName() of Circuit, exception: "+ e.getMessage());
        }
        getLogger().info(String.format("Circuit name: %s", circuitName));

        String correlationID = "NOT-SET";
        try {
            correlationID = String.valueOf(getCorrelationID.execute(params[1].getClass().getClassLoader(), message));
        } catch (ReflectorException e) {
            getLogger().info("Reflection exception on call to access correlationID of Message, exception: "+ e.getMessage());
        }
        getLogger().info(String.format("CorrelationID: %s", correlationID));

        getLogger().info(String.format("Naive correlation header read: %s = %s", AppdynamicsAgent.TRANSACTION_CORRELATION_HEADER, getCorrelationHeader(message)));
        return null;
    }

    @Override
    public void onMethodEnd(Object state, Object object, String className, String methodName, Object[] params, Throwable exception, Object returnVal) {

    }

    @Override
    public List<Rule> initializeRules() {
        List<Rule> rules = new ArrayList<>();
        rules.add(new Rule.Builder(
                "com.vordel.circuit.MessageProcessor")
                .classMatchType(SDKClassMatchType.INHERITS_FROM_CLASS)
                .methodMatchString("invoke")
                .build()
        );
        return rules;
    }

    public String getCorrelationHeader( Object message ) {
        if( message == null ) return null;
        try {
            return (String) get.execute(message.getClass().getClassLoader(), message, new Object[]{ AppdynamicsAgent.TRANSACTION_CORRELATION_HEADER});
        } catch (ReflectorException e) {
            getLogger().info("Exception trying to read appd header from message naive attempt, exception: "+ e.getMessage());
        }
        return null;
    }

    private Object paramsToString(Object[] param) {
        if( param == null || param.length == 0 ) return "";
        StringBuilder sb = new StringBuilder();
        for( int i =0 ; i< param.length; i++ ) {
            if( param[i] == null ) {
                sb.append("notSure null");
            } else {
                sb.append(param[i].getClass().getCanonicalName());
                sb.append(" ").append(String.valueOf(param[i]));
            }
            if( i < param.length-1 ) sb.append(", ");
        }
        return sb.toString();
    }
}
