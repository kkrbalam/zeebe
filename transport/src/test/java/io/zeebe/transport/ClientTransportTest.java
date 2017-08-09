/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.transport;

import static io.zeebe.test.util.TestUtil.doRepeatedly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import io.zeebe.dispatcher.Dispatcher;
import io.zeebe.dispatcher.Dispatchers;
import io.zeebe.test.util.AutoCloseableRule;
import io.zeebe.test.util.TestUtil;
import io.zeebe.test.util.io.FailingBufferWriter;
import io.zeebe.test.util.io.FailingBufferWriter.FailingBufferWriterException;
import io.zeebe.transport.impl.TransportChannel;
import io.zeebe.transport.impl.TransportHeaderDescriptor;
import io.zeebe.transport.util.ControllableServerTransport;
import io.zeebe.transport.util.RecordingMessageHandler;
import io.zeebe.transport.util.TransportTestUtil;
import io.zeebe.util.actor.ActorScheduler;
import io.zeebe.util.actor.ActorSchedulerBuilder;
import io.zeebe.util.buffer.BufferUtil;
import io.zeebe.util.buffer.DirectBufferWriter;
import io.zeebe.util.time.ClockUtil;

public class ClientTransportTest
{

    public static final DirectBuffer BUF1 = BufferUtil.wrapBytes(1, 2, 3, 4);
    public static final SocketAddress SERVER_ADDRESS1 = new SocketAddress("localhost", 51115);
    public static final SocketAddress SERVER_ADDRESS2 = new SocketAddress("localhost", 51116);

    public static final int REQUEST_POOL_SIZE = 4;
    public static final int BUFFER_SIZE = 16 * 1024;

    @Rule
    public AutoCloseableRule closeables = new AutoCloseableRule();

    protected Dispatcher clientReceiveBuffer;

    protected ClientTransport clientTransport;
    protected ActorScheduler actorScheduler;

    @Before
    public void setUp()
    {
        actorScheduler = ActorSchedulerBuilder.createDefaultScheduler("test");
        closeables.manage(actorScheduler);

        final Dispatcher clientSendBuffer = Dispatchers.create("clientSendBuffer")
            .bufferSize(BUFFER_SIZE)
            .subscriptions(ClientTransportBuilder.SEND_BUFFER_SUBSCRIPTION_NAME)
            .actorScheduler(actorScheduler)
            .build();
        closeables.manage(clientSendBuffer);

        clientReceiveBuffer = Dispatchers.create("clientReceiveBuffer")
            .bufferSize(BUFFER_SIZE)
            .actorScheduler(actorScheduler)
            .build();
        closeables.manage(clientReceiveBuffer);

        clientTransport = Transports.newClientTransport()
                .sendBuffer(clientSendBuffer)
                .requestPoolSize(REQUEST_POOL_SIZE)
                .scheduler(actorScheduler)
                .messageReceiveBuffer(clientReceiveBuffer)
                .build();
        closeables.manage(clientTransport);
    }

    @After
    public void tearDown()
    {
        ClockUtil.reset();
    }

    protected ControllableServerTransport buildControllableServerTransport()
    {
        final ControllableServerTransport serverTransport = new ControllableServerTransport();
        closeables.manage(serverTransport);
        return serverTransport;
    }

    protected ServerTransport buildServerTransport(Function<ServerTransportBuilder, ServerTransport> builderConsumer)
    {
        final Dispatcher serverSendBuffer = Dispatchers.create("serverSendBuffer")
            .bufferSize(BUFFER_SIZE)
            .subscriptions(ServerTransportBuilder.SEND_BUFFER_SUBSCRIPTION_NAME)
            .actorScheduler(actorScheduler)
            .build();
        closeables.manage(serverSendBuffer);

        final ServerTransportBuilder transportBuilder = Transports.newServerTransport()
            .sendBuffer(serverSendBuffer)
            .scheduler(actorScheduler);

        final ServerTransport serverTransport = builderConsumer.apply(transportBuilder);
        closeables.manage(serverTransport);

        return serverTransport;
    }

