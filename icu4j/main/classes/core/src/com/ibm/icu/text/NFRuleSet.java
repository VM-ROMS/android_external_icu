/*
 *******************************************************************************
 * Copyright (C) 1996-2015, International Business Machines Corporation and    *
 * others. All Rights Reserved.                                                *
 *******************************************************************************
 */
package com.ibm.icu.text;

import java.text.ParsePosition;
import java.util.ArrayList;
import java.util.List;

import com.ibm.icu.impl.PatternProps;
import com.ibm.icu.impl.Utility;

/**
 * A collection of rules used by a RuleBasedNumberFormat to format and
 * parse numbers.  It is the responsibility of a RuleSet to select an
 * appropriate rule for formatting a particular number and dispatch
 * control to it, and to arbitrate between different rules when parsing
 * a number.
 */

final class NFRuleSet {
    //-----------------------------------------------------------------------
    // data members
    //-----------------------------------------------------------------------

    /**
     * The rule set's name
     */
    private String name;

    /**
     * The rule set's regular rules
     */
    private NFRule[] rules;

    /**
     * The rule set's negative-number rule
     */
    private NFRule negativeNumberRule = null;

    /**
     * The rule set's fraction rules: element 0 is the proper fraction
     * (0.x) rule, element 1 is the improper fraction (x.x) rule, and
     * element 2 is the master (x.0) rule.
     */
    private NFRule[] fractionRules = new NFRule[3];

    /**
     * True if the rule set is a fraction rule set.  A fraction rule set
     * is a rule set that is used to format the fractional part of a
     * number.  It is called from a >> substitution in another rule set's
     * fraction rule, and is only called upon to format values between
     * 0 and 1.  A fraction rule set has different rule-selection
     * behavior than a regular rule set.
     */
    private boolean isFractionRuleSet = false;

    /**
     * True if the rule set is parseable.
     */
    private boolean isParseable = true;

    /**
     * Used to limit recursion for bad rule sets.
     */
    private int recursionCount = 0;

    /**
     * Limit of recursion.
     */
    private static final int RECURSION_LIMIT = 50;

    //-----------------------------------------------------------------------
    // construction
    //-----------------------------------------------------------------------

    /*
     * Constructs a rule set.
     * @param descriptions An array of Strings representing rule set
     * descriptions.  On exit, this rule set's entry in the array will
     * have been stripped of its rule set name and any trailing whitespace.
     * @param index The index into "descriptions" of the description
     * for the rule to be constructed
     */
    public NFRuleSet(String[] descriptions, int index) throws IllegalArgumentException {
        String description = descriptions[index];

        if (description.length() == 0) {
            throw new IllegalArgumentException("Empty rule set description");
        }

        // if the description begins with a rule set name (the rule set
        // name can be omitted in formatter descriptions that consist
        // of only one rule set), copy it out into our "name" member
        // and delete it from the description
        if (description.charAt(0) == '%') {
            int pos = description.indexOf(':');
            if (pos == -1) {
                throw new IllegalArgumentException("Rule set name doesn't end in colon");
            }
            else {
                name = description.substring(0, pos);
                while (pos < description.length() && PatternProps.isWhiteSpace(description.charAt(++pos))) {
                }
                description = description.substring(pos);
                descriptions[index] = description;
            }
        }
        else {
            // if the description doesn't begin with a rule set name, its
            // name is "%default"
            name = "%default";
        }

        if (description.length() == 0) {
            throw new IllegalArgumentException("Empty rule set description");
        }

        if ( name.endsWith("@noparse")) {
            name = name.substring(0,name.length()-8); // Remove the @noparse from the name
            isParseable = false;
        }

        // all of the other members of NFRuleSet are initialized
        // by parseRules()
    }

