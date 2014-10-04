/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.wso2.carbon.inbound.endpoint.protocol.http.netty.impl;


import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.apache.log4j.Logger;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.inbound.InboundRequestProcessor;
import org.wso2.carbon.inbound.endpoint.protocol.http.utils.InboundConstants;

/**
 * Start ServerBootStrap in a given port for http inbound connections
 */

public class InboundHttpListner implements InboundRequestProcessor {
    private static Logger logger = Logger.getLogger(InboundHttpListner.class);
    private int port;
    private SynapseEnvironment synapseEnvironment;
    private String injectSeq;
    private String faultSeq;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Thread listnerThread;


    public InboundHttpListner(int port, SynapseEnvironment synapseEnvironment, String injectSeq, String faultSeq) {
        this.port = port;
        this.synapseEnvironment = synapseEnvironment;
        this.injectSeq = injectSeq;
        this.faultSeq = faultSeq;

    }

    public InboundHttpListner() {
    }

    @Override
    public void init() {
        start();
    }

    public void start() {
        logger.info("Starting Inbound Http Listner on Port " + this.port);
        listnerThread = new Thread(new Runnable() {
            public void run() {
                bossGroup = new NioEventLoopGroup();
                workerGroup = new NioEventLoopGroup();
                try {
                    ServerBootstrap b = new ServerBootstrap();
                    b.option(ChannelOption.SO_BACKLOG, InboundConstants.MAXIMUM_CONNECTIONS_QUEUED);
                    b.group(bossGroup, workerGroup)
                            .channel(NioServerSocketChannel.class)
                            .childHandler(new InboundHttpTransportHandlerInitializer(synapseEnvironment, injectSeq, faultSeq));

                    Channel ch = null;
                    try {
                        ch = b.bind(port).sync().channel();
                        ch.closeFuture().sync();
                        logger.info("Inbound Listner Started");
                    } catch (InterruptedException e) {
                        logger.info("");
                    }
                } finally {
                    bossGroup.shutdownGracefully();
                    workerGroup.shutdownGracefully();
                }
            }
        },
                "Inbound Listner"
        );
        listnerThread.start();

    }

    @Override
    public void destroy() {
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        if (listnerThread != null) {
            listnerThread.stop();
        }
    }


    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

}
