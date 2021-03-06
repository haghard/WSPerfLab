package perf.test.netty.client;

import com.google.common.base.Preconditions;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import perf.test.netty.PropertyNames;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Nitesh Kant
 */
class DedicatedHttpClient<T, R extends HttpRequest> {

    private final Channel channel;
    private final String host;
    private final DedicatedClientPool<T, R> owningPool;

    DedicatedHttpClient(Channel channel, String host, DedicatedClientPool<T, R> owningPool) {
        this.channel = channel;
        this.host = host;
        this.owningPool = owningPool;
    }

    RequestExecutionPromise<T> execute(R request, HttpClientImpl.RequestProcessingPromise processingFinishPromise) {
        return executeRequest(request, new ResponseHandlerWrapper<T>(request, processingFinishPromise), 0,
                              null);
    }

    RequestExecutionPromise<T> retry(final ChannelHandlerContext failedContext, int retryCount,
                                     RequestExecutionPromise<T> completionPromise) {
        Preconditions.checkNotNull(completionPromise, "Completion promise can not be null for retries.");
        ResponseHandlerWrapper<T> handler =
                (ResponseHandlerWrapper<T>) failedContext.channel().attr(owningPool.getResponseHandlerKey()).get();
        return executeRequest(handler.request, handler, retryCount, completionPromise);
    }

    private RequestExecutionPromise<T> executeRequest(R request, final ResponseHandlerWrapper<T> responseHandler,
                                                      int retryCount,
                                                      @Nullable RequestExecutionPromise<T> completionPromise) {

        request.headers().set(HttpHeaders.Names.HOST, host);
        channel.attr(DedicatedClientPool.RETRY_COUNT_KEY).setIfAbsent(new AtomicInteger(retryCount));
        channel.attr(owningPool.getResponseHandlerKey()).set(responseHandler);
        ChannelFuture writeFuture = channel.writeAndFlush(request);
        writeFuture.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    if (PropertyNames.ServerTraceRequests.getValueAsBoolean()) {
                        responseHandler.processingFinishPromise.checkpoint("Write success.");
                    }
                } else {
                    responseHandler.processingFinishPromise.tryFailure(future.cause()); // TODO: See if we can retry.
                    if (PropertyNames.ServerTraceRequests.getValueAsBoolean()) {
                        responseHandler.processingFinishPromise.checkpoint("Write failed." + future.cause());
                    }
                }
            }
        });
        responseHandler.processingFinishPromise.checkpoint("Request Written. Retry count: " + retryCount);
        RequestExecutionPromise<T> processingCompletePromise;
        if (null == completionPromise) {
            processingCompletePromise = new RequestProcessingPromise<T>(channel, writeFuture);
        } else {
            processingCompletePromise = completionPromise;
        }
        channel.attr(owningPool.getProcessingCompletePromiseKey()).set(processingCompletePromise);

        processingCompletePromise.addListener(responseHandler);
        return processingCompletePromise;
    }

    public boolean isActive() {
        return channel.isActive();
    }

    public class ResponseHandlerWrapper<T> implements GenericFutureListener<Future<T>> {

        private final R request;
        private final HttpClientImpl.RequestProcessingPromise processingFinishPromise;

        ResponseHandlerWrapper(R request, HttpClientImpl.RequestProcessingPromise processingFinishPromise) {
            this.request = request;
            this.processingFinishPromise = processingFinishPromise;
        }

        @Override
        public void operationComplete(Future<T> future) throws Exception {
            owningPool.returnClient(DedicatedHttpClient.this);
        }

        public HttpClientImpl.RequestProcessingPromise getProcessingFinishPromise() {
            return processingFinishPromise;
        }
    }

    class RequestProcessingPromise<T> extends DefaultPromise<T> implements RequestExecutionPromise<T> {

        private final ChannelFuture sendRequestFuture;

        public RequestProcessingPromise(Channel channel, ChannelFuture sendRequestFuture) {
            super(channel.eventLoop()); // Retry will switch the eventloop as the promise returned will be of the first channel used (which failed)
            this.sendRequestFuture = sendRequestFuture;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (sendRequestFuture.isCancellable()) {
                sendRequestFuture.cancel(mayInterruptIfRunning);
            }
            return super.cancel(mayInterruptIfRunning);
        }

        @Override
        public EventExecutor getExecutingClientExecutor() {
            return channel.eventLoop();
        }
    }
}
