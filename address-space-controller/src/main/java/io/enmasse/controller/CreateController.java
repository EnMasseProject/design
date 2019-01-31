/*
 * Copyright 2017-2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.enmasse.address.model.*;
import io.enmasse.admin.model.v1.*;
import io.enmasse.config.AnnotationKeys;
import io.enmasse.controller.common.ControllerKind;
import io.enmasse.controller.common.Kubernetes;
import io.enmasse.k8s.api.AddressApi;
import io.enmasse.k8s.api.AddressSpaceApi;
import io.enmasse.k8s.api.EventLogger;
import io.enmasse.k8s.api.SchemaProvider;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesList;
import io.fabric8.kubernetes.api.model.KubernetesListBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static io.enmasse.controller.InfraConfigs.parseCurrentInfraConfig;
import static io.enmasse.controller.common.ControllerReason.AddressSpaceChanged;
import static io.enmasse.controller.common.ControllerReason.AddressSpaceCreated;
import static io.enmasse.controller.common.ControllerReason.AddressSpaceUpgraded;
import static io.enmasse.k8s.api.EventLogger.Type.Normal;

public class CreateController implements Controller {
    private static final Logger log = LoggerFactory.getLogger(CreateController.class.getName());

    private static final ObjectMapper mapper = new ObjectMapper();
    private final Kubernetes kubernetes;
    private final SchemaProvider schemaProvider;
    private final InfraResourceFactory infraResourceFactory;
    private final EventLogger eventLogger;
    private final String defaultCertProvider;
    private final String version;
    private final AddressSpaceApi addressSpaceApi;

    public CreateController(Kubernetes kubernetes, SchemaProvider schemaProvider, InfraResourceFactory infraResourceFactory, EventLogger eventLogger, String defaultCertProvider, String version, AddressSpaceApi addressSpaceApi) {
        this.kubernetes = kubernetes;
        this.schemaProvider = schemaProvider;
        this.infraResourceFactory = infraResourceFactory;
        this.eventLogger = eventLogger;
        this.defaultCertProvider = defaultCertProvider;
        this.version = version;
        this.addressSpaceApi = addressSpaceApi;
    }

    private static List<EndpointSpec> validateEndpoints(AddressSpaceResolver addressSpaceResolver, AddressSpace addressSpace) {
        // Set default endpoints from type
        AddressSpaceType addressSpaceType = addressSpaceResolver.getType(addressSpace.getSpec().getType());
        if (addressSpace.getSpec().getEndpoints().isEmpty()) {
            return addressSpaceType.getAvailableEndpoints();
        } else {
            // Validate endpoints;
            List<EndpointSpec> endpoints = addressSpace.getSpec().getEndpoints();
            Set<String> services = addressSpaceType.getAvailableEndpoints().stream()
                    .map(EndpointSpec::getService)
                    .collect(Collectors.toSet());
            Set<String> actualServices = endpoints.stream()
                    .map(EndpointSpec::getService)
                    .collect(Collectors.toSet());

            services.removeAll(actualServices);
            if (!services.isEmpty()) {
                log.warn("Endpoint list is missing reference to services: {}", services);
                throw new IllegalArgumentException("Endpoint list is missing reference to services: " + services);
            }
            return endpoints;
        }
    }

    @Override
    public AddressSpace handle(AddressSpace addressSpace) throws Exception {
        Schema schema = schemaProvider.getSchema();
        AddressSpaceResolver addressSpaceResolver = new AddressSpaceResolver(schema);
        addressSpaceResolver.validate(addressSpace);

        List<EndpointSpec> endpoints = validateEndpoints(addressSpaceResolver, addressSpace);

        // Ensure the required certs are set
        List<EndpointSpec> newEndpoints = new ArrayList<>();
        for (EndpointSpec endpoint : endpoints) {
            EndpointSpecBuilder endpointBuilder = new EndpointSpecBuilder(endpoint);

            CertSpecBuilder certSpec = endpoint.getCert() != null ? new CertSpecBuilder(endpoint.getCert()) : new CertSpecBuilder();
            if (certSpec.getProvider() == null) {
                certSpec.withProvider(defaultCertProvider);
            }

            if (certSpec.getSecretName() == null) {
                certSpec.withSecretName(KubeUtil.getExternalCertSecretName(endpoint.getService(), addressSpace));
            }

            endpointBuilder.withCert(certSpec.build());
            newEndpoints.add(endpointBuilder.build());
        }
        addressSpace = new AddressSpaceBuilder(addressSpace)
                .editOrNewSpec()
                .withEndpoints(newEndpoints)
                .endSpec()
                .build();

        AddressSpaceType addressSpaceType = addressSpaceResolver.getType(addressSpace.getSpec().getType());
        AddressSpacePlan addressSpacePlan = addressSpaceResolver.getPlan(addressSpaceType, addressSpace.getSpec().getPlan());

        InfraConfig desiredInfraConfig = getInfraConfig(addressSpace);
        InfraConfig currentInfraConfig = parseCurrentInfraConfig(schemaProvider.getSchema(), addressSpace);
        if (currentInfraConfig == null && !kubernetes.existsAddressSpace(addressSpace)) {
            KubernetesList resourceList = new KubernetesListBuilder()
                    .addAllToItems(infraResourceFactory.createInfraResources(addressSpace, desiredInfraConfig))
                    .build();
            addAppliedInfraConfigAnnotation(resourceList, desiredInfraConfig);

            log.info("Creating address space {}", addressSpace);

            kubernetes.create(resourceList);
            eventLogger.log(AddressSpaceCreated, "Created address space", Normal, ControllerKind.AddressSpace, addressSpace.getMetadata().getName());
            addressSpace.putAnnotation(AnnotationKeys.APPLIED_INFRA_CONFIG, mapper.writeValueAsString(desiredInfraConfig));
            addressSpace.putAnnotation(AnnotationKeys.APPLIED_PLAN, addressSpace.getSpec().getPlan());
        } else if (currentInfraConfig == null || !currentInfraConfig.equals(desiredInfraConfig)) {

            if (version.equals(desiredInfraConfig.getVersion())) {
                if (checkExceedsQuota(addressSpaceType, addressSpacePlan, addressSpace)) {
                    return addressSpace;
                }
                KubernetesList resourceList = new KubernetesListBuilder()
                        .addAllToItems(infraResourceFactory.createInfraResources(addressSpace, desiredInfraConfig))
                        .build();
                addAppliedInfraConfigAnnotation(resourceList, desiredInfraConfig);

                log.info("Upgrading address space {}", addressSpace);

                kubernetes.apply(resourceList,desiredInfraConfig.getUpdatePersistentVolumeClaim());
                eventLogger.log(AddressSpaceUpgraded, "Upgraded address space", Normal, ControllerKind.AddressSpace, addressSpace.getMetadata().getName());
                addressSpace.putAnnotation(AnnotationKeys.APPLIED_INFRA_CONFIG, mapper.writeValueAsString(desiredInfraConfig));
                addressSpace.putAnnotation(AnnotationKeys.APPLIED_PLAN, addressSpace.getSpec().getPlan());
            } else {
                log.info("Version of desired config ({}) does not match controller version ({}), skipping upgrade", desiredInfraConfig.getVersion(), version);
            }
        } else if (!addressSpace.getSpec().getPlan().equals(addressSpace.getAnnotation(AnnotationKeys.APPLIED_PLAN))) {
            if (checkExceedsQuota(addressSpaceType, addressSpacePlan, addressSpace)) {
                return addressSpace;
            }

            KubernetesList resourceList = new KubernetesListBuilder()
                    .addAllToItems(infraResourceFactory.createInfraResources(addressSpace, desiredInfraConfig))
                    .build();
            addAppliedInfraConfigAnnotation(resourceList, desiredInfraConfig);

            log.info("Updating address space plan {}", addressSpace);

            kubernetes.apply(resourceList, desiredInfraConfig.getUpdatePersistentVolumeClaim());
            eventLogger.log(AddressSpaceChanged, "Changed address space plan", Normal, ControllerKind.AddressSpace, addressSpace.getMetadata().getName());
            addressSpace.putAnnotation(AnnotationKeys.APPLIED_INFRA_CONFIG, mapper.writeValueAsString(desiredInfraConfig));
            addressSpace.putAnnotation(AnnotationKeys.APPLIED_PLAN, addressSpace.getSpec().getPlan());
        }

        return addressSpace;
    }

    private boolean checkExceedsQuota(AddressSpaceType addressSpaceType, AddressSpacePlan plan, AddressSpace addressSpace) {
        AddressApi addressApi = addressSpaceApi.withAddressSpace(addressSpace);
        Set<Address> addresses = addressApi.listAddresses(addressSpace.getMetadata().getNamespace()).stream()
                .filter(address -> addressSpace.getMetadata().getName().equals(Address.extractAddressSpace(address)))
                .collect(Collectors.toSet());

        Map<String, Double> quota = new HashMap<>();
        Map<String, Double> usage = new HashMap<>();
        for (ResourceAllowance allowance : plan.getResources()) {
            quota.put(allowance.getName(), allowance.getMax());
        }

        AddressResolver addressResolver = new AddressResolver(addressSpaceType);
        for (Address address : addresses) {
            AddressPlan addressPlan = addressResolver.getPlan(address);
            for (ResourceRequest resourceRequest : addressPlan.getRequiredResources()) {
                usage.compute(resourceRequest.getName(), (s, old) -> {
                    if (old == null) {
                        return resourceRequest.getCredit();
                    } else {
                        return old + resourceRequest.getCredit();
                    }
                });
                usage.compute("aggregate", (s, old) -> {
                    if (old == null) {
                        return resourceRequest.getCredit();
                    } else {
                        return old + resourceRequest.getCredit();
                    }
                });
            }
        }

        boolean exceedsQuota = false;
        for (Map.Entry<String, Double> usageEntry : usage.entrySet()) {
            Double quotaValue = quota.get(usageEntry.getKey());
            if (quotaValue != null && usageEntry.getValue() > quotaValue) {
                addressSpace.getStatus().appendMessage(String.format("Unable to apply plan %s to address space %s:%s: quota exceeded for resource %s", plan.getAddressPlans(), addressSpace.getMetadata().getNamespace(), addressSpace.getMetadata().getName(), usageEntry.getKey()));
                exceedsQuota = true;
            }
        }
        return exceedsQuota;
    }

    private void addAppliedInfraConfigAnnotation(KubernetesList resourceList, InfraConfig infraConfig) throws JsonProcessingException {
        for (HasMetadata item : resourceList.getItems()) {
            if (item instanceof StatefulSet) {
                Kubernetes.addObjectAnnotation(item, AnnotationKeys.APPLIED_INFRA_CONFIG, mapper.writeValueAsString(infraConfig));
            }
        }
    }

    private InfraConfig getInfraConfig(AddressSpace addressSpace) {
        AddressSpaceResolver addressSpaceResolver = new AddressSpaceResolver(schemaProvider.getSchema());
        return addressSpaceResolver.getInfraConfig(addressSpace.getSpec().getType(), addressSpace.getSpec().getPlan());
    }

    @Override
    public String toString() {
        return "CreateController";
    }
}
