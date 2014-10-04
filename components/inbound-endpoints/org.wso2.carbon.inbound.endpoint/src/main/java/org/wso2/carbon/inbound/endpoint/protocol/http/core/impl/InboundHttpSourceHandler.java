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
package org.wso2.carbon.inbound.endpoint.protocol.http.core.impl;


import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.NHttpServerConnection;
import org.apache.http.nio.NHttpServerEventHandler;
import org.apache.http.nio.util.ContentOutputBuffer;
import org.apache.http.nio.util.HeapByteBufferAllocator;
import org.apache.http.nio.util.SimpleOutputBuffer;
import org.apache.http.params.DefaultedHttpParams;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.transport.passthru.Pipe;
import org.apache.synapse.transport.passthru.ProtocolState;
import org.wso2.carbon.inbound.endpoint.protocol.http.utils.InboundConfiguration;
import org.wso2.carbon.inbound.endpoint.protocol.http.utils.InboundConstants;
import org.wso2.carbon.inbound.endpoint.protocol.http.utils.InboundThreadFactory;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * class interact with Client Requests and get Http Requests and passed them to Request worker
 */
public class InboundHttpSourceHandler implements NHttpServerEventHandler {

    protected Log log = LogFactory.getLog(this.getClass());

    private SynapseEnvironment synapseEnvironment;
    private String injectSeq;
    private String faultSeq;
    private InboundConfiguration inboundConfiguration;
    private ExecutorService executorService;

    private static final String INBOUND_THREAD_FACTORY = "inbound_request";
    private static final String SYANPSE_RESPONSE_SOURCE_BUFFER = "synapse.response-source-buffer";

    public InboundHttpSourceHandler(InboundConfiguration inboundConfiguration, SynapseEnvironment synapseEnvironment,
                                    String injectSeq, String faultSeq) {
        this.synapseEnvironment = synapseEnvironment;
        this.injectSeq = injectSeq;
        this.faultSeq = faultSeq;
        this.inboundConfiguration = inboundConfiguration;
        this.executorService = Executors.newFixedThreadPool(InboundConstants.WORKER_POOL_SIZE,
                new InboundThreadFactory(INBOUND_THREAD_FACTORY));
    }

    /**
     * calls by http core when IO session is initiated to particular client connection.
     * @param nHttpServerConnection
     * @throws IOException
     * @throws HttpException
     */
    public void connected(NHttpServerConnection nHttpServerConnection) throws IOException, HttpException {
        inboundConfiguration.getSourceConnections().addConnection(nHttpServerConnection);
        InboundSourceContext.create(nHttpServerConnection, ProtocolState.REQUEST_READY, inboundConfiguration);
    }

    /**
     *
     * @param nHttpServerConnection
     * @throws IOException
     * @throws HttpException
     */
    public void requestReceived(NHttpServerConnection nHttpServerConnection) throws IOException, HttpException {
        try {
            HttpContext context = nHttpServerConnection.getContext();
            if (!InboundSourceContext.assertState(nHttpServerConnection, ProtocolState.REQUEST_READY)
                    && !InboundSourceContext.assertState(nHttpServerConnection, ProtocolState.WSDL_RESPONSE_DONE)) {
                handleInvalidState(nHttpServerConnection, "Request received");
                return;
            }
            // we have received a message over this connection. So we must inform the pool
            inboundConfiguration.getSourceConnections().useConnection(nHttpServerConnection);

            // at this point we have read the HTTP Headers
            InboundSourceContext.updateState(nHttpServerConnection, ProtocolState.REQUEST_HEAD);
            InboundHttpSourceRequest request = new InboundHttpSourceRequest(
                    inboundConfiguration, nHttpServerConnection.getHttpRequest(), nHttpServerConnection);
            InboundSourceContext.setRequest(nHttpServerConnection, request);
            request.setInjectSeq(injectSeq);
            request.setFaultSeq(faultSeq);
            request.start(nHttpServerConnection);


            /******/
            String method = request.getRequest() != null ? request.getRequest().getRequestLine().getMethod().toUpperCase() : "";
            if ("GET".equals(method) || "HEAD".equals(method)) {
                HttpContext contextGetHEAD = request.getConnection().getContext();
                ContentOutputBuffer outputBuffer = new SimpleOutputBuffer(InboundConstants.SYNAPSE_RESPONSE_BUFFER_SIZE,
                        new HeapByteBufferAllocator());
                contextGetHEAD.setAttribute(SYANPSE_RESPONSE_SOURCE_BUFFER, outputBuffer);
            }
            executorService.execute(
                    new InboundHttpSourceRequestWorker(request, inboundConfiguration, synapseEnvironment));
        } catch (HttpException e) {
            log.error(e.getMessage(), e);
            InboundSourceContext.updateState(nHttpServerConnection, ProtocolState.CLOSED);
            inboundConfiguration.getSourceConnections().shutDownConnection(nHttpServerConnection, true);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            InboundSourceContext.updateState(nHttpServerConnection, ProtocolState.CLOSED);
            inboundConfiguration.getSourceConnections().shutDownConnection(nHttpServerConnection, true);
        }
    }


