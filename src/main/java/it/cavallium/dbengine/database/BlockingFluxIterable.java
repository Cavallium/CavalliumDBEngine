package it.cavallium.dbengine.database;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

public abstract class BlockingFluxIterable<T> {

	private final Scheduler scheduler;

	public BlockingFluxIterable(Scheduler scheduler) {
		this.scheduler = scheduler;
	}

	public Flux<T> generate() {
		return Flux
				.<T>create(sink -> {
					boolean alreadyInitialized = false;
					AtomicLong requests = new AtomicLong(0);
					Semaphore availableRequests = new Semaphore(0);
					AtomicBoolean cancelled = new AtomicBoolean(false);
					sink.onRequest(n -> {
						requests.addAndGet(n);
						availableRequests.release();
					});
					sink.onDispose(() -> {
						cancelled.set(true);
						availableRequests.release();
					});

					try {
						try {
							loop:
							while (true) {
								availableRequests.acquireUninterruptibly();
								var remainingRequests = requests.getAndSet(0);
								if (remainingRequests == 0 || cancelled.get()) {
									break;
								}

								while (remainingRequests-- > 0) {
									if (!alreadyInitialized) {
										alreadyInitialized = true;
										this.onStartup();
									}

									T next = onNext();
									if (next == null) {
										break loop;
									}
									sink.next(next);
								}
							}
						} finally {
							if (alreadyInitialized) {
								onTerminate();
							}
						}
					} finally {
						sink.complete();
					}
				})
				.subscribeOn(scheduler);
	}

	public abstract void onStartup();

	public abstract void onTerminate();

	@Nullable
	public abstract T onNext();
}