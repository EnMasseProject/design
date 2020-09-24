/*
 * Copyright 2016-2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.controller.standard;

import static io.enmasse.address.model.Phase.Active;
import static io.enmasse.address.model.Phase.Configuring;
import static io.enmasse.address.model.Phase.Failed;
import static io.enmasse.address.model.Phase.Pending;
import static io.enmasse.address.model.Phase.Terminating;
import static io.enmasse.controller.standard.ControllerKind.Broker;
import static io.enmasse.controller.standard.ControllerReason.BrokerUpgraded;
import static io.enmasse.k8s.api.EventLogger.Type.Normal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import io.enmasse.address.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.enmasse.admin.model.AddressPlan;
import io.enmasse.admin.model.AddressSpacePlan;
import io.enmasse.admin.model.v1.StandardInfraConfig;
import io.enmasse.admin.model.v1.StandardInfraConfigBuilder;
import io.enmasse.amqp.RouterManagement;
import io.enmasse.config.AnnotationKeys;
import io.enmasse.k8s.api.AddressApi;
import io.enmasse.k8s.api.AddressSpaceApi;
import io.enmasse.k8s.api.EventLogger;
import io.enmasse.k8s.api.ResourceChecker;
import io.enmasse.k8s.api.SchemaProvider;
import io.enmasse.k8s.api.Watch;
import io.enmasse.k8s.api.Watcher;
import io.enmasse.metrics.api.MetricLabel;
import io.enmasse.metrics.api.MetricType;
import io.enmasse.metrics.api.MetricValue;
import io.enmasse.metrics.api.Metrics;
import io.enmasse.metrics.api.ScalarMetric;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetSpec;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.internal.readiness.Readiness;
import io.vertx.core.Vertx;

/**
 * Controller for a single standard address space
 */
public class AddressController implements Watcher<Address> {
    private static final Logger log = LoggerFactory.getLogger(AddressController.class);
    private final StandardControllerOptions options;
    private final AddressSpaceApi addressSpaceApi;
    private final AddressApi addressApi;
    private final Kubernetes kubernetes;
    private final BrokerSetGenerator clusterGenerator;
    private Watch watch;
    private final EventLogger eventLogger;
    private final SchemaProvider schemaProvider;
    private final Vertx vertx;
    private final BrokerIdGenerator brokerIdGenerator;
    private final BrokerStatusCollectorFactory brokerStatusCollectorFactory;
    private final RouterStatusCache statusCollector;
    private final ResourceChecker<Address> reconciler;

    // Metrics
    private volatile Long readyAddressCount;
    private volatile Long notReadyAddressCount;
    private volatile Long readyForwarders;
    private volatile Long notReadyForwarders;
    private volatile Long numAddresses;
    private volatile Long numForwarders;
    private volatile Long totalTime;
    private volatile Map<Phase, Long> countByPhase = new HashMap<>();

    public AddressController(StandardControllerOptions options, AddressSpaceApi addressSpaceApi, AddressApi addressApi, Kubernetes kubernetes, BrokerSetGenerator clusterGenerator, EventLogger eventLogger, SchemaProvider schemaProvider, Vertx vertx, Metrics metrics, BrokerIdGenerator brokerIdGenerator, BrokerStatusCollectorFactory brokerStatusCollectorFactory) {
        this.options = options;
        this.addressSpaceApi = addressSpaceApi;
        this.addressApi = addressApi;
        this.kubernetes = kubernetes;
        this.clusterGenerator = clusterGenerator;
        this.eventLogger = eventLogger;
        this.schemaProvider = schemaProvider;
        this.vertx = vertx;
        this.brokerIdGenerator = brokerIdGenerator;
        this.brokerStatusCollectorFactory = brokerStatusCollectorFactory;
        RouterManagement routerManagement = RouterManagement.withCertsInDir(vertx, "standard-controller", options.getManagementConnectTimeout(), options.getManagementQueryTimeout(), options.getCertDir());
        this.statusCollector = new RouterStatusCache(routerManagement, kubernetes, eventLogger, options.getAddressSpace(), options.getStatusCheckInterval());
        reconciler = new ResourceChecker<>(this, options.getRecheckInterval());
        registerMetrics(metrics);
    }

    private void registerMetrics(Metrics metrics) {
        String componentName = "standard-controller-" + options.getInfraUuid();
        metrics.registerMetric(new ScalarMetric(
                "version",
                "The version of the standard-controller",
                MetricType.gauge,
                () -> Collections.singletonList(new MetricValue(0, new MetricLabel("name", componentName), new MetricLabel("version", options.getVersion())))));

        MetricLabel[] metricLabels = new MetricLabel[]{new MetricLabel("address_space_name", options.getAddressSpace()), new MetricLabel("namespace", options.getAddressSpaceNamespace())};
        metrics.registerMetric(new ScalarMetric(
                "addresses_ready_total",
                "Total number of addresses in ready state",
                MetricType.gauge,
                () -> Collections.singletonList(new MetricValue(readyAddressCount, metricLabels))));

        metrics.registerMetric(new ScalarMetric(
                "addresses_not_ready_total",
                "Total number of address in a not ready state",
                MetricType.gauge,
                () -> Collections.singletonList(new MetricValue(notReadyAddressCount, metricLabels))));

        metrics.registerMetric(new ScalarMetric(
                "addresses_total",
                "Total number of addresses",
                MetricType.gauge,
                () -> Collections.singletonList(new MetricValue(numAddresses, metricLabels))));

        metrics.registerMetric(new ScalarMetric(
                "addresses_pending_total",
                "Total number of addresses in Pending state",
                MetricType.gauge,
                () -> Collections.singletonList(new MetricValue(countByPhase.get(Pending), metricLabels))));

        metrics.registerMetric(new ScalarMetric(
                "addresses_failed_total",
                "Total number of addresses in Failed state",
                MetricType.gauge,
                () -> Collections.singletonList(new MetricValue(countByPhase.get(Failed), metricLabels))));

        metrics.registerMetric(new ScalarMetric(
                "addresses_terminating_total",
                "Total number of addresses in Terminating state",
                MetricType.gauge,
                () -> Collections.singletonList(new MetricValue(countByPhase.get(Terminating), metricLabels))));

        metrics.registerMetric(new ScalarMetric(
                "addresses_configuring_total",
                "Total number of addresses in Configuring state",
                MetricType.gauge,
                () -> Collections.singletonList(new MetricValue(countByPhase.get(Configuring), metricLabels))));

        metrics.registerMetric(new ScalarMetric(
                "addresses_active_total",
                "Total number of addresses in Active state",
                MetricType.gauge,
                () -> Collections.singletonList(new MetricValue(countByPhase.get(Active), metricLabels))));

        metrics.registerMetric(new ScalarMetric(
                "addresses_forwarders_total",
                "Total number of forwarders",
                MetricType.gauge,
                () -> Collections.singletonList(new MetricValue(numForwarders, metricLabels))));

        metrics.registerMetric(new ScalarMetric(
                "addresses_forwarders_ready_total",
                "Total number of forwarders in ready state",
                MetricType.gauge,
                () -> Collections.singletonList(new MetricValue(readyForwarders, metricLabels))));

        metrics.registerMetric(new ScalarMetric(
                "addresses_forwarders_not_ready_total",
                "Total number of forwarders in not ready state",
                MetricType.gauge,
                () -> Collections.singletonList(new MetricValue(notReadyForwarders, metricLabels))));

        metrics.registerMetric(new ScalarMetric(
                "standard_controller_loop_duration_seconds",
                "Time spent in controller loop",
                MetricType.gauge,
                () -> Collections.singletonList(new MetricValue((double) totalTime / 1_000_000_000.0, metricLabels))));

        metrics.registerMetric(new ScalarMetric(
                "standard_controller_router_check_failures_total",
                "Number of RouterCheckFailures",
                MetricType.counter,
                () -> Collections.singletonList(new MetricValue(statusCollector.getRouterCheckFailures(), metricLabels))));
    }