    @Test
    public void shouldUseSameChannelForConsecutiveRequestsToSameRemote()
    {
        // given
        final ControllableServerTransport serverTransport = buildControllableServerTransport();
        serverTransport.listenOn(SERVER_ADDRESS1);

        final RemoteAddress remote = clientTransport.registerRemoteAddress(SERVER_ADDRESS1);
        final ClientOutput output = clientTransport.getOutput();

        output.sendRequest(remote, new DirectBufferWriter().wrap(BUF1));
        output.sendRequest(remote, new DirectBufferWriter().wrap(BUF1));

        final AtomicInteger messageCounter = serverTransport.acceptNextConnection(SERVER_ADDRESS1);

        // when
        TestUtil.doRepeatedly(() -> serverTransport.receive(SERVER_ADDRESS1))
            .until((r) -> messageCounter.get() == 2);

        // then
        assertThat(serverTransport.getClientChannels(SERVER_ADDRESS1)).hasSize(1);
    }

    @Test
    public void shouldUseDifferentChannelsForDifferentRemotes()
    {
        // given
        final ControllableServerTransport serverTransport = buildControllableServerTransport();
        serverTransport.listenOn(SERVER_ADDRESS1);
        serverTransport.listenOn(SERVER_ADDRESS2);
        final ClientOutput output = clientTransport.getOutput();

        final RemoteAddress remote1 = clientTransport.registerRemoteAddress(SERVER_ADDRESS1);
        final RemoteAddress remote2 = clientTransport.registerRemoteAddress(SERVER_ADDRESS2);

        output.sendRequest(remote1, new DirectBufferWriter().wrap(BUF1));
        output.sendRequest(remote2, new DirectBufferWriter().wrap(BUF1));

        // when
        final AtomicInteger messageCounter1 = serverTransport.acceptNextConnection(SERVER_ADDRESS1);
        final AtomicInteger messageCounter2 = serverTransport.acceptNextConnection(SERVER_ADDRESS2);

        // then
        TestUtil.doRepeatedly(() -> serverTransport.receive(SERVER_ADDRESS1))
            .until((r) -> messageCounter1.get() == 1);
        TestUtil.doRepeatedly(() -> serverTransport.receive(SERVER_ADDRESS2))
            .until((r) -> messageCounter2.get() == 1);

        assertThat(serverTransport.getClientChannels(SERVER_ADDRESS1)).hasSize(1);
        assertThat(serverTransport.getClientChannels(SERVER_ADDRESS2)).hasSize(1);
    }

    @Test
    public void shouldOpenNewChannelOnceChannelIsClosed()
    {
        // given
        final ControllableServerTransport serverTransport = buildControllableServerTransport();
        serverTransport.listenOn(SERVER_ADDRESS1);

        final RemoteAddress remote = clientTransport.registerRemoteAddress(SERVER_ADDRESS1);
        final ClientOutput output = clientTransport.getOutput();

        final ClientRequest request = output.sendRequest(remote, new DirectBufferWriter().wrap(BUF1));
        serverTransport.acceptNextConnection(SERVER_ADDRESS1);

        serverTransport.getClientChannels(SERVER_ADDRESS1).get(0).close();
        TestUtil.waitUntil(() -> request.isFailed());

        // when
        output.sendRequest(remote, new DirectBufferWriter().wrap(BUF1));

        // then
        final AtomicInteger messageCounter = serverTransport.acceptNextConnection(SERVER_ADDRESS1);

        TestUtil.doRepeatedly(() -> serverTransport.receive(SERVER_ADDRESS1))
            .until((r) -> messageCounter.get() == 1);

        // then
        assertThat(serverTransport.getClientChannels(SERVER_ADDRESS1)).hasSize(2);
    }

