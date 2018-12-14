/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.common.model;

import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinitionBuilder;

public final class CustomResources {

    private CustomResources() {}

    public static CustomResourceDefinition createCustomResource(final String group, final String version, final String kind) {
        String singular = kind.toLowerCase();
        String listKind = kind + "List";
        String plural = singular + "s";
        return new CustomResourceDefinitionBuilder()
                        .editOrNewMetadata()
                        .withName(plural + "." + group)
                        .addToLabels("app", "enmasse")
                        .endMetadata()
                        .editOrNewSpec()
                        .withGroup(group)
                        .withVersion(version)
                        .withScope("Namespaced")
                        .editOrNewNames()
                        .withKind(kind)
                        .withListKind(listKind)
                        .withPlural(plural)
                        .withSingular(singular)
                        .endNames()
                        .endSpec()
                        .build();
    }

}
