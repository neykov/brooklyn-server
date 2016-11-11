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

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.sensor.Sensor;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.mgmt.BrooklynTaskTags;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.location.jclouds.BasicJcloudsLocationCustomizer;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableSet;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.flags.TypeCoercions;
import org.apache.brooklyn.util.core.task.Tasks;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.domain.SecurityGroup;
import org.jclouds.compute.domain.Template;
import org.jclouds.net.domain.IpPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.TypeToken;

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
                - api-server
              network-ingress:
                - network: api-server
                  exposing: [...]
 * }
 * </pre>
*/
// TODO call editor in tasks so its visible in UI
// TODO assumes all entities in the same VPC
// TODO check for sg support on cloud
// TODO delete security groups; could become complex as need to delete them in the right order;
//      can delete only after all network-ingress rules and instances referncing the sg are gone
// TODO escape network names
// TODO support protocols in ingress rules
// TODO resolve values
// TODO short-lived cache (seconds) to avoid repeating the same operations for each cluster entity

// TODO Rate limiting problems?
// FAIL security group us-east-1/jclouds#svet-api-server-j0ez01mf62 is not available after creating" - but it's there in the console!
// FAIL AWSResponseException: request POST https://ec2.us-east-1.amazonaws.com/ HTTP/1.1 failed with code 400, error: AWSError{requestId='7e206738-6142-44a7-8935-554d831abce8', requestToken='null', code='InvalidParameterValue', message='Value () for parameter groupId is invalid. The value cannot be empty', context='{Response=, Errors=}'}"
public class SecurityNetworkCustomizer extends BasicJcloudsLocationCustomizer {
    private static final Logger log = LoggerFactory.getLogger(SecurityNetworkCustomizer.class);
    
    @SuppressWarnings("serial")
    public static final ConfigKey<Collection<String>> NETWORKS = ConfigKeys.newConfigKey(new TypeToken<Collection<String>>() {},
            "networks", "A list of network groups to attach the entity to");
    
    @SuppressWarnings("serial")
    public  static final ConfigKey<Collection<? extends Map<String, ?>>> NETWORKS_INGRESS = ConfigKeys.newConfigKey(new TypeToken<Collection<? extends Map<String, ?>>>() {},
            "networks-ingress", "A list of network groups to attach the entity to");

    /**
     * Add a flag that disables this customizer.  It's isn't currently possible to add a customizer
     * based on a flag.  This flag makes it possible to write blueprints using the customizer but still
     * be able to disable it for clouds (e.g. bluebox) where the SG implementation has known issues.
     *
     * Default: true
     */
    private boolean enabled = true;

    // Needed for initializer
    // TODO document and even remove this requirement, why not same as entities?
    public SecurityNetworkCustomizer(ConfigBag config) {
        super(config);
    }

    // To be used for customizer behaviour
    public SecurityNetworkCustomizer() {
    }

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

        Tasks.setBlockingDetails("Initializing network accessibility");
        try {
            addSecurityGroups(location, template);
        } finally {
            Tasks.resetBlockingDetails();
        }
    }

    protected void addSecurityGroups(JcloudsLocation location, Template template) {
        JcloudsLocationSecurityGroupEditor sgEditor = new JcloudsLocationSecurityGroupEditor(location, template.getLocation());
        SecurityGroupRules sgRules = SecurityGroupRules.newInstance(template.getLocation());
        Collection<String> newGroupNames = MutableList.of();
        EntityInternal entity = getContextEntity();
        Set<String> networkNames = MutableSet.of("management");
        Collection<String> networks = params.get(NETWORKS);
        if (networks != null) {
            for (String network : networks) {
                networkNames.add(network);
            }
        }
        // TODO template.getOptions().getInboundPorts() - use for public & management
        for (String networkName : networkNames) {
            SecurityGroup sg = sgEditor.getOrCreateSecurityGroup(getAppNetworkName(networkName, entity));
            Collection<IpPermission> rule = getNetworkRule(sgRules, networkName, sg);
            sgEditor.addPermissions(sg, rule);
            newGroupNames.add(sg.getName());
        }
        
        Collection<? extends Map<String, ?>> networkIngress = params.get(NETWORKS_INGRESS);
        if (networkIngress != null && networkIngress.size() > 0) {
            SecurityGroup sg = sgEditor.createSecurityGroup("ingress-networks-" + entity.getId() + "-" + entity.getApplicationId()).get();
            for (Map<String, ?> ingress : networkIngress) {
                String name = (String) ingress.get("network");
                // TODO add conversion String -> Collection
                @SuppressWarnings("serial")
                Collection<Object> ports = TypeCoercions.coerce(ingress.get("exposing"), new TypeToken<Collection<Object>>() {});
                if (name != null && ports != null) {
                    for (Object port : ports) {
                        // TODO DSL support (deferred values)
                        Integer portValue = getSensorValue(entity, port);
                        if (portValue != null) {
                            // TODO local cache for the groups
                            Collection<IpPermission> rule = sgRules.allFromOnPort(sgEditor.getOrCreateSecurityGroup(getAppNetworkName(name, entity)), portValue);
                            sgEditor.addPermissions(sg, rule);
                        } else {
                            log.warn("Entity " + entity + " requests port " + port + " to be exposed to network " + name + " but the sensor is not populated yet. Ignoring.");
                        }
                    }
                    newGroupNames.add(sg.getName());
                }
            }
        }
        Set<String> existingSg = template.getOptions().getGroups();
        template.getOptions().securityGroups(ImmutableSet.<String>builder().addAll(existingSg).addAll(newGroupNames).build());
    }

    protected Integer getSensorValue(EntityInternal entity, Object port) {
        String portStr = port.toString();
        Sensor<?> portSensor = entity.getEntityType().getSensor(portStr);
        AttributeSensor<Integer> attrSensor;
        if (portSensor != null) {
            // TODO may be should return AttributeSensor<?> and coerce the sensor value
            @SuppressWarnings("unchecked")
            AttributeSensor<Integer> castedSensor = (AttributeSensor<Integer>) portSensor;
            attrSensor = castedSensor;
        } else {
            Integer portNumeric = TypeCoercions.coerce(port, Integer.class);
            if (portNumeric != null) {
                return portNumeric;
            } else {
                attrSensor = Sensors.newIntegerSensor(portStr);
            }
        }
        return entity.sensors().get(attrSensor);
    }

    protected String getAppNetworkName(String networkName, EntityInternal entity) {
        return networkName + "-" + entity.getApplicationId();
    }

    protected Collection<IpPermission> getNetworkRule(SecurityGroupRules sgRules, String networkName, SecurityGroup sg) {
        Collection<IpPermission> rule;
        if (networkName.equalsIgnoreCase("public") || networkName.equalsIgnoreCase("management")) {
            // TODO restrict to inbountPorts only and Brooklyn source IP for management
            rule = sgRules.everything();
        } else {
            rule = sgRules.allFrom(sg);
        }
        return rule;
    }
    
    protected final static EntityInternal getContextEntity() {
        return (EntityInternal) BrooklynTaskTags.getTargetOrContextEntity(Tasks.current());
    }

}
