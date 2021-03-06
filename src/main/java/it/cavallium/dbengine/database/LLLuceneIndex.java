package it.cavallium.dbengine.database;

import it.cavallium.data.generator.nativedata.Nullablefloat;
import it.cavallium.dbengine.client.query.current.data.NoSort;
import it.cavallium.dbengine.client.query.current.data.Query;
import it.cavallium.dbengine.client.query.current.data.QueryParams;
import it.cavallium.dbengine.client.query.current.data.ScoreMode;
import it.cavallium.dbengine.lucene.LuceneUtils;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.GroupedFlux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

public interface LLLuceneIndex extends LLSnapshottable {

	String getLuceneIndexName();

	Mono<Void> addDocument(LLTerm id, LLDocument doc);

	Mono<Void> addDocuments(Flux<Entry<LLTerm, LLDocument>> documents);

	Mono<Void> deleteDocument(LLTerm id);

	Mono<Void> updateDocument(LLTerm id, LLDocument document);

	Mono<Void> updateDocuments(Mono<Map<LLTerm, LLDocument>> documents);

	Mono<Void> deleteAll();

	/**
	 * @param queryParams the limit is valid for each lucene instance. If you have 15 instances, the number of elements
	 *                    returned can be at most <code>limit * 15</code>.
	 *                    <p>
	 *                    The additional query will be used with the moreLikeThis query: "mltQuery AND additionalQuery"
	 * @return the collection has one or more flux
	 */
	Mono<LLSearchResultShard> moreLikeThis(@Nullable LLSnapshot snapshot,
			QueryParams queryParams,
			String keyFieldName,
			Flux<Tuple2<String, Set<String>>> mltDocumentFields);

	/**
	 * @param queryParams the limit is valid for each lucene instance. If you have 15 instances, the number of elements
	 *                    returned can be at most <code>limit * 15</code>
	 * @return the collection has one or more flux
	 */
	Mono<LLSearchResultShard> search(@Nullable LLSnapshot snapshot, QueryParams queryParams, String keyFieldName);

	default Mono<Long> count(@Nullable LLSnapshot snapshot, Query query) {
		QueryParams params = QueryParams.of(query, 0, 0, Nullablefloat.empty(), NoSort.of(), ScoreMode.of(false, false));
		return Mono.from(this.search(snapshot, params, null)
				.flatMap(llSearchResultShard -> llSearchResultShard.release().thenReturn(llSearchResultShard.totalHitsCount()))
				.defaultIfEmpty(0L));
	}

	boolean isLowMemoryMode();

	Mono<Void> close();

	/**
	 * Flush writes to disk
	 */
	Mono<Void> flush();

	/**
	 * Refresh index searcher
	 */
	Mono<Void> refresh(boolean force);
}
