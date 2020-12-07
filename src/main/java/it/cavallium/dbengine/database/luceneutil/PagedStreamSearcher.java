package it.cavallium.dbengine.database.luceneutil;

import java.io.IOException;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
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
	public Long streamSearch(IndexSearcher indexSearcher,
			Query query,
			int limit,
			@Nullable Sort luceneSort,
			String keyFieldName,
			Consumer<String> consumer) throws IOException {
		if (limit < MAX_ITEMS_PER_PAGE) {
			// Use a normal search method because the limit is low
			return baseStreamSearcher.streamSearch(indexSearcher, query, limit, luceneSort, keyFieldName, consumer);
		}
		IntWrapper currentAllowedResults = new IntWrapper(limit);

		// Run the first page search
		TopDocs lastTopDocs = indexSearcher.search(query, MAX_ITEMS_PER_PAGE, luceneSort);
		if (lastTopDocs.scoreDocs.length > 0) {
			ScoreDoc lastScoreDoc = getLastItem(lastTopDocs.scoreDocs);
			consumeHits(currentAllowedResults, lastTopDocs.scoreDocs, indexSearcher, keyFieldName, consumer);

			// Run the searches for each page until the end
			boolean finished = currentAllowedResults.var <= 0;
			while (!finished) {
				lastTopDocs = indexSearcher.searchAfter(lastScoreDoc, query, MAX_ITEMS_PER_PAGE, luceneSort);
				if (lastTopDocs.scoreDocs.length > 0) {
					lastScoreDoc = getLastItem(lastTopDocs.scoreDocs);
					consumeHits(currentAllowedResults, lastTopDocs.scoreDocs, indexSearcher, keyFieldName, consumer);
				}
				if (lastTopDocs.scoreDocs.length < MAX_ITEMS_PER_PAGE || currentAllowedResults.var <= 0) {
					finished = true;
				}
			}
		}
		return lastTopDocs.totalHits.value;
	}

	private void consumeHits(IntWrapper currentAllowedResults,
			ScoreDoc[] hits,
			IndexSearcher indexSearcher,
			String keyFieldName,
			Consumer<String> consumer) throws IOException {
		for (ScoreDoc hit : hits) {
			int docId = hit.doc;
			float score = hit.score;

			if (currentAllowedResults.var-- > 0) {
				Document d = indexSearcher.doc(docId, Set.of(keyFieldName));
				if (d.getFields().isEmpty()) {
					System.err.println("The document docId:" + docId + ",score:" + score + " is empty.");
					var realFields = indexSearcher.doc(docId).getFields();
					if (!realFields.isEmpty()) {
						System.err.println("Present fields:");
						for (IndexableField field : realFields) {
							System.err.println(" - " + field.name());
						}
					}
				} else {
					var field = d.getField(keyFieldName);
					if (field == null) {
						System.err.println("Can't get key of document docId:" + docId + ",score:" + score);
					} else {
						consumer.accept(field.stringValue());
					}
				}
			} else {
				break;
			}
		}
	}

	private static ScoreDoc getLastItem(ScoreDoc[] scoreDocs) {
		return scoreDocs[scoreDocs.length - 1];
	}
}