    public void start() throws Exception {
        // Run initial status check so that existing addresses are ready
        statusCollector.checkRouterStatus();
        statusCollector.start();
        reconciler.start();
        watch = addressApi.watchAddresses(reconciler, options.getResyncInterval());
    }

    public void stop() throws Exception {
        if (watch != null) {
            watch.close();
        }
        statusCollector.stop();
        reconciler.stop();
    }

    @Override
    public void onUpdate(List<Address> addressList) throws Exception {
        long start = System.nanoTime();

        Schema schema = schemaProvider.getSchema();
        if (schema == null) {
            log.info("No schema available");
            return;
        }
        AddressSpaceType addressSpaceType = schema.findAddressSpaceType("standard").orElseThrow(() -> new RuntimeException("Unable to handle updates: standard address space not found in schema!"));
        AddressResolver addressResolver = new AddressResolver(addressSpaceType);
        if (addressSpaceType.getPlans().isEmpty()) {
            log.info("No address space plan available");
            return;
        }

        String addressPrefix = String.format("%s.", options.getAddressSpace());
        // make a deep copy of the list first, but only for relevant items
        addressList = addressList.stream()
                .filter(a -> a.getMetadata().getName().startsWith(addressPrefix))
                // copy item
                .map(a -> new AddressBuilder(a).build())
                .collect(Collectors.toList());

        AddressSpaceResolver addressSpaceResolver = new AddressSpaceResolver(schema);

        final Map<String, ProvisionState> previousStatus = addressList.stream()
                .collect(Collectors.toMap(a -> a.getMetadata().getName(),
                        a -> new ProvisionState(a.getStatus(), AppliedConfig.getCurrentAppliedAddressSpec(a).orElse(null))));

        AddressSpacePlan addressSpacePlan = addressSpaceType.findAddressSpacePlan(options.getAddressSpacePlanName()).orElseThrow(() -> new RuntimeException("Unable to handle updates: address space plan " + options.getAddressSpacePlanName() + " not found!"));

        long resolvedPlan = System.nanoTime();

        AddressSpace addressSpace = addressSpaceApi.getAddressSpaceWithName(options.getAddressSpaceNamespace(), options.getAddressSpace()).orElse(null);
        if (addressSpace == null) {
            log.warn("Unable to find address space, will not validate address forwarders");
        }

        AddressProvisioner provisioner = new AddressProvisioner(addressSpaceResolver, addressResolver, addressSpacePlan, clusterGenerator, kubernetes, eventLogger, options.getInfraUuid(), brokerIdGenerator);

        Map<String, Address> validAddresses = new HashMap<>();
        List<Phase> readyPhases = Arrays.asList(Configuring, Active);
        for (Address address : addressList) {
            address.getStatus().clearMessages();
            if (readyPhases.contains(address.getStatus().getPhase())) {
                address.getStatus().setReady(true);
            }

            if (address.getSpec().getForwarders() != null) {
                List<AddressStatusForwarder> forwarderStatuses = new ArrayList<>();
                for (AddressSpecForwarder forwarder : address.getSpec().getForwarders()) {
                    forwarderStatuses.add(new AddressStatusForwarderBuilder()
                            .withName(forwarder.getName())
                            .withReady(true)
                            .build());
                }
                address.getStatus().setForwarders(forwarderStatuses);
            }

            if (!validateAddress(address, addressSpace, addressResolver)) {
                continue;
            }

            Address existing = validAddresses.get(address.getSpec().getAddress());
            if (existing != null) {
                if (!address.getStatus().getPhase().equals(Pending) && existing.getStatus().getPhase().equals(Pending)) {
                    // If existing address is pending, and we are not pending, we take priority
                    String errorMessage = String.format("Address '%s' already exists with resource name '%s'", address.getSpec().getAddress(), address.getMetadata().getName());
                    updateAddressStatus(existing, errorMessage, true);
                    validAddresses.put(address.getSpec().getAddress(), address);
                } else {
                    // Existing address has already been accepted, or we are both pending, existing takes priority.
                    String errorMessage = String.format("Address '%s' already exists with resource name '%s'", address.getSpec().getAddress(), existing.getMetadata().getName());
                    updateAddressStatus(address, errorMessage, true);
                }
                continue;
            }

            // If the address is already assigned to at least one broker then the address must have been valid in
            // the past. Add to the list of valid addresses to ensure it is not prematurely de-provisioned owing
            // to a later update to the address's spec (e.g. bad deadletter assignment).
            boolean updateStatus = true;
            if (!address.getStatus().getBrokerStatuses().isEmpty()) {
                validAddresses.put(address.getSpec().getAddress(), address);
                updateStatus = false;
            }

            if ("subscription".equals(address.getSpec().getType())) {
                String topic = address.getSpec().getTopic();
                if (topic == null) {
                    String errorMessage = String.format("Subscription address '%s' (resource name '%s') must reference a known topic address.",
                            address.getSpec().getAddress(),
                            address.getMetadata().getName());
                    updateAddressStatus(address, errorMessage, updateStatus);
                    continue;
                } else {
                    Optional<Address> refTopic = addressList.stream().filter(a -> topic.equals(a.getSpec().getAddress())).findFirst();
                    if (refTopic.isEmpty()) {
                        String errorMessage = String.format(
                                "Subscription address '%s' (resource name '%s') references a topic address '%s' that does not exist.",
                                address.getSpec().getAddress(),
                                address.getMetadata().getName(),
                                topic);
                        updateAddressStatus(address, errorMessage, updateStatus);
                        continue;
                    } else {
                        Address target = refTopic.get();
                        if (!"topic".equals(target.getSpec().getType())) {
                            String errorMessage = String.format(
                                    "Subscription address '%s' (resource name '%s') references a topic address '%s'" +
                                            " (resource name '%s') that is not of expected type 'topic' (found type '%s' instead).",
                                    address.getSpec().getAddress(),
                                    address.getMetadata().getName(),
                                    topic,
                                    target.getMetadata().getName(),
                                    target.getSpec().getType());
                            updateAddressStatus(address, errorMessage, updateStatus);
                            continue;
                        }
                    }
                }
            }

            String deadletter = address.getSpec().getDeadletter();
            if (deadletter != null) {
                if (!Set.of("queue", "topic").contains(address.getSpec().getType())) {
                    String errorMessage = String.format(
                            "Address '%s' (resource name '%s') of type '%s' cannot reference a dead letter address.",
                            address.getSpec().getAddress(),
                            address.getMetadata().getName(),
                            address.getSpec().getType());
                    updateAddressStatus(address, errorMessage, updateStatus);
                    continue;
                }

                Optional<Address> refDlq = addressList.stream().filter(a -> deadletter.equals(a.getSpec().getAddress())).findFirst();
                if (refDlq.isEmpty()) {
                    String errorMessage = String.format(
                            "Address '%s' (resource name '%s') references a dead letter address '%s' that does not exist.",
                            address.getSpec().getAddress(),
                            address.getMetadata().getName(),
                            deadletter);
                    updateAddressStatus(address, errorMessage, updateStatus);
                    continue;
                } else {
                    Address target = refDlq.get();
                    if (!"deadletter".equals(target.getSpec().getType())) {
                        String errorMessage = String.format(
                                "Address '%s' (resource name '%s') references a dead letter address '%s'" +
                                        " (resource name '%s') that is not of expected type 'deadletter' (found type '%s' instead).",
                                address.getSpec().getAddress(),
                                address.getMetadata().getName(),
                                deadletter,
                                target.getMetadata().getName(),
                                target.getSpec().getType());
                        updateAddressStatus(address, errorMessage, updateStatus);
                        continue;
                    }
                }
            }

            String expiry = address.getSpec().getExpiry();
            if (expiry != null) {
                if (!Set.of("queue", "topic").contains(address.getSpec().getType())) {
                    String errorMessage = String.format(
                            "Address '%s' (resource name '%s') of type '%s' cannot reference an expiry address.",
                            address.getSpec().getAddress(),
                            address.getMetadata().getName(),
                            address.getSpec().getType());
                    updateAddressStatus(address, errorMessage, updateStatus);
                    continue;
                }

                Optional<Address> refDlq = addressList.stream().filter(a -> expiry.equals(a.getSpec().getAddress())).findFirst();
                if (refDlq.isEmpty()) {
                    String errorMessage = String.format(
                            "Address '%s' (resource name '%s') references an expiry address '%s' that does not exist.",
                            address.getSpec().getAddress(),
                            address.getMetadata().getName(),
                            expiry);
                    updateAddressStatus(address, errorMessage, updateStatus);
                    continue;
                } else {
                    Address target = refDlq.get();
                    if (!"deadletter".equals(target.getSpec().getType())) {
                        String errorMessage = String.format(
                                "Address '%s' (resource name '%s') references an expiry address '%s'" +
                                        " (resource name '%s') that is not of expected type 'deadletter' (found type '%s' instead).",
                                address.getSpec().getAddress(),
                                address.getMetadata().getName(),
                                expiry,
                                target.getMetadata().getName(),
                                target.getSpec().getType());
                        updateAddressStatus(address, errorMessage, updateStatus);
                        continue;
                    }
                }
            }
            validAddresses.put(address.getSpec().getAddress(), address);
        }

        Set<Address> addressSet = new LinkedHashSet<>(validAddresses.values());

        Map<Phase, Long> countByPhase = countPhases(addressSet);
        log.info("Total: {}, Active: {}, Configuring: {}, Pending: {}, Terminating: {}, Failed: {}", addressSet.size(), countByPhase.get(Active), countByPhase.get(Configuring), countByPhase.get(Pending), countByPhase.get(Terminating), countByPhase.get(Failed));

        Map<String, Map<String, UsageInfo>> usageMap = provisioner.checkUsage(filterByNotPhases(addressSet, EnumSet.of(Pending)));

        log.info("Usage: {}", usageMap);

        long calculatedUsage = System.nanoTime();
        Set<Address> pendingAddresses = filterBy(addressSet, address -> address.getStatus().getPhase().equals(Pending) ||
                !Objects.equals(AppliedConfig.create(address.getSpec()).getAddressSpec(), AppliedConfig.getCurrentAppliedAddressSpec(address).orElse(null)) ||
                hasPlansChanged(addressResolver, address));

        Map<String, Map<String, UsageInfo>> neededMap = provisioner.checkQuota(usageMap, pendingAddresses, addressSet);

        // If we have address in configuring or pending, wake up the checker thread
        if (countByPhase.get(Configuring) > 0 || countByPhase.get(Pending) > 0) {
            statusCollector.wakeup();
        }

        log.info("Needed: {}", neededMap);

        long checkedQuota = System.nanoTime();

        List<BrokerCluster> clusterList = kubernetes.listClusters();
        RouterCluster routerCluster = kubernetes.getRouterCluster();
        long listClusters = System.nanoTime();

        StandardInfraConfig desiredConfig = (StandardInfraConfig) addressSpaceResolver.getInfraConfig("standard", addressSpacePlan.getMetadata().getName());
        provisioner.provisionResources(routerCluster, clusterList, neededMap, pendingAddresses, desiredConfig);

        processDeadletteres(addressSet);

        long provisionResources = System.nanoTime();

        Set<Address> liveAddresses = filterByPhases(addressSet, EnumSet.of(Configuring, Active));
        boolean checkRouterLinks = liveAddresses.stream()
                .anyMatch(a -> Arrays.asList("queue", "subscription").contains(a.getSpec().getType()) &&
                        a.getSpec().getForwarders() != null && !a.getSpec().getForwarders().isEmpty());

        List<RouterStatus> routerStatusList = checkRouterStatuses(checkRouterLinks);

        checkAddressStatuses(liveAddresses, addressResolver, routerStatusList);

        long checkStatuses = System.nanoTime();
        for (Address address : liveAddresses) {
            if (address.getStatus().isReady()) {
                address.getStatus().setPhase(Active);
            }
        }

        checkAndMoveMigratingBrokersToDraining(addressSet, clusterList);
        checkAndRemoveDrainingBrokers(addressSet);

        Set<Address> notTerminating = filterByNotPhases(addressSet, EnumSet.of(Terminating));
        List<BrokerCluster> unusedClusters = determineUnusedClusters(clusterList, notTerminating);
        deprovisionUnused(unusedClusters);

        long deprovisionUnused = System.nanoTime();

        List<BrokerCluster> usedClusters = new ArrayList<>(clusterList);
        usedClusters.removeAll(unusedClusters);
        upgradeClusters(desiredConfig, addressResolver, usedClusters, notTerminating);

        long upgradeClusters = System.nanoTime();

        int staleCount = 0;
        for (Address address : addressList) {
            var addressName = address.getMetadata().getNamespace() + ":" + address.getMetadata().getName();
            ProvisionState previous = previousStatus.get(address.getMetadata().getName());
            ProvisionState current = new ProvisionState(address.getStatus(), AppliedConfig.getCurrentAppliedAddressSpec(address).orElse(null));
            log.debug("Compare current state\nPrevious: {}\nCurrent : {}", previous, current);
            if (!current.equals(previous)) {
                log.debug("Address did change: {}", addressName);
                try {
                    if(!addressApi.replaceAddress(address)) {
                        log.info("Failed to persist address state: {}", addressName);
                    }
                } catch (KubernetesClientException e) {
                    if (e.getStatus().getCode() == 409) {
                        // The address record is stale.  The address controller will be notified again by the watcher,
                        // so safe ignore the stale record.
                        log.debug("Address {} has stale resource version {}", address.getMetadata().getName(), address.getMetadata().getResourceVersion());
                        staleCount++;
                    } else {
                        throw e;
                    }
                }
            } else {
                log.debug("No change for address: {}", addressName);
            }
        }

        if (staleCount > 0) {
            log.info("{} address(es) were stale.", staleCount);
        }

        long replaceAddresses = System.nanoTime();
        garbageCollectTerminating(filterByPhases(addressSet, EnumSet.of(Terminating)), addressResolver, routerStatusList);
        long gcTerminating = System.nanoTime();

        log.info("Time spent: Total: {} ns, resolvedPlan: {} ns, calculatedUsage: {} ns, checkedQuota: {} ns, listClusters: {} ns, provisionResources: {} ns, checkStatuses: {} ns, deprovisionUnused: {} ns, upgradeClusters: {} ns, replaceAddresses: {} ns, gcTerminating: {} ns", gcTerminating - start, resolvedPlan - start, calculatedUsage - resolvedPlan, checkedQuota - calculatedUsage, listClusters - checkedQuota, provisionResources - listClusters, checkStatuses - provisionResources, deprovisionUnused - checkStatuses, upgradeClusters - deprovisionUnused, replaceAddresses - upgradeClusters, gcTerminating - replaceAddresses);

        if (routerStatusList.isEmpty()) {
            readyAddressCount = null;
            notReadyAddressCount = null;
            notReadyForwarders = null;
            readyForwarders = null;
        } else {
            readyAddressCount = addressList.stream()
                    .filter(a -> a.getStatus().isReady())
                    .count();
            notReadyAddressCount = addressList.size() - readyAddressCount;

            Set<Address> addressesWithForwarders = filterBy(addressSet, address -> (address.getStatus().getForwarders() != null && !address.getStatus().getForwarders().isEmpty()));
            readyForwarders = countForwardersReady(addressesWithForwarders, true);
            notReadyForwarders = countForwardersReady(addressesWithForwarders, false);
            numForwarders = readyForwarders + notReadyForwarders;
        }

        numAddresses = (long) addressList.size();
        totalTime = gcTerminating - start;
        this.countByPhase = countByPhase;
    }

