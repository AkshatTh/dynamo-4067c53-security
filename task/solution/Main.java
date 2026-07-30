import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class Main {
    static class Event {
        String eventId;
        String type;
        String parentEventId;
        String targetEventId;
        String payerId;
        double amount;
        List<String> participants = new ArrayList<>();
        long validTimestamp;
        long systemRevision;
    }

    public static void main(String[] args) {
        String inputPath = args.length > 0 ? args[0] : "/app/ledger_events.json";
        String outputPath = args.length > 1 ? args[1] : "/app/balances.json";

        try {
            String json = new String(Files.readAllBytes(Paths.get(inputPath)));
            
            long validCutoff = parseLongKey(json, "valid_timestamp_cutoff");
            long systemCutoff = parseLongKey(json, "system_revision_cutoff");

            List<Event> events = parseEvents(json);

            // 1. All seen users in ANY payment event in the input file
            Set<String> allUsersSet = new TreeSet<>();
            for (Event e : events) {
                if ("payment".equals(e.type)) {
                    if (e.payerId != null) allUsersSet.add(e.payerId);
                    if (e.participants != null) {
                        for (String p : e.participants) {
                            allUsersSet.add(p);
                        }
                    }
                }
            }

            // 2. Global Filter First
            List<Event> surviving = new ArrayList<>();
            for (Event e : events) {
                if (e.validTimestamp <= validCutoff && e.systemRevision <= systemCutoff) {
                    surviving.add(e);
                }
            }

            // Index surviving events
            Map<String, Event> survivingPayments = new HashMap<>();
            Map<String, List<Event>> statusEvents = new HashMap<>();
            Map<String, List<Event>> correctionEvents = new HashMap<>();

            for (Event e : surviving) {
                if ("payment".equals(e.type)) {
                    survivingPayments.put(e.eventId, e);
                } else if ("revocation".equals(e.type) || "reinstate".equals(e.type)) {
                    statusEvents.computeIfAbsent(e.targetEventId, k -> new ArrayList<>()).add(e);
                } else if ("correction".equals(e.type)) {
                    correctionEvents.computeIfAbsent(e.targetEventId, k -> new ArrayList<>()).add(e);
                }
            }

            // Comparator: highest system_revision, tie-break by event_id ascending
            Comparator<Event> tieBreaker = (a, b) -> {
                if (a.systemRevision != b.systemRevision) {
                    return Long.compare(b.systemRevision, a.systemRevision);
                }
                return a.eventId.compareTo(b.eventId);
            };

            // 3. Base status & effective amount per payment
            Map<String, Boolean> baseStatus = new HashMap<>();
            Map<String, Long> effectiveAmountCents = new HashMap<>();

            for (Map.Entry<String, Event> entry : survivingPayments.entrySet()) {
                String pId = entry.getKey();
                Event pEvt = entry.getValue();

                // Base status
                List<Event> stList = statusEvents.get(pId);
                if (stList == null || stList.isEmpty()) {
                    baseStatus.put(pId, true);
                } else {
                    stList.sort(tieBreaker);
                    Event winner = stList.get(0);
                    baseStatus.put(pId, "reinstate".equals(winner.type));
                }

                // Effective amount
                List<Event> corrList = correctionEvents.get(pId);
                if (corrList == null || corrList.isEmpty()) {
                    effectiveAmountCents.put(pId, Math.round(pEvt.amount * 100.0));
                } else {
                    corrList.sort(tieBreaker);
                    Event winner = corrList.get(0);
                    effectiveAmountCents.put(pId, Math.round(winner.amount * 100.0));
                }
            }

            // 4. Ancestry activation, Cycle & Orphan detection
            Map<String, Boolean> trulyActive = new HashMap<>();
            for (String pId : survivingPayments.keySet()) {
                String curr = pId;
                Set<String> visited = new HashSet<>();
                boolean active = true;

                while (curr != null) {
                    if (visited.contains(curr)) {
                        active = false; // Cycle
                        break;
                    }
                    visited.add(curr);

                    if (!survivingPayments.containsKey(curr)) {
                        active = false; // Orphan
                        break;
                    }

                    if (!baseStatus.getOrDefault(curr, false)) {
                        active = false; // Revoked ancestor
                        break;
                    }

                    curr = survivingPayments.get(curr).parentEventId;
                }
                trulyActive.put(pId, active);
            }

            // 5. Net balance computation
            Map<String, Long> balancesCents = new TreeMap<>();
            for (String u : allUsersSet) {
                balancesCents.put(u, 0L);
            }

            for (Map.Entry<String, Event> entry : survivingPayments.entrySet()) {
                String pId = entry.getKey();
                if (!trulyActive.getOrDefault(pId, false)) {
                    continue;
                }

                Event pEvt = entry.getValue();
                long amtCents = effectiveAmountCents.get(pId);
                String payer = pEvt.payerId;

                Set<String> uniqueParticipantsSet = new TreeSet<>(pEvt.participants);
                List<String> uniqueParticipants = new ArrayList<>(uniqueParticipantsSet);
                int N = uniqueParticipants.size();

                if (payer != null && balancesCents.containsKey(payer)) {
                    balancesCents.put(payer, balancesCents.get(payer) + amtCents);
                }

                if (N > 0) {
                    long baseShare = amtCents / N;
                    long remainder = amtCents % N;

                    for (int i = 0; i < N; i++) {
                        String u = uniqueParticipants.get(i);
                        long share = baseShare + (i < remainder ? 1 : 0);
                        if (balancesCents.containsKey(u)) {
                            balancesCents.put(u, balancesCents.get(u) - share);
                        }
                    }
                }
            }

            // 6. Formatting JSON output
            StringBuilder sb = new StringBuilder("{\n");
            int count = 0;
            int total = balancesCents.size();
            for (Map.Entry<String, Long> entry : balancesCents.entrySet()) {
                count++;
                String u = entry.getKey();
                long cents = entry.getValue();
                double dollars = cents / 100.0;
                String fmt = String.format(Locale.US, "%.2f", dollars);
                if ("-0.00".equals(fmt)) fmt = "0.00";

                sb.append("  \"").append(u).append("\": \"").append(fmt).append("\"");
                if (count < total) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("}\n");

            Files.write(Paths.get(outputPath), sb.toString().getBytes());
            System.out.println("Successfully generated " + outputPath);

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static long parseLongKey(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        return 0;
    }

    private static List<Event> parseEvents(String json) {
        List<Event> list = new ArrayList<>();
        int eventsIdx = json.indexOf("\"events\"");
        if (eventsIdx == -1) return list;

        int startArray = json.indexOf("[", eventsIdx);
        int endArray = json.lastIndexOf("]");
        if (startArray == -1 || endArray == -1 || startArray >= endArray) return list;

        String arrayContent = json.substring(startArray + 1, endArray);

        List<String> objectStrings = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start != -1) {
                    objectStrings.add(arrayContent.substring(start, i + 1));
                    start = -1;
                }
            }
        }

        for (String objStr : objectStrings) {
            Event e = new Event();
            e.eventId = parseStringField(objStr, "event_id");
            e.type = parseStringField(objStr, "type");
            e.parentEventId = parseStringField(objStr, "parent_event_id");
            e.targetEventId = parseStringField(objStr, "target_event_id");
            e.payerId = parseStringField(objStr, "payer_id");
            e.amount = parseDoubleField(objStr, "amount");
            e.validTimestamp = parseLongField(objStr, "valid_timestamp");
            e.systemRevision = parseLongField(objStr, "system_revision");
            e.participants = parseStringArray(objStr, "participants");
            list.add(e);
        }

        return list;
    }

    private static String parseStringField(String obj, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(obj);
        if (m.find()) return m.group(1);

        Pattern nullP = Pattern.compile("\"" + key + "\"\\s*:\\s*null");
        Matcher nullM = nullP.matcher(obj);
        if (nullM.find()) return null;

        return null;
    }

    private static double parseDoubleField(String obj, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
        Matcher m = p.matcher(obj);
        if (m.find()) return Double.parseDouble(m.group(1));
        return 0.0;
    }

    private static long parseLongField(String obj, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9]+)");
        Matcher m = p.matcher(obj);
        if (m.find()) return Long.parseLong(m.group(1));
        return 0L;
    }

    private static List<String> parseStringArray(String obj, String key) {
        List<String> result = new ArrayList<>();
        int idx = obj.indexOf("\"" + key + "\"");
        if (idx == -1) return result;

        int startBracket = obj.indexOf("[", idx);
        int endBracket = obj.indexOf("]", startBracket);
        if (startBracket == -1 || endBracket == -1) return result;

        String content = obj.substring(startBracket + 1, endBracket);
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(content);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }
}
