package cloud.mineplay.punishments.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class OpenApiContractTest {
  @Test
  void contractIsValidYamlAndEveryLocalSchemaReferenceResolves() throws Exception {
    Object parsed;
    try (var reader = Files.newBufferedReader(Path.of("docs/api/openapi.yaml"))) {
      parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
    }
    Map<?, ?> document = assertInstanceOf(Map.class, parsed);
    assertEquals("3.1.0", document.get("openapi"));
    assertEquals(5, ((Map<?, ?>) document.get("paths")).size());
    validateReferences(document, document);
  }

  private void validateReferences(Object node, Map<?, ?> root) {
    if (node instanceof Map<?, ?> map) {
      if (map.get("$ref") instanceof String ref) {
        assertTrue(ref.startsWith("#/"));
        Object target = root;
        for (String part : ref.substring(2).split("/")) {
          target = ((Map<?, ?>) target).get(part.replace("~1", "/").replace("~0", "~"));
          assertNotNull(target, "Unresolved reference " + ref);
        }
      }
      map.values().forEach(value -> validateReferences(value, root));
    } else if (node instanceof List<?> list) list.forEach(value -> validateReferences(value, root));
  }
}
