package games.strategy.engine.data.gameparser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.VariableList.Variable;

class GameDataVariableParser {

  Map<String, List<String>> parseVariables(final Game game) {
    return game.getVariableList() != null ? parseVariableElement(game) : Map.of();
  }

  private Map<String, List<String>> parseVariableElement(final Game game) {
    final Map<String, List<String>> variables = new HashMap<>();
    for (final Variable variable : game.getVariableList().getVariables()) {
      final List<String> values =
          variable.getElements().stream()
              .map(Variable.Element::getName)
              .flatMap(value -> findNestedVariables(value, variables))
              .collect(Collectors.toList());
      final String name = "$" + variable.getName() + "$";
      variables.put(name, values);
    }
    return variables;
  }

  private Stream<String> findNestedVariables(
      final String value, final Map<String, List<String>> variables) {
    if (!variables.containsKey(value)) {
      return Stream.of(value);
    }
    return variables.get(value).stream().flatMap(s -> findNestedVariables(s, variables));
  }
}
