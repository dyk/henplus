/*
 * This is free software, licensed under the Gnu Public License (GPL)
 * get a copy from <http://www.gnu.org/licenses/gpl.html>
 * $Id: TerminalOutputDevice.java,v 1.1 2004-02-01 14:12:52 hzeller Exp $
 * author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus;

import java.io.PrintStream;

/**
 * The OutputDevice to write to.
 */
public class TerminalOutputDevice extends PrintStreamOutputDevice {
    private static final String BOLD   = "\033[1m";
    private static final String NORMAL = "\033[m";
    private static final String GREY   = "\033[1;30m";

    public TerminalOutputDevice(PrintStream out) {
        super(out);
    }

    public void attributeBold()  { 
        print( BOLD );
    }
    public void attributeGrey()  { 
        print( GREY );
    }

    public void attributeReset() { 
        print( NORMAL );
    }
}
