/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.location.jclouds.networking;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

import org.apache.brooklyn.util.collections.MutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.UncheckedExecutionException;

import org.jclouds.aws.AWSResponseException;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.domain.SecurityGroup;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.extensions.SecurityGroupExtension;
import org.jclouds.domain.Location;
import org.jclouds.net.domain.IpPermission;
import org.jclouds.net.domain.IpProtocol;
import org.jclouds.providers.ProviderMetadata;
import org.jclouds.providers.Providers;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.core.location.geo.LocalhostExternalIpLoader;
import org.apache.brooklyn.location.jclouds.BasicJcloudsLocationCustomizer;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.location.jclouds.JcloudsLocationConfig;
import org.apache.brooklyn.location.jclouds.JcloudsLocationCustomizer;
import org.apache.brooklyn.location.jclouds.JcloudsMachineLocation;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.time.Duration;

/**
 * Configures custom security groups on Jclouds locations.
 * <p>
 * This customizer can be injected into {@link JcloudsLocation#obtainOnce} using
 * the {@link JcloudsLocationConfig#JCLOUDS_LOCATION_CUSTOMIZERS} configuration key.
 * It will be executed after the provisiioning of the {@link JcloudsMachineLocation}
 * to apply app-specific customization related to the security groups.
 * <p>
 * {@link SecurityGroupExtension} is an optional extension to the jclouds compute
 * service. It allows the manipulation of {@link SecurityGroup security groups}.
 *
 * @since 0.7.0
 */
public class JcloudsLocationSecurityGroupCustomizer extends BasicJcloudsLocationCustomizer {

    private static final Logger LOG = LoggerFactory.getLogger(JcloudsLocationSecurityGroupCustomizer.class);

    // Caches instances of JcloudsLocationSecurityGroupCustomizer by application IDs.
    private static final LoadingCache<String, JcloudsLocationSecurityGroupCustomizer> CUSTOMISERS = CacheBuilder.newBuilder()
            .build(new CacheLoader<String, JcloudsLocationSecurityGroupCustomizer>() {
                @Override
                public JcloudsLocationSecurityGroupCustomizer load(final String appContext) throws Exception {
                    return new JcloudsLocationSecurityGroupCustomizer(appContext);
                }
            });

    /** Caches the base security group that should be shared between all instances in the same Jclouds location */
    private final Cache<Location, SecurityGroup> sharedGroupCache = CacheBuilder.newBuilder().build();

    /** Caches security groups unique to instances */
    private final Cache<String, SecurityGroup> uniqueGroupCache = CacheBuilder.newBuilder().build();

    /** The context for this location customizer. */
    private final String applicationId;

    /** The CIDR for addresses that may SSH to machines. */
    private Supplier<Cidr> sshCidrSupplier;

    /**
     * A predicate indicating whether the customiser can retry a request to add a security group
     * or a rule after an throwable is thrown.
     */
    private Predicate<Exception> isExceptionRetryable = Predicates.alwaysFalse();

    protected JcloudsLocationSecurityGroupCustomizer(String applicationId) {
        // Would be better to restrict with something like LocalhostExternalIpCidrSupplier, but
        // we risk making machines inaccessible from Brooklyn when HA fails over.
        this(applicationId, Suppliers.ofInstance(new Cidr("0.0.0.0/0")));
    }

    protected JcloudsLocationSecurityGroupCustomizer(String applicationId, Supplier<Cidr> sshCidrSupplier) {
        super();
        this.applicationId = applicationId;
        this.sshCidrSupplier = sshCidrSupplier;
    }

    /**
     * Gets the customizer for the given applicationId. Multiple calls to this method with the
     * same application context will return the same JcloudsLocationSecurityGroupCustomizer instance.
     * @param applicationId An identifier for the application the customizer is to be used for
     * @return the unique customizer for the given context
     */
    public static JcloudsLocationSecurityGroupCustomizer getInstance(String applicationId) {
        return CUSTOMISERS.getUnchecked(applicationId);
    }

