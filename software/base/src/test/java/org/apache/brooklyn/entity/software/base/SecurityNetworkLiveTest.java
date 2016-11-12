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
package org.apache.brooklyn.entity.software.base;

import java.util.Map;

import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.location.PortRanges;
import org.apache.brooklyn.core.sensor.PortAttributeSensorAndConfigKey;
import org.apache.brooklyn.entity.AbstractEc2LiveTest;
import org.apache.brooklyn.location.jclouds.networking.SecurityNetworkCustomizer;
import org.apache.brooklyn.util.core.config.ConfigBag;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

// TODO move to brooklyn-locations-jclouds
public class SecurityNetworkLiveTest extends AbstractEc2LiveTest {

    @Override
    protected void doTest(Location loc) throws Exception {
        ConfigBag config = ConfigBag.newInstance();
        config.put(SecurityNetworkCustomizer.NETWORKS, ImmutableList.<String>of(
                "svet-api-server"));
        config.put(SecurityNetworkCustomizer.NETWORKS_INGRESS, ImmutableList.<Map<String, Object>>of(
                ImmutableMap.<String, Object>of(
                        "network", "svet-api-server",
                        "exposing", ImmutableList.of("http.port")),
                ImmutableMap.<String, Object>of(
                        "network", "svet-external",
                        "exposing", ImmutableList.of("http.port"))));
        app.createAndManageChild(EntitySpec.create(SecuredEmptySoftwareProcess.class)
                .addInitializer(new SecurityNetworkCustomizer(config)));
        app.start(ImmutableList.of(loc));
        // TODO confirm SG creation - currently manual through AWS console
    }

    @Override
    public void test_Debian_6() throws Exception {
    }

    @Override
    public void test_Debian_7_2() throws Exception {
    }

    @Override
    public void test_Ubuntu_10_0() throws Exception {
    }

    @Override
    public void test_Ubuntu_12_0() throws Exception {
        super.test_Ubuntu_12_0();
    }

    @Override
    public void test_CentOS_6_3() throws Exception {
    }

    @Override
    public void test_CentOS_5() throws Exception {
    }

    @Override
    public void test_Red_Hat_Enterprise_Linux_6() throws Exception {
    }

    @Override
    public void test_Suse_11sp3() throws Exception {
    }
    
    @ImplementedBy(SecuredEmptySoftwareProcessImpl.class)
    public static interface SecuredEmptySoftwareProcess extends EmptySoftwareProcess {
        public static final PortAttributeSensorAndConfigKey HTTP_PORT = ConfigKeys.newPortSensorAndConfigKey(
                "http.port", null, PortRanges.fromInteger(6446));
    }

    public static class SecuredEmptySoftwareProcessImpl extends EmptySoftwareProcessImpl implements SecuredEmptySoftwareProcess {
    }

}