    @Test
    public void shouldCloseChannelsWhenTransportCloses()
    {
        // given
        final ControllableServerTransport serverTransport = buildControllableServerTransport();
        serverTransport.listenOn(SERVER_ADDRESS1);

        final RemoteAddress remote = clientTransport.registerRemoteAddress(SERVER_ADDRESS1);
        final ClientOutput output = clientTransport.getOutput();

        output.sendRequest(remote, new DirectBufferWriter().wrap(BUF1));
        final AtomicInteger messageCounter = serverTransport.acceptNextConnection(SERVER_ADDRESS1);
        TestUtil.doRepeatedly(() -> serverTransport.receive(SERVER_ADDRESS1)).until(i -> messageCounter.get() == 1);

        // when
        clientTransport.close();
        serverTransport.receive(SERVER_ADDRESS1); // receive once more to make server recognize that channel has closed

        // then
        final TransportChannel channel = serverTransport.getClientChannels(SERVER_ADDRESS1).get(0);
        TestUtil.waitUntil(() -> !channel.getNioChannel().isOpen());

        assertThat(serverTransport.getClientChannels(SERVER_ADDRESS1)).hasSize(1);
        assertThat(channel.getNioChannel().isOpen()).isFalse();
    }

    @Test
    public void shouldFailRequestWhenChannelDoesNotConnect()
    {
        // given
        final RemoteAddress remote = clientTransport.registerRemoteAddress(SERVER_ADDRESS1);
        final ClientOutput output = clientTransport.getOutput();

        // when
        final ClientRequest request = output.sendRequest(remote, new DirectBufferWriter().wrap(BUF1));

        // then
        TestUtil.waitUntil(() -> request.isFailed());

        assertThat(request.isFailed()).isTrue();

        try
        {
            request.get();
            fail("Should not resolve");
        }
        catch (Exception e)
        {
            assertThat(e).isInstanceOf(ExecutionException.class);
        }
    }

    @Test
    public void shouldNotOpenRequestWhenClienRequestPoolCapacityIsExceeded()
    {
        // given
        final ClientOutput clientOutput = clientTransport.getOutput();
        final RemoteAddress remoteAddress = clientTransport.registerRemoteAddress(SERVER_ADDRESS1);

        for (int i = 0; i < REQUEST_POOL_SIZE; i++)
        {
            clientOutput.sendRequest(remoteAddress, new DirectBufferWriter().wrap(BUF1));
        }

        // when
        final ClientRequest request = clientOutput.sendRequest(remoteAddress, new DirectBufferWriter().wrap(BUF1));

        // then
        assertThat(request).isNull();

    }

    @Test
    public void shouldReuseRequestOnceClosed()
    {
        // given
        final ControllableServerTransport serverTransport = buildControllableServerTransport();

        final ClientOutput clientOutput = clientTransport.getOutput();
        final RemoteAddress remoteAddress = clientTransport.registerRemoteAddress(SERVER_ADDRESS1);

        for (int i = 0; i < REQUEST_POOL_SIZE - 1; i++)
        {
            clientOutput.sendRequest(remoteAddress, new DirectBufferWriter().wrap(BUF1));
        }

        final ClientRequest request = clientOutput.sendRequest(remoteAddress, new DirectBufferWriter().wrap(BUF1));
        TestUtil.waitUntil(() -> request.isFailed());
        request.close();

        serverTransport.listenOn(SERVER_ADDRESS1); // don't let request fail next time

        // when
        final ClientRequest newRequest = clientOutput.sendRequest(remoteAddress, new DirectBufferWriter().wrap(BUF1));

        // then
        assertThat(newRequest).isNotNull();
        assertThat(newRequest).isSameAs(request); // testing object identity may be too strict from an API perspective but is good to identify technical issues

        // and the request state should be reset
        assertThat(newRequest.isDone()).isFalse();
        assertThat(newRequest.isFailed()).isFalse();
    }

    @Test
    public void shouldReturnRequestToPoolWhenBufferWriterFails()
    {
        // given
        final FailingBufferWriter failingWriter = new FailingBufferWriter();
        final DirectBufferWriter successfulWriter = new DirectBufferWriter().wrap(BUF1);

        final ClientOutput clientOutput = clientTransport.getOutput();
        final RemoteAddress remoteAddress = clientTransport.registerRemoteAddress(SERVER_ADDRESS1);

        for (int i = 0; i < REQUEST_POOL_SIZE; i++)
        {
            try
            {
                clientOutput.sendRequest(remoteAddress, failingWriter);
            }
            catch (FailingBufferWriterException e)
            {
                // expected
            }
        }

        // when
        final ClientRequest request = clientOutput.sendRequest(remoteAddress, successfulWriter);

        // then
        assertThat(request).isNotNull();
    }

