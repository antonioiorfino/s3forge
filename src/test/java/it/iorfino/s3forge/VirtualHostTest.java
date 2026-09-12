package it.iorfino.s3forge;

import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for virtual-host style addressing.
 *
 * <p>Instead of using the AWS SDK (which would require DNS configuration for
 * {@code <bucket>.localhost}), these tests exercise the HTTP layer directly
 * with the JDK {@link HttpClient}. This is sufficient to verify that the
 * server correctly extracts the bucket name from the {@code Host} header and
 * routes requests accordingly.</p>
 *
 * <p>The tests assume that {@code *.localhost} resolves to the loopback
 * address, as mandated by RFC 6761. This holds on Linux and macOS out of the
 * box, and on Windows 10+ in most configurations.</p>
 *
 * @since 0.1.0
 */
class VirtualHostTest {

    private static S3Forge forge;
    private static CloseableHttpClient http;
    private static int port;

    @BeforeAll
    static void setup() throws IOException {
        forge = S3Forge.builder()
            .port(0)
            .inMemory()
            .virtualHostDomain("localhost")
            .build();
        forge.start();
        port = forge.port();
        http = HttpClients.createDefault();
    }

    @AfterAll
    static void teardown() {
        if (forge != null) forge.close();
    }

    private HttpResponse<String> send(String method, String host, String path,
                                      String body) throws Exception {
        URI uri = URI.create("http://" + host + ":" + port + path);
        ClassicHttpRequest request;
        switch (method) {
            case "PUT" -> request = new HttpPut(uri);
            case "POST" -> request = new HttpPost(uri);
            case "DELETE" -> request = new HttpDelete(uri);
            default -> request = new HttpGet(uri);
        }

        request.setHeader("Host", host + ":" + port);

        if (body != null && ("PUT".equals(method) || "POST".equals(method))) {
            request.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
        }

        return http.execute(request, response -> {
            var entity = response.getEntity();
            String responseBody = entity == null
                ? ""
                : EntityUtils.toString(entity, StandardCharsets.UTF_8);
            int status = response.getCode();
            URI finalUri = uri;

            return new HttpResponse<String>() {
                @Override public int statusCode() { return status; }
                @Override public String body() { return responseBody; }
                @Override public java.net.http.HttpHeaders headers() { return null; }
                @Override public java.net.http.HttpRequest request() { return null; }
                @Override public URI uri() { return finalUri; }
                @Override public java.util.Optional<java.net.http.HttpResponse<String>> previousResponse() {
                    return java.util.Optional.empty();
                }
                @Override public java.net.http.HttpClient.Version version() {
                    return java.net.http.HttpClient.Version.HTTP_1_1;
                }
                @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
                    return java.util.Optional.empty();
                }
            };
        });
    }

    @Test
    void createAndGetViaVirtualHost() throws Exception {
        // PUT http://mybucket.localhost:port/  → createBucket("mybucket")
        var create = send("PUT", "mybucket.localhost", "/", null);
        assertEquals(200, create.statusCode());

        // PUT http://mybucket.localhost:port/hello.txt → putObject
        var put = send("PUT", "mybucket.localhost", "/hello.txt", "hi there");
        assertEquals(200, put.statusCode());

        // GET http://mybucket.localhost:port/hello.txt
        var get = send("GET", "mybucket.localhost", "/hello.txt", null);
        assertEquals(200, get.statusCode());
        assertEquals("hi there", get.body());
    }

    @Test
    void nestedKeyViaVirtualHost() throws Exception {
        send("PUT", "vnested.localhost", "/", null);
        send("PUT", "vnested.localhost", "/a/b/c.txt", "deep");

        var get = send("GET", "vnested.localhost", "/a/b/c.txt", null);
        assertEquals(200, get.statusCode());
        assertEquals("deep", get.body());
    }

    @Test
    void listObjectsViaVirtualHost() throws Exception {
        send("PUT", "vlist.localhost", "/", null);
        send("PUT", "vlist.localhost", "/x.txt", "1");
        send("PUT", "vlist.localhost", "/y.txt", "2");

        var list = send("GET", "vlist.localhost", "/", null);
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("<Key>x.txt</Key>"));
        assertTrue(list.body().contains("<Key>y.txt</Key>"));
    }

    @Test
    void plainLocalhostFallsBackToPathStyle() throws Exception {
        // No subdomain → path-style: /vpath is the bucket name.
        var create = send("PUT", "localhost", "/vpath", null);
        assertEquals(200, create.statusCode());

        var put = send("PUT", "localhost", "/vpath/f.txt", "path-style");
        assertEquals(200, put.statusCode());

        var get = send("GET", "localhost", "/vpath/f.txt", null);
        assertEquals(200, get.statusCode());
        assertEquals("path-style", get.body());
    }

    @Test
    void bucketWithDotsInName() throws Exception {
        // "a.b.localhost" → bucket "a.b"
        send("PUT", "a.b.localhost", "/", null);
        send("PUT", "a.b.localhost", "/f.txt", "dotted");

        var get = send("GET", "a.b.localhost", "/f.txt", null);
        assertEquals(200, get.statusCode());
        assertEquals("dotted", get.body());
    }

    @Test
    void deleteViaVirtualHost() throws Exception {
        send("PUT", "vdel.localhost", "/", null);
        send("PUT", "vdel.localhost", "/gone.txt", "bye");

        var del = send("DELETE", "vdel.localhost", "/gone.txt", null);
        assertEquals(204, del.statusCode());

        var get = send("GET", "vdel.localhost", "/gone.txt", null);
        assertEquals(404, get.statusCode());
    }
}
