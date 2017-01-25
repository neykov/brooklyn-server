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
import static org.testng.Assert.assertTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.test.BrooklynAppLiveTestSupport;
import org.apache.brooklyn.util.text.Identifiers;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

public class VanillaSoftwareProcessIntegrationTest extends BrooklynAppLiveTestSupport {

    // TODO Should re-write this as a non-intergration test (e.g. using a RecordingSshTool, rather than real execs)

    private Location localhost;
    private Path runRecord;
    
    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        localhost = app.getManagementContext().getLocationRegistry().getLocationManaged("localhost");
        
        runRecord = Files.createTempFile("testVanillaSoftwareProcess-runRecord-"+Identifiers.makeRandomId(8), ".txt");
    }

    @Override
    public void tearDown() throws Exception {
        if (runRecord != null) Files.delete(runRecord);
        super.tearDown();
    }
    
    @Test(groups = "Integration")
    public void testAllCmds() throws Exception {
        app.createAndManageChild(EntitySpec.create(VanillaSoftwareProcess.class)
                .configure(VanillaSoftwareProcess.INSTALL_UNIQUE_LABEL, Identifiers.makeRandomId(8))
                .configure(VanillaSoftwareProcess.PRE_INSTALL_COMMAND, "echo preInstallCommand >> "+runRecord.toAbsolutePath())
                .configure(VanillaSoftwareProcess.INSTALL_COMMAND, "echo installCommand >> "+runRecord.toAbsolutePath())
                .configure(VanillaSoftwareProcess.POST_INSTALL_COMMAND, "echo postInstallCommand >> "+runRecord.toAbsolutePath())
                .configure(VanillaSoftwareProcess.CUSTOMIZE_COMMAND, "echo customizeCommand >> "+runRecord.toAbsolutePath())
                .configure(VanillaSoftwareProcess.PRE_LAUNCH_COMMAND, "echo preLaunchCommand >> "+runRecord.toAbsolutePath())
                .configure(VanillaSoftwareProcess.LAUNCH_COMMAND, "echo launchCommand >> "+runRecord.toAbsolutePath())
                .configure(VanillaSoftwareProcess.POST_LAUNCH_COMMAND, "echo postLaunchCommand >> "+runRecord.toAbsolutePath())
                .configure(VanillaSoftwareProcess.CHECK_RUNNING_COMMAND, "echo checkRunningCommand >> "+runRecord.toAbsolutePath())
                .configure(VanillaSoftwareProcess.STOP_COMMAND, "echo stopCommand >> "+runRecord.toAbsolutePath()));
        app.start(ImmutableList.of(localhost));

        String record = new String(Files.readAllBytes(runRecord));
        List<String> lines = ImmutableList.copyOf(Splitter.on("\n").omitEmptyStrings().split(record));
        
        assertEquals(lines.get(0), "preInstallCommand", "lines="+lines);
        assertEquals(lines.get(1), "installCommand", "lines="+lines);
        assertEquals(lines.get(2), "postInstallCommand", "lines="+lines);
        assertEquals(lines.get(3), "customizeCommand", "lines="+lines);
        assertEquals(lines.get(4), "preLaunchCommand", "lines="+lines);
        assertEquals(lines.get(5), "launchCommand", "lines="+lines);
        assertEquals(lines.get(6), "postLaunchCommand", "lines="+lines);
        assertEquals(lines.get(7), "checkRunningCommand", "lines="+lines);
        
        app.stop();

        String record2 = new String(Files.readAllBytes(runRecord));
        List<String> lines2 = ImmutableList.copyOf(Splitter.on("\n").omitEmptyStrings().split(record2));
        
        assertEquals(lines2.get(lines2.size()-1), "stopCommand", "lines="+lines2);
    }

    // See https://issues.apache.org/jira/browse/BROOKLYN-273
    @Test(groups = "Integration")
    public void testRestartCmds() throws Exception {
        VanillaSoftwareProcess entity = app.createAndManageChild(EntitySpec.create(VanillaSoftwareProcess.class)
                .configure(VanillaSoftwareProcess.INSTALL_UNIQUE_LABEL, Identifiers.makeRandomId(8))
                .configure(VanillaSoftwareProcess.PRE_INSTALL_COMMAND, "echo preInstallCommand >> "+runRecord.toAbsolutePath())
                .configure(VanillaSoftwareProcess.INSTALL_COMMAND, "echo installCommand >> "+runRecord.toAbsolutePath())
                .configure(VanillaSoftwareProcess.POST_INSTALL_COMMAND, "echo postInstallCommand >> "+runRecord.toAbsolutePath())
                .configure(VanillaSoftwareProcess.CUSTOMIZE_COMMAND, "echo customizeCommand >> "+runRecord.toAbsolutePath())
                .configure(VanillaSoftwareProcess.PRE_LAUNCH_COMMAND, "echo preLaunchCommand >> "+runRecord.toAbsolutePath())
                .configure(VanillaSoftwareProcess.LAUNCH_COMMAND, "echo launchCommand >> "+runRecord.toAbsolutePath())
                .configure(VanillaSoftwareProcess.POST_LAUNCH_COMMAND, "echo postLaunchCommand >> "+runRecord.toAbsolutePath())
                .configure(VanillaSoftwareProcess.CHECK_RUNNING_COMMAND, "echo checkRunningCommand >> "+runRecord.toAbsolutePath())
                .configure(VanillaSoftwareProcess.STOP_COMMAND, "echo stopCommand >> "+runRecord.toAbsolutePath()));
        app.start(ImmutableList.of(localhost));

        // Stop the entity, and clear out all record of previous execs
        Entities.invokeEffector(app, entity, VanillaSoftwareProcess.STOP, ImmutableMap.of(
                VanillaSoftwareProcess.StopSoftwareParameters.STOP_MACHINE_MODE.getName(), VanillaSoftwareProcess.StopSoftwareParameters.StopMode.NEVER,
                VanillaSoftwareProcess.StopSoftwareParameters.STOP_PROCESS_MODE.getName(), VanillaSoftwareProcess.StopSoftwareParameters.StopMode.ALWAYS))
                .get();

        Files.delete(runRecord);
        runRecord = Files.createFile(runRecord);

        // Invoke restart(), and check if all steps were executed
        Entities.invokeEffector(app, entity, VanillaSoftwareProcess.RESTART, ImmutableMap.of(
                VanillaSoftwareProcess.RestartSoftwareParameters.RESTART_CHILDREN.getName(), false,
                VanillaSoftwareProcess.RestartSoftwareParameters.RESTART_MACHINE.getName(), VanillaSoftwareProcess.RestartSoftwareParameters.RestartMachineMode.FALSE))
                .get();

        String record = new String(Files.readAllBytes(runRecord));
        List<String> lines = ImmutableList.copyOf(Splitter.on("\n").omitEmptyStrings().split(record));
        
        assertEquals(lines.get(0), "checkRunningCommand", "lines="+lines);
        assertEquals(lines.get(1), "stopCommand", "lines="+lines);
        assertEquals(lines.get(2), "preLaunchCommand", "lines="+lines);
        assertEquals(lines.get(3), "launchCommand", "lines="+lines);
        assertEquals(lines.get(4), "postLaunchCommand", "lines="+lines);
        assertEquals(lines.get(5), "checkRunningCommand", "lines="+lines);
    }

    @Test(groups = "Integration")
    public void testDownloadOnlyCmd() throws Exception {
        Path downloadArtifact = Files.createTempFile("testVanillaSoftwareProcess-downloadArtifact-"+Identifiers.makeRandomId(8), ".txt");
        Files.write(downloadArtifact, "my download artifact".getBytes());

        try {
            VanillaSoftwareProcess entity = app.createAndManageChild(EntitySpec.create(VanillaSoftwareProcess.class)
                    .configure(VanillaSoftwareProcess.DOWNLOAD_URL, downloadArtifact.toUri().toString())
                    .configure(VanillaSoftwareProcess.LAUNCH_COMMAND, "echo launched")
                    .configure(VanillaSoftwareProcess.CHECK_RUNNING_COMMAND, "echo running"));
            app.start(ImmutableList.of(localhost));

            Path installedArtifact = FileSystems.getDefault().getPath(entity.getAttribute(VanillaSoftwareProcess.INSTALL_DIR), downloadArtifact.getFileName().toString());
            assertEquals(new String(Files.readAllBytes(installedArtifact)), "my download artifact");

            Path installCompletionMarker = FileSystems.getDefault().getPath(entity.getAttribute(VanillaSoftwareProcess.INSTALL_DIR), "BROOKLYN");
            assertTrue(Files.isRegularFile(installCompletionMarker), "file="+installCompletionMarker);

        } finally {
            Files.delete(downloadArtifact);
        }
    }
    
    @Test(groups = "Integration")
    public void testInstallOnlyCmd() throws Exception {
        VanillaSoftwareProcess entity = app.createAndManageChild(EntitySpec.create(VanillaSoftwareProcess.class)
                .configure(VanillaSoftwareProcess.INSTALL_COMMAND, "echo installCommand >> "+runRecord.toAbsolutePath())
                .configure(VanillaSoftwareProcess.LAUNCH_COMMAND, "echo launching")
                .configure(VanillaSoftwareProcess.CHECK_RUNNING_COMMAND, "echo running"));
        app.start(ImmutableList.of(localhost));

        Path installCompletionMarker = FileSystems.getDefault().getPath(entity.getAttribute(VanillaSoftwareProcess.INSTALL_DIR), "BROOKLYN");
        assertTrue(Files.isRegularFile(installCompletionMarker), "file="+installCompletionMarker);

        assertEquals(new String(Files.readAllBytes(runRecord)).trim(), "installCommand");
    }
    
    @Test(groups = "Integration")
    public void testDownloadAndInstallCmds() throws Exception {
        Path downloadArtifact = Files.createTempFile("testVanillaSoftwareProcess-downloadArtifact-"+Identifiers.makeRandomId(8), ".txt");
        Files.write(downloadArtifact, "my download artifact".getBytes());

        try {
            VanillaSoftwareProcess entity = app.createAndManageChild(EntitySpec.create(VanillaSoftwareProcess.class)
                    .configure(VanillaSoftwareProcess.DOWNLOAD_URL, downloadArtifact.toUri().toString())
                    .configure(VanillaSoftwareProcess.INSTALL_COMMAND, "echo installCommand >> "+runRecord.toAbsolutePath())
                    .configure(VanillaSoftwareProcess.LAUNCH_COMMAND, "echo launched")
                    .configure(VanillaSoftwareProcess.CHECK_RUNNING_COMMAND, "echo running"));
            app.start(ImmutableList.of(localhost));

            Path installedArtifact = FileSystems.getDefault().getPath(entity.getAttribute(VanillaSoftwareProcess.INSTALL_DIR), downloadArtifact.getFileName().toString());
            assertEquals(new String(Files.readAllBytes(installedArtifact)), "my download artifact");

            Path installCompletionMarker = FileSystems.getDefault().getPath(entity.getAttribute(VanillaSoftwareProcess.INSTALL_DIR), "BROOKLYN");
            assertTrue(Files.isRegularFile(installCompletionMarker), "file="+installCompletionMarker);

            assertEquals(new String(Files.readAllBytes(runRecord)).trim(), "installCommand");

        } finally {
            Files.delete(downloadArtifact);
        }
    }
    
    @Test(groups = "Integration")
    public void testShellEnv() throws Exception {
        VanillaSoftwareProcess entity = app.createAndManageChild(EntitySpec.create(VanillaSoftwareProcess.class)
                .configure(VanillaSoftwareProcess.SHELL_ENVIRONMENT.subKey("RUN_RECORD"), runRecord.toAbsolutePath())
                .configure(VanillaSoftwareProcess.SHELL_ENVIRONMENT.subKey("IGNORED"), Identifiers.makeRandomId(8))
                .configure(VanillaSoftwareProcess.INSTALL_COMMAND, "echo installCommand >> $RUN_RECORD")
                .configure(VanillaSoftwareProcess.LAUNCH_COMMAND, "echo launching")
                .configure(VanillaSoftwareProcess.CHECK_RUNNING_COMMAND, "echo running"));
        app.start(ImmutableList.of(localhost));

        Path installCompletionMarker = FileSystems.getDefault().getPath(entity.getAttribute(VanillaSoftwareProcess.INSTALL_DIR), "BROOKLYN");
        assertTrue(Files.isRegularFile(installCompletionMarker), "file="+installCompletionMarker);

        assertEquals(new String(Files.readAllBytes(runRecord)).trim(), "installCommand");
    }
    
    @Test(groups = "Integration")
    public void testInstallExecutedOnlyOnce() throws Exception {
        VanillaSoftwareProcess entity = app.createAndManageChild(EntitySpec.create(VanillaSoftwareProcess.class)
                .configure(VanillaSoftwareProcess.INSTALL_COMMAND, "echo installCommand >> "+runRecord.toAbsolutePath())
                .configure(VanillaSoftwareProcess.LAUNCH_COMMAND, "echo launching")
                .configure(VanillaSoftwareProcess.CHECK_RUNNING_COMMAND, "echo running"));
        VanillaSoftwareProcess entity2 = app.createAndManageChild(EntitySpec.create(VanillaSoftwareProcess.class)
                .configure(VanillaSoftwareProcess.INSTALL_COMMAND, "echo installCommand >> "+runRecord.toAbsolutePath())
                .configure(VanillaSoftwareProcess.LAUNCH_COMMAND, "echo launching")
                .configure(VanillaSoftwareProcess.CHECK_RUNNING_COMMAND, "echo running"));
        app.start(ImmutableList.of(localhost));

        Path installCompletionMarker = FileSystems.getDefault().getPath(entity.getAttribute(VanillaSoftwareProcess.INSTALL_DIR), "BROOKLYN");
        assertTrue(Files.isRegularFile(installCompletionMarker), "file="+installCompletionMarker);

        Path installCompletionMarker2 = FileSystems.getDefault().getPath(entity2.getAttribute(VanillaSoftwareProcess.INSTALL_DIR), "BROOKLYN");
        assertEquals(installCompletionMarker, installCompletionMarker2);
        
        assertEquals(new String(Files.readAllBytes(runRecord)).trim(), "installCommand");
    }

    // Installation creates a installs/VanillaSoftwareProcess_0.0.0_nnnnn/BROOKLYN marker file.
    // It indicates that installation has already been done successfully, so it is skipped the second time.
    // Assert it respects different values for the install script, to ensure each different VanillaSoftwareProcess
    // does get installed!
    @Test(groups = "Integration", dependsOnMethods="testShellEnv")
    public void testShellEnvUsedInHashForInstallCompletion() throws Exception {
        testShellEnv();
    }
    
    @Test(groups = "Integration", dependsOnMethods="testDownloadOnlyCmd")
    public void testDownloadUrlUsedInHashForInstallCompletion() throws Exception {
        testDownloadOnlyCmd();
    }
    
    @Test(groups = "Integration", dependsOnMethods="testInstallOnlyCmd")
    public void testInstallCmdUsedInHashForInstallCompletion() throws Exception {
        testInstallOnlyCmd();
    }
}
