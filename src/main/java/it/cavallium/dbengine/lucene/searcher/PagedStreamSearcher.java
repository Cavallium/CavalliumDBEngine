package it.cavallium.dbengine.lucene.searcher;

import it.cavallium.dbengine.lucene.LuceneUtils;
import java.io.IOException;
import java.util.function.LongConsumer;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopDocs;
import org.jetbrains.annotations.Nullable;
import org.warp.commonutils.type.IntWrapper;

/**
 * Sorted paged search (the most memory-efficient stream searcher for big queries)
 */
public class PagedStreamSearcher implements LuceneStreamSearcher {

	public static final int MAX_ITEMS_PER_PAGE = 1000;
	private final LuceneStreamSearcher baseStreamSearcher;

	public PagedStreamSearcher(LuceneStreamSearcher baseStreamSearcher) {
		this.baseStreamSearcher = baseStreamSearcher;
	}

	@Override
	public void search(IndexSearcher indexSearcher,
			Query query,
			int limit,
			@Nullable Sort luceneSort,
			ScoreMode scoreMode,
			@Nullable Float minCompetitiveScore,
			String keyFieldName,
			ResultItemConsumer resultsConsumer,
			LongConsumer totalHitsConsumer) throws IOException {
		if (limit < MAX_ITEMS_PER_PAGE) {
			// Use a normal search method because the limit is low
			baseStreamSearcher.search(indexSearcher,
					query,
					limit,
					luceneSort,
					scoreMode,
					minCompetitiveScore,
					keyFieldName,
					resultsConsumer,
					totalHitsConsumer
			);
			return;
		}
		IntWrapper currentAllowedResults = new IntWrapper(limit);

		// Run the first page search
		TopDocs lastTopDocs = indexSearcher.search(query, MAX_ITEMS_PER_PAGE, luceneSort, scoreMode != ScoreMode.COMPLETE_NO_SCORES);
		totalHitsConsumer.accept(lastTopDocs.totalHits.value);
		if (lastTopDocs.scoreDocs.length > 0) {
			ScoreDoc lastScoreDoc = getLastItem(lastTopDocs.scoreDocs);
			consumeHits(currentAllowedResults, lastTopDocs.scoreDocs, indexSearcher, minCompetitiveScore, keyFieldName, resultsConsumer);

			// Run the searches for each page until the end
			boolean finished = currentAllowedResults.var <= 0;
			while (!finished) {
				lastTopDocs = indexSearcher.searchAfter(lastScoreDoc, query, MAX_ITEMS_PER_PAGE, luceneSort, scoreMode != ScoreMode.COMPLETE_NO_SCORES);
				if (lastTopDocs.scoreDocs.length > 0) {
					lastScoreDoc = getLastItem(lastTopDocs.scoreDocs);
					consumeHits(currentAllowedResults, lastTopDocs.scoreDocs, indexSearcher, minCompetitiveScore, keyFieldName, resultsConsumer);
				}
				if (lastTopDocs.scoreDocs.length < MAX_ITEMS_PER_PAGE || currentAllowedResults.var <= 0) {
					finished = true;
				}
			}
		}
	}

	private HandleResult consumeHits(IntWrapper currentAllowedResults,
			ScoreDoc[] hits,
			IndexSearcher indexSearcher,
			@Nullable Float minCompetitiveScore,
			String keyFieldName,
			ResultItemConsumer resultsConsumer) throws IOException {
		for (ScoreDoc hit : hits) {
			int docId = hit.doc;
			float score = hit.score;

			if (currentAllowedResults.var-- > 0) {
				if (LuceneUtils.collectTopDoc(logger,
						docId,
						score,
						minCompetitiveScore,
						indexSearcher,
						keyFieldName,
						resultsConsumer
				) == HandleResult.HALT) {
					return HandleResult.HALT;
				}
			} else {
				break;
			}
		}
		return HandleResult.CONTINUE;
	}

	private static ScoreDoc getLastItem(ScoreDoc[] scoreDocs) {
		return scoreDocs[scoreDocs.length - 1];
	}
}
