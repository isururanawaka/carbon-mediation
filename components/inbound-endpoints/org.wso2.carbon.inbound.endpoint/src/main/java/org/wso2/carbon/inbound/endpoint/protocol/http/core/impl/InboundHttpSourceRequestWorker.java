/*
*  Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.wso2.carbon.inbound.endpoint.protocol.http.core.impl;


import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.util.UUIDGenerator;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axiom.soap.SOAPFactory;
import org.apache.axiom.soap.impl.llom.soap11.SOAP11Factory;
import org.apache.axiom.util.UIDGenerator;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.addressing.EndpointReference;
import org.apache.axis2.builder.BuilderUtil;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.transport.RequestResponseTransport;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.axis2.transport.http.HTTPTransportUtils;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpInetConnection;
import org.apache.http.nio.NHttpServerConnection;
import org.apache.http.protocol.HTTP;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.inbound.InboundEndpointConstants;
import org.apache.synapse.mediators.base.SequenceMediator;
import org.apache.synapse.transport.nhttp.HttpCoreRequestResponseTransport;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.transport.nhttp.util.NhttpUtil;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.ProtocolState;
import org.wso2.carbon.inbound.endpoint.protocol.http.utils.InboundConfiguration;
import org.wso2.carbon.inbound.endpoint.protocol.http.utils.InboundConstants;

import java.net.InetAddress;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * create Synapse Message Context from HTTP Request and inject it to the sequence.
 */
public class InboundHttpSourceRequestWorker implements Runnable {

    private static final Log log = LogFactory.getLog(InboundHttpSourceRequestWorker.class);

    /**
     * the http request
     */
    private InboundHttpSourceRequest request = null;
    /**
     * The configuration of the receiver
     */
    private InboundConfiguration sourceConfiguration = null;

    private static final String SOAP_ACTION_HEADER = "SOAPAction";

    private SynapseEnvironment synapseEnvironment;

    private static final String SKIP = "SKIP";

    private static final String NIO_ACK_REQUESTED = "NIO-ACK-Requested";

    public InboundHttpSourceRequestWorker(InboundHttpSourceRequest inboundSourceRequest,
                                          InboundConfiguration inboundConfiguration, SynapseEnvironment synapseEnvironment) {
        this.synapseEnvironment = synapseEnvironment;
        this.request = inboundSourceRequest;
        this.sourceConfiguration = inboundConfiguration;
    }


    public void run() {
        if (request != null) {
            org.apache.synapse.MessageContext msgCtx = createMessageContext(request);// synapse message context
            MessageContext messageContext = ((Axis2MessageContext) msgCtx).getAxis2MessageContext();//Axis2 message Context

            setInboundProperties(msgCtx);// setting Inbound related properties

            SequenceMediator injectingSequence = (SequenceMediator) synapseEnvironment.getSynapseConfiguration().
                    getSequence(request.getInjectSeq());// Get injecting sequence for synapse engine

            if (injectingSequence != null) {
                injectingSequence.setErrorHandler(request.getFaultSeq());
                if (log.isDebugEnabled()) {
                    log.debug("injecting message to sequence : " + request.getInjectSeq());
                }
            } else {
                log.error("Sequence: " + request.getInjectSeq() + " not found");
            }
            if (request.isEntityEnclosing()) {
                processEntityEnclosingRequest(messageContext);

            } else {
                processNonEntityEnclosing(null, messageContext);//handling rest
            }
            synapseEnvironment.injectAsync(msgCtx, injectingSequence);// inject to synapse environment

            sendAck(messageContext); // send ack for client if needed
        } else {
            log.error("InboundSourceRequest cannot be null");
        }
    }

    /**
     * Calls for rest calls and set doing rest to true
     *
     * @param soapEnvelope
     * @param msgContext
     */
    private void processNonEntityEnclosing(SOAPEnvelope soapEnvelope, MessageContext msgContext) {
        String soapAction = request.getHeaders().get(SOAP_ACTION_HEADER);
        if ((soapAction != null) && soapAction.startsWith("\"") && soapAction.endsWith("\"")) {
            soapAction = soapAction.substring(1, soapAction.length() - 1);
        }

        msgContext.setSoapAction(soapAction);
        msgContext.setTo(new EndpointReference(request.getUri()));
        msgContext.setServerSide(true);
        msgContext.setDoingREST(true);


        if (!request.isEntityEnclosing()) {
            msgContext.setProperty(PassThroughConstants.NO_ENTITY_BODY, Boolean.TRUE);
        }
        try {
            if (soapEnvelope == null) {
                msgContext.setEnvelope(new SOAP11Factory().getDefaultEnvelope());
            } else {
                msgContext.setEnvelope(soapEnvelope);
            }

        } catch (AxisFault axisFault) {
            log.error("Error processing " + request.getMethod() +
                    " request for : " + request.getUri(), axisFault);
        }
    }

