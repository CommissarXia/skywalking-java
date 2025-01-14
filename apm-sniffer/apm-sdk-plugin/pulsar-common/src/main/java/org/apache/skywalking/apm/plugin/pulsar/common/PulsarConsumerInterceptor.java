/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.apm.plugin.pulsar.common;

import java.lang.reflect.Method;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.impl.ConsumerImpl;
import org.apache.skywalking.apm.agent.core.context.CarrierItem;
import org.apache.skywalking.apm.agent.core.context.ContextCarrier;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.tag.Tags;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.network.trace.component.ComponentsDefine;
import org.apache.skywalking.apm.plugin.pulsar.common.PulsarPluginConfig.Plugin.Pulsar;
import org.apache.skywalking.apm.util.StringUtil;

/**
 * Interceptor for pulsar consumer enhanced instance
 * <p>
 * Here is the intercept process steps:
 *
 * <pre>
 *  1. Record the service url, topic name and subscription name through this(ConsumerImpl)
 *  2. Create the entry span when call <code>messageProcessed</code> method
 *  3. Extract all the <code>Trace Context</code> when call <code>messageProcessed</code> method
 *  4. Capture trace context and set into SkyWalkingDynamic field if consumer has a message listener when <code>messageProcessed</code> method finished
 *  5. Stop the entry span.
 * </pre>
 */
public class PulsarConsumerInterceptor implements InstanceMethodsAroundInterceptor {

    public static final String OPERATE_NAME_PREFIX = "Pulsar/";
    public static final String CONSUMER_OPERATE_NAME = "/Consumer/";

    @Override
    public void beforeMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
                             MethodInterceptResult result) throws Throwable {
        if (allArguments[0] != null) {
            ConsumerImpl consumer = (ConsumerImpl) objInst;
            final String serviceUrl = consumer.getClient().getLookup().getServiceUrl();

            Message msg = (Message) allArguments[0];
            ContextCarrier carrier = new ContextCarrier();
            CarrierItem next = carrier.items();
            while (next.hasNext()) {
                next = next.next();
                next.setHeadValue(msg.getProperty(next.getHeadKey()));
            }
            AbstractSpan activeSpan = ContextManager.createEntrySpan(
                OPERATE_NAME_PREFIX + consumer.getTopic() + CONSUMER_OPERATE_NAME + objInst.getSkyWalkingDynamicField(),
                carrier
            );
            activeSpan.setComponent(ComponentsDefine.PULSAR_CONSUMER);
            SpanLayer.asMQ(activeSpan);
            Tags.MQ_BROKER.set(activeSpan, serviceUrl);
            Tags.MQ_TOPIC.set(activeSpan, consumer.getTopic());
            activeSpan.setPeer(serviceUrl);
            if (Pulsar.TRACE_MESSAGE_CONTENTS) {
                Tags.MQ_BODY.set(activeSpan, StringUtil.cut(new String(msg.getData()), Pulsar.MESSAGE_CONTENTS_MAX_LENGTH));
            }
        }
    }

    @Override
    public Object afterMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
                              Object ret) throws Throwable {
        if (allArguments[0] != null) {
            ConsumerImpl consumer = (ConsumerImpl) objInst;
            EnhancedInstance msg = (EnhancedInstance) allArguments[0];
            MessageEnhanceRequiredInfo messageEnhanceRequiredInfo = (MessageEnhanceRequiredInfo) msg
                .getSkyWalkingDynamicField();
            if (messageEnhanceRequiredInfo == null) {
                messageEnhanceRequiredInfo = new MessageEnhanceRequiredInfo();
                msg.setSkyWalkingDynamicField(messageEnhanceRequiredInfo);
            }
            messageEnhanceRequiredInfo.setTopic(consumer.getTopic());
            messageEnhanceRequiredInfo.setContextSnapshot(ContextManager.capture());
            ContextManager.stopSpan();
        }
        return ret;
    }

    @Override
    public void handleMethodException(EnhancedInstance objInst, Method method, Object[] allArguments,
                                      Class<?>[] argumentsTypes, Throwable t) {
        if (allArguments[0] != null) {
            ContextManager.activeSpan().log(t);
        }
    }
}
