/*
 * Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.inbound.endpoint.protocol.http;

import org.apache.log4j.Logger;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.inbound.InboundProcessorParams;
import org.apache.synapse.inbound.InboundRequestProcessor;
import org.apache.synapse.transport.passthru.SourceHandler;
import org.apache.synapse.transport.passthru.api.PassThroughInboundEndpointHandler;
import org.apache.synapse.transport.passthru.config.SourceConfiguration;

import java.net.InetSocketAddress;


/**
 * Listener class for HttpInboundEndpoint which is trigger by inbound core and
 * responsible for start ListeningEndpoint related to given port
 */
public class InboundHttpListener implements InboundRequestProcessor {

    private static final Logger logger = Logger.getLogger(InboundHttpListener.class);

    private String injectingSequence;
    private String onErrorSequence;
    private SynapseEnvironment synapseEnvironment;
    private String name;
    private String port;

    public InboundHttpListener(InboundProcessorParams params) {

        this.port = params.getProperties().
                getProperty(InboundHttpConstants.INBOUND_ENDPOINT_PARAMETER_HTTP_PORT);
        this.name = params.getName();
        this.injectingSequence = params.getInjectingSeq();
        this.onErrorSequence = params.getOnErrorSeq();
        this.synapseEnvironment = params.getSynapseEnvironment();
    }

    @Override
    public void init() {

        InboundHttpConfiguration inboundHttpConfiguration =
                new InboundHttpConfiguration(injectingSequence, onErrorSequence, synapseEnvironment);
        //Get registered source configuration of PassThrough Transport
        SourceConfiguration sourceConfiguration = null;
        try {
            sourceConfiguration = PassThroughInboundEndpointHandler.getPassThroughSourceConfiguration();
        } catch (Exception e) {
            logger.error("Cannot get PassThroughSourceConfiguration ", e);
        }
        if (sourceConfiguration != null) {
            //Create Handler for handle Http Requests
            SourceHandler inboundSourceHandler = new InboundHttpSourceHandler(sourceConfiguration,
                                                                                               inboundHttpConfiguration);
            try {
                //Start Endpoint in given port
                PassThroughInboundEndpointHandler.startEndpoint
                        (new InetSocketAddress(Integer.parseInt(port)), inboundSourceHandler, name);
            } catch (NumberFormatException e) {
                logger.error("Exception occurred while startEndpoint  " + name + " May be given port " + port, e);
            }
        } else {
            logger.error("SourceConfiguration is not registered in PassThrough Transport");
        }
    }

    @Override
    public void destroy() {
        PassThroughInboundEndpointHandler.closeEndpoint(Integer.parseInt(port));
    }


}