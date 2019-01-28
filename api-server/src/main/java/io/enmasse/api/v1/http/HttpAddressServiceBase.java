/*
 * Copyright 2017-2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.api.v1.http;

import io.enmasse.address.model.Address;
import io.enmasse.address.model.AddressBuilder;
import io.enmasse.address.model.AddressList;
import io.enmasse.api.auth.RbacSecurityContext;
import io.enmasse.api.auth.ResourceVerb;
import io.enmasse.api.common.Exceptions;
import io.enmasse.config.AnnotationKeys;
import io.enmasse.k8s.api.SchemaProvider;
import io.enmasse.api.common.Status;
import io.enmasse.api.v1.AddressApiHelper;
import io.enmasse.config.LabelKeys;
import io.enmasse.k8s.api.AddressSpaceApi;
import io.enmasse.k8s.model.v1beta1.PartialObjectMetadata;
import io.enmasse.k8s.model.v1beta1.Table;
import io.enmasse.k8s.model.v1beta1.TableColumnDefinition;
import io.enmasse.k8s.model.v1beta1.TableRow;
import io.enmasse.k8s.util.TimeUtil;
import io.enmasse.model.validation.DefaultValidator;
import io.fabric8.kubernetes.api.model.ListMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * HTTP API for operating on addresses within an address space.
 * <br>
 * In order to properly work with annotations, this implementation doesn't make use of annotations on its operation
 * methods, and only provides "internal" methods. Concrete implementations, which expose the operation methods via
 * HTTP will declare those methods, with proper annotations and do call the "internal" methods of this class.
 */
public class HttpAddressServiceBase {
    private static final Logger log = LoggerFactory.getLogger(HttpAddressServiceBase.class.getName());
    private final AddressApiHelper apiHelper;
    private final Clock clock;

    public HttpAddressServiceBase(AddressSpaceApi addressSpaceApi, SchemaProvider schemaProvider, Clock clock) {
        this.clock = clock;
        this.apiHelper = new AddressApiHelper(addressSpaceApi, schemaProvider);
    }

    private Response doRequest(String errorMessage, Callable<Response> request) throws Exception {
        try {
            return request.call();
        } catch (Exception e) {
            log.error(errorMessage, e);
            throw e;
        }
    }

    private static void verifyAuthorized(SecurityContext securityContext, String namespace, ResourceVerb verb) {
        if (!securityContext.isUserInRole(RbacSecurityContext.rbacToRole(namespace, verb, "addresses", "enmasse.io"))) {
            throw Exceptions.notAuthorizedException();
        }
    }

    Response internalGetAddressList(SecurityContext securityContext, String acceptHeader, String namespace, String addressSpace, String address, String labelSelector) throws Exception {

        return doRequest("Error listing addresses",() -> {
            verifyAuthorized(securityContext, namespace, ResourceVerb.list);
            if (address == null) {
                if (labelSelector != null) {
                    Map<String, String> labels = AddressApiHelper.parseLabelSelector(labelSelector);
                    AddressList list = apiHelper.getAddressesWithLabels(namespace, addressSpace, labels);
                    return Response.ok(formatResponse(acceptHeader, list)).build();
                } else {
                    AddressList list = apiHelper.getAddresses(namespace, addressSpace);
                    return Response.ok(formatResponse(acceptHeader, list)).build();
                }
            } else {
                AddressList list = apiHelper.getAddresses(namespace, addressSpace);
                for (Address entity : list.getItems()) {
                    if (entity.getSpec().getAddress().equals(address)) {
                        return Response.ok(formatResponse(acceptHeader, entity)).build();
                    }
                }
                return Response.status(404).entity(Status.notFound("Address", address)).build();
            }
        });
    }

    Response internalGetAddress(SecurityContext securityContext, String acceptHeader, String namespace, String addressSpace, String address) throws Exception {
        return doRequest("Error getting address", () -> {
            verifyAuthorized(securityContext, namespace, ResourceVerb.list);
            return apiHelper.getAddress(namespace, addressSpace, address)
                    .map(a -> Response.ok(formatResponse(acceptHeader, a)).build())
                    .orElseGet(() -> Response.status(404).entity(Status.notFound("Address", address)).build());
        });
    }

    Response internalCreateAddress(SecurityContext securityContext, UriInfo uriInfo, String namespace, String addressSpace, Address address) throws Exception {
        checkRequestBodyNotNull(address);
        DefaultValidator.validate(address);
        Address finalAddress = setAddressDefaults(namespace, addressSpace, address, null);
        return doRequest("Error creating address", () -> {
            verifyAuthorized(securityContext, namespace, ResourceVerb.create);
            Address created = apiHelper.createAddress(addressSpace, finalAddress);
            UriBuilder builder = uriInfo.getAbsolutePathBuilder();
            builder.path(created.getMetadata().getName());
            return Response.created(builder.build()).entity(created).build();
        });
    }

