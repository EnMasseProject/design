/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.annotations;

import io.enmasse.systemtest.EnmasseInstallType;
import io.enmasse.systemtest.Environment;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.Optional;

import static org.junit.platform.commons.support.AnnotationSupport.findAnnotation;

public class SupportedInstallTypeCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<SupportedInstallType> annotation = findAnnotation(context.getRequiredTestClass(), SupportedInstallType.class);
        if (annotation.isPresent()) {
            SupportedInstallType supports = annotation.get();
            io.enmasse.systemtest.platform.Kubernetes kube = io.enmasse.systemtest.platform.Kubernetes.getInstance();
            Environment env = Environment.getInstance();
            String reason = String.format("Env is supported types %s type used %s olmAvailability %s",
                    supports.value().toString(), env.installType(), kube.isOLMAvailable());
            if (isTestEnabled(supports, kube.isOLMAvailable(), env.installType())) {
               return ConditionEvaluationResult.enabled(reason);
            } else {
                return ConditionEvaluationResult.disabled(reason);
            }
        }
        return ConditionEvaluationResult.enabled("No rule set, test is enabled");
    }

    public boolean isTestEnabled(SupportedInstallType supports, boolean olmAvailable, EnmasseInstallType envInstallType) {
        EnmasseInstallType supportedInstallType = supports.value();
        if (supportedInstallType == EnmasseInstallType.OLM) {
            return olmAvailable;
        }
        return envInstallType == supportedInstallType;
    }

}
