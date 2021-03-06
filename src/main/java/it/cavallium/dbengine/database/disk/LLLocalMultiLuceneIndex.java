package it.cavallium.dbengine.database.disk;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import it.cavallium.dbengine.client.IndicizerAnalyzers;
import it.cavallium.dbengine.client.IndicizerSimilarities;
import it.cavallium.dbengine.client.LuceneIndex;
import it.cavallium.dbengine.client.LuceneOptions;
import it.cavallium.dbengine.client.query.current.data.QueryParams;
import it.cavallium.dbengine.database.LLDocument;
import it.cavallium.dbengine.database.LLLuceneIndex;
import it.cavallium.dbengine.database.LLSearchResult;
import it.cavallium.dbengine.database.LLSearchResultShard;
import it.cavallium.dbengine.database.LLSnapshot;
import it.cavallium.dbengine.database.LLTerm;
import it.cavallium.dbengine.lucene.LuceneUtils;
import it.cavallium.dbengine.lucene.analyzer.TextFieldsAnalyzer;
import it.cavallium.dbengine.lucene.analyzer.TextFieldsSimilarity;
import it.cavallium.dbengine.lucene.searcher.AdaptiveLuceneMultiSearcher;
import it.cavallium.dbengine.lucene.searcher.LocalQueryParams;
import it.cavallium.dbengine.lucene.searcher.LuceneMultiSearcher;
import it.cavallium.dbengine.lucene.searcher.LuceneShardSearcher;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.lucene.search.CollectionStatistics;
import org.apache.lucene.search.IndexSearcher;
import org.jetbrains.annotations.Nullable;
import org.warp.commonutils.batch.ParallelUtils;
import org.warp.commonutils.functional.IOBiConsumer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.GroupedFlux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

public class LLLocalMultiLuceneIndex implements LLLuceneIndex {

	private final ConcurrentHashMap<Long, LLSnapshot[]> registeredSnapshots = new ConcurrentHashMap<>();
	private final AtomicLong nextSnapshotNumber = new AtomicLong(1);
	private final LLLocalLuceneIndex[] luceneIndices;


	private final LuceneMultiSearcher multiSearcher = new AdaptiveLuceneMultiSearcher();

	public LLLocalMultiLuceneIndex(Path lucene,
			String name,
			int instancesCount,
			IndicizerAnalyzers indicizerAnalyzers,
			IndicizerSimilarities indicizerSimilarities,
			LuceneOptions luceneOptions) throws IOException {

		if (instancesCount <= 1 || instancesCount > 100) {
			throw new IOException("Unsupported instances count: " + instancesCount);
		}

		LLLocalLuceneIndex[] luceneIndices = new LLLocalLuceneIndex[instancesCount];
		for (int i = 0; i < instancesCount; i++) {
			String instanceName;
			if (i == 0) {
				instanceName = name;
			} else {
				instanceName = name + "_" + String.format("%03d", i);
			}
			luceneIndices[i] = new LLLocalLuceneIndex(lucene,
					instanceName,
					indicizerAnalyzers,
					indicizerSimilarities,
					luceneOptions
			);
		}
		this.luceneIndices = luceneIndices;
	}

	private LLLocalLuceneIndex getLuceneIndex(LLTerm id) {
		return luceneIndices[getLuceneIndexId(id)];
	}

	private int getLuceneIndexId(LLTerm id) {
		return Math.abs(id.getValue().hashCode()) % luceneIndices.length;
	}

	@Override
	public String getLuceneIndexName() {
		return luceneIndices[0].getLuceneIndexName();
	}

	@Override
	public Mono<Void> addDocument(LLTerm id, LLDocument doc) {
		return getLuceneIndex(id).addDocument(id, doc);
	}

