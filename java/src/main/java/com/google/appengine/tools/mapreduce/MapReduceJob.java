// Copyright 2011 Google Inc. All Rights Reserved.

package com.google.appengine.tools.mapreduce;



import static com.google.common.base.Preconditions.checkNotNull;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.mapreduce.impl.BaseContext;
import com.google.appengine.tools.mapreduce.impl.CountersImpl;
import com.google.appengine.tools.mapreduce.impl.FilesByShard;
import com.google.appengine.tools.mapreduce.impl.GoogleCloudStorageMapOutput;
import com.google.appengine.tools.mapreduce.impl.GoogleCloudStorageMergeInput;
import com.google.appengine.tools.mapreduce.impl.GoogleCloudStorageMergeOutput;
import com.google.appengine.tools.mapreduce.impl.GoogleCloudStorageReduceInput;
import com.google.appengine.tools.mapreduce.impl.GoogleCloudStorageSortInput;
import com.google.appengine.tools.mapreduce.impl.GoogleCloudStorageSortOutput;
import com.google.appengine.tools.mapreduce.impl.HashingSharder;
import com.google.appengine.tools.mapreduce.impl.MapShardTask;
import com.google.appengine.tools.mapreduce.impl.ReduceShardTask;
import com.google.appengine.tools.mapreduce.impl.WorkerController;
import com.google.appengine.tools.mapreduce.impl.WorkerShardTask;
import com.google.appengine.tools.mapreduce.impl.pipeline.CleanupPipelineJob;
import com.google.appengine.tools.mapreduce.impl.pipeline.ExamineStatusAndReturnResult;
import com.google.appengine.tools.mapreduce.impl.pipeline.ResultAndStatus;
import com.google.appengine.tools.mapreduce.impl.pipeline.ShardedJob;
import com.google.appengine.tools.mapreduce.impl.shardedjob.ShardedJobServiceFactory;
import com.google.appengine.tools.mapreduce.impl.shardedjob.ShardedJobSettings;
import com.google.appengine.tools.mapreduce.impl.sort.MergeContext;
import com.google.appengine.tools.mapreduce.impl.sort.MergeShardTask;
import com.google.appengine.tools.mapreduce.impl.sort.SortContext;
import com.google.appengine.tools.mapreduce.impl.sort.SortShardTask;
import com.google.appengine.tools.mapreduce.impl.sort.SortWorker;
import com.google.appengine.tools.pipeline.FutureValue;
import com.google.appengine.tools.pipeline.Job0;
import com.google.appengine.tools.pipeline.Job1;
import com.google.appengine.tools.pipeline.PipelineService;
import com.google.appengine.tools.pipeline.PipelineServiceFactory;
import com.google.appengine.tools.pipeline.PromisedValue;
import com.google.appengine.tools.pipeline.Value;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * A Pipeline job that runs a MapReduce.
 *
 * @author ohler@google.com (Christian Ohler)
 *
 * @param <I> type of input values
 * @param <K> type of intermediate keys
 * @param <V> type of intermediate values
 * @param <O> type of output values
 * @param <R> type of final result
 */
public class MapReduceJob<I, K, V, O, R> extends Job0<MapReduceResult<R>> {

  private static final long serialVersionUID = 723635736794527552L;
  private static final Logger log = Logger.getLogger(MapReduceJob.class.getName());

  private final MapReduceSpecification<I, K, V, O, R> specification;
  private final MapReduceSettings settings;

  public MapReduceJob(MapReduceSpecification<I, K, V, O, R> specification,
      MapReduceSettings settings) {
    this.specification = specification;
    this.settings = settings;
  }

  /**
   * Starts a {@link MapReduceJob} with the given parameters in a new Pipeline.
   * Returns the pipeline id.
   */
  public static <I, K, V, O, R> String start(
      MapReduceSpecification<I, K, V, O, R> specification, MapReduceSettings settings) {
    if (settings.getWorkerQueueName() == null) {
      settings = new MapReduceSettings.Builder(settings).setWorkerQueueName("default").build();
    }
    PipelineService pipelineService = PipelineServiceFactory.newPipelineService();
    return pipelineService.startNewPipeline(
        new MapReduceJob<>(specification, settings), settings.toJobSettings());
  }

