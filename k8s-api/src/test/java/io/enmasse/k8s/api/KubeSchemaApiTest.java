/*
 * Copyright 2017-2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.k8s.api;

import io.enmasse.address.model.AddressSpaceType;
import io.enmasse.address.model.AddressType;
import io.enmasse.address.model.Phase;
import io.enmasse.address.model.Schema;
import io.enmasse.admin.model.v1.*;
import io.enmasse.config.AnnotationKeys;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;


public class KubeSchemaApiTest {

    private CrdApi<AddressSpacePlan> addressSpacePlanApi;
    private CrdApi<AddressPlan> addressPlanApi;
    private CrdApi<StandardInfraConfig> standardInfraConfigApi;
    private CrdApi<BrokeredInfraConfig> brokeredInfraConfigApi;
    private CrdApi<AuthenticationService> authenticationServiceApi;
    private CrdApi<ConsoleService> consoleServiceApi;
    private AddressType deadletter;

    @BeforeEach
    public void setup() {
        addressSpacePlanApi = mock(CrdApi.class);
        addressPlanApi = mock(CrdApi.class);
        standardInfraConfigApi = mock(CrdApi.class);
        brokeredInfraConfigApi = mock(CrdApi.class);
        authenticationServiceApi = mock(CrdApi.class);
        consoleServiceApi = mock(CrdApi.class);
    }

    @Test
    public void testSchemaAssemble() {
        KubeSchemaApi schemaApi = new KubeSchemaApi(addressSpacePlanApi, addressPlanApi, brokeredInfraConfigApi, standardInfraConfigApi, authenticationServiceApi, "1.0", Clock.systemUTC(), false, true);

        List<AddressSpacePlan> addressSpacePlans = Arrays.asList(
                new AddressSpacePlanBuilder()
                        .withNewMetadata()
                        .withName("spaceplan1")
                        .endMetadata()
                        .editOrNewSpec()
                        .withInfraConfigRef("infra1")
                        .withAddressSpaceType("standard")
                        .withAddressPlans(Arrays.asList("plan1", "plan2", "plan4", "plan5"))
                        .withResourceLimits(Map.of("broker", 1.0, "router", 1.0, "aggregate", 1.0))
                        .endSpec()
                        .build(),
                new AddressSpacePlanBuilder()
                        .withNewMetadata()
                        .withName("spaceplan2")
                        .endMetadata()
                        .editOrNewSpec()
                        .withInfraConfigRef("infra1")
                        .withAddressSpaceType("brokered")
                        .withAddressPlans(Arrays.asList( "plan3"))
                        .withResourceLimits(Map.of("broker", 1.0))
                        .endSpec()
                        .build(),
                new AddressSpacePlanBuilder()
                        .withNewMetadata()
                        .withName("spaceplan3")
                        .endMetadata()
                        .editOrNewSpec()
                        .withInfraConfigRef("infra4")
                        .withAddressSpaceType("brokered")
                        .withAddressPlans(Arrays.asList( "unknown"))
                        .withResourceLimits(Map.of("broker", 1.0))
                        .endSpec()
                        .build(),
                new AddressSpacePlanBuilder()
                        .withNewMetadata()
                        .withName("spaceplan4")
                        .endMetadata()
                        .editOrNewSpec()
                        .withInfraConfigRef("infra1")
                        .withAddressSpaceType("brokered")
                        .withAddressPlans(Arrays.asList( "plan4"))
                        .withResourceLimits(Map.of("broker", 1.0))
                        .endSpec()
                        .build());

        List<AddressPlan> addressPlans = Arrays.asList(
                new AddressPlanBuilder()
                        .withNewMetadata()
                        .withName("plan1")
                        .endMetadata()
                        .editOrNewSpec()
                        .withAddressType("queue")
                        .withResources(Map.of("broker", 0.1, "router", 0.01))
                        .endSpec()
                        .build(),
                new AddressPlanBuilder()
                        .withNewMetadata()
                        .withName("plan2")
                        .endMetadata()
                        .editOrNewSpec()
                        .withAddressType("topic")
                        .withResources(Map.of("broker", 0.1, "router", 0.01))
                        .endSpec()
                        .build(),
                new AddressPlanBuilder()
                        .withMetadata(new ObjectMetaBuilder()
                                .withName("plan4")
                                .build())
                        .editOrNewSpec()
                        .withAddressType("anycast")
                        .withResources(Map.of("broker", 0.1, "router", 0.01))
                        .endSpec()
                        .build(),
                new AddressPlanBuilder()
                        .withNewMetadata()
                        .withName("plan3")
                        .endMetadata()
                        .editOrNewSpec()
                        .withAddressType("queue")
                        .withResources(Map.of("broker", 0.1))
                        .endSpec()
                        .build(),
                new AddressPlanBuilder()
                        .withNewMetadata()
                        .withName("plan5")
                        .endMetadata()
                        .editOrNewSpec()
                        .withAddressType("deadletter")
                        .endSpec()
                        .build());

        List<StandardInfraConfig> standardInfraConfigs = Arrays.asList(
                new StandardInfraConfigBuilder()
                        .withNewMetadata()
                        .withName("infra1")
                        .endMetadata()
                        .build());

        List<BrokeredInfraConfig> brokeredInfraConfigs = Arrays.asList(
                new BrokeredInfraConfigBuilder()
                        .withNewMetadata()
                        .withName("infra1")
                        .endMetadata()
                        .build());

        List<AuthenticationService> authenticationServices = Arrays.asList(
                new AuthenticationServiceBuilder()
                        .withNewMetadata()
                        .withName("standard")
                        .endMetadata()
                        .build());

        Schema schema = schemaApi.assembleSchema(addressSpacePlans, addressPlans, standardInfraConfigs, brokeredInfraConfigs, authenticationServices);

        assertTrue(schema.findAddressSpaceType("standard").isPresent());
        assertTrue(schema.findAddressSpaceType("brokered").isPresent());

        {
            AddressSpaceType type = schema.findAddressSpaceType("standard").get();
            assertTrue(type.findAddressSpacePlan("spaceplan1").isPresent());
            assertFalse(type.findAddressSpacePlan("spaceplan2").isPresent());
            assertTrue(type.findAddressSpacePlan("spaceplan1").get().getAddressPlans().contains("plan1"));
            assertTrue(type.findAddressSpacePlan("spaceplan1").get().getAddressPlans().contains("plan2"));
            assertTrue(type.findAddressSpacePlan("spaceplan1").get().getAddressPlans().contains("plan4"));
            assertTrue(type.findAddressSpacePlan("spaceplan1").get().getAddressPlans().contains("plan5"));
            assertTrue(type.findInfraConfig("infra1").isPresent());

            assertTrue(type.findAddressType("queue").get().findAddressPlan("plan1").isPresent());
            assertTrue(type.findAddressType("topic").get().findAddressPlan("plan2").isPresent());
            assertTrue(type.findAddressType("anycast").get().findAddressPlan("plan4").isPresent());
            assertEquals(Phase.Failed, ((AddressPlan)type.findAddressType("anycast").get().findAddressPlan("plan4").get()).getStatus().getPhase());
            assertEquals(Phase.Active, ((AddressPlan)type.findAddressType("queue").get().findAddressPlan("plan1").get()).getStatus().getPhase());
        }
        {
            AddressSpaceType type = schema.findAddressSpaceType("brokered").get();
            assertTrue(type.findAddressSpacePlan("spaceplan2").isPresent());
            assertFalse(type.findAddressSpacePlan("spaceplan1").isPresent());
            assertTrue(type.findAddressSpacePlan("spaceplan2").get().getAddressPlans().contains("plan3"));
            assertFalse(type.findAddressSpacePlan("spaceplan2").get().getAddressPlans().contains("plan1"));
            assertFalse(type.findAddressSpacePlan("spaceplan2").get().getAddressPlans().contains("plan2"));
            assertTrue(type.findInfraConfig("infra1").isPresent());

            assertTrue(type.findAddressType("queue").get().findAddressPlan("plan3").isPresent());
        }
        {
            assertEquals(Phase.Failed, addressSpacePlans.get(2).getStatus().getPhase());
            System.out.println(addressSpacePlans.get(2).getStatus().getMessage());
            assertTrue(addressSpacePlans.get(2).getStatus().getMessage().contains("missing infra config definition infra4"));
            assertTrue(addressSpacePlans.get(2).getStatus().getMessage().contains("unable to find address plan definition"));
        }
        {
            assertEquals(Phase.Failed, addressPlans.get(2).getStatus().getPhase());
            assertTrue(addressPlans.get(2).getStatus().getMessage().contains("address type anycast not supported by address space type brokered"));
        }

        System.out.println(schema.printSchema());
    }

    @Test
    public void testWatchCreated() throws Exception {
        CrdApi<AddressSpacePlan> addressSpacePlanApi = mock(CrdApi.class);
        CrdApi<AddressPlan> addressPlanApi = mock(CrdApi.class);
        CrdApi<StandardInfraConfig> standardInfraConfigApi = mock(CrdApi.class);
        CrdApi<BrokeredInfraConfig> brokeredInfraConfigApi = mock(CrdApi.class);
        CrdApi<AuthenticationService> authenticationServiceApi = mock(CrdApi.class);
        CrdApi<ConsoleService> consoleServiceApi = mock(CrdApi.class);

        Watch mockWatch = mock(Watch.class);

        when(addressSpacePlanApi.watchResources(any(), any())).thenReturn(mockWatch);
        when(addressPlanApi.watchResources(any(), any())).thenReturn(mockWatch);
        when(brokeredInfraConfigApi.watchResources(any(), any())).thenReturn(mockWatch);
        when(standardInfraConfigApi.watchResources(any(), any())).thenReturn(mockWatch);
        when(authenticationServiceApi.watchResources(any(), any())).thenReturn(mockWatch);

        SchemaApi schemaApi = new KubeSchemaApi(addressSpacePlanApi, addressPlanApi, brokeredInfraConfigApi, standardInfraConfigApi, authenticationServiceApi, "1.0", Clock.systemUTC(), true, false);

        schemaApi.watchSchema(items -> { }, Duration.ofSeconds(5));
        verify(addressSpacePlanApi).watchResources(any(), eq(Duration.ofSeconds(5)));
        verify(addressPlanApi).watchResources(any(), eq(Duration.ofSeconds(5)));
        verify(standardInfraConfigApi).watchResources(any(), eq(Duration.ofSeconds(5)));
        verify(brokeredInfraConfigApi).watchResources(any(), eq(Duration.ofSeconds(5)));
        verify(authenticationServiceApi).watchResources(any(), eq(Duration.ofSeconds(5)));
    }
}