	@SuppressWarnings({"unchecked"})
	@Override
	public Mono<Void> addDocuments(Flux<Entry<LLTerm, LLDocument>> documents) {
		return documents
				.bufferTimeout(512, Duration.ofSeconds(2))
				.flatMap(inputEntries -> {
					List<Entry<LLTerm, LLDocument>>[] sortedEntries = new List[luceneIndices.length];
					Mono<Void>[] results = new Mono[luceneIndices.length];

					// Sort entries
					for(var inputEntry : inputEntries) {
						int luceneIndexId = getLuceneIndexId(inputEntry.getKey());
						if (sortedEntries[luceneIndexId] == null) {
							sortedEntries[luceneIndexId] = new ArrayList<>();
						}
						sortedEntries[luceneIndexId].add(inputEntry);
					}

					// Add documents
					int luceneIndexId = 0;
					for (List<Entry<LLTerm, LLDocument>> docs : sortedEntries) {
						if (docs != null && !docs.isEmpty()) {
							LLLocalLuceneIndex luceneIndex = luceneIndices[luceneIndexId];
							results[luceneIndexId] = luceneIndex.addDocuments(Flux.fromIterable(docs));
						} else {
							results[luceneIndexId] = Mono.empty();
						}
						luceneIndexId++;
					}

					return Mono.when(results);
				})
				.then();
	}

	@Override
	public Mono<Void> deleteDocument(LLTerm id) {
		return getLuceneIndex(id).deleteDocument(id);
	}

	@Override
	public Mono<Void> updateDocument(LLTerm id, LLDocument document) {
		return getLuceneIndex(id).updateDocument(id, document);
	}

	@Override
	public Mono<Void> updateDocuments(Mono<Map<LLTerm, LLDocument>> documents) {
		return documents
				.flatMapMany(map -> {
					var sortedMap = new HashMap<LLLocalLuceneIndex, Map<LLTerm, LLDocument>>();
					map.forEach((key, value) -> sortedMap
							.computeIfAbsent(getLuceneIndex(key), _unused -> new HashMap<>())
							.put(key, value)
					);
					return Flux.fromIterable(Collections.unmodifiableMap(sortedMap).entrySet());
				})
				.flatMap(luceneIndexWithNewDocuments -> {
					var luceneIndex = luceneIndexWithNewDocuments.getKey();
					var docs = luceneIndexWithNewDocuments.getValue();
					return luceneIndex.updateDocuments(Mono.just(docs));
				})
				.then();
	}

	@Override
	public Mono<Void> deleteAll() {
		return Flux
				.fromArray(luceneIndices)
				.flatMap(LLLocalLuceneIndex::deleteAll)
				.then();
	}

	private LLSnapshot resolveSnapshot(LLSnapshot multiSnapshot, int instanceId) {
		if (multiSnapshot != null) {
			return registeredSnapshots.get(multiSnapshot.getSequenceNumber())[instanceId];
		} else {
			return null;
		}
	}

	private Optional<LLSnapshot> resolveSnapshotOptional(LLSnapshot multiSnapshot, int instanceId) {
		return Optional.ofNullable(resolveSnapshot(multiSnapshot, instanceId));
	}

	@Override
	public Mono<LLSearchResultShard> moreLikeThis(@Nullable LLSnapshot snapshot,
			QueryParams queryParams,
			String keyFieldName,
			Flux<Tuple2<String, Set<String>>> mltDocumentFields) {
		LocalQueryParams localQueryParams = LuceneUtils.toLocalQueryParams(queryParams);
		record LuceneIndexWithSnapshot(LLLocalLuceneIndex luceneIndex, Optional<LLSnapshot> snapshot) {}

		return multiSearcher
				// Create shard searcher
				.createShardSearcher(localQueryParams)
				.flatMap(shardSearcher -> Flux
						// Iterate the indexed shards
						.fromArray(luceneIndices).index()
						// Resolve the snapshot of each shard
						.flatMap(tuple -> Mono
								.fromCallable(() -> resolveSnapshotOptional(snapshot, (int) (long) tuple.getT1()))
								.map(luceneSnapshot -> new LuceneIndexWithSnapshot(tuple.getT2(), luceneSnapshot))
						)
						// Execute the query and collect it using the shard searcher
						.flatMap(luceneIndexWithSnapshot -> luceneIndexWithSnapshot.luceneIndex()
								.distributedMoreLikeThis(luceneIndexWithSnapshot.snapshot.orElse(null), queryParams, mltDocumentFields, shardSearcher))
						// Collect all the shards results into a single global result
						.then(shardSearcher.collect(localQueryParams, keyFieldName, Schedulers.boundedElastic()))
				)
				// Fix the result type
				.map(result -> new LLSearchResultShard(result.results(), result.totalHitsCount(), result.release()));
	}

