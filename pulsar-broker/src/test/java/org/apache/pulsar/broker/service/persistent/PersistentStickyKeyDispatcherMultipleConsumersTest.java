/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.service.persistent;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPromise;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.impl.EntryImpl;
import org.apache.bookkeeper.mledger.impl.ManagedCursorImpl;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.service.*;
import org.apache.pulsar.common.api.proto.PulsarApi;
import org.apache.pulsar.common.protocol.Commands;
import org.apache.pulsar.common.protocol.Markers;
import org.mockito.ArgumentCaptor;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.testng.Assert;
import org.testng.IObjectFactory;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.ObjectFactory;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.pulsar.common.protocol.Commands.serializeMetadataAndPayload;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.testng.Assert.fail;

@PrepareForTest({ DispatchRateLimiter.class })
@PowerMockIgnore({"org.apache.logging.log4j.*"})
public class PersistentStickyKeyDispatcherMultipleConsumersTest {

    private PulsarService pulsarMock;
    private BrokerService brokerMock;
    private ManagedCursorImpl cursorMock;
    private Consumer consumerMock;
    private PersistentTopic topicMock;
    private PersistentSubscription subscriptionMock;
    private ServiceConfiguration configMock;
    private ChannelPromise channelMock;

    private PersistentStickyKeyDispatcherMultipleConsumers persistentDispatcher;

    final String topicName = "persistent://public/default/testTopic";
    final String subscriptionName = "testSubscription";

    @ObjectFactory
    public IObjectFactory getObjectFactory() {
        return new org.powermock.modules.testng.PowerMockObjectFactory();
    }

    @BeforeMethod
    public void setup() throws Exception {
        configMock = mock(ServiceConfiguration.class);
        doReturn(true).when(configMock).isSubscriptionRedeliveryTrackerEnabled();
        doReturn(100).when(configMock).getDispatcherMaxReadBatchSize();

        pulsarMock = mock(PulsarService.class);
        doReturn(configMock).when(pulsarMock).getConfiguration();

        brokerMock = mock(BrokerService.class);
        doReturn(pulsarMock).when(brokerMock).pulsar();

        topicMock = mock(PersistentTopic.class);
        doReturn(brokerMock).when(topicMock).getBrokerService();
        doReturn(topicName).when(topicMock).getName();

        cursorMock = mock(ManagedCursorImpl.class);
        doReturn(null).when(cursorMock).getLastIndividualDeletedRange();
        doReturn(subscriptionName).when(cursorMock).getName();

        consumerMock = mock(Consumer.class);
        channelMock = mock(ChannelPromise.class);
        doReturn(1000).when(consumerMock).getAvailablePermits();
        doReturn(true).when(consumerMock).isWritable();
        doReturn(channelMock).when(consumerMock).sendMessages(
                anyList(),
                any(EntryBatchSizes.class),
                any(EntryBatchIndexesAcks.class),
                anyInt(),
                anyLong(),
                any(RedeliveryTracker.class)
        );

        subscriptionMock = mock(PersistentSubscription.class);

        PowerMockito.mockStatic(DispatchRateLimiter.class);
        PowerMockito.when(DispatchRateLimiter.isDispatchRateNeeded(
                any(BrokerService.class),
                any(Optional.class),
                anyString(),
                any(DispatchRateLimiter.Type.class))
        ).thenReturn(false);

        persistentDispatcher = new PersistentStickyKeyDispatcherMultipleConsumers(
                topicMock, cursorMock, subscriptionMock, new ConsistentHashingStickyKeyConsumerSelector(100));
        persistentDispatcher.addConsumer(consumerMock);
        persistentDispatcher.consumerFlow(consumerMock, 1000);
    }

    @Test
    public void testSendMarkerMessage() {
        List<Entry> entries = new ArrayList<>();
        ByteBuf markerMessage = Markers.newReplicatedSubscriptionsSnapshotRequest("testSnapshotId", "testSourceCluster");
        entries.add(EntryImpl.create(1, 1, markerMessage));
        entries.add(EntryImpl.create(1, 2, createMessage("message1", 1)));
        entries.add(EntryImpl.create(1, 3, createMessage("message2", 2)));
        entries.add(EntryImpl.create(1, 4, createMessage("message3", 3)));
        entries.add(EntryImpl.create(1, 5, createMessage("message4", 4)));
        entries.add(EntryImpl.create(1, 6, createMessage("message5", 5)));

        try {
            persistentDispatcher.readEntriesComplete(entries, PersistentStickyKeyDispatcherMultipleConsumers.ReadType.Normal);
        } catch (Exception e) {
            fail("Failed to readEntriesComplete.", e);
        }

        ArgumentCaptor<Integer> totalMessagesCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(consumerMock, times(2)).sendMessages(
                anyList(),
                any(EntryBatchSizes.class),
                any(EntryBatchIndexesAcks.class),
                totalMessagesCaptor.capture(),
                anyLong(),
                any(RedeliveryTracker.class)
        );

        List<Integer> allTotalMessagesCaptor = totalMessagesCaptor.getAllValues();
        Assert.assertEquals(allTotalMessagesCaptor.get(0).intValue(), 0);
        Assert.assertEquals(allTotalMessagesCaptor.get(1).intValue(), 5);
    }

    private ByteBuf createMessage(String message, int sequenceId) {
        PulsarApi.MessageMetadata.Builder messageMetadata = PulsarApi.MessageMetadata.newBuilder();
        messageMetadata.setSequenceId(sequenceId);
        messageMetadata.setProducerName("testProducer");
        messageMetadata.setPartitionKey("testKey");
        messageMetadata.setPublishTime(System.currentTimeMillis());
        return serializeMetadataAndPayload(Commands.ChecksumType.Crc32c, messageMetadata.build(), Unpooled.copiedBuffer(message.getBytes(UTF_8)));
    }
}
