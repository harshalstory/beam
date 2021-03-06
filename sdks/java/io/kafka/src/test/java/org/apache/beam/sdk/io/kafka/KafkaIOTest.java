/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.kafka;

import static org.apache.beam.sdk.metrics.MetricResultsMatchers.attemptedMetricsResult;
import static org.apache.beam.sdk.transforms.display.DisplayDataMatchers.hasDisplayItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.beam.sdk.Pipeline.PipelineExecutionException;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.coders.BigEndianIntegerCoder;
import org.apache.beam.sdk.coders.BigEndianLongCoder;
import org.apache.beam.sdk.coders.CoderRegistry;
import org.apache.beam.sdk.coders.InstantCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.coders.VarLongCoder;
import org.apache.beam.sdk.io.Read;
import org.apache.beam.sdk.io.UnboundedSource;
import org.apache.beam.sdk.io.UnboundedSource.UnboundedReader;
import org.apache.beam.sdk.io.kafka.serialization.InstantDeserializer;
import org.apache.beam.sdk.metrics.MetricName;
import org.apache.beam.sdk.metrics.MetricNameFilter;
import org.apache.beam.sdk.metrics.MetricQueryResults;
import org.apache.beam.sdk.metrics.MetricResult;
import org.apache.beam.sdk.metrics.MetricsFilter;
import org.apache.beam.sdk.metrics.SinkMetrics;
import org.apache.beam.sdk.metrics.SourceMetrics;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.Distinct;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.Max;
import org.apache.beam.sdk.transforms.Min;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.Values;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.util.CoderUtils;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionList;
import org.apache.beam.sdk.values.TypeDescriptors;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.utils.Utils;
import org.hamcrest.collection.IsIterableContainingInAnyOrder;
import org.hamcrest.collection.IsIterableWithSize;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests of {@link KafkaIO}.
 * Run with 'mvn test -Dkafka.clients.version=0.10.1.1', to test with a specific Kafka version.
 */
@RunWith(JUnit4.class)
public class KafkaIOTest {

  private static final Logger LOG = LoggerFactory.getLogger(KafkaIOTest.class);

  /*
   * The tests below borrow code and structure from CountingSourceTest. In addition verifies
   * the reader interleaves the records from multiple partitions.
   *
   * Other tests to consider :
   *   - test KafkaRecordCoder
   */

  @Rule
  public final transient TestPipeline p = TestPipeline.create();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private static final Instant LOG_APPEND_START_TIME = new Instant(600 * 1000);

  // Update mock consumer with records distributed among the given topics, each with given number
  // of partitions. Records are assigned in round-robin order among the partitions.
  private static MockConsumer<byte[], byte[]> mkMockConsumer(
      List<String> topics, int partitionsPerTopic, int numElements,
      OffsetResetStrategy offsetResetStrategy) {

    final List<TopicPartition> partitions = new ArrayList<>();
    final Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> records = new HashMap<>();
    Map<String, List<PartitionInfo>> partitionMap = new HashMap<>();

    for (String topic : topics) {
      List<PartitionInfo> partIds = new ArrayList<>(partitionsPerTopic);
      for (int i = 0; i < partitionsPerTopic; i++) {
        TopicPartition tp = new TopicPartition(topic, i);
        partitions.add(tp);
        partIds.add(new PartitionInfo(topic, i, null, null, null));
        records.put(tp, new ArrayList<>());
      }
      partitionMap.put(topic, partIds);
    }

    int numPartitions = partitions.size();
    final long[] offsets = new long[numPartitions];


    for (int i = 0; i < numElements; i++) {
      int pIdx = i % numPartitions;
      TopicPartition tp = partitions.get(pIdx);

      byte[] key = ByteBuffer.wrap(new byte[4]).putInt(i).array();    // key is 4 byte record id
      byte[] value =  ByteBuffer.wrap(new byte[8]).putLong(i).array(); // value is 8 byte record id

      records.get(tp).add(
          new ConsumerRecord<>(
              tp.topic(),
              tp.partition(),
              offsets[pIdx]++,
              LOG_APPEND_START_TIME.plus(Duration.standardSeconds(i)).getMillis(),
              TimestampType.LOG_APPEND_TIME,
              0, key.length, value.length, key, value));
    }

    // This is updated when reader assigns partitions.
    final AtomicReference<List<TopicPartition>> assignedPartitions =
        new AtomicReference<>(Collections.<TopicPartition>emptyList());

    final MockConsumer<byte[], byte[]> consumer =
        new MockConsumer<byte[], byte[]>(offsetResetStrategy) {
          @Override
          public void assign(final Collection<TopicPartition> assigned) {
            super.assign(assigned);
            assignedPartitions.set(ImmutableList.copyOf(assigned));
            for (TopicPartition tp : assigned) {
              updateBeginningOffsets(ImmutableMap.of(tp, 0L));
              updateEndOffsets(ImmutableMap.of(tp, (long) records.get(tp).size()));
            }
          }
          // Override offsetsForTimes() in order to look up the offsets by timestamp.
          @Override
          public Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes(
            Map<TopicPartition, Long> timestampsToSearch) {
            return timestampsToSearch
              .entrySet()
              .stream()
              .map(e -> {
                // In test scope, timestamp == offset.
                long maxOffset = offsets[partitions.indexOf(e.getKey())];
                long offset = e.getValue();
                OffsetAndTimestamp value = (offset >= maxOffset)
                  ? null : new OffsetAndTimestamp(offset, offset);
                return new SimpleEntry<>(e.getKey(), value);
              })
              .collect(Collectors.toMap(SimpleEntry::getKey, SimpleEntry::getValue));
          }
        };

    for (String topic : topics) {
      consumer.updatePartitions(topic, partitionMap.get(topic));
    }

    // MockConsumer does not maintain any relationship between partition seek position and the
    // records added. e.g. if we add 10 records to a partition and then seek to end of the
    // partition, MockConsumer is still going to return the 10 records in next poll. It is
    // our responsibility to make sure currently enqueued records sync with partition offsets.
    // The following task will be called inside each invocation to MockConsumer.poll().
    // We enqueue only the records with the offset >= partition's current position.
    Runnable recordEnqueueTask = new Runnable() {
      @Override
      public void run() {
        // add all the records with offset >= current partition position.
        for (TopicPartition tp : assignedPartitions.get()) {
          long curPos = consumer.position(tp);
          for (ConsumerRecord<byte[], byte[]> r : records.get(tp)) {
            if (r.offset() >= curPos) {
              consumer.addRecord(r);
            }
          }
        }
        consumer.schedulePollTask(this);
      }
    };

    consumer.schedulePollTask(recordEnqueueTask);
    return consumer;
  }

