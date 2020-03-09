/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package iotconfig

import (
	"context"
	"fmt"

	"k8s.io/apimachinery/pkg/util/intstr"

	"github.com/enmasseproject/enmasse/pkg/util/cchange"

	"sigs.k8s.io/controller-runtime/pkg/client"

	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"

	iotv1alpha1 "github.com/enmasseproject/enmasse/pkg/apis/iot/v1alpha1"
	"github.com/enmasseproject/enmasse/pkg/util/install"
	routev1 "github.com/openshift/api/route/v1"
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
)

// This sets the default Hono probes
func SetHonoProbes(container *corev1.Container) {

	container.ReadinessProbe = install.ApplyHttpProbe(container.ReadinessProbe, 10, "/readiness", 8088)
	container.LivenessProbe = install.ApplyHttpProbe(container.LivenessProbe, 10, "/liveness", 8088)

}

func FullHostNameForEnvVar(serviceName string) string {
	return serviceName + ".$(KUBERNETES_NAMESPACE).svc"
}

// Append a string to the value of an env-var. If the env-var doesn't exist, it will be created with the provided value.
// A whitespace is added between the existing value and the new value.
func AppendEnvVarValue(container *corev1.Container, name string, value string) {
	if container.Env == nil {
		container.Env = make([]corev1.EnvVar, 0)
	}

	opts := ""

	for _, env := range container.Env {
		if env.Name == name {
			opts = env.Value
		}
	}

	if len(opts) > 0 {
		opts += " "
	}

	opts += value

	container.Env = append(container.Env, corev1.EnvVar{
		Name:  name,
		Value: opts,
	})
}

// block injection of sidecar variables, for containers not using jaeger
func BlockTracingSidecarConfig(config *iotv1alpha1.IoTConfig, container *corev1.Container) {

	if config.Spec.Tracing.Strategy.Sidecar != nil || config.Spec.Tracing.Strategy.DaemonSet != nil {

		install.ApplyEnvSimple(container, "JAEGER_SERVICE_NAME", "")
		install.ApplyEnvSimple(container, "JAEGER_PROPAGATION", "")

	} else {

		install.RemoveEnv(container, "JAEGER_SERVICE_NAME")
		install.RemoveEnv(container, "JAEGER_PROPAGATION")

	}

}

// setup tracing for a container
func SetupTracing(config *iotv1alpha1.IoTConfig, deployment *appsv1.Deployment, container *corev1.Container) {

	if config.Spec.Tracing.Strategy.Sidecar != nil {

		// sidecar

		install.ApplyEnvSimple(container, "JAEGER_SERVICE_NAME", deployment.Name)
		install.ApplyEnvSimple(container, "JAEGER_PROPAGATION", "jaeger,b3")
		install.ApplyEnvSimple(container, "JAEGER_AGENT_HOST", "localhost")

	} else if config.Spec.Tracing.Strategy.DaemonSet != nil {

		// daemon set

		install.ApplyEnvSimple(container, "JAEGER_SERVICE_NAME", deployment.Name)
		install.ApplyEnvSimple(container, "JAEGER_PROPAGATION", "jaeger,b3")
		install.ApplyEnv(container, "JAEGER_AGENT_HOST", func(envvar *corev1.EnvVar) {
			envvar.Value = ""
			envvar.ValueFrom = &corev1.EnvVarSource{
				FieldRef: &corev1.ObjectFieldSelector{
					FieldPath: "status.hostIP",
				},
			}
		})

	} else {

		// disabled

		install.RemoveEnv(container, "JAEGER_AGENT_HOST")
		install.RemoveEnv(container, "JAEGER_SERVICE_NAME")
		install.RemoveEnv(container, "JAEGER_PROPAGATION")

	}

	if config.Spec.Tracing.Strategy.Sidecar != nil {

		if deployment.Annotations["sidecar.jaegertracing.io/inject"] == "" {
			// we only set this to true when unset, because the tracing operator
			// will replace this with the actual tracing instance
			deployment.Annotations["sidecar.jaegertracing.io/inject"] = "true"
		}

	} else {

		delete(deployment.Labels, "sidecar.jaegertracing.io/injected")
		delete(deployment.Annotations, "sidecar.jaegertracing.io/inject")

		for i, c := range deployment.Spec.Template.Spec.Containers {
			if c.Name == "jaeger-agent" {
				log.Info(fmt.Sprintf("Removing jaeger tracing sidecar from deployment: %s", deployment.Name))
				deployment.Spec.Template.Spec.Containers = append(deployment.Spec.Template.Spec.Containers[:i], deployment.Spec.Template.Spec.Containers[i+1:]...)
				break
			}
		}

	}

}

