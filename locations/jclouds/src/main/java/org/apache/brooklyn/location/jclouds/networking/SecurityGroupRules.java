/*
 * Copyright 2016 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.brooklyn.location.jclouds.networking;

import java.util.Collection;
import java.util.Set;

import javax.annotation.Nullable;

import org.apache.brooklyn.core.location.PortRanges;
import org.apache.brooklyn.util.net.Cidr;
import org.jclouds.compute.domain.SecurityGroup;
import org.jclouds.domain.Location;
import org.jclouds.net.domain.IpPermission;
import org.jclouds.net.domain.IpProtocol;
import org.jclouds.providers.ProviderMetadata;
import org.jclouds.providers.Providers;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

public class SecurityGroupRules {
    private Location location;

    public static SecurityGroupRules newInstance(Location location) {
        return new SecurityGroupRules(location);
    }

    protected SecurityGroupRules(Location location) {
        this.location = location;
    }

    public Collection<IpPermission> everything() {
        return ImmutableList.<IpPermission>builder()
                .add(everything(IpProtocol.TCP))
                .add(everything(IpProtocol.UDP))
                .addAll(optionalIcmp(everything(IpProtocol.ICMP)))
                .build();
    }

    public IpPermission everything(IpProtocol proto) {
        return allFromBuilder(proto)
                .cidrBlock(Cidr.ANYWHERE.toString())
                .build();
    }

    public IpPermission allFrom(SecurityGroup sg, IpProtocol proto) {
        return allFromBuilder(proto)
                .groupId(getProviderId(sg))
                .build();
    }

    public Collection<IpPermission> allFrom(SecurityGroup sg) {
        return ImmutableList.<IpPermission>builder()
                .add(allFrom(sg, IpProtocol.TCP))
                .add(allFrom(sg, IpProtocol.UDP))
                .addAll(allFromIfSupported(sg, IpProtocol.ICMP))
                .build();
    }

    public Collection<IpPermission> allFromOnPort(SecurityGroup sg, int port) {
        return ImmutableList.<IpPermission>builder()
                .add(allFromOnPortRange(sg, IpProtocol.TCP, port, port))
                .add(allFromOnPortRange(sg, IpProtocol.UDP, port, port))
                .addAll(allFromIfSupported(sg, IpProtocol.ICMP))
                .build();
    }

    public IpPermission allFromOnPort(SecurityGroup sg, IpProtocol proto, int port) {
        return allFromOnPortRange(sg, proto, port, port);
    }

    public IpPermission allFromOnPortRange(SecurityGroup sg, IpProtocol proto, int startPort, int endPort) {
        return IpPermission.builder()
                .groupId(getProviderId(sg))
                .fromPort(startPort)
                .toPort(endPort)
                .ipProtocol(proto)
                .build();
    }

    public Collection<IpPermission> allFromIfSupported(SecurityGroup sg, IpProtocol proto) {
        return optionalIcmp(allFrom(sg, proto));
    }

    public IpPermission allFrom(Cidr cidr, IpProtocol proto) {
        return allFromBuilder(proto)
                .cidrBlock(cidr.toString())
                .build();
    }

    public Collection<IpPermission> allFromIfSupported(Cidr cidr, IpProtocol proto) {
        return optionalIcmp(allFrom(cidr, proto));
    }

    public Collection<IpPermission> allFrom(Cidr cidr) {
        return ImmutableList.<IpPermission>builder()
                .add(allFrom(cidr, IpProtocol.TCP))
                .add(allFrom(cidr, IpProtocol.UDP))
                .addAll(allFromIfSupported(cidr, IpProtocol.ICMP))
                .build();
    }

    private Collection<IpPermission> optionalIcmp(IpPermission allFrom) {
        if (allFrom.getIpProtocol() != IpProtocol.ICMP || !isAzure(location)) {
            return ImmutableList.of(allFrom);
        } else {
            return ImmutableList.of();
        }
    }

    private IpPermission.Builder allFromBuilder(IpProtocol proto) {
        return allFromBuilderOnPort(proto,
                // why -1?
                proto == IpProtocol.ICMP ? -1 : getStartPort(),
                proto == IpProtocol.ICMP ? -1 : PortRanges.MAX_PORT);
    }

    private IpPermission.Builder allFromBuilderOnPort(IpProtocol proto, int startPort, int endPort) {
        return IpPermission.builder()
                .fromPort(startPort)
                .toPort(endPort)
                .ipProtocol(proto);
    }

    // https://issues.apache.org/jira/browse/BROOKLYN-99
    private String getProviderId(SecurityGroup sg) {
        if (isOpenstackNova(location)) {
            return sg.getId();
        } else {
            return sg.getProviderId();
        }
    }

    private int getStartPort() {
        if (isOpenstackNova(location)) {
            return 1;
        } else {
            return 0;
        }
    }

    static boolean isOpenstackNova(Location location) {
        Set<String> computeIds = getJcloudsLocationIds(ImmutableList.of("openstack-nova", "openstack-mitaka-nova", "openstack-devtest-compute"));
        return location.getParent() != null && Iterables.contains(computeIds, location.getParent().getId());
    }
    
    static boolean isAzure(Location location) {
        Set<String> computeIds = getJcloudsLocationIds("azurecompute");
        return location.getParent() != null && Iterables.contains(computeIds, location.getParent().getId());
    }
    
    private static Set<String> getJcloudsLocationIds(final String jcloudsApiId) {
        return getJcloudsLocationIds(ImmutableList.of(jcloudsApiId));
    }
    
    private static Set<String> getJcloudsLocationIds(final Collection<? extends String> jcloudsApiIds) {
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
}
