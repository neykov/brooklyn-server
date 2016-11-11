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

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.Callable;

import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.location.jclouds.JcloudsMachineLocation;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.jclouds.aws.AWSResponseException;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.domain.SecurityGroup;
import org.jclouds.compute.extensions.SecurityGroupExtension;
import org.jclouds.domain.Location;
import org.jclouds.net.domain.IpPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;

// TODO Rename to JcloudsSecurityGroupEditor (or even SecurityGroupEditor)
public class JcloudsLocationSecurityGroupEditor {

    private static final Logger LOG = LoggerFactory.getLogger(JcloudsLocationSecurityGroupEditor.class);

    private final Location location;
    private final ComputeService computeService;
    private final Optional<SecurityGroupExtension> securityApi;
    private final String locationId;
    private final Predicate<Exception> isExceptionRetryable;

    /**
     * Constructor.
     * @param location JClouds location where security groups will be managed.
     * @param computeService The JClouds compute service instance.
     * @param predicate A predicate indicating whether the customiser can retry a request to add a security group
     * or a rule after an throwable is thrown.
     */
    @Deprecated
    public JcloudsLocationSecurityGroupEditor(Location location, ComputeService computeService, Predicate<Exception> predicate) {
        this.location = location;
        this.computeService = computeService;
        this.locationId = this.computeService.getContext().unwrap().getId();
        this.securityApi = this.computeService.getSecurityGroupExtension(); // TODO surely check for isPresent else throw?
        this.isExceptionRetryable = checkNotNull(predicate, "predicate");
    }

    /**
     * Location - usually the regionId but could be more specific, i.e. availability zone
     */
    public JcloudsLocationSecurityGroupEditor(JcloudsLocation jcloudsLocation, Location templateLocation) {
        this(templateLocation, jcloudsLocation.getComputeService(), Predicates.<Exception>alwaysFalse());
    }

    /**
     * Location - usually the regionId but could be more specific, i.e. availability zone
     */
    public JcloudsLocationSecurityGroupEditor(JcloudsMachineLocation jcloudsLocation) {
        this(jcloudsLocation.getOptionalNode().get().getLocation(), jcloudsLocation.getParent().getComputeService(), Predicates.<Exception>alwaysFalse());
    }

    /**
     * Flag to indicate whether the given compute service has support for security groups.
     */
    public boolean hasServiceSupport() {
        return securityApi.isPresent();
    }

    /**
     * Get the location in which security groups will be created or searched.
     */
    public Location getLocation() {
        return location;
    }

    /**
     * Get the location id from the compute service (e.g. "aws-ec2").
     */
    public String getLocationId() {
        return locationId;
    }

    public Maybe<Set<SecurityGroup>> getSecurityGroupsForNode(final String nodeId) {
        if (!securityApi.isPresent()) return Maybe.absent();
        final Set<SecurityGroup> groups = securityApi.get().listSecurityGroupsForNode(nodeId);
        return Maybe.of(groups);
    }

    /**
     * TODO deleteSecurityGroup!
     */
    public Maybe<SecurityGroup> createSecurityGroup(final String name) {
        if (!securityApi.isPresent()) return Maybe.absent();

        LOG.debug("Creating security group {} in {}", name, location);
        Callable<SecurityGroup> callable = new Callable<SecurityGroup>() {
            @Override
            public SecurityGroup call() throws Exception {
                return securityApi.get().createSecurityGroup(name, location);
            }
        };
        return Maybe.of(runOperationWithRetry(callable));
    }

    public Optional<Set<SecurityGroup>> listSecurityGroupsForNode(final String nodeId) {
        if (!securityApi.isPresent()) return Optional.absent();
        return Optional.of(securityApi.get().listSecurityGroupsForNode(nodeId));
    }

    public Optional<SecurityGroup> findSecurityGroupsMatching(Predicate predicate) {
        if (!securityApi.isPresent()) return Optional.absent();
        return Iterables.tryFind(securityApi.get().listSecurityGroupsInLocation(location), predicate);
    }

    public Optional<SecurityGroup> findSecurityGroupByName(final String name) {
        return findSecurityGroupsMatching(new Predicate<SecurityGroup>() {
            @Override
            public boolean apply(final SecurityGroup input) {
                // endsWith because Jclouds prepends 'jclouds#' to security group names.
                return input.getName().endsWith(name);
            }
        });
    }

