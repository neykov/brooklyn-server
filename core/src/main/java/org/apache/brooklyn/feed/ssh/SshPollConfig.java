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
package org.apache.brooklyn.feed.ssh;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;

import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.core.feed.PollConfig;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;

public class SshPollConfig<T> extends PollConfig<SshPollValue, T, SshPollConfig<T>> {

    private Supplier<String> commandSupplier;
    private List<Supplier<Map<String,String>>> dynamicEnvironmentSupplier = MutableList.of();

    public static final Predicate<SshPollValue> DEFAULT_SUCCESS = new Predicate<SshPollValue>() {
        @Override
        public boolean apply(@Nullable SshPollValue input) {
            return input != null && input.getExitStatus() == 0;
        }};

    public static <T> SshPollConfig<T> forSensor(AttributeSensor<T> sensor) {
        return new SshPollConfig<T>(sensor);
    }

    public static SshPollConfig<Void> forMultiple() {
        return new SshPollConfig<Void>(PollConfig.NO_SENSOR);
    }

    public SshPollConfig(AttributeSensor<T> sensor) {
        super(sensor);
        super.checkSuccess(DEFAULT_SUCCESS);
    }

    public SshPollConfig(SshPollConfig<T> other) {
        super(other);
        commandSupplier = other.commandSupplier;
    }
    
    /** @deprecated since 0.7.0; use {@link #getCommandSupplier()} and resolve just-in-time */
    @Deprecated
    public String getCommand() {
        return getCommandSupplier().get();
    }
    public Supplier<String> getCommandSupplier() {
        return commandSupplier;
    }
    
    /** @deprecated since 0.7.0; use {@link #getEnvSupplier()} and resolve just-in-time */
    @Deprecated
    public Map<String, String> getEnv() {
        return getEnvSupplier().get();
    }
    
    @SuppressWarnings("unused")
    public Supplier<Map<String,String>> getEnvSupplier() {
        if (true) return new CombiningEnvSupplier(dynamicEnvironmentSupplier);
        
        // TODO Kept in case it's persisted; new code will not use this.
        return new Supplier<Map<String,String>>() {
            @Override
            public Map<String, String> get() {
                Map<String,String> result = MutableMap.of();
                for (Supplier<Map<String, String>> envS: dynamicEnvironmentSupplier) {
                    if (envS!=null) {
                        Map<String, String> envM = envS.get();
                        if (envM!=null) {
                            mergeEnvMaps(envM, result);
                        }
                    }
                }
                return result;
            }
            private void mergeEnvMaps(Map<String,String> supplied, Map<String,String> target) {
                if (supplied==null) return;
                // as the value is a string there is no need to look at deep merge behaviour
                target.putAll(supplied);
            }
        };
    }
    
    private static class CombiningEnvSupplier implements Supplier<Map<String,String>> {
        private final List<Supplier<Map<String, String>>> dynamicEnvironmentSupplier;
        
        public CombiningEnvSupplier(List<Supplier<Map<String,String>>> dynamicEnvironmentSupplier) {
            this.dynamicEnvironmentSupplier = checkNotNull(dynamicEnvironmentSupplier, "dynamicEnvironmentSupplier");
        }
        @Override
        public Map<String, String> get() {
            Map<String,String> result = MutableMap.of();
            for (Supplier<Map<String, String>> envS: dynamicEnvironmentSupplier) {
                if (envS!=null) {
                    Map<String, String> envM = envS.get();
                    if (envM!=null) {
                        mergeEnvMaps(envM, result);
                    }
                }
            }
            return result;
        }
        protected void mergeEnvMaps(Map<String,String> supplied, Map<String,String> target) {
            if (supplied==null) return;
            // as the value is a string there is no need to look at deep merge behaviour
            target.putAll(supplied);
        }
        @Override
        public int hashCode() {
            return dynamicEnvironmentSupplier.hashCode();
        }
        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof CombiningEnvSupplier)) return false;
            CombiningEnvSupplier o = (CombiningEnvSupplier) obj;
            
            return Objects.equal(dynamicEnvironmentSupplier, o.dynamicEnvironmentSupplier);
        }
        @Override
        public String toString() {
            return "CombiningEnvSupplier("+dynamicEnvironmentSupplier+")";
        }
    }

    public SshPollConfig<T> command(String val) { return command(Suppliers.ofInstance(val)); }
    public SshPollConfig<T> command(Supplier<String> val) {
        this.commandSupplier = val;
        return this;
    }

    /** add the given env param; sequence is as per {@link #env(Supplier)} */
    public SshPollConfig<T> env(String key, String val) {
        return env(Collections.singletonMap(key, val));
    }
    
    /** add the given env params; sequence is as per {@link #env(Supplier)}.
     * behaviour is undefined if the map supplied here is subsequently changed.
     * <p>
     * if a map's contents might change, use {@link #env(Supplier)} */
    public SshPollConfig<T> env(Map<String,String> val) {
        if (val==null) return this;
        return env(Suppliers.ofInstance(val));
    }

    /** 
     * adds the given dynamic supplier of environment variables.
     * <p>
     * use of a supplier allows env vars to be computed on each execution,
     * for example to take the most recent sensor values.
     * <p>
     * in the case of multiple map suppliers, static maps, or static {@link #env(String, String)} 
     * key value pairs, the order in which they are specified here is the order
     * in which they are computed and applied. 
     **/
    public SshPollConfig<T> env(Supplier<Map<String,String>> val) {
        Preconditions.checkNotNull(val);
        dynamicEnvironmentSupplier.add(val);
        return this;
    }

    @Override protected String toStringBaseName() { return "ssh"; }
    @Override protected Object toStringPollSource() {
        if (getCommandSupplier()==null) return null;
        String command = getCommandSupplier().get();
        return command;
    }
    @Override protected MutableList<Object> equalsFields() { 
        return super.equalsFields()
            .appendIfNotNull(getCommandSupplier()!=null ? getCommandSupplier().get() : null)
            .appendIfNotNull(getEnvSupplier()!=null ? getEnvSupplier().get() : null); 
    }
    
}
