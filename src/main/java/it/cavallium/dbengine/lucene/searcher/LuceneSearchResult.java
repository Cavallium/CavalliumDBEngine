package it.cavallium.dbengine.lucene.searcher;

import it.cavallium.dbengine.database.LLKeyScore;
import it.cavallium.dbengine.database.disk.LLLocalKeyValueDatabase;
import java.io.IOException;
import java.util.Objects;
import org.warp.commonutils.log.Logger;
import org.warp.commonutils.log.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class LuceneSearchResult {

	protected static final Logger logger = LoggerFactory.getLogger(LuceneSearchResult.class);

	private volatile boolean releaseCalled;
	private final long totalHitsCount;
	private final Flux<LLKeyScore> results;
	private final Mono<Void> release;

	public LuceneSearchResult(long totalHitsCount, Flux<LLKeyScore> results, Mono<Void> release) {
		this.totalHitsCount = totalHitsCount;
		this.results = results;
		this.release = Mono.fromRunnable(() -> {
			if (releaseCalled) {
				logger.warn("LuceneSearchResult::release has been called twice!");
			}
			releaseCalled = true;
		}).then(release);
	}

	@SuppressWarnings("deprecation")
	@Override
	protected void finalize() throws Throwable {
		if (!releaseCalled) {
			logger.warn("LuceneSearchResult::release has not been called before class finalization!");
		}
		super.finalize();
	}

	public long totalHitsCount() {
		return totalHitsCount;
	}

	public Flux<LLKeyScore> results() {
		return results;
	}

	public Mono<Void> release() {
		return release;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == this)
			return true;
		if (obj == null || obj.getClass() != this.getClass())
			return false;
		var that = (LuceneSearchResult) obj;
		return this.totalHitsCount == that.totalHitsCount && Objects.equals(this.results, that.results);
	}

	@Override
	public int hashCode() {
		return Objects.hash(totalHitsCount, results);
	}

	@Override
	public String toString() {
		return "LuceneSearchResult[" + "totalHitsCount=" + totalHitsCount + ", " + "results=" + results + ']';
	}

}
