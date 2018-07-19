package com.netflix.hystrix;

import rx.Observable;
import rx.Subscription;
import rx.subjects.ReplaySubject;

import java.util.concurrent.atomic.AtomicInteger;

public class HystrixCachedObservable<R> {
    protected final Subscription originalSubscription;
    protected final Observable<R> cachedObservable;
    private AtomicInteger outstandingSubscriptions = new AtomicInteger();

    protected HystrixCachedObservable(final Observable<R> originalObservable) {
        ReplaySubject<R> replaySubject = ReplaySubject.create();
        this.originalSubscription = originalObservable
                .subscribe(replaySubject);

        this.cachedObservable = replaySubject
                .doOnUnsubscribe(() -> {
                    if (outstandingSubscriptions.decrementAndGet() == 0) {
                        originalSubscription.unsubscribe();
                    }
                })
                .doOnSubscribe(() -> outstandingSubscriptions.getAndIncrement());
    }

    public static <R> HystrixCachedObservable<R> from(Observable<R> o, AbstractCommand<R> originalCommand) {
        return new HystrixCommandResponseFromCache<>(o, originalCommand);
    }

    public static <R> HystrixCachedObservable<R> from(Observable<R> o) {
        return new HystrixCachedObservable<>(o);
    }

    public Observable<R> toObservable() {
        return cachedObservable;
    }

    public void unsubscribe() {
        originalSubscription.unsubscribe();
    }
}
