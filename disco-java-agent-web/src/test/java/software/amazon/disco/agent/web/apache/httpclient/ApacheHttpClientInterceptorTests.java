/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License").
 *   You may not use this file except in compliance with the License.
 *   A copy of the License is located at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   or in the "license" file accompanying this file. This file is distributed
 *   on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *   express or implied. See the License for the specific language governing
 *   permissions and limitations under the License.
 */

package software.amazon.disco.agent.web.apache.httpclient;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import software.amazon.disco.agent.concurrent.TransactionContext;
import software.amazon.disco.agent.event.Event;
import software.amazon.disco.agent.event.EventBus;
import software.amazon.disco.agent.event.HeaderReplaceable;
import software.amazon.disco.agent.event.HttpServiceDownstreamRequestEvent;
import software.amazon.disco.agent.event.HttpServiceDownstreamResponseEvent;
import software.amazon.disco.agent.event.ServiceDownstreamResponseEvent;
import software.amazon.disco.agent.web.apache.source.ApacheClientTestUtil;
import software.amazon.disco.agent.web.apache.source.InterceptedBasicHttpResponse;
import software.amazon.disco.agent.web.apache.source.InterceptedHttpRequestBase;
import software.amazon.disco.agent.web.apache.source.MockEventBusListener;
import software.amazon.disco.agent.web.apache.source.SomeChainedExecuteMethodsHttpClient;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ApacheHttpClientInterceptorTests {

    private ApacheHttpClientInterceptor interceptor;
    private HttpResponse expectedResponse;
    private IOException expectedIOException;
    private MockEventBusListener mockEventBusListener;

    @Before
    public void before() {
        interceptor = new ApacheHttpClientInterceptor();
        expectedResponse = null;
        expectedIOException = null;
        mockEventBusListener = new MockEventBusListener();
        TransactionContext.create();
        EventBus.addListener(mockEventBusListener);
    }

    @After
    public void after() {
        TransactionContext.destroy();
        EventBus.removeListener(mockEventBusListener);
    }

    @Test
    public void testInstallation() {
        AgentBuilder agentBuilder = mock(AgentBuilder.class);
        AgentBuilder.Identified.Extendable extendable = mock(AgentBuilder.Identified.Extendable.class);
        AgentBuilder.Identified.Narrowable narrowable = mock(AgentBuilder.Identified.Narrowable.class);
        when(agentBuilder.type(any(ElementMatcher.class))).thenReturn(narrowable);
        when(narrowable.transform(any(AgentBuilder.Transformer.class))).thenReturn(extendable);
        AgentBuilder result = interceptor.install(agentBuilder);
        assertSame(extendable, result);
    }

    @Test
    public void testClassMatcherSucceedsOnRealClient() {
        assertTrue(classMatches(HttpClients.createMinimal().getClass()));
        assertTrue(classMatches(HttpClients.createMinimal().getClass().getSuperclass()));
    }

    @Test
    public void testClassMatcherSucceededOnConcreteClass() {
        assertTrue(classMatches(SomeChainedExecuteMethodsHttpClient.class));
    }

    @Test
    public void testClassMatcherFailedOnInterface() {
        assertFalse(classMatches(HttpClient.class));
    }

    @Test
    public void testClassMatcherFailedOnUnrelatedClass() {
        assertFalse(classMatches(String.class));
    }

    @Test
    public void testMethodMatcherSucceedsOnRealClient() throws Exception {
        HttpClient client = HttpClients.createMinimal();
        Class<?> clazz = client.getClass();
        assertEquals(CloseableHttpClient.class, clazz.getSuperclass());
        assertEquals(12, methodMatchedCount("execute", clazz.getSuperclass()));
    }

    /**
     * Matched count should be equivalent to the number of target `execute` method in {@link SomeChainedExecuteMethodsHttpClient}.
     */
    @Test
    public void testMethodMatcherSucceeded() throws Exception {
        assertEquals(11, methodMatchedCount("execute", SomeChainedExecuteMethodsHttpClient.class));
    }

    @Test
    public void testMethodMatcherFailedOnAbstractMethod() throws Exception {
        assertEquals(0, methodMatchedCount("execute", HttpClient.class));
    }

    @Test(expected = NoSuchMethodException.class)
    public void testMethodMatcherFailedOnNotExistingMethod() throws Exception {
        assertEquals(0, methodMatchedCount("doesntExistMethodName", SomeChainedExecuteMethodsHttpClient.class));
    }

    @Test(expected = NoSuchMethodException.class)
    public void testMethodMatcherFailedOnWrongClass() throws Exception {
        assertEquals(0, methodMatchedCount("execute", String.class));
    }


    /**
     * Helper method to test the class matcher matching
     *
     * @param clazz Class type we are validating
     * @return true if matches else false
     */
    private boolean classMatches(Class clazz) {
        return ApacheHttpClientInterceptor.buildClassMatcher().matches(new TypeDescription.ForLoadedType(clazz));
    }

    /**
     * Helper method to test the method matcher against an input class
     *
     * @param methodName name of method
     * @param paramType  class we are verifying contains the method
     * @return Matched methods count
     * @throws NoSuchMethodException
     */
    private int methodMatchedCount(String methodName, Class paramType) throws NoSuchMethodException {
        List<Method> methods = new ArrayList<>();
        for (Method m : paramType.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                methods.add(m);
            }
        }

        if (methods.size() == 0) throw new NoSuchMethodException();

        int matchedCount = 0;
        for (Method m : methods) {
            MethodDescription.ForLoadedMethod forLoadedMethod = new MethodDescription.ForLoadedMethod(m);
            if (ApacheHttpClientInterceptor.buildMethodMatcher(new TypeDescription.ForLoadedType(paramType)).matches(forLoadedMethod)) {
                matchedCount++;
            }
        }
        return matchedCount;
    }
    @Test
    public void testHeaderReplacement() throws Throwable {
        HttpUriRequest get = new InterceptedHttpRequestBase();
        get.addHeader("foo", "bar");
        get.addHeader("foo", "bar2");

        SomeChainedExecuteMethodsHttpClient someHttpClient = new SomeChainedExecuteMethodsHttpClient();
        someHttpClient.setExpectedResponse(expectedResponse);
        ApacheHttpClientMethodDelegation.intercept(new Object[] {get}, "origin", () -> someHttpClient.execute(get));

        List<Event> events = mockEventBusListener.getReceivedEvents();
        HeaderReplaceable event = (HeaderReplaceable)events.get(0);
        event.replaceHeader("foo", "bar3");

        assertEquals(1, get.getHeaders("foo").length);
        assertEquals("bar3", get.getFirstHeader("foo").getValue());
    }

    /**
     * Intercepts chained methods starts here: {@link SomeChainedExecuteMethodsHttpClient#execute(HttpUriRequest)}
     */
    @Test
    public void testInterceptorSucceededOnChainedMethods() throws Throwable {
        HttpUriRequest request = new InterceptedHttpRequestBase();

        // Set up victim http client
        SomeChainedExecuteMethodsHttpClient someHttpClient = new SomeChainedExecuteMethodsHttpClient();
        expectedResponse = new InterceptedBasicHttpResponse(new ProtocolVersion("protocol", 1, 1), 200, "");
        someHttpClient.setExpectedResponse(expectedResponse);
        ApacheHttpClientMethodDelegation.intercept(new Object[] {request}, "origin", () -> someHttpClient.execute(request));

        List<Event> events = mockEventBusListener.getReceivedEvents();
        // Verify only one of interceptions does the interceptor business logic even if there is a method chaining,
        // as a result, only two service downstream events are published (request/response)
        assertEquals(2, events.size());

        // Verify the Request Event
        ApacheClientTestUtil.verifyServiceRequestEvent((HttpServiceDownstreamRequestEvent) events.get(0));

        // Verify the Response Event
        HttpServiceDownstreamResponseEvent serviceDownstreamResponseEvent = (HttpServiceDownstreamResponseEvent) events.get(1);
        ApacheClientTestUtil.verifyServiceResponseEvent(serviceDownstreamResponseEvent);
        assertNull(serviceDownstreamResponseEvent.getResponse());
        assertNull(serviceDownstreamResponseEvent.getThrown());
        assertEquals(200, serviceDownstreamResponseEvent.getStatusCode());
        assertEquals(3, someHttpClient.getExecuteMethodChainingDepth());
    }

    /**
     * Intercepts {@link SomeChainedExecuteMethodsHttpClient#execute(InterceptedHttpRequestBase)},
     */
    @Test(expected = IOException.class)
    public void testInterceptorSucceededAndReThrowOnException() throws Throwable {
        // Set up victim http client
        expectedIOException = new IOException();
        InterceptedHttpRequestBase request = new InterceptedHttpRequestBase();
        SomeChainedExecuteMethodsHttpClient someHttpClient = new SomeChainedExecuteMethodsHttpClient();
        someHttpClient.setExpectedIOException(expectedIOException);
        try {
            ApacheHttpClientMethodDelegation.intercept(new Object[]{request}, "origin", () -> someHttpClient.execute(request));
        } finally {
            List<Event> events = mockEventBusListener.getReceivedEvents();
            // Verify only one of interceptions does the interceptor business logic even if there is a method chaining,
            // as a result, only two service downstream events are published (request/response)
            assertEquals(2, events.size());

            // Verify the Request Event
            ApacheClientTestUtil.verifyServiceRequestEvent((HttpServiceDownstreamRequestEvent) events.get(0));

            // Verify the Response Event
            ServiceDownstreamResponseEvent serviceDownstreamResponseEvent = (ServiceDownstreamResponseEvent) events.get(1);
            ApacheClientTestUtil.verifyServiceResponseEvent(serviceDownstreamResponseEvent);
            assertNull(serviceDownstreamResponseEvent.getResponse());
            assertEquals(expectedIOException, serviceDownstreamResponseEvent.getThrown());

            assertEquals(1, someHttpClient.getExecuteMethodChainingDepth());
        }
    }

    @Test
    public void testInterceptorSucceedsOnResponseHandlerExecute() throws Throwable {
        HttpUriRequest get = new InterceptedHttpRequestBase();

        SomeChainedExecuteMethodsHttpClient someHttpClient = new SomeChainedExecuteMethodsHttpClient();
        ResponseHandler<Object> responseHandler = Mockito.mock(ResponseHandler.class);
        Mockito.when(responseHandler.handleResponse(Mockito.any())).thenReturn(new Object());
        ApacheHttpClientMethodDelegation.intercept(new Object[] {get}, "origin", () -> someHttpClient.execute(get, responseHandler));

        List<Event> events = mockEventBusListener.getReceivedEvents();

        // Verify only one of interceptions does the interceptor business logic even if there is a method chaining,
        // as a result, only two service downstream events are published (request/response)
        assertEquals(2, events.size());

        // Verify the Request Event
        ApacheClientTestUtil.verifyServiceRequestEvent((HttpServiceDownstreamRequestEvent) events.get(0));

        // Verify the Response Event
        ServiceDownstreamResponseEvent serviceDownstreamResponseEvent = (ServiceDownstreamResponseEvent) events.get(1);
        ApacheClientTestUtil.verifyServiceResponseEvent(serviceDownstreamResponseEvent);
    }
}