    /**
     * create Synapse MessageContext
     *
     * @param inboundSourceRequest
     * @return msgCtx
     */
    private org.apache.synapse.MessageContext createMessageContext(InboundHttpSourceRequest inboundSourceRequest) {
        org.apache.synapse.MessageContext msgCtx = synapseEnvironment.createMessageContext();

        MessageContext axis2MsgCtx = ((org.apache.synapse.core.axis2.Axis2MessageContext) msgCtx).getAxis2MessageContext();
        axis2MsgCtx.setServerSide(true);
        axis2MsgCtx.setMessageID(UUIDGenerator.getUUID());
        String oriUri = inboundSourceRequest.getUri();
        String restUrlPostfix = NhttpUtil.getRestUrlPostfix(oriUri, axis2MsgCtx.getConfigurationContext().getServicePath());
        String servicePrefix = oriUri.substring(0, oriUri.indexOf(restUrlPostfix));

        axis2MsgCtx.setTo(new EndpointReference(oriUri));
        axis2MsgCtx.setProperty(MessageContext.CLIENT_API_NON_BLOCKING, false);
        axis2MsgCtx.setProperty(PassThroughConstants.SERVICE_PREFIX, servicePrefix);


        axis2MsgCtx.setProperty(PassThroughConstants.REST_URL_POSTFIX, restUrlPostfix);


        return msgCtx;
    }

    /**
     * Process Entity Enclosing Requests
     *
     * @param msgContext
     */
    private void processEntityEnclosingRequest(MessageContext msgContext) {
        try {
            String contentTypeHeader = request.getHeaders().get(HTTP.CONTENT_TYPE);
            contentTypeHeader = contentTypeHeader != null ? contentTypeHeader : inferContentType();

            String charSetEncoding = null;
            String contentType = null;

            if (contentTypeHeader != null) {
                charSetEncoding = BuilderUtil.getCharSetEncoding(contentTypeHeader);
                contentType = TransportUtils.getContentType(contentTypeHeader, msgContext);
            }

            // get the contentType of char encoding
            if (charSetEncoding == null) {
                charSetEncoding = MessageContext.DEFAULT_CHAR_SET_ENCODING;
            }
            String method =
                    request.getRequest() != null ? request.getRequest().getRequestLine().getMethod().toUpperCase() : "";

            msgContext.setTo(new EndpointReference(request.getUri()));
            msgContext.setProperty(HTTPConstants.HTTP_METHOD, method);
            msgContext.setProperty(Constants.Configuration.CHARACTER_SET_ENCODING, charSetEncoding);
            msgContext.setServerSide(true);
            msgContext.setProperty(Constants.Configuration.CONTENT_TYPE, contentTypeHeader);
            msgContext.setProperty(Constants.Configuration.MESSAGE_TYPE, contentType);
            String soapAction = request.getHeaders().get(SOAP_ACTION_HEADER);

            int soapVersion = HTTPTransportUtils.
                    initializeMessageContext(msgContext, soapAction,
                            request.getUri(), contentTypeHeader);
            SOAPEnvelope envelope;

            if (soapVersion == 1) {
                SOAPFactory fac = OMAbstractFactory.getSOAP11Factory();
                envelope = fac.getDefaultEnvelope();
            } else {
                SOAPFactory fac = OMAbstractFactory.getSOAP12Factory();
                envelope = fac.getDefaultEnvelope();
            }

            msgContext.setEnvelope(envelope);


            msgContext.setProperty(PassThroughConstants.PASS_THROUGH_PIPE, request.getPipe());
            Map excessHeaders = request.getExcessHeaders();

            msgContext.setMessageID(UIDGenerator.generateURNString());

            // Axis2 spawns a new threads to send a message if this is TRUE - and it has to
            // be the other way
            msgContext.setProperty(MessageContext.CLIENT_API_NON_BLOCKING,
                    Boolean.FALSE);


            NHttpServerConnection conn = request.getConnection();


            msgContext.setProperty(Constants.OUT_TRANSPORT_INFO, this);
            msgContext.setServerSide(true);
            msgContext.setProperty(
                    Constants.Configuration.TRANSPORT_IN_URL, request.getUri());

            // http transport header names are case insensitive
            Map<String, String> headers = new TreeMap<String, String>(new Comparator<String>() {
                public int compare(String o1, String o2) {
                    return o1.compareToIgnoreCase(o2);
                }
            });

            Set<Map.Entry<String, String>> entries = request.getHeaders().entrySet();
            for (Map.Entry<String, String> entry : entries) {
                headers.put(entry.getKey(), entry.getValue());
            }

            msgContext.setProperty(MessageContext.TRANSPORT_HEADERS, headers);
            msgContext.setProperty(NhttpConstants.EXCESS_TRANSPORT_HEADERS, excessHeaders);


            // Following section is required for throttling to work
            if (conn instanceof HttpInetConnection) {
                HttpInetConnection netConn = (HttpInetConnection) conn;
                InetAddress remoteAddress = netConn.getRemoteAddress();
                if (remoteAddress != null) {
                    msgContext.setProperty(
                            MessageContext.REMOTE_ADDR, remoteAddress.getHostAddress());
                    msgContext.setProperty(
                            NhttpConstants.REMOTE_HOST, NhttpUtil.getHostName(remoteAddress));
                }
            }


            msgContext.setProperty(RequestResponseTransport.TRANSPORT_CONTROL,
                    new HttpCoreRequestResponseTransport(msgContext));

        } catch (AxisFault axisFault) {
            log.error(axisFault.getMessage(), axisFault);
        }
    }

