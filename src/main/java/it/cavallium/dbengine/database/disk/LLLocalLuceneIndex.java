package it.cavallium.dbengine.database.disk;

import it.cavallium.dbengine.client.query.QueryParser;
import it.cavallium.dbengine.client.query.current.data.QueryParams;
import it.cavallium.dbengine.database.EnglishItalianStopFilter;
import it.cavallium.dbengine.database.LLDocument;
import it.cavallium.dbengine.database.LLKeyScore;
import it.cavallium.dbengine.database.LLLuceneIndex;
import it.cavallium.dbengine.database.LLSearchCollectionStatisticsGetter;
import it.cavallium.dbengine.database.LLSearchResult;
import it.cavallium.dbengine.database.LLSearchResultShard;
import it.cavallium.dbengine.database.LLSnapshot;
import it.cavallium.dbengine.database.LLTerm;
import it.cavallium.dbengine.database.LLUtils;
import it.cavallium.dbengine.lucene.LuceneUtils;
import it.cavallium.dbengine.lucene.ScheduledTaskLifecycle;
import it.cavallium.dbengine.lucene.analyzer.TextFieldsAnalyzer;
import it.cavallium.dbengine.lucene.analyzer.TextFieldsSimilarity;
import it.cavallium.dbengine.lucene.searcher.AdaptiveStreamSearcher;
import it.cavallium.dbengine.lucene.searcher.AllowOnlyQueryParsingCollectorStreamSearcher;
import it.cavallium.dbengine.lucene.searcher.LuceneSearchInstance;
import it.cavallium.dbengine.lucene.searcher.LuceneStreamSearcher;
import it.cavallium.dbengine.lucene.searcher.LuceneStreamSearcher.HandleResult;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.KeepOnlyLastCommitDeletionPolicy;
import org.apache.lucene.index.SnapshotDeletionPolicy;
import org.apache.lucene.queries.mlt.MoreLikeThis;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.search.similarities.TFIDFSimilarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.RAMDirectory;
import org.jetbrains.annotations.Nullable;
import org.warp.commonutils.log.Logger;
import org.warp.commonutils.log.LoggerFactory;
import org.warp.commonutils.type.ShortNamedThreadFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink.OverflowStrategy;
import reactor.core.publisher.GroupedFlux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

public class LLLocalLuceneIndex implements LLLuceneIndex {

	protected static final Logger logger = LoggerFactory.getLogger(LLLocalLuceneIndex.class);
	private static final LuceneStreamSearcher streamSearcher = new AdaptiveStreamSearcher();
	private static final AllowOnlyQueryParsingCollectorStreamSearcher allowOnlyQueryParsingCollectorStreamSearcher
			= new AllowOnlyQueryParsingCollectorStreamSearcher();
	/**
	 * Global lucene index scheduler.
	 * There is only a single thread globally to not overwhelm the disk with
	 * concurrent commits or concurrent refreshes.
	 */
	private static final Scheduler luceneHeavyTasksScheduler = Schedulers.newBoundedElastic(1,
			Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
			"lucene",
			Integer.MAX_VALUE,
			true
	);
	// Scheduler used to get callback values of LuceneStreamSearcher without creating deadlocks
	private static final Scheduler luceneSearcherScheduler = Schedulers
			.fromExecutorService(Executors
					.newCachedThreadPool(new ShortNamedThreadFactory("lucene-searcher")));

	private final String luceneIndexName;
	private final SnapshotDeletionPolicy snapshotter;
	private final IndexWriter indexWriter;
	private final SearcherManager searcherManager;
	private final Directory directory;
	/**
	 * Last snapshot sequence number. 0 is not used
	 */
	private final AtomicLong lastSnapshotSeqNo = new AtomicLong(0);
	/**
	 * LLSnapshot seq no to index commit point
	 */
	private final ConcurrentHashMap<Long, LuceneIndexSnapshot> snapshots = new ConcurrentHashMap<>();
	private final boolean lowMemory;
	private final TextFieldsSimilarity similarity;

	private final ScheduledTaskLifecycle scheduledTasksLifecycle;
	private final @Nullable LLSearchCollectionStatisticsGetter distributedCollectionStatisticsGetter;

