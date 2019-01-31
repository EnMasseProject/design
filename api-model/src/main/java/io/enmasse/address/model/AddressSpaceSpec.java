/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.address.model;

import java.util.Collections;
import java.util.List;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.enmasse.admin.model.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.Doneable;
import io.sundr.builder.annotations.Buildable;
import io.sundr.builder.annotations.Inline;

@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = "io.fabric8.kubernetes.api.builder",
        inline = @Inline(
                type = Doneable.class,
                prefix = "Doneable",
                value = "done"
                )
        )
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AddressSpaceSpec {

    @NotEmpty
    private String type;
    private List<@Valid EndpointSpec> endpoints = Collections.emptyList();
    @Valid
    private NetworkPolicy networkPolicy;
    @NotEmpty
    private String plan;
    @Valid
    private AuthenticationService authenticationService = new AuthenticationService();

    public AddressSpaceSpec() {
    }

    public void setEndpoints(List<EndpointSpec> endpointList) {
        this.endpoints = endpointList;
    }

    public List<EndpointSpec> getEndpoints() {
        return Collections.unmodifiableList(endpoints);
    }

    public void setType(String typeName) {
        this.type = typeName;
    }

    public String getType() {
        return type;
    }

    public void setPlan(String plan) {
        this.plan = plan;
    }

    public String getPlan() {
        return plan;
    }

    public void setNetworkPolicy(NetworkPolicy networkPolicy) {
        this.networkPolicy = networkPolicy;
    }

    public NetworkPolicy getNetworkPolicy() {
        return networkPolicy;
    }

    public void setAuthenticationService(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    public AuthenticationService getAuthenticationService() {
        return authenticationService;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("{");

        sb
                .append("type=").append(type).append(",")
                .append("plan=").append(plan).append(",")
                .append("endpoints=").append(endpoints).append(",")
                .append("networkPolicy=").append(networkPolicy);

        return sb.toString();
    }

}
