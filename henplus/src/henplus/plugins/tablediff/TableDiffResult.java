/*
 * This is free software, licensed under the Gnu Public License (GPL)
 * get a copy from <http://www.gnu.org/licenses/gpl.html>
 */
package henplus.plugins.tablediff;

import henplus.sqlmodel.Column;
import henplus.sqlmodel.Table;
import henplus.util.ListMap;

import java.util.SortedSet;
import java.util.TreeSet;

/**
 * <p>Title: TableDiffResult</p>
 * <p>Description: Represents the result of two diffed tables.<br>
 * Created on: 24.07.2003</p>
 * @version $Id: TableDiffResult.java,v 1.3 2004-03-07 14:22:02 hzeller Exp $ 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public final class TableDiffResult {    
    private Table _table;
    private SortedSet/*<Column>*/ _removedColumns;
    private SortedSet/*<Column>*/ _addedColumns;
    private ListMap/*<Column, Column>*/ _modifiedColumns; // key: reference, value: modified
    
    public TableDiffResult() {
    }
    
    public boolean hasDiffs() {
        return (_addedColumns != null
                || _removedColumns != null
                || _modifiedColumns != null);
    }

    public SortedSet getAddedColumns() {
        return _addedColumns;
    }
    
    public boolean addAddedColumn(Column column) {
        if ( _addedColumns == null )
            _addedColumns = new TreeSet();
        return _addedColumns.add( column );
    }

    public ListMap getModifiedColumns() {
        return _modifiedColumns;
    }
    
    public Object putModifiedColumns(Column reference, Column modified) {
        if ( _modifiedColumns == null )
            _modifiedColumns = new ListMap();
        return _modifiedColumns.put(reference, modified);
    }

    public SortedSet getRemovedColumns() {
        return _removedColumns;
    }
    
    public boolean addRemovedColumn(Column column) {
        if ( _removedColumns == null )
            _removedColumns = new TreeSet();
        return _removedColumns.add( column );
    }
}