  /**
   * The pipeline job to execute the Map stage of the MapReduce. (For all shards)
   */
  private static class MapJob<I, K, V> extends Job0<MapReduceResult<FilesByShard>> {
    private static final long serialVersionUID = 274712180795282822L;

    private final String mrJobId;
    private final MapReduceSpecification<I, K, V, ?, ?> mrSpec;
    private final MapReduceSettings settings;
    private final String shardedJobId;

    private MapJob(
        String mrJobId, MapReduceSpecification<I, K, V, ?, ?> mrSpec, MapReduceSettings settings) {
      this.mrJobId = checkNotNull(mrJobId, "Null mrJobId");
      this.mrSpec = checkNotNull(mrSpec, "Null mrSpec");
      this.settings = checkNotNull(settings, "Null settings");
      shardedJobId = "map-" + mrJobId;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + "(" + mrJobId + ")";
    }

    /**
     * Starts a shardedJob for each map worker. The format of the files and output is defined by
     * {@link GoogleCloudStorageMapOutput}.
     *
     * @returns A future containing the FilesByShard for the sortJob
     */
    @Override
    public Value<MapReduceResult<FilesByShard>> run() {
      Context context = new BaseContext(mrJobId);
      Input<I> input = mrSpec.getInput();
      input.setContext(context);
      List<? extends InputReader<I>> readers;
      try {
        readers = input.createReaders();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      Output<KeyValue<K, V>, FilesByShard> output = new GoogleCloudStorageMapOutput<>(
              settings.getBucketName(),
              mrJobId,
              mrSpec.getKeyMarshaller(),
              mrSpec.getValueMarshaller(),
              new HashingSharder(getNumOutputFiles(readers.size())));
      output.setContext(context);

      List<? extends OutputWriter<KeyValue<K, V>>> writers = output.createWriters(readers.size());
      Preconditions.checkState(readers.size() == writers.size(), "%s: %s readers, %s writers",
          shardedJobId, readers.size(), writers.size());
      ImmutableList.Builder<WorkerShardTask<I, KeyValue<K, V>, MapperContext<K, V>>> mapTasks =
          ImmutableList.builder();
      for (int i = 0; i < readers.size(); i++) {
        mapTasks.add(new MapShardTask<>(mrJobId, i, readers.size(), readers.get(i),
            mrSpec.getMapper(), writers.get(i), settings.getMillisPerSlice()));
      }
      ShardedJobSettings shardedJobSettings =
          settings.toShardedJobSettings(shardedJobId, getPipelineKey());

      PromisedValue<ResultAndStatus<FilesByShard>> resultAndStatus = newPromise();
      WorkerController<I, KeyValue<K, V>, FilesByShard, MapperContext<K, V>> workerController =
          new WorkerController<>(mrJobId, new CountersImpl(), output, resultAndStatus.getHandle());
      ShardedJob<?> shardedJob =
          new ShardedJob<>(shardedJobId, mapTasks.build(), workerController, shardedJobSettings);
      FutureValue<Void> shardedJobResult = futureCall(shardedJob, settings.toJobSettings());
      return futureCall(new ExamineStatusAndReturnResult<FilesByShard>(shardedJobId),
          resultAndStatus, settings.toJobSettings(waitFor(shardedJobResult),
              statusConsoleUrl(shardedJobSettings.getMapReduceStatusUrl())));
    }

    private int getNumOutputFiles(int mapShards) {
      return Math.min(settings.getMapFanout(), Math.max(mapShards, mrSpec.getNumReducers()));
    }

    @SuppressWarnings("unused")
    public Value<MapReduceResult<FilesByShard>> handleException(
        CancellationException ex) {
      ShardedJobServiceFactory.getShardedJobService().abortJob(shardedJobId);
      return null;
    }
  }

