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
package org.apache.brooklyn.core.entity;

import com.google.common.annotations.Beta;
import com.google.common.base.Objects;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.Group;
import org.apache.brooklyn.api.mgmt.SubscriptionHandle;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.sensor.SensorEvent;
import org.apache.brooklyn.api.sensor.SensorEventListener;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.apache.brooklyn.core.entity.trait.Startable.SERVICE_UP;
import static org.apache.brooklyn.test.Asserts.*;

/**
 * Convenience class containing assertions that may be made about entities.
 */
public class EntityAsserts {

    private static final Logger LOG = LoggerFactory.getLogger(EntityAsserts.class);

    public static <T> void assertAttributeEquals(Entity entity, AttributeSensor<T> attribute, T expected) {
        assertEquals(entity.getAttribute(attribute), expected, "entity=" + entity + "; attribute=" + attribute);
    }

    public static <T> void assertConfigEquals(Entity entity, ConfigKey<T> configKey, T expected) {
        assertEquals(entity.getConfig(configKey), expected, "entity=" + entity + "; configKey=" + configKey);
    }

    public static <T> void assertAttributeEqualsEventually(final Entity entity, final AttributeSensor<T> attribute, final T expected) {
        assertAttributeEqualsEventually(Maps.newLinkedHashMap(), entity, attribute, expected);
    }

    public static <T> void assertAttributeEqualsEventually(Map<?,?> flags, final Entity entity, final AttributeSensor<T> attribute, final T expected) {
        // Not using assertAttributeEventually(predicate) so get nicer error message
        Asserts.succeedsEventually(castToMapWithStringKeys(flags), new Runnable() {
            @Override
            public void run() {
                assertAttributeEquals(entity, attribute, expected);
            }
        });
    }

    public static <T> T assertAttributeEventuallyNonNull(final Entity entity, final AttributeSensor<T> attribute) {
        return assertAttributeEventuallyNonNull(Maps.newLinkedHashMap(), entity, attribute);
    }

    public static <T> T assertAttributeEventuallyNonNull(Map<?,?> flags, final Entity entity, final AttributeSensor<T> attribute) {
        return assertAttributeEventually(flags, entity, attribute, Predicates.notNull());
    }

    public static <T> T assertAttributeEventually(final Entity entity, final AttributeSensor<T> attribute, Predicate<? super T> predicate) {
        return assertAttributeEventually(ImmutableMap.of(), entity, attribute, predicate);
    }

    public static <T> T assertAttributeEventually(Map<?,?> flags, final Entity entity, final AttributeSensor<T> attribute, final Predicate<? super T> predicate) {
        final AtomicReference<T> result = new AtomicReference<T>();
        Asserts.succeedsEventually(castToMapWithStringKeys(flags), new Runnable() {
            @Override public void run() {
                T val = assertAttribute(entity, attribute, predicate);
                result.set(val);
            }});
        return result.get();
    }

    public static <T> T assertAttribute(final Entity entity, final AttributeSensor<T> attribute, final Predicate<? super T> predicate) {
        T val = entity.getAttribute(attribute);
        Asserts.assertTrue(predicate.apply(val), "attribute="+attribute+"; val=" + val);
        return val;
    }


    public static <T extends Entity> void assertPredicateEventuallyTrue(final T entity, final Predicate<? super T> predicate) {
        assertPredicateEventuallyTrue(ImmutableMap.of(), entity, predicate);
    }

    public static <T extends Entity> void assertPredicateEventuallyTrue(Map<?,?> flags, final T entity, final Predicate<? super T> predicate) {
        Asserts.succeedsEventually(castToMapWithStringKeys(flags), new Runnable() {
            @Override public void run() {
                Asserts.assertTrue(predicate.apply(entity), "predicate " + predicate + " unsatisfied for "+ entity);
            }});
    }

    public static <T> void assertAttributeEqualsContinually(final Entity entity, final AttributeSensor<T> attribute, final T expected) {
        assertAttributeEqualsContinually(ImmutableMap.of(), entity, attribute, expected);
    }

    public static <T> void assertAttributeEqualsContinually(Map<?,?> flags, final Entity entity, final AttributeSensor<T> attribute, final T expected) {
        Asserts.succeedsContinually(flags, new Runnable() {
            @Override public void run() {
                assertAttributeEquals(entity, attribute, expected);
            }});
    }

    public static void assertGroupSizeEqualsEventually(final Group group, int expected) {
        assertGroupSizeEqualsEventually(ImmutableMap.of(), group, expected);
    }

    public static void assertGroupSizeEqualsEventually(Map<?,?> flags, final Group group, final int expected) {
        Asserts.succeedsEventually(castToMapWithStringKeys(flags), new Runnable() {
            @Override public void run() {
                Collection<Entity> members = group.getMembers();
                assertEquals(members.size(), expected, "members=" + members);
            }});
    }