    /**
     * Construct the subordinate data structures used by this object.
     * This function is called by the RuleBasedNumberFormat constructor
     * after all the rule sets have been created to actually parse
     * the description and build rules from it.  Since any rule set
     * can refer to any other rule set, we have to have created all of
     * them before we can create anything else.
     * @param description The textual description of this rule set
     * @param owner The formatter that owns this rule set
     */
    public void parseRules(String                description,
                           RuleBasedNumberFormat owner) {
        // (the number of elements in the description list isn't necessarily
        // the number of rules-- some descriptions may expend into two rules)
        List<NFRule> tempRules = new ArrayList<NFRule>();

        // we keep track of the rule before the one we're currently working
        // on solely to support >>> substitutions
        NFRule predecessor = null;

        // Iterate through the rules.  The rules
        // are separated by semicolons (there's no escape facility: ALL
        // semicolons are rule delimiters)
        int oldP = 0;
        int descriptionLen = description.length();
        int p;
        do {
            p = description.indexOf(';', oldP);
            if (p < 0) {
                p = descriptionLen;
            }

            // makeRules (a factory method on NFRule) will return either
            // a single rule or an array of rules.  Either way, add them
            // to our rule vector
            NFRule.makeRules(description.substring(oldP, p),
                    this, predecessor, owner, tempRules);
            predecessor = tempRules.get(tempRules.size() - 1);

            oldP = p + 1;
        }
        while (oldP < descriptionLen);

        // for rules that didn't specify a base value, their base values
        // were initialized to 0.  Make another pass through the list and
        // set all those rules' base values.  We also remove any special
        // rules from the list and put them into their own member variables
        long defaultBaseValue = 0;

        // (this isn't a for loop because we might be deleting items from
        // the vector-- we want to make sure we only increment i when
        // we _didn't_ delete anything from the vector)
        int i = 0;
        while (i < tempRules.size()) {
            NFRule rule = tempRules.get(i);

            switch ((int)rule.getBaseValue()) {
                case 0:
                    // if the rule's base value is 0, fill in a default
                    // base value (this will be 1 plus the preceding
                    // rule's base value for regular rule sets, and the
                    // same as the preceding rule's base value in fraction
                    // rule sets)
                    rule.setBaseValue(defaultBaseValue);
                    if (!isFractionRuleSet) {
                        ++defaultBaseValue;
                    }
                    ++i;
                    break;

                case NFRule.NEGATIVE_NUMBER_RULE:
                    // if it's the negative-number rule, copy it into its own
                    // data member and delete it from the list
                    negativeNumberRule = rule;
                    tempRules.remove(i);
                    break;

                case NFRule.IMPROPER_FRACTION_RULE:
                    // if it's the improper fraction rule, copy it into the
                    // correct element of fractionRules
                    fractionRules[0] = rule;
                    tempRules.remove(i);
                    break;

                case NFRule.PROPER_FRACTION_RULE:
                    // if it's the proper fraction rule, copy it into the
                    // correct element of fractionRules
                    fractionRules[1] = rule;
                    tempRules.remove(i);
                    break;

                case NFRule.MASTER_RULE:
                    // if it's the master rule, copy it into the
                    // correct element of fractionRules
                    fractionRules[2] = rule;
                    tempRules.remove(i);
                    break;

                default:
                    // if it's a regular rule that already knows its base value,
                    // check to make sure the rules are in order, and update
                    // the default base value for the next rule
                    if (rule.getBaseValue() < defaultBaseValue) {
                        throw new IllegalArgumentException("Rules are not in order, base: " +
                                rule.getBaseValue() + " < " + defaultBaseValue);
                    }
                    defaultBaseValue = rule.getBaseValue();
                    if (!isFractionRuleSet) {
                        ++defaultBaseValue;
                    }
                    ++i;
                    break;
            }
        }

        // finally, we can copy the rules from the vector into a
        // fixed-length array
        rules = new NFRule[tempRules.size()];
        tempRules.toArray(rules);
    }

    /**
     * Flags this rule set as a fraction rule set.  This function is
     * called during the construction process once we know this rule
     * set is a fraction rule set.  We don't know a rule set is a
     * fraction rule set until we see it used somewhere.  This function
     * is not ad must not be called at any time other than during
     * construction of a RuleBasedNumberFormat.
     */
    public void makeIntoFractionRuleSet() {
        isFractionRuleSet = true;
    }

    //-----------------------------------------------------------------------
    // boilerplate
    //-----------------------------------------------------------------------