    /**
     * Content Type Return
     *
     * @return
     */
    private String inferContentType() {
        Map<String, String> headers = request.getHeaders();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (HTTP.CONTENT_TYPE.equalsIgnoreCase(header.getKey())) {
                return header.getValue();
            }
        }
        Parameter param = sourceConfiguration.getConfigurationContext().getAxisConfiguration().
                getParameter(PassThroughConstants.REQUEST_CONTENT_TYPE);
        if (param != null) {
            return param.getValue().toString();
        }
        return null;
    }

    /**
     * Setting Inbound related properties
     *
     * @param msgContext
     */
    private void setInboundProperties(org.apache.synapse.MessageContext msgContext) {
        MessageContext messageContext = ((Axis2MessageContext) msgContext).getAxis2MessageContext();
        messageContext.setProperty(
                InboundConstants.HTTP_INBOUND_SOURCE_REQUEST, request);
        messageContext.setProperty(
                InboundConstants.HTTP_INBOUND_SOURCE_CONFIGURATION, sourceConfiguration);
        messageContext.setProperty(InboundConstants.HTTP_INBOUND_SOURCE_CONNECTION,
                request.getConnection());
        msgContext.setProperty(SynapseConstants.IS_INBOUND, true);
        msgContext.setProperty(InboundEndpointConstants.INBOUND_ENDPOINT_RESPONSE_WORKER,
                InboundHttpGlobalConfiguration.getInboundHttpSourceResponseWorker());
        msgContext.setWSAAction(request.getHeaders().get(InboundConstants.SOAP_ACTION));
    }


    /**
     * Send Ack to client id forced ack
     *
     * @param msgContext
     */
    private void sendAck(MessageContext msgContext) {
        String respWritten = "";
        if (msgContext.getOperationContext() != null) {
            respWritten = (String) msgContext.getOperationContext().getProperty(
                    Constants.RESPONSE_WRITTEN);
        }

        if (msgContext.getProperty(PassThroughConstants.FORCE_SOAP_FAULT) != null) {
            respWritten = SKIP;
        }

        boolean respWillFollow = !Constants.VALUE_TRUE.equals(respWritten)
                && !SKIP.equals(respWritten);

        boolean ack = (((RequestResponseTransport) msgContext.getProperty(
                RequestResponseTransport.TRANSPORT_CONTROL)).getStatus()
                == RequestResponseTransport.RequestResponseTransportStatus.ACKED);


        boolean forced = msgContext.isPropertyTrue(NhttpConstants.FORCE_SC_ACCEPTED);
        boolean nioAck = msgContext.isPropertyTrue(NIO_ACK_REQUESTED, false);


        if (respWillFollow || ack || forced || nioAck) {
            NHttpServerConnection conn = request.getConnection();
            InboundHttpSourceResponse inboundHttpSourceResponse;
            if (!nioAck) {
                msgContext.removeProperty(MessageContext.TRANSPORT_HEADERS);
                inboundHttpSourceResponse = InboundSourceResponseFactory.create(msgContext,
                        request, sourceConfiguration);
                inboundHttpSourceResponse.setStatus(HttpStatus.SC_ACCEPTED);
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Sending ACK response with status "
                            + msgContext.getProperty(NhttpConstants.HTTP_SC)
                            + ", for MessageID : " + msgContext.getMessageID());
                }
                inboundHttpSourceResponse = InboundSourceResponseFactory.create(msgContext,
                        request, sourceConfiguration);
                inboundHttpSourceResponse.setStatus(Integer.parseInt(
                        msgContext.getProperty(NhttpConstants.HTTP_SC).toString()));
            }


            ProtocolState state = InboundSourceContext.getState(conn);

            if (state != null && state.compareTo(ProtocolState.REQUEST_DONE) <= 0) {
                conn.requestOutput();
            } else {
                InboundSourceContext.updateState(conn, ProtocolState.CLOSED);
                sourceConfiguration.getSourceConnections().shutDownConnection(conn);
            }
        }
    }
}
