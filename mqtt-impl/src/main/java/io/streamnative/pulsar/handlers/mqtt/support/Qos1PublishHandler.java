/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamnative.pulsar.handlers.mqtt.support;

import io.netty.channel.Channel;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.streamnative.pulsar.handlers.mqtt.AbstractQosPublishHandler;
import io.streamnative.pulsar.handlers.mqtt.Connection;
import io.streamnative.pulsar.handlers.mqtt.MQTTServerConfiguration;
import io.streamnative.pulsar.handlers.mqtt.MQTTService;
import io.streamnative.pulsar.handlers.mqtt.adapter.MqttAdapterMessage;
import io.streamnative.pulsar.handlers.mqtt.exception.MQTTNoMatchingSubscriberException;
import io.streamnative.pulsar.handlers.mqtt.messages.ack.MqttAck;
import io.streamnative.pulsar.handlers.mqtt.messages.ack.MqttPubAck;
import io.streamnative.pulsar.handlers.mqtt.messages.codes.mqtt5.Mqtt5PubReasonCode;
import io.streamnative.pulsar.handlers.mqtt.utils.MqttUtils;
import io.streamnative.pulsar.handlers.mqtt.utils.NettyUtils;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.broker.service.BrokerServiceException;
import org.apache.pulsar.common.util.FutureUtil;

/**
 * Publish handler implementation for Qos 1.
 */
@Slf4j
public class Qos1PublishHandler extends AbstractQosPublishHandler {

    public Qos1PublishHandler(MQTTService mqttService, MQTTServerConfiguration configuration, Channel channel) {
        super(mqttService, configuration, channel);
    }

    @Override
    public CompletableFuture<Void> publish(MqttAdapterMessage adapter) {
        final MqttPublishMessage msg = (MqttPublishMessage) adapter.getMqttMessage();
        final Connection connection = NettyUtils.getConnection(channel);
        final int protocolVersion = connection.getProtocolVersion();
        final int packetId = msg.variableHeader().packetId();
        final String topic = msg.variableHeader().topicName();
        final CompletableFuture<Void> ret;
        if (MqttUtils.isRetainedMessage(msg)) {
            ret = retainedMessageHandler.addRetainedMessage(msg);
        } else {
            ret = writeToPulsarTopic(msg, !MqttUtils.isMqtt3(protocolVersion)).thenApply(__ -> null);
        }
        // we need to check if subscription exist when protocol version is mqtt 5.x
        return ret
                .thenCompose(__ -> {
                    MqttAck pubAck = MqttPubAck.successBuilder(protocolVersion)
                            .packetId(packetId)
                            .build();
                    CompletableFuture<Void> future = connection.sendAck(pubAck);
                    future.whenComplete((result, error) -> {
                        connection.decrementServerReceivePubMessage();
                        if (log.isDebugEnabled()) {
                            log.debug("[{}] Send Pub Ack {} to {}", topic, msg.variableHeader().packetId(),
                                    connection.getClientId());
                        }
                    });
                    return future;
                }).exceptionally(ex -> {
                    Throwable realCause = FutureUtil.unwrapCompletionException(ex);
                    if (realCause instanceof MQTTNoMatchingSubscriberException) {
                        log.warn("[{}] Write {} to Pulsar topic succeed. But do not have subscriber.", topic, msg);
                        MqttAck mqttAck = MqttPubAck.successBuilder(protocolVersion)
                                .packetId(packetId)
                                .isNoMatchingSubscription()
                                .build();
                        connection.sendAck(mqttAck)
                                .whenComplete((result, error)->{
                                    connection.decrementServerReceivePubMessage();
                                });
                    } else if (realCause instanceof BrokerServiceException.TopicNotFoundException) {
                        log.warn("Topic [{}] Not found, the configuration [isAllowAutoTopicCreation={}]",
                                topic, pulsarService.getConfig().isAllowAutoTopicCreation());
                        MqttAck pubAck = MqttPubAck.errorBuilder(protocolVersion)
                                .packetId(packetId)
                                .reasonCode(Mqtt5PubReasonCode.UNSPECIFIED_ERROR)
                                .reasonString("Topic not found")
                                .build();
                        connection.sendAckThenClose(pubAck);
                    } else {
                        log.error("[{}] Publish msg {} fail.", topic, msg, ex);
                        MqttAck pubAck = MqttPubAck.errorBuilder(protocolVersion)
                                .packetId(packetId)
                                .reasonCode(Mqtt5PubReasonCode.UNSPECIFIED_ERROR)
                                .reasonString(realCause.getMessage())
                                .build();
                        connection.sendAckThenClose(pubAck);
                    }
                    return null;
                });
    }
}