    /**
     * Gets a customizer for the given entity's application. Multiple calls to this method with entities
     * in the same application will return the same JcloudsLocationSecurityGroupCustomizer instance.
     * @param entity The entity the customizer is to be used for
     * @return the unique customizer for the entity's owning application
     */
    public static JcloudsLocationSecurityGroupCustomizer getInstance(Entity entity) {
        return getInstance(entity.getApplicationId());
    }

    /**
     * @param predicate
     *          A predicate whose return value indicates whether a request to add a security group
     *          or permission may be retried after its input {@link Exception} was thrown.
     * @return this
     */
    public JcloudsLocationSecurityGroupCustomizer setRetryExceptionPredicate(Predicate<Exception> predicate) {
        this.isExceptionRetryable = checkNotNull(predicate, "predicate");
        return this;
    }

    /**
     * @param cidrSupplier A supplier returning a CIDR for hosts that are allowed to SSH to locations.
     */
    public JcloudsLocationSecurityGroupCustomizer setSshCidrSupplier(Supplier<Cidr> cidrSupplier) {
        this.sshCidrSupplier = checkNotNull(cidrSupplier, "cidrSupplier");
        return this;
    }

    /** @see #addPermissionsToLocation(JcloudsMachineLocation, java.lang.Iterable) */
    public JcloudsLocationSecurityGroupCustomizer addPermissionsToLocation(final JcloudsMachineLocation location, IpPermission... permissions) {
        addPermissionsToLocation(location, ImmutableList.copyOf(permissions));
        return this;
    }

    /** @see #addPermissionsToLocation(JcloudsMachineLocation, java.lang.Iterable) */
    public JcloudsLocationSecurityGroupCustomizer addPermissionsToLocation(final JcloudsMachineLocation location, SecurityGroupDefinition securityGroupDefinition) {
        addPermissionsToLocation(location, securityGroupDefinition.getPermissions());
        return this;
    }

    private SecurityGroup getMachineUniqueSecurityGroup(final String nodeId, final JcloudsLocationSecurityGroupEditor groupEditor) {
        // Expect to have two security groups on the node: one shared between all nodes in the location,
        // that is cached in sharedGroupCache, and one created by Jclouds that is unique to the node.
        // Relies on customize having been called before. This should be safe because the arguments
        // needed to call this method are not available until post-instance creation.
        SecurityGroup machineUniqueSecurityGroup;
        Tasks.setBlockingDetails("Loading unique security group for node: " + nodeId);
        try {
            machineUniqueSecurityGroup = uniqueGroupCache.get(nodeId, new Callable<SecurityGroup>() {
                @Override public SecurityGroup call() throws Exception {
                    SecurityGroup sg = getUniqueSecurityGroupForNodeCachingSharedGroupIfPreviouslyUnknown(nodeId, groupEditor);
                    if (sg == null) {
                        throw new IllegalStateException("Failed to find machine-unique group on node: " + nodeId);
                    }
                    return sg;
                }
            });
        } catch (UncheckedExecutionException e) {
            throw Throwables.propagate(new Exception(e.getCause()));
        } catch (ExecutionException e) {
            throw Throwables.propagate(new Exception(e.getCause()));
        } finally {
            Tasks.resetBlockingDetails();
        }
        return machineUniqueSecurityGroup;
    }

    /**
     * Applies the given security group permissions to the given location.
     * <p>
     * Takes no action if the location's compute service does not have a security group extension.
     * <p>
     * The {@code synchronized} block is to serialize the permission changes, preventing race
     * conditions in some clouds. If multiple customizations of the same group are done in parallel
     * the changes may not be picked up by later customizations, meaning the same rule could possibly be
     * added twice, which would fail. A finer grained mechanism would be preferable here, but
     * we have no access to the information required, so this brute force serializing is required.
     *
     * @param location Location to gain permissions
     * @param permissions The set of permissions to be applied to the location
     */
    public JcloudsLocationSecurityGroupCustomizer addPermissionsToLocation(final JcloudsMachineLocation location, final Iterable<IpPermission> permissions) {
        addPermissionsToLocationAndReturnSecurityGroup(location, permissions);
        return this;
    }

