/*
 * Copyright 2019-2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package iotconfig

import (
	"context"
	"strconv"

	"github.com/enmasseproject/enmasse/pkg/util"
	"github.com/enmasseproject/enmasse/pkg/util/recon"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	"github.com/enmasseproject/enmasse/pkg/util/install"
	appsv1 "k8s.io/api/apps/v1"
	"k8s.io/api/core/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"

	iotv1alpha1 "github.com/enmasseproject/enmasse/pkg/apis/iot/v1alpha1"
)

func (r *ReconcileIoTConfig) processInfinispanDeviceRegistry(ctx context.Context, config *iotv1alpha1.IoTConfig) (reconcile.Result, error) {

	rc := &recon.ReconcileContext{}

	rc.ProcessSimple(func() error {
		return r.processConfigMap(ctx, nameDeviceRegistry+"-config", config, false, r.reconcileInfinispanDeviceRegistryConfigMap)
	})
	rc.ProcessSimple(func() error {
		return r.processDeployment(ctx, nameDeviceRegistry, config, false, r.reconcileInfinispanDeviceRegistryDeployment)
	})

	return rc.Result()
}

func (r *ReconcileIoTConfig) reconcileInfinispanDeviceRegistryDeployment(config *iotv1alpha1.IoTConfig, deployment *appsv1.Deployment) error {

	install.ApplyDeploymentDefaults(deployment, "iot", deployment.Name)
	deployment.Annotations[RegistryTypeAnnotation] = "infinispan"
	deployment.Annotations[util.ConnectsTo] = "iot-auth-service"
	deployment.Spec.Template.Spec.ServiceAccountName = "iot-device-registry"
	deployment.Spec.Template.Annotations[RegistryTypeAnnotation] = "infinispan"

	deployment.Spec.Template.Labels[RegistryAdapterFeatureLabel] = "true"
	deployment.Spec.Template.Labels[RegistryManagementFeatureLabel] = "true"

	service := config.Spec.ServicesConfig.DeviceRegistry
	applyDefaultDeploymentConfig(deployment, service.Infinispan.ServiceConfig, nil)

	var tracingContainer *corev1.Container
	err := install.ApplyDeploymentContainerWithError(deployment, "device-registry", func(container *corev1.Container) error {

		tracingContainer = container

		if err := install.SetContainerImage(container, "iot-device-registry-infinispan", config); err != nil {
			return err
		}

		container.Args = nil

		// set default resource limits

		container.Resources = corev1.ResourceRequirements{
			Limits: corev1.ResourceList{
				corev1.ResourceMemory: *resource.NewQuantity(512*1024*1024 /* 512Mi */, resource.BinarySI),
			},
		}

		container.Ports = []corev1.ContainerPort{
			{Name: "amqps", ContainerPort: 5671, Protocol: corev1.ProtocolTCP},
			{Name: "http", ContainerPort: 8080, Protocol: corev1.ProtocolTCP},
			{Name: "https", ContainerPort: 8443, Protocol: corev1.ProtocolTCP},
		}

		container.Ports = appendHonoStandardPorts(container.Ports)
		SetHonoProbes(container)

		// eval native TLS flag

		var nativeTls bool
		if service.Infinispan != nil {
			nativeTls = service.Infinispan.IsNativeTlsRequired(config)
		} else {
			nativeTls = false
		}

		// environment

		container.Env = []corev1.EnvVar{
			{Name: "SPRING_CONFIG_LOCATION", Value: "file:///etc/config/"},
			{Name: "SPRING_PROFILES_ACTIVE", Value: "device-registry"},
			{Name: "LOGGING_CONFIG", Value: "file:///etc/config/logback-spring.xml"},
			{Name: "KUBERNETES_NAMESPACE", ValueFrom: &corev1.EnvVarSource{FieldRef: &corev1.ObjectFieldSelector{FieldPath: "metadata.namespace"}}},

			{Name: "HONO_AUTH_HOST", Value: FullHostNameForEnvVar("iot-auth-service")},
			{Name: "HONO_AUTH_VALIDATION_SHARED_SECRET", Value: *config.Status.AuthenticationServicePSK},

			{Name: "ENMASSE_IOT_AMQP_NATIVE_TLS_REQUIRED", Value: strconv.FormatBool(nativeTls)},

			{Name: "ENMASSE_IOT_REST_NATIVE_TLS_REQUIRED", Value: strconv.FormatBool(nativeTls)},
			{Name: "ENMASSE_IOT_REST_AUTH_TOKEN_CACHE_EXPIRATION", Value: service.Infinispan.Management.AuthTokenCacheExpiration},
		}

		SetupTracing(config, deployment, container)
		AppendStandardHonoJavaOptions(container)

		// append trust stores

		if err := AppendTrustStores(config, container, []string{"HONO_AUTH_TRUST_STORE_PATH"}); err != nil {
			return err
		}

		// volume mounts

		install.ApplyVolumeMountSimple(container, "config", "/etc/config", true)
		install.ApplyVolumeMountSimple(container, "tls", "/etc/tls-internal", true)
		install.ApplyVolumeMountSimple(container, "tls-endpoint", "/etc/tls-external", true)
		install.DropVolumeMount(container, "registry")

		// apply container options

		if service.Infinispan != nil {
			applyContainerConfig(container, service.Infinispan.Container)
		}

		// apply infinispan server options

		if service.Infinispan.Server.External != nil {
			if err := appendInfinispanExternalServer(container, config.Spec.ServicesConfig.DeviceRegistry.Infinispan.Server.External); err != nil {
				return err
			}
		} else {
			return util.NewConfigurationError("infinispan backend server configuration missing")
		}

		// return

		return nil
	})

	if err != nil {
		return err
	}

	// reset init containers

	deployment.Spec.Template.Spec.InitContainers = nil

	// tracing

	SetupTracing(config, deployment, tracingContainer)

	// volumes

	install.ApplyConfigMapVolume(&deployment.Spec.Template.Spec, "config", nameDeviceRegistry+"-config")
	install.DropVolume(&deployment.Spec.Template.Spec, "registry")

	// inter service secrets

	if err := ApplyInterServiceForDeployment(r.client, config, deployment, nameDeviceRegistry); err != nil {
		return err
	}

	// endpoint

	if err := applyEndpointDeployment(r.client, service.Management.Endpoint, deployment, nameDeviceRegistry, "tls-endpoint"); err != nil {
		return err
	}

	// return

	return nil
}

