package it.cavallium.dbengine.database.memory;

import io.netty.buffer.ByteBufAllocator;
import it.cavallium.dbengine.client.DatabaseOptions;
import it.cavallium.dbengine.client.IndicizerAnalyzers;
import it.cavallium.dbengine.client.IndicizerSimilarities;
import it.cavallium.dbengine.client.LuceneOptions;
import it.cavallium.dbengine.database.Column;
import it.cavallium.dbengine.database.LLDatabaseConnection;
import it.cavallium.dbengine.database.LLKeyValueDatabase;
import it.cavallium.dbengine.database.LLLuceneIndex;
import it.cavallium.dbengine.database.disk.LLLocalKeyValueDatabase;
import it.cavallium.dbengine.database.disk.LLLocalLuceneIndex;
import it.cavallium.dbengine.database.disk.LLLocalMultiLuceneIndex;
import it.cavallium.dbengine.netty.JMXNettyMonitoringManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class LLMemoryDatabaseConnection implements LLDatabaseConnection {

	static {
		JMXNettyMonitoringManager.initialize();
	}

	private final ByteBufAllocator allocator;

	public LLMemoryDatabaseConnection(ByteBufAllocator allocator) {
		this.allocator = allocator;
	}

	@Override
	public ByteBufAllocator getAllocator() {
		return allocator;
	}

	@Override
	public Mono<LLDatabaseConnection> connect() {
		return Mono.empty();
	}

	@Override
	public Mono<LLKeyValueDatabase> getDatabase(String name,
			List<Column> columns,
			DatabaseOptions databaseOptions) {
		return Mono
				.<LLKeyValueDatabase>fromCallable(() -> new LLMemoryKeyValueDatabase(
						allocator,
						name,
						columns
				))
				.subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public Mono<LLLuceneIndex> getLuceneIndex(String name,
			int instancesCount,
			IndicizerAnalyzers indicizerAnalyzers,
			IndicizerSimilarities indicizerSimilarities,
			LuceneOptions luceneOptions) {
		return Mono
				.<LLLuceneIndex>fromCallable(() -> new LLLocalLuceneIndex(null,
						name,
						indicizerAnalyzers,
						indicizerSimilarities,
						luceneOptions
				))
				.subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public Mono<Void> disconnect() {
		return Mono.empty();
	}
}