    /**
     * Compares two rule sets for equality.
     * @param that The other rule set
     * @return true if the two rule sets are functionally equivalent.
     */
    public boolean equals(Object that) {
        // if different classes, they're not equal
        if (!(that instanceof NFRuleSet)) {
            return false;
        } else {
            // otherwise, compare the members one by one...
            NFRuleSet that2 = (NFRuleSet)that;

            if (!name.equals(that2.name)
                    || !Utility.objectEquals(negativeNumberRule, that2.negativeNumberRule)
                    || !Utility.objectEquals(fractionRules[0], that2.fractionRules[0])
                    || !Utility.objectEquals(fractionRules[1], that2.fractionRules[1])
                    || !Utility.objectEquals(fractionRules[2], that2.fractionRules[2])
                    || rules.length != that2.rules.length
                    || isFractionRuleSet != that2.isFractionRuleSet)
            {
                return false;
            }

            // ...then compare the rule lists...
            for (int i = 0; i < rules.length; i++) {
                if (!rules[i].equals(that2.rules[i])) {
                    return false;
                }
            }

            // ...and if we make it here, they're equal
            return true;
        }
    }

    public int hashCode() {
        assert false : "hashCode not designed";
        return 42;
    }


    /**
     * Builds a textual representation of a rule set.
     * @return A textual representation of a rule set.  This won't
     * necessarily be the same description that the rule set was
     * constructed with, but it will produce the same results.
     */
    public String toString() {
        StringBuilder result = new StringBuilder();

        // the rule set name goes first...
        result.append(name).append(":\n");

        // followed by the regular rules...
        for (int i = 0; i < rules.length; i++) {
            result.append("    ").append(rules[i].toString()).append("\n");
        }

        // followed by the special rules (if they exist)
        if (negativeNumberRule != null) {
            result.append("    ").append(negativeNumberRule.toString()).append("\n");
        }
        if (fractionRules[0] != null) {
            result.append("    ").append(fractionRules[0].toString()).append("\n");
        }
        if (fractionRules[1] != null) {
            result.append("    ").append(fractionRules[1].toString()).append("\n");
        }
        if (fractionRules[2] != null) {
            result.append("    ").append(fractionRules[2].toString()).append("\n");
        }

        return result.toString();
    }

    //-----------------------------------------------------------------------
    // simple accessors
    //-----------------------------------------------------------------------

    /**
     * Says whether this rule set is a fraction rule set.
     * @return true if this rule is a fraction rule set; false if it isn't
     */
    public boolean isFractionSet() {
        return isFractionRuleSet;
    }

    /**
     * Returns the rule set's name
     * @return The rule set's name
     */
    public String getName() {
        return name;
    }

    /**
     * Return true if the rule set is public.
     * @return true if the rule set is public
     */
    public boolean isPublic() {
        return !name.startsWith("%%");
    }

    /**
     * Return true if the rule set can be used for parsing.
     * @return true if the rule set can be used for parsing.
     */
    public boolean isParseable() {
        return isParseable;
    }

    //-----------------------------------------------------------------------
    // formatting
    //-----------------------------------------------------------------------

    /**
     * Formats a long.  Selects an appropriate rule and dispatches
     * control to it.
     * @param number The number being formatted
     * @param toInsertInto The string where the result is to be placed
     * @param pos The position in toInsertInto where the result of
     * this operation is to be inserted
     */
    public void format(long number, StringBuffer toInsertInto, int pos) {
        NFRule applicableRule = findNormalRule(number);

        if (++recursionCount >= RECURSION_LIMIT) {
            recursionCount = 0;
            throw new IllegalStateException("Recursion limit exceeded when applying ruleSet " + name);
        }
        applicableRule.doFormat(number, toInsertInto, pos);
        --recursionCount;
    }

    /**
     * Formats a double.  Selects an appropriate rule and dispatches
     * control to it.
     * @param number The number being formatted
     * @param toInsertInto The string where the result is to be placed
     * @param pos The position in toInsertInto where the result of
     * this operation is to be inserted
     */
    public void format(double number, StringBuffer toInsertInto, int pos) {
        NFRule applicableRule = findRule(number);

        if (++recursionCount >= RECURSION_LIMIT) {
            recursionCount = 0;
            throw new IllegalStateException("Recursion limit exceeded when applying ruleSet " + name);
        }
        applicableRule.doFormat(number, toInsertInto, pos);
        --recursionCount;
    }