    public Collection<SecurityGroup> addPermissionsToLocationAndReturnSecurityGroup(final JcloudsMachineLocation location, final Iterable<IpPermission> permissions) {
        synchronized (JcloudsLocationSecurityGroupCustomizer.class) {
            String nodeId = location.getNode().getId();
            final Location nodeLocation = location.getNode().getLocation();
            ComputeService computeService = location.getParent().getComputeService();

            final JcloudsLocationSecurityGroupEditor groupEditor = createSecurityGroupEditor(computeService, nodeLocation);
            return addPermissionsInternal(permissions, nodeId, groupEditor).values();
        }
    }

    /**
     * Removes the given security group permissions from the given node.
     * <p>
     * Takes no action if the compute service does not have a security group extension.
     * @param location Location of the node to remove permissions from
     * @param permissions The set of permissions to be removed from the node
     */
    public void removePermissionsFromLocation(final JcloudsMachineLocation location, final Iterable<IpPermission> permissions) {
        synchronized (JcloudsLocationSecurityGroupCustomizer.class) {
            removePermissionsInternal(location, permissions);
        }
    }

    /**
     * Removes the given security group permissions from the given node.
     * <p>
     * Takes no action if the compute service does not have a security group extension.
     * @param location Location of the node to remove permissions from
     * @param permissions The set of permissions to be removed from the node
     */
    private void removePermissionsInternal(final JcloudsMachineLocation location, Iterable<IpPermission> permissions) {
        ComputeService computeService = location.getParent().getComputeService();
        String nodeId = location.getNode().getId();

        if (!computeService.getSecurityGroupExtension().isPresent()) {
            LOG.warn("Security group extension for {} absent; cannot update node {} with {}",
                    new Object[] {computeService, nodeId, permissions});
            return;
        }

        final JcloudsLocationSecurityGroupEditor tool = createSecurityGroupEditor(computeService, location.getNode().getLocation());
        SecurityGroup machineUniqueSecurityGroup = getMachineUniqueSecurityGroup(nodeId, tool);
        tool.removePermissions(machineUniqueSecurityGroup, permissions);
    }



    /**
     * Applies the given security group permissions to the given node with the given compute service.
     * <p>
     * Takes no action if the compute service does not have a security group extension.
     * @param permissions The set of permissions to be applied to the node
     * @param nodeId The id of the node to update
     * @param groupEditor An editor for security groups in this node's location.
     *
     * TODO By design this method returns a collection with one member, the node specific security group;
     * ideally it would be good to review the call hierarchy and change the method to return just the group,
     * not a collection.
     */
    private Map<String, SecurityGroup> addPermissionsInternal(Iterable<IpPermission> permissions, final String nodeId,
                                                      final JcloudsLocationSecurityGroupEditor groupEditor) {

        if (!groupEditor.hasServiceSupport()) {
            LOG.warn("Security group support for {} absent; cannot update node {} with {}",
                new Object[] {groupEditor, nodeId, permissions});
            return ImmutableMap.of();
        }

        // Expect to have two security groups on the node: one shared between all nodes in the location,
        // that is cached in sharedGroupCache, and one created by Jclouds that is unique to the node.
        // Relies on customize having been called before. This should be safe because the arguments
        // needed to call this method are not available until post-instance creation.
        SecurityGroup machineUniqueSecurityGroup = getMachineUniqueSecurityGroup(nodeId, groupEditor);
        MutableList<IpPermission> newPermissions = MutableList.copyOf(permissions);
        Iterables.removeAll(newPermissions, machineUniqueSecurityGroup.getIpPermissions());
        machineUniqueSecurityGroup = groupEditor.addPermissions(machineUniqueSecurityGroup, newPermissions);
        return MutableMap.of(machineUniqueSecurityGroup.getId(), machineUniqueSecurityGroup);
    }