    @Test
    public void shouldBeAbleToPostponeReceivedMessage()
    {
        // given
        final AtomicInteger numInvocations = new AtomicInteger(0);
        final AtomicBoolean consumeMessage = new AtomicBoolean(false);

        final ClientInputMessageSubscription subscription = clientTransport.openSubscription("foo", new ClientMessageHandler()
            {
                @Override
                public boolean onMessage(ClientOutput output, RemoteAddress remoteAddress, DirectBuffer buffer, int offset,
                        int length)
                {
                    numInvocations.incrementAndGet();
                    return consumeMessage.getAndSet(true);
                }
            })
            .join();

        // when simulating a received message
        doRepeatedly(() -> clientReceiveBuffer.offer(BUF1)).until(p -> p >= 0);

        // then handler has been invoked twice, once when the message was postponed, and once when it was consumed
        doRepeatedly(() -> subscription.poll()).until(i -> i != 0);
        assertThat(numInvocations.get()).isEqualTo(2);
    }

    @Test
    public void shouldPostponeMessagesOnReceiveBufferBackpressure() throws InterruptedException
    {
        // given
        final int maximumMessageLength = clientReceiveBuffer.getMaxFrameLength()
                - TransportHeaderDescriptor.HEADER_LENGTH
                - 1; // https://github.com/zeebe-io/zb-dispatcher/issues/21

        final DirectBuffer largeBuf = new UnsafeBuffer(new byte[maximumMessageLength]);

        final int messagesToExhaustReceiveBuffer = (BUFFER_SIZE / largeBuf.capacity()) + 1;
        final SendMessagesHandler handler = new SendMessagesHandler(messagesToExhaustReceiveBuffer, largeBuf);

        buildServerTransport(
            b ->
                b.bindAddress(SERVER_ADDRESS1.toInetSocketAddress())
                .build(handler, null));

        final RecordingMessageHandler clientHandler = new RecordingMessageHandler();
        final ClientInputMessageSubscription clientSubscription = clientTransport.openSubscription("foo", clientHandler).join();

        // triggering the server pushing a the messages
        final RemoteAddress remote = clientTransport.registerRemoteAddress(SERVER_ADDRESS1);
        clientTransport.getOutput().sendMessage(new TransportMessage().remoteAddress(remote).buffer(BUF1));

        TransportTestUtil.waitUntilExhausted(clientReceiveBuffer);
        Thread.sleep(200L); // give transport a bit of time to try to push more messages on top

        // when
        final AtomicInteger receivedMessages = new AtomicInteger(0);
        doRepeatedly(() ->
        {
            final int polledMessages = clientSubscription.poll();
            return receivedMessages.addAndGet(polledMessages);
        }).until(m -> m == messagesToExhaustReceiveBuffer);

        // then
        assertThat(receivedMessages.get()).isEqualTo(messagesToExhaustReceiveBuffer);
    }

    protected class SendMessagesHandler implements ServerMessageHandler
    {
        final int numMessagesToSend;
        int messagesSent;
        final TransportMessage message;

        public SendMessagesHandler(int numMessagesToSend, DirectBuffer messageToSend)
        {
            this.numMessagesToSend = numMessagesToSend;
            this.messagesSent = 0;
            this.message = new TransportMessage().buffer(messageToSend);
        }

        @Override
        public boolean onMessage(ServerOutput output, RemoteAddress remoteAddress, DirectBuffer buffer, int offset,
                int length)
        {
            message.remoteAddress(remoteAddress);
            for (int i = messagesSent; i < numMessagesToSend; i++)
            {
                if (output.sendMessage(message))
                {
                    messagesSent++;
                }
                else
                {
                    return false;
                }
            }

            return true;
        }

    }



}
