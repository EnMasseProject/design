/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.admin.model.v1;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.fabric8.kubernetes.api.model.Doneable;
import io.sundr.builder.annotations.Buildable;
import io.sundr.builder.annotations.Inline;

@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = "io.fabric8.kubernetes.api.builder",
        inline = @Inline(type = Doneable.class, prefix = "Doneable", value = "done")
)
@JsonPropertyOrder({"resources", "addressFullPolicy", "storageClassName", "updatePersistentVolumeClaim"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StandardInfraConfigSpecBroker extends AbstractWithAdditionalProperties {
    private StandardInfraConfigSpecBrokerResources resources;
    private String addressFullPolicy;
    private String storageClassName;
    private Boolean updatePersistentVolumeClaim;

    public StandardInfraConfigSpecBroker() {
    }

    public StandardInfraConfigSpecBroker(final StandardInfraConfigSpecBrokerResources resources) {
        setResources(resources);
    }

    public void setResources(StandardInfraConfigSpecBrokerResources resources) {
        this.resources = resources;
    }

    public StandardInfraConfigSpecBrokerResources getResources() {
        return resources;
    }

    public void setAddressFullPolicy(String addressFullPolicy) {
        this.addressFullPolicy = addressFullPolicy;
    }

    public String getAddressFullPolicy() {
        return addressFullPolicy;
    }

    public void setStorageClassName(String storageClassName) {
        this.storageClassName = storageClassName;
    }

    public String getStorageClassName() {
        return storageClassName;
    }

    public void setUpdatePersistentVolumeClaim(Boolean updatePersistentVolumeClaim) {
        this.updatePersistentVolumeClaim = updatePersistentVolumeClaim;
    }

    /*
     * NOTE: This is required due to a bug in the builder generator. For a boolean object
     * type it requires an "is" type of the getter. Luckily we can hide this behind the "default"
     * visibility. Also the "is" variant must appear before the "get" variant.
     */
    Boolean isUpdatePersistentVolumeClaim() {
        return updatePersistentVolumeClaim;
    }

    public Boolean getUpdatePersistentVolumeClaim() {
        return updatePersistentVolumeClaim;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StandardInfraConfigSpecBroker that = (StandardInfraConfigSpecBroker) o;
        return Objects.equals(resources, that.resources) &&
                Objects.equals(addressFullPolicy, that.addressFullPolicy) &&
                Objects.equals(storageClassName, that.storageClassName) &&
                Objects.equals(updatePersistentVolumeClaim, that.updatePersistentVolumeClaim);
    }

    @Override
    public int hashCode() {
        return Objects.hash(resources, addressFullPolicy, storageClassName, updatePersistentVolumeClaim);
    }

}
