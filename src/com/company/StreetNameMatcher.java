package com.company;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by nick on 11/12/15.
 */
public class StreetNameMatcher {
    private final HashMap<String, String> streetAbbreviations, directionAbbreviations, numericAbbreviations;

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
}