    public void inputReady(NHttpServerConnection nHttpServerConnection, ContentDecoder contentDecoder)
            throws IOException, HttpException {
        try {
            ProtocolState protocolState = InboundSourceContext.getState(nHttpServerConnection);
            if (protocolState != ProtocolState.REQUEST_HEAD
                    && protocolState != ProtocolState.REQUEST_BODY) {
                handleInvalidState(nHttpServerConnection, "Request message body data received");
                log.error("Error when reading request content in to pipe");
                return;
            }
            InboundSourceContext.updateState(nHttpServerConnection, ProtocolState.REQUEST_BODY);
            InboundHttpSourceRequest request = InboundSourceContext.getRequest(nHttpServerConnection);
            int readBytes = request.read(nHttpServerConnection, contentDecoder);
        } catch (IOException e) {
            informReaderError(nHttpServerConnection);
            log.error(e.getMessage(), e);
            InboundSourceContext.updateState(nHttpServerConnection, ProtocolState.CLOSED);
            inboundConfiguration.getSourceConnections().shutDownConnection(nHttpServerConnection, true);
        }

    }

    public void responseReady(NHttpServerConnection nHttpServerConnection) throws IOException, HttpException {
        try {
            ProtocolState protocolState = InboundSourceContext.getState(nHttpServerConnection);
            if (protocolState.compareTo(ProtocolState.REQUEST_DONE) < 0) {
                return;
            }
            if (protocolState.compareTo(ProtocolState.CLOSING) >= 0) {
                return;
            }
            if (protocolState != ProtocolState.REQUEST_DONE) {
                handleInvalidState(nHttpServerConnection, "Writing a response");
                log.error("Invalid Sate");
                return;
            }

            // because the duplex nature of http core we can reach hear without a actual response
            InboundHttpSourceResponse response = InboundSourceContext.getResponse(nHttpServerConnection);
            if (response != null) {
                response.start(nHttpServerConnection);
            }
        } catch (IOException e) {
            informWriterError(nHttpServerConnection);
            InboundSourceContext.updateState(nHttpServerConnection, ProtocolState.CLOSING);
            inboundConfiguration.getSourceConnections().shutDownConnection(nHttpServerConnection, true);
        } catch (HttpException e) {
            log.error(e.getMessage(), e);
            informWriterError(nHttpServerConnection);
            InboundSourceContext.updateState(nHttpServerConnection, ProtocolState.CLOSING);
            inboundConfiguration.getSourceConnections().shutDownConnection(nHttpServerConnection, true);
        }
    }

