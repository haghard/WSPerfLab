package perf.test.netty.server.tests;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import perf.test.netty.client.PoolExhaustedException;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A registry of all {@link TestCaseHandler} implementations.
 *
 * @author Nitesh Kant (nkant@netflix.com)
 */
public class TestRegistry {

    private static final Map<String, TestCaseHandler> handlers = new ConcurrentHashMap<String, TestCaseHandler>();

    /**
     * Registers all the handlers, which typically means bootstrapping the client pool.
     *
     * @throws InterruptedException If the client pool (if initialized) in the underlying client could not startup all
     * the connections and was interrupted.
     * @param eventLoopGroup
     */
    public static synchronized void init(EventLoopGroup eventLoopGroup) throws PoolExhaustedException {
        TestCaseA caseA = new TestCaseA(eventLoopGroup);
        handlers.put(caseA.getTestCaseName(), caseA);
    }

    public static synchronized void shutdown() {
        for (TestCaseHandler testCaseHandler : handlers.values()) {
            testCaseHandler.dispose();
        }
    }

    public static TestCaseHandler getHandler(String name) {
        return handlers.get(name);
    }

    public static Collection<TestCaseHandler> getAllHandlers() {
        return handlers.values();
    }
}