    private void updateAddressStatus(Address address, String errorMessage, boolean updateStatus) {
        if (updateStatus) {
            address.getStatus().setPhase(Pending);
        }
        address.getStatus().appendMessage(errorMessage);
    }

    private long countForwardersReady(Set<Address> addressesWithForwarders, boolean desired) {
        long total = 0;
        for (Address address : addressesWithForwarders) {
            for (AddressStatusForwarder forwarder : address.getStatus().getForwarders()) {
                if (forwarder.isReady() == desired) {
                    total++;
                }
            }
        }
        return total;
    }

    private Set<String> checkRegisteredSubserveTopics() {
        SubserveStatusCollector statusCollector = new SubserveStatusCollector(vertx, options.getCertDir());

        for (Pod router : kubernetes.listRouters()) {
            if (Readiness.isPodReady(router)) {
                try {
                    return statusCollector.collect(router);
                } catch (Exception e) {
                    log.info("Error requesting registered topics from {}. Ignoring", router.getMetadata().getName(), e);
                }
            }
        }
        return Collections.emptySet();
    }

    private void upgradeClusters(StandardInfraConfig desiredConfig, AddressResolver addressResolver, List<BrokerCluster> clusterList, Set<Address> addresses) throws Exception {
        for (BrokerCluster cluster : clusterList) {
            final StandardInfraConfig currentConfig = cluster.getInfraConfig();
            if (!desiredConfig.equals(currentConfig)) {
                if (options.getVersion().equals(desiredConfig.getSpec().getVersion())) {
                    if (!desiredConfig.getUpdatePersistentVolumeClaim() && currentConfig != null && !currentConfig.getSpec().getBroker().getResources().getStorage().equals(desiredConfig.getSpec().getBroker().getResources().getStorage())) {
                        desiredConfig = new StandardInfraConfigBuilder(desiredConfig)
                                .editSpec()
                                .editBroker()
                                .editResources()
                                .withStorage(currentConfig.getSpec().getBroker().getResources().getStorage())
                                .endResources()
                                .endBroker()
                                .endSpec()
                                .build();
                    }
                    BrokerCluster upgradedCluster = null;
                    if (!cluster.getClusterId().startsWith("broker-sharded")) {
                        upgradedCluster = clusterGenerator.generateCluster(cluster.getClusterId(), 1, null, null, desiredConfig);
                    } else {
                        Address address = addresses.stream()
                                .filter(a -> a.getStatus().getBrokerStatuses().stream().map(BrokerStatus::getClusterId).collect(Collectors.toSet()).contains(cluster.getClusterId()))
                                .findFirst()
                                .orElse(null);
                        if (address != null) {
                            AddressPlan plan = addressResolver.getPlan(address);
                            int brokerNeeded = 0;
                            for (Map.Entry<String, Double> resourceRequest : plan.getResources().entrySet()) {
                                if (resourceRequest.getKey().equals("broker")) {
                                    brokerNeeded = resourceRequest.getValue().intValue();
                                    break;
                                }
                            }
                            upgradedCluster = clusterGenerator.generateCluster(cluster.getClusterId(), brokerNeeded, address, plan, desiredConfig);
                        }
                    }
                    log.info("Upgrading broker {}", cluster.getClusterId());
                    cluster.updateResources(upgradedCluster, desiredConfig);
                    boolean updatePersistentVolumeClaim = desiredConfig.getUpdatePersistentVolumeClaim();
                    List<HasMetadata> itemsToBeApplied = new ArrayList<>(cluster.getResources().getItems());
                    try {
                        kubernetes.apply(cluster.getResources(), updatePersistentVolumeClaim, itemsToBeApplied::remove);
                    } catch (KubernetesClientException original) {
                        // Workaround for #2880 Failure executing: PATCH... Message: Unable to access invalid index: 20.
                        if (!itemsToBeApplied.isEmpty() && original.getMessage() != null && original.getMessage().contains("Unable to access invalid index")) {
                            HasMetadata failedResource = itemsToBeApplied.get(0);
                            if (failedResource instanceof StatefulSet || failedResource instanceof Deployment) {
                                log.warn("Failed to apply {} for cluster {}, will try #2880 workaround", failedResource, cluster.getClusterId(), original);
                                try {
                                    if (failedResource instanceof StatefulSet) {
                                        StatefulSetSpec spec = ((StatefulSet) failedResource).getSpec();
                                        stripEnvironmentFromResource(spec.getTemplate());
                                        spec.setReplicas(0);
                                    } else {
                                        DeploymentSpec spec = ((Deployment) failedResource).getSpec();
                                        stripEnvironmentFromResource(spec.getTemplate());
                                        spec.setReplicas(0);
                                    }
                                    Kubernetes.addObjectAnnotation(failedResource, AnnotationKeys.APPLIED_INFRA_CONFIG, new ObjectMapper().writeValueAsString(currentConfig));
                                    kubernetes.apply(failedResource, updatePersistentVolumeClaim);
                                    log.warn("Applied #2880 workaround for {} of {}, next upgrade cycle should complete upgrade.", failedResource.getMetadata(), cluster.getClusterId());
                                } catch (KubernetesClientException e) {
                                    log.error("Failed to apply failed resource {} of {} for #2880 workaround. " +
                                            "Manual intervention may be required to complete upgrade", failedResource.getMetadata(), cluster.getClusterId());
                                }
                            } else {
                                log.warn("Don't know how to work around #2880 for resource type {}", failedResource.getClass());
                            }
                        }
                        throw original;
                    }
                    eventLogger.log(BrokerUpgraded, "Upgraded broker", Normal, Broker, cluster.getClusterId());
                } else {
                    log.info("Version of desired config ({}) does not match controller version ({}), skipping upgrade", desiredConfig.getSpec().getVersion(), options.getVersion());
                }
            }
        }
    }

