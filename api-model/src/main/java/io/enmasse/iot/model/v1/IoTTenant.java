/*
 * Copyright 2018-2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.iot.model.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.enmasse.common.model.AbstractHasMetadata;
import io.enmasse.common.model.DefaultCustomResource;
import io.fabric8.kubernetes.api.model.Doneable;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.sundr.builder.annotations.Buildable;
import io.sundr.builder.annotations.BuildableReference;
import io.sundr.builder.annotations.Inline;

@Buildable(
                editableEnabled = false,
                generateBuilderPackage = false,
                builderPackage = "io.fabric8.kubernetes.api.builder",
                refs = {@BuildableReference(AbstractHasMetadata.class)},
                inline = @Inline(
                                type = Doneable.class,
                                prefix = "Doneable",
                                value = "done"))
@DefaultCustomResource
@SuppressWarnings("serial")
@JsonIgnoreProperties(ignoreUnknown = true)
public class IoTTenant extends AbstractHasMetadata<IoTTenant> {

    public static final String KIND = "IoTTenant";

    private IoTTenantSpec spec;
    private IoTTenantStatus status;

    public IoTTenant() {
        super(KIND, IoTCrd.API_VERSION);
    }

    public void setSpec(final IoTTenantSpec spec) {
        this.spec = spec;
    }

    public IoTTenantSpec getSpec() {
        return this.spec;
    }

    public IoTTenantStatus getStatus() {
        return status;
    }

    public void setStatus(IoTTenantStatus status) {
        this.status = status;
    }

    /*
     * Required due to: fabric8io/kubernetes-client#1902
     */
    @Override
    public ObjectMeta getMetadata() {
        return super.getMetadata();
    }
}
