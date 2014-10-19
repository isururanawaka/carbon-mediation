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


import org.apache.commons.collections.map.MultiValueMap;
import org.apache.http.*;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.NHttpServerConnection;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.transport.passthru.Pipe;
import org.apache.synapse.transport.passthru.ProtocolState;
import org.wso2.carbon.inbound.endpoint.protocol.http.utils.InboundConfiguration;

import java.io.IOException;
import java.util.*;

public class InboundHttpSourceRequest {

    private Pipe pipe = null;
    /**
     * HTTP Headers
     */
    private Map<String, String> headers = new TreeMap<String, String>(new Comparator<String>() {
        public int compare(String o1, String o2) {
            return o1.compareToIgnoreCase(o2);
        }
    });
    /**
     * HTTP URL
     */
    private String url;
    /**
     * HTTP Method
     */
    private String method;
    /**
     * Weather reqyest has a body
     */
    private boolean entityEnclosing;
    /**
     * The http request
     */
    private HttpRequest request = null;
    /**
     * Configuration of the receiver
     */
    private InboundConfiguration sourceConfiguration;
    /**
     * HTTP Version
     */
    private ProtocolVersion version = null;
    /**
     * The connection from the client
     */
    private NHttpServerConnection connection = null;

    /**
     * Excess headers of the request
     */
    private MultiValueMap excessHeaders = new MultiValueMap();

    private SynapseEnvironment synapseEnvironment;
    private String injectSeq;
    private String faultSeq;
    private String to;


    public InboundHttpSourceRequest(InboundConfiguration sourceConfiguration,
                                    HttpRequest request,
                                    NHttpServerConnection conn) {
        this.sourceConfiguration = sourceConfiguration;
        this.request = request;
        this.connection = conn;

        this.url = request.getRequestLine().getUri();
        this.method = request.getRequestLine().getMethod();
        this.entityEnclosing = request instanceof HttpEntityEnclosingRequest;
        this.version = request.getProtocolVersion();

        this.version = request.getRequestLine().getProtocolVersion();
        if (!version.lessEquals(HttpVersion.HTTP_1_1)) {
            // Downgrade protocol version if greater than HTTP/1.1
            this.version = HttpVersion.HTTP_1_1;
        }

        Header[] headers = request.getAllHeaders();
        if (headers != null) {
            for (Header header : headers) {
                if (this.headers.containsKey(header.getName())) {
                    addExcessHeader(header);
                } else {
                    this.headers.put(header.getName(), header.getValue());
                }
            }
        }
    }

    /**
     * Start processing the request by connecting the pipe if this request has an entity body.
     *
     * @param conn connection
     * @throws java.io.IOException           if an error occurs
     * @throws org.apache.http.HttpException if an error occurs
     */
    public void start(NHttpServerConnection conn) throws IOException, HttpException {
        if (entityEnclosing) {
            pipe = new Pipe(conn, sourceConfiguration.getBufferFactory().getBuffer(), "source", sourceConfiguration);

            InboundSourceContext.get(conn).setReader(pipe);

            // See if the client expects a 100-Continue
            if (((HttpEntityEnclosingRequest) request).expectContinue()) {
                HttpResponse ack = new BasicHttpResponse(version, HttpStatus.SC_CONTINUE, "Continue");
                conn.submitResponse(ack);
            }
        } else {
            // this request is completed, there is nothing more to read
            InboundSourceContext.updateState(conn, ProtocolState.REQUEST_DONE);
            // No httpRequest content expected. Suspend client input
            conn.suspendInput();
        }
    }

    /**
     * Produce the content in to the pipe.
     *
     * @param conn    the connection
     * @param decoder content decoder
     * @return number of bytes read
     * @throws java.io.IOException if an error occurs
     */
    public int read(NHttpServerConnection conn, ContentDecoder decoder) throws IOException {
        if (pipe == null) {
            throw new IllegalStateException("A Pipe must be connected before calling read");
        }

        if (entityEnclosing) {
            int bytes = pipe.produce(decoder);

            if (decoder.isCompleted()) {
                // Update connection state
                InboundSourceContext.updateState(conn, ProtocolState.REQUEST_DONE);
                // Suspend client input
                conn.suspendInput();
            }
            return bytes;
        } else {
            throw new IllegalStateException("Only Entity Enclosing Requests " +
                    "can read content in to the pipe");
        }
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getUri() {
        return url;
    }

    public String getMethod() {
        return method;
    }

    public Map getExcessHeaders() {
        return this.excessHeaders;
    }

    public void addExcessHeader(Header h) {
        excessHeaders.put(h.getName(), h.getValue());
    }

    public Pipe getPipe() {
        return pipe;
    }

    public NHttpServerConnection getConnection() {
        return connection;
    }

    public ProtocolVersion getVersion() {
        return version;
    }

    public HttpRequest getRequest() {
        return request;
    }

    public boolean isEntityEnclosing() {
        return entityEnclosing;
    }

    public SynapseEnvironment getSynapseEnvironment() {
        return synapseEnvironment;
    }

    public void setSynapseEnvironment(SynapseEnvironment synapseEnvironment) {
        this.synapseEnvironment = synapseEnvironment;
    }

    public String getInjectSeq() {
        return injectSeq;
    }

    public void setInjectSeq(String injectSeq) {
        this.injectSeq = injectSeq;
    }

    public String getFaultSeq() {
        return faultSeq;
    }

    public void setFaultSeq(String faultSeq) {
        this.faultSeq = faultSeq;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }


}