    public void outputReady(NHttpServerConnection nHttpServerConnection, ContentEncoder contentEncoder) throws
            IOException, HttpException {
        try {
            ProtocolState protocolState = InboundSourceContext.getState(nHttpServerConnection);
            //special case to handle WSDLs
            if (protocolState == ProtocolState.WSDL_RESPONSE_DONE) {
                // we need to shut down if the shutdown flag is set
                HttpContext context = nHttpServerConnection.getContext();
                ContentOutputBuffer outBuf = (ContentOutputBuffer) context.getAttribute(
                        SYANPSE_RESPONSE_SOURCE_BUFFER);
                int bytesWritten = outBuf.produceContent(contentEncoder);
                nHttpServerConnection.requestInput();
                if (outBuf instanceof SimpleOutputBuffer && !((SimpleOutputBuffer) outBuf).hasData()) {
                    inboundConfiguration.getSourceConnections().releaseConnection(nHttpServerConnection);
                }
                return;
            }


            if (protocolState != ProtocolState.RESPONSE_HEAD
                    && protocolState != ProtocolState.RESPONSE_BODY) {
                log.warn("Illegal incoming connection state: "
                        + protocolState + " . Possibly two send backs " +
                        "are happening for the same request");

                handleInvalidState(nHttpServerConnection, "Trying to write response body");
                return;
            }

            InboundSourceContext.updateState(nHttpServerConnection, ProtocolState.RESPONSE_BODY);

            InboundHttpSourceResponse response = InboundSourceContext.getResponse(nHttpServerConnection);

            int bytesSent = response.write(nHttpServerConnection, contentEncoder);
        } catch (IOException e) {
            informWriterError(nHttpServerConnection);
            InboundSourceContext.updateState(nHttpServerConnection, ProtocolState.CLOSING);
            inboundConfiguration.getSourceConnections().shutDownConnection(nHttpServerConnection, true);
        }
    }

    public void endOfInput(NHttpServerConnection nHttpServerConnection) throws IOException {
        nHttpServerConnection.close();
    }

    public void timeout(NHttpServerConnection nHttpServerConnection) throws IOException {
        ProtocolState state = InboundSourceContext.getState(nHttpServerConnection);

        if (state == ProtocolState.REQUEST_READY || state == ProtocolState.RESPONSE_DONE) {
            if (log.isDebugEnabled()) {
                log.debug(nHttpServerConnection + ": Keep-Alive connection was time out: " + nHttpServerConnection);
            }
        } else if (state == ProtocolState.REQUEST_BODY ||
                state == ProtocolState.REQUEST_HEAD) {


            informReaderError(nHttpServerConnection);
            log.warn("Connection time out while reading the request: " + nHttpServerConnection);
        } else if (state == ProtocolState.RESPONSE_BODY ||
                state == ProtocolState.RESPONSE_HEAD) {
            informWriterError(nHttpServerConnection);
            log.warn("Connection time out while writing the response: " + nHttpServerConnection);
        } else if (state == ProtocolState.REQUEST_DONE) {
            log.warn("Connection time out after request is read: " + nHttpServerConnection);
        }

        InboundSourceContext.updateState(nHttpServerConnection, ProtocolState.CLOSED);

        inboundConfiguration.getSourceConnections().shutDownConnection(nHttpServerConnection, true);
    }

    public void closed(NHttpServerConnection nHttpServerConnection) {
        ProtocolState state = InboundSourceContext.getState(nHttpServerConnection);
        boolean isFault = false;
        if (state == ProtocolState.REQUEST_READY || state == ProtocolState.RESPONSE_DONE) {
            if (log.isDebugEnabled()) {
                log.debug(nHttpServerConnection + ": Keep-Alive connection was closed: " + nHttpServerConnection);
            }
        } else if (state == ProtocolState.REQUEST_BODY ||
                state == ProtocolState.REQUEST_HEAD) {
            isFault = true;
            informReaderError(nHttpServerConnection);
            log.warn("Connection closed while reading the request: " + nHttpServerConnection);
        } else if (state == ProtocolState.RESPONSE_BODY ||
                state == ProtocolState.RESPONSE_HEAD) {
            isFault = true;
            informWriterError(nHttpServerConnection);
            log.warn("Connection closed while writing the response: " + nHttpServerConnection);
        } else if (state == ProtocolState.REQUEST_DONE) {
            isFault = true;
            log.warn("Connection closed by the client after request is read: " + nHttpServerConnection);
        }
        InboundSourceContext.updateState(nHttpServerConnection, ProtocolState.CLOSED);
        inboundConfiguration.getSourceConnections().shutDownConnection(nHttpServerConnection, isFault);
    }

