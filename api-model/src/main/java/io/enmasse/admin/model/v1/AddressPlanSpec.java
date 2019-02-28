/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.admin.model.v1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.fabric8.kubernetes.api.model.Doneable;
import io.sundr.builder.annotations.Buildable;
import io.sundr.builder.annotations.Inline;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = "io.fabric8.kubernetes.api.builder",
        inline = @Inline(type = Doneable.class, prefix = "Doneable", value = "done")
)
@JsonPropertyOrder({"displayName", "shortDescription", "addressType", "resources"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AddressPlanSpec extends AbstractWithAdditionalProperties {
    private String shortDescription;
    private String addressType;
    private Map<String, Double> resources = new HashMap<>();

    public String getShortDescription() {
        return shortDescription;
    }

    public void setShortDescription(String shortDescription) {
        this.shortDescription = shortDescription;
    }

    public String getAddressType() {
        return addressType;
    }

    public void setAddressType(String addressType) {
        this.addressType = addressType;
    }

    public Map<String, Double> getResources() {
        return Collections.unmodifiableMap(resources);
    }

    public void setResources(Map<String, Double> resources) {
        this.resources = new HashMap<>(resources);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AddressPlanSpec that = (AddressPlanSpec) o;
        return Objects.equals(shortDescription, that.shortDescription) &&
                Objects.equals(addressType, that.addressType) &&
                Objects.equals(resources, that.resources);
    }

    @Override
    public int hashCode() {
        return Objects.hash(shortDescription, addressType, resources);
    }

    @Override
    public String toString() {
        return "AddressPlanSpec{" +
                "shortDescription='" + shortDescription + '\'' +
                ", addressType='" + addressType + '\'' +
                ", resources=" + resources +
                '}';
    }
}
