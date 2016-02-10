package Conflation;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by nick on 11/12/15.
 */
public class StreetNameMatcher {
    private final HashMap<String, String> streetAbbreviations, directionAbbreviations, numericAbbreviations;

    private final static Comparator<StreetNameMatch> STREET_NAME_MATCH_COMPARATOR = new Comparator<StreetNameMatch>() {
        @Override
        public int compare(StreetNameMatch o1, StreetNameMatch o2) {
            return o1.levenshteinDistance > o2.levenshteinDistance ? -1 : 1;
        }
    };
    public class StreetNameMatch {
        public final String streetName1, streetName2;
        public final int levenshteinDistance;
        public StreetNameMatch(final String street1, final String street2) {
            streetName1 = street1;
            streetName2 = street2;
            levenshteinDistance = damerauLevenshteinDistance(street1, street2, 128);
        }
    }
    public class StreetNameComponents {
        final String originalName, originalNameExpanded;
        final List<String> baseComponents, directionComponents, typeComponents;

        public StreetNameComponents(final String streetName) {
            originalName = streetName;

            final String[] components = streetName.split("\\.? ");
            baseComponents = new ArrayList<>(components.length);
            directionComponents = new ArrayList<>(components.length);
            typeComponents = new ArrayList<>(components.length);
            for(int i=0;i<components.length;i++) {
                if(directionAbbreviations.containsKey(components[i])) {
                    components[i] = directionAbbreviations.get(components[i]);
                    directionComponents.add(components[i]);
                } else if(directionAbbreviations.containsValue(components[i])) {
                    directionComponents.add(components[i]);
                } else if(streetAbbreviations.containsKey(components[i])) {
                    components[i] = streetAbbreviations.get(components[i]);
                    typeComponents.add(components[i]);
                } else if(streetAbbreviations.containsValue(components[i])) {
                    typeComponents.add(components[i]);
                } else {
                    baseComponents.add(components[i]);
                }
            }
            originalNameExpanded = String.join(" ", components);
        }
    }