    public SecurityGroup addPermissions(SecurityGroup group, final Iterable<IpPermission> permissions) {
        SecurityGroup lastGroup = group;
        for (IpPermission permission : permissions) {
            lastGroup = addPermission(group, permission);
        }
        // TODO any of the calls could return null because of duplicate record. Fetch the new SG state ourselves in this case 
        return lastGroup;
    }

    // TODO rollback (inside sgEditor)
    public SecurityGroup addPermissions(final SecurityGroup group, final IpPermission... permissions) {
        return addPermissions(group, Arrays.asList(permissions));
    }

    // TODO sync model?
    public SecurityGroup getOrCreateSecurityGroup(String networkName) {
//        Optional<SecurityGroup> sg = findSecurityGroupByName(networkName);
//        if (sg.isPresent()) {
//            return sg.get();
//        } else {
            // TODO Amazon will behave and just return the existing ID if already exists
            // Probably not the case for other clouds
            return createSecurityGroup(networkName).get();
//        }
    }

    public SecurityGroup addPermission(final SecurityGroup group, final IpPermission permission) {
        LOG.debug("Adding permission to security group {}: {}", group.getName(), permission);
        Callable<SecurityGroup> callable = new Callable<SecurityGroup>() {
            @Override
            public SecurityGroup call() throws Exception {
                try {
                    return securityApi.get().addIpPermission(permission, group);
                } catch (Exception e) {
                    Exceptions.propagateIfFatal(e);
                    // Sometimes AWSResponseException is wrapped in an IllegalStateException
                    AWSResponseException cause = Exceptions.getFirstThrowableOfType(e, AWSResponseException.class);
                    if (cause != null) {
                        if ("InvalidPermission.Duplicate".equals(cause.getError().getCode())) {
                            // TODO don't log, that's part of the design now
                            // already exists
                            LOG.info("Permission already exists for security group; continuing (logging underlying exception at debug): permission="+permission+"; group="+group);
                            LOG.debug("Permission already exists for security group; continuing: permission="+permission+"; group="+group, e);
                            return null;
                        }
                    }

                    // TODO did something change? the whole catch clause looks wrong (before changes)
                    // TODO is there existing test coverage for these cases?
                    if (e.toString().contains("already exists")) {
                        // belt-and-braces, in case already exists
                        LOG.info("Permission already exists for security group; continuing (but unexpected exception type): permission="+permission+"; group="+group, e);
                        return null;
                    } else {
                        throw Exceptions.propagate(e);
                    }
                }
            }
        };
        return runOperationWithRetry(callable);
    }


    public SecurityGroup removePermission(final SecurityGroup group, final IpPermission permission) {
        LOG.debug("Removing permission from security group {}: {}", group.getName(), permission);
        Callable<SecurityGroup> callable = new Callable<SecurityGroup>() {
            @Override
            public SecurityGroup call() throws Exception {
                return securityApi.get().removeIpPermission(permission, group);
            }
        };
        return runOperationWithRetry(callable);
    }

    public SecurityGroup removePermissions(SecurityGroup group, final Iterable<IpPermission> permissions) {
        for (IpPermission permission : permissions) {
            group = removePermission(group, permission);
        }
        return group;
    }

    /**
     * Runs the given callable. Repeats until the operation succeeds or {@link #isExceptionRetryable} indicates
     * that the request cannot be retried.
     */
    protected <T> T runOperationWithRetry(Callable<T> operation) {
        int backoff = 64;
        Exception lastException = null;
        for (int retries = 0; retries < 100; retries++) { // TODO this will try for about 2.0e+21 years; maybe not try so hard?
            try {
                return operation.call();
            } catch (Exception e) {
                Exceptions.propagateIfFatal(e);
                lastException = e;
                if (isExceptionRetryable.apply(e)) {
                    LOG.debug("Attempt #{} failed to add security group: {}", retries + 1, e.getMessage());
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException e1) {
                        throw Exceptions.propagate(e1);
                    }
                    backoff = backoff << 1;
                } else {
                    break;
                }
            }
        }

        throw new RuntimeException("Unable to add security group rule; repeated errors from provider", lastException);
    }

    @Override
    public String toString() {
        return "JcloudsLocationSecurityGroupEditor{" +
            "location=" + location +
            ", computeService=" + computeService +
            ", securityApi=" + (securityApi.isPresent() ? securityApi.get() : "<unsupported>") +
            ", locationId='" + locationId + '\'' +
            '}';
    }
}
