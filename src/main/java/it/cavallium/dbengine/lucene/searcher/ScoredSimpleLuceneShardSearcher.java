package it.cavallium.dbengine.lucene.searcher;

import static it.cavallium.dbengine.lucene.searcher.CurrentPageInfo.EMPTY_STATUS;
import static it.cavallium.dbengine.lucene.searcher.CurrentPageInfo.TIE_BREAKER;

import it.cavallium.dbengine.database.LLKeyScore;
import it.cavallium.dbengine.lucene.LuceneUtils;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.TopFieldDocs;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

class ScoredSimpleLuceneShardSearcher implements LuceneShardSearcher {

	private final Object lock = new Object();
	private final List<IndexSearcher> indexSearchersArray = new ArrayList<>();
	private final List<Mono<Void>> indexSearcherReleasersArray = new ArrayList<>();
	private final List<TopFieldCollector> collectors = new ArrayList<>();
	private final CollectorManager<TopFieldCollector, TopDocs> firstPageSharedManager;
	private final Query luceneQuery;
	private final PaginationInfo paginationInfo;

	public ScoredSimpleLuceneShardSearcher(CollectorManager<TopFieldCollector, TopDocs> firstPageSharedManager,
			Query luceneQuery, PaginationInfo paginationInfo) {
		this.firstPageSharedManager = firstPageSharedManager;
		this.luceneQuery = luceneQuery;
		this.paginationInfo = paginationInfo;
	}

	@Override
	public Mono<Void> searchOn(IndexSearcher indexSearcher,
			Mono<Void> releaseIndexSearcher,
			LocalQueryParams queryParams,
			Scheduler scheduler) {
		return Mono.<Void>fromCallable(() -> {
			TopFieldCollector collector;
			synchronized (lock) {
				//noinspection BlockingMethodInNonBlockingContext
				collector = firstPageSharedManager.newCollector();
				indexSearchersArray.add(indexSearcher);
				indexSearcherReleasersArray.add(releaseIndexSearcher);
				collectors.add(collector);
			}
			//noinspection BlockingMethodInNonBlockingContext
			indexSearcher.search(luceneQuery, collector);
			return null;
		}).subscribeOn(scheduler);
	}

	@Override
	public Mono<LuceneSearchResult> collect(LocalQueryParams queryParams, String keyFieldName, Scheduler scheduler) {
		if (!queryParams.isScored()) {
			return Mono.error(
					new UnsupportedOperationException("Can't execute an unscored query with a scored lucene shard searcher")
			);
		}
		return Mono
				.fromCallable(() -> {
					TopDocs result;
					Mono<Void> release;
					synchronized (lock) {
						//noinspection BlockingMethodInNonBlockingContext
						result = firstPageSharedManager.reduce(collectors);
						release = Mono.when(indexSearcherReleasersArray);
					}
					IndexSearchers indexSearchers;
					synchronized (lock) {
						indexSearchers = IndexSearchers.of(indexSearchersArray);
					}
					Flux<LLKeyScore> firstPageHits = LuceneUtils
							.convertHits(result.scoreDocs, indexSearchers, keyFieldName, scheduler);

					Flux<LLKeyScore> nextHits = Flux.defer(() -> {
						if (paginationInfo.forceSinglePage()
								|| paginationInfo.totalLimit() - paginationInfo.firstPageLimit() <= 0) {
							return Flux.empty();
						}
						return Flux
								.<TopDocs, CurrentPageInfo>generate(
										() -> new CurrentPageInfo(LuceneUtils.getLastFieldDoc(result.scoreDocs),
												paginationInfo.totalLimit() - paginationInfo.firstPageLimit(), 1),
										(s, sink) -> {
											if (s.last() != null && s.remainingLimit() > 0) {
												Sort luceneSort = queryParams.sort();
												if (luceneSort == null) {
													luceneSort = Sort.RELEVANCE;
												}
												CollectorManager<TopFieldCollector, TopDocs> sharedManager
														= new ScoringShardsCollectorManager(luceneSort, s.currentPageLimit(),
														(FieldDoc) s.last(), LuceneUtils.totalHitsThreshold(), 0, s.currentPageLimit());
												//noinspection BlockingMethodInNonBlockingContext
												TopDocs pageTopDocs = Flux
														.fromIterable(indexSearchersArray)
														.index()
														.flatMapSequential(tuple -> Mono
																.fromCallable(() -> {
																	long shardIndex = tuple.getT1();
																	IndexSearcher indexSearcher = tuple.getT2();
																	//noinspection BlockingMethodInNonBlockingContext
																	TopFieldCollector collector = sharedManager.newCollector();
																	//noinspection BlockingMethodInNonBlockingContext
																	indexSearcher.search(luceneQuery, collector);
																	return collector;
																})
																.subscribeOn(scheduler)
														)
														.collect(Collectors.toCollection(ObjectArrayList::new))
														.flatMap(collectors -> Mono.fromCallable(() -> {
															//noinspection BlockingMethodInNonBlockingContext
															return sharedManager.reduce(collectors);
														}).subscribeOn(scheduler))
														.subscribeOn(Schedulers.immediate())
														.blockOptional().orElseThrow();
												var pageLastDoc = LuceneUtils.getLastFieldDoc(pageTopDocs.scoreDocs);
												sink.next(pageTopDocs);
												return new CurrentPageInfo(pageLastDoc, s.remainingLimit() - s.currentPageLimit(), s.pageIndex() + 1);
											} else {
												sink.complete();
												return EMPTY_STATUS;
											}
										},
										s -> {}
								)
								.subscribeOn(scheduler)
								.concatMap(topFieldDoc -> LuceneUtils
										.convertHits(topFieldDoc.scoreDocs, indexSearchers, keyFieldName, scheduler)
								);
					});

					return new LuceneSearchResult(result.totalHits.value,
							firstPageHits
									.concatWith(nextHits),
									//.transform(flux -> LuceneUtils.filterTopDoc(flux, queryParams)),
							release
					);
				})
				.subscribeOn(scheduler);
	}

}
