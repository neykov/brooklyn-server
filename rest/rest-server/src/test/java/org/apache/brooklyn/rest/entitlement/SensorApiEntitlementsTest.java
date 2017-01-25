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
package org.apache.brooklyn.rest.entitlement;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.mgmt.entitlement.EntitlementClass;
import org.apache.brooklyn.api.mgmt.entitlement.EntitlementContext;
import org.apache.brooklyn.api.mgmt.entitlement.EntitlementManager;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.core.config.render.RendererHints;
import org.apache.brooklyn.core.mgmt.entitlement.Entitlements;
import org.apache.brooklyn.core.mgmt.entitlement.Entitlements.EntityAndItem;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.rest.api.SensorApi;
import org.apache.brooklyn.rest.resources.SensorResource;
import org.apache.brooklyn.test.Asserts;
import org.testng.annotations.Test;

/**
 * Test the {@link SensorApi} implementation.
 * <p>
 * Check that {@link SensorResource} correctly renders {@link AttributeSensor}
 * values, including {@link RendererHints.DisplayValue} hints.
 */
@Test(singleThreaded = true)
public class SensorApiEntitlementsTest extends AbstractRestApiEntitlementsTest {

    @Test(groups = "Integration")
    public void testGet() throws Exception {
        entity.sensors().set(TestEntity.NAME, "myval");
        
        String sensorName = TestEntity.NAME.getName();
        String path = "/v1/applications/"+app.getId()+"/entities/"+entity.getId()+"/sensors/"+sensorName;
        String val = "\"myval\"";
        
        assert401(path);
        assertEquals(httpGet("myRoot", path), val);
        assertEquals(httpGet("myUser", path), val);
        assertEquals(httpGet("myReadonly", path), val);
        assert404("myMinimal", path); // can't see app, to retrieve entity
        assert404("unrecognisedUser", path);

        StaticDelegatingEntitlementManager.setDelegate(new SeeSelectiveSensor(entity, sensorName));
        assertEquals(httpGet("myCustom", path), val);
        
        StaticDelegatingEntitlementManager.setDelegate(new SeeSelectiveSensor(entity, "differentConfName"));
        assertForbidden("myCustom", path);
    }
    
    @Test(groups = "Integration")
    public void testCurrentState() throws Exception {
        entity.sensors().set(TestEntity.NAME, "myval");
        
        String path = "/v1/applications/"+app.getId()+"/entities/"+entity.getId()+"/sensors/current-state";
        String sensorName = TestEntity.NAME.getName();
        String regex = ".*"+sensorName+".*myval.*";
        
        assert401(path);
        Asserts.assertStringMatchesRegex(httpGet("myRoot", path), regex);
        Asserts.assertStringMatchesRegex(httpGet("myUser", path), regex);
        Asserts.assertStringMatchesRegex(httpGet("myReadonly", path), regex);
        assert404("myMinimal", path); // can't see app, to retrieve entity
        assert404("unrecognisedUser", path);

        StaticDelegatingEntitlementManager.setDelegate(new SeeSelectiveSensor(entity, sensorName));
        Asserts.assertStringMatchesRegex(httpGet("myCustom", path), regex);
        
        StaticDelegatingEntitlementManager.setDelegate(new SeeSelectiveSensor(entity, "differentSensorName"));
        String resp = httpGet("myCustom", path);
        assertFalse(resp.matches(regex), "resp="+resp);
    }
    
    public static class SeeSelectiveSensor implements EntitlementManager {
        private final Entity entity;
        private final String regex;
        
        public SeeSelectiveSensor(Entity entity, String regex) {
            this.entity = entity;
            this.regex = regex;
        }
        @Override 
        @SuppressWarnings("unchecked")
        public <T> boolean isEntitled(EntitlementContext context, EntitlementClass<T> entitlementClass, T entitlementClassArgument) {
            String type = entitlementClass.entitlementClassIdentifier();
            if (Entitlements.SEE_SENSOR.entitlementClassIdentifier().equals(type)) {
                EntityAndItem<String> entityAndItem = (EntityAndItem<String>) entitlementClassArgument;
                return entity.equals(entityAndItem.getEntity()) && entityAndItem.getItem().matches(regex);
            } else {
                return true;
            }
        }
    }
}
