/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.isolated.infra.brokered;

import io.enmasse.address.model.AddressBuilder;
import io.enmasse.address.model.AddressSpaceBuilder;
import io.enmasse.address.model.DoneableAddressSpace;
import io.enmasse.admin.model.v1.AddressSpacePlan;
import io.enmasse.admin.model.v1.AddressSpacePlanBuilder;
import io.enmasse.admin.model.v1.BrokeredInfraConfig;
import io.enmasse.admin.model.v1.BrokeredInfraConfigBuilder;
import io.enmasse.admin.model.v1.BrokeredInfraConfigSpecAdmin;
import io.enmasse.admin.model.v1.BrokeredInfraConfigSpecAdminBuilder;
import io.enmasse.admin.model.v1.BrokeredInfraConfigSpecBroker;
import io.enmasse.admin.model.v1.BrokeredInfraConfigSpecBrokerBuilder;
import io.enmasse.admin.model.v1.ResourceAllowance;
import io.enmasse.admin.model.v1.ResourceRequest;
import io.enmasse.systemtest.bases.infra.InfraTestBase;
import io.enmasse.systemtest.model.address.AddressType;
import io.enmasse.systemtest.model.addressspace.AddressSpaceType;
import io.enmasse.systemtest.time.TimeoutBudget;
import io.enmasse.systemtest.utils.AddressUtils;
import io.enmasse.systemtest.utils.PlanUtils;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.enmasse.systemtest.TestTag.ISOLATED;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag(ISOLATED)
class InfraTestBrokered extends InfraTestBase {

    @Test
    void testCreateInfra() throws Exception {
        PodTemplateSpec brokerTemplateSpec = PlanUtils.createTemplateSpec(Collections.singletonMap("mycomponent", "broker"), "mybrokernode", "broker");
        PodTemplateSpec adminTemplateSpec = PlanUtils.createTemplateSpec(Collections.singletonMap("mycomponent", "admin"), "myadminnode", "admin");
        BrokeredInfraConfig testInfra = new BrokeredInfraConfigBuilder()
                .withNewMetadata()
                .withName("test-infra-3-brokered")
                .endMetadata()
                .withNewSpec()
                .withVersion(environment.enmasseVersion())
                .withBroker(new BrokeredInfraConfigSpecBrokerBuilder()
                        .withAddressFullPolicy("FAIL")
                        .withNewResources()
                        .withMemory("512Mi")
                        .withStorage("1Gi")
                        .endResources()
                        .withJavaOpts("-Dsystemtest=property")
                        .withPodTemplate(brokerTemplateSpec)
                        .build())
                .withAdmin(new BrokeredInfraConfigSpecAdminBuilder()
                        .withNewResources()
                        .withMemory("512Mi")
                        .endResources()
                        .withPodTemplate(adminTemplateSpec)
                        .build())
                .endSpec()
                .build();
        resourceManager.createInfraConfig(testInfra);

        exampleAddressPlan = PlanUtils.createAddressPlanObject("example-queue-plan-brokered", AddressType.QUEUE,
                Collections.singletonList(new ResourceRequest("broker", 1.0)));

        resourceManager.createAddressPlan(exampleAddressPlan);

        AddressSpacePlan exampleSpacePlan = new AddressSpacePlanBuilder()
                .withNewMetadata()
                .withName("example-space-plan-brokered")
                .withNamespace(kubernetes.getInfraNamespace())
                .endMetadata()
                .withNewSpec()
                .withAddressSpaceType(AddressSpaceType.BROKERED.toString())
                .withShortDescription("Custom systemtests defined address space plan")
                .withInfraConfigRef(testInfra.getMetadata().getName())
                .withResourceLimits(Collections.singletonList(new ResourceAllowance("broker", 3.0))
                        .stream().collect(Collectors.toMap(ResourceAllowance::getName, ResourceAllowance::getMax)))
                .withAddressPlans(Collections.singletonList(exampleAddressPlan)
                        .stream().map(addressPlan -> addressPlan.getMetadata().getName()).collect(Collectors.toList()))
                .endSpec()
                .build();
        resourceManager.createAddressSpacePlan(exampleSpacePlan);

        exampleAddressSpace = new AddressSpaceBuilder()
                .withNewMetadata()
                .withName("example-address-space")
                .withNamespace(kubernetes.getInfraNamespace())
                .endMetadata()
                .withNewSpec()
                .withType(AddressSpaceType.BROKERED.toString())
                .withPlan(exampleSpacePlan.getMetadata().getName())
                .withNewAuthenticationService()
                .withName("standard-authservice")
                .endAuthenticationService()
                .endSpec()
                .build();

        resourceManager.createAddressSpace(exampleAddressSpace);

        resourceManager.setAddresses(new AddressBuilder()
                .withNewMetadata()
                .withNamespace(exampleSpacePlan.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(exampleAddressSpace, "example-queue"))
                .endMetadata()
                .withNewSpec()
                .withType(AddressType.QUEUE.toString())
                .withAddress("example-queue")
                .withPlan(exampleAddressPlan.getMetadata().getName())
                .endSpec()
                .build());

        assertInfra("512Mi", "1Gi", brokerTemplateSpec, "512Mi", adminTemplateSpec, "-Dsystemtest=property");
    }

