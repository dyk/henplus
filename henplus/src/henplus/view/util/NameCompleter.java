/*
 * This is free software, licensed under the Gnu Public License (GPL)
 * get a copy from <http://www.gnu.org/licenses/gpl.html>
 * $Id: NameCompleter.java,v 1.5 2004-03-23 11:05:38 magrokosmos Exp $ 
 * author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus.view.util;

import java.util.Collection;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.TreeMap;

/**
 * a Completer for names that are only given partially. This is used for
 * tab-completion or to automatically correct names.
 */
public class NameCompleter {
    private final SortedSet nameSet;
    private final SortedMap canonicalNames;

    public NameCompleter() {
	nameSet = new TreeSet();
	canonicalNames = new TreeMap();
    }
    
    public NameCompleter(Iterator names) {
	this();
	while (names.hasNext()) {
	    addName((String) names.next());
	}
    }

    public NameCompleter(Collection c) {
	this(c.iterator());
    }

    public NameCompleter(String names[]) {
        this();
        for (int i=0; i < names.length; ++i) {
            addName(names[i]);
        }
    }

    public void addName(String name) {
	nameSet.add(name);
	canonicalNames.put(name.toLowerCase(), name);
    }
    
    public Iterator getAllNamesIterator() {
        return nameSet.iterator();
    }
    
    public SortedSet getAllNames() {
        return nameSet;
    }

    public String findCaseInsensitive(String name) {
        if (name == null)
            return null;
        name = name.toLowerCase();
        return (String) canonicalNames.get(name);
    }

    /**
     * returns an iterator with alternatives that match the partial name
     * given or 'null' if there is no alternative.
     */
    public Iterator getAlternatives(String partialName) {
	// first test, if we find the name directly
	Iterator testIt = nameSet.tailSet(partialName).iterator();
	String testMatch = (testIt.hasNext()) ? (String) testIt.next() : null;
	if (testMatch == null || !testMatch.startsWith(partialName)) {
	    String canonical = partialName.toLowerCase();
	    testIt = canonicalNames.tailMap(canonical).keySet().iterator();
	    testMatch = (testIt.hasNext()) ? (String) testIt.next() : null;
	    if (testMatch == null || !testMatch.startsWith(canonical))
		return null; // nope.
	    String foundName = (String) canonicalNames.get(testMatch);
	    partialName = foundName.substring(0, partialName.length());
	}

        return new SortedMatchIterator(partialName, nameSet);
    }
}

/*
 * Local variables:
 * c-basic-offset: 4
 * compile-command: "ant -emacs -find build.xml"
 * End:
 */