  private static class ConsumerFactoryFn
                implements SerializableFunction<Map<String, Object>, Consumer<byte[], byte[]>> {
    private final List<String> topics;
    private final int partitionsPerTopic;
    private final int numElements;
    private final OffsetResetStrategy offsetResetStrategy;

    ConsumerFactoryFn(List<String> topics,
                      int partitionsPerTopic,
                      int numElements,
                      OffsetResetStrategy offsetResetStrategy) {
      this.topics = topics;
      this.partitionsPerTopic = partitionsPerTopic;
      this.numElements = numElements;
      this.offsetResetStrategy = offsetResetStrategy;
    }

    @Override
    public Consumer<byte[], byte[]> apply(Map<String, Object> config) {
      return mkMockConsumer(topics, partitionsPerTopic, numElements, offsetResetStrategy);
    }
  }

  private static KafkaIO.Read<Integer, Long> mkKafkaReadTransform(
      int numElements,
      @Nullable SerializableFunction<KV<Integer, Long>, Instant> timestampFn) {
    return mkKafkaReadTransform(numElements, numElements, timestampFn);
  }

  /**
   * Creates a consumer with two topics, with 10 partitions each.
   * numElements are (round-robin) assigned all the 20 partitions.
   */
  private static KafkaIO.Read<Integer, Long> mkKafkaReadTransform(
      int numElements,
      int maxNumRecords,
      @Nullable SerializableFunction<KV<Integer, Long>, Instant> timestampFn) {

    List<String> topics = ImmutableList.of("topic_a", "topic_b");

    KafkaIO.Read<Integer, Long> reader = KafkaIO.<Integer, Long>read()
        .withBootstrapServers("myServer1:9092,myServer2:9092")
        .withTopics(topics)
        .withConsumerFactoryFn(new ConsumerFactoryFn(
            topics, 10, numElements, OffsetResetStrategy.EARLIEST)) // 20 partitions
        .withKeyDeserializer(IntegerDeserializer.class)
        .withValueDeserializer(LongDeserializer.class)
        .withMaxNumRecords(maxNumRecords);

    if (timestampFn != null) {
      return reader.withTimestampFn(timestampFn);
    } else {
      return reader;
    }
  }

  private static class AssertMultipleOf implements SerializableFunction<Iterable<Long>, Void> {
    private final int num;

    AssertMultipleOf(int num) {
      this.num = num;
    }

    @Override
    public Void apply(Iterable<Long> values) {
      for (Long v : values) {
        assertEquals(0, v % num);
      }
      return null;
    }
  }

  public static void addCountingAsserts(PCollection<Long> input, long numElements) {
    // Count == numElements
    // Unique count == numElements
    // Min == 0
    // Max == numElements-1
    addCountingAsserts(input, numElements, numElements, 0L, numElements - 1);
  }

  public static void addCountingAsserts(
      PCollection<Long> input, long count, long uniqueCount, long min, long max) {

    PAssert.thatSingleton(input.apply("Count", Count.globally())).isEqualTo(count);

    PAssert.thatSingleton(input.apply(Distinct.create()).apply("UniqueCount", Count.globally()))
        .isEqualTo(uniqueCount);

    PAssert.thatSingleton(input.apply("Min", Min.globally())).isEqualTo(min);

    PAssert.thatSingleton(input.apply("Max", Max.globally())).isEqualTo(max);
  }

  @Test
  public void testUnboundedSource() {
    int numElements = 1000;

    PCollection<Long> input =
        p.apply(mkKafkaReadTransform(numElements, new ValueAsTimestampFn()).withoutMetadata())
            .apply(Values.create());

    addCountingAsserts(input, numElements);
    p.run();
  }

  @Test
  public void testUnreachableKafkaBrokers() {
    // Expect an exception when the Kafka brokers are not reachable on the workers.
    // We specify partitions explicitly so that splitting does not involve server interaction.
    // Set request timeout to 10ms so that test does not take long.

    thrown.expect(Exception.class);
    thrown.expectMessage("Reader-0: Timeout while initializing partition 'test-0'");

    int numElements = 1000;
    PCollection<Long> input =
        p.apply(
                KafkaIO.<Integer, Long>read()
                    .withBootstrapServers("8.8.8.8:9092") // Google public DNS ip.
                    .withTopicPartitions(ImmutableList.of(new TopicPartition("test", 0)))
                    .withKeyDeserializer(IntegerDeserializer.class)
                    .withValueDeserializer(LongDeserializer.class)
                    .updateConsumerProperties(
                        ImmutableMap.of(
                            ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10,
                            ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 5,
                            ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 8,
                            ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 8))
                    .withMaxNumRecords(10)
                    .withoutMetadata())
            .apply(Values.create());

    addCountingAsserts(input, numElements);
    p.run();
  }