  /**
   * The pipeline job to execute the Sort stage of the MapReduce. (For all shards)
   */
  private static class SortJob extends Job1<
      MapReduceResult<FilesByShard>,
      MapReduceResult<FilesByShard>> {
    private static final long serialVersionUID = 8761355950012542309L;
    // We don't need the CountersImpl part of the MapResult input here but we
    // accept it to avoid needing an adapter job to connect this job to MapJob's result.
    private final String mrJobId;
    private final MapReduceSpecification<?, ?, ?, ?, ?> mrSpec;
    private final MapReduceSettings settings;
    private final String shardedJobId;

    private SortJob(
        String mrJobId, MapReduceSpecification<?, ?, ?, ?, ?> mrSpec, MapReduceSettings settings) {
      this.mrJobId = checkNotNull(mrJobId, "Null mrJobId");
      this.mrSpec = checkNotNull(mrSpec, "Null mrSpec");
      this.settings = checkNotNull(settings, "Null settings");
      shardedJobId = "sort-" + mrJobId;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + "(" + mrJobId + ")";
    }

    /**
     * Takes in the the result of the map stage. (FilesByShard indexed by sortShard) These files are
     * then read, and written out in sorted order. The result is a set of files for each reducer.
     * The format for how the data is written out is defined by {@link GoogleCloudStorageSortOutput}
     */
    @Override
    public Value<MapReduceResult<FilesByShard>> run(MapReduceResult<FilesByShard> mapResult) {

      Context context = new BaseContext(mrJobId);
      int mapShards = findMaxFilesPerShard(mapResult.getOutputResult());
      int reduceShards = mrSpec.getNumReducers();
      FilesByShard filesByShard = mapResult.getOutputResult();
      filesByShard.splitShards(Math.max(mapShards, reduceShards));
      GoogleCloudStorageSortInput input = new GoogleCloudStorageSortInput(filesByShard);
      ((Input<?>) input).setContext(context);
      List<? extends InputReader<KeyValue<ByteBuffer, ByteBuffer>>> readers = input.createReaders();
      Output<KeyValue<ByteBuffer, List<ByteBuffer>>, FilesByShard> output =
          new GoogleCloudStorageSortOutput(settings.getBucketName(), mrJobId,
              new HashingSharder(reduceShards));
      output.setContext(context);

      List<? extends OutputWriter<KeyValue<ByteBuffer, List<ByteBuffer>>>> writers =
          output.createWriters(readers.size());
      Preconditions.checkState(readers.size() == writers.size(), "%s: %s readers, %s writers",
          shardedJobId, readers.size(), writers.size());
      ImmutableList.Builder<WorkerShardTask<KeyValue<ByteBuffer, ByteBuffer>,
          KeyValue<ByteBuffer, List<ByteBuffer>>, SortContext>> sortTasks =
              ImmutableList.builder();
      for (int i = 0; i < readers.size(); i++) {
        sortTasks.add(new SortShardTask(mrJobId,
            i,
            readers.size(),
            readers.get(i),
            new SortWorker(settings.getMaxSortMemory(), settings.getSortBatchPerEmitBytes()),
            writers.get(i),
            settings.getSortReadTimeMillis()));
      }
      ShardedJobSettings shardedJobSettings =
          settings.toShardedJobSettings(shardedJobId, getPipelineKey());

      PromisedValue<ResultAndStatus<FilesByShard>> resultAndStatus = newPromise();
      WorkerController<KeyValue<ByteBuffer, ByteBuffer>, KeyValue<ByteBuffer, List<ByteBuffer>>,
          FilesByShard, SortContext> workerController = new WorkerController<>(mrJobId,
          mapResult.getCounters(), output, resultAndStatus.getHandle());

      ShardedJob<?> shardedJob =
          new ShardedJob<>(shardedJobId, sortTasks.build(), workerController, shardedJobSettings);
      FutureValue<Void> shardedJobResult = futureCall(shardedJob, settings.toJobSettings());

      return futureCall(new ExamineStatusAndReturnResult<FilesByShard>(shardedJobId),
          resultAndStatus, settings.toJobSettings(waitFor(shardedJobResult),
              statusConsoleUrl(shardedJobSettings.getMapReduceStatusUrl())));
    }

    @SuppressWarnings("unused")
    public Value<FilesByShard> handleException(CancellationException ex) {
      ShardedJobServiceFactory.getShardedJobService().abortJob(shardedJobId);
      return null;
    }
  }

