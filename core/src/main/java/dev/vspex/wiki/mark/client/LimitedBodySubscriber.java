package dev.vspex.wiki.mark.client;

import java.io.*;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounded streaming subscriber; production imports stage to disk instead of retaining assets. */
final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
  static final int LIMIT = 50 * 1024 * 1024;
  private final CompletableFuture<byte[]> body = new CompletableFuture<>();
  private final OutputStream output;
  private final long limit;
  private long count;
  private Flow.Subscription subscription;

  LimitedBodySubscriber() {
    this(null, LIMIT);
  }

  LimitedBodySubscriber(Path destination, long limit) {
    this.limit = Math.min(limit, LIMIT);
    try {
      output =
          destination == null ? new ByteArrayOutputStream() : Files.newOutputStream(destination);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public CompletionStage<byte[]> getBody() {
    return body;
  }

  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    this.subscription = subscription;
    subscription.request(1);
  }

  @Override
  public void onNext(List<ByteBuffer> buffers) {
    try {
      byte[] chunk = new byte[8192];
      for (ByteBuffer buffer : buffers) {
        if (count + buffer.remaining() > limit)
          throw new IOException("Attachment exceeds 50 MiB or remaining import limit");
        count += buffer.remaining();
        while (buffer.hasRemaining()) {
          int length = Math.min(chunk.length, buffer.remaining());
          buffer.get(chunk, 0, length);
          output.write(chunk, 0, length);
        }
      }
      subscription.request(1);
    } catch (IOException e) {
      subscription.cancel();
      onError(e);
    }
  }

  @Override
  public void onError(Throwable error) {
    try {
      output.close();
    } catch (IOException ignored) {
    }
    body.completeExceptionally(error);
  }

  @Override
  public void onComplete() {
    try {
      output.close();
      body.complete(
          output instanceof ByteArrayOutputStream bytes ? bytes.toByteArray() : new byte[0]);
    } catch (IOException e) {
      body.completeExceptionally(e);
    }
  }
}
