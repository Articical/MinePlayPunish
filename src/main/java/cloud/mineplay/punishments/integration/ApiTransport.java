package cloud.mineplay.punishments.integration;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface ApiTransport extends AutoCloseable {
  CompletableFuture<Response> send(
      String method, URI uri, Map<String, String> headers, String body);

  record Response(int status, String body) {}

  @Override
  default void close() {}
}
