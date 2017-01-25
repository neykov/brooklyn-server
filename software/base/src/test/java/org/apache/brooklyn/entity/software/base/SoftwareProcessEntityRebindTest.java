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
package org.apache.brooklyn.entity.software.base;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.location.MachineProvisioningLocation;
import org.apache.brooklyn.api.location.NoMachinesAvailableException;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.BrooklynConfigKeys;
import org.apache.brooklyn.core.entity.EntityAsserts;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic.ServiceProblemsLogic;
import org.apache.brooklyn.core.location.AbstractLocation;
import org.apache.brooklyn.core.mgmt.rebind.RebindTestFixtureWithApp;
import org.apache.brooklyn.core.test.entity.TestApplication;
import org.apache.brooklyn.entity.software.base.SoftwareProcessEntityTest.MyService;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.core.flags.SetFromFlag;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

public class SoftwareProcessEntityRebindTest extends RebindTestFixtureWithApp {

    @Override
    protected boolean enablePersistenceBackups() {
        return false;
    }

    @Test
    public void testReleasesLocationOnStopAfterRebinding() throws Exception {
        MyService origE = origApp.createAndManageChild(EntitySpec.create(MyService.class)
                .configure(BrooklynConfigKeys.SKIP_ON_BOX_BASE_DIR_RESOLUTION, true));
        
        MyProvisioningLocation origLoc = mgmt().getLocationManager().createLocation(LocationSpec.create(MyProvisioningLocation.class)
                .displayName("mylocname"));
        origApp.start(ImmutableList.of(origLoc));
        assertEquals(origLoc.inUseCount.get(), 1);
        
        newApp = rebind();
        MyProvisioningLocation newLoc = (MyProvisioningLocation) Iterables.getOnlyElement(newApp.getLocations());
        assertEquals(newLoc.inUseCount.get(), 1);
        
        newApp.stop();
        assertEquals(newLoc.inUseCount.get(), 0);
    }

    @Test
    public void testCreatesDriverAfterRebind() throws Exception {
        MyService origE = origApp.createAndManageChild(EntitySpec.create(MyService.class)
                .configure(BrooklynConfigKeys.SKIP_ON_BOX_BASE_DIR_RESOLUTION, true));
                //the entity skips enricher initialization, do it explicitly
        origE.enrichers().add(ServiceStateLogic.newEnricherForServiceStateFromProblemsAndUp());

        MyProvisioningLocation origLoc = mgmt().getLocationManager().createLocation(LocationSpec.create(MyProvisioningLocation.class)
                .displayName("mylocname"));
        origApp.start(ImmutableList.of(origLoc));
        assertEquals(origE.getAttribute(Attributes.SERVICE_STATE_EXPECTED).getState(), Lifecycle.RUNNING);
        EntityAsserts.assertAttributeEqualsEventually(origE, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);

        ServiceProblemsLogic.updateProblemsIndicator((EntityLocal)origE, "test", "fire");
        EntityAsserts.assertAttributeEqualsEventually(origE, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.ON_FIRE);

        newApp = rebind();
        MyService newE = (MyService) Iterables.getOnlyElement(newApp.getChildren());
        assertTrue(newE.getDriver() != null, "driver should be initialized");
    }

    @Test
    public void testDoesNotCreateDriverAfterRebind() throws Exception {
        MyService origE = origApp.createAndManageChild(EntitySpec.create(MyService.class)
                .configure(BrooklynConfigKeys.SKIP_ON_BOX_BASE_DIR_RESOLUTION, true));
                //the entity skips enricher initialization, do it explicitly
        origE.enrichers().add(ServiceStateLogic.newEnricherForServiceStateFromProblemsAndUp());
        
        MyProvisioningLocation origLoc = mgmt().getLocationManager().createLocation(LocationSpec.create(MyProvisioningLocation.class)
                .displayName("mylocname"));
        origApp.start(ImmutableList.of(origLoc));
        assertEquals(origE.getAttribute(Attributes.SERVICE_STATE_EXPECTED).getState(), Lifecycle.RUNNING);
        EntityAsserts.assertAttributeEqualsEventually(origE, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);

        ServiceStateLogic.setExpectedState(origE, Lifecycle.ON_FIRE);
        EntityAsserts.assertAttributeEqualsEventually(origE, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.ON_FIRE);

        newApp = rebind();
        MyService newE = (MyService) Iterables.getOnlyElement(newApp.getChildren());
        assertNull(newE.getDriver(), "driver should not be initialized because entity is in a permanent failure");
    }
    
    public static class MyProvisioningLocation extends AbstractLocation implements MachineProvisioningLocation<SshMachineLocation> {
        @SetFromFlag(defaultVal="0")
        AtomicInteger inUseCount;

        public MyProvisioningLocation() {
        }
        
        @Override
        public MachineProvisioningLocation<SshMachineLocation> newSubLocation(Map<?, ?> newFlags) {
            throw new UnsupportedOperationException();
        }
        
        @Override
        public SshMachineLocation obtain(Map flags) throws NoMachinesAvailableException {
            inUseCount.incrementAndGet();
            return getManagementContext().getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                    .parent(this)
                    .configure("address","localhost"));
        }

        @Override
        public void release(SshMachineLocation machine) {
            inUseCount.decrementAndGet();
        }

        @Override
        public Map getProvisioningFlags(Collection tags) {
            return Collections.emptyMap();
        }
    }
}