    private void stripEnvironmentFromResource(PodTemplateSpec resource) {
        resource.getSpec().getContainers().forEach(
                c -> {
                    c.setEnv(Collections.emptyList());
                }
        );
        resource.getSpec().getInitContainers().forEach(
                c -> {
                    c.setEnv(Collections.emptyList());
                }
        );
    }

    private List<BrokerCluster> determineUnusedClusters(List<BrokerCluster> clusters, Set<Address> addressSet) {
        List<BrokerCluster> unused = new ArrayList<>();

        for (BrokerCluster cluster : clusters) {
            int numFound = 0;
            for (Address address : addressSet) {
                Set<String> clusterIds = address.getStatus().getBrokerStatuses().stream()
                        .map(BrokerStatus::getClusterId)
                        .collect(Collectors.toSet());
                if (clusterIds.contains(cluster.getClusterId())) {
                    numFound++;
                }
                for (BrokerStatus brokerStatus : address.getStatus().getBrokerStatuses()) {
                    if (brokerStatus.getClusterId().equals(cluster.getClusterId())) {
                        numFound++;
                    }
                }
            }

            if (numFound == 0) {
                unused.add(cluster);
            }
        }
        return unused;
    }

    private void deprovisionUnused(List<BrokerCluster> unused) {
        unused.forEach(cluster -> {
            try {
                kubernetes.delete(cluster.getResources());
                eventLogger.log(ControllerReason.BrokerDeleted, "Deleted broker " + cluster.getClusterId(), Normal, ControllerKind.Address, cluster.getClusterId());
            } catch (Exception e) {
                log.warn("Error deleting cluster {}", cluster.getClusterId(), e);
                eventLogger.log(ControllerReason.BrokerDeleteFailed, "Error deleting broker cluster " + cluster.getClusterId() + ": " + e.getMessage(), EventLogger.Type.Warning, ControllerKind.Address, cluster.getClusterId());
            }
        });
    }

