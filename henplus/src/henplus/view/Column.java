/*
 * This is free software, licensed under the Gnu Public License (GPL)
 * get a copy from <http://www.gnu.org/licenses/gpl.html>
 * $Id: Column.java,v 1.3 2004-03-06 15:37:18 hzeller Exp $ 
 * author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus.view;

import java.util.StringTokenizer;

/**
 * One column in the table.
 */
public class Column {
    
    private final static String NULL_TEXT = "[NULL]";
    private final static int NULL_LENGTH = NULL_TEXT.length();

    private final String columnText[]; // multi-rows
    private int width;
    private int pos;

    public Column(long value) {
        this(String.valueOf(value));
    }

    public Column(String text) {
        if (text == null) {
            width = NULL_LENGTH;
            columnText = null;
        }
        else {
            width = 0;
            StringTokenizer tok = new StringTokenizer(text, "\n");
            columnText = new String[tok.countTokens()];
            for (int i = 0; i < columnText.length; ++i) {
                String line = (String)tok.nextElement();
                int lWidth = line.length();
                columnText[i] = line;
                if (lWidth > width) {
                    width = lWidth;
                }
            }
        }
        pos = 0;
    }

    // package private methods for the table renderer.
    int getWidth() {
        return width;
    }

    boolean hasNextLine() {
        return (columnText != null && pos < columnText.length);
    }

    boolean isNull() {
        return (columnText == null);
    }

    String getNextLine() {
        String result = "";
        if (columnText == null) {
            if (pos == 0)
                result = NULL_TEXT;
        }
        else if (pos < columnText.length) {
            result = columnText[pos];
        }
        ++pos;
        return result;
    }
}

/*
 * Local variables:
 * c-basic-offset: 4
 * compile-command: "ant -emacs -find build.xml"
 * End:
 */