	@Override
	public Mono<LLSearchResultShard> search(@Nullable LLSnapshot snapshot,
			QueryParams queryParams,
			String keyFieldName) {
		LocalQueryParams localQueryParams = LuceneUtils.toLocalQueryParams(queryParams);
		record LuceneIndexWithSnapshot(LLLocalLuceneIndex luceneIndex, Optional<LLSnapshot> snapshot) {}

		return multiSearcher
				// Create shard searcher
				.createShardSearcher(localQueryParams)
				.flatMap(shardSearcher -> Flux
						// Iterate the indexed shards
						.fromArray(luceneIndices).index()
						// Resolve the snapshot of each shard
						.flatMap(tuple -> Mono
								.fromCallable(() -> resolveSnapshotOptional(snapshot, (int) (long) tuple.getT1()))
								.map(luceneSnapshot -> new LuceneIndexWithSnapshot(tuple.getT2(), luceneSnapshot))
						)
						// Execute the query and collect it using the shard searcher
						.flatMap(luceneIndexWithSnapshot -> luceneIndexWithSnapshot.luceneIndex()
								.distributedSearch(luceneIndexWithSnapshot.snapshot.orElse(null), queryParams, shardSearcher))
						// Collect all the shards results into a single global result
						.then(shardSearcher.collect(localQueryParams, keyFieldName, Schedulers.boundedElastic()))
				)
				// Fix the result type
				.map(result -> new LLSearchResultShard(result.results(), result.totalHitsCount(), result.release()));
	}

	@Override
	public Mono<Void> close() {
		return Flux
				.fromArray(luceneIndices)
				.flatMap(LLLocalLuceneIndex::close)
				.then();
	}

	@Override
	public Mono<Void> flush() {
		return Flux
				.fromArray(luceneIndices)
				.flatMap(LLLocalLuceneIndex::flush)
				.then();
	}

	@Override
	public Mono<Void> refresh(boolean force) {
		return Flux
				.fromArray(luceneIndices)
				.flatMap(index -> index.refresh(force))
				.then();
	}

	@Override
	public Mono<LLSnapshot> takeSnapshot() {
		return Mono
				// Generate next snapshot index
				.fromCallable(nextSnapshotNumber::getAndIncrement)
				.flatMap(snapshotIndex -> Flux
						.fromArray(luceneIndices)
						.flatMapSequential(LLLocalLuceneIndex::takeSnapshot)
						.collectList()
						.map(list -> list.toArray(LLSnapshot[]::new))
						.doOnNext(instancesSnapshotsArray -> registeredSnapshots.put(snapshotIndex, instancesSnapshotsArray))
						.thenReturn(new LLSnapshot(snapshotIndex))
				);
	}

	@Override
	public Mono<Void> releaseSnapshot(LLSnapshot snapshot) {
		return Mono
				.fromCallable(() -> registeredSnapshots.remove(snapshot.getSequenceNumber()))
				.flatMapMany(Flux::fromArray)
				.index()
				.flatMapSequential(tuple -> {
					int index = (int) (long) tuple.getT1();
					LLSnapshot instanceSnapshot = tuple.getT2();
					return luceneIndices[index].releaseSnapshot(instanceSnapshot);
				})
				.then();
	}

	@Override
	public boolean isLowMemoryMode() {
		return luceneIndices[0].isLowMemoryMode();
	}
}