  @Test
  public void testUnboundedSourceWithSingleTopic() {
    // same as testUnboundedSource, but with single topic

    int numElements = 1000;
    String topic = "my_topic";

    KafkaIO.Read<Integer, Long> reader = KafkaIO.<Integer, Long>read()
        .withBootstrapServers("none")
        .withTopic("my_topic")
        .withConsumerFactoryFn(new ConsumerFactoryFn(
            ImmutableList.of(topic), 10, numElements, OffsetResetStrategy.EARLIEST))
        .withMaxNumRecords(numElements)
        .withKeyDeserializer(IntegerDeserializer.class)
        .withValueDeserializer(LongDeserializer.class);

    PCollection<Long> input = p.apply(reader.withoutMetadata()).apply(Values.create());

    addCountingAsserts(input, numElements);
    p.run();
  }

  @Test
  public void testUnboundedSourceWithExplicitPartitions() {
    int numElements = 1000;

    List<String> topics = ImmutableList.of("test");

    KafkaIO.Read<byte[], Long> reader = KafkaIO.<byte[], Long>read()
        .withBootstrapServers("none")
        .withTopicPartitions(ImmutableList.of(new TopicPartition("test", 5)))
        .withConsumerFactoryFn(new ConsumerFactoryFn(
            topics, 10, numElements, OffsetResetStrategy.EARLIEST)) // 10 partitions
        .withKeyDeserializer(ByteArrayDeserializer.class)
        .withValueDeserializer(LongDeserializer.class)
        .withMaxNumRecords(numElements / 10);

    PCollection<Long> input = p.apply(reader.withoutMetadata()).apply(Values.create());

    // assert that every element is a multiple of 5.
    PAssert
      .that(input)
      .satisfies(new AssertMultipleOf(5));

    PAssert.thatSingleton(input.apply(Count.globally())).isEqualTo(numElements / 10L);

    p.run();
  }

  private static class ElementValueDiff extends DoFn<Long, Long> {
    @ProcessElement
    public void processElement(ProcessContext c) throws Exception {
      c.output(c.element() - c.timestamp().getMillis());
    }
  }

  @Test
  public void testUnboundedSourceTimestamps() {

    int numElements = 1000;

    PCollection<Long> input =
        p.apply(mkKafkaReadTransform(numElements, new ValueAsTimestampFn()).withoutMetadata())
            .apply(Values.create());

    addCountingAsserts(input, numElements);

    PCollection<Long> diffs =
        input
            .apply("TimestampDiff", ParDo.of(new ElementValueDiff()))
            .apply("DistinctTimestamps", Distinct.create());
    // This assert also confirms that diffs only has one unique value.
    PAssert.thatSingleton(diffs).isEqualTo(0L);

    p.run();
  }

  @Test
  public void testUnboundedSourceLogAppendTimestamps() {
    // LogAppendTime (server side timestamp) for records is set based on record index
    // in MockConsumer above. Ensure that those exact timestamps are set by the source.
    int numElements = 1000;

    PCollection<Long> input =
      p.apply(mkKafkaReadTransform(numElements, null)
                .withLogAppendTime()
                .withoutMetadata())
        .apply(Values.create());

    addCountingAsserts(input, numElements);

    PCollection<Long> diffs =
      input
        .apply(MapElements.into(TypeDescriptors.longs()).via(t ->
          LOG_APPEND_START_TIME.plus(Duration.standardSeconds(t)).getMillis()))
        .apply("TimestampDiff", ParDo.of(new ElementValueDiff()))
        .apply("DistinctTimestamps", Distinct.create());

    // This assert also confirms that diff only has one unique value.
    PAssert.thatSingleton(diffs).isEqualTo(0L);

    p.run();
  }

  // Returns TIMESTAMP_MAX_VALUE for watermark when all the records are read from a partition.
  static class TimestampPolicyWithEndOfSource<K, V> extends TimestampPolicyFactory<K, V> {
    private final long maxOffset;

    TimestampPolicyWithEndOfSource(long maxOffset) {
      this.maxOffset = maxOffset;
    }

    @Override
    public TimestampPolicy<K, V> createTimestampPolicy(
      TopicPartition tp, Optional<Instant> previousWatermark) {
      return new TimestampPolicy<K, V>() {
        long lastOffset = 0;
        Instant lastTimestamp = BoundedWindow.TIMESTAMP_MIN_VALUE;

        @Override
        public Instant getTimestampForRecord(PartitionContext ctx, KafkaRecord<K, V> record) {
          lastOffset = record.getOffset();
          lastTimestamp = new Instant(record.getTimestamp());
          return lastTimestamp;
        }

        @Override
        public Instant getWatermark(PartitionContext ctx) {
          if (lastOffset < maxOffset) {
            return lastTimestamp;
          } else { // EOF
            return BoundedWindow.TIMESTAMP_MAX_VALUE;
          }
        }
      };
    }
  }

  @Test
  public void testUnboundedSourceWithoutBoundedWrapper() {
    // Most of the tests in this file set 'maxNumRecords' on the source, which wraps
    // the unbounded source in a bounded source. As a result, the test pipeline run as
    // bounded/batch pipelines under direct-runner.
    // This is same as testUnboundedSource() without the BoundedSource wrapper.

    final int numElements = 1000;
    final int numPartitions = 10;
    String topic = "testUnboundedSourceWithoutBoundedWrapper";

    KafkaIO.Read<byte[], Long> reader = KafkaIO.<byte[], Long>read()
      .withBootstrapServers(topic)
      .withTopic(topic)
      .withConsumerFactoryFn(new ConsumerFactoryFn(
        ImmutableList.of(topic), numPartitions, numElements, OffsetResetStrategy.EARLIEST))
      .withKeyDeserializer(ByteArrayDeserializer.class)
      .withValueDeserializer(LongDeserializer.class)
      .withTimestampPolicyFactory(
        new TimestampPolicyWithEndOfSource<>(numElements / numPartitions - 1));

    PCollection <Long> input =
      p.apply("readFromKafka", reader.withoutMetadata())
        .apply(Values.create())
        .apply(Window.into(FixedWindows.of(Duration.standardDays(100))));

    PipelineResult result = p.run();

    MetricName elementsRead = SourceMetrics.elementsRead().getName();

    MetricQueryResults metrics = result.metrics().queryMetrics(
      MetricsFilter.builder()
        .addNameFilter(MetricNameFilter.inNamespace(elementsRead.namespace()))
        .build());

    assertThat(metrics.counters(), hasItem(
      attemptedMetricsResult(
        elementsRead.namespace(),
        elementsRead.name(),
        "readFromKafka",
              (long) numElements)));
  }

