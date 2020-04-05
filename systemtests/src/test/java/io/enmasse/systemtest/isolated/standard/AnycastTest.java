/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.enmasse.systemtest.isolated.standard;

import io.enmasse.address.model.Address;
import io.enmasse.address.model.AddressBuilder;
import io.enmasse.address.model.AddressSpace;
import io.enmasse.address.model.AddressSpaceBuilder;
import io.enmasse.systemtest.UserCredentials;
import io.enmasse.systemtest.amqp.AmqpClient;
import io.enmasse.systemtest.bases.TestBase;
import io.enmasse.systemtest.model.addressplan.DestinationPlan;
import io.enmasse.systemtest.model.addressspace.AddressSpacePlans;
import io.enmasse.systemtest.model.addressspace.AddressSpaceType;
import io.enmasse.systemtest.utils.AddressUtils;
import org.apache.qpid.proton.message.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.enmasse.systemtest.TestTag.ISOLATED;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@Tag(ISOLATED)
public class AnycastTest extends TestBase {

    private AddressSpace addressSpace;
    private UserCredentials userCredentials = new UserCredentials("test", "test");


    public static void runAnycastTest(Address dest, AmqpClient... clients) throws InterruptedException, TimeoutException, IOException, ExecutionException {
        if (clients.length == 0) {
            throw new IllegalStateException("Clients are required for this test");
        }
        List<String> msgs = new ArrayList<>();
        for (int i = 0; i < clients.length; i++) {
            msgs.add("message-anycast-" + i);
        }
        List<Future<List<Message>>> received = new ArrayList<>();
        for (AmqpClient client : clients) {
            received.add(client.recvMessages(dest.getSpec().getAddress(), 1));
        }
        Future<Integer> sendResult = clients[0].sendMessages(dest.getSpec().getAddress(), msgs);
        assertThat("Wrong count of messages sent", sendResult.get(1, TimeUnit.MINUTES), is(msgs.size()));
        for (int i = 0; i < received.size(); i++) {
            assertThat("Wrong count of messages received: receiver" + i,
                    received.get(i).get(1, TimeUnit.MINUTES).size(), is(1));
        }
    }

    @BeforeEach
    public void createTestEnv() throws Exception {
        addressSpace = new AddressSpaceBuilder()
                .withNewMetadata()
                .withName("anycast-space-standard")
                .withNamespace(kubernetes.getInfraNamespace())
                .endMetadata()
                .withNewSpec()
                .withType(AddressSpaceType.STANDARD.toString())
                .withPlan(AddressSpacePlans.STANDARD_UNLIMITED)
                .withNewAuthenticationService()
                .withName("standard-authservice")
                .endAuthenticationService()
                .endSpec()
                .build();
        resourceManager.createAddressSpace(addressSpace);
    }

    @Test
    void testMultipleReceivers() throws Exception {


        Address dest = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(addressSpace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(addressSpace, "anycast-multiple"))
                .endMetadata()
                .withNewSpec()
                .withType("anycast")
                .withAddress("anycastMultipleReceivers")
                .withPlan(DestinationPlan.STANDARD_SMALL_ANYCAST)
                .endSpec()
                .build();
        resourceManager.setAddresses(dest);

        resourceManager.createOrUpdateUser(addressSpace, userCredentials);
        resourceManager.initFactories(addressSpace, userCredentials);

        AmqpClient client1 = resourceManager.getAmqpClientFactory().createQueueClient();
        AmqpClient client2 = resourceManager.getAmqpClientFactory().createQueueClient();
        AmqpClient client3 = resourceManager.getAmqpClientFactory().createQueueClient();

        runAnycastTest(dest, client1, client2, client3);
    }

    @Test
    void testRestApi() throws Exception {
        Address a1 = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(addressSpace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(addressSpace, "anycastrest"))
                .endMetadata()
                .withNewSpec()
                .withType("anycast")
                .withAddress("anycastrest")
                .withPlan(DestinationPlan.STANDARD_SMALL_ANYCAST)
                .endSpec()
                .build();
        Address a2 = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(addressSpace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(addressSpace, "anycastrest2"))
                .endMetadata()
                .withNewSpec()
                .withType("anycast")
                .withAddress("anycastrest2")
                .withPlan(DestinationPlan.STANDARD_SMALL_ANYCAST)
                .endSpec()
                .build();

        assertAddressApi(addressSpace, a1, a2);
    }

    @Test
    void testScaleRouterAutomatically() throws Exception {
        //deploy addresses
        ArrayList<Address> dest = new ArrayList<>();
        int destCount = 210;
        for (int i = 0; i < destCount; i++) {
            dest.add(new AddressBuilder()
                    .withNewMetadata()
                    .withNamespace(addressSpace.getMetadata().getNamespace())
                    .withName(AddressUtils.generateAddressMetadataName(addressSpace, "medium-anycast" + i))
                    .endMetadata()
                    .withNewSpec()
                    .withType("anycast")
                    .withAddress("medium-anycast" + i)
                    .withPlan(DestinationPlan.STANDARD_MEDIUM_ANYCAST)
                    .endSpec()
                    .build());
        }
        resourceManager.setAddresses(dest.toArray(new Address[0]));

        waitForRouterReplicas(addressSpace, 3);

        resourceManager.createOrUpdateUser(addressSpace, userCredentials);
        resourceManager.initFactories(addressSpace, userCredentials);

        //simple send/receive
        AmqpClient client1 = resourceManager.getAmqpClientFactory().createQueueClient();
        AmqpClient client2 = resourceManager.getAmqpClientFactory().createQueueClient();
        for (int i = 0; i < destCount; i = i + 5) {
            runAnycastTest(dest.get(i), client1, client2);
        }

        //remove part of destinations
        int removeCount = 120;
        resourceManager.deleteAddresses(dest.subList(0, removeCount).toArray(new Address[0]));
        waitForRouterReplicas(addressSpace, 2);

        //simple send/receive
        for (int i = removeCount; i < destCount; i = i + 3) {
            runAnycastTest(dest.get(i), client1, client2);
        }

        //remove all destinations
        resourceManager.setAddresses();
        waitForRouterReplicas(addressSpace, 2);
    }
}
