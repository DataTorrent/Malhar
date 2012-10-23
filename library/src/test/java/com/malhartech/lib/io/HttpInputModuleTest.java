/**
 * Copyright (c) 2012-2012 Malhar, Inc.
 * All rights reserved.
 */
package com.malhartech.lib.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.Assert;

import org.apache.commons.io.IOUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mortbay.jetty.Handler;
import org.mortbay.jetty.Request;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.handler.AbstractHandler;

import com.malhartech.api.OperatorConfiguration;
import com.malhartech.dag.TestSink;


public class HttpInputModuleTest {

  @Test
  public void testHttpInputModule() throws Exception {

    final List<String> receivedMessages = new ArrayList<String>();
    Handler handler=new AbstractHandler()
    {
      int responseCount = 0;
        @Override
        public void handle(String target, HttpServletRequest request, HttpServletResponse response, int dispatch)
            throws IOException, ServletException
        {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            IOUtils.copy(request.getInputStream(), bos);
            receivedMessages.add(new String(bos.toByteArray()));
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_OK);
            response.setHeader("Transfer-Encoding", "chunked");
            try {
              JSONObject json = new JSONObject();
              json.put("responseId", "response" + ++responseCount);
              byte[] bytes = json.toString().getBytes();
              response.getOutputStream().println(bytes.length);
              response.getOutputStream().write(bytes);
              response.getOutputStream().println();
              response.getOutputStream().println(0);
              response.getOutputStream().flush();
            } catch (JSONException e) {
              response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error generating response: " + e.toString());
            }

            ((Request)request).setHandled(true);
        }
    };

    Server server = new Server(0);
    server.setHandler(handler);
    server.start();

    String url = "http://localhost:" + server.getConnectors()[0].getLocalPort() + "/somecontext";
    //String url = "http://localhost:8080/channel/mobile/phoneLocationQuery";

    final HttpInputOperator node = new HttpInputOperator();

    TestSink<Map<String, Object>> sink = new TestSink<Map<String, Object>>();

    node.outputPort.setSink(sink);
    node.setName("testHttpInputNode");
    node.resourceUrl = new URI(url);

    node.setup(new OperatorConfiguration());

    Thread nodeThread = new Thread()
    {
      @Override
      public void run()
      {
        node.getDataPoller().run();
      }
    };
    nodeThread.start();

    Thread.yield();
    while (nodeThread.getState() != Thread.State.RUNNABLE) {
      System.out.println("Waiting for node activation: " + nodeThread.getState());
      Thread.sleep(10);
    }

    sink.waitForResultCount(1, 3000);
    Assert.assertTrue("tuple emmitted", sink.collectedTuples.size() > 0);

    Map<String, Object> tuple = sink.collectedTuples.get(0);
    Assert.assertEquals("", tuple.get("responseId"), "response1");

    nodeThread.interrupt();
    node.teardown();
    server.stop();

  }

}
