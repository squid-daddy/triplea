package games.strategy.engine.data.gameparser;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import games.strategy.engine.ClientContext;
import games.strategy.engine.data.AllianceTracker;
import games.strategy.engine.data.Attachable;
import games.strategy.engine.data.EngineVersionException;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameParseException;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.IAttachment;
import games.strategy.engine.data.NamedAttachable;
import games.strategy.engine.data.ProductionFrontier;
import games.strategy.engine.data.ProductionFrontierList;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.RelationshipTracker;
import games.strategy.engine.data.RelationshipType;
import games.strategy.engine.data.RelationshipTypeList;
import games.strategy.engine.data.RepairFrontier;
import games.strategy.engine.data.RepairFrontierList;
import games.strategy.engine.data.RepairRule;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.TechnologyFrontier;
import games.strategy.engine.data.TechnologyFrontierList;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.TerritoryEffect;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.properties.BooleanProperty;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.engine.data.properties.IEditableProperty;
import games.strategy.engine.data.properties.NumberProperty;
import games.strategy.engine.data.properties.StringProperty;
import games.strategy.engine.delegate.IDelegate;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.attachments.UnitAttachment;
import games.strategy.triplea.delegate.GenericTechAdvance;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.formatter.MyFormatter;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.extern.java.Log;
import org.triplea.generic.xml.reader.XmlMapper;
import org.triplea.generic.xml.reader.exceptions.XmlParsingException;
import org.triplea.java.UrlStreams;
import org.triplea.map.data.elements.AttachmentList;
import org.triplea.map.data.elements.DiceSides;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.GamePlay;
import org.triplea.map.data.elements.Initialize;
import org.triplea.map.data.elements.Initialize.OwnerInitialize.TerritoryOwner;
import org.triplea.map.data.elements.PlayerList.Alliance;
import org.triplea.map.data.elements.Production;
import org.triplea.map.data.elements.PropertyList;
import org.triplea.map.data.elements.RelationshipTypes;
import org.triplea.map.data.elements.Technology;
import org.triplea.map.data.elements.TerritoryEffectList;
import org.triplea.map.data.elements.Triplea;
import org.triplea.map.data.elements.UnitList;
import org.triplea.util.Tuple;
import org.triplea.util.Version;

/** Parses a game XML file into a {@link GameData} domain object. */
@Log
public final class GameParser {
  private static final String RESOURCE_IS_DISPLAY_FOR_NONE = "NONE";

  @Nonnull private final GameData data;
  private final String mapName;
  private final XmlGameElementMapper xmlGameElementMapper;

  private final GameDataVariableParser variableParser = new GameDataVariableParser();

  @VisibleForTesting
  public GameParser(
      final String mapName,
      final XmlGameElementMapper xmlGameElementMapper) {
    data = new GameData();
    this.mapName = mapName;
    this.xmlGameElementMapper = xmlGameElementMapper;
  }




  /**
   * Performs a deep parse of the game definition contained in the specified stream.
   *
   * @return A complete {@link GameData} instance that can be used to play the game.
   */
  public static Optional<GameData> parse(final URI mapUri) {
    return UrlStreams.openStream(
        mapUri,
        inputStream -> {
          try {
            return new GameParser(mapUri.toString(), new XmlGameElementMapper())
                .parse(inputStream);
          } catch (final EngineVersionException e) {
            log.log(Level.WARNING, "Game engine not compatible with: " + mapUri, e);
            return null;
          } catch (final GameParseException | XmlParsingException e) {
            log.log(Level.WARNING, "Could not parse:" + mapUri + ", " + e.getMessage(), e);
            return null;
          }
        });
  }

  @Nonnull
  @VisibleForTesting
  public static GameData parse(
      final String mapName,
      final InputStream stream,
      final XmlGameElementMapper xmlGameElementMapper)
      throws GameParseException, EngineVersionException, XmlParsingException {
    checkNotNull(mapName);
    checkNotNull(stream);
    checkNotNull(xmlGameElementMapper);

    return new GameParser(mapName, xmlGameElementMapper).parse(stream);
  }



  @Nonnull
  private GameData parse(final InputStream stream)
      throws GameParseException, EngineVersionException, XmlParsingException {

    final Game game;
    try (XmlMapper xmlMapper = new XmlMapper(stream)) {
      game = xmlMapper.mapXmlToObject(Game.class);
    }
    parseMapProperties(game);
    parseMapDetails(game);
    return data;
  }

  private GameParseException newGameParseException(final String message) {
    return newGameParseException(message, null);
  }

  private GameParseException newGameParseException(
      final String message, final @Nullable Throwable cause) {
    final String gameName = data.getGameName() != null ? data.getGameName() : "<unknown>";
    return new GameParseException(
        String.format("map name: '%s', game name: '%s', %s", mapName, gameName, message), cause);
  }

  private void parseMapProperties(final Game game)
      throws GameParseException, EngineVersionException {
    // mandatory fields
    // get the name of the map
    parseInfo(game);

    // test minimum engine version FIRST
    parseMinimumEngineVersionNumber(game);
    parseDiceSides(game);
    parsePlayerList(game);
    parseAlliances(game);

    if (game.getPropertyList() != null) {
      parseProperties(game.getPropertyList());
    }
  }