    private Set<Address> filterBy(Set<Address> addressSet, Predicate<Address> predicate) {
        return addressSet.stream()
                .filter(predicate::test)
                .collect(Collectors.toSet());
    }

    private Set<Address> filterByPhases(Set<Address> addressSet, Set<Phase> phases) {
        return addressSet.stream()
                .filter(address -> phases.contains(address.getStatus().getPhase()))
                .collect(Collectors.toSet());
    }

    private Map<Phase,Long> countPhases(Set<Address> addressSet) {
        Map<Phase, Long> countMap = new HashMap<>();
        for (Phase phase : Phase.values()) {
            countMap.put(phase, 0L);
        }
        for (Address address : addressSet) {
            countMap.put(address.getStatus().getPhase(), 1 + countMap.get(address.getStatus().getPhase()));
        }
        return countMap;
    }

    private Set<Address> filterByNotPhases(Set<Address> addressSet, Set<Phase> phases) {
        return addressSet.stream()
                .filter(address -> !phases.contains(address.getStatus().getPhase()))
                .collect(Collectors.toSet());
    }

    private void garbageCollectTerminating(Set<Address> addresses, AddressResolver addressResolver, List<RouterStatus> routerStatusList) throws Exception {
        Map<Address, Integer> okMap = checkAddressStatuses(addresses, addressResolver, routerStatusList);
        for (Map.Entry<Address, Integer> entry : okMap.entrySet()) {
            if (entry.getValue() == 0) {
                log.info("Garbage collecting {}", entry.getKey());
                addressApi.deleteAddress(entry.getKey());
            }
        }
    }

