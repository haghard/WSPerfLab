package perf.test.netty.server.tests;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import perf.test.netty.PropertyNames;
import perf.test.netty.client.PoolExhaustedException;
import perf.test.netty.server.RequestProcessingFailedException;
import perf.test.netty.server.RequestProcessingPromise;
import perf.test.utils.ServiceResponseBuilder;
import perf.test.utils.netty.SourceRequestState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Nitesh Kant (nkant@netflix.com)
 */
public class TestCaseA extends TestCaseHandler {

    private static final Logger logger = LoggerFactory.getLogger(TestCaseA.class);

    public static final String CALL_A_URI_WITHOUT_ID = constructUri("A",
            PropertyNames.TestCaseACallANumItems.getValueAsInt(),
            PropertyNames.TestCaseACallAItemSize.getValueAsInt(),
            PropertyNames.TestCaseACallAItemDelay.getValueAsInt());

    public static final String CALL_B_URI_WITHOUT_ID = constructUri("B",
            PropertyNames.TestCaseACallBNumItems.getValueAsInt(),
            PropertyNames.TestCaseACallBItemSize.getValueAsInt(),
            PropertyNames.TestCaseACallBItemDelay.getValueAsInt());

    public static final String CALL_C_URI_WITHOUT_ID = constructUri("C",
            PropertyNames.TestCaseACallCNumItems.getValueAsInt(),
            PropertyNames.TestCaseACallCItemSize.getValueAsInt(),
            PropertyNames.TestCaseACallCItemDelay.getValueAsInt());

    public static final String CALL_D_URI_WITHOUT_ID = constructUri("D",
            PropertyNames.TestCaseACallDNumItems.getValueAsInt(),
            PropertyNames.TestCaseACallDItemSize.getValueAsInt(),
            PropertyNames.TestCaseACallDItemDelay.getValueAsInt());

    public static final String CALL_E_URI_WITHOUT_ID = constructUri("E",
            PropertyNames.TestCaseACallENumItems.getValueAsInt(),
            PropertyNames.TestCaseACallEItemSize.getValueAsInt(),
            PropertyNames.TestCaseACallEItemDelay.getValueAsInt());



    public TestCaseA(EventLoopGroup eventLoopGroup) throws PoolExhaustedException {
        super("testA", eventLoopGroup);
    }

    @Override
    protected void executeTestCase(Channel channel, final EventExecutor executor, final boolean keepAlive, String id,
                                   final RequestProcessingPromise requestProcessingPromise) {

        final String reqId = SourceRequestState.instance().getRequestId(channel);

        final ResponseCollector responseCollector = new ResponseCollector();

        final MoveForwardBarrier topLevelMoveFwdBarrier = new MoveForwardBarrier(2);

        CompletionListener callAListener =
                new CompletionListener(responseCollector, ResponseCollector.RESPONSE_A_INDEX, requestProcessingPromise) {

                    @Override
                    protected void onResponseReceived() {
                        final MoveForwardBarrier callAMoveFwdBarrier = new MoveForwardBarrier(2);

                        CompletionListener callCListener =
                                new CompletionListener(responseCollector, ResponseCollector.RESPONSE_C_INDEX,
                                                       requestProcessingPromise) {

                                    @Override
                                    protected void onResponseReceived() {
                                        if (callAMoveFwdBarrier.shouldProceedOnResponse() && topLevelMoveFwdBarrier
                                                .shouldProceedOnResponse()) {
                                            buildFinalResponseAndFinish(responseCollector, requestProcessingPromise);
                                        }
                                    }
                                };

                        CompletionListener callDListener =
                                new CompletionListener(responseCollector, ResponseCollector.RESPONSE_D_INDEX,
                                                       requestProcessingPromise) {

                                    @Override
                                    protected void onResponseReceived() {
                                        if (callAMoveFwdBarrier.shouldProceedOnResponse() && topLevelMoveFwdBarrier
                                                .shouldProceedOnResponse()) {
                                            buildFinalResponseAndFinish(responseCollector, requestProcessingPromise);
                                        }
                                    }
                                };

                        get(reqId, executor,
                            CALL_C_URI_WITHOUT_ID
                            + responseCollector.responses[ResponseCollector.RESPONSE_A_INDEX].getResponseKey(),
                            callCListener, requestProcessingPromise, ResponseCollector.RESPONSE_C_INDEX);
                        get(reqId, executor,
                            CALL_D_URI_WITHOUT_ID
                            + responseCollector.responses[ResponseCollector.RESPONSE_A_INDEX].getResponseKey(),
                            callDListener, requestProcessingPromise, ResponseCollector.RESPONSE_D_INDEX);
                    }
                };

        CompletionListener callBListener =
                new CompletionListener(responseCollector, ResponseCollector.RESPONSE_B_INDEX,
                                       requestProcessingPromise) {
                    @Override
                    protected void onResponseReceived() {
                        CompletionListener callEListener =
                                new CompletionListener(responseCollector, ResponseCollector.RESPONSE_E_INDEX,
                                                       requestProcessingPromise) {

                                    @Override
                                    protected void onResponseReceived() {
                                        if (topLevelMoveFwdBarrier.shouldProceedOnResponse()) {
                                            buildFinalResponseAndFinish(responseCollector, requestProcessingPromise);
                                        }
                                    }
                                };
                        get(reqId, executor,
                            CALL_E_URI_WITHOUT_ID
                            + responseCollector.responses[ResponseCollector.RESPONSE_B_INDEX].getResponseKey(),
                            callEListener, requestProcessingPromise, ResponseCollector.RESPONSE_E_INDEX);
                    }
                };
        get(reqId, executor, CALL_A_URI_WITHOUT_ID + id, callAListener, requestProcessingPromise, ResponseCollector.RESPONSE_A_INDEX);
        get(reqId, executor, CALL_B_URI_WITHOUT_ID + id, callBListener, requestProcessingPromise, ResponseCollector.RESPONSE_B_INDEX);
    }

    protected void get(String reqId, EventExecutor eventExecutor, String path,
                                           GenericFutureListener<Future<FullHttpResponse>> responseHandler,
                                           final RequestProcessingPromise requestProcessingPromise, int callIndex) {
        if (PropertyNames.ServerTraceRequests.getValueAsBoolean()) {
            requestProcessingPromise.checkpoint("Sending request for call index: " + callIndex);
        }
        get(reqId, eventExecutor, path, responseHandler);
    }

    protected static void buildFinalResponseAndFinish(ResponseCollector responseCollector,
                                                    Promise<FullHttpResponse> requestProcessingPromise) {
        ByteArrayOutputStream outputStream;
        try {
            outputStream = ServiceResponseBuilder.buildTestAResponse(jsonFactory, responseCollector.responses);
            ByteBuf content = Unpooled.copiedBuffer(outputStream.toByteArray());
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
            requestProcessingPromise.trySuccess(response);
        } catch (IOException e) {
            requestProcessingPromise.tryFailure(new RequestProcessingFailedException(HttpResponseStatus.INTERNAL_SERVER_ERROR, e));
        }
    }


    private static class MoveForwardBarrier {

        private final int expectedCalls;
        private final AtomicInteger responseReceivedCounter;

        private MoveForwardBarrier(int expectedCalls) {
            this.expectedCalls = expectedCalls;
            responseReceivedCounter = new AtomicInteger();
        }

        boolean shouldProceedOnResponse() {
            int responseCount = responseReceivedCounter.incrementAndGet();
            return responseCount >= expectedCalls;
        }
    }



}