    public void exception(NHttpServerConnection nHttpServerConnection, Exception ex) {
        if (ex instanceof IOException) {
            ProtocolState state = InboundSourceContext.getState(nHttpServerConnection);
            if (state == ProtocolState.REQUEST_BODY ||
                    state == ProtocolState.REQUEST_HEAD) {
                informReaderError(nHttpServerConnection);
            } else if (state == ProtocolState.RESPONSE_BODY ||
                    state == ProtocolState.RESPONSE_HEAD) {
                informWriterError(nHttpServerConnection);
            } else if (state == ProtocolState.REQUEST_DONE) {
                informWriterError(nHttpServerConnection);
            } else if (state == ProtocolState.RESPONSE_DONE) {
                informWriterError(nHttpServerConnection);
            }

            InboundSourceContext.updateState(nHttpServerConnection, ProtocolState.CLOSED);
            inboundConfiguration.getSourceConnections().shutDownConnection(nHttpServerConnection, true);
        } else if (ex instanceof HttpException) {
            try {
                if (nHttpServerConnection.isResponseSubmitted()) {
                    inboundConfiguration.getSourceConnections().shutDownConnection(nHttpServerConnection, true);
                    return;
                }
                HttpContext httpContext = nHttpServerConnection.getContext();

                HttpResponse response = new BasicHttpResponse(
                        HttpVersion.HTTP_1_1, HttpStatus.SC_BAD_REQUEST, "Bad request");
                response.setParams(
                        new DefaultedHttpParams(inboundConfiguration.buildHttpParams(),
                                response.getParams())
                );
                response.addHeader(HTTP.CONN_DIRECTIVE, HTTP.CONN_CLOSE);

                // Pre-process HTTP request
                httpContext.setAttribute(ExecutionContext.HTTP_CONNECTION, nHttpServerConnection);
                httpContext.setAttribute(ExecutionContext.HTTP_REQUEST, null);
                httpContext.setAttribute(ExecutionContext.HTTP_RESPONSE, response);

                inboundConfiguration.getHttpProcessor().process(response, httpContext);

                nHttpServerConnection.submitResponse(response);
                InboundSourceContext.updateState(nHttpServerConnection, ProtocolState.CLOSED);
                nHttpServerConnection.close();
            } catch (Exception ex1) {
                log.error(ex.getMessage(), ex);
                InboundSourceContext.updateState(nHttpServerConnection, ProtocolState.CLOSED);
                inboundConfiguration.getSourceConnections().shutDownConnection(nHttpServerConnection, true);
            }
        } else {
            log.error("Unexpected error: " + ex.getMessage(), ex);
            InboundSourceContext.updateState(nHttpServerConnection, ProtocolState.CLOSED);
            inboundConfiguration.getSourceConnections().shutDownConnection(nHttpServerConnection, true);
        }
    }

    private void handleInvalidState(NHttpServerConnection conn, String action) {
        log.warn(action + " while the handler is in an inconsistent state " +
                InboundSourceContext.getState(conn));
        InboundSourceContext.updateState(conn, ProtocolState.CLOSED);
        inboundConfiguration.getSourceConnections().shutDownConnection(conn, true);
    }

    private void informReaderError(NHttpServerConnection conn) {
        Pipe reader = InboundSourceContext.get(conn).getReader();
        if (reader != null) {
            reader.producerError();
        }
    }

    private void informWriterError(NHttpServerConnection conn) {
        Pipe writer = InboundSourceContext.get(conn).getWriter();
        if (writer != null) {
            writer.consumerError();
        }
    }
}