  private void parseMapDetails(final Game game) throws GameParseException {
    final Map<String, List<String>> variables = variableParser.parseVariables(game);
    parseMap(game);

    if (game.getResourceList() != null) {
      parseResources(game);
    }
    if (game.getUnitList() != null) {
      parseUnits(game.getUnitList());
    }
    // Parse all different relationshipTypes that are defined in the xml, for example: War, Allied,
    // Neutral, NAP
    if (game.getRelationshipTypes() != null) {
      parseRelationshipTypes(game.getRelationshipTypes());
    }
    if (game.getTerritoryEffectList() != null) {
      parseTerritoryEffects(game.getTerritoryEffectList());
    }
    parseGamePlay(game.getGamePlay());
    if (game.getProduction() != null) {
      parseProduction(game.getProduction());
    }
    if (game.getTechnology() != null) {
      parseTechnology(game.getTechnology());
    } else {
      TechAdvance.createDefaultTechAdvances(data);
    }

    if (game.getAttachmentList() != null) {
      parseAttachments(game.getAttachmentList(), variables);
    }

    if (game.getInitialize() != null) {
      parseInitialization(game);
    }
    // set & override default relationships
    // sets the relationship between all players and the NullPlayer to NullRelation (with archeType
    // War)
    data.getRelationshipTracker().setNullPlayerRelations();
    // sets the relationship for all players with themselves to the SelfRelation (with archeType
    // Allied)
    data.getRelationshipTracker().setSelfRelations();
    // set default tech attachments (comes after we parse all technologies, parse all attachments,
    // and parse all game
    // options/properties)
    checkThatAllUnitsHaveAttachments(data);
    TechAbilityAttachment.setDefaultTechnologyAttachments(data);
    try {
      validate();
    } catch (final Exception e) {
      log.log(Level.SEVERE, "Error parsing: " + mapName, e);
      throw newGameParseException("validation failed", e);
    }
  }

  private void parseDiceSides(final Game game) throws GameParseException {
    final int diceSides =
        Optional.ofNullable(game.getDiceSides()).map(DiceSides::getValue).orElse(6);
    if (diceSides < 1) {
      throw new GameParseException("Invalid value for dice sides: " + diceSides);
    }
    data.setDiceSides(diceSides);
  }

  private void parseMinimumEngineVersionNumber(final Game game) throws EngineVersionException {
    final String minimumVersion =
        Optional.ofNullable(game.getTriplea()).map(Triplea::getMinimumVersion).orElse(null);
    if (minimumVersion == null) {
      return;
    }
    final Version mapMinimumEngineVersion = new Version(minimumVersion);
    if (!ClientContext.engineVersion()
        .isCompatibleWithMapMinimumEngineVersion(mapMinimumEngineVersion)) {
      throw new EngineVersionException(
          String.format(
              "Current engine version: %s, is not compatible with version: %s, required by map: %s",
              ClientContext.engineVersion(),
              mapMinimumEngineVersion.toString(),
              data.getGameName()));
    }
  }

  private void validate() throws GameParseException {
    // validate unit attachments
    for (final UnitType u : data.getUnitTypeList()) {
      validateAttachments(u);
    }
    for (final Territory t : data.getMap()) {
      validateAttachments(t);
    }
    for (final Resource r : data.getResourceList().getResources()) {
      validateAttachments(r);
    }
    for (final GamePlayer r : data.getPlayerList().getPlayers()) {
      validateAttachments(r);
    }
    for (final RelationshipType r : data.getRelationshipTypeList().getAllRelationshipTypes()) {
      validateAttachments(r);
    }
    for (final TerritoryEffect r : data.getTerritoryEffectList().values()) {
      validateAttachments(r);
    }
    for (final TechAdvance r : data.getTechnologyFrontier().getTechs()) {
      validateAttachments(r);
    }
    // if relationships are used, every player should have a relationship with every other player
    validateRelationships();
  }

  private void validateRelationships() throws GameParseException {
    // for every player
    for (final GamePlayer player : data.getPlayerList()) {
      // in relation to every player
      for (final GamePlayer player2 : data.getPlayerList()) {
        // See if there is a relationship between them
        if ((data.getRelationshipTracker().getRelationshipType(player, player2) == null)) {
          // or else throw an exception!
          throw newGameParseException(
              "No relation set for: " + player.getName() + " and " + player2.getName());
        }
      }
    }
  }

  private void validateAttachments(final Attachable attachable) throws GameParseException {
    for (final IAttachment a : attachable.getAttachments().values()) {
      a.validate(data);
    }
  }

  private TechAdvance getTechnologyFromFrontier(final String name) {
    final TechnologyFrontier frontier = data.getTechnologyFrontier();
    TechAdvance type = frontier.getAdvanceByName(name);
    if (type == null) {
      type = frontier.getAdvanceByProperty(name);
    }
    return type;
  }

  private void parseInfo(final Game game) {
    data.setGameName(game.getInfo().getName());
    data.setGameVersion(new Version(game.getInfo().getVersion()));
  }

  private void parseMap(final Game game) {
    parseTerritories(game.getMap().getTerritories());
    parseConnections(game.getMap().getConnections());
  }