    Response internalCreateAddresses(SecurityContext securityContext, UriInfo uriInfo, String namespace, String addressSpace, AddressList addressList) throws Exception {
        checkRequestBodyNotNull(addressList);
        DefaultValidator.validate(addressList);
        Set<Address> finalAddresses = addressList.getItems().stream()
                .map(a -> setAddressDefaults(namespace, addressSpace, a, null))
                .collect(Collectors.toSet());
        return doRequest("Error creating address", () -> {
            verifyAuthorized(securityContext, namespace, ResourceVerb.create);
            apiHelper.createAddresses(addressSpace, finalAddresses);
            return Response.created(uriInfo.getAbsolutePathBuilder().build()).build();
        });
    }

    @SuppressWarnings("deprecation")
    static Address setAddressDefaults(String namespace, String addressSpace, Address address, Address existing) {
        if (existing == null) {
            if (address.getMetadata().getNamespace() == null || address.getMetadata().getName() == null) {
                AddressBuilder builder = new AddressBuilder(address);
                if (address.getMetadata().getNamespace() == null) {
                    builder.editOrNewMetadata().withNamespace(namespace).endMetadata();
                }

                if (address.getMetadata().getName() == null) {
                    builder.editOrNewMetadata().withName(Address.generateName(addressSpace, address.getSpec().getAddress())).endMetadata();
                }

                address = builder.build();
            }

            address.putLabelIfAbsent(LabelKeys.ADDRESS_TYPE, address.getSpec().getType());

        } else {
            validateChanges(existing, address);

            address = new AddressBuilder(existing)

                    .editOrNewMetadata()
                    .addToAnnotations(address.getMetadata().getAnnotations())
                    .addToLabels(address.getMetadata().getLabels())
                    .endMetadata()

                    .editOrNewSpec()
                    .withPlan(address.getSpec().getPlan())
                    .endSpec()

                    .build();
        }

        // validate the addressspace

        if ( address.getSpec().getAddressSpace() != null ) {
            if ( !addressSpace.equals(address.getSpec().getAddressSpace())) {
                throw new BadRequestException(String.format("Address space in spec section does not match address space in request URI"));
            }
        }

        return address;
    }

    private static  <T> boolean hasChanged ( final Address existing, final Address address, final Function<Address, T> extractor ) {

        final T v1 = extractor.apply(existing );
        final T v2 = extractor.apply(address);

        return !Objects.equals(v1,  v2);
    }

    private static void validateChanges(Address existing, Address address) {

        if (hasChanged(existing, address, a -> a.getSpec().getType())) {
            throw new BadRequestException("Cannot change type of address " + address.getMetadata().getName() + " from " + existing.getSpec().getType() + " to " + address.getSpec().getType());
        }

        if (hasChanged(existing, address, a -> a.getSpec().getAddress())) {
            throw new BadRequestException("Cannot change address of address " + address.getMetadata().getName() + " from " + existing.getSpec().getAddress() + " to " + address.getSpec().getAddress());
        }

        if (hasChanged(existing, address, a -> a.getSpec().getTopic())) {
            throw new BadRequestException("Cannot change topic of address " + address.getMetadata().getName() + " from " + existing.getSpec().getTopic() + " to " + address.getSpec().getTopic());
        }

        overrideAnnotation(existing, address, AnnotationKeys.BROKER_ID);
        overrideAnnotation(existing, address, AnnotationKeys.CLUSTER_ID);
        overrideAnnotation(existing, address, AnnotationKeys.APPLIED_PLAN);
        overrideAnnotation(existing, address, AnnotationKeys.INFRA_UUID);
        overrideLabel(existing, address, LabelKeys.ADDRESS_SPACE);
        overrideLabel(existing, address, LabelKeys.NAMESPACE);
    }

    private static void override(Map<String,String> map1, Map<String,String> map2, String key ) {
        if (map1.get(key) == null) {
            return;
        }
        if (!map1.get(key).equals(map2.get(key))) {
            map2.put(key, map1.get(key));
        }
    }

    private static void overrideAnnotation(Address existing, Address address, String annotationKey) {
        override(existing.getMetadata().getAnnotations(), address.getMetadata().getAnnotations(), annotationKey);
    }

    private static void overrideLabel(Address existing, Address address, String labelKey) {
        override(existing.getMetadata().getLabels(), address.getMetadata().getLabels(), labelKey);
    }

    private void checkRequestBodyNotNull(Object object) {
        if (object == null) {
            throw new BadRequestException("Missing request body");
        }
    }

    Response internalReplaceAddress(SecurityContext securityContext, String namespace, String addressSpace, String addressNameFromURL, Address payload) throws Exception {
        checkRequestBodyNotNull(payload);
        checkAddressObjectNameNotNull(payload, addressNameFromURL);
        checkMatchingAddressName(addressNameFromURL, payload);
        DefaultValidator.validate(payload);
        return doRequest("Error updating address", () -> {
            verifyAuthorized(securityContext, namespace, ResourceVerb.update);
            Address existing = apiHelper.getAddress(namespace, addressSpace, addressNameFromURL).orElse(null);
            if (existing == null) {
                return Response.status(404).entity(Status.notFound("Address", addressNameFromURL)).build();
            }
            Address toReplace = setAddressDefaults(namespace, addressSpace, payload, existing);
            Address replaced = apiHelper.replaceAddress(addressSpace, toReplace);
            return Response.ok(replaced).build();
        });
    }

