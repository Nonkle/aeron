/*
 * Copyright 2014-2021 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.*;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.codecs.*;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusterMarkFile;
import io.aeron.cluster.service.ClusterTerminationException;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.security.DefaultAuthenticatorSupplier;
import io.aeron.status.ReadableCounter;
import io.aeron.test.Tests;
import io.aeron.test.cluster.TestClusterClock;
import org.agrona.DirectBuffer;
import org.agrona.collections.MutableLong;
import org.agrona.concurrent.*;
import org.agrona.concurrent.status.AtomicCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import java.util.concurrent.TimeUnit;

import static io.aeron.cluster.ClusterControl.ToggleState.*;
import static io.aeron.cluster.ConsensusModule.Configuration.SESSION_LIMIT_MSG;
import static io.aeron.cluster.ConsensusModuleAgent.SLOW_TICK_INTERVAL_NS;
import static io.aeron.cluster.client.AeronCluster.Configuration.PROTOCOL_SEMANTIC_VERSION;
import static io.aeron.protocol.DataHeaderFlyweight.HEADER_LENGTH;
import static java.lang.Boolean.TRUE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ConsensusModuleAgentTest
{
    private static final long SLOW_TICK_INTERVAL_MS = TimeUnit.NANOSECONDS.toMillis(SLOW_TICK_INTERVAL_NS);
    private static final String RESPONSE_CHANNEL_ONE = "aeron:udp?endpoint=localhost:11111";
    private static final String RESPONSE_CHANNEL_TWO = "aeron:udp?endpoint=localhost:22222";

    private final EgressPublisher mockEgressPublisher = mock(EgressPublisher.class);
    private final LogPublisher mockLogPublisher = mock(LogPublisher.class);
    private final Aeron mockAeron = mock(Aeron.class);
    private final ConcurrentPublication mockResponsePublication = mock(ConcurrentPublication.class);
    private final ExclusivePublication mockExclusivePublication = mock(ExclusivePublication.class);
    private final Counter mockTimedOutClientCounter = mock(Counter.class);

    private final ConsensusModule.Context ctx = new ConsensusModule.Context()
        .errorHandler(Tests::onError)
        .errorCounter(mock(AtomicCounter.class))
        .moduleStateCounter(mock(Counter.class))
        .commitPositionCounter(mock(Counter.class))
        .controlToggleCounter(mock(Counter.class))
        .clusterNodeRoleCounter(mock(Counter.class))
        .timedOutClientCounter(mockTimedOutClientCounter)
        .idleStrategySupplier(NoOpIdleStrategy::new)
        .aeron(mockAeron)
        .clusterMemberId(0)
        .authenticatorSupplier(new DefaultAuthenticatorSupplier())
        .clusterMarkFile(mock(ClusterMarkFile.class))
        .archiveContext(new AeronArchive.Context())
        .logPublisher(mockLogPublisher)
        .egressPublisher(mockEgressPublisher);

    @BeforeEach
    public void before()
    {
        when(mockAeron.conductorAgentInvoker()).thenReturn(mock(AgentInvoker.class));
        when(mockEgressPublisher.sendEvent(any(), anyLong(), anyInt(), any(), any())).thenReturn(TRUE);
        when(mockLogPublisher.appendSessionClose(any(), anyLong(), anyLong())).thenReturn(TRUE);
        when(mockLogPublisher.appendSessionOpen(any(), anyLong(), anyLong())).thenReturn(128L);
        when(mockLogPublisher.appendClusterAction(anyLong(), anyLong(), any(ClusterAction.class)))
            .thenReturn(TRUE);
        when(mockAeron.addPublication(anyString(), anyInt())).thenReturn(mockResponsePublication);
        when(mockAeron.addExclusivePublication(anyString(), anyInt())).thenReturn(mockExclusivePublication);
        when(mockAeron.addSubscription(anyString(), anyInt())).thenReturn(mock(Subscription.class));
        when(mockAeron.addSubscription(anyString(), anyInt(), eq(null), any(UnavailableImageHandler.class)))
            .thenReturn(mock(Subscription.class));
        when(mockResponsePublication.isConnected()).thenReturn(TRUE);
    }

    @Test
    public void shouldLimitActiveSessions()
    {
        final TestClusterClock clock = new TestClusterClock(TimeUnit.MILLISECONDS);
        ctx.maxConcurrentSessions(1)
            .epochClock(clock)
            .clusterClock(clock);

        final ConsensusModuleAgent agent = new ConsensusModuleAgent(ctx);

        final long correlationIdOne = 1L;
        agent.state(ConsensusModule.State.ACTIVE);
        agent.role(Cluster.Role.LEADER);
        Tests.setField(agent, "appendPosition", mock(ReadableCounter.class));
        agent.onSessionConnect(correlationIdOne, 2, PROTOCOL_SEMANTIC_VERSION, RESPONSE_CHANNEL_ONE, new byte[0]);

        clock.update(17, TimeUnit.MILLISECONDS);
        agent.doWork();

        verify(mockLogPublisher).appendSessionOpen(any(ClusterSession.class), anyLong(), anyLong());

        final long correlationIdTwo = 2L;
        agent.onSessionConnect(correlationIdTwo, 3, PROTOCOL_SEMANTIC_VERSION, RESPONSE_CHANNEL_TWO, new byte[0]);
        clock.update(clock.time() + 10L, TimeUnit.MILLISECONDS);
        agent.doWork();

        verify(mockEgressPublisher).sendEvent(
            any(ClusterSession.class), anyLong(), anyInt(), eq(EventCode.ERROR), eq(SESSION_LIMIT_MSG));
    }

    @Test
    public void shouldCloseInactiveSession()
    {
        final TestClusterClock clock = new TestClusterClock(TimeUnit.MILLISECONDS);
        final long startMs = SLOW_TICK_INTERVAL_MS;
        clock.update(startMs, TimeUnit.MILLISECONDS);

        ctx.epochClock(clock)
            .clusterClock(clock);

        final ConsensusModuleAgent agent = new ConsensusModuleAgent(ctx);

        final long correlationId = 1L;
        agent.state(ConsensusModule.State.ACTIVE);
        agent.role(Cluster.Role.LEADER);
        Tests.setField(agent, "appendPosition", mock(ReadableCounter.class));
        agent.onSessionConnect(correlationId, 2, PROTOCOL_SEMANTIC_VERSION, RESPONSE_CHANNEL_ONE, new byte[0]);

        agent.doWork();

        verify(mockLogPublisher).appendSessionOpen(any(ClusterSession.class), anyLong(), eq(startMs));

        final long timeMs = startMs + TimeUnit.NANOSECONDS.toMillis(ConsensusModule.Configuration.sessionTimeoutNs());
        clock.update(timeMs, TimeUnit.MILLISECONDS);
        agent.doWork();

        final long timeoutMs = timeMs + SLOW_TICK_INTERVAL_MS;
        clock.update(timeoutMs, TimeUnit.MILLISECONDS);
        agent.doWork();

        verify(mockTimedOutClientCounter).incrementOrdered();
        verify(mockLogPublisher).appendSessionClose(any(ClusterSession.class), anyLong(), eq(timeoutMs));
        verify(mockEgressPublisher).sendEvent(
            any(ClusterSession.class), anyLong(), anyInt(), eq(EventCode.CLOSED), eq(CloseReason.TIMEOUT.name()));
    }

    @Test
    public void shouldCloseTerminatedSession()
    {
        final TestClusterClock clock = new TestClusterClock(TimeUnit.MILLISECONDS);
        final long startMs = SLOW_TICK_INTERVAL_MS;
        clock.update(startMs, TimeUnit.MILLISECONDS);

        ctx.epochClock(clock)
            .clusterClock(clock);

        final ConsensusModuleAgent agent = new ConsensusModuleAgent(ctx);

        final long correlationId = 1L;
        agent.state(ConsensusModule.State.ACTIVE);
        agent.role(Cluster.Role.LEADER);
        Tests.setField(agent, "appendPosition", mock(ReadableCounter.class));
        agent.onSessionConnect(correlationId, 2, PROTOCOL_SEMANTIC_VERSION, RESPONSE_CHANNEL_ONE, new byte[0]);

        agent.doWork();

        final ArgumentCaptor<ClusterSession> sessionCaptor = ArgumentCaptor.forClass(ClusterSession.class);

        verify(mockLogPublisher).appendSessionOpen(sessionCaptor.capture(), anyLong(), eq(startMs));

        final long timeMs = startMs + SLOW_TICK_INTERVAL_MS;
        clock.update(timeMs, TimeUnit.MILLISECONDS);
        agent.doWork();

        agent.onServiceCloseSession(sessionCaptor.getValue().id());

        verify(mockLogPublisher).appendSessionClose(any(ClusterSession.class), anyLong(), eq(timeMs));
        verify(mockEgressPublisher).sendEvent(
            any(ClusterSession.class),
            anyLong(),
            anyInt(),
            eq(EventCode.CLOSED),
            eq(CloseReason.SERVICE_ACTION.name()));
    }

    @Test
    public void shouldSuspendThenResume()
    {
        final TestClusterClock clock = new TestClusterClock(TimeUnit.MILLISECONDS);

        final MutableLong stateValue = new MutableLong();
        final Counter mockState = mock(Counter.class);
        when(mockState.get()).thenAnswer((invocation) -> stateValue.value);
        doAnswer(
            (invocation) ->
            {
                stateValue.value = invocation.getArgument(0);
                return null;
            })
            .when(mockState).set(anyLong());

        final MutableLong controlValue = new MutableLong(NEUTRAL.code());
        final Counter mockControlToggle = mock(Counter.class);
        when(mockControlToggle.get()).thenAnswer((invocation) -> controlValue.value);

        doAnswer(
            (invocation) ->
            {
                controlValue.value = invocation.getArgument(0);
                return null;
            })
            .when(mockControlToggle).set(anyLong());

        doAnswer(
            (invocation) ->
            {
                final long expected = invocation.getArgument(0);
                if (expected == controlValue.value)
                {
                    controlValue.value = invocation.getArgument(1);
                    return true;
                }
                return false;
            })
            .when(mockControlToggle).compareAndSet(anyLong(), anyLong());

        ctx.moduleStateCounter(mockState);
        ctx.controlToggleCounter(mockControlToggle);
        ctx.epochClock(clock).clusterClock(clock);

        final ConsensusModuleAgent agent = new ConsensusModuleAgent(ctx);
        Tests.setField(agent, "appendPosition", mock(ReadableCounter.class));

        assertEquals(ConsensusModule.State.INIT.code(), stateValue.get());

        agent.state(ConsensusModule.State.ACTIVE);
        agent.role(Cluster.Role.LEADER);
        assertEquals(ConsensusModule.State.ACTIVE.code(), stateValue.get());

        SUSPEND.toggle(mockControlToggle);
        clock.update(SLOW_TICK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        agent.doWork();

        assertEquals(ConsensusModule.State.SUSPENDED.code(), stateValue.get());
        assertEquals(SUSPEND.code(), controlValue.get());

        RESUME.toggle(mockControlToggle);
        clock.update(SLOW_TICK_INTERVAL_MS * 2, TimeUnit.MILLISECONDS);
        agent.doWork();

        assertEquals(ConsensusModule.State.ACTIVE.code(), stateValue.get());
        assertEquals(NEUTRAL.code(), controlValue.get());

        final InOrder inOrder = Mockito.inOrder(mockLogPublisher);
        inOrder.verify(mockLogPublisher).appendClusterAction(anyLong(), anyLong(), eq(ClusterAction.SUSPEND));
        inOrder.verify(mockLogPublisher).appendClusterAction(anyLong(), anyLong(), eq(ClusterAction.RESUME));
    }

    @Test
    public void shouldThrowClusterTerminationExceptionUponShutdown()
    {
        final TestClusterClock clock = new TestClusterClock(TimeUnit.MILLISECONDS);

        final CountedErrorHandler countedErrorHandler = mock(CountedErrorHandler.class);
        final MutableLong stateValue = new MutableLong();
        final Counter mockState = mock(Counter.class);
        final Runnable terminationHook = mock(Runnable.class);
        when(mockState.get()).thenAnswer((invocation) -> stateValue.value);
        doAnswer(
            (invocation) ->
            {
                stateValue.value = invocation.getArgument(0);
                return null;
            })
            .when(mockState).set(anyLong());

        final OutOfMemoryError hookException = new OutOfMemoryError("Hook exception!");
        doThrow(hookException).when(terminationHook).run();

        ctx.countedErrorHandler(countedErrorHandler)
            .moduleStateCounter(mockState)
            .epochClock(clock)
            .clusterClock(clock)
            .terminationHook(terminationHook);

        final ConsensusModuleAgent agent = new ConsensusModuleAgent(ctx);
        agent.state(ConsensusModule.State.QUITTING);

        assertThrows(ClusterTerminationException.class,
            () -> agent.onServiceAck(1024, 100, 0, 55, 0));

        verify(countedErrorHandler).onError(hookException);
    }

    @Test
    void shouldEmitTermBaseOffsetFromCurrentTerm()
    {
        final long followerLogLeadershipTermId = 1;
        final long leaderLogPosition = 1000;
        final TestClusterClock clock = new TestClusterClock(TimeUnit.MILLISECONDS);
        final String members =
            "0,localhost:10001,localhost:10002,localhost:10003,localhost:10004,localhost:10004|" +
            "1,localhost:10101,localhost:10102,localhost:10103,localhost:10104,localhost:10104|" +
            "2,localhost:10201,localhost:10202,localhost:10203,localhost:10204,localhost:10204|";
        final RecordingLog recordingLog = mock(RecordingLog.class);
        final RecordingLog.Entry[] entries = {
            new RecordingLog.Entry(0, 0, 0, 250, 1000, 0, RecordingLog.ENTRY_TYPE_TERM, true, 0),
            new RecordingLog.Entry(0, 1, 250, 500, 2000, 0, RecordingLog.ENTRY_TYPE_TERM, true, 1),
            new RecordingLog.Entry(0, 2, 500, 750, 3000, 0, RecordingLog.ENTRY_TYPE_TERM, true, 2),
            new RecordingLog.Entry(0, 3, 750, 1000, 3000, 0, RecordingLog.ENTRY_TYPE_TERM, true, 3),
        };

        final int currentLeadershipTermId = entries.length - 1;
        final RecordingLog.Entry currentTerm = entries[currentLeadershipTermId];
        final RecordingLog.Entry followerNextLeadershipTerm = entries[(int)followerLogLeadershipTermId + 1];

        final SbeMessageValidator sbeMessageValidator = new SbeMessageValidator(
            new MessageHeaderEncoder(),
            new MessageHeaderDecoder(),
            new NewLeadershipTermEncoder(),
            new NewLeadershipTermDecoder());

        when(recordingLog.findTermEntry(anyLong()))
            .thenAnswer(invocation -> entries[(int)(long)invocation.getArgument(0)]);
        when(mockExclusivePublication.tryClaim(anyInt(), any())).thenAnswer(
            invocation -> sbeMessageValidator.forBufferClaim(invocation.getArgument(0), invocation.getArgument(1)));
        when(mockLogPublisher.position()).thenReturn(leaderLogPosition);

        ctx.maxConcurrentSessions(1)
            .clusterMembers(members)
            .recordingLog(recordingLog)
            .epochClock(clock)
            .clusterClock(clock);

        final ConsensusModuleAgent agent = new ConsensusModuleAgent(ctx);
        agent.role(Cluster.Role.LEADER);
        agent.leadershipTermId(currentLeadershipTermId);
        agent.logRecordingId(currentTerm.recordingId);
        agent.onCanvassPosition(followerLogLeadershipTermId, 300, 2);

        sbeMessageValidator.body()
            .logLeadershipTermId(followerLogLeadershipTermId)
            .logTruncatePosition(followerNextLeadershipTerm.termBaseLogPosition)
            .leadershipTermId(currentLeadershipTermId)
            .termBaseLogPosition(currentTerm.termBaseLogPosition)
            .logPosition(leaderLogPosition)
            .leaderRecordingId(currentTerm.recordingId)
            .timestamp(currentTerm.timestamp)
            .leaderMemberId(0)
            .logSessionId(0)
            .isStartup(BooleanType.FALSE);

        sbeMessageValidator.assertBuffersMatch();
    }

    private static class SbeMessageValidator
    {
        private final AtomicBuffer inputBuffer = new UnsafeBuffer(new byte[1024 * 1024]);
        private final AtomicBuffer expectedBuffer = new UnsafeBuffer(new byte[1024 * 1024]);
        private final MessageHeaderEncoder headerEncoder;
        private final MessageHeaderDecoder headerDecoder;
        private final NewLeadershipTermEncoder encoder;
        private final NewLeadershipTermDecoder decoder;

        SbeMessageValidator(
            final MessageHeaderEncoder headerEncoder,
            final MessageHeaderDecoder headerDecoder,
            final NewLeadershipTermEncoder encoder,
            final NewLeadershipTermDecoder decoder)
        {
            this.headerEncoder = headerEncoder;
            this.headerDecoder = headerDecoder;
            this.encoder = encoder;
            this.decoder = decoder;

            reset();
            encoder.wrapAndApplyHeader(expectedBuffer, HEADER_LENGTH, headerEncoder);
        }

        public void reset()
        {
            inputBuffer.setMemory(0, inputBuffer.capacity(), (byte)0);
            expectedBuffer.setMemory(0, inputBuffer.capacity(), (byte)0);
        }

        public MessageHeaderEncoder header()
        {
            return headerEncoder;
        }

        public NewLeadershipTermEncoder body()
        {
            return encoder;
        }

        public AtomicBuffer inputBuffer()
        {
            return inputBuffer;
        }

        public void assertBuffersMatch()
        {
            final int totalLength = HEADER_LENGTH + headerEncoder.encodedLength() + encoder.encodedLength();
            for (int i = 0; i < totalLength; i++)
            {
                if (inputBuffer.getByte(i) != expectedBuffer.getByte(i))
                {
                    final String expectedString = toString(expectedBuffer);
                    final String actualString = toString(inputBuffer);
                    assertEquals(expectedString, actualString, "Buffers differ at index: " + i);
                }
            }
        }

        private String toString(final DirectBuffer buffer)
        {
            headerDecoder.wrap(buffer, HEADER_LENGTH);
            decoder.wrap(
                buffer,
                headerDecoder.offset() + headerDecoder.encodedLength(),
                headerDecoder.blockLength(),
                headerDecoder.version());
            return decoder.toString();
        }

        public long forBufferClaim(final int length, final BufferClaim bufferClaim)
        {
            final int frameLength = HEADER_LENGTH + length;
            bufferClaim.wrap(inputBuffer, 0, frameLength);
            return frameLength;
        }
    }
}
