/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flume.source.kafka;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyList;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Properties;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import junit.framework.Assert;
import kafka.common.TopicExistsException;
import kafka.consumer.ConsumerIterator;
import kafka.message.Message;

import kafka.message.MessageAndMetadata;

import org.apache.flume.*;
import org.apache.flume.PollableSource.Status;
import org.apache.flume.channel.ChannelProcessor;
import org.apache.flume.conf.Configurables;
import org.apache.flume.source.AbstractSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KafkaSourceTest {
  private static final Logger log =
          LoggerFactory.getLogger(KafkaSourceTest.class);

  private KafkaSource kafkaSource;
  private KafkaSourceEmbeddedKafka kafkaServer;
  private ConsumerIterator<byte[], byte[]> mockIt;
  private Message message;
  private Context context;
  private List<Event> events;
  private String topicName = "test1";


  @SuppressWarnings("unchecked")
  @Before
  public void setup() throws Exception {

    kafkaSource = new KafkaSource();
    kafkaServer = new KafkaSourceEmbeddedKafka();
    try {
      kafkaServer.createTopic(topicName);
    } catch (TopicExistsException e) {
      //do nothing
    }

    context = new Context();
    context.put(KafkaSourceConstants.ZOOKEEPER_CONNECT_FLUME,
            kafkaServer.getZkConnectString());
    context.put(KafkaSourceConstants.GROUP_ID_FLUME,"flume");
    context.put(KafkaSourceConstants.TOPIC,topicName);
    context.put("kafka.consumer.timeout.ms","100");

    ChannelProcessor channelProcessor = mock(ChannelProcessor.class);

    events = Lists.newArrayList();

    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        events.addAll((List<Event>)invocation.getArguments()[0]);
        return null;
      }
    }).when(channelProcessor).processEventBatch(any(List.class));
    kafkaSource.setChannelProcessor(channelProcessor);
  }

  @After
  public void tearDown() throws Exception {
    kafkaSource.stop();
    kafkaServer.stop();
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testProcessItNotEmpty() throws EventDeliveryException,
          SecurityException, NoSuchFieldException, IllegalArgumentException,
          IllegalAccessException, InterruptedException {
    context.put(KafkaSourceConstants.BATCH_SIZE,"1");
    kafkaSource.configure(context);
    kafkaSource.start();

    Thread.sleep(500L);

    kafkaServer.produce(topicName, "", "hello, world");

    Thread.sleep(500L);

    Assert.assertEquals(Status.READY, kafkaSource.process());
    Assert.assertEquals(Status.BACKOFF, kafkaSource.process());
    Assert.assertEquals(1, events.size());

    Assert.assertEquals("hello, world", new String(events.get(0).getBody(),
            Charsets.UTF_8));


  }

  @SuppressWarnings("unchecked")
  @Test
  public void testProcessItNotEmptyBatch() throws EventDeliveryException,
          SecurityException, NoSuchFieldException, IllegalArgumentException,
          IllegalAccessException, InterruptedException {
    context.put(KafkaSourceConstants.BATCH_SIZE,"2");
    kafkaSource.configure(context);
    kafkaSource.start();

    Thread.sleep(500L);

    kafkaServer.produce(topicName, "", "hello, world");
    kafkaServer.produce(topicName, "", "foo, bar");

    Thread.sleep(500L);

    Status status = kafkaSource.process();
    assertEquals(Status.READY, status);
    Assert.assertEquals("hello, world", new String(events.get(0).getBody(),
            Charsets.UTF_8));
    Assert.assertEquals("foo, bar", new String(events.get(1).getBody(),
            Charsets.UTF_8));

  }


  @SuppressWarnings("unchecked")
  @Test
  public void testProcessItEmpty() throws EventDeliveryException,
          SecurityException, NoSuchFieldException, IllegalArgumentException,
          IllegalAccessException, InterruptedException {
    kafkaSource.configure(context);
    kafkaSource.start();
    Thread.sleep(500L);

    Status status = kafkaSource.process();
    assertEquals(Status.BACKOFF, status);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testNonExistingTopic() throws EventDeliveryException,
          SecurityException, NoSuchFieldException, IllegalArgumentException,
          IllegalAccessException, InterruptedException {
    context.put(KafkaSourceConstants.TOPIC,"faketopic");
    kafkaSource.configure(context);
    kafkaSource.start();
    Thread.sleep(500L);

    Status status = kafkaSource.process();
    assertEquals(Status.BACKOFF, status);
  }

  @SuppressWarnings("unchecked")
  @Test(expected= FlumeException.class)
  public void testNonExistingZk() throws EventDeliveryException,
          SecurityException, NoSuchFieldException, IllegalArgumentException,
          IllegalAccessException, InterruptedException {
    context.put(KafkaSourceConstants.ZOOKEEPER_CONNECT_FLUME,"blabla:666");
    kafkaSource.configure(context);
    kafkaSource.start();
    Thread.sleep(500L);

    Status status = kafkaSource.process();
    assertEquals(Status.BACKOFF, status);
  }



}