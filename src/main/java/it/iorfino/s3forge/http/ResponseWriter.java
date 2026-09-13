package it.iorfino.s3forge.http;

import com.sun.net.httpserver.HttpExchange;
import it.iorfino.s3forge.model.S3Error;
import it.iorfino.s3forge.xml.XmlWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class ResponseWriter {

    private ResponseWriter() {}

    public static void xml(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/xml");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void empty(HttpExchange ex, int status) throws IOException {
        ex.sendResponseHeaders(status, -1);
        ex.close();
    }

    public static void error(HttpExchange ex, S3Error err) throws IOException {
        String body =
                new XmlWriter()
                        .header()
                        .open("Error")
                        .element("Code", err.code())
                        .element("Message", err.message())
                        .close("Error")
                        .toString();
        xml(ex, err.httpStatus(), body);
    }
}