	public LLLocalLuceneIndex(Path luceneBasePath,
			String name,
			TextFieldsAnalyzer analyzer,
			TextFieldsSimilarity similarity,
			Duration queryRefreshDebounceTime,
			Duration commitDebounceTime,
			boolean lowMemory, boolean inMemory, @Nullable LLSearchCollectionStatisticsGetter distributedCollectionStatisticsGetter) throws IOException {
		if (name.length() == 0) {
			throw new IOException("Empty lucene database name");
		}
		Path directoryPath = luceneBasePath.resolve(name + ".lucene.db");
		this.directory = inMemory ? new RAMDirectory() : FSDirectory.open(directoryPath);
		this.luceneIndexName = name;
		this.snapshotter = new SnapshotDeletionPolicy(new KeepOnlyLastCommitDeletionPolicy());
		this.lowMemory = lowMemory;
		this.similarity = similarity;
		this.distributedCollectionStatisticsGetter = distributedCollectionStatisticsGetter;
		IndexWriterConfig indexWriterConfig = new IndexWriterConfig(LuceneUtils.getAnalyzer(analyzer));
		indexWriterConfig.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
		indexWriterConfig.setIndexDeletionPolicy(snapshotter);
		indexWriterConfig.setCommitOnClose(true);
		if (lowMemory) {
			indexWriterConfig.setRAMBufferSizeMB(32);
			indexWriterConfig.setRAMPerThreadHardLimitMB(32);
		} else {
			indexWriterConfig.setRAMBufferSizeMB(128);
			//indexWriterConfig.setRAMPerThreadHardLimitMB(512);
		}
		indexWriterConfig.setSimilarity(getSimilarity());
		this.indexWriter = new IndexWriter(directory, indexWriterConfig);
		this.searcherManager
				= new SearcherManager(indexWriter, false, false, null);

		// Create scheduled tasks lifecycle manager
		this.scheduledTasksLifecycle = new ScheduledTaskLifecycle();

		// Start scheduled tasks
		registerScheduledFixedTask(this::scheduledCommit, commitDebounceTime);
		registerScheduledFixedTask(this::scheduledQueryRefresh, queryRefreshDebounceTime);
	}

	private Similarity getSimilarity() {
		return LuceneUtils.getSimilarity(similarity);
	}

	private void registerScheduledFixedTask(Runnable task, Duration duration) {
		scheduledTasksLifecycle.registerScheduledTask(luceneHeavyTasksScheduler.schedulePeriodically(() -> {
			scheduledTasksLifecycle.startScheduledTask();
			try {
				task.run();
			} finally {
				scheduledTasksLifecycle.endScheduledTask();
			}
		}, duration.toMillis(), duration.toMillis(), TimeUnit.MILLISECONDS));
	}

	@Override
	public String getLuceneIndexName() {
		return luceneIndexName;
	}

	@Override
	public Mono<LLSnapshot> takeSnapshot() {
		return takeLuceneSnapshot()
						.flatMap(snapshot -> Mono
								.fromCallable(() -> {
									var snapshotSeqNo = lastSnapshotSeqNo.incrementAndGet();
									this.snapshots.put(snapshotSeqNo, new LuceneIndexSnapshot(snapshot));
									return new LLSnapshot(snapshotSeqNo);
								})
								.subscribeOn(Schedulers.boundedElastic())
						);
	}

	/**
	 * Use internally. This method commits before taking the snapshot if there are no commits in a new database,
	 * avoiding the exception.
	 */
	private Mono<IndexCommit> takeLuceneSnapshot() {
		return Mono
				.fromCallable(snapshotter::snapshot)
				.subscribeOn(Schedulers.boundedElastic())
				.onErrorResume(ex -> Mono
						.defer(() -> {
							if (ex instanceof IllegalStateException && "No index commit to snapshot".equals(ex.getMessage())) {
								return Mono.fromCallable(() -> {
									//noinspection BlockingMethodInNonBlockingContext
									indexWriter.commit();
									//noinspection BlockingMethodInNonBlockingContext
									return snapshotter.snapshot();
								}).subscribeOn(luceneHeavyTasksScheduler);
							} else {
								return Mono.error(ex);
							}
						})
				);
	}

