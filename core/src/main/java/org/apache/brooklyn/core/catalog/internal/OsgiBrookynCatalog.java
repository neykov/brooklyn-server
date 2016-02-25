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
package org.apache.brooklyn.core.catalog.internal;

import com.google.common.base.Predicate;
import java.util.Collection;
import java.util.List;
import org.apache.brooklyn.api.catalog.BrooklynCatalog;
import org.apache.brooklyn.api.catalog.CatalogItem;
import org.apache.brooklyn.api.internal.AbstractBrooklynObjectSpec;

/**
 * A catalog that enumerates OSGi services registered as {@link CatalogItem}s.
 *
 * For now we only support read operations over the OSGi service registry. An interesting extension would be to implement mutability using
 * karaf feature install/uninstall services for {@link CatalogItem}s that are applicable.
 *
 * All unsupported operations are delegated to a fallback catalog (chain of responsibility).
 */
public class OsgiBrookynCatalog implements BrooklynCatalog {

    private final BrooklynCatalog successor;

    private List<CatalogItem> catalogItems;

    public OsgiBrookynCatalog(BrooklynCatalog successor) {
        this.successor = successor;
    }

    @Override
    public CatalogItem<?, ?> getCatalogItem(String symbolicName, String version) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public <T, SpecT> CatalogItem<T, SpecT> getCatalogItem(Class<T> type, String symbolicName, String version) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public <T, SpecT> Iterable<CatalogItem<T, SpecT>> getCatalogItems() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public <T, SpecT> Iterable<CatalogItem<T, SpecT>> getCatalogItems(Predicate<? super CatalogItem<T, SpecT>> filter) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public void deleteCatalogItem(String symbolicName, String version) {
        successor.deleteCatalogItem(symbolicName, version);
    }

    @Override
    public void persist(CatalogItem<?, ?> catalogItem) {
        successor.persist(catalogItem);
    }

    @Override
    public ClassLoader getRootClassLoader() {
        return successor.getRootClassLoader();
    }

    @Override
    public <T, SpecT extends AbstractBrooklynObjectSpec<? extends T, SpecT>> SpecT createSpec(CatalogItem<T, SpecT> item) {
        return successor.createSpec(item);
    }

    @Override
    public CatalogItem<?, ?> addItem(String yaml) {
        return successor.addItem(yaml);
    }

    @Override
    public CatalogItem<?, ?> addItem(String yaml, boolean forceUpdate) {
        return successor.addItem(yaml, forceUpdate);
    }

    @Override
    public Iterable<? extends CatalogItem<?, ?>> addItems(String yaml) {
        return successor.addItems(yaml);
    }

    @Override
    public Iterable<? extends CatalogItem<?, ?>> addItems(String yaml, boolean forceUpdate) {
         return successor.addItems(yaml, forceUpdate);
   }

    @Override
    public void addItem(CatalogItem<?, ?> item) {
        successor.addItem(item);
    }

    @Override
    public CatalogItem<?, ?> addItem(Class<?> clazz) {
        return successor.addItem(clazz);
    }

    @Override
    public void reset(Collection<CatalogItem<?, ?>> entries) {
        successor.reset(entries);
    }

}
