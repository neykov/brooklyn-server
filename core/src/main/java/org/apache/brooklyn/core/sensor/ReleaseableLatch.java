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
package org.apache.brooklyn.core.sensor;

import java.util.concurrent.Semaphore;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.util.core.task.DeferredSupplier;
import org.apache.brooklyn.util.core.task.ImmediateSupplier;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;

// DeferredSupplier used as a marker interface to prevent coercion. When resolved it must evaluate to {@code Boolean.TRUE}.
public interface ReleaseableLatch extends DeferredSupplier<Boolean>, ImmediateSupplier<Boolean> {
    /**
     * Increment usage count for the {@code caller} entity
     */
    void acquire(Entity caller);

    /**
     * Decrement usage count for the {@code caller} entity
     */
    void release(Entity caller);

    static abstract class AbstractReleaseableLatch implements ReleaseableLatch {
        // Instances coerce to TRUE as they are non-null.
        @Override public Boolean get() {return Boolean.TRUE;}
        @Override public Maybe<Boolean> getImmediately() {return Maybe.of(Boolean.TRUE);}
    }

    ReleaseableLatch NOP = new Factory.NopLatch();

    static class Factory {
        private static class NopLatch extends AbstractReleaseableLatch {
            @Override public void acquire(Entity caller) {}
            @Override public void release(Entity caller) {}
        }

        private static class MaxConcurrencyLatch extends AbstractReleaseableLatch {
            private int permits;
            private transient final Semaphore sem;

            public MaxConcurrencyLatch(int permits) {
                this.permits = permits;
                this.sem = new Semaphore(permits);
            }

            @Override
            public void acquire(Entity caller) {
                try {
                    sem.acquire();
                } catch (InterruptedException e) {
                    throw Exceptions.propagate(e);
                }
            }

            @Override
            public void release(Entity caller) {
                sem.release();
            }

            // On rebind reset thread count
            private Object readResolve() {
                return newMaxConcurrencyLatch(permits);
            }

            @Override
            public String toString() {
                return getClass().getSimpleName() + "[permits=" + sem.availablePermits() + "/" + permits + "]";
            }
        }

        public static ReleaseableLatch newMaxConcurrencyLatch(int maxThreadsNum) {
            return new MaxConcurrencyLatch(maxThreadsNum);
        }

    }

}
