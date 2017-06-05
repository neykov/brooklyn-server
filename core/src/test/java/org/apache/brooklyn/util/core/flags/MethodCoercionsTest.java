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
package org.apache.brooklyn.util.core.flags;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.List;

import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;

public class MethodCoercionsTest {

    private Method staticMultiParameterMethod;
    private Method singleParameterMethod;
    private Method multiParameterMethod;
    private Method singleCollectionParameterMethod;

    @BeforeClass
    public void testFixtureSetUp() {
        try {
            staticMultiParameterMethod = TestClass.class.getMethod("staticMultiParameterMethod", boolean.class, int.class);
            singleParameterMethod = TestClass.class.getMethod("singleParameterMethod", int.class);
            multiParameterMethod = TestClass.class.getMethod("multiParameterMethod", boolean.class, int.class);
            singleCollectionParameterMethod = TestClass.class.getMethod("singleCollectionParameterMethod", List.class);
        } catch (NoSuchMethodException e) {
            throw Exceptions.propagate(e);
        }
    }

    @Test
    public void testMatchSingleParameterMethod() throws Exception {
        Predicate<Method> predicate = MethodCoercions.matchSingleParameterMethod("singleParameterMethod", "42");
        assertTrue(predicate.apply(singleParameterMethod));
        assertFalse(predicate.apply(multiParameterMethod));
        assertFalse(predicate.apply(singleCollectionParameterMethod));
    }

    @Test
    public void testMatchSingleCollectionParameterMethod() throws Exception {
        Predicate<Method> predicate = MethodCoercions.matchSingleParameterMethod("singleCollectionParameterMethod", ImmutableList.of("42"));
        assertFalse(predicate.apply(singleParameterMethod));
        assertFalse(predicate.apply(multiParameterMethod));
        assertTrue(predicate.apply(singleCollectionParameterMethod));
    }

    @Test
    public void testMatchMethodByName() throws Exception {
        Predicate<Method> predicate = MethodCoercions.matchMethodByName("singleParameterMethod");
        assertTrue(predicate.apply(singleParameterMethod));
        assertFalse(predicate.apply(multiParameterMethod));
        assertFalse(predicate.apply(singleCollectionParameterMethod));
    }

    @Test
    public void testMatchMultiParameterMethod() throws Exception {
        Predicate<Method> predicate = MethodCoercions.matchMultiParameterMethod("multiParameterMethod", ImmutableList.of("true", "42"));
        assertFalse(predicate.apply(singleParameterMethod));
        assertTrue(predicate.apply(multiParameterMethod));
        assertFalse(predicate.apply(singleCollectionParameterMethod));
    }

    @Test
    public void testMatchMultiParameterMethodWithoutName() throws Exception {
        Predicate<Method> predicate = MethodCoercions.matchMultiParameterMethod(ImmutableList.of("true", "42"));
        assertFalse(predicate.apply(singleParameterMethod));
        assertTrue(predicate.apply(multiParameterMethod));
        assertFalse(predicate.apply(singleCollectionParameterMethod));
    }

    @Test
    public void testTryFindAndInvokeMultiParameterMethod() throws Exception {
        TestClass instance = new TestClass();
        Maybe<?> maybe = MethodCoercions.tryFindAndInvokeMultiParameterMethod(instance, "multiParameterMethod", ImmutableList.of("true", "42"));
        assertTrue(maybe.isPresent());
        assertTrue(instance.wasMultiParameterMethodCalled());
    }

    @Test
    public void testTryFindAndInvokeStaticMultiParameterMethod() throws Exception {
        try {
            Maybe<?> maybe = MethodCoercions.tryFindAndInvokeMultiParameterMethod(TestClass.class, ImmutableList.of(staticMultiParameterMethod), ImmutableList.of("true", "42"));
            assertTrue(maybe.isPresent());
            assertTrue(TestClass.wasStaticMultiParameterMethodCalled());
        } finally {
            TestClass.clear();
        }
    }

