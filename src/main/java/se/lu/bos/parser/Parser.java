package se.lu.bos.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.lu.bos.model.*;
import se.lu.bos.util.TimeUtil;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Parser {

    private static final Logger log = LoggerFactory.getLogger(Parser.class);

    public static final String REPORT_BASE_NAME = "missionReport(2014-11-30_23-38-38)"; //"missionReport(2014-11-30_23-33-34)"; //"missionReport(2014-11-30_17-14-34)";

    private Map<Integer, GameObject> mappedObjects = new HashMap<Integer, GameObject>();


    // Patterns
    private Pattern aType1Pattern = Pattern.compile("(T:?)(\\d+)(.*)(AMMO:?)(\\S+)(.*)(TID:?)(\\d+)");

    private List<GameObject> allGameObjects = new ArrayList<GameObject>();

    private String clean(String rootName) {
        return rootName.replaceAll("[^a-zA-Z0-9\\s]", "");
    }

    public Stats buildStatsFromRootFileName(String folder, String rootFileName) throws IOException {
        Stats stats = new Stats();
        stats.setRootFileName(clean(rootFileName));
        String logdata = readLogFiles(folder, rootFileName);

        stats.setReportFileDate(new Concatenator().getFirstFileDate(folder, rootFileName));
        stats.setFullLog(logdata);
        return buildStatsFromLogData(stats, logdata);
    }

    private Stats buildStatsFromLogData(Stats stats, String logdata) {
        List<String> logRows = toList(logdata);

        Integer playerId = parsePlayerId(logRows);
        stats.setPlayerId(playerId);
        mappedObjects.put(playerId, findGameObject(logRows, playerId));


        List<String> playerEntries = buildPlayerEntries(playerId, logRows);

        processPlayerEntries(stats, logRows, playerId, playerEntries);

        // After looping over player entries, try to find final state of any objects we've hit:
        resolveDestroyedObjects(stats, logRows);
        resolveIndirectlyDestroyedObjects(stats, logRows, playerId);

        storeKilledObjectsOnStats(stats, playerId);

        // Find hits that caused damage to us
        resolveDamageOnPilot(stats, logRows, playerId, playerEntries);

        // Find own fighter, pilot name, starting and final ammo count etc.
        resolveMetaData(stats, logRows, playerId);

        // Build hierarchy of mapped objects
        buildHierarichalGameObjectGraph();


        stats.setAssociatedObjects(new ArrayList<GameObject>(mappedObjects.values()));
        stats.setCreated(new Date());
        stats.setTotalDuration(TimeUtil.gameTickToTime(parseTime(logRows.get(logRows.size() - 1))));
        stats.setFinalState(resolveFinalPlayerObjectState(logRows, playerId));

        // Leaving this commented out for now.
        //  findAllGameObjects(logRows);
        //  stats.setAllGameObjects(allGameObjects);

        logRecordedStats(stats);

        return stats;
    }

    /**
     * Try to find any AType:3 having -1 as AID. Check back for any damage done or hits on this object.
     * The attacker with the most hits is awarded the kill.
     *
     * Seems as AID:-1 is used when a damaged plane is destroyed due to crash for example
     *
     * @param stats
     * @param logRows
     */
    private void resolveIndirectlyDestroyedObjects(Stats stats, List<String> logRows, Integer playerId) {

        for(String row : logRows) {
            if(row.contains("AType:3") && row.contains("AID:-1")) {
                // Iterate over hits
                Integer targetId = Integer.parseInt(row.substring(row.indexOf(" TID:") + 5, row.indexOf(" POS(")));
                Map<Integer, Integer> attackerHitCountMap = new HashMap<Integer, Integer>();
                for(Hit h : stats.getHits()) {
                    if(h.getAttackerId() != -1 && h.getTargetId().equals(targetId)) {
                        if(attackerHitCountMap.containsKey(h.getAttackerId())) {
                            Integer val = attackerHitCountMap.get(h.getAttackerId());
                            val += 1;
                            attackerHitCountMap.put(h.getAttackerId(), val);
                        } else {
                            attackerHitCountMap.put(h.getAttackerId(), 1);
                        }
                    }
                }

                Integer highestNumberOfHitsAttackerId = -1;
                Integer highestHits = 0;
                // Create kill if if most hits comes from playerId
                for(Map.Entry<Integer, Integer> entry : attackerHitCountMap.entrySet()) {
                    if(entry.getValue() > highestHits) {
                        highestHits = entry.getValue();
                        highestNumberOfHitsAttackerId = entry.getKey();
                    }
                }
                if(highestNumberOfHitsAttackerId.equals(playerId) && highestHits > 0) {
                    // Construct Kill

                    GameObject gameObject = findGameObject(logRows, targetId);
                    if(!stats.getKills().contains(gameObject)) {
                        gameObject.setTimeOfKill(parseTime(row));
                        FlightPosition fp = parseFlightPosition(row);
                        if(fp != null) {
                            gameObject.setKilledXPos(fp.getX());
                            gameObject.setKilledZPos(fp.getZ());
                        }
                        stats.getKills().add(gameObject);
                    }
                    if(!mappedObjects.containsKey(targetId)) {
                        mappedObjects.put(targetId, gameObject);
                    }
                }
            }
        }

    }

    private State resolveFinalPlayerObjectState(List<String> logRows, Integer playerId) {
        for(String row : logRows) {
            if(row.contains(" AType:3 ") && row.contains(" TID:" + playerId + " ")) {
                return State.DESTROYED;
            }
        }
        return State.ALIVE;
    }

    private void buildHierarichalGameObjectGraph() {
        Iterator<GameObject> i = mappedObjects.values().iterator();
        while(i.hasNext()) {
            GameObject o = i.next();

            if(o.getParentId() != null && o.getParentId() != -1) {
                 // This object has a parent. Add this object to the
                // parent's child list
                GameObject parentObject = mappedObjects.get(o.getParentId());
                if(parentObject != null && !parentObject.getChildren().contains(o)) {
                    parentObject.getChildren().add(o);

                    // Then remove it from the "main" one.
                    i.remove();
                } else {
                    //  The object may be a child of a child. We need to recurse the current graph
                    parentObject = findGameObjectRecursively(o.getParentId(), mappedObjects.values());
                    if(parentObject != null) {
                        parentObject.getChildren().add(o);
                        i.remove();
                    }

                }
            }
        }
    }

    private void logRecordedStats(Stats stats) {
        log.info("Recorded " + stats.getHits().size() + " hits");
        log.info("Recorded " + stats.getKills().size() + " kills");

        log.info("Recorded " + stats.getAircraftHitNotKilledCount() + " aircraft hit but not killed");
        log.info("Recorded " + stats.getAircraftKillCount() + " aircraft kills");
        log.info("Recorded " + stats.getPilotKillCount() + " pilot kills");

        log.info("Recorded " + stats.getNonExplosiveHits() + " non-explosive hits");
        log.info("Recorded " + stats.getUniqueAmmoTypes().toString() + " ammos");
    }

    // T:5 AType:10 PLID:287743 PID:288767 BUL:1200 SH:0 BOMB:0 RCT:0 (188986.344,999.733,138912.453) IDS:a8a19327-93a5-492a-8066-24f32ae0e044 LOGIN:0551fc36-cc61-45ed-9be1-8b393c3abcc7 NAME:Lupson TYPE:Bf 109 G-2 COUNTRY:201 FORM:1 FIELD:0 INAIR:0 PARENT:-1 PAYLOAD:0 FUEL:1.000 SKIN: WM:1
    private void resolveMetaData(Stats stats, List<String> logRows, Integer playerId) {
        for(String row : logRows) {
            if(row.contains(" AType:10 ") && row.contains(" PLID:" + playerId)) {
                stats.setStartingAmmo(Integer.parseInt(row.substring(row.indexOf("BUL:")+4, row.indexOf(" SH:"))));

                // NAME:Lupson TYPE:Bf 109 G-2
                stats.setPilotName(row.substring(row.indexOf(" NAME:")+6, row.indexOf(" TYPE:")));
                stats.setPilotPlane(row.substring(row.indexOf(" TYPE:")+6, row.indexOf(" COUNTRY:")));
                Float[] pos = parsePos(row);
                if(pos != null && pos.length == 3) {
                    mappedObjects.get(playerId).setSpawnedXPos(pos[0]);
                    mappedObjects.get(playerId).setSpawnedZPos(pos[2]);
                }
            }

            if(row.contains(" AType:4 ")) {
                if(row.contains(" PLID:" + playerId)) {
                    stats.setFinalAmmo(Integer.parseInt(row.substring(row.indexOf("BUL:")+4, row.indexOf(" SH:"))));
                }
            }

            // AType:0 GDate:1942.12.11 GTime:11:45:0 MFile:
            if(row.contains(" AType:0 GDate:")) {
                stats.setGameDate(row.substring(row.indexOf(" GDate:")+7, row.indexOf(" GTime:")));
                stats.setGameTime(TimeUtil.pad(row.substring(row.indexOf(" GTime:") + 7, row.indexOf(" MFile:"))));
                stats.setMissionName(row.substring(row.indexOf(" MFile:") + 7, row.indexOf(" MID:")));
            }

            if(row.contains(" AType:17 ")) {
                stats.getFlightTrack().add(parseFlightPosition(row));
            }
        }
    }

    private void resolveDamageOnPilot(Stats stats, List<String> logRows, Integer playerId, List<String> playerEntries) {
        List<String> playerAsTargetEntries = buildPlayerAsTargetEntries(playerId, logRows);
        for(String entry : playerAsTargetEntries) {
            if(entry.contains("AType:1")) {

                // T:65095 AType:1 AMMO:SHELL_GER_20x82_AP AID:1865727 TID:822271
                Matcher matcher = aType1Pattern.matcher(entry);
                while (matcher.find()) {

                    Integer attackerId = Integer.parseInt(entry.substring(entry.indexOf(" AID:")+5, entry.indexOf(" TID:")));
                    Hit hit = new Hit(matcher.group(5), Long.parseLong(matcher.group(2)), attackerId, playerId);
                    //hit.setAttacker(mappedObjects.get(playerId));

                    if(!mappedObjects.containsKey(hit.getAttackerId())) {
                        // Find the Object we were hit by. Store in hashmap.
                        mappedObjects.put(hit.getAttackerId(), findGameObject(logRows, hit.getAttackerId()));
                    }

                    hit.setAttackerName(mappedObjects.get(hit.getAttackerId()).getName().trim());
                    if(     mappedObjects.get(hit.getAttackerId()) != null &&
                            mappedObjects.get(hit.getAttackerId()).getParentId() != null &&
                            mappedObjects.get(hit.getAttackerId()).getParentId() != -1)
                    {
                        hit.setAttacker(findGameObject(logRows, mappedObjects.get(hit.getAttackerId()).getParentId()).getType().trim());
                    } else {
                        hit.setAttacker(mappedObjects.get(hit.getAttackerId()).getType().trim());
                    }

                    // Find the damage-entry for this hit. Match on timestamp
                    for(String row : playerEntries) {
                        if(row.startsWith("T:" + hit.getTime() + " AType:2 DMG:")) {
                            int startIndex = ("T:" + hit.getTime() + " AType:2 DMG:").length();
                            hit.setDamage(Float.parseFloat(row.substring(startIndex, row.indexOf(" AID:"))));
                        }
                    }

                    stats.getHitsTaken().add(hit);
                }
            }
        }
    }

    private void storeKilledObjectsOnStats(Stats stats, Integer playerId) {
        for(Map.Entry<Integer, GameObject> entry : mappedObjects.entrySet()) {
            if(entry.getKey() != -1 && !entry.getValue().getGameObjectId().equals(playerId) && entry.getValue().getState() == State.DESTROYED) {
                if(!stats.getKills().contains(entry.getValue())) {
                    stats.getKills().add(entry.getValue());
                }
            }
        }
    }

    // T:70855 AType:18 BOTID:823295 PARENTID:822271
    private void resolveDestroyedObjects(Stats stats, List<String> logRows) {
        for(Hit h : stats.getHits()) {
           if(h.getTargetId() != -1 && mappedObjects.get(h.getTargetId()).getState() != State.DESTROYED) {
                for(String row : logRows) {
                    if(row.contains("AType:18")) {

                        if(row.contains("AType:18") && row.contains("PARENTID:" + h.getTargetId())) {
                           mappedObjects.get(h.getTargetId()).setState(State.DESTROYED);
                            mappedObjects.get(h.getTargetId()).setTimeOfKill(parseTime(row));

                            Integer botId = Integer.parseInt(row.substring(row.indexOf("BOTID:") + 6, row.indexOf(" PARENTID:")));
                           if(mappedObjects.containsKey(botId)) {
                                mappedObjects.get(botId).setState(State.DESTROYED);
                                mappedObjects.get(botId).setTimeOfKill(parseTime(row));
                                FlightPosition fp = parseFlightPosition(row);
                                if(fp != null) {
                                    mappedObjects.get(botId).setKilledXPos(fp.getX());
                                    mappedObjects.get(botId).setKilledZPos(fp.getZ());
                                }
                            }

                        }
                    }
                }
           }
        }
    }

    private void processPlayerEntries(Stats stats, List<String> logRows, Integer playerId, List<String> playerEntries) {
        for(String entry : playerEntries) {
            if(entry.contains("AType:1")) {
                // T:65095 AType:1 AMMO:SHELL_GER_20x82_AP AID:1865727 TID:822271

                Matcher matcher = aType1Pattern.matcher(entry);
                while (matcher.find()) {

                    Hit hit = new Hit(matcher.group(5), Long.parseLong(matcher.group(2)), playerId, Integer.parseInt(matcher.group(8)));
                    //hit.setAttacker(mappedObjects.get(playerId));

                    if(!mappedObjects.containsKey(hit.getTargetId())) {
                        // Find the Object we've hit. Store in hashmap.
                        mappedObjects.put(hit.getTargetId(), findGameObject(logRows, hit.getTargetId()));
                    }

                    GameObject rootGameObject = findRootGameObject(logRows, hit.getTargetId());
                    hit.setTarget(rootGameObject.getType().trim());

                    String targetName = mappedObjects.get(hit.getTargetId()).getName().trim();
                    if(!targetName.equals("noname")) {
                        hit.setName(targetName);
                    } else {
                        hit.setName(hit.getTarget());
                    }

                    // Find the damage-entry for this hit. Match on timestamp
                    for(String row : playerEntries) {
                        if(row.startsWith("T:" + hit.getTime() + " AType:2 DMG:")) {
                            int startIndex = ("T:" + hit.getTime() + " AType:2 DMG:").length();
                            hit.setDamage(Float.parseFloat(row.substring(startIndex, row.indexOf(" AID:"))));

                        }
                    }

                    stats.getHits().add(hit);
                }
            } else if(entry.contains("AType:3")) {
                // T:70670 AType:3 AID:1865727 TID:822271 POS(114849.367,512.380,131898.188)
                Integer targetId = Integer.parseInt(entry.substring(entry.indexOf(" TID:") + 5, entry.indexOf(" POS(")));
                GameObject gameObject = findGameObject(logRows, targetId);
                if(!stats.getKills().contains(gameObject)) {
                    gameObject.setTimeOfKill(parseTime(entry));
                    Float[] pos = parsePos(entry);
                    if(pos != null && pos.length == 3) {
                        gameObject.setKilledXPos(pos[0]);
                        gameObject.setKilledZPos(pos[2]);
                    }

                    stats.getKills().add(gameObject);
                }
                if(!mappedObjects.containsKey(targetId)) {
                    mappedObjects.put(targetId, gameObject);
                }
            }
        }
    }

    // T:41069 AType:17 ID:138239 POS(226705.891,3185.417,355836.938)
    private FlightPosition parseFlightPosition(String row) {
        try {
            FlightPosition fp = new FlightPosition();
            fp.setMissionTime(parseTime(row));
            Float[] pos = parsePos(row);
            fp.setX(pos[0]);
            fp.setY(pos[1]);
            fp.setZ(pos[2]);
            return fp;
        } catch (Exception e) {
            log.warn("Failed to parse position from row '" + row + "', returning null");
            return null;
        }
    }

    private Float[] parsePos(String row) {
        if(!row.contains(" POS(")) {
            return null;
        }
        String posData = row.substring(row.indexOf(" POS(")+5, row.lastIndexOf(")")-1);
        String[] parts = posData.split(",");
        Float[] res = new Float[3];
        res[0] = Float.parseFloat(parts[0]);
        res[1] = Float.parseFloat(parts[1]);
        res[2] = Float.parseFloat(parts[2]);
        return res;
    }

    private GameObject findRootGameObject(List<String> logRows, Integer targetId) {
        GameObject gameObject = findGameObject(logRows, targetId);
        if(gameObject.getParentId() != null && gameObject.getParentId() != -1 && !gameObject.getParentId().equals(targetId)) {
            return findRootGameObject(logRows, gameObject.getParentId());
        } else {
            return gameObject;
        }
    }


    private GameObject findGameObjectRecursively(Integer idToFind, Collection<GameObject> values) {
        for(GameObject o : values) {
            if(o.getGameObjectId().equals(idToFind)) {
                return o;
            } else {
                GameObject go = findGameObjectRecursively(idToFind, o.getChildren());
                if(go != null) {
                    return go;
                }
            }
        }
        return null;
    }

    private void findAllGameObjects(List<String> logRows) {
        for(String row : logRows) {
            if(row.contains("AType:12")) {
                Integer id = parseId(row);
                allGameObjects.add(buildGameObject(id, row));
            }
        }
    }

    private Integer parseId(String row) {
        return Integer.parseInt(row.substring(row.indexOf(" ID:")+4, row.indexOf(" TYPE:")));
    }


    private GameObject findGameObject(List<String> logRows, Integer id) {
        for(String row : logRows) {
            if(row.contains("AType:12")) {

                if(row.contains("AType:12") && row.contains(" ID:" + id)) {
                    return buildGameObject(id, row);
                }
            }
        }
        return null;
    }

    private GameObject buildGameObject(Integer id, String row) {
        String type = row.substring(row.indexOf("TYPE:")+5, row.indexOf("COUNTRY:"));
        String name = row.substring(row.indexOf("NAME:")+5, row.indexOf("PID:"));
        String parentId = row.substring(row.indexOf(" PID:")+5).split(" ")[0];
        Integer countryCode = Integer.parseInt(row.substring(row.indexOf(" COUNTRY:") + 9, row.indexOf(" NAME:")));
        return new GameObject(id, name, type, parentId.equals("-1") ? GameObjectType.VEHICLE : GameObjectType.PILOT, Integer.parseInt(parentId), countryFromCode(countryCode));
    }

    private String countryFromCode(Integer countryCode) {
        switch(countryCode) {
            case 101:
                return "USSR";
            case 201:
                return "Germany";
            default:
                return "Unknown";
        }
    }

    private List<String> buildPlayerEntries(Integer playerId, List<String> logRows) {
        List<String> pList = new ArrayList<String>();
        for(String row : logRows) {
            if(row.contains("AID:"+playerId)) {
                pList.add(row);
            }
        }
        return pList;
    }

    private List<String> buildPlayerAsTargetEntries(Integer playerId, List<String> logRows) {
        List<String> pList = new ArrayList<String>();
        for(String row : logRows) {
            if(row.contains("TID:"+playerId)) {
                pList.add(row);
            }
        }
        return pList;
    }

    private List<String> toList(String logdata) {
        return Arrays.asList(logdata.split("\r\n"));
    }

    private Integer parsePlayerId(List<String> rows) {
        for(String row : rows) {
            if(row.contains("AType:10 PLID:")) {
                return parsePlayerIdUsingRegExp(row);
            }
        }
        return -1;
    }

    private Integer parsePlayerIdUsingRegExp(String row) {
        Pattern pattern = Pattern.compile("(PLID:?)(\\d+)(.*)");
        Matcher matcher = pattern.matcher(row);

        while (matcher.find()) {
            return Integer.parseInt(matcher.group(2).trim());
        }
        return  -1;
    }

    private String readLogFiles(String directory, String rootFileName) throws IOException {
        return new Concatenator().buildFromBaseFileName(directory, rootFileName);
    }

    // T - tick, it is 1/50 of second
    private Integer parseTime(String row) {
        try {
            return Integer.parseInt(row.substring(2, row.indexOf(" AType:")));
        } catch (Exception e) {
            log.error("Error parsing time from row: " + row);
            log.error("Message: " + e.getMessage());
            return 0;
        }
    }

    public Stats rebuildFromStoredLog(Stats s, String fullLog) {
        return buildStatsFromLogData(s, fullLog);
    }
}