    /**
     * Loads the security groups attached to the node with the given ID and returns the group
     * that is unique to the node, per the application context. This method will also update
     * {@link #sharedGroupCache} if no mapping for the shared group's location previously
     * existed (e.g. Brooklyn was restarted and rebound to an existing application).
     *
     * Notice that jclouds will attach 2 securityGroups to the node if the locationId is `aws-ec2` so it needs to
     * look for the uniqueSecurityGroup rather than the shared securityGroup.
     *
     * @param nodeId The id of the node in question
     * @param groupEditor The id of the node in question
     * @return the security group unique to the given node, or null if one could not be determined.
     */
    private SecurityGroup getUniqueSecurityGroupForNodeCachingSharedGroupIfPreviouslyUnknown(String nodeId,
             final JcloudsLocationSecurityGroupEditor groupEditor) {

        final Optional<Set<SecurityGroup>> optional = groupEditor.listSecurityGroupsForNode(nodeId);
        if (!optional.isPresent()) return null;

        Set<SecurityGroup> groupsOnNode = optional.get();
        if(groupsOnNode.isEmpty()){
            return null;
        }

        SecurityGroup unique;
        if (groupEditor.getLocationId().equals("aws-ec2")) {
            if (groupsOnNode.size() == 2) {
                String expectedSharedName = getNameForSharedSecurityGroup();
                Iterator<SecurityGroup> it = groupsOnNode.iterator();
                SecurityGroup shared = it.next();
                if (shared.getName().endsWith(expectedSharedName)) {
                    unique = it.next();
                } else {
                    unique = shared;
                    shared = it.next();
                }
                if (!shared.getName().endsWith(expectedSharedName)) {
                    LOG.warn("Couldn't determine which security group is shared between instances in app {}. Expected={}, found={}",
                            new Object[]{ applicationId, expectedSharedName, groupsOnNode });
                    return null;
                }
                // Shared entry might be missing if Brooklyn has rebound to an application
                SecurityGroup old = sharedGroupCache.asMap().putIfAbsent(shared.getLocation(), shared);
                LOG.info("Loaded unique security group for node {} (in {}): {}",
                        new Object[]{nodeId, applicationId, unique});
                if (old == null) {
                    LOG.info("Proactively set shared group for app {} to: {}", applicationId, shared);
                }
                return unique;
            } else {
                LOG.warn("Expected to find two security groups on node {} in app {} (one shared, one unique). Found {}: {}",
                        new Object[]{ nodeId, applicationId, groupsOnNode.size(), groupsOnNode });
            }
        }
        return Iterables.getOnlyElement(groupsOnNode);
    }

    /**
     * Replaces security groups configured on the given template with one that allows
     * SSH access on port 22 and allows communication on all ports between machines in
     * the same group. Security groups are reused when templates have equal
     * {@link org.jclouds.compute.domain.Template#getLocation locations}.
     * <p>
     * This method is called by Brooklyn when obtaining machines, as part of the
     * {@link JcloudsLocationCustomizer} contract. It
     * should not be called from anywhere else.
     *
     * @param location The Brooklyn location that has called this method while obtaining a machine
     * @param computeService The compute service being used by the location argument to provision a machine
     * @param template The machine template created by the location argument
     */
    @Override
    public void customize(JcloudsLocation location, ComputeService computeService, Template template) {
        if (!computeService.getSecurityGroupExtension().isPresent()) {
            LOG.warn("Security group extension for {} absent; cannot configure security groups in context: {}", computeService, applicationId);
        } else if (template.getLocation() == null) {
            LOG.warn("No location has been set on {}; cannot configure security groups in context: {}", template, applicationId);
        } else {
            LOG.info("Configuring security groups on location {} in context {}", location, applicationId);
            JcloudsLocationSecurityGroupEditor groupEditor = createSecurityGroupEditor(computeService, template.getLocation());
            setSecurityGroupOnTemplate(location, template, groupEditor);
        }
    }

    private JcloudsLocationSecurityGroupEditor createSecurityGroupEditor(ComputeService computeService, Location location) {
        return new JcloudsLocationSecurityGroupEditor(location, computeService, isExceptionRetryable);
    }