    @Test
    public void testTryFindAndInvokeBestMatchingMethod() throws Exception {
        TestClass instance = new TestClass();
        Maybe<?> maybe = MethodCoercions.tryFindAndInvokeBestMatchingMethod(instance, "singleParameterMethod", "42");
        assertTrue(maybe.isPresent());
        assertTrue(instance.wasSingleParameterMethodCalled());

        instance = new TestClass();
        maybe = MethodCoercions.tryFindAndInvokeBestMatchingMethod(instance, "multiParameterMethod", ImmutableList.of("true", "42"));
        assertTrue(maybe.isPresent());
        assertTrue(instance.wasMultiParameterMethodCalled());

        instance = new TestClass();
        maybe = MethodCoercions.tryFindAndInvokeBestMatchingMethod(instance, "singleCollectionParameterMethod", ImmutableList.of("fred", "joe"));
        assertTrue(maybe.isPresent());
        assertTrue(instance.wasSingleCollectionParameterMethodCalled());
    }

    @Test
    public void testTryFindAndInvokeBestMatchingMethodOnPrivateClassWithPublicSuper() throws Exception {
        PrivateClass instance = new PrivateClass();
        Maybe<?> maybe = MethodCoercions.tryFindAndInvokeBestMatchingMethod(instance, "methodOnSuperClass", "42");
        assertTrue(maybe.isPresent());
        assertTrue(instance.wasMethodOnSuperClassCalled());
        
        Maybe<?> maybe2 = MethodCoercions.tryFindAndInvokeBestMatchingMethod(instance, "methodOnInterface", "42");
        assertTrue(maybe2.isPresent());
        assertTrue(instance.wasMethodOnInterfaceCalled());
    }

    @Test
    public void testTryFindAndInvokeSingleParameterMethod() throws Exception {
        TestClass instance = new TestClass();
        Maybe<?> maybe = MethodCoercions.tryFindAndInvokeSingleParameterMethod(instance, "singleParameterMethod", "42");
        assertTrue(maybe.isPresent());
        assertTrue(instance.wasSingleParameterMethodCalled());
    }

    @Test
    public void testTryFindAndInvokeSingleCollectionParameterMethod() throws Exception {
        TestClass instance = new TestClass();
        Maybe<?> maybe = MethodCoercions.tryFindAndInvokeSingleParameterMethod(instance, "singleCollectionParameterMethod", ImmutableList.of("42"));
        assertTrue(maybe.isPresent());
        assertTrue(instance.wasSingleCollectionParameterMethodCalled());
    }

    public static class TestClass {

        private static boolean staticMultiParameterMethodCalled;
        
        private boolean singleParameterMethodCalled;
        private boolean multiParameterMethodCalled;
        private boolean singleCollectionParameterMethodCalled;

        public static void staticMultiParameterMethod(boolean parameter1, int parameter2) {
            staticMultiParameterMethodCalled = true;
        }

        public static boolean wasStaticMultiParameterMethodCalled() {
            return staticMultiParameterMethodCalled;
        }

        public static void clear() {
            staticMultiParameterMethodCalled = false;
        }
        
        public void singleParameterMethod(int parameter) {
            singleParameterMethodCalled = true;
        }

        public void multiParameterMethod(boolean parameter1, int parameter2) {
            multiParameterMethodCalled = true;
        }

        public void singleCollectionParameterMethod(List<String> parameter) {
            singleCollectionParameterMethodCalled = true;
        }

        public boolean wasSingleParameterMethodCalled() {
            return singleParameterMethodCalled;
        }

        public boolean wasMultiParameterMethodCalled() {
            return multiParameterMethodCalled;
        }

        public boolean wasSingleCollectionParameterMethodCalled() {
            return singleCollectionParameterMethodCalled;
        }
    }

    public static abstract class PublicSuperClass {
        public abstract PublicSuperClass methodOnSuperClass(int arg);
    }
        
    public static interface PublicInterface {
        public PublicInterface methodOnInterface(int arg);
    }
        
    static class PrivateClass extends PublicSuperClass implements PublicInterface {

        private boolean methodOnSuperClassCalled;
        private boolean methodOnInterfaceCalled;

        public PrivateClass() {}
        
        @Override
        public PrivateClass methodOnSuperClass(int arg) {
            methodOnSuperClassCalled = true;
            return this;
        }
        
        @Override
        public PrivateClass methodOnInterface(int arg) {
            methodOnInterfaceCalled = true;
            return this;
        }
        
        public boolean wasMethodOnSuperClassCalled() {
            return methodOnSuperClassCalled;
        }

        public boolean wasMethodOnInterfaceCalled() {
            return methodOnInterfaceCalled;
        }
    }
}
