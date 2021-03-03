package it.cavallium.dbengine.client;

import it.cavallium.dbengine.client.query.ClientQueryParams;
import it.cavallium.dbengine.client.query.current.data.Query;
import it.cavallium.dbengine.database.LLLuceneIndex;
import it.cavallium.dbengine.database.LLScoreMode;
import it.cavallium.dbengine.database.LLSearchResult;
import it.cavallium.dbengine.database.LLSnapshot;
import it.cavallium.dbengine.database.LLSnapshottable;
import it.cavallium.dbengine.database.LLTerm;
import it.cavallium.dbengine.database.collections.Joiner.ValueGetter;
import it.cavallium.dbengine.lucene.LuceneUtils;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

public class LuceneIndex<T, U> implements LLSnapshottable {

	private final LLLuceneIndex luceneIndex;
	private final Indicizer<T,U> indicizer;

	public LuceneIndex(LLLuceneIndex luceneIndex, Indicizer<T, U> indicizer) {
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

	public Mono<Void> addDocument(T key, U value) {
		return indicizer
				.toDocument(key, value)
				.flatMap(doc -> luceneIndex.addDocument(indicizer.toIndex(key), doc));
	}

	public Mono<Void> addDocuments(Flux<Entry<T, U>> entries) {
		return luceneIndex
				.addDocuments(entries
						.flatMap(entry -> indicizer
								.toDocument(entry.getKey(), entry.getValue())
								.map(doc -> Map.entry(indicizer.toIndex(entry.getKey()), doc)))
						.groupBy(Entry::getKey, Entry::getValue)
				);
	}

	public Mono<Void> deleteDocument(T key) {
		LLTerm id = indicizer.toIndex(key);
		return luceneIndex.deleteDocument(id);
	}

	public Mono<Void> updateDocument(T key, U value) {
		return indicizer
				.toDocument(key, value)
				.flatMap(doc -> luceneIndex.updateDocument(indicizer.toIndex(key), doc));
	}

	public Mono<Void> updateDocuments(Flux<Entry<T, U>> entries) {
		return luceneIndex
				.updateDocuments(entries
						.flatMap(entry -> indicizer
								.toDocument(entry.getKey(), entry.getValue())
								.map(doc -> Map.entry(indicizer.toIndex(entry.getKey()), doc)))
						.groupBy(Entry::getKey, Entry::getValue)
				);
	}

	public Mono<Void> deleteAll() {
		return luceneIndex.deleteAll();
	}

	private SearchResultKeys<T> transformLuceneResult(LLSearchResult llSearchResult,
			@Nullable MultiSort<SearchResultKey<T>> sort,
			LLScoreMode scoreMode,
			@Nullable Long limit) {
		Flux<Flux<LuceneSignal<SearchResultKey<T>>>> mappedKeys = llSearchResult
				.results()
				.map(flux -> flux.map(signal -> {
					if (signal.isValue()) {
						return LuceneSignal.value(
								new SearchResultKey<T>(indicizer.getKey(signal.getValue().getKey()),
								signal.getValue().getScore()
						));
					} else {
						return LuceneSignal.totalHitsCount(signal.getTotalHitsCount());
					}
				}));
		MultiSort<SearchResultKey<T>> finalSort;
		if (scoreMode != LLScoreMode.COMPLETE_NO_SCORES && sort == null) {
			finalSort = MultiSort.topScore();
		} else {
			finalSort = sort;
		}

		MultiSort<LuceneSignal<SearchResultKey<T>>> mappedSort;
		if (finalSort != null) {
			mappedSort = new MultiSort<>(finalSort.getQuerySort(), (signal1, signal2) -> {
				if (signal1.isValue() && signal2.isValue()) {
					return finalSort.getResultSort().compare((signal1.getValue()), signal2.getValue());
				} else {
					return 0;
				}
			});
		} else {
			mappedSort = null;
		}
		Flux<LuceneSignal<SearchResultKey<T>>> sortedKeys = LuceneUtils.mergeStream(mappedKeys, mappedSort, limit);
		return new SearchResultKeys<>(sortedKeys);
	}

	private SearchResult<T, U> transformLuceneResultWithValues(LLSearchResult llSearchResult,
			@Nullable MultiSort<SearchResultItem<T, U>> sort,
			LLScoreMode scoreMode,
			@Nullable Long limit,
			ValueGetter<T, U> valueGetter) {
		Flux<Flux<LuceneSignal<SearchResultItem<T, U>>>> mappedKeys = llSearchResult
				.results()
				.map(flux -> flux.flatMapSequential(signal -> {
					if (signal.isValue()) {
						var key = indicizer.getKey(signal.getValue().getKey());
						return valueGetter
								.get(key)
								.map(value -> LuceneSignal.value(new SearchResultItem<>(key, value, signal.getValue().getScore())));
					} else {
						return Mono.just(LuceneSignal.totalHitsCount((signal.getTotalHitsCount())));
					}
				}));
		MultiSort<SearchResultItem<T, U>> finalSort;
		if (scoreMode != LLScoreMode.COMPLETE_NO_SCORES && sort == null) {
			finalSort = MultiSort.topScoreWithValues();
		} else {
			finalSort = sort;
		}

		MultiSort<LuceneSignal<SearchResultItem<T, U>>> mappedSort;
		if (finalSort != null) {
			mappedSort = new MultiSort<>(finalSort.getQuerySort(), (signal1, signal2) -> {
				if (signal1.isValue() && signal2.isValue()) {
					return finalSort.getResultSort().compare((signal1.getValue()), signal2.getValue());
				} else {
					return 0;
				}
			});
		} else {
			mappedSort = null;
		}
		var sortedKeys = LuceneUtils.mergeStream(mappedKeys, mappedSort, limit);
		return new SearchResult<>(sortedKeys);
	}

	/**
	 *
	 * @param queryParams the limit is valid for each lucene instance.
	 *               If you have 15 instances, the number of elements returned
	 *               can be at most <code>limit * 15</code>
	 * @return the collection has one or more flux
	 */
	public Mono<SearchResultKeys<T>> moreLikeThis(
			ClientQueryParams<SearchResultKey<T>> queryParams,
			T key,
			U mltDocumentValue) {
		Flux<Tuple2<String, Set<String>>> mltDocumentFields
				= indicizer.getMoreLikeThisDocumentFields(key, mltDocumentValue);
		return luceneIndex
				.moreLikeThis(resolveSnapshot(queryParams.getSnapshot()), queryParams.toQueryParams(), indicizer.getKeyFieldName(), mltDocumentFields)
				.map(llSearchResult -> this.transformLuceneResult(llSearchResult,
						queryParams.getSort(),
						queryParams.getScoreMode(),
						queryParams.getLimit()
				));

	}

	/**
	 *
	 * @param queryParams the limit is valid for each lucene instance.
	 *               If you have 15 instances, the number of elements returned
	 *               can be at most <code>limit * 15</code>
	 * @return the collection has one or more flux
	 */
	public Mono<SearchResult<T, U>> moreLikeThisWithValues(
			ClientQueryParams<SearchResultItem<T, U>> queryParams,
			T key,
			U mltDocumentValue,
			ValueGetter<T, U> valueGetter) {
		Flux<Tuple2<String, Set<String>>> mltDocumentFields
				= indicizer.getMoreLikeThisDocumentFields(key, mltDocumentValue);
		return luceneIndex
				.moreLikeThis(resolveSnapshot(queryParams.getSnapshot()),
						queryParams.toQueryParams(),
						indicizer.getKeyFieldName(),
						mltDocumentFields
				)
				.map(llSearchResult -> this.transformLuceneResultWithValues(llSearchResult,
						queryParams.getSort(),
						queryParams.getScoreMode(),
						queryParams.getLimit(),
						valueGetter
				));
	}

	/**
	 *
	 * @param queryParams the limit is valid for each lucene instance.
	 *               If you have 15 instances, the number of elements returned
	 *               can be at most <code>limit * 15</code>
	 * @return the collection has one or more flux
	 */
	public Mono<SearchResultKeys<T>> search(
			ClientQueryParams<SearchResultKey<T>> queryParams) {
		return luceneIndex
				.search(resolveSnapshot(queryParams.getSnapshot()), queryParams.toQueryParams(), indicizer.getKeyFieldName())
				.map(llSearchResult -> this.transformLuceneResult(llSearchResult,
						queryParams.getSort(),
						queryParams.getScoreMode(),
						queryParams.getLimit()
				));
	}

	/**
	 *
	 * @param queryParams the limit is valid for each lucene instance.
	 *               If you have 15 instances, the number of elements returned
	 *               can be at most <code>limit * 15</code>
	 * @return the collection has one or more flux
	 */
	public Mono<SearchResult<T, U>> searchWithValues(
			ClientQueryParams<SearchResultItem<T, U>> queryParams,
			ValueGetter<T, U> valueGetter) {
		return luceneIndex
				.search(resolveSnapshot(queryParams.getSnapshot()), queryParams.toQueryParams(), indicizer.getKeyFieldName())
				.map(llSearchResult -> this.transformLuceneResultWithValues(llSearchResult,
						queryParams.getSort(),
						queryParams.getScoreMode(),
						queryParams.getLimit(),
						valueGetter
				));
	}

	public Mono<Long> count(@Nullable CompositeSnapshot snapshot, Query query) {
		return Mono.from(this.search(ClientQueryParams.<SearchResultKey<T>>builder().snapshot(snapshot).query(query).limit(0).build())
				.flatMapMany(SearchResultKeys::results)
				.filter(LuceneSignal::isTotalHitsCount)
				.map(LuceneSignal::getTotalHitsCount));
	}

	public boolean isLowMemoryMode() {
		return luceneIndex.isLowMemoryMode();
	}

	public Mono<Void> close() {
		return luceneIndex.close();
	}

	/**
	 * Flush writes to disk
	 */
	public Mono<Void> flush() {
		return luceneIndex.flush();
	}

	/**
	 * Refresh index searcher
	 */
	public Mono<Void> refresh() {
		return luceneIndex.refresh();
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