    protected void checkAddressObjectNameNotNull(Address address, String addressNameFromURL) {
        if (address.getMetadata().getName() == null) {
            throw new BadRequestException("the name of the object (" + addressNameFromURL + " based on URL) was undeterminable: name must be provided");
        }
    }

    private void checkMatchingAddressName(String addressNameFromURL, Address addressFromPayload) {
        if (addressFromPayload.getMetadata().getName() != null && !addressFromPayload.getMetadata().getName().equals(addressNameFromURL)) {
            throw new BadRequestException("the name of the object (" + addressFromPayload.getMetadata().getName() + ") does not match the name on the URL (" + addressNameFromURL + ")");
        }
    }

    Response internalDeleteAddress(SecurityContext securityContext, String namespace, String addressSpace, String addressName) throws Exception {
        return doRequest("Error deleting address", () -> {
            verifyAuthorized(securityContext, namespace, ResourceVerb.delete);
            Address address = apiHelper.deleteAddress(namespace, addressSpace, addressName);
            if (address == null) {
                return Response.status(404).entity(Status.notFound("Address", addressName)).build();
            }
            return Response.ok(Status.successStatus(200, "Address", addressName, address.getMetadata().getUid())).build();
        });
    }

    protected Response internalDeleteAddresses(SecurityContext securityContext, String namespace) throws Exception {
        return doRequest("Error deleting addresses", () -> {
            verifyAuthorized(securityContext, namespace, ResourceVerb.delete);
            apiHelper.deleteAddresses(namespace);
            return Response.ok(Status.successStatus(200)).build();
        });
    }

    private Object formatResponse(String acceptHeader, Address address) {
        if (isTableFormat(acceptHeader)) {
            return new Table(new ListMeta(), tableColumnDefinitions, createRows(Collections.singletonList(address)));
        } else {
            return address;
        }
    }

    private Object formatResponse(String acceptHeader, AddressList list) {
        if (isTableFormat(acceptHeader)) {
            return new Table(new ListMeta(), tableColumnDefinitions, createRows(list.getItems()));
        } else {
            return list;
        }
    }

    private boolean isTableFormat(String acceptHeader) {
        return acceptHeader != null && acceptHeader.contains("as=Table") && acceptHeader.contains("g=meta.k8s.io") && acceptHeader.contains("v=v1beta1");
    }

    private static final List<TableColumnDefinition> tableColumnDefinitions = Arrays.asList(
            new TableColumnDefinition("Name must be unique within an address space and namespace.",
                    "name",
                    "Name",
                    0,
                    "string"),
            new TableColumnDefinition("Address of the address resource.",
                    "",
                    "Address",
                    0,
                    "string"),
            new TableColumnDefinition("Address space of the address resource.",
                    "",
                    "AddressSpace",
                    1,
                    "string"),
            new TableColumnDefinition("Type of address",
                    "",
                    "Type",
                    1,
                    "string"),
            new TableColumnDefinition("Plan of address resource",
                    "",
                    "Plan",
                    1,
                    "string"),
            new TableColumnDefinition("The readiness of this address.",
                    "",
                    "Ready",
                    0,
                    "string"),
            new TableColumnDefinition("The phase of this address.",
                    "",
                    "Phase",
                    0,
                    "string"),
            new TableColumnDefinition("The timestamp representing server time when this address was created.",
                    "",
                    "Age",
                    0,
                    "string"),
            new TableColumnDefinition("The status messages reported for the address.",
                    "",
                    "Status",
                    1,
                    "string"));

    private List<TableRow> createRows(List<Address> addressList) {
        Instant now = clock.instant();
        return addressList.stream()
                .map(address -> new TableRow(
                        Arrays.asList(
                                address.getMetadata().getName(),
                                address.getSpec().getAddress(),
                                Address.extractAddressSpace(address),
                                address.getSpec().getType(),
                                address.getSpec().getPlan(),
                                address.getStatus().isReady(),
                                address.getStatus().getPhase(),
                                Optional.ofNullable(address.getMetadata().getCreationTimestamp())
                                        .map(s -> TimeUtil.formatHumanReadable(Duration.between(TimeUtil.parseRfc3339(s), now)))
                                        .orElse(""),
                                String.join(". ", address.getStatus().getMessages())),
                        new PartialObjectMetadata(new ObjectMetaBuilder()
                                .withNamespace(address.getMetadata().getNamespace())
                                .withName(address.getMetadata().getName())
                                .withLabels(address.getMetadata().getLabels())
                                .withAnnotations(address.getMetadata().getAnnotations())
                                .withCreationTimestamp(address.getMetadata().getCreationTimestamp())
                                .withSelfLink(address.getMetadata().getSelfLink())
                                .withUid(address.getMetadata().getUid())
                                .withResourceVersion(address.getMetadata().getResourceVersion())
                                .build())))
                .collect(Collectors.toList());
    }
}
