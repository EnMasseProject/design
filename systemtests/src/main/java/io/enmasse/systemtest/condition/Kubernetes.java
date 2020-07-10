/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.condition;

import io.enmasse.systemtest.platform.cluster.ClusterType;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(AssumeKubernetesCondition.class)
public @interface Kubernetes {
    ClusterType type() default ClusterType.WHATEVER;
    MultinodeCluster multinode() default MultinodeCluster.WHATEVER;
}
