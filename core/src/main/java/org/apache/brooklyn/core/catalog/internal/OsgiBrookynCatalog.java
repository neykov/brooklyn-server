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

import static com.google.common.base.Preconditions.checkNotNull;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.util.Collection;
import java.util.List;
import org.apache.brooklyn.api.catalog.BrooklynCatalog;
import org.apache.brooklyn.api.catalog.CatalogItem;
import org.apache.brooklyn.api.internal.AbstractBrooklynObjectSpec;

/**
 * A catalog that enumerates OSGi services registered as {@link CatalogItem}s.
 *
 * For now we only support read operations over the OSGi service registry. An
 * interesting extension would be to implement mutability using karaf feature
 * install/uninstall services for {@link CatalogItem}s that are applicable.
 *
 * All unsupported operations are delegated to a fallback catalog (chain of
 * responsibility).
 */
public class OsgiBrookynCatalog implements BrooklynCatalog {

    private final BrooklynCatalog successor;

    private List<CatalogItem<?, ?>> catalogItems;

    public OsgiBrookynCatalog(BrooklynCatalog successor) {
        this.successor = successor;
    }

    public synchronized void setOsgiCatalogItems(List<CatalogItem<?, ?>> catalogItems) {
        this.catalogItems = catalogItems;
    }

    @Override
    public synchronized CatalogItem<?, ?> getCatalogItem(String symbolicName, String version) {
        if (symbolicName == null) {
            return null;
        }
        checkNotNull(version, "version");
        for (CatalogItem<?, ?> item : catalogItems) {
            if (symbolicName.equals(item.getSymbolicName())
                    && version.equals(item.getVersion())) {
                return item;
            }
        }
        return successor.getCatalogItem(symbolicName, version);
    }

    @Override
    public synchronized <T, SpecT> CatalogItem<T, SpecT> getCatalogItem(Class<T> type, String symbolicName, String version) {
        if (symbolicName == null || version == null) {
            return null;
        }
        CatalogItem<?, ?> result = getCatalogItem(symbolicName, version);
        if (result != null && type == null || type.isAssignableFrom(result.getCatalogItemJavaType())) {
            return (CatalogItem<T, SpecT>) result;
        }
        return successor.getCatalogItem(type, symbolicName, version);
    }

    @Override
    public synchronized <T, SpecT> Iterable<CatalogItem<T, SpecT>> getCatalogItems() {
        return ImmutableList.<CatalogItem<T, SpecT>>builder()
                .addAll((Iterable<CatalogItem<T, SpecT>>) (Iterable) catalogItems)
                .addAll(successor.<T, SpecT>getCatalogItems())
                .build();
    }

    @Override
    public synchronized <T, SpecT> Iterable<CatalogItem<T, SpecT>> getCatalogItems(Predicate<? super CatalogItem<T, SpecT>> filter) {
        return ImmutableList.<CatalogItem<T, SpecT>>builder()
                .addAll(Iterables.filter((Iterable<CatalogItem<T, SpecT>>) (Iterable) catalogItems, filter))
                .addAll(successor.<T, SpecT>getCatalogItems(filter))
                .build();
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

    @Override
    public <T extends BrooklynCatalog> T findCatalog(Class<T> clazz) {
        if (OsgiBrookynCatalog.class.isAssignableFrom(clazz)) {
            return (T) this;
        } else {
            return successor.findCatalog(clazz);
        }
    }

}