  private void parseTerritories(
      final List<org.triplea.map.data.elements.Map.Territory> territories) {
    for (final org.triplea.map.data.elements.Map.Territory territory : territories) {
      data.getMap().addTerritory(new Territory(territory.getName(), territory.isWater(), data));
    }
  }

  private void parseConnections(
      final List<org.triplea.map.data.elements.Map.Connection> connections) {
    for (final org.triplea.map.data.elements.Map.Connection connection : connections) {
      data.getMap()
          .addConnection(
              data.getMap().getTerritory(connection.getT1()),
              data.getMap().getTerritory(connection.getT2()));
    }
  }

  private void parseResources(final Game game) throws GameParseException {
    for (final org.triplea.map.data.elements.ResourceList.Resource resource :
        game.getResourceList().getResources()) {
      final String name = resource.getName();
      final String isDisplayedFor = resource.getIsDisplayedFor();
      if (isDisplayedFor.isEmpty()) {
        data.getResourceList()
            .addResource(new Resource(name, data, data.getPlayerList().getPlayers()));
      } else if (isDisplayedFor.equalsIgnoreCase(RESOURCE_IS_DISPLAY_FOR_NONE)) {
        data.getResourceList().addResource(new Resource(name, data));
      } else {
        data.getResourceList()
            .addResource(new Resource(name, data, parsePlayersFromIsDisplayedFor(isDisplayedFor)));
      }
    }
  }

  @VisibleForTesting
  List<GamePlayer> parsePlayersFromIsDisplayedFor(final String encodedPlayerNames)
      throws GameParseException {
    final List<GamePlayer> players = new ArrayList<>();
    for (final String playerName : Splitter.on(':').split(encodedPlayerNames)) {
      final @Nullable GamePlayer player = data.getPlayerList().getPlayerId(playerName);
      if (player == null) {
        throw newGameParseException("Parse resources could not find player: " + playerName);
      }
      players.add(player);
    }
    return players;
  }

  private void parseRelationshipTypes(final RelationshipTypes relationshipTypes) {
    relationshipTypes.getRelationshipTypes().stream()
        .map(RelationshipTypes.RelationshipType::getName)
        .map(name -> new RelationshipType(name, data))
        .forEach(data.getRelationshipTypeList()::addRelationshipType);
  }

  private void parseTerritoryEffects(final TerritoryEffectList territoryEffectList) {
    territoryEffectList.getTerritoryEffects().stream()
        .map(TerritoryEffectList.TerritoryEffect::getName)
        .forEach(name -> data.getTerritoryEffectList().put(name, new TerritoryEffect(name, data)));
  }

  private void parseUnits(final UnitList unitList) {
    unitList.getUnits().stream()
        .map(UnitList.Unit::getName)
        .map(name -> new UnitType(name, data))
        .forEach(data.getUnitTypeList()::addUnitType);
  }

  private void parsePlayerList(final Game game) {
    game.getPlayerList()
        .getPlayers()
        .forEach(
            player ->
                data.getPlayerList()
                    .addPlayerId(
                        new GamePlayer(
                            player.getName(),
                            player.isOptional(),
                            player.isCanBeDisabled(),
                            player.getDefaultType(),
                            player.isHidden(),
                            data)));
  }

  private void parseAlliances(final Game game) {
    final AllianceTracker allianceTracker = data.getAllianceTracker();
    final Collection<GamePlayer> players = data.getPlayerList().getPlayers();

    for (final Alliance alliance : game.getPlayerList().getAlliances()) {
      allianceTracker.addToAlliance(
          data.getPlayerList().getPlayerId(alliance.getPlayer()), alliance.getAlliance());
    }
    // if relationships aren't initialized based on relationshipInitialize we use the alliances to
    // set the relationships
    if (game.getInitialize().getRelationshipInitialize() == null) {
      final RelationshipTracker relationshipTracker = data.getRelationshipTracker();
      final RelationshipTypeList relationshipTypeList = data.getRelationshipTypeList();
      // iterate through all players to get known allies and enemies
      for (final GamePlayer currentPlayer : players) {
        // start with all players as enemies
        // start with no players as allies
        final Set<GamePlayer> allies = allianceTracker.getAllies(currentPlayer);
        final Set<GamePlayer> enemies = new HashSet<>(players);
        enemies.removeAll(allies);

        // remove self from enemies list (in case of free-for-all)
        enemies.remove(currentPlayer);
        // remove self from allies list (in case you are a member of an alliance)
        allies.remove(currentPlayer);
        // At this point enemies and allies should be set for this player.
        for (final GamePlayer alliedPLayer : allies) {
          relationshipTracker.setRelationship(
              currentPlayer, alliedPLayer, relationshipTypeList.getDefaultAlliedRelationship());
        }
        for (final GamePlayer enemyPlayer : enemies) {
          relationshipTracker.setRelationship(
              currentPlayer, enemyPlayer, relationshipTypeList.getDefaultWarRelationship());
        }
      }
    }
  }