  /**
   * The pipeline job to execute the optional Merge stage of the MapReduce. (For all shards)
   */
  private static class MergeJob extends
      Job1<MapReduceResult<FilesByShard>, MapReduceResult<FilesByShard>> {

    private static final long serialVersionUID = 6206131608067499939L;
    // We don't need the CountersImpl part of the MapResult input here but we
    // accept it to avoid needing an adapter job to connect this job to MapJob's result.
    private final String mrJobId;
    private final MapReduceSpecification<?, ?, ?, ?, ?> mrSpec;
    private final MapReduceSettings settings;
    private final Integer tier;
    private final String shardedJobId;

    private MergeJob(String mrJobId, MapReduceSpecification<?, ?, ?, ?, ?> mrSpec,
        MapReduceSettings settings, Integer tier) {
      this.tier = checkNotNull(tier, "Null tier");
      this.mrJobId = checkNotNull(mrJobId, "Null mrJobId");
      this.mrSpec = checkNotNull(mrSpec, "Null mrSpec");
      this.settings = checkNotNull(settings, "Null settings");
      this.shardedJobId = "merge-" + mrJobId + "-" + tier;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + "(" + mrJobId + ")";
    }

    /**
     * Takes in the the result of the sort stage, the files are read and re-written, merging
     * multiple files into one in the process. In the event that multiple phases are required this
     * method will invoke itself recursively.
     */
    @Override
    public Value<MapReduceResult<FilesByShard>> run(MapReduceResult<FilesByShard> priorResult) {

      Context context = new BaseContext(mrJobId);
      FilesByShard sortFiles = priorResult.getOutputResult();
      int maxFilesPerShard = findMaxFilesPerShard(sortFiles);
      if (maxFilesPerShard <= settings.getMergeFanin()) {
        return immediate(priorResult);
      }

      GoogleCloudStorageMergeInput input =
          new GoogleCloudStorageMergeInput(sortFiles, settings.getMergeFanin());
      ((Input<?>) input).setContext(context);
      List<? extends InputReader<KeyValue<ByteBuffer, Iterator<ByteBuffer>>>> readers =
          input.createReaders();

      Output<KeyValue<ByteBuffer, List<ByteBuffer>>, FilesByShard> output =
          new GoogleCloudStorageMergeOutput(settings.getBucketName(), mrJobId, tier);
      output.setContext(context);

      List<? extends OutputWriter<KeyValue<ByteBuffer, List<ByteBuffer>>>> writers =
          output.createWriters(readers.size());
      Preconditions.checkState(readers.size() == writers.size(), "%s: %s readers, %s writers",
          shardedJobId, readers.size(), writers.size());
      ImmutableList.Builder<WorkerShardTask<KeyValue<ByteBuffer, Iterator<ByteBuffer>>,
          KeyValue<ByteBuffer, List<ByteBuffer>>, MergeContext>> mergeTasks =
          ImmutableList.builder();
      for (int i = 0; i < readers.size(); i++) {
        mergeTasks.add(new MergeShardTask(mrJobId,
            i,
            readers.size(),
            readers.get(i),
            writers.get(i),
            settings.getSortReadTimeMillis()));
      }
      ShardedJobSettings shardedJobSettings =
          settings.toShardedJobSettings(shardedJobId, getPipelineKey());

      PromisedValue<ResultAndStatus<FilesByShard>> resultAndStatus = newPromise();
      WorkerController<KeyValue<ByteBuffer, Iterator<ByteBuffer>>,
          KeyValue<ByteBuffer, List<ByteBuffer>>, FilesByShard, MergeContext> workerController =
          new WorkerController<>(mrJobId, priorResult.getCounters(), output,
              resultAndStatus.getHandle());
      ShardedJob<?> shardedJob =
          new ShardedJob<>(shardedJobId, mergeTasks.build(), workerController, shardedJobSettings);
      FutureValue<Void> shardedJobResult = futureCall(shardedJob, settings.toJobSettings());

      FutureValue<MapReduceResult<FilesByShard>> finished = futureCall(
          new ExamineStatusAndReturnResult<FilesByShard>(shardedJobId),
          resultAndStatus, settings.toJobSettings(waitFor(shardedJobResult),
              statusConsoleUrl(shardedJobSettings.getMapReduceStatusUrl())));
      futureCall(new Cleanup(settings), immediate(priorResult), waitFor(finished));
      return futureCall(new MergeJob(mrJobId, mrSpec, settings, tier + 1), finished,
          settings.toJobSettings(maxAttempts(1)));
    }

    @SuppressWarnings("unused")
    public Value<FilesByShard> handleException(CancellationException ex) {
      ShardedJobServiceFactory.getShardedJobService().abortJob(shardedJobId);
      return null;
    }
  }

