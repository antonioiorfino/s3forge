package it.iorfino.s3forge;

import com.sun.net.httpserver.HttpServer;
import it.iorfino.s3forge.config.S3ForgeConfig;
import it.iorfino.s3forge.http.Router;
import it.iorfino.s3forge.store.Store;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

final class S3ForgeServer {

    private final S3ForgeConfig config;
    private final Store store;
    private HttpServer server;

    S3ForgeServer(S3ForgeConfig config, Store store) {
        this.config = config;
        this.store = store;
    }

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        // Java 21: un virtual thread per richiesta
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", new Router(store, config));
        server.start();
    }

    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    int port() {
        return server.getAddress().getPort();
    }
}
