/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix.collapser;

import com.netflix.hystrix.HystrixCollapser.CollapsedRequest;
import rx.Observable;
import rx.subjects.ReplaySubject;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Observable that represents a collapsed request sent back to a user.  It gets used by Collapser implementations
 * when receiving a batch response and emitting values/errors to collapsers.
 *
 * There are 4 methods that Collapser implementations may use:
 *
 * 1) {@link #setResponse(T)}: return a single-valued response.  equivalent to OnNext(T), OnCompleted()
 * 2) {@link #emitResponse(T)}: emit a single value.  equivalent to OnNext(T)
 * 3) {@link #setException(Exception)}: return an exception.  equivalent to OnError(Exception)
 * 4) {@link #setComplete()}: mark that no more values will be emitted.  Should be used in conjunction with {@link #emitResponse(T)}.  equivalent to OnCompleted()
 *
 * <p>
 * This is an internal implementation of CollapsedRequest<T, R> functionality.  Instead of directly extending {@link rx.Observable},
 * it provides a {@link #toObservable()} method
 * <p>
 *
 * @param <T>
 * 
 * @param <R>
 */
/* package */class CollapsedRequestSubject<T, R> implements CollapsedRequest<T, R> {
    private final R argument;

    private AtomicBoolean valueSet = new AtomicBoolean(false);
    private final ReplaySubject<T> subject = ReplaySubject.create();
    private final Observable<T> subjectWithAccounting;

    private AtomicInteger outstandingSubscriptions = new AtomicInteger();

    public CollapsedRequestSubject(final R arg, final RequestBatch<?, T, R> containingBatch) {
        if (arg == RequestCollapser.NULL_SENTINEL) {
            this.argument = null;
        } else {
            this.argument = arg;
        }
        this.subjectWithAccounting = subject
                .doOnSubscribe(() -> outstandingSubscriptions.getAndIncrement())
                .doOnUnsubscribe(() -> {
                    if (outstandingSubscriptions.decrementAndGet() == 0) {
                        containingBatch.remove(arg);
                    }
                });
    }

    public CollapsedRequestSubject(final R arg) {
        this.subjectWithAccounting = subject;
        this.argument = arg;
    }

    /**
     * The request argument.
     * 
     * @return request argument
     */
    @Override
    public R getArgument() {
        return argument;
    }

    /**
     * When set any client thread blocking on get() will immediately be unblocked and receive the single-valued response.
     * 
     * @throws IllegalStateException
     *             if called more than once or after setException.
     * @param response response to give to initial command
     */
    @Override
    public void setResponse(T response) {
        if (isNotTerminated()) {
            subject.onNext(response);
            valueSet.set(true);
            subject.onCompleted();
        } else {
            throw new IllegalStateException("Response has already terminated so response can not be set : " + response);
        }
    }

    /**
     * Emit a response that should be OnNexted to an Observer
     * @param response response to emit to initial command
     */
    @Override
    public void emitResponse(T response) {
        if (isNotTerminated()) {
            subject.onNext(response);
            valueSet.set(true);
        } else {
            throw new IllegalStateException("Response has already terminated so response can not be set : " + response);
        }
    }

    @Override
    public void setComplete() {
        if (isNotTerminated()) {
            subject.onCompleted();
        }
    }

    /**
     * Set an exception if a response is not yet received otherwise skip it
     * 
     * @param e synthetic error to set on initial command when no actual response is available
     */
    public void setExceptionIfResponseNotReceived(Exception e) {
        if (!valueSet.get() && isNotTerminated()) {
            subject.onError(e);
        }
    }

    /**
     * Set an ISE if a response is not yet received otherwise skip it
     *
     * @param e A pre-generated exception.  If this is null an ISE will be created and returned
     * @param exceptionMessage The message for the ISE
     */
    public Exception setExceptionIfResponseNotReceived(Exception e, String exceptionMessage) {
        Exception exception = e;

        if (!valueSet.get() && isNotTerminated()) {
            if (e == null) {
                exception = new IllegalStateException(exceptionMessage);
            }
            setExceptionIfResponseNotReceived(exception);
        }
        // return any exception that was generated
        return exception;
    }

    /**
     * When set any client thread blocking on get() will immediately be unblocked and receive the exception.
     * 
     * @throws IllegalStateException
     *             if called more than once or after setResponse.
     * @param e received exception that gets set on the initial command
     */
    @Override
    public void setException(Exception e) {
        if (isNotTerminated()) {
            subject.onError(e);
        } else {
            throw new IllegalStateException("Response has already terminated so exception can not be set", e);
        }
    }

    private boolean isNotTerminated() {
        return !subject.hasCompleted() && !subject.hasThrowable();
    }

    public Observable<T> toObservable() {
        return subjectWithAccounting;
    }
}