func AppendStandardHonoJavaOptions(container *corev1.Container) {

	AppendEnvVarValue(
		container,
		"JAVA_APP_OPTS",
		"-Djava.net.preferIPv4Stack=true -Dvertx.logger-delegate-factory-class-name=io.vertx.core.logging.SLF4JLogDelegateFactory",
	)

}

func applyDefaultDeploymentConfig(deployment *appsv1.Deployment, serviceConfig iotv1alpha1.ServiceConfig, configCtx *cchange.ConfigChangeRecorder) {
	deployment.Spec.Replicas = serviceConfig.Replicas
	deployment.Spec.Strategy.Type = appsv1.RollingUpdateDeploymentStrategyType
	if configCtx != nil {
		deployment.Spec.Template.Annotations["iot.enmasse.io/config-hash"] = configCtx.HashString()
	} else {
		delete(deployment.Spec.Template.Annotations, "iot.enmasse.io/config-hash")
	}
}

func applyContainerConfig(container *corev1.Container, config *iotv1alpha1.ContainerConfig) {

	if config == nil {
		return
	}

	if config.Resources != nil {
		container.Resources = *config.Resources
	}

}

func (r *ReconcileIoTConfig) cleanupSecrets(ctx context.Context, config *iotv1alpha1.IoTConfig, adapterName string) error {

	// we need to use an unstructured list, as "SecretList" doesn't work
	// due to kubernetes-sigs/controller-runtime#362

	ul := unstructured.UnstructuredList{}
	ul.SetKind("SecretList")
	ul.SetAPIVersion("")

	ls, err := install.LabelSelectorFromMap(install.CreateDefaultLabels(nil, "iot", adapterName+"-tls"))
	if err != nil {
		return err
	}

	n, err := install.BulkRemoveOwner(ctx, r.client, config, true, &ul, client.ListOptions{
		Namespace:     config.GetNamespace(),
		LabelSelector: ls,
	})

	if err == nil {
		log.Info("cleaned up adapter secrets", "adapter", adapterName, "secretsDeleted", n)
	}

	return err
}

func deviceRegistryImplementation(config *iotv1alpha1.IoTConfig) DeviceRegistryImplementation {

	var file = config.Spec.ServicesConfig.DeviceRegistry.File
	var infinispan = config.Spec.ServicesConfig.DeviceRegistry.Infinispan

	if infinispan != nil && file == nil {
		return DeviceRegistryInfinispan
	} else if infinispan == nil && file != nil {
		return DeviceRegistryFileBased
	} else {
		return DeviceRegistryIllegal
	}
}

func updateEndpointStatus(protocol string, forcePort bool, service *routev1.Route, status *iotv1alpha1.EndpointStatus) {

	status.URI = ""

	if service.Spec.Host == "" {
		return
	}

	status.URI = protocol + "://" + service.Spec.Host

	if forcePort {
		status.URI += ":443"
	}

}

// Append the standard Hono ports
func appendHonoStandardPorts(ports []corev1.ContainerPort) []corev1.ContainerPort {
	if ports == nil {
		ports = make([]corev1.ContainerPort, 0)
	}
	ports = append(ports, corev1.ContainerPort{
		ContainerPort: 8088,
		Name:          "health",
	})
	return ports
}

func (r *ReconcileIoTConfig) reconcileMetricsService(serviceName string) func(config *iotv1alpha1.IoTConfig, service *corev1.Service) error {
	return func(config *iotv1alpha1.IoTConfig, service *corev1.Service) error {
		return processReconcileMetricsService(config, serviceName, service)
	}
}

// Configure a metrics service for hono standard components.
// Hono exposes metrics on /prometheus on the health endpoint. We create a "<component>-metrics" service and map
// the "prometheus" port form the service to the "health" port of the container. So we can define a "prometheus"
// port on the ServiceMonitor on EnMasse with a custom path of "/prometheus".
func processReconcileMetricsService(config *iotv1alpha1.IoTConfig, serviceName string, service *corev1.Service) error {

	install.ApplyMetricsServiceDefaults(service, "iot", serviceName)

	service.Spec.Ports = []corev1.ServicePort{
		{
			Name:       "prometheus",
			Port:       8088,
			TargetPort: intstr.FromString("health"),
			Protocol:   corev1.ProtocolTCP,
		},
	}

	return nil
}