  private void parseRelationInitialize(
      final List<Initialize.RelationshipInitialize.Relationship> relations) {
    for (final Initialize.RelationshipInitialize.Relationship relation : relations) {
      final GamePlayer p1 = data.getPlayerList().getPlayerId(relation.getPlayer1());
      final GamePlayer p2 = data.getPlayerList().getPlayerId(relation.getPlayer2());
      final RelationshipType r =
          data.getRelationshipTypeList().getRelationshipType(relation.getType());
      final int roundValue = relation.getRoundValue();
      data.getRelationshipTracker().setRelationship(p1, p2, r, roundValue);
    }
  }

  private void parseGamePlay(final GamePlay gamePlay) throws GameParseException {
    parseDelegates(gamePlay.getDelegates());
    parseSteps(gamePlay.getSequence().getSteps());
    parseOffset(gamePlay.getOffset());
  }

  private void parseProperties(final PropertyList propertyList) {
    final GameProperties properties = data.getProperties();
    for (final PropertyList.Property property : propertyList.getProperties()) {
      final String editable = property.getEditable();
      final String propertyName = property.getName();
      final String value =
          !property.getValue().isBlank()
              ? property.getValue()
              : property.getValueProperty().getData();

      if (editable != null && editable.equalsIgnoreCase("true")) {
        parseEditableProperty(property, value);
      } else {
        if (property.getNumberProperty() == null
            && property.getBooleanProperty() == null
            && property.getValueProperty() == null
            && property.getStringProperty() == null) {

          // we don't know what type this property is!!, it appears like only numbers and string may
          // be represented without proper type definition

          try {
            // test if it is an integer
            final int integer = Integer.parseInt(value);
            properties.set(propertyName, integer);
          } catch (final NumberFormatException e) {
            // then it must be a string
            properties.set(propertyName, value);
          }
        } else {
          if (property.getBooleanProperty() != null) {
            properties.set(propertyName, Boolean.valueOf(value));
          } else if (property.getNumberProperty() != null) {
            try {
              properties.set(propertyName, Integer.parseInt(value));
            } catch (final NumberFormatException e) {
              properties.set(propertyName, 0);
            }
          } else {
            properties.set(propertyName, value);
          }
        }
      }
    }
    data.getPlayerList()
        .forEach(
            playerId ->
                data.getProperties()
                    .addPlayerProperty(
                        new NumberProperty(
                            Constants.getIncomePercentageFor(playerId), null, 999, 0, 100)));
    data.getPlayerList()
        .forEach(
            playerId ->
                data.getProperties()
                    .addPlayerProperty(
                        new NumberProperty(Constants.getPuIncomeBonus(playerId), null, 999, 0, 0)));
  }

  private void parseEditableProperty(
      final PropertyList.Property property, final String defaultValue) {

    final IEditableProperty<?> editableProperty;
    if (property.getBooleanProperty() != null) {
      editableProperty =
          new BooleanProperty(property.getName(), null, Boolean.parseBoolean(defaultValue));
    } else if (property.getNumberProperty() != null) {
      editableProperty =
          new NumberProperty(
              property.getName(),
              null,
              property.getNumberProperty().getMax(),
              property.getNumberProperty().getMin(),
              Integer.parseInt(defaultValue));
    } else {
      editableProperty = new StringProperty(property.getName(), null, defaultValue);
    }
    data.getProperties().addEditableProperty(editableProperty);
  }

  private void parseOffset(final GamePlay.Offset offsetAttributes) {
    if (offsetAttributes == null) {
      return;
    }
    final int roundOffset = offsetAttributes.getRound();
    data.getSequence().setRoundOffset(roundOffset);
  }

  private void parseDelegates(final List<GamePlay.Delegate> delegateList)
      throws GameParseException {
    for (final GamePlay.Delegate gamePlayDelegate : delegateList) {
      // load the class
      final String className = gamePlayDelegate.getJavaClass();
      final IDelegate delegate =
          xmlGameElementMapper
              .newDelegate(className)
              .orElseThrow(
                  () -> newGameParseException("Class <" + className + "> is not a delegate."));
      final String name = gamePlayDelegate.getName();
      final String displayName = Optional.ofNullable(gamePlayDelegate.getDisplay()).orElse(name);
      delegate.initialize(name, displayName);
      data.addDelegate(delegate);
    }
  }

  private void parseSteps(final List<GamePlay.Sequence.Step> stepList) {
    for (final GamePlay.Sequence.Step current : stepList) {
      final IDelegate delegate = data.getDelegate(current.getDelegate());
      final GamePlayer player = data.getPlayerList().getPlayerId(current.getPlayer());
      final String name = current.getName();

      final Properties stepProperties = parseStepProperties(current.getStepProperties());
      final String displayName = Strings.emptyToNull(current.getDisplay());
      final GameStep step = new GameStep(name, displayName, player, delegate, data, stepProperties);
      if (current.getMaxRunCount() > 0) {
        step.setMaxRunCount(current.getMaxRunCount());
      }
      data.getSequence().addStep(step);
    }
  }

  private static Properties parseStepProperties(
      final List<GamePlay.Sequence.Step.StepProperty> properties) {
    final Properties stepProperties = new Properties();
    for (final GamePlay.Sequence.Step.StepProperty stepProperty : properties) {
      stepProperties.setProperty(stepProperty.getName(), stepProperty.getValue());
    }
    return stepProperties;
  }

