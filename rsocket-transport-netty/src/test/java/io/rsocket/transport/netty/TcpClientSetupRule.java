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

package io.rsocket.transport.netty;

import io.rsocket.RSocket;
import io.rsocket.RSocketFactory;
import io.rsocket.test.ClientSetupRule;
import io.rsocket.test.TestRSocket;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.transport.netty.server.TcpServerTransport;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

public class TcpClientSetupRule extends ClientSetupRule<InetSocketAddress> {

    public TcpClientSetupRule() {
        super(() -> InetSocketAddress.createUnresolved("localhost", 8989),
            (address) ->
            {
                RSocket block = RSocketFactory
                    .connect()
                    .transport(TcpClientTransport.create(address.getHostName(), address.getPort()))
                    .start()
                    .doOnError(t -> t.printStackTrace())
                    .block();

                return block;
            },
            (address) ->
                RSocketFactory
                    .receive()
                    .acceptor((setup, sendingSocket) -> Mono.just(new TestRSocket()))
                    .transport(TcpServerTransport.create(address.getHostName(), address.getPort()))
                    .start()
                    .subscribe()
        );
    }

}