func appendInfinispanExternalServer(container *v1.Container, external *iotv1alpha1.ExternalInfinispanRegistryServer) error {

	// basic connection

	install.ApplyEnvSimple(container, "ENMASSE_IOT_REGISTRY_INFINISPAN_HOST", external.Host)
	install.ApplyEnvSimple(container, "ENMASSE_IOT_REGISTRY_INFINISPAN_PORT", strconv.Itoa(int(external.Port)))
	install.ApplyEnvSimple(container, "ENMASSE_IOT_REGISTRY_INFINISPAN_USERNAME", external.Username)
	install.ApplyEnvSimple(container, "ENMASSE_IOT_REGISTRY_INFINISPAN_PASSWORD", external.Password)

	// SASL

	install.ApplyOrRemoveEnvSimple(container, "ENMASSE_IOT_REGISTRY_INFINISPAN_SASLSERVERNAME", external.SaslServerName)
	install.ApplyOrRemoveEnvSimple(container, "ENMASSE_IOT_REGISTRY_INFINISPAN_SASLREALM", external.SaslRealm)

	// cache names

	adapterCredentials := ""
	devices := ""
	if external.CacheNames != nil {
		adapterCredentials = external.CacheNames.AdapterCredentials
		devices = external.CacheNames.Devices
	}

	install.ApplyOrRemoveEnvSimple(container, "ENMASSE_IOT_REGISTRY_INFINISPAN_CACHENAMES_ADAPTERCREDENTIALS", adapterCredentials)
	install.ApplyOrRemoveEnvSimple(container, "ENMASSE_IOT_REGISTRY_INFINISPAN_CACHENAMES_DEVICES", devices)

	// done

	return nil
}

func (r *ReconcileIoTConfig) reconcileInfinispanDeviceRegistryConfigMap(_ *iotv1alpha1.IoTConfig, configMap *corev1.ConfigMap) error {

	install.ApplyDefaultLabels(&configMap.ObjectMeta, "iot", configMap.Name)

	if configMap.Data == nil {
		configMap.Data = make(map[string]string)
	}

	if configMap.Data["logback-spring.xml"] == "" {
		configMap.Data["logback-spring.xml"] = DefaultLogbackConfig
	}

	configMap.Data["application.yml"] = `
hono:

  auth:
    port: 5671
    keyPath: /etc/tls/tls.key
    certPath: /etc/tls/tls.crt
    keyFormat: PEM
    trustStorePath: /var/run/secrets/kubernetes.io/serviceaccount/service-ca.crt
    trustStoreFormat: PEM

enmasse:
  iot:

    app:
      maxInstances: 1

    vertx:
      preferNative: true

    healthCheck:
      insecurePortBindAddress: 0.0.0.0
      startupTimeout: 90

    registry:
      ttl: 1m
      maxBcryptIterations: 10

    amqp:
      bindAddress: 0.0.0.0
      keyPath: /etc/tls-internal/tls.key
      certPath: /etc/tls-internal/tls.crt
      keyFormat: PEM

    rest:
      bindAddress: 0.0.0.0
      keyPath: /etc/tls-external/tls.key
      certPath: /etc/tls-external/tls.crt
      keyFormat: PEM
`
	return nil
}
