/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.brooklyn.core.entity;

import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.sensor.SensorEvent;
import org.apache.brooklyn.api.sensor.SensorEventListener;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.core.task.Tasks;
import org.testng.Assert;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.primitives.Longs;

/**
 * An event listener that records each event and allows callers to access all values and
 * all values sorted by event timestamp.
 */
public class RecordingSensorEventListener<T> implements SensorEventListener<T>, Iterable<SensorEvent<T>> {

    private final List<SensorEvent<T>> events = Lists.newCopyOnWriteArrayList();
    private final List<Task<?>> tasks = Lists.newCopyOnWriteArrayList();

    private final boolean suppressDuplicates;
    private T lastValue;

    public RecordingSensorEventListener() {
        this(false);
    }

    public RecordingSensorEventListener(boolean suppressDuplicates) {
        this.suppressDuplicates = suppressDuplicates;
    }

    @Override
    public void onEvent(SensorEvent<T> event) {
        if (!suppressDuplicates || events.isEmpty() || !Objects.equals(lastValue, event.getValue())) {
            events.add(event);
            tasks.add(Tasks.current());
            lastValue = event.getValue();
        }
    }

    /**
     * @return An immutable iterable of the recorded events.
     */
    public List<SensorEvent<T>> getEvents() {
        return ImmutableList.copyOf(events);
    }

    /**
     * The {@link {@link Tasks#current()} for each call to {@link #onEvent(SensorEvent)}
     */
    public List<Task<?>> getTasks() {
        return MutableList.copyOf(tasks).asUnmodifiable();
    }

    /**
     * @return A live read-only view of recorded events.
     */
    public Iterable<T> getEventValues() {
        return FluentIterable.from(events)
                .transform(new GetValueFunction<T>());
    }

    /**
     * @return A static read-only view of event values sorted by the time at which they occurred.
     */
    public Iterable<T> getEventValuesSortedByTimestamp() {
        List<SensorEvent<T>> copy = Lists.newArrayList(events);
        Collections.sort(copy, new EventTimestampComparator());
        return FluentIterable.from(copy)
                .transform(new GetValueFunction<T>());
    }

    public void assertHasEvent(Predicate<? super SensorEvent<T>> filter) {
        for (SensorEvent<T> event : events) {
            if (filter.apply(event)) {
                return;
            }
        }
        Assert.fail("No event matching filter "+ filter);
    }

    public void assertHasEventEventually(final Predicate<? super SensorEvent<T>> filter) {
        Asserts.succeedsEventually(new Runnable() {
            @Override
            public void run() {
                assertHasEvent(filter);
            }});
    }
    
    public void assertEventCount(int expected) {
        Assert.assertEquals(events.size(), expected, "events="+events);
    }

    /**
     * Clears all events recorded by the listener.
     */
    public void clearEvents() {
        events.clear();
        tasks.clear();
        lastValue = null;
    }

    @Override
    public Iterator<SensorEvent<T>> iterator() {
        return getEvents().iterator();
    }

    private static class GetValueFunction<T> implements Function<SensorEvent<T>, T> {
        @Override
        public T apply(SensorEvent<T> input) {
            return input.getValue();
        }
    }

    private static class EventTimestampComparator implements Comparator<SensorEvent<?>> {
        @Override
        public int compare(SensorEvent<?> o1, SensorEvent<?> o2) {
            return Longs.compare(o1.getTimestamp(), o2.getTimestamp());
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[size=" + getEvents().size() + "; events=" + getEvents() + "]";
    }
}