  private static class RemoveKafkaMetadata<K, V> extends DoFn<KafkaRecord<K, V>, KV<K, V>> {
    @ProcessElement
    public void processElement(ProcessContext ctx) {
      ctx.output(ctx.element().getKV());
    }
  }

  @Test
  public void testUnboundedSourceSplits() throws Exception {

    int numElements = 1000;
    int numSplits = 10;

    // Coders must be specified explicitly here due to the way the transform
    // is used in the test.
    UnboundedSource<KafkaRecord<Integer, Long>, ?> initial =
        mkKafkaReadTransform(numElements, null)
            .withKeyDeserializerAndCoder(IntegerDeserializer.class, BigEndianIntegerCoder.of())
            .withValueDeserializerAndCoder(LongDeserializer.class, BigEndianLongCoder.of())
            .makeSource();

    List<? extends UnboundedSource<KafkaRecord<Integer, Long>, ?>> splits =
        initial.split(numSplits, p.getOptions());
    assertEquals("Expected exact splitting", numSplits, splits.size());

    long elementsPerSplit = numElements / numSplits;
    assertEquals("Expected even splits", numElements, elementsPerSplit * numSplits);
    PCollectionList<Long> pcollections = PCollectionList.empty(p);
    for (int i = 0; i < splits.size(); ++i) {
      pcollections =
          pcollections.and(
              p.apply("split" + i, Read.from(splits.get(i)).withMaxNumRecords(elementsPerSplit))
                  .apply("Remove Metadata " + i, ParDo.of(new RemoveKafkaMetadata<>()))
                  .apply("collection " + i, Values.create()));
    }
    PCollection<Long> input = pcollections.apply(Flatten.pCollections());

    addCountingAsserts(input, numElements);
    p.run();
  }

  /**
   * A timestamp function that uses the given value as the timestamp.
   */
  private static class ValueAsTimestampFn
                       implements SerializableFunction<KV<Integer, Long>, Instant> {
    @Override
    public Instant apply(KV<Integer, Long> input) {
      return new Instant(input.getValue());
    }
  }

