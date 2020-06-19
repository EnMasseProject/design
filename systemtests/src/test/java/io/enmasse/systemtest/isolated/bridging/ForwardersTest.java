/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.isolated.bridging;

import io.enmasse.address.model.Address;
import io.enmasse.address.model.AddressBuilder;
import io.enmasse.address.model.AddressSpace;
import io.enmasse.address.model.AddressSpaceSpecConnectorCredentials;
import io.enmasse.address.model.AddressSpaceSpecConnectorTls;
import io.enmasse.address.model.AddressSpecForwarderBuilder;
import io.enmasse.address.model.AddressSpecForwarderDirection;
import io.enmasse.systemtest.UserCredentials;
import io.enmasse.systemtest.amqp.AmqpClient;
import io.enmasse.systemtest.bases.bridging.BridgingBase;
import io.enmasse.systemtest.logs.CustomLogger;
import io.enmasse.systemtest.model.address.AddressType;
import io.enmasse.systemtest.model.addressplan.DestinationPlan;
import io.enmasse.systemtest.time.TimeoutBudget;
import io.enmasse.systemtest.utils.AddressSpaceUtils;
import io.enmasse.systemtest.utils.AddressUtils;
import io.enmasse.systemtest.utils.TestUtils;
import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.Data;
import org.apache.qpid.proton.message.Message;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import static io.enmasse.systemtest.TestTag.ACCEPTANCE;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class ForwardersTest extends BridgingBase {

    private static Logger log = CustomLogger.getLogger();
    private static final String REMOTE_QUEUE1 = "queue1";

    @Test
    @Tag(ACCEPTANCE)
    void testForwardFromQueueToRemoteQueue() throws Exception {
        doTestForwarderOut(null, defaultCredentials());
    }

    @Test
    @Tag(ACCEPTANCE)
    void testForwardFromSubscriptionToRemoteQueue() throws Exception {
        doTestForwarderSubscriptionOut(null, defaultCredentials());
    }

    @Test
    void testForwardFromRemoteQueue() throws Exception {
        doTestForwarderIn(null, defaultCredentials());

    }

    @Test
    void testForwardToUnavailableBroker() throws Exception {

        AddressSpace space = createAddressSpace("forward-to-remote", "*", null, defaultCredentials());
        Address forwarder = new AddressBuilder()
                .withNewMetadata()
                .withName(AddressUtils.generateAddressMetadataName(space, "forwarder-queue1"))
                .withNamespace(remoteBrokerNamespace)
                .endMetadata()
                .withNewSpec()
                .withAddress("forwarder-queue1")
                .withType(AddressType.QUEUE.toString())
                .withPlan(DestinationPlan.STANDARD_SMALL_QUEUE)
                .addToForwarders(new AddressSpecForwarderBuilder()
                        .withName("forwarder1")
                        .withRemoteAddress(REMOTE_NAME + "/" + REMOTE_QUEUE1)
                        .withDirection(AddressSpecForwarderDirection.out)
                        .build())
                .endSpec()
                .build();
        resourcesManager.setAddresses(forwarder);
        AddressUtils.waitForForwardersReady(new TimeoutBudget(1, TimeUnit.MINUTES), forwarder);

        UserCredentials localUser = new UserCredentials("test", "test");
        resourcesManager.createOrUpdateUser(space, localUser);

        doTestSendToForwarder(space, forwarder, localUser, REMOTE_QUEUE1, 5);

        //make broker unavailable
        scaleDownBroker();

        //check connector and address forwarder is not ready
        AddressSpaceUtils.waitForAddressSpaceConnectorsNotReady(space);
        TestUtils.waitUntilCondition("Forwarders not ready", phase -> {
            try {
                AddressUtils.waitForForwardersReady(new TimeoutBudget(20, TimeUnit.SECONDS), forwarder);
                return false;
            } catch (Exception ex) {
                return ex instanceof IllegalStateException;
            }
        }, new TimeoutBudget(3, TimeUnit.MINUTES));
        //however address should be still ready
        AddressUtils.waitForDestinationsReady(new TimeoutBudget(30, TimeUnit.SECONDS), forwarder);

        //send to forwarder
        int messagesBatch = 20;
        AmqpClient localClient = getAmqpClientFactory().createQueueClient(space);
        localClient.getConnectOptions().setCredentials(localUser);

        localClient.sendMessages(forwarder.getSpec().getAddress(), TestUtils.generateMessages(messagesBatch));

        //wake up the broker
        scaleUpBroker();

        //wait until forwarder is ready again
        AddressUtils.waitForDestinationsReady(new TimeoutBudget(30, TimeUnit.SECONDS), forwarder);
        AddressSpaceUtils.waitForAddressSpaceConnectorsReady(space);
        AddressUtils.waitForForwardersReady(new TimeoutBudget(1, TimeUnit.MINUTES), forwarder);

        //check messages where automatically forwarded once broker is back up again
        AmqpClient clientToRemote = createClientToRemoteBroker();

        var receivedInRemote = clientToRemote.recvMessages(REMOTE_QUEUE1, messagesBatch);

        assertThat("Wrong count of messages received from remote queue: " + REMOTE_QUEUE1, receivedInRemote.get(1, TimeUnit.MINUTES).size(), is(messagesBatch));
    }

    @Test
    public void testForwarderTLSOut() throws Exception {
        doTestForwarderOut(defaultTls(), defaultCredentials());
    }

    @Test
    public void testForwarderMutualTLSOut() throws Exception {
        doTestForwarderOut(defaultMutualTls(), null);
    }

    @Test
    public void testForwarderTLSIn() throws Exception {
        doTestForwarderIn(defaultTls(), defaultCredentials());
    }

    @Test
    public void testForwarderMutualTLSIn() throws Exception {
        doTestForwarderIn(defaultMutualTls(), null);
    }

    @Test
    void testForwardSecretSettings() throws Exception {
        doTestForwarderOut(tlsInSecret(), credentialsInSecret());
    }

    @Test
    void testForwardToFullQueue() throws Exception {
        AddressSpace space = createAddressSpace("forward-to-full", "*", null, defaultCredentials());
        Address forwarder = new AddressBuilder()
                .withNewMetadata()
                .withName(AddressUtils.generateAddressMetadataName(space, "forwarder-queue1"))
                .withNamespace(remoteBrokerNamespace)
                .endMetadata()
                .withNewSpec()
                .withAddress("forwarder-queue1")
                .withType(AddressType.QUEUE.toString())
                .withPlan(DestinationPlan.STANDARD_SMALL_QUEUE)
                .addToForwarders(new AddressSpecForwarderBuilder()
                        .withName("forwarder1")
                        .withRemoteAddress(REMOTE_NAME + "/" + REMOTE_QUEUE1)
                        .withDirection(AddressSpecForwarderDirection.out)
                        .build())
                .endSpec()
                .build();
        resourcesManager.setAddresses(forwarder);
        AddressUtils.waitForForwardersReady(new TimeoutBudget(1, TimeUnit.MINUTES), forwarder);

        UserCredentials localUser = new UserCredentials("test", "test");
        resourcesManager.createOrUpdateUser(space, localUser);

        //send until remote broker is full
        AmqpClient clientToRemote = createClientToRemoteBroker();
        boolean full = false;
        byte[] bytes = new byte[1024];
        Random random = new Random();
        int messagesSent = 0;
        TimeoutBudget timeout = new TimeoutBudget(30, TimeUnit.SECONDS);
        do {
            Message message = Message.Factory.create();
            random.nextBytes(bytes);
            message.setBody(new AmqpValue(new Data(new Binary(bytes))));
            message.setAddress(REMOTE_QUEUE1);
            try {
                clientToRemote.sendMessage(REMOTE_QUEUE1, message).get(5, TimeUnit.SECONDS);
                messagesSent++;
                if (timeout.timeoutExpired()) {
                   Assertions.fail("Timeout waiting for remote broker to become full, probably error in test env configuration");
                }
            } catch (Exception e) {
                full = true;
                log.info("broker is full");
            }
        } while(!full);

        int messagesBatch = 20;

        AmqpClient localClient = getAmqpClientFactory().createQueueClient(space);
        localClient.getConnectOptions().setCredentials(localUser);
        //send to address with forwarder wich will retry forwarding indefinetly until remote broker is available
        localClient.sendMessages(forwarder.getSpec().getAddress(), TestUtils.generateMessages(messagesBatch));

        //receive messages that was causing remote broker to block, and check that we also get the forwarded messages
        var receivedInDLQ = clientToRemote.recvMessages(REMOTE_QUEUE1, messagesSent + messagesBatch);
        assertThat("Wrong count of messages received on remote address after queue is full in remote broker", receivedInDLQ.get(5, TimeUnit.MINUTES).size(), is(messagesSent + messagesBatch));

    }

    private void doTestForwarderOut(AddressSpaceSpecConnectorTls tlsSettings, AddressSpaceSpecConnectorCredentials credentials) throws Exception {
        AddressSpace space = createAddressSpace("forward-to-remote", "*", tlsSettings, credentials);
        Address forwarder = new AddressBuilder()
                .withNewMetadata()
                .withName(AddressUtils.generateAddressMetadataName(space, "forwarder-queue1"))
                .withNamespace(remoteBrokerNamespace)
                .endMetadata()
                .withNewSpec()
                .withAddress("forwarder-queue1")
                .withType(AddressType.QUEUE.toString())
                .withPlan(DestinationPlan.STANDARD_SMALL_QUEUE)
                .addToForwarders(new AddressSpecForwarderBuilder()
                        .withName("forwarder1")
                        .withRemoteAddress(REMOTE_NAME + "/" + REMOTE_QUEUE1)
                        .withDirection(AddressSpecForwarderDirection.out)
                        .build())
                .endSpec()
                .build();
        resourcesManager.setAddresses(forwarder);
        AddressUtils.waitForForwardersReady(new TimeoutBudget(1, TimeUnit.MINUTES), forwarder);

        UserCredentials localUser = new UserCredentials("test", "test");
        resourcesManager.createOrUpdateUser(space, localUser);

        int messagesBatch = 20;

        doTestSendToForwarder(space, forwarder, localUser, REMOTE_QUEUE1, messagesBatch);
    }

    private void doTestForwarderSubscriptionOut(AddressSpaceSpecConnectorTls tlsSettings, AddressSpaceSpecConnectorCredentials credentials) throws Exception {
        AddressSpace space = createAddressSpace("forward-to-remote", "*", tlsSettings, credentials);
        Address topic = new AddressBuilder()
                .withNewMetadata()
                .withName(AddressUtils.generateAddressMetadataName(space, "forwarder-topic1"))
                .withNamespace(remoteBrokerNamespace)
                .endMetadata()
                .withNewSpec()
                .withAddress("forwarder-topic1")
                .withType(AddressType.TOPIC.toString())
                .withPlan(DestinationPlan.STANDARD_SMALL_TOPIC)
                .endSpec()
                .build();
        Address forwarder = new AddressBuilder()
                .withNewMetadata()
                .withName(AddressUtils.generateAddressMetadataName(space, "forwarder-sub1"))
                .withNamespace(remoteBrokerNamespace)
                .endMetadata()
                .withNewSpec()
                .withAddress("forwarder-sub1")
                .withTopic("forwarder-topic1")
                .withType(AddressType.SUBSCRIPTION.toString())
                .withPlan(DestinationPlan.STANDARD_SMALL_SUBSCRIPTION)
                .addToForwarders(new AddressSpecForwarderBuilder()
                        .withName("forwarder1")
                        .withRemoteAddress(REMOTE_NAME + "/" + REMOTE_QUEUE1)
                        .withDirection(AddressSpecForwarderDirection.out)
                        .build())
                .endSpec()
                .build();
        resourcesManager.setAddresses(topic, forwarder);
        AddressUtils.waitForForwardersReady(new TimeoutBudget(1, TimeUnit.MINUTES), forwarder);

        UserCredentials localUser = new UserCredentials("test", "test");
        resourcesManager.createOrUpdateUser(space, localUser);

        int messagesBatch = 20;

        doTestSendToForwarder(space, topic, localUser, REMOTE_QUEUE1, messagesBatch);
    }

    private void doTestForwarderIn(AddressSpaceSpecConnectorTls tlsSettings, AddressSpaceSpecConnectorCredentials credentials) throws Exception {
        AddressSpace space = createAddressSpace("forward-from-remote", "*", tlsSettings, credentials);
        Address forwarder = new AddressBuilder()
                .withNewMetadata()
                .withName(AddressUtils.generateAddressMetadataName(space, "forwarder-queue1"))
                .withNamespace(remoteBrokerNamespace)
                .endMetadata()
                .withNewSpec()
                .withAddress("forwarder-queue1")
                .withType(AddressType.QUEUE.toString())
                .withPlan(DestinationPlan.STANDARD_SMALL_QUEUE)
                .addToForwarders(new AddressSpecForwarderBuilder()
                        .withName("forwarder1")
                        .withRemoteAddress(REMOTE_NAME + "/" + REMOTE_QUEUE1)
                        .withDirection(AddressSpecForwarderDirection.in)
                        .build())
                .endSpec()
                .build();
        resourcesManager.setAddresses(forwarder);
        AddressUtils.waitForForwardersReady(new TimeoutBudget(1, TimeUnit.MINUTES), forwarder);

        UserCredentials localUser = new UserCredentials("test", "test");
        resourcesManager.createOrUpdateUser(space, localUser);

        int messagesBatch = 20;

        doTestReceiveInForwarder(space, forwarder, localUser, REMOTE_QUEUE1, messagesBatch);
    }

    private void doTestSendToForwarder(AddressSpace space, Address forwarder, UserCredentials localUser, String remoteAddress, int messagesBatch) throws Exception {
        //send to address with forwarder

        AmqpClient localClient = getAmqpClientFactory().createQueueClient(space);
        localClient.getConnectOptions().setCredentials(localUser);

        localClient.sendMessages(forwarder.getSpec().getAddress(), TestUtils.generateMessages(messagesBatch));

        //receive in remote broker

        AmqpClient clientToRemote = createClientToRemoteBroker();

        var receivedInRemote = clientToRemote.recvMessages(remoteAddress, messagesBatch);

        assertThat("Wrong count of messages received from remote queue: " + remoteAddress, receivedInRemote.get(1, TimeUnit.MINUTES).size(), is(messagesBatch));
    }

    private void doTestReceiveInForwarder(AddressSpace space, Address forwarder, UserCredentials localUser, String remoteAddress, int messagesBatch) throws Exception {
        //send to remote broker

        AmqpClient clientToRemote = createClientToRemoteBroker();

        clientToRemote.sendMessages(remoteAddress, TestUtils.generateMessages(messagesBatch));

        //receive in address with forwarder

        AmqpClient localClient = getAmqpClientFactory().createQueueClient(space);
        localClient.getConnectOptions().setCredentials(localUser);

        var receivedInRemote = localClient.recvMessages(forwarder.getSpec().getAddress(), messagesBatch);

        assertThat("Wrong count of messages received in local address: " + forwarder.getSpec().getAddress(), receivedInRemote.get(1, TimeUnit.MINUTES).size(), is(messagesBatch));
    }


}
