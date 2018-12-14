/*
 * Copyright 2017-2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.user.model.v1;

import java.util.Objects;
import java.util.regex.Pattern;

import io.enmasse.common.model.AbstractHasMetadata;
import io.enmasse.common.model.DefaultCustomResource;
import io.fabric8.kubernetes.api.model.Doneable;
import io.sundr.builder.annotations.Buildable;
import io.sundr.builder.annotations.BuildableReference;
import io.sundr.builder.annotations.Inline;

@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = "io.fabric8.kubernetes.api.builder",
        refs= {@BuildableReference(AbstractHasMetadata.class)},
        inline = @Inline(
                type = Doneable.class,
                prefix = "Doneable",
                value = "done"
                )
        )
@DefaultCustomResource
public class User extends AbstractHasMetadata<User> {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z]+([a-z0-9\\-]*[a-z0-9]+|[a-z0-9]*)\\.[a-z0-9]+([a-z0-9@.\\-]*[a-z0-9]+|[a-z0-9]*)$");

    private static final long serialVersionUID = 1L;

    public static final String KIND = "User";
    public static final String VERSION = "v1alpha1";
    public static final String GROUP = "user.enmasse.io";
    public static final String API_VERSION = GROUP +"/" + VERSION;

    public User() {
        super(KIND,API_VERSION);
    }

    private UserSpec spec;

    public void setSpec(final UserSpec spec) {
        this.spec = spec;
    }

    public UserSpec getSpec() {
        return spec;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return Objects.equals(getApiVersion(), user.getApiVersion()) &&
                Objects.equals(getKind(), user.getKind()) &&
                Objects.equals(getMetadata(), user.getMetadata());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getApiVersion(), getKind(), getMetadata(), spec);
    }

    public void validate() {
        try {

            validateMetadata();

            Objects.requireNonNull(spec, "'spec' must be set");
            spec.validate();

        } catch (Exception e) {
            throw new UserValidationFailedException(e);
        }
    }

    private void validateMetadata() {
        Objects.requireNonNull(getMetadata(), "'metadata' must be set");

        final String name = getMetadata().getName();
        final String namespace = getMetadata().getNamespace();

        Objects.requireNonNull(name, "'name' must be set");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new UserValidationFailedException("Invalid resource name '" + name + "', must match " + NAME_PATTERN);
        }
        Objects.requireNonNull(namespace, "'namespace' must be set");

    }
}