    private void checkAndMoveMigratingBrokersToDraining(Set<Address> addresses, List<BrokerCluster> brokerList) throws Exception {
        for (Address address : addresses) {
            int numActive = 0;
            int numReadyActive = 0;
            for (BrokerStatus brokerStatus : address.getStatus().getBrokerStatuses()) {
                if (BrokerState.Active.equals(brokerStatus.getState())) {
                    numActive++;
                    for (BrokerCluster cluster : brokerList) {
                        if (brokerStatus.getClusterId().equals(cluster.getClusterId()) && cluster.getReadyReplicas() > 0) {
                            numReadyActive++;
                            break;
                        }
                    }
                }
            }

            if (numActive == numReadyActive) {
                for (BrokerStatus brokerStatus : address.getStatus().getBrokerStatuses()) {
                    if (BrokerState.Migrating.equals(brokerStatus.getState())) {
                        brokerStatus.setState(BrokerState.Draining);
                    }
                }
            }
        }
    }

    private void checkAndRemoveDrainingBrokers(Set<Address> addresses) throws Exception {
        BrokerStatusCollector brokerStatusCollector = brokerStatusCollectorFactory.createBrokerStatusCollector();
        for (Address address : addresses) {
            List<BrokerStatus> brokerStatuses = new ArrayList<>();
            for (BrokerStatus brokerStatus : address.getStatus().getBrokerStatuses()) {
                if (BrokerState.Draining.equals(brokerStatus.getState())) {
                    try {
                        long messageCount = brokerStatusCollector.getQueueMessageCount(address.getSpec().getAddress(), brokerStatus.getClusterId());
                        if (messageCount > 0) {
                            brokerStatuses.add(brokerStatus);
                        }
                    } catch (Exception e) {
                        log.warn("Error checking status of broker {}:{} in state Draining. Keeping.", brokerStatus.getClusterId(), brokerStatus.getContainerId(), e);
                        brokerStatuses.add(brokerStatus);
                    }
                } else {
                    brokerStatuses.add(brokerStatus);
                }
            }
            address.getStatus().setBrokerStatuses(brokerStatuses);
        }
    }

    private List<RouterStatus> checkRouterStatuses(boolean checkRouterLinks) throws Exception {

        statusCollector.setCheckRouterLinks(checkRouterLinks);

        return statusCollector.getLatestResults();
    }

    private Map<Address, Integer> checkAddressStatuses(Set<Address> addresses, AddressResolver addressResolver, List<RouterStatus> routerStatusList) throws Exception {

        Map<Address, Integer> numOk = new HashMap<>();
        if (addresses.isEmpty()) {
            return numOk;
        }
        Map<String, Integer> clusterOk = new HashMap<>();
        Map<String, Set<String>> clusterAddresses = new HashMap<>();
        Map<String, Set<String>> clusterQueues = new HashMap<>();

        BrokerStatusCollector brokerStatusCollector = brokerStatusCollectorFactory.createBrokerStatusCollector();
        for (Address address : addresses) {
            AddressType addressType = addressResolver.getType(address);
            AddressPlan addressPlan = addressResolver.getPlan(addressType, address);

            int ok = 0;
            switch (addressType.getName()) {
                case "deadletter":
                    ok += checkBrokerStatus(brokerStatusCollector, address, clusterOk, clusterAddresses, clusterQueues);
                    for (RouterStatus routerStatus : routerStatusList) {
                        ok += routerStatus.checkAddress(address);
                        ok += routerStatus.checkAutoLinks(address, 1);
                    }
                    for (BrokerStatus brokerStatus : address.getStatus().getBrokerStatuses()) {
                        Set<String> addressNames = clusterAddresses.get(brokerStatus.getClusterId());
                        Set<String> queueNames = clusterQueues.get(brokerStatus.getClusterId());
                        ok += checkAddressAndQueue(address, brokerStatus, addressNames, address.getSpec().getAddress(), queueNames, address.getSpec().getAddress());
                    }
                    ok += RouterStatus.checkActiveAutoLink(address, routerStatusList, 1);
                    updateMessageTtlStatus(addressPlan, address);
                    updateMessageRedeliveryStatus(addressPlan, address);
                    break;
                case "queue":
                    ok += checkBrokerStatus(brokerStatusCollector, address, clusterOk, clusterAddresses, clusterQueues);
                    for (RouterStatus routerStatus : routerStatusList) {
                        ok += routerStatus.checkAddress(address);
                        ok += routerStatus.checkAutoLinks(address, 2);
                    }
                    for (BrokerStatus brokerStatus : address.getStatus().getBrokerStatuses()) {
                        Set<String> addressNames = clusterAddresses.get(brokerStatus.getClusterId());
                        Set<String> queueNames = clusterQueues.get(brokerStatus.getClusterId());
                        ok += checkAddressAndQueue(address, brokerStatus, addressNames, address.getSpec().getAddress(), queueNames, address.getSpec().getAddress());
                        if (address.getSpec().getDeadletter() != null) {
                            ok += checkAddressAndQueue(address, brokerStatus, addressNames, address.getSpec().getDeadletter(), queueNames, address.getSpec().getDeadletter());
                        }
                        if (address.getSpec().getExpiry() != null) {
                            ok += checkAddressAndQueue(address, brokerStatus, addressNames, address.getSpec().getExpiry(), queueNames, address.getSpec().getExpiry());
                        }
                    }
                    ok += RouterStatus.checkActiveAutoLink(address, routerStatusList, 2);
                    ok += RouterStatus.checkForwarderLinks(address, routerStatusList);
                    updateMessageTtlStatus(addressPlan, address);
                    updateMessageRedeliveryStatus(addressPlan, address);
                    break;
                case "subscription":
                    ok += checkBrokerStatus(brokerStatusCollector, address, clusterOk, clusterAddresses, clusterQueues);
                    for (BrokerStatus brokerStatus : address.getStatus().getBrokerStatuses()) {
                        Set<String> addressNames = clusterAddresses.get(brokerStatus.getClusterId());
                        Set<String> queueNames = clusterQueues.get(brokerStatus.getClusterId());
                        ok += checkAddressAndQueue(address, brokerStatus, addressNames, address.getSpec().getTopic(), queueNames, address.getSpec().getAddress());
                    }
                    ok += RouterStatus.checkForwarderLinks(address, routerStatusList);
                    break;
                case "topic":
                    ok += checkBrokerStatus(brokerStatusCollector, address, clusterOk, clusterAddresses, clusterQueues);
                    for (RouterStatus routerStatus : routerStatusList) {
                        ok += routerStatus.checkLinkRoutes(address);
                    }
                    if (isPooled(addressPlan)) {
                        for (BrokerStatus brokerStatus : address.getStatus().getBrokerStatuses()) {
                            Set<String> addressNames = clusterAddresses.get(brokerStatus.getClusterId());
                            Set<String> queueNames = clusterQueues.get(brokerStatus.getClusterId());
                            ok += checkAddressAndQueue(address, brokerStatus, addressNames, address.getSpec().getAddress(), null, null);

                            if (address.getSpec().getExpiry() != null) {
                                ok += checkAddressAndQueue(address, brokerStatus, addressNames, address.getSpec().getExpiry(), queueNames, address.getSpec().getExpiry());
                            }
                        }
                        ok += RouterStatus.checkActiveLinkRoute(address, routerStatusList);
                    } else {
                        ok += RouterStatus.checkConnection(address, routerStatusList);
                    }
                    updateMessageTtlStatus(addressPlan, address);
                    updateMessageRedeliveryStatus(addressPlan, address);
                    break;
                case "anycast":
                case "multicast":
                    for (RouterStatus routerStatus : routerStatusList) {
                        ok += routerStatus.checkAddress(address);
                    }
                    break;
            }
            numOk.put(address, ok);
        }

        return numOk;
    }

