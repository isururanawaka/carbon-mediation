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


import org.apache.synapse.core.SynapseEnvironment;

/**
 * Class Represent Http Inbound  Related Properties
 */
public class InboundHttpConfiguration {

    private SynapseEnvironment synapseEnvironment;
    private String injectSeq;
    private String faultSeq;


    public InboundHttpConfiguration(String injectSeq,String faultSeq, SynapseEnvironment synapseEnvironment) {
        this.injectSeq = injectSeq;
        this.faultSeq = faultSeq;
        this.synapseEnvironment = synapseEnvironment;
    }

    /**
     * @return synapse environment
     */
    public SynapseEnvironment getSynapseEnvironment() {
        return synapseEnvironment;
    }

    /**
     * @return injecting sequence
     */
    public String getInjectSeq() {
        return injectSeq;
    }

    /**
     * @return fault sequence
     */
    public String getFaultSeq() {
        return faultSeq;
    }

}