  private void parseProduction(final Production production) throws GameParseException {
    parseProductionRules(production.getProductionRules());
    parseProductionFrontiers(production.getProductionFrontiers());
    parsePlayerProduction(production.getPlayerProductions());
    parseRepairRules(production.getRepairRules());
    parseRepairFrontiers(production.getRepairFrontiers());
    parsePlayerRepair(production.getPlayerRepairs());
  }

  private void parseTechnology(final Technology technology) throws GameParseException {
    parseTechnologies(technology.getTechnologies());
    parsePlayerTech(technology.getPlayerTechs());
  }

  private void parseProductionRules(final List<Production.ProductionRule> elements)
      throws GameParseException {
    for (final Production.ProductionRule current : elements) {
      final String name = current.getName();
      final ProductionRule rule = new ProductionRule(name, data);
      parseCosts(rule, current.getCosts());
      parseResults(rule, current.getResults());
      data.getProductionRuleList().addProductionRule(rule);
    }
  }

  private void parseRepairRules(final List<Production.RepairRule> elements)
      throws GameParseException {
    for (final Production.RepairRule current : elements) {
      final String name = current.getName();
      final RepairRule rule = new RepairRule(name, data);
      parseRepairCosts(rule, current.getCosts());
      parseRepairResults(rule, current.getResults());
      data.getRepairRules().addRepairRule(rule);
    }
  }

  private void parseCosts(
      final ProductionRule rule, final List<Production.ProductionRule.Cost> elements)
      throws GameParseException {
    if (elements.isEmpty()) {
      throw newGameParseException("no costs  for rule:" + rule.getName());
    }
    for (final Production.ProductionRule.Cost current : elements) {
      final Resource resource = data.getResourceList().getResource(current.getResource());
      final int quantity = current.getQuantity();
      rule.addCost(resource, quantity);
    }
  }

  private void parseRepairCosts(
      final RepairRule rule, final List<Production.ProductionRule.Cost> elements)
      throws GameParseException {
    if (elements.isEmpty()) {
      throw newGameParseException("no costs  for rule:" + rule.getName());
    }
    for (final Production.ProductionRule.Cost current : elements) {
      final Resource resource = data.getResourceList().getResource(current.getResource());
      final int quantity = current.getQuantity();
      rule.addCost(resource, quantity);
    }
  }

  private void parseResults(
      final ProductionRule rule, final List<Production.ProductionRule.Result> elements)
      throws GameParseException {
    if (elements.isEmpty()) {
      throw newGameParseException("no results  for rule:" + rule.getName());
    }
    for (final Production.ProductionRule.Result current : elements) {
      // must find either a resource or a unit with the given name
      NamedAttachable result = data.getResourceList().getResource(current.getResourceOrUnit());
      if (result == null) {
        result = data.getUnitTypeList().getUnitType(current.getResourceOrUnit());
      }
      if (result == null) {
        throw newGameParseException(
            "Could not find resource or unit" + current.getResourceOrUnit());
      }
      final int quantity = current.getQuantity();
      rule.addResult(result, quantity);
    }
  }

  private void parseRepairResults(
      final RepairRule rule, final List<Production.ProductionRule.Result> elements)
      throws GameParseException {
    if (elements.isEmpty()) {
      throw newGameParseException("no results  for rule:" + rule.getName());
    }
    for (final Production.ProductionRule.Result current : elements) {
      // must find either a resource or a unit with the given name
      NamedAttachable result = data.getResourceList().getResource(current.getResourceOrUnit());
      if (result == null) {
        result = data.getUnitTypeList().getUnitType(current.getResourceOrUnit());
      }
      if (result == null) {
        throw newGameParseException(
            "Could not find resource or unit" + current.getResourceOrUnit());
      }
      final int quantity = current.getQuantity();
      rule.addResult(result, quantity);
    }
  }

  private void parseProductionFrontiers(final List<Production.ProductionFrontier> elements) {
    final ProductionFrontierList frontiers = data.getProductionFrontierList();
    for (final Production.ProductionFrontier current : elements) {
      final String name = current.getName();
      final ProductionFrontier frontier = new ProductionFrontier(name, data);
      parseFrontierRules(current.getFrontierRules(), frontier);
      frontiers.addProductionFrontier(frontier);
    }
  }

  private void parseTechnologies(final Technology.Technologies element) {
    if (element == null) {
      return;
    }
    final TechnologyFrontier allTechs = data.getTechnologyFrontier();
    parseTechs(element.getTechNames(), allTechs);
  }

  private void parsePlayerTech(final List<Technology.PlayerTech> elements)
      throws GameParseException {
    for (final Technology.PlayerTech current : elements) {
      final GamePlayer player = data.getPlayerList().getPlayerId(current.getPlayer());
      final TechnologyFrontierList categories = player.getTechnologyFrontierList();
      parseCategories(current.getCategories(), categories);
    }
  }

  private void parseCategories(
      final List<Technology.PlayerTech.Category> elements, final TechnologyFrontierList categories)
      throws GameParseException {
    for (final Technology.PlayerTech.Category current : elements) {
      final TechnologyFrontier tf = new TechnologyFrontier(current.getName(), data);
      parseCategoryTechs(current.getTechs(), tf);
      categories.addTechnologyFrontier(tf);
    }
  }