  // Kafka records are read in a separate thread inside the reader. As a result advance() might not
  // read any records even from the mock consumer, especially for the first record.
  // This is a helper method to loop until we read a record.
  private static void advanceOnce(UnboundedReader<?> reader, boolean isStarted) throws IOException {
    if (!isStarted && reader.start()) {
      return;
    }
    while (!reader.advance()) {
      // very rarely will there be more than one attempts.
      // In case of a bug we might end up looping forever, and test will fail with a timeout.

      // Avoid hard cpu spinning in case of a test failure.
      try {
        Thread.sleep(1);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Test
  public void testUnboundedSourceCheckpointMark() throws Exception {
    int numElements = 85; // 85 to make sure some partitions have more records than other.

    // create a single split:
    UnboundedSource<KafkaRecord<Integer, Long>, KafkaCheckpointMark> source =
        mkKafkaReadTransform(numElements, new ValueAsTimestampFn())
          .makeSource()
          .split(1, PipelineOptionsFactory.create())
          .get(0);

    UnboundedReader<KafkaRecord<Integer, Long>> reader = source.createReader(null, null);
    final int numToSkip = 20; // one from each partition.

    // advance numToSkip elements
    for (int i = 0; i < numToSkip; ++i) {
      advanceOnce(reader, i > 0);
    }

    // Confirm that we get the expected element in sequence before checkpointing.
    assertEquals(numToSkip - 1, (long) reader.getCurrent().getKV().getValue());
    assertEquals(numToSkip - 1, reader.getCurrentTimestamp().getMillis());

    // Checkpoint and restart, and confirm that the source continues correctly.
    KafkaCheckpointMark mark = CoderUtils.clone(
        source.getCheckpointMarkCoder(), (KafkaCheckpointMark) reader.getCheckpointMark());
    reader = source.createReader(null, mark);

    // Confirm that we get the next elements in sequence.
    // This also confirms that Reader interleaves records from each partitions by the reader.

    for (int i = numToSkip; i < numElements; i++) {
      advanceOnce(reader, i > numToSkip);
      assertEquals(i, (long) reader.getCurrent().getKV().getValue());
      assertEquals(i, reader.getCurrentTimestamp().getMillis());
    }
  }

  @Test
  public void testUnboundedSourceCheckpointMarkWithEmptyPartitions() throws Exception {
    // Similar to testUnboundedSourceCheckpointMark(), but verifies that source resumes
    // properly from empty partitions, without missing messages added since checkpoint.

    // Initialize consumer with fewer elements than number of partitions so that some are empty.
    int initialNumElements = 5;
    UnboundedSource<KafkaRecord<Integer, Long>, KafkaCheckpointMark> source =
        mkKafkaReadTransform(initialNumElements, new ValueAsTimestampFn())
            .makeSource()
            .split(1, PipelineOptionsFactory.create())
            .get(0);

    UnboundedReader<KafkaRecord<Integer, Long>> reader = source.createReader(null, null);

    for (int l = 0; l < initialNumElements; ++l) {
      advanceOnce(reader, l > 0);
    }

    // Checkpoint and restart, and confirm that the source continues correctly.
    KafkaCheckpointMark mark = CoderUtils.clone(
        source.getCheckpointMarkCoder(), (KafkaCheckpointMark) reader.getCheckpointMark());

    // Create another source with MockConsumer with OffsetResetStrategy.LATEST. This insures that
    // the reader need to explicitly need to seek to first offset for partitions that were empty.

    int numElements = 100; // all the 20 partitions will have elements
    List<String> topics = ImmutableList.of("topic_a", "topic_b");

    source = KafkaIO.<Integer, Long>read()
        .withBootstrapServers("none")
        .withTopics(topics)
        .withConsumerFactoryFn(new ConsumerFactoryFn(
            topics, 10, numElements, OffsetResetStrategy.LATEST))
        .withKeyDeserializer(IntegerDeserializer.class)
        .withValueDeserializer(LongDeserializer.class)
        .withMaxNumRecords(numElements)
        .withTimestampFn(new ValueAsTimestampFn())
        .makeSource()
        .split(1, PipelineOptionsFactory.create())
        .get(0);

    reader = source.createReader(null, mark);

    // Verify in any order. As the partitions are unevenly read, the returned records are not in a
    // simple order. Note that testUnboundedSourceCheckpointMark() verifies round-robin oder.

    List<Long> expected = new ArrayList<>();
    List<Long> actual = new ArrayList<>();
    for (long i = initialNumElements; i < numElements; i++) {
      advanceOnce(reader, i > initialNumElements);
      expected.add(i);
      actual.add(reader.getCurrent().getKV().getValue());
    }
    assertThat(actual, IsIterableContainingInAnyOrder.containsInAnyOrder(expected.toArray()));
  }

  @Test
  public void testUnboundedSourceMetrics() {
    int numElements = 1000;

    String readStep = "readFromKafka";

    p.apply(readStep,
        mkKafkaReadTransform(numElements, new ValueAsTimestampFn())
          .updateConsumerProperties(ImmutableMap.of(ConsumerConfig.GROUP_ID_CONFIG, "test.group"))
          .commitOffsetsInFinalize()
          .withoutMetadata());

    PipelineResult result = p.run();

    String splitId = "0";

    MetricName elementsRead = SourceMetrics.elementsRead().getName();
    MetricName elementsReadBySplit = SourceMetrics.elementsReadBySplit(splitId).getName();
    MetricName bytesRead = SourceMetrics.bytesRead().getName();
    MetricName bytesReadBySplit = SourceMetrics.bytesReadBySplit(splitId).getName();
    MetricName backlogElementsOfSplit = SourceMetrics.backlogElementsOfSplit(splitId).getName();
    MetricName backlogBytesOfSplit = SourceMetrics.backlogBytesOfSplit(splitId).getName();

    MetricQueryResults metrics = result.metrics().queryMetrics(
        MetricsFilter.builder().build());

    Iterable<MetricResult<Long>> counters = metrics.counters();

    assertThat(counters, hasItem(attemptedMetricsResult(
        elementsRead.namespace(),
        elementsRead.name(),
        readStep,
        1000L)));

    assertThat(counters, hasItem(attemptedMetricsResult(
        elementsReadBySplit.namespace(),
        elementsReadBySplit.name(),
        readStep,
        1000L)));

    assertThat(counters, hasItem(attemptedMetricsResult(
        bytesRead.namespace(),
        bytesRead.name(),
        readStep,
        12000L)));

    assertThat(counters, hasItem(attemptedMetricsResult(
        bytesReadBySplit.namespace(),
        bytesReadBySplit.name(),
        readStep,
        12000L)));

    MetricQueryResults backlogElementsMetrics =
        result.metrics().queryMetrics(
            MetricsFilter.builder()
                .addNameFilter(
                    MetricNameFilter.named(
                        backlogElementsOfSplit.namespace(),
                        backlogElementsOfSplit.name()))
                .build());

    // since gauge values may be inconsistent in some environments assert only on their existence.
    assertThat(backlogElementsMetrics.gauges(), IsIterableWithSize.iterableWithSize(1));

    MetricQueryResults backlogBytesMetrics =
        result.metrics().queryMetrics(
            MetricsFilter.builder()
                .addNameFilter(
                    MetricNameFilter.named(
                        backlogBytesOfSplit.namespace(),
                        backlogBytesOfSplit.name()))
                .build());

    // since gauge values may be inconsistent in some environments assert only on their existence.
    assertThat(backlogBytesMetrics.gauges(), IsIterableWithSize.iterableWithSize(1));

    // Check checkpointMarkCommitsEnqueued metric.
    MetricQueryResults commitsEnqueuedMetrics =
        result.metrics().queryMetrics(
            MetricsFilter.builder()
                .addNameFilter(
                    MetricNameFilter.named(
                        KafkaUnboundedReader.METRIC_NAMESPACE,
                        KafkaUnboundedReader.CHECKPOINT_MARK_COMMITS_ENQUEUED_METRIC))
                .build());

    assertThat(commitsEnqueuedMetrics.counters(), IsIterableWithSize.iterableWithSize(1));
    assertThat(commitsEnqueuedMetrics.counters().iterator().next().attempted(), greaterThan(0L));
  }

  @Test
  public void testSink() throws Exception {
    // Simply read from kafka source and write to kafka sink. Then verify the records
    // are correctly published to mock kafka producer.

    int numElements = 1000;

    try (MockProducerWrapper producerWrapper = new MockProducerWrapper()) {

      ProducerSendCompletionThread completionThread =
        new ProducerSendCompletionThread(producerWrapper.mockProducer).start();

      String topic = "test";

      p
        .apply(mkKafkaReadTransform(numElements, new ValueAsTimestampFn())
            .withoutMetadata())
        .apply(KafkaIO.<Integer, Long>write()
            .withBootstrapServers("none")
            .withTopic(topic)
            .withKeySerializer(IntegerSerializer.class)
            .withValueSerializer(LongSerializer.class)
            .withInputTimestamp()
            .withProducerFactoryFn(new ProducerFactoryFn(producerWrapper.producerKey)));

      p.run();

      completionThread.shutdown();

      verifyProducerRecords(producerWrapper.mockProducer, topic, numElements, false, true);
    }
  }

  @Test
  public void testValuesSink() throws Exception {
    // similar to testSink(), but use values()' interface.

    int numElements = 1000;

    try (MockProducerWrapper producerWrapper = new MockProducerWrapper()) {

      ProducerSendCompletionThread completionThread =
        new ProducerSendCompletionThread(producerWrapper.mockProducer).start();

      String topic = "test";

      p.apply(mkKafkaReadTransform(numElements, new ValueAsTimestampFn()).withoutMetadata())
          .apply(Values.create()) // there are no keys
          .apply(
              KafkaIO.<Integer, Long>write()
                  .withBootstrapServers("none")
                  .withTopic(topic)
                  .withValueSerializer(LongSerializer.class)
                  .withProducerFactoryFn(new ProducerFactoryFn(producerWrapper.producerKey))
                  .values());

      p.run();

      completionThread.shutdown();

      verifyProducerRecords(producerWrapper.mockProducer, topic, numElements, true, false);
    }
  }

  @Test
  public void testExactlyOnceSink() {
    // testSink() with EOS enabled.
    // This does not actually inject retries in a stage to test exactly-once-semantics.
    // It mainly exercises the code in normal flow without retries.
    // Ideally we should test EOS Sink by triggering replays of a messages between stages.
    // It is not feasible to test such retries with direct runner. When DoFnTester supports
    // state, we can test ExactlyOnceWriter DoFn directly to ensure it handles retries correctly.

    if (!ProducerSpEL.supportsTransactions()) {
      LOG.warn(
        "testExactlyOnceSink() is disabled as Kafka client version does not support transactions.");
      return;
    }

    int numElements = 1000;

    try (MockProducerWrapper producerWrapper = new MockProducerWrapper()) {

      ProducerSendCompletionThread completionThread =
        new ProducerSendCompletionThread(producerWrapper.mockProducer).start();

      String topic = "test";

      p
        .apply(mkKafkaReadTransform(numElements, new ValueAsTimestampFn())
                 .withoutMetadata())
        .apply(KafkaIO.<Integer, Long>write()
                 .withBootstrapServers("none")
                 .withTopic(topic)
                 .withKeySerializer(IntegerSerializer.class)
                 .withValueSerializer(LongSerializer.class)
                 .withEOS(1, "test")
                 .withConsumerFactoryFn(new ConsumerFactoryFn(
                   Lists.newArrayList(topic), 10, 10, OffsetResetStrategy.EARLIEST))
                 .withPublishTimestampFunction((e, ts) -> ts)
                 .withProducerFactoryFn(new ProducerFactoryFn(producerWrapper.producerKey)));

      p.run();

      completionThread.shutdown();

      verifyProducerRecords(producerWrapper.mockProducer, topic, numElements, false, true);
    }
  }

  @Test
  public void testSinkWithSendErrors() throws Throwable {
    // similar to testSink(), except that up to 10 of the send calls to producer will fail
    // asynchronously.

    // TODO: Ideally we want the pipeline to run to completion by retrying bundles that fail.
    // We limit the number of errors injected to 10 below. This would reflect a real streaming
    // pipeline. But I am sure how to achieve that. For now expect an exception:

    thrown.expect(InjectedErrorException.class);
    thrown.expectMessage("Injected Error #1");

    int numElements = 1000;

    try (MockProducerWrapper producerWrapper = new MockProducerWrapper()) {

      ProducerSendCompletionThread completionThreadWithErrors =
        new ProducerSendCompletionThread(producerWrapper.mockProducer, 10, 100).start();

      String topic = "test";

      p
        .apply(mkKafkaReadTransform(numElements, new ValueAsTimestampFn())
            .withoutMetadata())
        .apply(KafkaIO.<Integer, Long>write()
            .withBootstrapServers("none")
            .withTopic(topic)
            .withKeySerializer(IntegerSerializer.class)
            .withValueSerializer(LongSerializer.class)
            .withProducerFactoryFn(new ProducerFactoryFn(producerWrapper.producerKey)));

      try {
        p.run();
      } catch (PipelineExecutionException e) {
        // throwing inner exception helps assert that first exception is thrown from the Sink
        throw e.getCause().getCause();
      } finally {
        completionThreadWithErrors.shutdown();
      }
    }
  }

  @Test
  public void testUnboundedSourceStartReadTime() {

    assumeTrue(new ConsumerSpEL().hasOffsetsForTimes());

    int numElements = 1000;
    // In this MockConsumer, we let the elements of the time and offset equal and there are 20
    // partitions. So set this startTime can read half elements.
    int startTime = numElements / 20 / 2;
    int maxNumRecords = numElements / 2;

    PCollection<Long> input =
        p.apply(
                mkKafkaReadTransform(numElements, maxNumRecords, new ValueAsTimestampFn())
                    .withStartReadTime(new Instant(startTime))
                    .withoutMetadata())
            .apply(Values.create());

    addCountingAsserts(input, maxNumRecords, maxNumRecords, maxNumRecords, numElements - 1);
    p.run();

  }

  @Rule public ExpectedException noMessagesException = ExpectedException.none();

  @Test
  public void testUnboundedSourceStartReadTimeException() {

    assumeTrue(new ConsumerSpEL().hasOffsetsForTimes());

    noMessagesException.expect(RuntimeException.class);

    int numElements = 1000;
    // In this MockConsumer, we let the elements of the time and offset equal and there are 20
    // partitions. So set this startTime can not read any element.
    int startTime = numElements / 20;

    p.apply(
            mkKafkaReadTransform(numElements, numElements, new ValueAsTimestampFn())
                .withStartReadTime(new Instant(startTime))
                .withoutMetadata())
        .apply(Values.create());

    p.run();

  }

  @Test
  public void testSourceDisplayData() {
    KafkaIO.Read<Integer, Long> read = mkKafkaReadTransform(10, null);

    DisplayData displayData = DisplayData.from(read);

    assertThat(displayData, hasDisplayItem("topics", "topic_a,topic_b"));
    assertThat(displayData, hasDisplayItem("enable.auto.commit", false));
    assertThat(displayData, hasDisplayItem("bootstrap.servers", "myServer1:9092,myServer2:9092"));
    assertThat(displayData, hasDisplayItem("auto.offset.reset", "latest"));
    assertThat(displayData, hasDisplayItem("receive.buffer.bytes", 524288));
  }

  @Test
  public void testSourceWithExplicitPartitionsDisplayData() {
    KafkaIO.Read<byte[], byte[]> read = KafkaIO.readBytes()
        .withBootstrapServers("myServer1:9092,myServer2:9092")
        .withTopicPartitions(ImmutableList.of(new TopicPartition("test", 5),
            new TopicPartition("test", 6)))
        .withConsumerFactoryFn(new ConsumerFactoryFn(
            Lists.newArrayList("test"), 10, 10, OffsetResetStrategy.EARLIEST)); // 10 partitions

    DisplayData displayData = DisplayData.from(read);

    assertThat(displayData, hasDisplayItem("topicPartitions", "test-5,test-6"));
    assertThat(displayData, hasDisplayItem("enable.auto.commit", false));
    assertThat(displayData, hasDisplayItem("bootstrap.servers", "myServer1:9092,myServer2:9092"));
    assertThat(displayData, hasDisplayItem("auto.offset.reset", "latest"));
    assertThat(displayData, hasDisplayItem("receive.buffer.bytes", 524288));
  }

  @Test
  public void testSinkDisplayData() {
    try (MockProducerWrapper producerWrapper = new MockProducerWrapper()) {
      KafkaIO.Write<Integer, Long> write = KafkaIO.<Integer, Long>write()
        .withBootstrapServers("myServerA:9092,myServerB:9092")
        .withTopic("myTopic")
        .withValueSerializer(LongSerializer.class)
        .withProducerFactoryFn(new ProducerFactoryFn(producerWrapper.producerKey));

      DisplayData displayData = DisplayData.from(write);

      assertThat(displayData, hasDisplayItem("topic", "myTopic"));
      assertThat(displayData, hasDisplayItem("bootstrap.servers", "myServerA:9092,myServerB:9092"));
      assertThat(displayData, hasDisplayItem("retries", 3));
    }
  }

  // interface for testing coder inference
  private interface DummyInterface<T> {
  }

  // interface for testing coder inference
  private interface DummyNonparametricInterface {
  }

  // class for testing coder inference
  private static class DeserializerWithInterfaces
          implements DummyInterface<String>, DummyNonparametricInterface,
            Deserializer<Long> {

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
    }

    @Override
    public Long deserialize(String topic, byte[] bytes) {
      return 0L;
    }

    @Override
    public void close() {
    }
  }

  // class for which a coder cannot be infered
  private static class NonInferableObject {

  }

  // class for testing coder inference
  private static class NonInferableObjectDeserializer
          implements Deserializer<NonInferableObject> {

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
    }

    @Override
    public NonInferableObject deserialize(String topic, byte[] bytes) {
      return new NonInferableObject();
    }

    @Override
    public void close() {
    }
  }

  @Test
  public void testInferKeyCoder() {
    CoderRegistry registry = CoderRegistry.createDefault();

    assertTrue(KafkaIO.inferCoder(registry, LongDeserializer.class).getValueCoder()
            instanceof VarLongCoder);

    assertTrue(KafkaIO.inferCoder(registry, StringDeserializer.class).getValueCoder()
            instanceof StringUtf8Coder);

    assertTrue(KafkaIO.inferCoder(registry, InstantDeserializer.class).getValueCoder()
            instanceof InstantCoder);

    assertTrue(KafkaIO.inferCoder(registry, DeserializerWithInterfaces.class).getValueCoder()
            instanceof VarLongCoder);
  }

  @Rule public ExpectedException cannotInferException = ExpectedException.none();

  @Test
  public void testInferKeyCoderFailure() throws Exception {
    cannotInferException.expect(RuntimeException.class);

    CoderRegistry registry = CoderRegistry.createDefault();
    KafkaIO.inferCoder(registry, NonInferableObjectDeserializer.class);
  }

  @Test
  public void testSinkMetrics() throws Exception {
    // Simply read from kafka source and write to kafka sink. Then verify the metrics are reported.

    int numElements = 1000;

    try (MockProducerWrapper producerWrapper = new MockProducerWrapper()) {

      ProducerSendCompletionThread completionThread =
        new ProducerSendCompletionThread(producerWrapper.mockProducer).start();

      String topic = "test";

      p
          .apply(mkKafkaReadTransform(numElements, new ValueAsTimestampFn())
              .withoutMetadata())
          .apply("writeToKafka", KafkaIO.<Integer, Long>write()
              .withBootstrapServers("none")
              .withTopic(topic)
              .withKeySerializer(IntegerSerializer.class)
              .withValueSerializer(LongSerializer.class)
              .withProducerFactoryFn(new ProducerFactoryFn(producerWrapper.producerKey)));

      PipelineResult result = p.run();

      MetricName elementsWritten = SinkMetrics.elementsWritten().getName();

      MetricQueryResults metrics = result.metrics().queryMetrics(
          MetricsFilter.builder()
              .addNameFilter(MetricNameFilter.inNamespace(elementsWritten.namespace()))
              .build());

      assertThat(metrics.counters(), hasItem(
          attemptedMetricsResult(
              elementsWritten.namespace(),
              elementsWritten.name(),
              "writeToKafka",
              1000L)));

      completionThread.shutdown();
    }
  }

  private static void verifyProducerRecords(MockProducer<Integer, Long> mockProducer,
                                            String topic,
                                            int numElements,
                                            boolean keyIsAbsent,
                                            boolean verifyTimestamp) {

    // verify that appropriate messages are written to kafka
    List<ProducerRecord<Integer, Long>> sent = mockProducer.history();

    // sort by values
    sent.sort(Comparator.comparingLong(ProducerRecord::value));

    for (int i = 0; i < numElements; i++) {
      ProducerRecord<Integer, Long> record = sent.get(i);
      assertEquals(topic, record.topic());
      if (keyIsAbsent) {
        assertNull(record.key());
      } else {
        assertEquals(i, record.key().intValue());
      }
      assertEquals(i, record.value().longValue());
      if (verifyTimestamp) {
        assertEquals(i, record.timestamp().intValue());
      }
    }
  }

  /**
   * This wrapper over MockProducer. It also places the mock producer in global MOCK_PRODUCER_MAP.
   * The map is needed so that the producer returned by ProducerFactoryFn during pipeline can be
   * used in verification after the test. We also override {@code flush()} method in MockProducer
   * so that test can control behavior of {@code send()} method (e.g. to inject errors).
   */
  private static class MockProducerWrapper implements AutoCloseable {

    final String producerKey;
    final MockProducer<Integer, Long> mockProducer;

    // MockProducer has "closed" method starting version 0.11.
    private static Method closedMethod;

    static {
      try {
        closedMethod = MockProducer.class.getMethod("closed");
      } catch (NoSuchMethodException e) {
        closedMethod = null;
      }
    }


    MockProducerWrapper() {
      producerKey = String.valueOf(ThreadLocalRandom.current().nextLong());
      mockProducer = new MockProducer<Integer, Long>(
        false, // disable synchronous completion of send. see ProducerSendCompletionThread below.
        new IntegerSerializer(),
        new LongSerializer()) {

        // override flush() so that it does not complete all the waiting sends, giving a chance to
        // ProducerCompletionThread to inject errors.

        @Override
        public void flush() {
          while (completeNext()) {
            // there are some uncompleted records. let the completion thread handle them.
            try {
              Thread.sleep(10);
            } catch (InterruptedException e) {
              // ok to retry.
            }
          }
        }
      };

      // Add the producer to the global map so that producer factory function can access it.
      assertNull(MOCK_PRODUCER_MAP.putIfAbsent(producerKey, mockProducer));
    }

    public void close() {
      MOCK_PRODUCER_MAP.remove(producerKey);
      try {
        if (closedMethod == null || !((Boolean) closedMethod.invoke(mockProducer))) {
          mockProducer.close();
        }
      } catch (Exception e) { // Not expected.
        throw new RuntimeException(e);
      }
    }
  }

  private static final ConcurrentMap<String, MockProducer<Integer, Long>> MOCK_PRODUCER_MAP =
    new ConcurrentHashMap<>();

  private static class ProducerFactoryFn
    implements SerializableFunction<Map<String, Object>, Producer<Integer, Long>> {
    final String producerKey;

    ProducerFactoryFn(String producerKey) {
      this.producerKey = producerKey;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Producer<Integer, Long> apply(Map<String, Object> config) {

      // Make sure the config is correctly set up for serializers.
      Utils.newInstance(
        (Class<? extends Serializer<?>>)
          ((Class<?>) config.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG))
                .asSubclass(Serializer.class)
      ).configure(config, true);

      Utils.newInstance(
        (Class<? extends Serializer<?>>)
          ((Class<?>) config.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG))
              .asSubclass(Serializer.class)
      ).configure(config, false);

      // Returning same producer in each instance in a pipeline seems to work fine currently.
      // If DirectRunner creates multiple DoFn instances for sinks, we might need to handle
      // it appropriately. I.e. allow multiple producers for each producerKey and concatenate
      // all the messages written to each producer for verification after the pipeline finishes.

      return MOCK_PRODUCER_MAP.get(producerKey);
    }
  }

