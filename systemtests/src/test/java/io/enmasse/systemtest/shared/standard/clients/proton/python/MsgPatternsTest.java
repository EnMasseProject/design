/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.shared.standard.clients.proton.python;

import io.enmasse.systemtest.bases.clients.ClientTestBase;
import io.enmasse.systemtest.messagingclients.proton.python.PythonClientReceiver;
import io.enmasse.systemtest.messagingclients.proton.python.PythonClientSender;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.enmasse.systemtest.TestTag.SHARED;

@Tag(SHARED)
class MsgPatternsTest extends ClientTestBase {

    @Test
    void testBasicMessage() throws Exception {
        doBasicMessageTest(new PythonClientSender(logPath), new PythonClientReceiver(logPath));
    }

    @Test
    @DisplayName("testTopicSubscribe")
    void testTopicSubscribe() throws Exception {
        doTopicSubscribeTest(new PythonClientSender(logPath), new PythonClientReceiver(logPath), new PythonClientReceiver(logPath));
    }

    @Test
    @Disabled("selectors for queue does not work")
    void testMessageSelectorQueue() throws Exception {
        doMessageSelectorQueueTest(new PythonClientSender(logPath), new PythonClientReceiver(logPath));
    }

    @Test
    @DisplayName("testMessageSelectorTopic")
    void testMessageSelectorTopic() throws Exception {
        doMessageSelectorTopicTest(new PythonClientSender(logPath), new PythonClientSender(logPath),
                new PythonClientReceiver(logPath), new PythonClientReceiver(logPath));
    }
}
