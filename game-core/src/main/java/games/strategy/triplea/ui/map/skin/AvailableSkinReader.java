package games.strategy.triplea.ui.map.skin;

import java.util.Map;

public class AvailableSkinReader {
  /** returns the map skins for the game data. returns is a map of display-name -> map directory */
  public static Map<String, String> getSkins(final String mapName) {
    return Map.of("none", "none", "topographical", "topographical");
/*    return AvailableSkinReader.getSkins(mapName);


    final Map<String, String> skinsByDisplayName = new LinkedHashMap<>();
    skinsByDisplayName.put("Original", mapName);
    for (final File f : FileUtils.listFiles(ClientFileSystemHelper.getUserMapsFolder())) {
      if (mapSkinNameMatchesMapName(f.getName(), mapName)) {
        final String displayName =
            f.getName().replace(mapName + "-", "").replace("-master", "").replace(".zip", "");
        skinsByDisplayName.put(displayName, f.getName());
      }
    }
    return skinsByDisplayName;

 */
  }

  private static boolean mapSkinNameMatchesMapName(final String mapSkin, final String mapName) {
    return mapSkin.startsWith(mapName)
        && mapSkin.toLowerCase().contains("skin")
        && mapSkin.contains("-")
        && !mapSkin.endsWith("properties");
  }

}
