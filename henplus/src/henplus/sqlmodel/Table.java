/*
 * This is free software, licensed under the Gnu Public License (GPL)
 * get a copy from <http://www.gnu.org/licenses/gpl.html>
 * @version $Id: Table.java,v 1.5 2004-09-22 11:49:32 magrokosmos Exp $ 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
package henplus.sqlmodel;

import henplus.util.ListMap;

import java.util.HashSet;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.Set;

public final class Table implements Comparable {
    
    private String _name;
    private ListMap /*<String, Column>*/ _columns;

    // private PrimaryKey _pk;
    
    // FIXME: add notion of schema.

    public Table(String name) {
        _name = name;
        _columns = new ListMap();
    }

    public String getName() {
        return _name;
    }

    public void setName(String string) {
        _name = string;
    }
    
    public void addColumn(Column column) {
        _columns.put(column.getName(), column);
    }
    
    public ListIterator getColumnIterator() {
        ListIterator result = null;
        if (_columns != null) {
            result = _columns.valuesListIterator();
        }
        return result;
    }
    
    public Column getColumnByName(String name, boolean ignoreCase) {
        Column result = null;
        if (_columns != null) {
            result = (Column)_columns.get(name);
            if (result == null && ignoreCase) {
                final Iterator iter = _columns.keysListIterator();
                while (iter.hasNext()) {
                    String colName = (String)iter.next();
                    if (colName.equalsIgnoreCase(name)) {
                        result = (Column)_columns.get(colName);
                        break;
                    }
                }
            }
        }
        return result;
    }
    
    /**
     * @return <code>true</code>, if this <code>Table</code> has any foreign key, otherwise <code>false</code>.
     */
    public boolean hasForeignKeys() {
        return getForeignKeys() != null;
    }
    
    /**
     * @return A <code>Set</code> of <code>ColumnFkInfo</code> objects or <code>null</code>.
     */
    public Set/*<ColumnFkInfo>*/ getForeignKeys() {
        Set result = null;

        if (_columns != null) {
            final Iterator iter = _columns.values().iterator();
            while ( iter.hasNext() ) {
                Column c = (Column) iter.next();
                if ( c.getFkInfo() != null ) {
                    if ( result == null )
                        result = new HashSet();
                    result.add( c.getFkInfo() );
                }
            }
        }
        
        return result;
    }
    
    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    public String toString() {
        return _name;
    }
    
    public int hashCode() {
        return _name.hashCode();
    }
    
    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    public boolean equals( Object other ) {
        boolean result = false;
        
        if ( other == this )
            result = true;
        
        else if ( other instanceof Table ) {
            Table o = (Table)other;
            
            if ( _name != null && _name.equals( o.getName() ) )
                result = true;
            
            else if ( _name == null && o.getName() == null )
                result = true;
        }
        
        return result;
    }

    /* (non-Javadoc)
     * @see java.lang.Comparable#compareTo(java.lang.Object)
     */
    public int compareTo( Object other ) {
        int result = 0;
        if ( other instanceof Table ) {
            Table o = (Table)other;
            result = _name.compareTo( o.getName() );
        }
        return result;
    }
    
    /*
    public boolean columnIsPartOfPk(String column) {
        boolean result = false;
        if (_pk != null) {
            result = _pk.columnParticipates(column);
        }
        return result;
    }
    */

    /**
     * @return
     */
    /*
    public PrimaryKey getPk() {
        return _pk;
    }
    */

    /**
     * @param key
     */
    /*
    public void setPk(PrimaryKey key) {
        _pk = key;
    }
    */

}