    public StreetNameMatcher(Locale locale) {
        streetAbbreviations = new HashMap<>(128);
        directionAbbreviations = new HashMap<>(32);
        numericAbbreviations = new HashMap<>(8);

        streetAbbreviations.put("Ave", "Avenue");
        streetAbbreviations.put("St", "Street");
        streetAbbreviations.put("Wy", "Way");
        streetAbbreviations.put("Dr", "Drive");
        streetAbbreviations.put("Blvd", "Boulevard");
        streetAbbreviations.put("Bl", "Boulevard");
        streetAbbreviations.put("Pl", "Place");
        streetAbbreviations.put("Rd", "Road");
        streetAbbreviations.put("P&R", "Park and Ride");
        streetAbbreviations.put("Ct", "Court");

        numericAbbreviations.put("1st", "First");
        numericAbbreviations.put("2nd", "Second");
        numericAbbreviations.put("3rd", "Third");
        numericAbbreviations.put("4th", "Fourth");
        numericAbbreviations.put("5th", "Fifth");
        numericAbbreviations.put("6th", "Sixth");
        numericAbbreviations.put("7th", "Seventh");
        numericAbbreviations.put("8th", "Eighth");
        numericAbbreviations.put("9th", "Ninth");

        directionAbbreviations.put("N", "North");
        directionAbbreviations.put("NE", "Northeast");
        directionAbbreviations.put("E", "East");
        directionAbbreviations.put("SE", "Southeast");
        directionAbbreviations.put("S", "South");
        directionAbbreviations.put("SW", "Southwest");
        directionAbbreviations.put("W", "West");
        directionAbbreviations.put("NW", "Northwest");
        directionAbbreviations.put("N.", "North");
        directionAbbreviations.put("NE.", "Northeast");
        directionAbbreviations.put("E.", "East");
        directionAbbreviations.put("SE.", "Southeast");
        directionAbbreviations.put("S.", "South");
        directionAbbreviations.put("SW.", "Southwest");
        directionAbbreviations.put("W.", "West");
        directionAbbreviations.put("NW.", "Northwest");
    }
    public StreetNameComponents createComponents(final String streetName) {
        return new StreetNameComponents(streetName);
    }
    private String matchPattern(final String subject, final Pattern pattern, final HashMap<String, String> definedAbbreviations) {
        final Matcher dmPrefix = pattern.matcher(subject);
        if(dmPrefix.find()) {
            //System.out.println("GROUP: " + dmPrefix.group());
            final String match = dmPrefix.group(1);
            final int startMatch = dmPrefix.start(1), endMatch = dmPrefix.end(1);
            for (Map.Entry<String, String> dir : definedAbbreviations.entrySet()) {
                if (match.equals(dir.getKey())) {
                    return subject.substring(0, startMatch) + dir.getValue() + subject.substring(endMatch);
                }
            }
        }
        return subject;
    }
    public List<StreetNameMatch> matchStreetName(final String streetName, final String[] otherStreetNames) {
        final List<StreetNameMatch> matches = new ArrayList<>(otherStreetNames.length);
        final String expandedStreetName = expandedStreetName(streetName);

        for(final String otherStreetName : otherStreetNames) {
            matches.add(new StreetNameMatch(expandedStreetName, expandedStreetName(otherStreetName)));
        }
        matches.sort(STREET_NAME_MATCH_COMPARATOR);
        return matches;
    }
    public String expandedStreetName(final String abbreviatedName) {
        final String[] components = abbreviatedName.split("\\.? ");
        for(int i=0;i<components.length;i++) {
            if(directionAbbreviations.containsKey(components[i])) {
                components[i] = directionAbbreviations.get(components[i]);
            } else if(streetAbbreviations.containsKey(components[i])) {
                components[i] = streetAbbreviations.get(components[i]);
            }
        }
        return String.join(" ", components);
/*

        final Pattern directionPatternPrefix = Pattern.compile("^([a-z]{1,2})\\.? ", Pattern.CASE_INSENSITIVE); //e.g. SW Bronson Ave
        final Pattern directionPatternSuffix = Pattern.compile(" ([a-z]{1,2})\\.?$", Pattern.CASE_INSENSITIVE); //e.g. Bronson Ave SW
        final Pattern streetPatternPrefix = Pattern.compile("^([a-z]{2,4})\\.? ", Pattern.CASE_INSENSITIVE); //e.g. Ave Bronson
        final Pattern streetPatternContained = Pattern.compile(" ([a-z]{2,4})\\.?[^a-z]", Pattern.CASE_INSENSITIVE); //e.g. Bronson Ave N
        final Pattern streetPatternSuffix = Pattern.compile(" ([a-z]{2,4})\\.?$", Pattern.CASE_INSENSITIVE); //e.g. Bronson Ave

        String expandedName = abbreviatedName;
        expandedName = matchPattern(expandedName, directionPatternPrefix, directionAbbreviations);
        expandedName = matchPattern(expandedName, directionPatternSuffix, directionAbbreviations);
        expandedName = matchPattern(expandedName, streetPatternPrefix, streetAbbreviations);
        expandedName = matchPattern(expandedName, streetPatternContained, streetAbbreviations);
        expandedName = matchPattern(expandedName, streetPatternSuffix, streetAbbreviations);

        return expandedName;*/
    }
    public static int damerauLevenshteinDistance (final String a, final String b, final int alphabetLength) {
        final int INFINITY = a.length() + b.length();
        int[][] H = new int[a.length()+2][b.length()+2];
        H[0][0] = INFINITY;
        for(int i = 0; i<=a.length(); i++) {
            H[i+1][1] = i;
            H[i+1][0] = INFINITY;
        }
        for(int j = 0; j<=b.length(); j++) {
            H[1][j+1] = j;
            H[0][j+1] = INFINITY;
        }
        int[] DA = new int[alphabetLength];
        Arrays.fill(DA, 0);
        for(int i = 1; i<=a.length(); i++) {
            int DB = 0;
            for(int j = 1; j<=b.length(); j++) {
                int i1 = DA[b.charAt(j-1)];
                int j1 = DB;
                int d = ((a.charAt(i-1)==b.charAt(j-1))?0:1);
                if(d==0) DB = j;
                H[i+1][j+1] =
                        min(H[i][j]+d,
                                H[i+1][j] + 1,
                                H[i][j+1]+1,
                                H[i1][j1] + (i-i1-1) + 1 + (j-j1-1));
            }
            DA[a.charAt(i-1)] = i;
        }
        return H[a.length()+1][b.length()+1];
    }

    private static int min(int ... nums) {
        int min = Integer.MAX_VALUE;
        for (int num : nums) {
            min = Math.min(min, num);
        }
        return min;
    }
}