  private static int findMaxFilesPerShard(FilesByShard byShard) {
    int max = 0;
    for (int shard = 0; shard < byShard.getShardCount(); shard++) {
      max = Math.max(max, byShard.getFilesForShard(shard).getNumFiles());
    }
    return max;
  }

  /**
   * The pipeline job to execute the Reduce stage of the MapReduce. (For all shards)
   */
  private static class ReduceJob<K, V, O, R> extends Job1<MapReduceResult<R>,
      MapReduceResult<FilesByShard>> {

    private static final long serialVersionUID = 590237832617368335L;

    private final String mrJobId;
    private final MapReduceSpecification<?, K, V, O, R> mrSpec;
    private final MapReduceSettings settings;
    private final String shardedJobId;

    private ReduceJob(
        String mrJobId, MapReduceSpecification<?, K, V, O, R> mrSpec, MapReduceSettings settings) {
      this.mrJobId = checkNotNull(mrJobId, "Null mrJobId");
      this.mrSpec = checkNotNull(mrSpec, "Null mrSpec");
      this.settings = checkNotNull(settings, "Null settings");
      shardedJobId = "reduce-" + mrJobId;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + "(" + mrJobId + ")";
    }

    /**
     * Takes in the output from merge, and creates a sharded task to call the reducer with the
     * ordered input.
     * The way the data is read in is defined by {@link GoogleCloudStorageReduceInput}
     */
    @Override
    public Value<MapReduceResult<R>> run(MapReduceResult<FilesByShard> mergeResult) {
      Context context = new BaseContext(mrJobId);
      Output<O, R> output = mrSpec.getOutput();
      output.setContext(context);
      GoogleCloudStorageReduceInput<K, V> input = new GoogleCloudStorageReduceInput<>(
          mergeResult.getOutputResult(), mrSpec.getKeyMarshaller(), mrSpec.getValueMarshaller());
      ((Input<?>) input).setContext(context);
      List<? extends InputReader<KeyValue<K, Iterator<V>>>> readers = input.createReaders();

      List<? extends OutputWriter<O>> writers = output.createWriters(mrSpec.getNumReducers());
      Preconditions.checkArgument(readers.size() == writers.size(), "%s: %s readers, %s writers",
          shardedJobId, readers.size(), writers.size());
      ImmutableList.Builder<WorkerShardTask<KeyValue<K, Iterator<V>>, O, ReducerContext<O>>>
          reduceTasks = ImmutableList.builder();
      for (int i = 0; i < readers.size(); i++) {
        reduceTasks.add(new ReduceShardTask<>(mrJobId, i, readers.size(), readers.get(i),
            mrSpec.getReducer(), writers.get(i), settings.getMillisPerSlice()));
      }
      ShardedJobSettings shardedJobSettings =
          settings.toShardedJobSettings(shardedJobId, getPipelineKey());
      PromisedValue<ResultAndStatus<R>> resultAndStatus = newPromise();
      WorkerController<KeyValue<K, Iterator<V>>, O, R, ReducerContext<O>> workerController =
          new WorkerController<>(mrJobId, mergeResult.getCounters(), output,
              resultAndStatus.getHandle());
      ShardedJob<?> shardedJob =
          new ShardedJob<>(shardedJobId, reduceTasks.build(), workerController, shardedJobSettings);
      FutureValue<Void> shardedJobResult = futureCall(shardedJob, settings.toJobSettings());
      return futureCall(new ExamineStatusAndReturnResult<R>(shardedJobId), resultAndStatus,
          settings.toJobSettings(waitFor(shardedJobResult),
              statusConsoleUrl(shardedJobSettings.getMapReduceStatusUrl())));
    }

    @SuppressWarnings("unused")
    public Value<MapReduceResult<R>> handleException(CancellationException ex) {
      ShardedJobServiceFactory.getShardedJobService().abortJob(shardedJobId);
      return null;
    }
  }

