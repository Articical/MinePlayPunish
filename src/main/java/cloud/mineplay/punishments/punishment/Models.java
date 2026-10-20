package cloud.mineplay.punishments.punishment;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.UUID;

public final class Models {
  private Models() {}

  public enum Type {
    BAN,
    MUTE,
    WARNING,
    KICK,
    COMMUNITY_SERVICE
  }

  public enum Source {
    IN_GAME,
    CONSOLE,
    WEBSITE
  }

  public enum Status {
    ACTIVE,
    EXPIRED,
    REVOKED,
    COMPLETED
  }

  public enum ActionType {
    CREATE_BAN,
    CREATE_MUTE,
    CREATE_WARNING,
    CREATE_KICK,
    CREATE_COMMUNITY_SERVICE,
    REVOKE_PUNISHMENT,
    MODIFY_EXPIRATION,
    ADD_SERVICE_BLOCKS,
    SET_SERVICE_BLOCKS,
    RELEASE_SERVICE,
    ROTATE_APPEAL_TOKEN
  }

  public record Punishment(
      UUID id,
      String reference,
      UUID targetId,
      String targetName,
      Type type,
      String reason,
      String issuer,
      Source source,
      Instant issuedAt,
      Instant expiresAt,
      Status status,
      long revision,
      String appealUrl) {}

  public record Sentence(
      UUID id,
      UUID punishmentId,
      UUID targetId,
      int required,
      int completed,
      String tool,
      String state) {}

  public record Action(
      UUID id,
      ActionType type,
      UUID targetId,
      String targetName,
      UUID punishmentId,
      Long expectedRevision,
      String issuer,
      Source source,
      Instant issuedAt,
      Instant validUntil,
      JsonObject payload) {
    public Action {
      payload = payload == null ? new JsonObject() : payload.deepCopy();
    }

    @Override
    public JsonObject payload() {
      return payload.deepCopy();
    }
  }

  public record Result(boolean applied, String code, String detail, Punishment punishment) {}
}
