/*
 * This is free software, licensed under the Gnu Public License (GPL)
 * get a copy from <http://www.gnu.org/licenses/gpl.html>
 * 
 * author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus.commands;

import java.util.StringTokenizer;
import java.util.Iterator;

import henplus.HenPlus;
import henplus.SQLSession;
import henplus.CommandDispatcher;
import henplus.Command;
import henplus.AbstractCommand;
import henplus.view.util.SortedMatchIterator;

/**
 * document me.
 */
public class HelpCommand extends AbstractCommand {
    final static int INDENT = 42;

    /**
     * returns the command-string this command can handle.
     */
    public String[] getCommandList() { return new String[]{"help", "?"}; }
    
    public boolean requiresValidSession(String cmd) { return false; }

    /**
     * Returns a list of strings that are possible at this stage.
     */
    public Iterator complete(final CommandDispatcher disp,
			     String partialCommand, final String lastWord) 
    {
	// if we already have one arguemnt and try to expand the next: no.
	int argc = argumentCount(partialCommand);
	if (argc > 2 || (argc == 2 && lastWord.length() == 0)) {
	    return null;
	}

	final Iterator it = disp.getRegisteredCommandNames(lastWord);
        return new SortedMatchIterator(lastWord, it) {
                protected boolean exclude(String cmdName) {
                    Command cmd = disp.getCommandFrom(cmdName);
                    return (cmd.getLongDescription(cmdName) == null);
                }
            };
    }

    /**
     * execute the command given.
     */
    public int execute(SQLSession session, String cmdstr, String param) {
	StringTokenizer st = new StringTokenizer(param);
	if (st.countTokens() > 1)
	    return SYNTAX_ERROR;
	param = (st.hasMoreElements()) ? (String) st.nextElement() : null;
	/*
	 * nothing given: provide generic help.
	 */
	if (param == null) {
	    Iterator it = HenPlus.getInstance()
		.getDispatcher().getRegisteredCommands();
	    while (it.hasNext()) {
		Command cmd = (Command) it.next();
		String description = cmd.getShortDescription();
		if (description == null) // no description ..
		    continue;

		StringBuffer cmdPrint = new StringBuffer(" ");
		String[] cmds = cmd.getCommandList();
		String  firstSynopsis = cmd.getSynopsis(cmds[0]);
		/*
		 * either print a list of known commands or the complete
		 * synopsis, if there is only one command.
		 */
		if (cmds.length > 1 || firstSynopsis == null) {
		    for (int i = 0; i < cmds.length; ++i) {
			if (i != 0) cmdPrint.append(" | ");
			cmdPrint.append(cmds[i]);
		    }
		}
		else {
                    cmdPrint.append(firstSynopsis.length() < INDENT ? 
                                    firstSynopsis : cmds[0]);
		}
		HenPlus.msg().print(cmdPrint.toString());
		for (int i = cmdPrint.length(); i < INDENT; ++i) {
		    HenPlus.msg().print(" ");
		}
		HenPlus.msg().print(": ");
		HenPlus.msg().println(description);
	    }
	    HenPlus.msg().println("config read from [" 
                                  + HenPlus.getInstance().getConfigDir() + "]");
	}
	else {
	    CommandDispatcher disp = HenPlus.getInstance().getDispatcher();
	    String cmdString = disp.getCommandNameFrom(param);
	    Command c = disp.getCommandFrom(param);
	    if (c == null) {
		HenPlus.msg().println("Help: unknown command '"+param+"'");
		return EXEC_FAILED;
	    }
	    printDescription(cmdString, c);
	}
	return SUCCESS;
    }

    private void printDescription(String cmdStr, Command c) {
	String desc = c.getLongDescription(cmdStr);
	if (desc == null) {
	    if (c.getShortDescription() != null) {
		desc = "\t[short description]: " + c.getShortDescription();
	    }
	}
	String synopsis = c.getSynopsis(cmdStr);
	
	if (synopsis != null) {
            HenPlus.msg().attributeBold();
	    HenPlus.msg().println("SYNOPSIS");
            HenPlus.msg().attributeReset();
	    HenPlus.msg().println("\t" + synopsis);
	    HenPlus.msg().println();
	}
	if (desc != null) {
            HenPlus.msg().attributeBold();
	    HenPlus.msg().println("DESCRIPTION");
            HenPlus.msg().attributeReset();
	    HenPlus.msg().println(desc);
	    if (c.requiresValidSession(cmdStr)) {
		HenPlus.msg().println("\tRequires valid session.");
	    }
	}
	if (desc == null && synopsis == null) {
	    HenPlus.msg().println("no detailed help for '" + cmdStr + "'");
	}
    }

    /**
     * return a descriptive string.
     */
    public String getShortDescription() { return "provides help for commands";}
    
    public String getSynopsis(String cmd) { return cmd + " [command]"; }

    public String getLongDescription(String cmd) {
	String dsc;
	dsc="\tProvides help for the given command.   If invoked without a\n"+
	    "\tcommand name as parameter, a list of all available commands\n"+
	    "\tis shown.";
	return dsc;
    }
}

/*
 * Local variables:
 * c-basic-offset: 4
 * compile-command: "ant -emacs -find build.xml"
 * End:
 */