    private void setSecurityGroupOnTemplate(final JcloudsLocation location, final Template template, final JcloudsLocationSecurityGroupEditor groupEditor) {
        SecurityGroup shared;
        Tasks.setBlockingDetails("Loading security group shared by instances in " + template.getLocation() +
                " in app " + applicationId);
        try {
            shared = sharedGroupCache.get(template.getLocation(), new Callable<SecurityGroup>() {
                @Override public SecurityGroup call() throws Exception {
                    return getOrCreateSharedSecurityGroup(template.getLocation(), groupEditor);
                }
            });
        } catch (ExecutionException e) {
            throw Throwables.propagate(new Exception(e.getCause()));
        } finally {
            Tasks.resetBlockingDetails();
        }

        Set<String> originalGroups = template.getOptions().getGroups();
        template.getOptions().securityGroups(shared.getName());
        if (!originalGroups.isEmpty()) {
            LOG.info("Replaced configured security groups: configured={}, replaced with={}", originalGroups, template.getOptions().getGroups());
        } else {
            LOG.debug("Configured security groups at {} to: {}", location, template.getOptions().getGroups());
        }
    }

    /**
     * Loads the security group to be shared between nodes in the same application in the
     * given Location. If no such security group exists it is created.
     *
     * @param location The location in which the security group will be found
     * @param securityApi The API to use to list and create security groups
     * @return the security group to share between instances in the given location in this application
     */
    private SecurityGroup getOrCreateSharedSecurityGroup(Location location, JcloudsLocationSecurityGroupEditor groupEditor) {
        final String groupName = getNameForSharedSecurityGroup();
        // Could sort-and-search if straight search is too expensive
        Optional<SecurityGroup> shared = groupEditor.findSecurityGroupByName(groupName);
        if (shared.isPresent()) {
            LOG.info("Found existing shared security group in {} for app {}: {}",
                    new Object[]{location, applicationId, groupName});
            return shared.get();
        } else {
            LOG.info("Creating new shared security group in {} for app {}: {}",
                    new Object[]{location, applicationId, groupName});
            return createBaseSecurityGroupInLocation(groupName, groupEditor);
        }
    }

    /**
     * Creates a security group with rules to:
     * <ul>
     *     <li>Allow SSH access on port 22 from the world</li>
     *     <li>Allow TCP, UDP and ICMP communication between machines in the same group</li>
     * </ul>
     *
     * It needs to consider locationId as port ranges and groupId are cloud provider-dependent e.g openstack nova
     * wants from 1-65535 while aws-ec2 accepts from 0-65535.
     *
     *
     * @param groupName The name of the security group to create
     * @param securityApi The API to use to create the security group
     *
     * @return the created security group
     */
    private SecurityGroup createBaseSecurityGroupInLocation(String groupName, JcloudsLocationSecurityGroupEditor groupEditor) {
        SecurityGroup group = groupEditor.createSecurityGroup(groupName).get();

        String groupId = group.getProviderId();
        int fromPort = 0;
        if (isOpenstackNova(groupEditor.getLocation())) {
            groupId = group.getId();
            fromPort = 1;
        }
        // Note: For groupName to work with GCE we also need to tag the machines with the same ID.
        // See sourceTags section at https://developers.google.com/compute/docs/networking#firewalls
        IpPermission.Builder allWithinGroup = IpPermission.builder()
                .groupId(groupId)
                .fromPort(fromPort)
                .toPort(65535);
        group = groupEditor.addPermission(group, allWithinGroup.ipProtocol(IpProtocol.TCP).build());
        group = groupEditor.addPermission(group, allWithinGroup.ipProtocol(IpProtocol.UDP).build());
        if (!isAzure(groupEditor.getLocation())) {
            group = groupEditor.addPermission(group, allWithinGroup.ipProtocol(IpProtocol.ICMP).fromPort(-1).toPort(-1).build());
        }

        IpPermission sshPermission = IpPermission.builder()
                .fromPort(22)
                .toPort(22)
                .ipProtocol(IpProtocol.TCP)
                .cidrBlock(getBrooklynCidrBlock())
                .build();
        group = groupEditor.addPermission(group, sshPermission);

        return group;
    }