	@Override
	public Mono<Void> releaseSnapshot(LLSnapshot snapshot) {
		return Mono.<Void>fromCallable(() -> {
			var indexSnapshot = this.snapshots.remove(snapshot.getSequenceNumber());
			if (indexSnapshot == null) {
				throw new IOException("LLSnapshot " + snapshot.getSequenceNumber() + " not found!");
			}

			indexSnapshot.close();

			var luceneIndexSnapshot = indexSnapshot.getSnapshot();
			snapshotter.release(luceneIndexSnapshot);
			// Delete unused files after releasing the snapshot
			indexWriter.deleteUnusedFiles();
			return null;
		}).subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public Mono<Void> addDocument(LLTerm key, LLDocument doc) {
		return Mono.<Void>fromCallable(() -> {
			indexWriter.addDocument(LLUtils.toDocument(doc));
			return null;
		}).subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public Mono<Void> addDocuments(Flux<GroupedFlux<LLTerm, LLDocument>> documents) {
		return documents
				.flatMap(group -> group
						.collectList()
						.flatMap(docs -> Mono
								.<Void>fromCallable(() -> {
									indexWriter.addDocuments(LLUtils.toDocuments(docs));
									return null;
								})
								.subscribeOn(Schedulers.boundedElastic()))
				)
				.then();
	}


	@Override
	public Mono<Void> deleteDocument(LLTerm id) {
		return Mono.<Void>fromCallable(() -> {
			indexWriter.deleteDocuments(LLUtils.toTerm(id));
			return null;
		}).subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public Mono<Void> updateDocument(LLTerm id, LLDocument document) {
		return Mono.<Void>fromCallable(() -> {
			indexWriter.updateDocument(LLUtils.toTerm(id), LLUtils.toDocument(document));
			return null;
		}).subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public Mono<Void> updateDocuments(Flux<GroupedFlux<LLTerm, LLDocument>> documents) {
		return documents.flatMap(this::updateDocuments).then();
	}

	private Mono<Void> updateDocuments(GroupedFlux<LLTerm, LLDocument> documents) {
		return documents
				.map(LLUtils::toDocument)
				.collectList()
				.flatMap(luceneDocuments -> Mono
						.<Void>fromCallable(() -> {
							indexWriter.updateDocuments(LLUtils.toTerm(documents.key()), luceneDocuments);
							return null;
						})
						.subscribeOn(Schedulers.boundedElastic())
				);
	}

	@Override
	public Mono<Void> deleteAll() {
		return Mono.<Void>fromCallable(() -> {
			//noinspection BlockingMethodInNonBlockingContext
			indexWriter.deleteAll();
			//noinspection BlockingMethodInNonBlockingContext
			indexWriter.forceMergeDeletes(true);
			//noinspection BlockingMethodInNonBlockingContext
			indexWriter.commit();
			return null;
		}).subscribeOn(luceneHeavyTasksScheduler);
	}

	private Mono<IndexSearcher> acquireSearcherWrapper(LLSnapshot snapshot, boolean distributedPre, long actionId) {
		return Mono.fromCallable(() -> {
			IndexSearcher indexSearcher;
			if (snapshot == null) {
				indexSearcher = searcherManager.acquire();
				indexSearcher.setSimilarity(getSimilarity());
			} else {
				indexSearcher = resolveSnapshot(snapshot).getIndexSearcher();
			}
			if (distributedCollectionStatisticsGetter != null && actionId != -1) {
				return new LLIndexSearcherWithCustomCollectionStatistics(indexSearcher,
						distributedCollectionStatisticsGetter,
						distributedPre,
						actionId
				);
			} else {
				return indexSearcher;
			}
		}).subscribeOn(Schedulers.boundedElastic());
	}

	private Mono<Void> releaseSearcherWrapper(LLSnapshot snapshot, IndexSearcher indexSearcher) {
		return Mono.<Void>fromRunnable(() -> {
			if (snapshot == null) {
				try {
					searcherManager.release(indexSearcher);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}).subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public Mono<LLSearchResult> moreLikeThis(@Nullable LLSnapshot snapshot,
			QueryParams queryParams,
			String keyFieldName,
			Flux<Tuple2<String, Set<String>>> mltDocumentFieldsFlux) {
		return moreLikeThis(snapshot,
				queryParams,
				keyFieldName,
				mltDocumentFieldsFlux,
				false,
				0,
				1
		);
	}

	public Mono<LLSearchResult> distributedMoreLikeThis(@Nullable LLSnapshot snapshot,
			QueryParams queryParams,
			String keyFieldName,
			Flux<Tuple2<String, Set<String>>> mltDocumentFieldsFlux,
			long actionId,
			int scoreDivisor) {
		return moreLikeThis(snapshot,
				queryParams,
				keyFieldName,
				mltDocumentFieldsFlux,
				false,
				actionId,
				scoreDivisor
		);
	}

	public Mono<Void> distributedPreMoreLikeThis(@Nullable LLSnapshot snapshot,
			QueryParams queryParams,
			String keyFieldName,
			Flux<Tuple2<String, Set<String>>> mltDocumentFieldsFlux,
			 long actionId) {
		return moreLikeThis(snapshot, queryParams, keyFieldName, mltDocumentFieldsFlux, true, actionId, 1).then();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private Mono<LLSearchResult> moreLikeThis(@Nullable LLSnapshot snapshot,
			QueryParams queryParams,
			String keyFieldName,
			Flux<Tuple2<String, Set<String>>> mltDocumentFieldsFlux,
			boolean doDistributedPre,
			long actionId,
			int scoreDivisor) {
		Query luceneAdditionalQuery;
		try {
			luceneAdditionalQuery = QueryParser.toQuery(queryParams.getQuery());
		} catch (Exception e) {
			return Mono.error(e);
		}
		return mltDocumentFieldsFlux
				.collectMap(Tuple2::getT1, Tuple2::getT2, HashMap::new)
				.flatMap(mltDocumentFields -> {
					mltDocumentFields.entrySet().removeIf(entry -> entry.getValue().isEmpty());
					if (mltDocumentFields.isEmpty()) {
						return Mono.just(new LLSearchResult(Flux.empty()));
					}

					return acquireSearcherWrapper(snapshot, doDistributedPre, actionId)
							.flatMap(indexSearcher -> Mono
									.fromCallable(() -> {
										var mlt = new MoreLikeThis(indexSearcher.getIndexReader());
										mlt.setAnalyzer(indexWriter.getAnalyzer());
										mlt.setFieldNames(mltDocumentFields.keySet().toArray(String[]::new));
										mlt.setMinTermFreq(1);
										mlt.setMinDocFreq(3);
										mlt.setMaxDocFreqPct(20);
										mlt.setBoost(QueryParser.isScoringEnabled(queryParams));
										mlt.setStopWords(EnglishItalianStopFilter.getStopWordsString());
										var similarity = getSimilarity();
										if (similarity instanceof TFIDFSimilarity) {
											mlt.setSimilarity((TFIDFSimilarity) similarity);
										} else {
											logger.trace("Using an unsupported similarity algorithm for MoreLikeThis:"
													+ " {}. You must use a similarity instance based on TFIDFSimilarity!", similarity);
										}

										// Get the reference doc and apply it to MoreLikeThis, to generate the query
										var mltQuery = mlt.like((Map) mltDocumentFields);
										Query luceneQuery;
										if (luceneAdditionalQuery != null) {
											luceneQuery = new BooleanQuery.Builder()
													.add(mltQuery, Occur.MUST)
													.add(new ConstantScoreQuery(luceneAdditionalQuery), Occur.MUST)
													.build();
										} else {
											luceneQuery = mltQuery;
										}

										return luceneQuery;
									})
									.subscribeOn(Schedulers.boundedElastic())
									.map(luceneQuery -> luceneSearch(doDistributedPre,
											indexSearcher,
											queryParams.getOffset(),
											queryParams.getLimit(),
											queryParams.getMinCompetitiveScore().getNullable(),
											keyFieldName,
											scoreDivisor,
											luceneQuery,
											QueryParser.toSort(queryParams.getSort()),
											QueryParser.toScoreMode(queryParams.getScoreMode()),
											releaseSearcherWrapper(snapshot, indexSearcher)
									))
									.onErrorResume(ex -> releaseSearcherWrapper(snapshot, indexSearcher).then(Mono.error(ex)))
							);
				});
	}

	private LLKeyScore fixKeyScore(LLKeyScore keyScore, int scoreDivisor) {
		return scoreDivisor == 1 ? keyScore : new LLKeyScore(keyScore.getKey(), keyScore.getScore() / (float) scoreDivisor);
	}

	@Override
	public Mono<LLSearchResult> search(@Nullable LLSnapshot snapshot, QueryParams queryParams, String keyFieldName) {
		return search(snapshot, queryParams, keyFieldName, false, 0, 1);
	}

	public Mono<LLSearchResult> distributedSearch(@Nullable LLSnapshot snapshot,
			QueryParams queryParams,
			String keyFieldName,
			long actionId,
			int scoreDivisor) {
		return search(snapshot, queryParams, keyFieldName, false, actionId, scoreDivisor);
	}

	public Mono<Void> distributedPreSearch(@Nullable LLSnapshot snapshot,
			QueryParams queryParams,
			String keyFieldName,
			long actionId) {
		return this
				.search(snapshot, queryParams, keyFieldName, true, actionId, 1)
				.then();
	}

	private Mono<LLSearchResult> search(@Nullable LLSnapshot snapshot,
			QueryParams queryParams, String keyFieldName,
			boolean doDistributedPre, long actionId, int scoreDivisor) {
		return this
				.acquireSearcherWrapper(snapshot, doDistributedPre, actionId)
				.flatMap(indexSearcher -> Mono
						.fromCallable(() -> {
							Objects.requireNonNull(queryParams.getScoreMode(), "ScoreMode must not be null");
							Query luceneQuery = QueryParser.toQuery(queryParams.getQuery());
							Sort luceneSort = QueryParser.toSort(queryParams.getSort());
							org.apache.lucene.search.ScoreMode luceneScoreMode = QueryParser.toScoreMode(queryParams.getScoreMode());
							return Tuples.of(luceneQuery, Optional.ofNullable(luceneSort), luceneScoreMode);
						})
						.subscribeOn(Schedulers.boundedElastic())
						.<LLSearchResult>flatMap(tuple -> Mono
								.fromSupplier(() -> {
									Query luceneQuery = tuple.getT1();
									Sort luceneSort = tuple.getT2().orElse(null);
									ScoreMode luceneScoreMode = tuple.getT3();

									return luceneSearch(doDistributedPre,
											indexSearcher,
											queryParams.getOffset(),
											queryParams.getLimit(),
											queryParams.getMinCompetitiveScore().getNullable(),
											keyFieldName,
											scoreDivisor,
											luceneQuery,
											luceneSort,
											luceneScoreMode,
											releaseSearcherWrapper(snapshot, indexSearcher)
									);
								})
								.onErrorResume(ex -> releaseSearcherWrapper(snapshot, indexSearcher).then(Mono.error(ex)))
						)
				);
	}

	/**
	 * This method always returns 1 shard! Not zero, not more than one.
	 */
	private LLSearchResult luceneSearch(boolean doDistributedPre,
			IndexSearcher indexSearcher,
			long offset,
			long limit,
			@Nullable Float minCompetitiveScore,
			String keyFieldName,
			int scoreDivisor,
			Query luceneQuery,
			Sort luceneSort,
			ScoreMode luceneScoreMode,
			Mono<Void> successCleanup) {
		return new LLSearchResult(Mono.<LLSearchResultShard>create(monoSink -> {
				LuceneSearchInstance luceneSearchInstance;
				long totalHitsCount;
				try {
					if (doDistributedPre) {
						allowOnlyQueryParsingCollectorStreamSearcher.search(indexSearcher, luceneQuery);
						monoSink.success(new LLSearchResultShard(successCleanup.thenMany(Flux.empty()), 0));
						return;
					} else {
						int boundedOffset = Math.max(0, offset > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) offset);
						int boundedLimit = Math.max(0, limit > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) limit);
						luceneSearchInstance = streamSearcher.search(indexSearcher,
								luceneQuery,
								boundedOffset,
								boundedLimit,
								luceneSort,
								luceneScoreMode,
								minCompetitiveScore,
								keyFieldName
						);
						totalHitsCount = luceneSearchInstance.getTotalHitsCount();
					}
				} catch (Exception ex) {
					monoSink.error(ex);
					return;
				}

				AtomicBoolean alreadySubscribed = new AtomicBoolean(false);
				var resultsFlux = Flux.<LLKeyScore>create(sink -> {

					if (!alreadySubscribed.compareAndSet(false, true)) {
						sink.error(new IllegalStateException("Already subscribed to results"));
						return;
					}

					AtomicBoolean cancelled = new AtomicBoolean();
					Semaphore requests = new Semaphore(0);
					sink.onDispose(() -> cancelled.set(true));
					sink.onCancel(() -> cancelled.set(true));
					sink.onRequest(delta -> requests.release((int) Math.min(delta, Integer.MAX_VALUE)));

					luceneSearcherScheduler
							.schedule(() -> {
								try {
									luceneSearchInstance.getResults(keyScore -> {
										try {
											if (cancelled.get()) {
												return HandleResult.HALT;
											}
											while (!requests.tryAcquire(500, TimeUnit.MILLISECONDS)) {
												if (cancelled.get()) {
													return HandleResult.HALT;
												}
											}
											sink.next(fixKeyScore(keyScore, scoreDivisor));
											if (cancelled.get()) {
												return HandleResult.HALT;
											} else {
												return HandleResult.CONTINUE;
											}
										} catch (Exception ex) {
											sink.error(ex);
											cancelled.set(true);
											requests.release(Integer.MAX_VALUE);
											return HandleResult.HALT;
										}
									});
									sink.complete();
								} catch (Exception ex) {
									sink.error(ex);
								}
							});

				}, OverflowStrategy.ERROR).subscribeOn(Schedulers.boundedElastic());

				monoSink.success(new LLSearchResultShard(Flux
						.usingWhen(
								Mono.just(true),
								b -> resultsFlux,
								b -> successCleanup),
						totalHitsCount));
		}).subscribeOn(Schedulers.boundedElastic()).flux());
	}

	@Override
	public Mono<Void> close() {
		return Mono
				.<Void>fromCallable(() -> {
					logger.debug("Closing IndexWriter...");
					scheduledTasksLifecycle.cancelAndWait();
					//noinspection BlockingMethodInNonBlockingContext
					indexWriter.close();
					//noinspection BlockingMethodInNonBlockingContext
					directory.close();
					logger.debug("IndexWriter closed");
					return null;
				})
				.subscribeOn(luceneHeavyTasksScheduler);
	}

	@Override
	public Mono<Void> flush() {
		return Mono
				.<Void>fromCallable(() -> {
					scheduledTasksLifecycle.startScheduledTask();
					try {
						//noinspection BlockingMethodInNonBlockingContext
						indexWriter.commit();
					} finally {
						scheduledTasksLifecycle.endScheduledTask();
					}
					return null;
				})
				.subscribeOn(luceneHeavyTasksScheduler);
	}

	@Override
	public Mono<Void> refresh() {
		return Mono
				.<Void>fromCallable(() -> {
					scheduledTasksLifecycle.startScheduledTask();
					try {
						//noinspection BlockingMethodInNonBlockingContext
						searcherManager.maybeRefreshBlocking();
					} finally {
						scheduledTasksLifecycle.endScheduledTask();
					}
					return null;
				})
				.subscribeOn(luceneHeavyTasksScheduler);
	}

	private void scheduledCommit() {
		try {
			if (indexWriter.hasUncommittedChanges()) {
				indexWriter.commit();
			}
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}

	@SuppressWarnings("unused")
	private void scheduledQueryRefresh() {
		try {
			boolean refreshStarted = searcherManager.maybeRefresh();
			// if refreshStarted == false, another thread is currently already refreshing
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}

	private LuceneIndexSnapshot resolveSnapshot(@Nullable LLSnapshot snapshot) {
		if (snapshot == null) {
			return null;
		}
		return Objects.requireNonNull(snapshots.get(snapshot.getSequenceNumber()),
				() -> "Can't resolve snapshot " + snapshot.getSequenceNumber()
		);
	}

	@Override
	public boolean isLowMemoryMode() {
		return lowMemory;
	}

	@Override
	public boolean supportsOffset() {
		return true;
	}
}