    private int checkAddressAndQueue(Address address, BrokerStatus brokerStatus, Set<String> addressNames, String expectedAddressName, Set<String> queueNames, String expectedQueueName) {
        int i = 0;
        if (!addressNames.contains(expectedAddressName)) {
            address.getStatus().setReady(false);
            address.getStatus().appendMessage(String.format("Address %s is not configured on broker %s", expectedAddressName, brokerStatus.getContainerId()));
        } else {
            i++;
        }
        if (queueNames != null) {
            if (!queueNames.contains(expectedQueueName)) {
                address.getStatus().setReady(false);
                address.getStatus().appendMessage(String.format("Queue %s is not configured on broker %s", expectedQueueName, brokerStatus.getContainerId()));
            } else {
                i++;
            }
        }
        return i;
    }

    private void updateMessageRedeliveryStatus(AddressPlan addressPlan, Address address) {
        MessageRedeliveryBuilder status = null;

        MessageRedelivery planRedelivery = addressPlan.getMessageRedelivery();
        if (planRedelivery != null) {
            status = new MessageRedeliveryBuilder(planRedelivery);
        }

        MessageRedelivery addrRedelivery = address.getSpec().getMessageRedelivery();
        if (addrRedelivery != null) {
            if (addrRedelivery.getMaximumDeliveryAttempts() != null) {
                if (status == null) {
                    status = new MessageRedeliveryBuilder();
                }
                status.withMaximumDeliveryAttempts(addrRedelivery.getMaximumDeliveryAttempts());
            }
            if (addrRedelivery.getMaximumDeliveryDelay() != null) {
                if (status == null) {
                    status = new MessageRedeliveryBuilder();
                }
                status.withMaximumDeliveryDelay(addrRedelivery.getMaximumDeliveryDelay());
            }
            if (addrRedelivery.getRedeliveryDelay() != null) {
                if (status == null) {
                    status = new MessageRedeliveryBuilder();
                }
                status.withRedeliveryDelay(addrRedelivery.getRedeliveryDelay());
            }
            if (addrRedelivery.getRedeliveryDelayMultiplier() != null) {
                if (status == null) {
                    status = new MessageRedeliveryBuilder();
                }
                status.withRedeliveryDelayMultiplier(addrRedelivery.getRedeliveryDelayMultiplier());
            }
        }

        address.getStatus().setMessageRedelivery(status == null ? null : status.build());

    }

    private void updateMessageTtlStatus(AddressPlan addressPlan, Address address) {
        MessageTtlBuilder status = new MessageTtlBuilder();

        MessageTtl planTtl = addressPlan.getTtl();
        if (planTtl != null) {
            Long min = sanitizeTtlValue(planTtl.getMinimum());
            Long max = sanitizeTtlValue(planTtl.getMaximum());

            boolean maxGtMin = min == null || max == null || max > min;
            if (max != null && maxGtMin) {
                status.withMaximum(max);
            }
            if (min != null && maxGtMin) {
                status.withMinimum(min);
            }
        }

        MessageTtl addrTtl = address.getSpec().getMessageTtl();
        if (addrTtl != null) {
            Long min = sanitizeTtlValue(addrTtl.getMinimum());
            Long max = sanitizeTtlValue(addrTtl.getMaximum());

            boolean maxGtMin = min == null || max == null || max > min;
            if (max != null && maxGtMin && (!status.hasMaximum() || status.getMaximum() > max)) {
                status.withMaximum(max);
            }
            if (min != null && maxGtMin && (!status.hasMinimum() || status.getMinimum() < min)) {
                status.withMinimum(min);
            }
        }

        address.getStatus().setMessageTtl(status.hasMaximum() || status.hasMinimum() ? status.build() : null);

    }

    private Long sanitizeTtlValue(Long ttl) {
        return ttl != null && ttl < 1 ? null : ttl;
    }

