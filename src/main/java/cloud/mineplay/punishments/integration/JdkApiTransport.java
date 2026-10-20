package cloud.mineplay.punishments.integration;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/**
 * HTTPS-only asynchronous HTTP, no redirects, bounded response memory and full request deadlines.
 */
public final class JdkApiTransport implements ApiTransport {
  private final HttpClient client;
  private final Duration timeout;

  public JdkApiTransport(Duration connectTimeout, Duration timeout) {
    this.timeout = timeout;
    client =
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public CompletableFuture<Response> send(
      String method, URI uri, Map<String, String> headers, String body) {
    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null)
      return CompletableFuture.failedFuture(ApiProtocol.invalid("HTTPS endpoint"));
    HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout);
    headers.forEach(request::header);
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return client
        .sendAsync(request.build(), ignored -> new LimitedBody())
        .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .thenApply(response -> new Response(response.statusCode(), response.body()));
  }

  @Override
  public void close() {
    client.shutdownNow();
  }

  private static final class LimitedBody implements HttpResponse.BodySubscriber<String> {
    private final CompletableFuture<String> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    @Override
    public CompletionStage<String> getBody() {
      return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (bytes.size() + buffer.remaining() > ApiProtocol.MAX_BODY_BYTES) {
          subscription.cancel();
          result.completeExceptionally(ApiProtocol.invalid("response size"));
          return;
        }
        byte[] part = new byte[buffer.remaining()];
        buffer.get(part);
        bytes.writeBytes(part);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable throwable) {
      result.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
      result.complete(bytes.toString(StandardCharsets.UTF_8));
    }
  }
}
