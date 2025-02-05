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

package software.amazon.disco.agent.integtest.web.servlet;

import software.amazon.disco.agent.event.Event;
import software.amazon.disco.agent.event.HttpNetworkProtocolRequestEvent;
import software.amazon.disco.agent.event.HttpNetworkProtocolResponseEvent;
import software.amazon.disco.agent.event.Listener;
import software.amazon.disco.agent.event.TransactionBeginEvent;
import software.amazon.disco.agent.event.TransactionEndEvent;
import software.amazon.disco.agent.integtest.web.servlet.source.FakeChainedServiceCallServlet;
import software.amazon.disco.agent.integtest.web.servlet.source.FakeOverriddenNestedServlet;
import software.amazon.disco.agent.integtest.web.servlet.source.FakeOverriddenServlet;
import software.amazon.disco.agent.integtest.web.servlet.source.FakeOverrideThrowExceptionServlet;
import software.amazon.disco.agent.integtest.web.servlet.source.FakeServletUseDefaultService;
import software.amazon.disco.agent.reflect.concurrent.TransactionContext;
import software.amazon.disco.agent.reflect.event.EventBus;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class HttpServletServiceInterceptorTests {
    private EventBusListener eventBusListener;
    HttpServletRequest request;
    HttpServletResponse response;

    @Before
    public void before() {
        // ensure TX begins in a good state
        Assert.assertFalse(TransactionContext.isWithinCreatedContext());

        request = Mockito.mock(HttpServletRequest.class);
        response = Mockito.mock(HttpServletResponse.class);

        // Required for servlet's service() calls
        Mockito.when(request.getMethod()).thenReturn("GET");
        Mockito.when(request.getProtocol()).thenReturn("HTTP/1.1");
        Mockito.when(request.getRequestURL()).thenReturn(new StringBuffer("URI"));
        eventBusListener = new EventBusListener();
        EventBus.addListener(eventBusListener);
    }

    @After
    public void after() {
        EventBus.removeListener(eventBusListener);
        // ensure TX is left in a good state
        Assert.assertFalse(TransactionContext.isWithinCreatedContext());
    }

    @Test
    public void testServletUseDefaultServiceTest() throws Throwable {
        //this servlet has no impl of service(), it just uses the one in the abstract base
        testServlet(new FakeServletUseDefaultService());
    }

    @Test
    public void testServletUseInheritedServiceCallingDefaultTest() throws Throwable {
        //this servlet implements service(), but calls to its super.service() impl
        testServlet(new FakeChainedServiceCallServlet());
    }

    @Test
    public void testServiceInterceptionOverridden() throws Throwable{
        FakeOverriddenServlet servlet = new FakeOverriddenServlet();

        servlet.service(request, response);

        // Confirm that the overridden service method was ran.
        Assert.assertTrue(servlet.didRunService());

        // Ensure that we captured 4 events. Signifies that we matched it.
        testEvents();
    }

    @Test
    public void testServiceInterceptionChained() throws Throwable{
        FakeChainedServiceCallServlet servlet = new FakeChainedServiceCallServlet();

        List<String> indicator = new ArrayList<>();
        servlet.service(request, response, 1, 2, 3, 4, indicator);

        // Ensure that the chain of "service()" methods were all called.
        Assert.assertEquals(1, indicator.size());

        // Ensure that we captured only 4 events. Chained calls should not give us more events.
        // The matcher "shouldn't" capture the other events anyways, but as a sanity check,
        // it's always good to ensure that more events aren't added through chained calls.
        testEvents();
    }

    @Test
    public void testNestedServiceInterception() throws Throwable {
        FakeOverriddenNestedServlet servlet = new FakeOverriddenNestedServlet();
        servlet.service(request, response);

        // Ensure that we captured only 4 events. Nested servlets reference count via the TX and only emit one pair of TX events
        //and one pair of Service events
        testEvents();

        // ensure TX is left in a good state
        Assert.assertFalse(TransactionContext.isWithinCreatedContext());
    }

    @Test
    public void testNestedServiceInterceptionWhenThrows() throws Throwable {
        FakeOverriddenNestedServlet servlet = new FakeOverriddenNestedServlet();
        servlet.setThrow();

        try {
            servlet.service(request, response);
            Assert.fail();
        } catch (ServletException e) {
            //fail() above if this was *not* thrown
        }

        // Ensure that we captured only 4 events. Nested servlets reference count via the TX and only emit one pair of TX events
        //and one pair of Service events
        testEvents();

        // ensure TX is left in a good state
        Assert.assertFalse(TransactionContext.isWithinCreatedContext());
    }

    @Test
    public void testServiceInterceptionException() throws Throwable {
        // If the application code throws an exception, we should still be able to capture the http events.
        FakeOverrideThrowExceptionServlet servlet = new FakeOverrideThrowExceptionServlet();

        Throwable thrown;
        try {
            servlet.service(request, response);
            Assert.fail();
        } catch (ServletException e) {
            thrown = e;
        }

        // Even though the service threw an exception, we still got http and TX events.
        testEvents();
    }

    // helper to test the same conditions for a variety of different concrete servlets
    private void testServlet(HttpServlet servlet) throws Throwable {

        // Mock HttpServletRequest object.
        List<String> reqHeaderNames = new ArrayList<>();
        reqHeaderNames.add("date");
        reqHeaderNames.add("host");
        reqHeaderNames.add("origin");
        reqHeaderNames.add("referer");
        reqHeaderNames.add("user-agent");
        reqHeaderNames.add("custom-header");
        // Add common http header
        Mockito.when(request.getHeaderNames()).thenReturn(Collections.enumeration(reqHeaderNames));
        Mockito.when(request.getHeader("date")).thenReturn("Tue, 24 Oct 1995 08:12:31 GMT");
        Mockito.when(request.getHeader("host")).thenReturn("amazon.com");
        Mockito.when(request.getHeader("origin")).thenReturn("http://aws.amazon.com");
        Mockito.when(request.getHeader("referer")).thenReturn("http://amazon.com/explore/something");
        Mockito.when(request.getHeader("user-agent")).thenReturn("Mozilla/5.0 (X11; Linux x86_64; rv:12.0) Gecko/20100101 Firefox/12.0");
        // As well as custom headers and hope that we intercept it.
        Mockito.when(request.getHeader("custom-header")).thenReturn("custom-header-data");
        Mockito.when(request.getRequestURL()).thenReturn(new StringBuffer("http://request.amazon.com"));
        // Used for request event population.
        Mockito.when(request.getLocalAddr()).thenReturn("0.0.0.0");
        Mockito.when(request.getRemoteAddr()).thenReturn("1.1.1.1");
        Mockito.when(request.getLocalPort()).thenReturn(80);
        Mockito.when(request.getRemotePort()).thenReturn(100);

        // Mock HttpServletResponse object
        List<String> respHeaderNames = new ArrayList<>();
        respHeaderNames.add("more-custom-header");
        Mockito.when(response.getHeaderNames()).thenReturn(respHeaderNames);
        Mockito.when(response.getHeader("more-custom-header")).thenReturn("some-custom-data");
        Mockito.when(response.getStatus()).thenReturn(200);

        //wrap objects, otherwise Mockito will mock out the methods in the Accessor interface, such as retrieveHeaderMap
        request = new HttpServletRequestWrapper(request);
        response = new HttpServletResponseWrapper(response);
        servlet.service(request, response);

        // Ensure that we get only 4 events, even when a subclass and a superclass are both instrumented.
        testEvents();

        HttpNetworkProtocolRequestEvent requestEvent = (HttpNetworkProtocolRequestEvent) eventBusListener.events.get(1);
        HttpNetworkProtocolResponseEvent responseEvent = (HttpNetworkProtocolResponseEvent) eventBusListener.events.get(2);

        // Request Event assertions
        Assert.assertEquals(requestEvent, responseEvent.getHttpRequestEvent());
        Assert.assertNotNull(requestEvent);
        Assert.assertEquals(request, requestEvent.getRequest());
        Assert.assertEquals(request.getHeader("date"), requestEvent.getHeaderData("date"));
        Assert.assertEquals(request.getHeader("host"), requestEvent.getHeaderData("host"));
        Assert.assertEquals(request.getHeader("origin"), requestEvent.getHeaderData("origin"));
        Assert.assertEquals(request.getHeader("referer"), requestEvent.getHeaderData("referer"));
        Assert.assertEquals(request.getHeader("user-agent"), requestEvent.getHeaderData("user-agent"));
        Assert.assertEquals(request.getHeader("custom-header"), requestEvent.getHeaderData("custom-header"));
        Assert.assertEquals(request.getMethod(), requestEvent.getMethod());
        Assert.assertEquals(request.getRequestURL().toString(), requestEvent.getURL());
        Assert.assertEquals(request.getLocalAddr(), requestEvent.getLocalIPAddress());
        Assert.assertEquals(request.getRemoteAddr(), requestEvent.getRemoteIPAddress());
        Assert.assertEquals(request.getLocalPort(), requestEvent.getDestinationPort());
        Assert.assertEquals(request.getRemotePort(), requestEvent.getSourcePort());

        // Response event assertions
        Assert.assertNotNull(responseEvent);
        Assert.assertEquals(response, responseEvent.getResponse());
        Assert.assertEquals(response.getStatus(), responseEvent.getStatusCode());
        Assert.assertEquals(response.getHeader("more-custom-header"), responseEvent.getHeaderData("more-custom-header"));
    }

    private void testEvents() {
        Assert.assertEquals(4, eventBusListener.events.size());
        Assert.assertTrue(eventBusListener.events.get(0) instanceof TransactionBeginEvent);
        Assert.assertTrue(eventBusListener.events.get(1) instanceof HttpNetworkProtocolRequestEvent);
        Assert.assertTrue(eventBusListener.events.get(2) instanceof HttpNetworkProtocolResponseEvent);
        Assert.assertTrue(eventBusListener.events.get(3) instanceof TransactionEndEvent);
    }

    class EventBusListener implements Listener {
        List<Event> events = new LinkedList<>();
        @Override
        public int getPriority() {
            return 0;
        }

        @Override
        public void listen(Event e) {
            if (!TransactionContext.isWithinCreatedContext()) {
                return;
            }
            events.add(e);
        }
    }
}