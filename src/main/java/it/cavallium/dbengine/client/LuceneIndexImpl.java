package it.cavallium.dbengine.client;

import it.cavallium.dbengine.client.query.ClientQueryParams;
import it.cavallium.dbengine.client.query.current.data.Query;
import it.cavallium.dbengine.database.LLKeyScore;
import it.cavallium.dbengine.database.LLLuceneIndex;
import it.cavallium.dbengine.database.LLSearchResultShard;
import it.cavallium.dbengine.database.LLSnapshot;
import it.cavallium.dbengine.database.LLTerm;
import it.cavallium.dbengine.database.collections.ValueGetter;
import it.cavallium.dbengine.database.collections.ValueTransformer;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

public class LuceneIndexImpl<T, U> implements LuceneIndex<T, U> {

	private final LLLuceneIndex luceneIndex;
	private final Indicizer<T,U> indicizer;

	public LuceneIndexImpl(LLLuceneIndex luceneIndex, Indicizer<T, U> indicizer) {
		this.luceneIndex = luceneIndex;
		this.indicizer = indicizer;
	}

	private LLSnapshot resolveSnapshot(CompositeSnapshot snapshot) {
		if (snapshot == null) {
			return null;
		} else {
			return snapshot.getSnapshot(luceneIndex);
		}
	}

	@Override
	public Mono<Void> addDocument(T key, U value) {
		return indicizer
				.toDocument(key, value)
				.flatMap(doc -> luceneIndex.addDocument(indicizer.toIndex(key), doc));
	}

	@Override
	public Mono<Void> addDocuments(Flux<Entry<T, U>> entries) {
		return luceneIndex
				.addDocuments(entries
						.flatMap(entry -> indicizer
								.toDocument(entry.getKey(), entry.getValue())
								.map(doc -> Map.entry(indicizer.toIndex(entry.getKey()), doc)))
				);
	}

	@Override
	public Mono<Void> deleteDocument(T key) {
		LLTerm id = indicizer.toIndex(key);
		return luceneIndex.deleteDocument(id);
	}

	@Override
	public Mono<Void> updateDocument(T key, @NotNull U value) {
		return indicizer
				.toDocument(key, value)
				.flatMap(doc -> luceneIndex.updateDocument(indicizer.toIndex(key), doc));
	}

	@Override
	public Mono<Void> updateDocuments(Flux<Entry<T, U>> entries) {
		return luceneIndex
				.updateDocuments(entries
						.flatMap(entry -> indicizer
								.toDocument(entry.getKey(), entry.getValue())
								.map(doc -> Map.entry(indicizer.toIndex(entry.getKey()), doc)))
						.collectMap(Entry::getKey, Entry::getValue)
				);
	}

	@Override
	public Mono<Void> deleteAll() {
		return luceneIndex.deleteAll();
	}

	private Mono<SearchResultKeys<T>> transformLuceneResultWithTransformer(LLSearchResultShard llSearchResult) {
		return Mono.just(new SearchResultKeys<>(llSearchResult.results()
				.map(signal -> new SearchResultKey<>(signal.key().map(indicizer::getKey), signal.score())),
				llSearchResult.totalHitsCount(),
				llSearchResult.release()
		));
	}

	private Mono<SearchResult<T, U>> transformLuceneResultWithValues(LLSearchResultShard llSearchResult,
			ValueGetter<T, U> valueGetter) {
		return Mono.fromCallable(() -> new SearchResult<>(llSearchResult.results().map(signal -> {
			var key = signal.key().map(indicizer::getKey);
			return new SearchResultItem<>(key, key.flatMap(valueGetter::get), signal.score());
		}), llSearchResult.totalHitsCount(), llSearchResult.release()));
	}

	private Mono<SearchResult<T, U>> transformLuceneResultWithTransformer(LLSearchResultShard llSearchResult,
			ValueTransformer<T, U> valueTransformer) {
		var scoresWithKeysFlux = llSearchResult
				.results()
				.flatMapSequential(signal -> signal.key().map(indicizer::getKey).map(key -> Tuples.of(signal.score(), key)));
		var resultItemsFlux = valueTransformer
				.transform(scoresWithKeysFlux)
				.map(tuple3 -> new SearchResultItem<>(Mono.just(tuple3.getT2()),
						Mono.just(tuple3.getT3()),
						tuple3.getT1()
				));
		return Mono.fromCallable(() -> new SearchResult<>(resultItemsFlux,
				llSearchResult.totalHitsCount(),
				llSearchResult.release()
		));
	}

