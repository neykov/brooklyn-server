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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
public class SecurityNetworkLiveTest extends AbstractEc2LiveTest {

    @Override
    protected void doTest(Location loc) throws Exception {
        SecurityNetworkCustomizer networkInitializer = new SecurityNetworkCustomizer();
        networkInitializer.config().set(SecurityNetworkCustomizer.NETWORKS, ImmutableList.<String>of(
                "svet-api-server"));
        networkInitializer.config().set(SecurityNetworkCustomizer.NETWORKS_INGRESS, ImmutableList.<Map<String, Object>>of(
                ImmutableMap.<String, Object>of(
                        "network", "svet-api-server",
                        "exposing", ImmutableList.of("http.port")),
                ImmutableMap.<String, Object>of(
                        "network", "svet-external",
                        "exposing", ImmutableList.of("http.port"))));
        SecuredEmptySoftwareProcess entity = app.createAndManageChild(EntitySpec.create(SecuredEmptySoftwareProcess.class)
                .configure(SecuredEmptySoftwareProcess.HTTP_PORT, PortRanges.fromInteger(6446))
                .addInitializer(networkInitializer));
        entity.sensors().set(SecuredEmptySoftwareProcess.HTTP_PORT, 6446);
        app.start(ImmutableList.of(loc));
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
        public static final PortAttributeSensorAndConfigKey HTTP_PORT = ConfigKeys.newPortSensorAndConfigKey("http.port", null);
    }

    public static class SecuredEmptySoftwareProcessImpl extends EmptySoftwareProcessImpl implements SecuredEmptySoftwareProcess {
    }

}