    /**
     * Asserts that the entity's value for this attribute changes, by registering a subscription and checking the value.
     *
     * @param entity The entity whose attribute will be checked.
     * @param attribute The attribute to check on the entity.
     *
     * @throws AssertionError if the assertion fails.
     */
    public static void assertAttributeChangesEventually(final Entity entity, final AttributeSensor<?> attribute) {
        final Object origValue = entity.getAttribute(attribute);
        final AtomicBoolean changed = new AtomicBoolean();
        SubscriptionHandle handle = entity.subscriptions().subscribe(entity, attribute, new SensorEventListener<Object>() {
            @Override public void onEvent(SensorEvent<Object> event) {
                if (!Objects.equal(origValue, event.getValue())) {
                    changed.set(true);
                }
            }});
        try {
            Asserts.succeedsEventually(new Runnable() {
                @Override public void run() {
                    Asserts.assertTrue(changed.get(), entity + " -> " + attribute + " not changed from "+origValue);
                }});
        } finally {
            entity.subscriptions().unsubscribe(entity, handle);
        }
    }


    /**
     * Assert that the given attribute of an entity does not take any of the disallowed values during a given period.
     *
     * This method relies on {@link Asserts#succeedsContinually(Runnable)}, therefore it loops comparing the value
     * of the attribute to the disallowed values, rather than setting up a subscription.  It may therefore miss a
     * situation where the attribute temporarily takes a disallowed value. This method is therefore suited for use
     * where the attribute will take on a value permanently, which may or may not be disallowed.
     *
     * @param entity      The entity owning the attribute to check.
     * @param attribute   The attribute on the entity.
     * @param disallowed  The disallowed values for the entity.
     * @param <T>         Type of the sensor.
     */
    @Beta @SafeVarargs
    public static <T> void assertAttributeContinuallyNotEqualTo(final Entity entity, final AttributeSensor<T> attribute, T... disallowed) {
        final Set<T> reject = Sets.newHashSet(disallowed);
        Asserts.succeedsContinually(new Runnable() {
            @Override
            public void run() {
                T val = entity.getAttribute(attribute);
                Asserts.assertFalse(reject.contains(val),
                        "Attribute " + attribute + " on " + entity + " has disallowed value " + val);
            }
        });
    }

    /**
     * Assert that the given attribute of an entity does not take any of the disallowed values during a given period.
     *
     * This method relies on {@link Asserts#succeedsContinually(Runnable)}, therefore it loops comparing the value
     * of the attribute to the disallowed values, rather than setting up a subscription.  It may therefore miss a
     * situation where the attribute temporarily takes a disallowed value. This method is therefore suited for use
     * where the attribute will take on a value permanently, which may or may not be disallowed.
     *
     * @param flags       Flags controlling the loop, with keys: <ul>
     *                    <li>timeout: a {@link Duration} specification String for the duration for which to test the
     *                    assertion. Default 1 second.</li>
     *                    <li>period: a {@link Duration} specification String for the interval at which to perform polls
     *                    on the attribute value. Default 10ms.</li>
     *                   </ul>
     * @param entity      The entity owning the attribute to check.
     * @param attribute   The attribute on the entity.
     * @param disallowed  The disallowed values for the entity.
     * @param <T>         Type of the sensor.
     */
    @Beta @SafeVarargs
    public static <T> void assertAttributeContinuallyNotEqualTo(final Map<?, ?> flags, final Entity entity, final AttributeSensor<T> attribute, T... disallowed) {
        final Set<T> reject = Sets.newHashSet(disallowed);
        Asserts.succeedsContinually(flags, new Runnable() {
            @Override
            public void run() {
                T val = entity.getAttribute(attribute);
                Asserts.assertFalse(reject.contains(val),
                        "Attribute " + attribute + " on " + entity + " has disallowed value " + val);
            }
        });
    }

    /**
     * Asserts sensors {@code service.isUp} is true, and that {@code service.state} is "running".
     * Setting these sensors is common behaviour for entities, but depends on the particular entity
     * implementation.
     */
    @Beta
    public static void assertEntityHealthy(Entity entity) {
        assertAttributeEquals(entity, SERVICE_UP, true);
        assertAttributeEquals(entity, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
        assertAttribute(entity, Attributes.SERVICE_STATE_EXPECTED, new Predicate<Lifecycle.Transition>() {
            @Override public boolean apply(Lifecycle.Transition transition) {
                assertNotNull(transition);
                return Lifecycle.RUNNING.equals(transition.getState());
            }
        });
    }

    /**
     * Asserts sensors {@code service.isUp} is false, and that {@code service.state} is "on fire".
     * Setting these sensors is common behaviour for entities, but depends on the particular entity
     * implementation.
     */
    @Beta
    public static void assertEntityFailed(Entity entity) {
        assertAttributeEquals(entity, SERVICE_UP, false);
        assertAttributeEquals(entity, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.ON_FIRE);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> castToMapWithStringKeys(Map<?, ?> map) {
        // TODO when checking that all keys are strings
        if (map == null) return ImmutableMap.of();
        for (Object key : map.keySet()) {
            if (!(key instanceof String)) {
                IllegalArgumentException e = new IllegalArgumentException("Invalid non-string key(s), type " + key.getClass().getName()+" in map");
                e.fillInStackTrace();
                LOG.warn("Deprecated: invalid key(s) in map (continuing)", e);
                break;
            }
        }
        return (Map<String, ?>) map;
    }
}