	@Override
	public Mono<SearchResultKeys<T>> moreLikeThis(ClientQueryParams<SearchResultKey<T>> queryParams,
			T key,
			U mltDocumentValue) {
		Flux<Tuple2<String, Set<String>>> mltDocumentFields
				= indicizer.getMoreLikeThisDocumentFields(key, mltDocumentValue);
		return luceneIndex
				.moreLikeThis(resolveSnapshot(queryParams.snapshot()), queryParams.toQueryParams(), indicizer.getKeyFieldName(), mltDocumentFields)
				.flatMap(this::transformLuceneResultWithTransformer);

	}

	@Override
	public Mono<SearchResult<T, U>> moreLikeThisWithValues(ClientQueryParams<SearchResultItem<T, U>> queryParams,
			T key,
			U mltDocumentValue,
			ValueGetter<T, U> valueGetter) {
		Flux<Tuple2<String, Set<String>>> mltDocumentFields
				= indicizer.getMoreLikeThisDocumentFields(key, mltDocumentValue);
		return luceneIndex
				.moreLikeThis(resolveSnapshot(queryParams.snapshot()),
						queryParams.toQueryParams(),
						indicizer.getKeyFieldName(),
						mltDocumentFields
				)
				.flatMap(llSearchResult -> this.transformLuceneResultWithValues(llSearchResult,
						valueGetter
				));
	}

	@Override
	public Mono<SearchResult<T, U>> moreLikeThisWithTransformer(ClientQueryParams<SearchResultItem<T, U>> queryParams,
			T key,
			U mltDocumentValue,
			ValueTransformer<T, U> valueTransformer) {
		Flux<Tuple2<String, Set<String>>> mltDocumentFields
				= indicizer.getMoreLikeThisDocumentFields(key, mltDocumentValue);
		return luceneIndex
				.moreLikeThis(resolveSnapshot(queryParams.snapshot()),
						queryParams.toQueryParams(),
						indicizer.getKeyFieldName(),
						mltDocumentFields
				)
				.flatMap(llSearchResult -> this.transformLuceneResultWithTransformer(llSearchResult, valueTransformer));
	}

	@Override
	public Mono<SearchResultKeys<T>> search(ClientQueryParams<SearchResultKey<T>> queryParams) {
		return luceneIndex
				.search(resolveSnapshot(queryParams.snapshot()),
						queryParams.toQueryParams(),
						indicizer.getKeyFieldName()
				)
				.flatMap(this::transformLuceneResultWithTransformer);
	}

	@Override
	public Mono<SearchResult<T, U>> searchWithValues(ClientQueryParams<SearchResultItem<T, U>> queryParams,
			ValueGetter<T, U> valueGetter) {
		return luceneIndex
				.search(resolveSnapshot(queryParams.snapshot()), queryParams.toQueryParams(), indicizer.getKeyFieldName())
				.flatMap(llSearchResult -> this.transformLuceneResultWithValues(llSearchResult, valueGetter));
	}

	@Override
	public Mono<SearchResult<T, U>> searchWithTransformer(ClientQueryParams<SearchResultItem<T, U>> queryParams,
			ValueTransformer<T, U> valueTransformer) {
		return luceneIndex
				.search(resolveSnapshot(queryParams.snapshot()), queryParams.toQueryParams(), indicizer.getKeyFieldName())
				.flatMap(llSearchResult -> this.transformLuceneResultWithTransformer(llSearchResult, valueTransformer));
	}

	@Override
	public Mono<Long> count(@Nullable CompositeSnapshot snapshot, Query query) {
		return this
				.search(ClientQueryParams.<SearchResultKey<T>>builder().snapshot(snapshot).query(query).limit(0).build())
				.flatMap(tSearchResultKeys -> tSearchResultKeys.release().thenReturn(tSearchResultKeys.totalHitsCount()));
	}

	@Override
	public boolean isLowMemoryMode() {
		return luceneIndex.isLowMemoryMode();
	}

	@Override
	public Mono<Void> close() {
		return luceneIndex.close();
	}

	/**
	 * Flush writes to disk
	 */
	@Override
	public Mono<Void> flush() {
		return luceneIndex.flush();
	}

	/**
	 * Refresh index searcher
	 */
	@Override
	public Mono<Void> refresh(boolean force) {
		return luceneIndex.refresh(force);
	}

	@Override
	public Mono<LLSnapshot> takeSnapshot() {
		return luceneIndex.takeSnapshot();
	}

	@Override
	public Mono<Void> releaseSnapshot(LLSnapshot snapshot) {
		return luceneIndex.releaseSnapshot(snapshot);
	}
}