  private void parseRepairFrontiers(final List<Production.RepairFrontier> elements) {
    final RepairFrontierList frontiers = data.getRepairFrontierList();
    for (final Production.RepairFrontier current : elements) {
      final String name = current.getName();
      final RepairFrontier frontier = new RepairFrontier(name, data);
      parseRepairFrontierRules(current.getRepairRules(), frontier);
      frontiers.addRepairFrontier(frontier);
    }
  }

  private void parsePlayerProduction(final List<Production.PlayerProduction> elements) {
    for (final Production.PlayerProduction current : elements) {
      final GamePlayer player = data.getPlayerList().getPlayerId(current.getPlayer());
      final ProductionFrontier frontier =
          data.getProductionFrontierList().getProductionFrontier(current.getFrontier());
      player.setProductionFrontier(frontier);
    }
  }

  private void parsePlayerRepair(final List<Production.PlayerRepair> elements) {
    for (final Production.PlayerRepair current : elements) {
      final GamePlayer player = data.getPlayerList().getPlayerId(current.getPlayer());
      final RepairFrontier repairFrontier =
          data.getRepairFrontierList().getRepairFrontier(current.getFrontier());
      player.setRepairFrontier(repairFrontier);
    }
  }

  private void parseFrontierRules(
      final List<Production.ProductionFrontier.FrontierRules> elements,
      final ProductionFrontier frontier) {
    for (final Production.ProductionFrontier.FrontierRules element : elements) {
      frontier.addRule(data.getProductionRuleList().getProductionRule(element.getName()));
    }
  }

  private void parseTechs(
      final List<Technology.Technologies.TechName> elements,
      final TechnologyFrontier allTechsFrontier) {
    for (final Technology.Technologies.TechName current : elements) {
      final String name = current.getName();
      final String tech = current.getTech();
      TechAdvance ta;
      if (!tech.isBlank()) {
        ta =
            new GenericTechAdvance(
                name, TechAdvance.findDefinedAdvanceAndCreateAdvance(tech, data), data);
      } else {
        try {
          ta = TechAdvance.findDefinedAdvanceAndCreateAdvance(name, data);
        } catch (final IllegalArgumentException e) {
          ta = new GenericTechAdvance(name, null, data);
        }
      }
      allTechsFrontier.addAdvance(ta);
    }
  }

  private void parseCategoryTechs(
      final List<Technology.PlayerTech.Category.Tech> elements, final TechnologyFrontier frontier)
      throws GameParseException {
    for (final Technology.PlayerTech.Category.Tech current : elements) {
      TechAdvance ta = data.getTechnologyFrontier().getAdvanceByProperty(current.getName());
      if (ta == null) {
        ta = data.getTechnologyFrontier().getAdvanceByName(current.getName());
      }
      if (ta == null) {
        throw newGameParseException("Technology not found :" + current.getName());
      }
      frontier.addAdvance(ta);
    }
  }

  private void parseRepairFrontierRules(
      final List<Production.RepairFrontier.RepairRules> elements, final RepairFrontier frontier) {
    for (final Production.RepairFrontier.RepairRules element : elements) {
      frontier.addRule(data.getRepairRules().getRepairRule(element.getName()));
    }
  }

  private void parseAttachments(
      final AttachmentList attachmentList, final Map<String, List<String>> variables)
      throws GameParseException {
    for (final AttachmentList.Attachment attachment : attachmentList.getAttachments()) {
      final String foreach = attachment.getForeach();
      if (foreach.isEmpty()) {
        parseAttachment(attachment, variables, Map.of());
      } else {
        final List<String> nestedForeach = Splitter.on("^").splitToList(foreach);
        if (nestedForeach.isEmpty() || nestedForeach.size() > 2) {
          throw newGameParseException(
              "Invalid foreach expression, can only use variables, ':', and at most 1 '^': "
                  + foreach);
        }
        final List<String> foreachVariables1 = Splitter.on(":").splitToList(nestedForeach.get(0));
        final List<String> foreachVariables2 =
            nestedForeach.size() == 2
                ? Splitter.on(":").splitToList(nestedForeach.get(1))
                : List.of();
        validateForeachVariables(foreachVariables1, variables, foreach);
        validateForeachVariables(foreachVariables2, variables, foreach);
        final int length1 = variables.get(foreachVariables1.get(0)).size();
        for (int i = 0; i < length1; i++) {
          final Map<String, String> foreachMap1 =
              createForeachVariablesMap(foreachVariables1, i, variables);
          if (foreachVariables2.isEmpty()) {
            parseAttachment(attachment, variables, foreachMap1);
          } else {
            final int length2 = variables.get(foreachVariables2.get(0)).size();
            for (int j = 0; j < length2; j++) {
              final Map<String, String> foreachMap2 =
                  createForeachVariablesMap(foreachVariables2, j, variables);
              foreachMap2.putAll(foreachMap1);
              parseAttachment(attachment, variables, foreachMap2);
            }
          }
        }
      }
    }
  }