    private int checkBrokerStatus(BrokerStatusCollector brokerStatusCollector, Address address, Map<String, Integer> clusterOk, Map<String, Set<String>> clusterAddresses, Map<String, Set<String>> clusterQueues) throws Exception {
        Set<String> clusterIds = address.getStatus().getBrokerStatuses().stream()
                .map(BrokerStatus::getClusterId)
                .collect(Collectors.toSet());

        int numOk = 0;
        for (String clusterId : clusterIds) {
            if (!clusterOk.containsKey(clusterId)) {
                if (!kubernetes.isDestinationClusterReady(clusterId)) {
                    address.getStatus().setReady(false).appendMessage("Cluster " + clusterId + " is unavailable");
                    clusterOk.put(clusterId, 0);
                } else {
                    clusterOk.put(clusterId, 1);
                    if (!clusterAddresses.containsKey(clusterId)) {
                        try {
                            clusterAddresses.put(clusterId, brokerStatusCollector.getAddressNames(clusterId));
                        } catch (Exception e) {
                            log.warn("Error retrieving address names for cluster {}: {}", clusterId, e.getMessage());
                        }
                    }

                    if (!clusterQueues.containsKey(clusterId)) {
                        try {
                            clusterQueues.put(clusterId, brokerStatusCollector.getQueueNames(clusterId));
                        } catch (Exception e) {
                            log.warn("Error retrieving queue names for cluster {}: {}", clusterId, e.getMessage());
                        }
                    }
                }
            }
            if (!clusterAddresses.containsKey(clusterId)) {
                clusterAddresses.put(clusterId, Collections.emptySet());
            }
            if (!clusterQueues.containsKey(clusterId)) {
                clusterQueues.put(clusterId, Collections.emptySet());
            }
            numOk += clusterOk.get(clusterId);
        }
        return numOk;
    }

    private boolean validateAddress(Address address, AddressSpace addressSpace, AddressResolver addressResolver) {
        if (!addressResolver.validate(address)) {
            return false;
        }

        boolean valid = true;
        if (addressSpace == null) {
            return valid;
        }

        if (address.getSpec().getForwarders() != null && !address.getSpec().getForwarders().isEmpty()) {
            if (addressSpace.getSpec().getConnectors() == null || addressSpace.getSpec().getConnectors().isEmpty()) {
                valid = false;
                address.getStatus().appendMessage(String.format("Unable to create forwarders: There are no connectors configured for address space '%s'", addressSpace.getMetadata().getName()));
            }

            if (!Arrays.asList("queue", "subscription").contains(address.getSpec().getType())) {
                valid = false;
                address.getStatus().appendMessage(String.format("Unable to create forwarders for address type '%s': Forwarders can only be created for address types 'queue' and 'subscription'", address.getSpec().getType()));
            }

            for (AddressSpecForwarder forwarder : address.getSpec().getForwarders()) {
                boolean found = false;
                for (AddressSpaceSpecConnector connector : addressSpace.getSpec().getConnectors()) {
                    if (forwarder.getRemoteAddress().startsWith(connector.getName())) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    valid = false;
                    address.getStatus().appendMessage(String.format("Unable to create forwarder '%s': remoteAddress '%s' is not prefixed with any connector in address space '%s'", forwarder.getName(), forwarder.getRemoteAddress(), address.getMetadata().getName()));
                }

                if ("subscription".equals(address.getSpec().getType()) && AddressSpecForwarderDirection.in.equals(forwarder.getDirection())) {
                    valid = false;
                    address.getStatus().appendMessage(String.format("Unable to create forwarder '%s': direction 'in' is not allowed on 'subscription' address type", forwarder.getName()));
                }
            }
        }
        if (!valid) {
            address.getStatus().setReady(false);
        }

        return valid;
    }

    private void processDeadletteres(Set<Address> addressSet) {
        Set<Address> deadLetters = addressSet.stream().filter(a -> "deadletter".equals(a.getSpec().getType())).collect(Collectors.toSet());
        Set<BrokerStatus> inUse = addressSet.stream().filter(a -> !"deadletter".equals(a.getSpec().getType())).flatMap(a -> a.getStatus().getBrokerStatuses().stream()).collect(Collectors.toSet());

        deadLetters.forEach(dla -> {
            List<BrokerStatus> copy = new ArrayList<>(dla.getStatus().getBrokerStatuses());
            Set<BrokerStatus> brokerStatuses = new TreeSet<>(Comparator.comparing(BrokerStatus::getContainerId));

            inUse.forEach(rs -> {
                copy.remove(rs);
                brokerStatuses.add(new BrokerStatus(rs.getClusterId(), rs.getContainerId(), BrokerState.Active));
            });

            // Any remaining brokerstatuses from the original set may have dead lettered messages remaining, so mark
            // them as Draining so the garbage collector can review and remove once empty.
            if (!copy.isEmpty()) {
                copy.forEach(s -> {
                    brokerStatuses.add(new BrokerStatus(s.getClusterId(), s.getContainerId(), BrokerState.Draining));
                });
            }
            dla.setStatus(new AddressStatusBuilder(dla.getStatus())
                    .withBrokerStatuses(new ArrayList<>(brokerStatuses))
                    .build());
        });
    }

    private boolean isPooled(AddressPlan plan) {
        for (Map.Entry<String, Double> request : plan.getResources().entrySet()) {
            if ("broker".equals(request.getKey()) && request.getValue() < 1.0) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPlansChanged(AddressResolver addressResolver, Address address) {
        AddressPlan addressPlan = addressResolver.getDesiredPlan(address);
        return !AddressPlanStatus.fromAddressPlan(addressPlan).equals(address.getStatus().getPlanStatus());
    }

    private class ProvisionState {
        private final AddressStatus status;
        private final AddressSpec addressSpec;

        public ProvisionState(AddressStatus status, AddressSpec addressSpec) {
            this.status = new AddressStatusBuilder(status).build();
            this.addressSpec = addressSpec == null ? null : new AddressSpecBuilder(addressSpec).build();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ProvisionState that = (ProvisionState) o;
            return Objects.equals(status, that.status) &&
                    Objects.equals(addressSpec, that.addressSpec);
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, addressSpec);
        }

        @Override
        public String toString() {
            return "ProvisionState{" +
                    "status=" + status +
                    ", addressSpec='" + addressSpec + '\'' +
                    '}';
        }
    }
}
