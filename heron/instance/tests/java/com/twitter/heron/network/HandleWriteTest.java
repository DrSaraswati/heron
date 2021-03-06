// Copyright 2016 Twitter. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.twitter.heron.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;

import org.junit.Assert;
import org.junit.Test;

import com.twitter.heron.api.generated.TopologyAPI;
import com.twitter.heron.common.basics.SysUtils;
import com.twitter.heron.common.network.IncomingPacket;
import com.twitter.heron.common.network.OutgoingPacket;
import com.twitter.heron.common.network.REQID;
import com.twitter.heron.instance.InstanceControlMsg;
import com.twitter.heron.proto.stmgr.StreamManager;
import com.twitter.heron.proto.system.Common;
import com.twitter.heron.resource.Constants;
import com.twitter.heron.resource.UnitTestHelper;

/**
 * To test whether Instance's handleWrite() from the stream manager.
 * 1. The instance will connect to stream manager successfully
 * 2. We will construct a bunch of Mock Message and offer them to outStreamQueue.
 * 3. The instance should get items from outStreamQueue and send them to Stream Manager.
 * 4. Check whether items received in Stream Manager match the Mock Message we constructed in Instance.
 */
public class HandleWriteTest extends AbstractNetworkTest {

  /**
   * Test write into network
   */
  @Test
  public void testHandleWrite() throws IOException {
    ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
    serverSocketChannel.socket().bind(new InetSocketAddress(HOST, serverPort));

    SocketChannel socketChannel = null;
    try {
      StreamManagerClient streamManagerClient = runStreamManagerClient();

      socketChannel = serverSocketChannel.accept();
      configure(socketChannel);
      socketChannel.configureBlocking(false);
      close(serverSocketChannel);

      // Receive request
      IncomingPacket incomingPacket = new IncomingPacket();
      while (incomingPacket.readFromChannel(socketChannel) != 0) {
        // 1ms sleep to mitigate busy looping
        SysUtils.sleep(Duration.ofMillis(1));
      }

      // Send back response
      // Though we do not use typeName, we need to unpack it first,
      // since the order is required
      String typeName = incomingPacket.unpackString();
      REQID rid = incomingPacket.unpackREQID();

      OutgoingPacket outgoingPacket
          = new OutgoingPacket(rid, UnitTestHelper.getRegisterInstanceResponse());
      outgoingPacket.writeToChannel(socketChannel);

      for (int i = 0; i < Constants.RETRY_TIMES; i++) {
        InstanceControlMsg instanceControlMsg = getInControlQueue().poll();
        if (instanceControlMsg != null) {
          break;
        } else {
          SysUtils.sleep(Constants.RETRY_INTERVAL);
        }
      }

      for (int i = 0; i < 10; i++) {
        // We randomly choose some messages writing to stream mgr
        streamManagerClient.sendMessage(UnitTestHelper.getRegisterInstanceResponse());
      }

      for (int i = 0; i < 10; i++) {
        incomingPacket = new IncomingPacket();
        while (incomingPacket.readFromChannel(socketChannel) != 0) {
          // 1ms sleep to mitigate busy looping
          SysUtils.sleep(Duration.ofMillis(1));
        }
        typeName = incomingPacket.unpackString();
        rid = incomingPacket.unpackREQID();
        StreamManager.RegisterInstanceResponse.Builder builder
            = StreamManager.RegisterInstanceResponse.newBuilder();
        incomingPacket.unpackMessage(builder);
        StreamManager.RegisterInstanceResponse response = builder.build();

        Assert.assertNotNull(response);
        Assert.assertTrue(response.isInitialized());
        Assert.assertEquals(Common.StatusCode.OK, response.getStatus().getStatus());
        Assert.assertEquals(1, response.getPplan().getStmgrsCount());
        Assert.assertEquals(2, response.getPplan().getInstancesCount());
        Assert.assertEquals(1, response.getPplan().getTopology().getBoltsCount());
        Assert.assertEquals(1, response.getPplan().getTopology().getSpoutsCount());
        Assert.assertEquals(TopologyAPI.TopologyState.RUNNING,
            response.getPplan().getTopology().getState());
      }

      getNIOLooper().exitLoop();

    } catch (ClosedChannelException ignored) {
    } finally {
      close(socketChannel);
    }
  }
}