  private void validateForeachVariables(
      final List<String> foreachVariables,
      final Map<String, List<String>> variables,
      final String foreach)
      throws GameParseException {
    if (foreachVariables.isEmpty()) {
      return;
    }
    if (!variables.keySet().containsAll(foreachVariables)) {
      throw newGameParseException("Attachment has invalid variables in foreach: " + foreach);
    }
    final int length = variables.get(foreachVariables.get(0)).size();
    for (final String foreachVariable : foreachVariables) {
      final List<String> foreachValue = variables.get(foreachVariable);
      if (length != foreachValue.size()) {
        throw newGameParseException(
            "Attachment foreach variables must have same number of elements: " + foreach);
      }
    }
  }

  private static Map<String, String> createForeachVariablesMap(
      final List<String> foreachVariables,
      final int currentIndex,
      final Map<String, List<String>> variables) {
    final Map<String, String> foreachMap = new HashMap<>();
    for (final String foreachVariable : foreachVariables) {
      final List<String> foreachValue = variables.get(foreachVariable);
      foreachMap.put("@" + foreachVariable.replace("$", "") + "@", foreachValue.get(currentIndex));
    }
    return foreachMap;
  }

  private void parseAttachment(
      final AttachmentList.Attachment attachment,
      final Map<String, List<String>> variables,
      final Map<String, String> foreach)
      throws GameParseException {
    final String className = attachment.getJavaClass();
    final Attachable attachable =
        findAttachment(attachment.getAttachTo(), attachment.getType(), foreach);
    final String name = replaceForeachVariables(attachment.getName(), foreach);
    final IAttachment attachmentObject =
        xmlGameElementMapper
            .newAttachment(className, name, attachable, data)
            .orElseThrow(
                () ->
                    newGameParseException(
                        "Attachment of type " + className + " could not be instantiated"));
    attachable.addAttachment(name, attachmentObject);
    final List<AttachmentList.Attachment.Option> options = attachment.getOptions();
    final List<Tuple<String, String>> attachmentOptionValues =
        setOptions(attachmentObject, options, foreach, variables);
    // keep a list of attachment references in the order they were added
    data.addToAttachmentOrderAndValues(Tuple.of(attachmentObject, attachmentOptionValues));
  }

  private Attachable findAttachment(
      final String attachToValue, final String type, final Map<String, String> foreach)
      throws GameParseException {
    final String attachTo = replaceForeachVariables(attachToValue, foreach);
    switch (type) {
      case "unitType":
        return data.getUnitTypeList().getUnitType(attachTo);
      case "territory":
        return data.getMap().getTerritory(attachTo);
      case "resource":
        return data.getResourceList().getResource(attachTo);
      case "territoryEffect":
        return data.getTerritoryEffectList().get(attachTo);
      case "player":
        return data.getPlayerList().getPlayerId(attachTo);
      case "relationship":
        return data.getRelationshipTypeList().getRelationshipType(attachTo);
      case "technology":
        return getTechnologyFromFrontier(attachTo);
      default:
        throw newGameParseException("Type not found to attach to: " + type);
    }
  }

  private List<Tuple<String, String>> setOptions(
      final IAttachment attachment,
      final List<AttachmentList.Attachment.Option> options,
      final Map<String, String> foreach,
      final Map<String, List<String>> variables)
      throws GameParseException {
    final List<Tuple<String, String>> results = new ArrayList<>();
    for (final AttachmentList.Attachment.Option option : options) {
      // decapitalize the property name for backwards compatibility
      final String name = decapitalize(option.getName());
      if (name.isEmpty()) {
        throw newGameParseException(
            "Option name with zero length for attachment: " + attachment.getName());
      }
      final String value = option.getValue();
      final String count = option.getCount();
      final String countAndValue = (!count.isEmpty() ? count + ":" : "") + value;
      if (containsEmptyForeachVariable(countAndValue, foreach)) {
        continue; // Skip adding option if contains empty foreach variable
      }
      final String valueWithForeach = replaceForeachVariables(countAndValue, foreach);
      final String finalValue = replaceVariables(valueWithForeach, variables);
      try {
        attachment
            .getProperty(name)
            .orElseThrow(
                () ->
                    newGameParseException(
                        String.format(
                            "Missing property definition for option '%s' in attachment '%s'",
                            name, attachment.getName())))
            .setValue(finalValue);
      } catch (final GameParseException e) {
        throw e;
      } catch (final Exception e) {
        throw newGameParseException(
            "Unexpected Exception while setting values for attachment: " + attachment, e);
      }

      results.add(Tuple.of(name, finalValue));
    }
    return results;
  }

  private String replaceForeachVariables(final String s, final Map<String, String> foreach) {
    String result = s;
    for (final Entry<String, String> entry : foreach.entrySet()) {
      result = result.replace(entry.getKey(), entry.getValue());
    }
    return result;
  }

  private boolean containsEmptyForeachVariable(final String s, final Map<String, String> foreach) {
    for (final Entry<String, String> entry : foreach.entrySet()) {
      if (entry.getValue().isEmpty() && s.contains(entry.getKey())) {
        return true;
      }
    }
    return false;
  }

  private String replaceVariables(final String s, final Map<String, List<String>> variables) {
    String result = s;
    for (final Entry<String, List<String>> entry : variables.entrySet()) {
      result = result.replace(entry.getKey(), String.join(":", entry.getValue()));
    }
    return result;
  }

