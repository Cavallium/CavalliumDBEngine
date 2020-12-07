package it.cavallium.dbengine.database.utils;

public class LuceneParallelStreamCollectorResult {

	private final long totalHitsCount;

	public LuceneParallelStreamCollectorResult(long totalHitsCount) {
		this.totalHitsCount = totalHitsCount;
	}

	public long getTotalHitsCount() {
		return totalHitsCount;
	}
}
