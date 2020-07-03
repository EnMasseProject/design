/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.messagingclients.rhea;

import io.enmasse.systemtest.messagingclients.AbstractClient;
import io.enmasse.systemtest.messagingclients.ClientArgument;
import io.enmasse.systemtest.messagingclients.ClientArgumentMap;
import io.enmasse.systemtest.messagingclients.ClientRole;
import io.enmasse.systemtest.messagingclients.ClientType;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class RheaClientReceiver extends AbstractClient {
    public RheaClientReceiver() throws Exception {
        super(ClientType.CLI_RHEA_RECEIVER, ClientRole.RECEIVER);
    }

    public RheaClientReceiver(String namespace) throws Exception {
        super(ClientType.CLI_RHEA_RECEIVER, ClientRole.RECEIVER, namespace);
    }

    public RheaClientReceiver(Path logPath) throws Exception {
        super(ClientType.CLI_RHEA_RECEIVER, ClientRole.RECEIVER, logPath);
    }

    @Override
    protected void fillAllowedArgs() {
        allowedArgs.add(ClientArgument.CONN_URLS);
        allowedArgs.add(ClientArgument.CONN_RECONNECT);
        allowedArgs.add(ClientArgument.CONN_RECONNECT_INTERVAL);
        allowedArgs.add(ClientArgument.CONN_RECONNECT_LIMIT);
        allowedArgs.add(ClientArgument.CONN_RECONNECT_TIMEOUT);
        allowedArgs.add(ClientArgument.CONN_HEARTBEAT);
        allowedArgs.add(ClientArgument.CONN_SSL);
        allowedArgs.add(ClientArgument.CONN_SSL_CERTIFICATE);
        allowedArgs.add(ClientArgument.CONN_SSL_PRIVATE_KEY);
        allowedArgs.add(ClientArgument.CONN_SSL_PASSWORD);
        allowedArgs.add(ClientArgument.CONN_SSL_TRUST_STORE);
        allowedArgs.add(ClientArgument.CONN_SSL_VERIFY_PEER);
        allowedArgs.add(ClientArgument.CONN_SSL_VERIFY_PEER_NAME);
        allowedArgs.add(ClientArgument.CONN_MAX_FRAME_SIZE);
        allowedArgs.add(ClientArgument.CONN_WEB_SOCKET);
        allowedArgs.add(ClientArgument.CONN_WEB_SOCKET_PROTOCOLS);
        allowedArgs.add(ClientArgument.CONN_PROPERTY);

        allowedArgs.add(ClientArgument.LINK_DURABLE);
        allowedArgs.add(ClientArgument.LINK_AT_MOST_ONCE);
        allowedArgs.add(ClientArgument.LINK_AT_LEAST_ONCE);
        allowedArgs.add(ClientArgument.CAPACITY);

        allowedArgs.add(ClientArgument.LOG_LIB);
        allowedArgs.add(ClientArgument.LOG_STATS);
        allowedArgs.add(ClientArgument.LOG_MESSAGES);

        allowedArgs.add(ClientArgument.BROKER);
        allowedArgs.add(ClientArgument.ADDRESS);
        allowedArgs.add(ClientArgument.COUNT);
        allowedArgs.add(ClientArgument.CLOSE_SLEEP);
        allowedArgs.add(ClientArgument.TIMEOUT);
        allowedArgs.add(ClientArgument.DURATION);

        allowedArgs.add(ClientArgument.SELECTOR);
        allowedArgs.add(ClientArgument.RECV_BROWSE);
        allowedArgs.add(ClientArgument.ACTION);
        allowedArgs.add(ClientArgument.PROCESS_REPLY_TO);
        allowedArgs.add(ClientArgument.RECV_LISTEN);
        allowedArgs.add(ClientArgument.RECV_LISTEN_PORT);
    }

    @Override
    protected ClientArgumentMap transformArguments(ClientArgumentMap args) {
        args = basicBrokerTransformation(args);
        args.put(ClientArgument.LOG_LIB, "TRANSPORT_FRM");
        return args;
    }

    @Override
    protected List<String> transformExecutableCommand(String executableCommand) {
        return Arrays.asList(executableCommand);
    }

    @Override
    public Supplier<Predicate<String>> linkAttachedProbeFactory() {
        return () -> {
            return new Predicate<String>() {
                int attachMsgCount = 0;
                @Override
                public boolean test(String line) {
                    if (line.contains("attach#12")) {
                        attachMsgCount++;
                    }
                    return attachMsgCount == 2;
                }
            };
        };
    }

}