  @VisibleForTesting
  static String decapitalize(final String value) {
    return ((value.length() > 0) ? value.substring(0, 1).toLowerCase() : "")
        + ((value.length() > 1) ? value.substring(1) : "");
  }

  private void parseInitialization(final Game game) throws GameParseException {
    if (game.getInitialize().getOwnerInitialize() != null) {
      parseOwner(game.getInitialize().getOwnerInitialize().getTerritoryOwners());
    }
    if (game.getInitialize().getUnitInitialize() != null) {
      parseUnitPlacement(game.getInitialize().getUnitInitialize().getUnitPlacements());
      parseHeldUnits(game.getInitialize().getUnitInitialize().getHeldUnits());
    }
    if (game.getInitialize().getResourceInitialize() != null) {
      parseResourceInitialization(game);
    }
    if (game.getInitialize().getRelationshipInitialize() != null) {
      parseRelationInitialize(game.getInitialize().getRelationshipInitialize().getRelationships());
    }
  }

  private void parseOwner(final List<TerritoryOwner> territoryOwners) {
    for (final TerritoryOwner current : territoryOwners) {
      final Territory territory = data.getMap().getTerritory(current.getTerritory());
      final GamePlayer owner = data.getPlayerList().getPlayerId(current.getOwner());
      territory.setOwner(owner);
      // Set the original owner on startup.
      // TODO Look into this
      // The addition of this caused the automated tests to fail as TestAttachment can't be cast to
      // TerritoryAttachment
      // The addition of this IF to pass the tests is wrong, but works until a better solution is
      // found.
      // Kevin will look into it.
      if (!territory.getData().getGameName().equals("gameExample")
          && !territory.getData().getGameName().equals("test")) {
        // set the original owner
        final TerritoryAttachment ta = TerritoryAttachment.get(territory);
        if (ta != null) {
          // If we already have an original owner set (ie: we set it previously in the attachment
          // using originalOwner or
          // occupiedTerrOf), then we DO NOT set the original owner again.
          // This is how we can have a game start with territories owned by 1 faction but controlled
          // by a 2nd faction.
          final GamePlayer currentOwner = ta.getOriginalOwner();
          if (currentOwner == null) {
            ta.setOriginalOwner(owner);
          }
        }
      }
    }
  }

  private void parseUnitPlacement(final List<Initialize.UnitInitialize.UnitPlacement> placements)
      throws GameParseException {
    for (final Initialize.UnitInitialize.UnitPlacement placement : placements) {
      final Territory territory = data.getMap().getTerritory(placement.getTerritory());
      final UnitType type = data.getUnitTypeList().getUnitType(placement.getUnitType());
      final String ownerString = placement.getOwner();
      final int hits = placement.getHitsTaken();
      final int unitDamage = placement.getUnitDamage();
      final GamePlayer owner =
          (ownerString == null || ownerString.isBlank())
              ? GamePlayer.NULL_PLAYERID
              : data.getPlayerList().getPlayerId(placement.getOwner());

      if (hits < 0 || hits > UnitAttachment.get(type).getHitPoints() - 1) {
        throw newGameParseException(
            "hitsTaken cannot be less than zero or greater than one less than total hitPoints, "
                + "invalid placement: "
                + placement);
      }
      if (unitDamage < 0) {
        throw newGameParseException(
            "unitDamage cannot be less than zero, invalid placement: " + placement);
      }
      final int quantity = placement.getQuantity();
      territory.getUnitCollection().addAll(type.create(quantity, owner, false, hits, unitDamage));
    }
  }

  private void parseHeldUnits(final List<Initialize.UnitInitialize.HeldUnits> heldUnits) {
    for (final Initialize.UnitInitialize.HeldUnits heldUnit : heldUnits) {
      final GamePlayer player = data.getPlayerList().getPlayerId(heldUnit.getPlayer());
      final UnitType type = data.getUnitTypeList().getUnitType(heldUnit.getUnitType());
      final int quantity = heldUnit.getQuantity();
      player.getUnitCollection().addAll(type.create(quantity, player));
    }
  }

  private void parseResourceInitialization(final Game game) throws GameParseException {
    for (final Initialize.ResourceInitialize.ResourceGiven resourceGiven :
        game.getInitialize().getResourceInitialize().getResourcesGiven()) {
      data.getPlayerList()
          .getPlayerId(resourceGiven.getPlayer())
          .getResources()
          .addResource(
              data.getResourceList().getResource(resourceGiven.getResource()),
              resourceGiven.getQuantity());
    }
  }

  private void checkThatAllUnitsHaveAttachments(final GameData data) throws GameParseException {
    final Collection<UnitType> errors = new ArrayList<>();
    for (final UnitType ut : data.getUnitTypeList().getAllUnitTypes()) {
      final UnitAttachment ua = UnitAttachment.get(ut);
      if (ua == null) {
        errors.add(ut);
      }
    }
    if (!errors.isEmpty()) {
      throw newGameParseException(
          data.getGameName()
              + " does not have unit attachments for: "
              + MyFormatter.defaultNamedToTextList(errors));
    }
  }
}
