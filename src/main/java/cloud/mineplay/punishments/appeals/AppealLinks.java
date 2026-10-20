package cloud.mineplay.punishments.appeals;

import cloud.mineplay.punishments.integration.ApiProtocol;
import java.net.URI;
import java.util.Base64;

/**
 * Only accepts a backend-registered link returned over the authenticated API. Never generates
 * tokens.
 */
public final class AppealLinks {
  private final URI base;

  public AppealLinks(String base) {
    this.base = ApiProtocol.httpsRoot(base);
    if (!this.base.getPath().endsWith("/") || this.base.getRawPath().contains("%"))
      throw ApiProtocol.invalid("appeal base");
  }

  public String validate(String candidate) {
    URI uri = ApiProtocol.httpsRoot(candidate);
    if (!base.getScheme().equalsIgnoreCase(uri.getScheme())
        || !base.getHost().equalsIgnoreCase(uri.getHost())
        || base.getPort() != uri.getPort()
        || !uri.getRawPath().startsWith(base.getRawPath()))
      throw ApiProtocol.invalid("appeal origin");
    String token = uri.getRawPath().substring(base.getRawPath().length());
    if (!token.matches("[A-Za-z0-9_-]{43,128}")) throw ApiProtocol.invalid("appeal token");
    try {
      if (Base64.getUrlDecoder().decode(token).length < 32)
        throw ApiProtocol.invalid("appeal entropy");
    } catch (IllegalArgumentException e) {
      throw ApiProtocol.invalid("appeal token");
    }
    return uri.toASCIIString();
  }
}