    @Test
    void testIncrementInfra() throws Exception {
        testReplaceInfra("1Gi", "2Gi", "768Mi");
    }

    @Test
    void testDecrementInfra() throws Exception {
        testReplaceInfra("256Mi", "512Mi", "256Mi");
    }

    void testReplaceInfra(String brokerMemory, String brokerStorage, String adminMemory) throws Exception {
        testCreateInfra();

        Boolean updatePersistentVolumeClaim = volumeResizingSupported();

        BrokeredInfraConfig infra = new BrokeredInfraConfigBuilder()
                .withNewMetadata()
                .withName("test-infra-2-brokered")
                .endMetadata()
                .withNewSpec()
                .withVersion(environment.enmasseVersion())
                .withBroker(new BrokeredInfraConfigSpecBrokerBuilder()
                        .withAddressFullPolicy("FAIL")
                        .withUpdatePersistentVolumeClaim(updatePersistentVolumeClaim)
                        .withNewResources()
                        .withMemory(brokerMemory)
                        .withStorage(brokerStorage)
                        .endResources()
                        .build())
                .withAdmin(new BrokeredInfraConfigSpecAdminBuilder()
                        .withNewResources()
                        .withMemory(adminMemory)
                        .endResources()
                        .build())
                .endSpec()
                .build();
        resourceManager.createInfraConfig(infra);


        AddressSpacePlan exampleSpacePlan = new AddressSpacePlanBuilder()
                .withNewMetadata()
                .withName("example-space-plan2-brokered")
                .endMetadata()
                .withNewSpec()
                .withAddressSpaceType(AddressSpaceType.BROKERED.toString())
                .withShortDescription("Custom systemtests defined address space plan")
                .withInfraConfigRef(infra.getMetadata().getName())
                .withResourceLimits(Collections.singletonList(new ResourceAllowance("broker", 3.0))
                        .stream().collect(Collectors.toMap(ResourceAllowance::getName, ResourceAllowance::getMax)))
                .withAddressPlans(Collections.singletonList(exampleAddressPlan)
                        .stream().map(addressPlan -> addressPlan.getMetadata().getName()).collect(Collectors.toList()))
                .endSpec()
                .build();
        resourceManager.createAddressSpacePlan(exampleSpacePlan);

        exampleAddressSpace = new DoneableAddressSpace(exampleAddressSpace).editSpec().withPlan(exampleSpacePlan.getMetadata().getName()).endSpec().done();
        resourceManager.replaceAddressSpace(exampleAddressSpace);

        waitUntilInfraReady(
            () -> assertInfra(brokerMemory, updatePersistentVolumeClaim ? brokerStorage : null, null, adminMemory, null, null),
                new TimeoutBudget(5, TimeUnit.MINUTES));
    }

    @Test
    void testReadInfra() throws Exception {
        BrokeredInfraConfig testInfra = new BrokeredInfraConfigBuilder()
                .withNewMetadata()
                .withName("test-infra-1-brokered")
                .endMetadata()
                .withNewSpec()
                .withVersion(environment.enmasseVersion())
                .withBroker(new BrokeredInfraConfigSpecBrokerBuilder()
                        .withAddressFullPolicy("FAIL")
                        .withNewResources()
                        .withMemory("512Mi")
                        .withStorage("1Gi")
                        .endResources()
                        .build())
                .withAdmin(new BrokeredInfraConfigSpecAdminBuilder()
                        .withNewResources()
                        .withMemory("512Mi")
                        .endResources()
                        .build())
                .endSpec()
                .build();
        resourceManager.createInfraConfig(testInfra);

        BrokeredInfraConfig actualInfra = resourceManager.getBrokeredInfraConfig(testInfra.getMetadata().getName());

        assertEquals(testInfra.getMetadata().getName(), actualInfra.getMetadata().getName());

        BrokeredInfraConfigSpecAdmin expectedAdmin = testInfra.getSpec().getAdmin();
        BrokeredInfraConfigSpecAdmin actualAdmin = actualInfra.getSpec().getAdmin();
        assertEquals(expectedAdmin.getResources().getMemory(), actualAdmin.getResources().getMemory());

        BrokeredInfraConfigSpecBroker expectedBroker = testInfra.getSpec().getBroker();
        BrokeredInfraConfigSpecBroker actualBroker = actualInfra.getSpec().getBroker();
        assertEquals(expectedBroker.getResources().getMemory(), actualBroker.getResources().getMemory());
        assertEquals(expectedBroker.getResources().getStorage(), actualBroker.getResources().getStorage());
        assertEquals(expectedBroker.getAddressFullPolicy(), actualBroker.getAddressFullPolicy());
        assertEquals(expectedBroker.getStorageClassName(), actualBroker.getStorageClassName());

    }

    private boolean assertInfra(String brokerMemory, String brokerStorage, PodTemplateSpec brokerTemplateSpec, String adminMemory, PodTemplateSpec adminTemplateSpec, String javaOpts) {
        assertAdminConsole(adminMemory, adminTemplateSpec);
        assertBroker(brokerMemory, brokerStorage, brokerTemplateSpec, javaOpts);
        return true;
    }

}
