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
import java.net.URI;

import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.internal.BrooklynProperties;
import org.apache.brooklyn.core.mgmt.entitlement.Entitlements;
import org.apache.brooklyn.core.mgmt.entitlement.PerUserEntitlementManager;
import org.apache.brooklyn.core.test.entity.LocalManagementContextForTests;
import org.apache.brooklyn.core.test.entity.TestApplication;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.rest.BrooklynRestApiLauncherTestFixture;
import org.apache.brooklyn.rest.BrooklynWebConfig;
import org.apache.brooklyn.util.http.HttpTool;
import org.apache.brooklyn.util.http.HttpToolResponse;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

/**
 * Sets up the REST api with some standard users, ready for testing entitlements.
 */
public abstract class AbstractRestApiEntitlementsTest extends BrooklynRestApiLauncherTestFixture {

    protected ManagementContext mgmt;
    protected TestApplication app;
    protected TestEntity entity;

    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        BrooklynProperties props = BrooklynProperties.Factory.newEmpty();
        props.put(Entitlements.GLOBAL_ENTITLEMENT_MANAGER.getName(), PerUserEntitlementManager.class.getName());
        props.put(PerUserEntitlementManager.PER_USER_ENTITLEMENTS_CONFIG_PREFIX+".myRoot", "root");
        props.put(PerUserEntitlementManager.PER_USER_ENTITLEMENTS_CONFIG_PREFIX+".myReadonly", "readonly");
        props.put(PerUserEntitlementManager.PER_USER_ENTITLEMENTS_CONFIG_PREFIX+".myMinimal", "minimal");
        props.put(PerUserEntitlementManager.PER_USER_ENTITLEMENTS_CONFIG_PREFIX+".myUser", "user");
        props.put(PerUserEntitlementManager.PER_USER_ENTITLEMENTS_CONFIG_PREFIX+".myCustom", StaticDelegatingEntitlementManager.class.getName());
        props.put(BrooklynWebConfig.SECURITY_PROVIDER_CLASSNAME, AuthenticateAnyoneSecurityProvider.class.getName());
        
        mgmt = LocalManagementContextForTests.builder(false).useProperties(props).build();
        app = mgmt.getEntityManager().createEntity(EntitySpec.create(TestApplication.class)
                .child(EntitySpec.create(TestEntity.class))
                        .configure(TestEntity.CONF_NAME, "myname"));
        entity = (TestEntity) Iterables.getOnlyElement(app.getChildren());
        
        useServerForTest(baseLauncher()
                .managementContext(mgmt)
                .forceUseOfDefaultCatalogWithJavaClassPath(true)
                .start());
    }

    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        if (mgmt != null) Entities.destroyAll(mgmt);
    }
    
    protected String assertPermitted(String user, String path) throws Exception {
        return httpGet(user, path);
    }

    public void assertPermittedPost(String user, String path, byte[] body) throws Exception {
        HttpToolResponse response = httpPost(user, path, body);
        assertHealthyStatusCode(response);
    }

    protected void assertForbidden(String user, String path) throws Exception {
        HttpToolResponse response = HttpTool.httpGet(newClient(user), URI.create(getBaseUriRest()).resolve(path), ImmutableMap.<String, String>of());
        assertStatusCodeEquals(response, 403);
    }

    public void assertForbiddenPost(String user, String path, byte[] body) throws Exception {
        HttpToolResponse response = httpPost(user, path, body);
        assertEquals(response.getResponseCode(), 403, "code=" + response.getResponseCode() + "; reason=" + response.getReasonPhrase());
    }

    protected void assert404(String user, String path) throws Exception {
        HttpToolResponse response = HttpTool.httpGet(newClient(user), URI.create(getBaseUriRest()).resolve(path), ImmutableMap.<String, String>of());
        assertStatusCodeEquals(response, 404);
    }

    protected void assert401(String path) throws Exception {
        HttpToolResponse response = HttpTool.httpGet(newClient(null), URI.create(getBaseUriRest()).resolve(path), ImmutableMap.<String, String>of());
        assertStatusCodeEquals(response, 401);
    }

    protected void assertStatusCodeEquals(HttpToolResponse response, int expected) {
        assertEquals(response.getResponseCode(), expected,
                "code="+response.getResponseCode()+"; reason="+response.getReasonPhrase()+"; content="+response.getContentAsString());
    }

}
