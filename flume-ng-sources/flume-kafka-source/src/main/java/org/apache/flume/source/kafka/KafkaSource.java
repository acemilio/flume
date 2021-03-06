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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import kafka.consumer.ConsumerIterator;
import kafka.consumer.ConsumerTimeoutException;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;

import org.apache.flume.*;
import org.apache.flume.conf.Configurable;
import org.apache.flume.conf.ConfigurationException;
import org.apache.flume.event.EventBuilder;
import org.apache.flume.source.AbstractSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * A Source for Kafka which reads messages from kafka.
 * I use this in company production environment and its performance is good.
 * Over 100k messages per second can be read from kafka in one source.<p>
 * <tt>kafka.zookeeper.connect: </tt> the zookeeper ip kafka use.<p>
 * <tt>kafka.group.id: </tt> the groupid of consumer group.<p>
 * <tt>topic: </tt> the topic to read from kafka.<p>
 * maxBatchSize - maximum number of messages written to Channel in one batch
 * maxBatchDurationMillis - maximum time before a batch (of any size)
 *                          will be written to Channel
 * kafka.auto.commit.enable - if true, commit automatically every time period.
 *                      if false, commit on each batch.
 * kafka.consumer.timeout.ms -  polling interval for new data for batch.
 *                        Low value means more CPU usage.
 *                        High value means the time.upper.limit may be missed.
 *
 * Any property starting with "kafka" will be passed to the kafka consumer
 * So you can use any configuration supported by Kafka 0.8.1.1
 */
public class KafkaSource extends AbstractSource
        implements Configurable, PollableSource {
  private static final Logger log = LoggerFactory.getLogger(KafkaSource.class);
  private ConsumerConnector consumer;
  private ConsumerIterator<byte[],byte[]> it;
  private String topic;
  private int batchUpperLimit;
  private int timeUpperLimit;
  private int consumerTimeout;
  private boolean kafkaAutoCommitEnabled;
  private Context context;
  private Properties kafkaProps;
  private final List<Event> eventList = new ArrayList<Event>();

  public Status process() throws EventDeliveryException {
    eventList.clear();
    byte[] bytes;
    Event event;
    Map<String, String> headers;
    try {
      int eventCounter = 0;
      int timeWaited = 0;
      IterStatus iterStatus = new IterStatus(false, -1);
      while (eventCounter < batchUpperLimit && timeWaited < timeUpperLimit) {
        iterStatus = timedHasNext();
        if (iterStatus.hasData()) {
          // get next message
          bytes = it.next().message();

          headers = new HashMap<String, String>();
          headers.put(KafkaSourceConstants.TIMESTAMP,
                  String.valueOf(System.currentTimeMillis()));
          headers.put(KafkaSourceConstants.TOPIC,topic);
          if (log.isDebugEnabled()) {
            log.debug("Message: {}", new String(bytes));
          }
          event = EventBuilder.withBody(bytes, headers);
          eventList.add(event);
          eventCounter++;
        }
        timeWaited += iterStatus.getWaitTime();
        if (log.isDebugEnabled()) {
          log.debug("Waited: {} ", timeWaited);
          log.debug("Event #: {}", eventCounter);
        }
      }
      // If we have events, send events to channel
      // and commit if Kafka doesn't auto-commit
      if (eventCounter > 0) {
        getChannelProcessor().processEventBatch(eventList);
        if (!kafkaAutoCommitEnabled) {
          // commit the read transactions to Kafka to avoid duplicates
          consumer.commitOffsets();
        }
      }
      if (!iterStatus.hasData()) {
        if (log.isDebugEnabled()) {
          log.debug("Returning with backoff. No more data to read");
        }
        return Status.BACKOFF;
      }
      return Status.READY;
    } catch (Exception e) {
      log.error("KafkaSource EXCEPTION, {}", e);
      return Status.BACKOFF;
    }
  }

  /**
   * We configure the source and generate properties for the Kafka Consumer
   *
   * Kafka Consumer properties are generated as follows:
   * 1. Generate a properties object with some static defaults that
   * can be overridden by Source configuration
   * 2. We add the configuration users added for Kafka (parameters starting
   * with kafka. and must be valid Kafka Consumer properties
   * 3. We add the source documented parameters which can override other
   * properties
   *
   * @param context
   */
  public void configure(Context context) {
    this.context = context;
    batchUpperLimit = context.getInteger(KafkaSourceConstants.BATCH_SIZE,
            KafkaSourceConstants.DEFAULT_BATCH_SIZE);
    timeUpperLimit = context.getInteger(KafkaSourceConstants.BATCH_DURATION_MS,
            KafkaSourceConstants.DEFAULT_BATCH_DURATION);
    topic = context.getString(KafkaSourceConstants.TOPIC);

    if(topic == null) {
      throw new ConfigurationException("Kafka topic must be specified.");
    }

    kafkaProps = KafkaSourceUtil.getKafkaProperties(context);
    consumerTimeout = Integer.parseInt(kafkaProps.getProperty(
            KafkaSourceConstants.CONSUMER_TIMEOUT));
    kafkaAutoCommitEnabled = Boolean.parseBoolean(kafkaProps.getProperty(
            KafkaSourceConstants.AUTO_COMMIT_ENABLED));

  }

  @Override
  public synchronized void start() {
    log.info("Starting {}...", this);

    try {
      //initialize a consumer. This creates the connection to ZooKeeper
      consumer = KafkaSourceUtil.getConsumer(kafkaProps);
    } catch (Exception e) {
      throw new FlumeException("Unable to create consumer. " +
              "Check whether the ZooKeeper server is up and that the " +
              "Flume agent can connect to it.", e);
    }

    Map<String, Integer> topicCountMap = new HashMap<String, Integer>();
    // We always have just one topic being read by one thread
    topicCountMap.put(topic, 1);

    // Get the message iterator for our topic
    // Note that this succeeds even if the topic doesn't exist
    // in that case we simply get no messages for the topic
    // Also note that currently we only support a single topic
    try {
      Map<String, List<KafkaStream<byte[], byte[]>>> consumerMap =
              consumer.createMessageStreams(topicCountMap);
      List<KafkaStream<byte[], byte[]>> topicList = consumerMap.get(topic);
      KafkaStream<byte[], byte[]> stream = topicList.get(0);
      it = stream.iterator();
    } catch (Exception e) {
      throw new FlumeException("Unable to get message iterator from Kafka", e);
    }
    log.info("Kafka source {} started.", getName());
    super.start();
  }

  @Override
  public synchronized void stop() {
    if (consumer != null) {
      // exit cleanly. This syncs offsets of messages read to ZooKeeper
      // to avoid reading the same messages again
      consumer.shutdown();
    }
    super.stop();
  }






  /**
   * Check if there are messages waiting in Kafka,
   * waiting until timeout (10ms by default) for messages to arrive.
   * And timing our wait.
   * @return IterStatus object.
   * Indicating whether a message was found and how long we waited for it
   */
  IterStatus timedHasNext() {
    try {
      long startTime = System.currentTimeMillis();
      it.hasNext();
      long endTime = System.currentTimeMillis();
      return new IterStatus(true, endTime - startTime);
    } catch (ConsumerTimeoutException e) {
      return new IterStatus(false, consumerTimeout);
    }
  }

  private class IterStatus {
    private long waitTime;
    private boolean hasData;


    private IterStatus(boolean hasData,long waitTime) {
      this.waitTime = waitTime;
      this.hasData = hasData;
    }

    public long getWaitTime() {
      return waitTime;
    }

    public boolean hasData() {
      return hasData;
    }
  }
}