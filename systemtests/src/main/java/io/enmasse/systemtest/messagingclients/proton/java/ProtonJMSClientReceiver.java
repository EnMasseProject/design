/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.messagingclients.proton.java;

import io.enmasse.systemtest.messagingclients.AbstractClient;
import io.enmasse.systemtest.messagingclients.ClientArgument;
import io.enmasse.systemtest.messagingclients.ClientArgumentMap;
import io.enmasse.systemtest.messagingclients.ClientType;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class ProtonJMSClientReceiver extends AbstractClient {
    public ProtonJMSClientReceiver() throws Exception {
        this(ClientType.CLI_JAVA_PROTON_JMS_RECEIVER, null);
    }

    public ProtonJMSClientReceiver(Path logPath) throws Exception {
        this(ClientType.CLI_JAVA_PROTON_JMS_RECEIVER, logPath);
    }

    protected ProtonJMSClientReceiver(ClientType type, Path logPath) throws Exception {
        super(type, logPath);
    }

    @Override
    protected void fillAllowedArgs() {
        allowedArgs.add(ClientArgument.CONN_RECONNECT);
        allowedArgs.add(ClientArgument.CONN_RECONNECT_INTERVAL);
        allowedArgs.add(ClientArgument.CONN_RECONNECT_LIMIT);
        allowedArgs.add(ClientArgument.CONN_RECONNECT_TIMEOUT);
        allowedArgs.add(ClientArgument.CONN_HEARTBEAT);
        allowedArgs.add(ClientArgument.CONN_SSL_CERTIFICATE);
        allowedArgs.add(ClientArgument.CONN_SSL_PRIVATE_KEY);
        allowedArgs.add(ClientArgument.CONN_SSL_PASSWORD);
        allowedArgs.add(ClientArgument.CONN_SSL_TRUST_STORE);
        allowedArgs.add(ClientArgument.CONN_SSL_VERIFY_PEER);
        allowedArgs.add(ClientArgument.CONN_SSL_VERIFY_PEER_NAME);
        allowedArgs.add(ClientArgument.CONN_MAX_FRAME_SIZE);
        allowedArgs.add(ClientArgument.CONN_ASYNC_ACKS);
        allowedArgs.add(ClientArgument.CONN_ASYNC_SEND);
        allowedArgs.add(ClientArgument.CONN_AUTH_MECHANISM);
        allowedArgs.add(ClientArgument.CONN_AUTH_SASL);
        allowedArgs.add(ClientArgument.CONN_CLIENT_ID);
        allowedArgs.add(ClientArgument.CONN_CLOSE_TIMEOUT);
        allowedArgs.add(ClientArgument.CONN_CONN_TIMEOUT);
        allowedArgs.add(ClientArgument.CONN_DRAIN_TIMEOUT);
        allowedArgs.add(ClientArgument.CONN_SSL_TRUST_ALL);
        allowedArgs.add(ClientArgument.CONN_SSL_VERIFY_HOST);

        allowedArgs.add(ClientArgument.TX_SIZE);
        allowedArgs.add(ClientArgument.TX_ACTION);
        allowedArgs.add(ClientArgument.TX_ENDLOOP_ACTION);

        allowedArgs.add(ClientArgument.LINK_DURABLE);
        allowedArgs.add(ClientArgument.LINK_AT_MOST_ONCE);
        allowedArgs.add(ClientArgument.LINK_AT_LEAST_ONCE);
        allowedArgs.add(ClientArgument.CAPACITY);

        allowedArgs.add(ClientArgument.LOG_LIB);
        allowedArgs.add(ClientArgument.LOG_STATS);
        allowedArgs.add(ClientArgument.LOG_MESSAGES);

        allowedArgs.add(ClientArgument.BROKER);
        allowedArgs.add(ClientArgument.ADDRESS);
        allowedArgs.add(ClientArgument.USERNAME);
        allowedArgs.add(ClientArgument.PASSWORD);
        allowedArgs.add(ClientArgument.COUNT);
        allowedArgs.add(ClientArgument.CLOSE_SLEEP);
        allowedArgs.add(ClientArgument.TIMEOUT);
        allowedArgs.add(ClientArgument.DURATION);

        allowedArgs.add(ClientArgument.MSG_SELECTOR);
        allowedArgs.add(ClientArgument.RECV_BROWSE);
        allowedArgs.add(ClientArgument.ACTION);
        allowedArgs.add(ClientArgument.PROCESS_REPLY_TO);
    }

    @Override
    protected ClientArgumentMap transformArguments(ClientArgumentMap args) {
        args = javaBrokerTransformation(args);
        args = modifySelectorArg(args);
        args.put(ClientArgument.LOG_LIB, "trace");
        return args;
    }

    protected ClientArgumentMap modifySelectorArg(ClientArgumentMap args) {
        if (args.getValues(ClientArgument.SELECTOR) != null) {
            args.put(ClientArgument.MSG_SELECTOR, args.getValues(ClientArgument.SELECTOR).get(0));
            args.remove(ClientArgument.SELECTOR);
        }
        return args;
    }

    @Override
    protected List<String> transformExecutableCommand(String executableCommand) {
        return Arrays.asList("java", "-jar", executableCommand, "receiver");
    }

    @Override
    public Supplier<Predicate<String>> clientAttachedProbeFactory() {
        return () -> {
            return new Predicate<String>() {
                @Override
                public boolean test(String line) {
                    return line.contains("New Proton Event: LINK_REMOTE_OPEN");
                }
            };
        };
    }

}
