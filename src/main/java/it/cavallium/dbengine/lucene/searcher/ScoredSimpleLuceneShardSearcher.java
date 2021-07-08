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
	private final List<TopFieldCollector> collectors = new ArrayList<>();
	private final CollectorManager<TopFieldCollector, TopFieldDocs> sharedManager;
	private final Query luceneQuery;
	private final PaginationInfo paginationInfo;

	public ScoredSimpleLuceneShardSearcher(CollectorManager<TopFieldCollector, TopFieldDocs> sharedManager,
			Query luceneQuery, PaginationInfo paginationInfo) {
		this.sharedManager = sharedManager;
		this.luceneQuery = luceneQuery;
		this.paginationInfo = paginationInfo;
	}

	@Override
	public Mono<Void> searchOn(IndexSearcher indexSearcher, LocalQueryParams queryParams, Scheduler scheduler) {
		return Mono.<Void>fromCallable(() -> {
			TopFieldCollector collector;
			synchronized (lock) {
				//noinspection BlockingMethodInNonBlockingContext
				collector = sharedManager.newCollector();
				indexSearchersArray.add(indexSearcher);
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
					if (queryParams.isSorted()) {
						TopFieldDocs[] topDocs;
						synchronized (lock) {
							topDocs = new TopFieldDocs[collectors.size()];
							var i = 0;
							for (TopFieldCollector collector : collectors) {
								topDocs[i] = collector.topDocs();
								for (ScoreDoc scoreDoc : topDocs[i].scoreDocs) {
									scoreDoc.shardIndex = i;
								}
								i++;
							}
						}
						result = LuceneUtils.mergeTopDocs(queryParams.sort(), LuceneUtils.safeLongToInt(paginationInfo.firstPageOffset()),
								LuceneUtils.safeLongToInt(paginationInfo.firstPageLimit()),
								topDocs,
								TIE_BREAKER
						);
					} else {
						TopDocs[] topDocs;
						synchronized (lock) {
							topDocs = new TopDocs[collectors.size()];
							var i = 0;
							for (TopFieldCollector collector : collectors) {
								topDocs[i] = collector.topDocs();
								for (ScoreDoc scoreDoc : topDocs[i].scoreDocs) {
									scoreDoc.shardIndex = i;
								}
								i++;
							}
						}
						result = TopDocs.merge(LuceneUtils.safeLongToInt(paginationInfo.firstPageOffset()),
								LuceneUtils.safeLongToInt(paginationInfo.firstPageLimit()),
								topDocs,
								TIE_BREAKER
						);
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
												CollectorManager<TopFieldCollector, TopFieldDocs> sharedManager;
												Sort luceneSort = queryParams.sort();
												if (luceneSort == null) {
													luceneSort = Sort.RELEVANCE;
												}
												sharedManager = TopFieldCollector.createSharedManager(luceneSort, s.currentPageLimit(),
														(FieldDoc) s.last(), 1000);
												//noinspection BlockingMethodInNonBlockingContext
												TopDocs pageTopDocs = Flux
														.fromIterable(indexSearchersArray)
														.index()
														.flatMapSequential(tuple -> Mono
																.fromCallable(() -> {
																	long shardIndex = tuple.getT1();
																	IndexSearcher indexSearcher = tuple.getT2();
																	//noinspection BlockingMethodInNonBlockingContext
																	var results = indexSearcher.search(luceneQuery, sharedManager);
																	for (ScoreDoc scoreDoc : results.scoreDocs) {
																		scoreDoc.shardIndex = LuceneUtils.safeLongToInt(shardIndex);
																	}
																	return results;
																})
																.subscribeOn(scheduler)
														)
														.collect(Collectors.toCollection(ObjectArrayList::new))
														.map(topFieldDocs -> topFieldDocs.toArray(TopFieldDocs[]::new))
														.flatMap(topFieldDocs -> Mono.fromCallable(() -> {
															if (queryParams.isSorted()) {
																return LuceneUtils.mergeTopDocs(queryParams.sort(), 0, s.currentPageLimit(),
																		topFieldDocs,
																		TIE_BREAKER
																);
															} else {
																return TopDocs.merge(0, s.currentPageLimit(),
																		topFieldDocs,
																		TIE_BREAKER
																);
															}
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

					return new LuceneSearchResult(result.totalHits.value, firstPageHits.concatWith(nextHits));
				})
				.subscribeOn(scheduler);
	}

}