  private static class Cleanup extends Job1<Void, MapReduceResult<FilesByShard>> {

    private static final long serialVersionUID = 4559443543355672948L;

    private final MapReduceSettings settings;

    public Cleanup(MapReduceSettings settings) {
      this.settings = settings;
    }

    @Override
    public Value<Void> run(MapReduceResult<FilesByShard> result) {
      Set<GcsFilename> toDelete = new HashSet<>();

      FilesByShard filesByShard = result.getOutputResult();
      for (int i = 0; i < filesByShard.getShardCount(); i++) {
        toDelete.addAll(filesByShard.getFilesForShard(i).getFiles());
      }

      CleanupPipelineJob.cleanup(new ArrayList<>(toDelete), settings.toJobSettings());
      return null;
    }
  }

  @Override
  public Value<MapReduceResult<R>> run() {
    MapReduceSettings settings = this.settings;
    if (settings.getWorkerQueueName() == null) {
      String queue = getOnQueue();
      if (queue == null) {
        log.warning("workerQueueName is null and current queue is not available in the pipeline"
            + " job, using 'default'");
        queue = "default";
      }
      settings = new MapReduceSettings.Builder(settings).setWorkerQueueName(queue).build();
    }
    String mrJobId = getJobKey().getName();
    FutureValue<MapReduceResult<FilesByShard>> mapResult = futureCall(
        new MapJob<>(mrJobId, specification, settings), settings.toJobSettings(maxAttempts(1)));

    FutureValue<MapReduceResult<FilesByShard>> sortResult = futureCall(
        new SortJob(mrJobId, specification, settings), mapResult,
        settings.toJobSettings(maxAttempts(1)));
    FutureValue<MapReduceResult<FilesByShard>> mergeResult = futureCall(
        new MergeJob(mrJobId, specification, settings, 1), sortResult,
        settings.toJobSettings(maxAttempts(1)));
    FutureValue<MapReduceResult<R>> reduceResult = futureCall(
        new ReduceJob<>(mrJobId, specification, settings), mergeResult,
        settings.toJobSettings(maxAttempts(1)));
    futureCall(new Cleanup(settings), mapResult, waitFor(sortResult));
    futureCall(new Cleanup(settings), mergeResult, waitFor(reduceResult));
    return reduceResult;
  }

  public Value<MapReduceResult<R>> handleException(Throwable t) throws Throwable {
    log.log(Level.SEVERE, "MapReduce job failed because of: ", t);
    throw t;
  }

  @Override
  public String getJobDisplayName() {
    return Optional.fromNullable(specification.getJobName()).or(super.getJobDisplayName());
  }
}
