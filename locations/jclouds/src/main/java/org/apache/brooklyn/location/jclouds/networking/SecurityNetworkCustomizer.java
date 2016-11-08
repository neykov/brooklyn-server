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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import org.apache.brooklyn.location.jclouds.BasicJcloudsLocationCustomizer;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.location.jclouds.JcloudsMachineLocation;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.Networking;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.domain.SecurityGroup;
import org.jclouds.compute.domain.Template;
import org.jclouds.net.domain.IpPermission;
import org.jclouds.net.domain.IpProtocol;

import javax.annotation.Nullable;
import javax.print.attribute.standard.MediaSize;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Configures a 'security network' security group on Jclouds locations
 * <p>
 * Is based on {@link JcloudsLocationSecurityGroupCustomizer} but can be instantiated
 * in yaml e.g.
 * <p>
 * <pre>
 * {@code
 * services:
 * - type: org.apache.brooklyn.entity.software.base.EmptySoftwareProcess
 *   brooklyn.config:
 *     provisioning.properties:
 *       customizers:
 *       - $brooklyn:object:
            type: org.apache.brooklyn.location.jclouds.networking.SecurityNetworkCustomizer
            object.fields:
              networks:
                - name: api_server
 * }
 * </pre>
*/
public class SecurityNetworkCustomizer extends BasicJcloudsLocationCustomizer {

    private static final String NAME = "name";
    private static final int DEFAULT_SSH_PORT = 22;

    /**
     * Add a flag that disables this customizer.  It's isn't currently possible to add a customizer
     * based on a flag.  This flag makes it possible to write blueprints using the customizer but still
     * be able to disable it for clouds (e.g. bluebox) where the SG implementation has known issues.
     *
     * Default: true
     */
    private boolean enabled = true;


    private List<Map<String, String>> networks;


    /**
     * @param enabled set to false to disable this customizer
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void customize(JcloudsLocation location, ComputeService computeService, Template template) {
        if(!enabled) return;

        super.customize(location, computeService, template);

        for (Map<String, String> network : getNetworks()) {
            String networkName = network.get(NAME);
            if (networkName != null) {
                final JcloudsLocationSecurityGroupCustomizer instance = getInstance(networkName); // TODO + applicationId
                instance.customize(location, computeService, template);
            }
        }
    }

    @Override
    public void customize(JcloudsLocation location, ComputeService computeService, JcloudsMachineLocation machine) {
        super.customize(location, computeService, machine);
        applySecurityGroupCustomizations(location, computeService, machine);
    }

    private Collection<SecurityGroup> applySecurityGroupCustomizations(
        JcloudsLocation location, ComputeService computeService, JcloudsMachineLocation machine) {

        if(!enabled) return ImmutableList.of();

        List<SecurityGroup> result = MutableList.of();
        for (Map<String, String> network : getNetworks()) {
            String networkName = network.get(NAME);
            if (networkName != null) {
                ImmutableList.Builder<IpPermission> builder = ImmutableList.<IpPermission>builder();
                final JcloudsLocationSecurityGroupCustomizer instance = getInstance(networkName); // TODO + applicationId

                final IpPermission defaultRule = IpPermission.builder()
                    .toPort(Integer.valueOf(DEFAULT_SSH_PORT))
                    .ipProtocol(IpProtocol.ALL)
                    .cidrBlock(instance.getBrooklynCidrBlock())
                    .build();
                builder.add(defaultRule); // TODO what should go in here really?

                result.addAll(instance.addPermissionsToLocationAndReturnSecurityGroup(
                    computeService, machine, builder.build()));
            }
        }
        return result;
    }




    @VisibleForTesting
    JcloudsLocationSecurityGroupCustomizer getInstance(String customizer) {
        return JcloudsLocationSecurityGroupCustomizer.getInstance(customizer);
    }


    public List<Map<String, String>> getNetworks() {
        return networks;
    }

    public void setNetworks(List<Map<String, String>> networks) {
        this.networks = networks;
    }
}
