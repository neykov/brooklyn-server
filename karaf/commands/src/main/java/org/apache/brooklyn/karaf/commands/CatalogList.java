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
package org.apache.brooklyn.karaf.commands;

import com.google.common.annotations.Beta;
import org.apache.brooklyn.api.catalog.CatalogItem;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.apache.karaf.shell.support.table.ShellTable;

@Beta
@Command(scope = "brooklyn", name = "catalog-list", description = "List the contents of the local brooklyn catalog")
@Service
public class CatalogList implements Action {

//    @Option(name = "-o", aliases = { "--option" }, description = "An option to the command", required = false, multiValued = false)
//    private String option;
//
//    @Argument(index = 0, name = "argument", description = "Argument to the command", required = true, multiValued = false)
//    private String argument;
    @Option(name = "-o", aliases = {"--ordered"}, description = "Display a list using alphabetical order ", required = false, multiValued = false)
    boolean ordered;

    @Option(name = "--no-format", description = "Disable table rendered output", required = false, multiValued = false)
    boolean noFormat;

    @Reference
    private ManagementContext managementContext;

    @Override
    public Object execute() throws Exception {
        if (managementContext == null) {
            throw new IllegalStateException("ManagementContext not found");
        }

        ShellTable table = new ShellTable();
        table.column("Symbolic name");
        table.column("Type");
        table.column("Version");
        table.emptyTableText("No catalog items available");

        Iterable<CatalogItem<Object, Object>> catalogItems = managementContext.getCatalog().getCatalogItems();
        for (CatalogItem<Object, Object> item : catalogItems) {
            table.addRow().addContent(
                    item.getSymbolicName(),
                    toDisplayableString(item.getCatalogItemType()),
                    item.getVersion());
        }

        table.print(System.out, !noFormat);

        return null;
    }

    private String toDisplayableString(CatalogItem.CatalogItemType type) {
        switch(type) {
            case ENTITY:
                return "entity";
            case LOCATION:
                return "location";
            case POLICY:
                return "policy";
            case TEMPLATE:
                return "template";
            default:
                return "unknown";
        }
    }
}
