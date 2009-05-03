/*
 * This is free software, licensed under the Gnu Public License (GPL)
 * get a copy from <http://www.gnu.org/licenses/gpl.html>
 * $Id: PropertyRegistry.java,v 1.5 2004-03-05 23:34:38 hzeller Exp $
 * author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus;

import java.util.SortedMap;
import java.util.TreeMap;

import henplus.property.PropertyHolder;

/**
 * A Registry that binds names to Properties.
 */
public class PropertyRegistry {
    private final SortedMap/* <String,PropertyHolder> */_namedProperties;

    public PropertyRegistry() {
        _namedProperties = new TreeMap();
    }

    /**
     * Every command or subsystem that needs to be able to set Properties, needs
     * to register its property here.
     */
    public void registerProperty(final String name, final PropertyHolder holder)
    throws IllegalArgumentException {
        if (_namedProperties.containsKey(name)) {
            throw new IllegalArgumentException("Property named '" + name
                    + "' already exists");
        }
        _namedProperties.put(name, holder);
    }

    public void unregisterProperty(final String name) {
        _namedProperties.remove(name);
    }

    /**
     * sets the Property to the given value. This throws an Exception, if the
     * PropertyHolder vetoes this attempt or if there is simply no Property
     * bound to the given name.
     * 
     * @param name
     *            the name the property is bound to.
     * @param value
     *            the new value of the property to be set.
     * @throws Exception
     *             , if the property does not exist or throws an Exception to
     *             veto the new value.
     */
    public void setProperty(final String name, final String value) throws Exception {
        final PropertyHolder holder = (PropertyHolder) _namedProperties.get(name);
        if (holder == null) {
            throw new IllegalArgumentException("unknown property '" + name
                    + "'");
        }
        holder.setValue(value);
    }

    /**
     * returns a Map view of property-Names to values. The returned Map must not
     * be modified.
     */
    public SortedMap/* <String,PropertyHolder> */getPropertyMap() {
        return _namedProperties;
    }
}

/*
 * Local variables: c-basic-offset: 4 compile-command:
 * "ant -emacs -find build.xml" End:
 */