  private static class InjectedErrorException extends RuntimeException {
    InjectedErrorException(String message) {
      super(message);
    }
  }

  /**
   * We start MockProducer with auto-completion disabled. That implies a record is not marked sent
   * until #completeNext() is called on it. This class starts a thread to asynchronously 'complete'
   * the the sends. During completion, we can also make those requests fail. This error injection
   * is used in one of the tests.
   */
  private static class ProducerSendCompletionThread {

    private final MockProducer<Integer, Long> mockProducer;
    private final int maxErrors;
    private final int errorFrequency;
    private final AtomicBoolean done = new AtomicBoolean(false);
    private final ExecutorService injectorThread;
    private int numCompletions = 0;

    ProducerSendCompletionThread(MockProducer<Integer, Long> mockProducer) {
      // complete everything successfully
      this(mockProducer, 0, 0);
    }

    ProducerSendCompletionThread(MockProducer<Integer, Long> mockProducer,
                                 int maxErrors,
                                 int errorFrequency) {
      this.mockProducer = mockProducer;
      this.maxErrors = maxErrors;
      this.errorFrequency = errorFrequency;
      injectorThread = Executors.newSingleThreadExecutor();
    }

    ProducerSendCompletionThread start() {
      injectorThread.submit(
          () -> {
            int errorsInjected = 0;

            while (!done.get()) {
              boolean successful;

              if (errorsInjected < maxErrors && ((numCompletions + 1) % errorFrequency) == 0) {
                successful =
                    mockProducer.errorNext(
                        new InjectedErrorException("Injected Error #" + (errorsInjected + 1)));

                if (successful) {
                  errorsInjected++;
                }
              } else {
                successful = mockProducer.completeNext();
              }

              if (successful) {
                numCompletions++;
              } else {
                // wait a bit since there are no unsent records
                try {
                  Thread.sleep(1);
                } catch (InterruptedException e) {
                  // ok to retry.
                }
              }
            }
          });

      return this;
    }

    void shutdown() {
      done.set(true);
      injectorThread.shutdown();
      try {
        assertTrue(injectorThread.awaitTermination(10, TimeUnit.SECONDS));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
  }
}
