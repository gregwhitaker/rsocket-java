/*
 * Copyright 2016 Netflix, Inc.
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

package io.rsocket;

import io.reactivex.subscribers.TestSubscriber;
import io.rsocket.exceptions.InvalidRequestException;
import io.rsocket.test.util.LocalDuplexConnection;
import io.rsocket.util.PayloadImpl;
import org.hamcrest.MatcherAssert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExternalResource;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.reactivestreams.Publisher;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.fail;

public class RSocketTest {

    @Rule
    public final SocketRule rule = new SocketRule();

    @Test(timeout = 2_000)
    public void testRequestReplyNoError() {
        TestSubscriber<Payload> subscriber = TestSubscriber.create();
        rule.crs.requestResponse(new PayloadImpl("hello"))
                .subscribe(subscriber);
        await(subscriber).assertNoErrors().assertComplete().assertValueCount(1);
        rule.assertNoErrors();
    }

    @Test(timeout = 2000)
    public void testHandlerEmitsError() {
        rule.setRequestAcceptor(new AbstractRSocket() {
            @Override
            public Mono<Payload> requestResponse(Payload payload) {
                return Mono.error(new NullPointerException("Deliberate exception."));
            }
        });
        TestSubscriber<Payload> subscriber = TestSubscriber.create();
        rule.crs.requestResponse(PayloadImpl.EMPTY)
            .subscribe(subscriber);
        await(subscriber).assertNotComplete().assertNoValues()
                         .assertError(InvalidRequestException.class);
        rule.assertNoErrors();
    }

    @Test
    public void testChannel() throws Exception {
        CountDownLatch latch = new CountDownLatch(10);
        Flux<Payload> requests = Flux
            .range(0, 10)
            .map(i -> new PayloadImpl("streaming in -> " + i));

        Flux<Payload> responses = rule.crs.requestChannel(requests);

        responses
            .doOnNext(p -> latch.countDown())
            .subscribe();

        latch.await();
    }

    private static TestSubscriber<Payload> await(TestSubscriber<Payload> subscriber) {
        try {
            return subscriber.await();
        } catch (InterruptedException e) {
            fail("Interrupted while waiting for completion.");
            return null;
        }
    }

    public static class SocketRule extends ExternalResource {

        private RSocketClient crs;
        private RSocketServer srs;
        private RSocket requestAcceptor;
        DirectProcessor<Frame> serverProcessor;
        DirectProcessor<Frame> clientProcessor;
        private ArrayList<Throwable> clientErrors = new ArrayList<>();
        private ArrayList<Throwable> serverErrors = new ArrayList<>();

        @Override
        public Statement apply(Statement base, Description description) {
            return new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    init();
                    base.evaluate();
                }
            };
        }

        protected void init() {
            serverProcessor = DirectProcessor.create();
            clientProcessor = DirectProcessor.create();

            LocalDuplexConnection serverConnection = new LocalDuplexConnection("server", clientProcessor, serverProcessor);
            LocalDuplexConnection clientConnection = new LocalDuplexConnection("client", serverProcessor, clientProcessor);

            requestAcceptor = null != requestAcceptor? requestAcceptor : new AbstractRSocket() {
                @Override
                public Mono<Payload> requestResponse(Payload payload) {
                    return Mono.just(payload);
                }

                @Override
                public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
                    Flux
                        .from(payloads)
                        .map(payload -> new PayloadImpl("server got -> [" + payload.toString() + "]"))
                        .subscribe();

                    return Flux.range(1, 10).map(payload -> new PayloadImpl("server got -> [" + payload.toString() + "]"));
                }
            };

            srs = new RSocketServer(serverConnection, requestAcceptor,
                throwable -> serverErrors.add(throwable));

            crs = new RSocketClient(clientConnection,
                                           throwable -> clientErrors.add(throwable), StreamIdSupplier.clientSupplier());
        }

        public void setRequestAcceptor(RSocket requestAcceptor) {
            this.requestAcceptor = requestAcceptor;
            init();
        }

        public void assertNoErrors() {
            MatcherAssert.assertThat("Unexpected error on the client connection.", clientErrors, is(empty()));
            MatcherAssert.assertThat("Unexpected error on the server connection.", serverErrors, is(empty()));
        }
    }

}