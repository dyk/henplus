/*
 * This is free software, licensed under the Gnu Public License (GPL)
 * get a copy from <http://www.gnu.org/licenses/gpl.html>
 */
package henplus.sqlmodel;

/**
 * <p>Title: ColumnFkInfo</p>
 * <p>Description:<br>
 * Created on: 01.08.2003</p>
 * @version $Id: ColumnFkInfo.java,v 1.2 2004-01-27 18:16:33 hzeller Exp $ 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public final class ColumnFkInfo {
        
    private String _fkName;
    private String _pkTable;
    private String _pkColumn;
        
    public ColumnFkInfo(String fkName, String pkTable, String pkColumn) {
        _fkName = fkName;
        _pkTable = pkTable;
        _pkColumn = pkColumn;
    }

    /**
     * @return
     */
    public String getFkName() {
        return _fkName;
    }
    
    public boolean equals (Object other) {
        if (other != null && other instanceof ColumnFkInfo) {
            ColumnFkInfo o = (ColumnFkInfo)other;
            if ( _fkName != null && !_fkName.equals(o.getFkName())
                    || _fkName == null && o.getFkName() != null ) {
                return false;
            }
            else if ( _pkTable != null && !_pkTable.equals(o.getPkTable())
                    || _pkTable == null && o.getPkTable() != null ) {
                return false;
            }
            else if ( _pkColumn != null && !_pkColumn.equals(o.getPkColumn())
                    || _pkColumn == null && o.getPkColumn() != null ) {
                return false;
            }
            else {
                return true;
            }
        }
        return false;
    }

    /**
     * @return
     */
    public String getPkColumn() {
        return _pkColumn;
    }

    /**
     * @return
     */
    public String getPkTable() {
        return _pkTable;
    }

}