    private Set<String> getJcloudsLocationIds(final String jcloudsApiId) {
        return getJcloudsLocationIds(ImmutableList.of(jcloudsApiId));
    }
    
    private Set<String> getJcloudsLocationIds(final Collection<? extends String> jcloudsApiIds) {
        Set<String> openstackNovaProviders = FluentIterable.from(Providers.all())
                .filter(new Predicate<ProviderMetadata>() {
            @Override
            public boolean apply(ProviderMetadata providerMetadata) {
                return jcloudsApiIds.contains(providerMetadata.getApiMetadata().getId());
            }
        }).transform(new Function<ProviderMetadata, String>() {
            @Nullable
            @Override
            public String apply(ProviderMetadata input) {
                return input.getId();
            }
        }).toSet();

        return new ImmutableSet.Builder<String>()
                .addAll(openstackNovaProviders)
                .addAll(jcloudsApiIds)
                .build();
    }

    private boolean isOpenstackNova(Location location) {
        Set<String> computeIds = getJcloudsLocationIds(ImmutableList.of("openstack-nova", "openstack-mitaka-nova", "openstack-devtest-compute"));
        return location.getParent() != null && Iterables.contains(computeIds, location.getParent().getId());
    }
    
    private boolean isAzure(Location location) {
        Set<String> computeIds = getJcloudsLocationIds("azurecompute");
        return location.getParent() != null && Iterables.contains(computeIds, location.getParent().getId());
    }
    


    /** @return the CIDR block used to configure Brooklyn's in security groups */
    public String getBrooklynCidrBlock() {
        return sshCidrSupplier.get().toString();
    }

    /**
     * @return The name to be used by security groups that will be shared between machines
     *         in the same location for this instance's application context.
     */
    @VisibleForTesting
    String getNameForSharedSecurityGroup() {
        return "brooklyn-" + applicationId.toLowerCase() + "-shared";
    }

    /**
     * Invalidates all entries in {@link #sharedGroupCache} and {@link #uniqueGroupCache}.
     * Use to simulate the effects of rebinding Brooklyn to a deployment.
     */
    @VisibleForTesting
    void clearSecurityGroupCaches() {
        LOG.info("Clearing security group caches");
        sharedGroupCache.invalidateAll();
        uniqueGroupCache.invalidateAll();
    }


    /**
     * @return
     *      A predicate that is true if an exception contains an {@link org.jclouds.aws.AWSResponseException}
     *      whose error code is either <code>InvalidGroup.InUse</code>, <code>DependencyViolation</code> or
     *      <code>RequestLimitExceeded</code>.
     */
    public static Predicate<Exception> newAwsExceptionRetryPredicate() {
        return new AwsExceptionRetryPredicate();
    }

    private static class AwsExceptionRetryPredicate implements Predicate<Exception> {
        // Error reference: http://docs.aws.amazon.com/AWSEC2/latest/APIReference/errors-overview.html
        private static final Set<String> AWS_ERRORS_TO_RETRY = ImmutableSet.of(
                "InvalidGroup.InUse", "DependencyViolation", "RequestLimitExceeded");

        @Override
        public boolean apply(Exception input) {
            @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
            AWSResponseException exception = Exceptions.getFirstThrowableOfType(input, AWSResponseException.class);
            if (exception != null) {
                String code = exception.getError().getCode();
                return AWS_ERRORS_TO_RETRY.contains(code);
            }
            return false;
        }
    }

    /**
     * A supplier of CIDRs that loads the external IP address of the localhost machine.
     */
    private static class LocalhostExternalIpCidrSupplier implements Supplier<Cidr> {

        private volatile Cidr cidr;

        @Override
        public Cidr get() {
            Cidr local = cidr;
            if (local == null) {
                synchronized (this) {
                    local = cidr;
                    if (local == null) {
                        String externalIp = LocalhostExternalIpLoader.getLocalhostIpWithin(Duration.seconds(5));
                        cidr = local = new Cidr(externalIp + "/32");
                    }
                }
            }
            return local;
        }

    }

}
