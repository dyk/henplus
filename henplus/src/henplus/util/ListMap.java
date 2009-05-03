/*
 * This is free software, licensed under the Gnu Public License (GPL)
 * get a copy from <http://www.gnu.org/licenses/gpl.html>
 * @version $Id: ListMap.java,v 1.6 2005-11-27 16:20:28 hzeller Exp $
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
package henplus.util;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

/**
 * This provides the functionality of LinkedHashMap. However, that Collection
 * became available at 1.4. So provide this for backward compatibility.
 * FIXME: we bumped to 1.5 compatibility, so we can actually use the Standard implementation.
 * 
 * @author Martin Grotzke
 */
@Deprecated
public final class ListMap implements Map, Serializable {
    private static final long serialVersionUID = 1;

    private final List keys;
    private final List values;

    public ListMap() {
        keys = new ArrayList();
        values = new ArrayList();
    }

    public int size() {
        return keys.size();
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    public boolean containsKey(final Object key) {
        return keys.contains(key);
    }

    public boolean containsValue(final Object value) {
        return values.contains(value);
    }

    public Object get(final Object key) {
        final int index = keys.indexOf(key);
        return index > -1 ? values.get(index) : null;
    }

    public Object put(final Object key, final Object value) {
        final Object orgValue = get(key);
        keys.add(key);
        values.add(value);
        return orgValue;
    }

    public Object remove(final Object key) {
        final Object orgValue = get(key);
        keys.remove(key);
        values.remove(orgValue);
        return orgValue;
    }

    public void putAll(final Map t) {
        /** @todo Implement this java.util.Map method */
        throw new java.lang.UnsupportedOperationException(
        "Method putAll() not yet implemented.");
    }

    public void clear() {
        keys.clear();
        values.clear();
    }

    public Set keySet() {
        return new HashSet(keys);
    }

    /**
     * Returns a <code>List</code> containing all keys.
     * 
     * @return a <code>List</code> containing all keys.
     */
    public List keys() {
        return keys;
    }

    /**
     * Returns a <code>ListIterator</code> over the keys. Use this method
     * instead of combining the <code>keySet</code> with it's
     * <code>iterator</code> method.
     */
    public ListIterator keysListIterator() {
        return keys.listIterator();
    }

    /**
     * Returns the values as a <code>Collection</code>, as defined in
     * <code>java.util.Map</code>.
     */
    public Collection values() {
        return (Collection) ((ArrayList) values).clone();
    }

    /**
     * Returns the values as a <code>List</code>.
     */
    public List valuesList() {
        return (List) ((ArrayList) values).clone();
    }

    /**
     * Returns a <code>ListIterator</code> over the values.
     */
    public ListIterator valuesListIterator() {
        return values.listIterator();
    }

    public Set entrySet() {
        /** @todo Implement this java.util.Map method */
        throw new java.lang.UnsupportedOperationException(
        "Method entrySet() not yet implemented.");
    }

    @Override
    public boolean equals(final Object o) {
        /** @todo Implement this java.util.Map method */
        throw new java.lang.UnsupportedOperationException(
        "Method equals() not yet implemented.");
    }

    @Override
    public int hashCode() {
        /** @todo Implement this java.util.Map method */
        throw new java.lang.UnsupportedOperationException(
        "Method hashcode() not yet implemented.");
    }

    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer();
        sb.append("ListMap [");
        for (int i = 0; i < keys.size(); i++) {
            sb.append(keys.get(i)).append(", ");
        }
        sb.delete(sb.length() - 2, sb.length());
        sb.append("]");
        return sb.toString();
    }
}