    /**
     * Selects an appropriate rule for formatting the number.
     * @param number The number being formatted.
     * @return The rule that should be used to format it
     */
    private NFRule findRule(double number) {
        // if this is a fraction rule set, use findFractionRuleSetRule()
        if (isFractionRuleSet) {
            return findFractionRuleSetRule(number);
        }

        // if the number is negative, return the negative number rule
        // (if there isn't a negative-number rule, we pretend it's a
        // positive number)
        if (number < 0) {
            if (negativeNumberRule != null) {
                return negativeNumberRule;
            } else {
                number = -number;
            }
        }

        // if the number isn't an integer, we use one f the fraction rules...
        if (number != Math.floor(number)) {
            // if the number is between 0 and 1, return the proper
            // fraction rule
            if (number < 1 && fractionRules[1] != null) {
                return fractionRules[1];
            }

            // otherwise, return the improper fraction rule
            else if (fractionRules[0] != null) {
                return fractionRules[0];
            }
        }

        // if there's a master rule, use it to format the number
        if (fractionRules[2] != null) {
            return fractionRules[2];

        } else {
            // and if we haven't yet returned a rule, use findNormalRule()
            // to find the applicable rule
            return findNormalRule(Math.round(number));
        }
    }

    /**
     * If the value passed to findRule() is a positive integer, findRule()
     * uses this function to select the appropriate rule.  The result will
     * generally be the rule with the highest base value less than or equal
     * to the number.  There is one exception to this: If that rule has
     * two substitutions and a base value that is not an even multiple of
     * its divisor, and the number itself IS an even multiple of the rule's
     * divisor, then the result will be the rule that preceded the original
     * result in the rule list.  (This behavior is known as the "rollback
     * rule", and is used to handle optional text: a rule with optional
     * text is represented internally as two rules, and the rollback rule
     * selects appropriate between them.  This avoids things like "two
     * hundred zero".)
     * @param number The number being formatted
     * @return The rule to use to format this number
     */
    private NFRule findNormalRule(long number) {
        // if this is a fraction rule set, use findFractionRuleSetRule()
        // to find the rule (we should only go into this clause if the
        // value is 0)
        if (isFractionRuleSet) {
            return findFractionRuleSetRule(number);
        }

        // if the number is negative, return the negative-number rule
        // (if there isn't one, pretend the number is positive)
        if (number < 0) {
            if (negativeNumberRule != null) {
                return negativeNumberRule;
            } else {
                number = -number;
            }
        }

        // we have to repeat the preceding two checks, even though we
        // do them in findRule(), because the version of format() that
        // takes a long bypasses findRule() and goes straight to this
        // function.  This function does skip the fraction rules since
        // we know the value is an integer (it also skips the master
        // rule, since it's considered a fraction rule.  Skipping the
        // master rule in this function is also how we avoid infinite
        // recursion)

        // binary-search the rule list for the applicable rule
        // (a rule is used for all values from its base value to
        // the next rule's base value)
        int lo = 0;
        int hi = rules.length;
        if (hi > 0) {
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                long ruleBaseValue = rules[mid].getBaseValue();
                if (ruleBaseValue == number) {
                    return rules[mid];
                }
                else if (ruleBaseValue > number) {
                    hi = mid;
                }
                else {
                    lo = mid + 1;
                }
            }
            if (hi == 0) { // bad rule set
                throw new IllegalStateException("The rule set " + name + " cannot format the value " + number);
            }
            NFRule result = rules[hi - 1];

            // use shouldRollBack() to see whether we need to invoke the
            // rollback rule (see shouldRollBack()'s documentation for
            // an explanation of the rollback rule).  If we do, roll back
            // one rule and return that one instead of the one we'd normally
            // return
            if (result.shouldRollBack(number)) {
                if (hi == 1) { // bad rule set
                    throw new IllegalStateException("The rule set " + name + " cannot roll back from the rule '" +
                            result + "'");
                }
                result = rules[hi - 2];
            }
            return result;
        }
        // else use the master rule
        return fractionRules[2];
    }

    /**
     * If this rule is a fraction rule set, this function is used by
     * findRule() to select the most appropriate rule for formatting
     * the number.  Basically, the base value of each rule in the rule
     * set is treated as the denominator of a fraction.  Whichever
     * denominator can produce the fraction closest in value to the
     * number passed in is the result.  If there's a tie, the earlier
     * one in the list wins.  (If there are two rules in a row with the
     * same base value, the first one is used when the numerator of the
     * fraction would be 1, and the second rule is used the rest of the
     * time.
     * @param number The number being formatted (which will always be
     * a number between 0 and 1)
     * @return The rule to use to format this number
     */
    private NFRule findFractionRuleSetRule(double number) {
        // the obvious way to do this (multiply the value being formatted
        // by each rule's base value until you get an integral result)
        // doesn't work because of rounding error.  This method is more
        // accurate

        // find the least common multiple of the rules' base values
        // and multiply this by the number being formatted.  This is
        // all the precision we need, and we can do all of the rest
        // of the math using integer arithmetic
        long leastCommonMultiple = rules[0].getBaseValue();
        for (int i = 1; i < rules.length; i++) {
            leastCommonMultiple = lcm(leastCommonMultiple, rules[i].getBaseValue());
        }
        long numerator = Math.round(number * leastCommonMultiple);

        // for each rule, do the following...
        long tempDifference;
        long difference = Long.MAX_VALUE;
        int winner = 0;
        for (int i = 0; i < rules.length; i++) {
            // "numerator" is the numerator of the fraction is the
            // denominator is the LCD.  The numerator if the the rule's
            // base value is the denominator is "numerator" times the
            // base value divided by the LCD.  Here we check to see if
            // that's an integer, and if not, how close it is to being
            // an integer.
            tempDifference = numerator * rules[i].getBaseValue() % leastCommonMultiple;

            // normalize the result of the above calculation: we want
            // the numerator's distance from the CLOSEST multiple
            // of the LCD
            if (leastCommonMultiple - tempDifference < tempDifference) {
                tempDifference = leastCommonMultiple - tempDifference;
            }

            // if this is as close as we've come, keep track of how close
            // that is, and the line number of the rule that did it.  If
            // we've scored a direct hit, we don't have to look at any more
            // rules
            if (tempDifference < difference) {
                difference = tempDifference;
                winner = i;
                if (difference == 0) {
                    break;
                }
            }
        }

        // if we have two successive rules that both have the winning base
        // value, then the first one (the one we found above) is used if
        // the numerator of the fraction is 1 and the second one is used if
        // the numerator of the fraction is anything else (this lets us
        // do things like "one third"/"two thirds" without having to define
        // a whole bunch of extra rule sets)
        if (winner + 1 < rules.length
                && rules[winner + 1].getBaseValue() == rules[winner].getBaseValue()) {
            if (Math.round(number * rules[winner].getBaseValue()) < 1
                    || Math.round(number * rules[winner].getBaseValue()) >= 2) {
                ++winner;
            }
        }

        // finally, return the winning rule
        return rules[winner];
    }

    /**
     * Calculates the least common multiple of x and y.
     */
    private static long lcm(long x, long y) {
        // binary gcd algorithm from Knuth, "The Art of Computer Programming,"
        // vol. 2, 1st ed., pp. 298-299
        long x1 = x;
        long y1 = y;

        int p2 = 0;
        while ((x1 & 1) == 0 && (y1 & 1) == 0) {
            ++p2;
            x1 >>= 1;
            y1 >>= 1;
        }

        long t;
        if ((x1 & 1) == 1) {
            t = -y1;
        } else {
            t = x1;
        }

        while (t != 0) {
            while ((t & 1) == 0) {
                t >>= 1;
            }
            if (t > 0) {
                x1 = t;
            } else {
                y1 = -t;
            }
            t = x1 - y1;
        }
        long gcd = x1 << p2;

        // x * y == gcd(x, y) * lcm(x, y)
        return x / gcd * y;
    }

    //-----------------------------------------------------------------------
    // parsing
    //-----------------------------------------------------------------------

    /**
     * Parses a string.  Matches the string to be parsed against each
     * of its rules (with a base value less than upperBound) and returns
     * the value produced by the rule that matched the most characters
     * in the source string.
     * @param text The string to parse
     * @param parsePosition The initial position is ignored and assumed
     * to be 0.  On exit, this object has been updated to point to the
     * first character position this rule set didn't consume.
     * @param upperBound Limits the rules that can be allowed to match.
     * Only rules whose base values are strictly less than upperBound
     * are considered.
     * @return The numerical result of parsing this string.  This will
     * be the matching rule's base value, composed appropriately with
     * the results of matching any of its substitutions.  The object
     * will be an instance of Long if it's an integral value; otherwise,
     * it will be an instance of Double.  This function always returns
     * a valid object: If nothing matched the input string at all,
     * this function returns new Long(0), and the parse position is
     * left unchanged.
     */
    public Number parse(String text, ParsePosition parsePosition, double upperBound) {
        // try matching each rule in the rule set against the text being
        // parsed.  Whichever one matches the most characters is the one
        // that determines the value we return.

        ParsePosition highWaterMark = new ParsePosition(0);
        Number result = Long.valueOf(0);
        Number tempResult = null;

        // dump out if there's no text to parse
        if (text.length() == 0) {
            return result;
        }

        // start by trying the negative number rule (if there is one)
        if (negativeNumberRule != null) {
            tempResult = negativeNumberRule.doParse(text, parsePosition, false, upperBound);
            if (parsePosition.getIndex() > highWaterMark.getIndex()) {
                result = tempResult;
                highWaterMark.setIndex(parsePosition.getIndex());
            }
// commented out because the error-index API on ParsePosition isn't there in 1.1.x
//            if (parsePosition.getErrorIndex() > highWaterMark.getErrorIndex()) {
//                highWaterMark.setErrorIndex(parsePosition.getErrorIndex());
//            }
            parsePosition.setIndex(0);
        }

        // then try each of the fraction rules
        for (int i = 0; i < 3; i++) {
            if (fractionRules[i] != null) {
                tempResult = fractionRules[i].doParse(text, parsePosition, false, upperBound);
                if (parsePosition.getIndex() > highWaterMark.getIndex()) {
                    result = tempResult;
                    highWaterMark.setIndex(parsePosition.getIndex());
                }
// commented out because the error-index API on ParsePosition isn't there in 1.1.x
//            if (parsePosition.getErrorIndex() > highWaterMark.getErrorIndex()) {
//                highWaterMark.setErrorIndex(parsePosition.getErrorIndex());
//            }
                parsePosition.setIndex(0);
            }
        }

        // finally, go through the regular rules one at a time.  We start
        // at the end of the list because we want to try matching the most
        // significant rule first (this helps ensure that we parse
        // "five thousand three hundred six" as
        // "(five thousand) (three hundred) (six)" rather than
        // "((five thousand three) hundred) (six)").  Skip rules whose
        // base values are higher than the upper bound (again, this helps
        // limit ambiguity by making sure the rules that match a rule's
        // are less significant than the rule containing the substitutions)/
        for (int i = rules.length - 1; i >= 0 && highWaterMark.getIndex() < text.length(); i--) {
            if (!isFractionRuleSet && rules[i].getBaseValue() >= upperBound) {
                continue;
            }

            tempResult = rules[i].doParse(text, parsePosition, isFractionRuleSet, upperBound);
            if (parsePosition.getIndex() > highWaterMark.getIndex()) {
                result = tempResult;
                highWaterMark.setIndex(parsePosition.getIndex());
            }
// commented out because the error-index API on ParsePosition isn't there in 1.1.x
//            if (parsePosition.getErrorIndex() > highWaterMark.getErrorIndex()) {
//                highWaterMark.setErrorIndex(parsePosition.getErrorIndex());
//            }
            parsePosition.setIndex(0);
        }

        // finally, update the parse position we were passed to point to the
        // first character we didn't use, and return the result that
        // corresponds to that string of characters
        parsePosition.setIndex(highWaterMark.getIndex());
// commented out because the error-index API on ParsePosition isn't there in 1.1.x
//        if (parsePosition.getIndex() == 0) {
//            parsePosition.setErrorIndex(highWaterMark.getErrorIndex());
//        }

        return result;
    }
}
